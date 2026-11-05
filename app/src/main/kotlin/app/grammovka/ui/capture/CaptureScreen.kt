package app.grammovka.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.grammovka.data.ai.ClaudeException
import app.grammovka.data.ai.LabelResult
import app.grammovka.data.ai.userMessage
import app.grammovka.data.net.NetException
import app.grammovka.data.net.NetProblem
import app.grammovka.data.repo.FoodRepository.Companion.toDomain
import app.grammovka.di.AppContainer
import app.grammovka.domain.log.LogPlan
import app.grammovka.domain.log.PlanItem
import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.parse.AskReason
import app.grammovka.domain.parse.Fragment
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.review.ReviewRequest
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.max

enum class CaptureMode { BARCODE, LABEL }

private sealed interface CaptureState {
    data object Scanning : CaptureState
    data class Busy(val text: String) : CaptureState
    data class NoNutrition(val code: String, val name: String?) : CaptureState
    data class Error(val text: String) : CaptureState
}

/**
 * Barcode: CameraX + ML Kit bundled model, on-device (https://developers.google.com/ml-kit/vision/barcode-scanning/android,
 * https://developer.android.com/media/camera/camerax). Order: local catalog → Open Food Facts → offer a label photo.
 * Label: ImageCapture → downscale to 1568 px → Claude LABEL → confirm screen.
 */
@Composable
fun CaptureScreen(
    container: AppContainer,
    date: LocalDate,
    initialMode: CaptureMode,
    onReview: (ReviewRequest) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var mode by remember { mutableStateOf(initialMode) }
    var state by remember { mutableStateOf<CaptureState>(CaptureState.Scanning) }
    var pendingBarcode by remember { mutableStateOf<String?>(null) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    fun onBarcode(code: String) {
        if (state != CaptureState.Scanning || mode != CaptureMode.BARCODE) return
        state = CaptureState.Busy("Штрихкод $code: ищу…")
        scope.launch {
            container.foodRepository.byBarcode(code)?.let { f ->
                val food = f.toDomain()
                onReview(ReviewRequest(date, InputMethod.BARCODE, LogPlan(listOf(PlanItem.NeedsGrams(Fragment(food.name, food.name), food, AskReason.NO_QUANTITY)))))
                return@launch
            }
            try {
                val p = container.offClient.product(code)
                if (p?.per100 != null) {
                    val draft = FoodDraft(
                        name = p.name ?: "", brand = p.brand, per100 = p.per100, basis = FoodBasis.AS_SOLD,
                        source = FoodSource.OFF, sourceRef = code, barcode = code,
                    )
                    onReview(ReviewRequest(date, InputMethod.BARCODE, drafts = listOf(draft to "Open Food Facts, штрихкод $code${p.quantity?.let { ", упаковка $it" } ?: ""}")))
                } else {
                    state = CaptureState.NoNutrition(code, p?.name)
                }
            } catch (e: NetException) {
                state = CaptureState.Error(
                    if (e.problem == NetProblem.RATE_LIMIT) "Open Food Facts ограничил частоту запросов. Повторите через минуту."
                    else "Продукта $code нет в каталоге, а Open Food Facts недоступен. Проверьте интернет или введите вручную.",
                )
            }
        }
    }

    fun takeLabel() {
        state = CaptureState.Busy("Снимаю…")
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = image.use { toScaledJpeg(it) }
                    state = CaptureState.Busy("Claude читает этикетку…")
                    scope.launch {
                        try {
                            when (val r = container.aiService.label(jpeg, pendingBarcode)) {
                                is LabelResult.Found -> onReview(
                                    ReviewRequest(date, InputMethod.LABEL, drafts = listOf(r.draft to r.note)),
                                )
                                is LabelResult.NotFound -> state = CaptureState.Error(
                                    (r.note?.let { "$it. " } ?: "") + "Таблица пищевой ценности не читается. Снимите ближе и ровнее или введите вручную.",
                                )
                            }
                        } catch (e: ClaudeException) {
                            state = CaptureState.Error(e.userMessage())
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    state = CaptureState.Error("Камера не сделала снимок. Повторите.")
                }
            },
        )
    }

    fun manual(code: String?) {
        val draft = FoodDraft(name = "", per100 = Nutrients(0.0, 0.0, 0.0, 0.0), source = FoodSource.MANUAL, barcode = code)
        onReview(ReviewRequest(date, InputMethod.BARCODE, drafts = listOf(draft to "Введите данные с упаковки.")))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(mode, imageCapture, onBarcode = ::onBarcode)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, "Назад", tint = Color.White) }
            Text(
                if (mode == CaptureMode.BARCODE) "Наведите на штрихкод" else "Снимите таблицу пищевой ценности",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
        if (mode == CaptureMode.BARCODE && state == CaptureState.Scanning) {
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(160.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val s = state) {
                CaptureState.Scanning -> {
                    if (!granted) {
                        Text("Нужен доступ к камере для сканера и фото этикеток.")
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, shape = MaterialTheme.shapes.small) { Text("Разрешить камеру") }
                    } else if (mode == CaptureMode.LABEL) {
                        Button(onClick = ::takeLabel, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Снять этикетку") }
                    }
                    OutlinedButton(
                        onClick = { mode = if (mode == CaptureMode.BARCODE) CaptureMode.LABEL else CaptureMode.BARCODE },
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                    ) { Text(if (mode == CaptureMode.BARCODE) "Фото этикетки" else "Сканер штрихкода") }
                }
                is CaptureState.Busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(s.text, Modifier.padding(start = 12.dp))
                }
                is CaptureState.NoNutrition -> {
                    Text("${s.name ?: "Продукт ${s.code}"}: в Open Food Facts нет пищевой ценности. Сфотографируйте таблицу на упаковке.")
                    Button(onClick = { pendingBarcode = s.code; mode = CaptureMode.LABEL; state = CaptureState.Scanning }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                        Text("Сфотографировать таблицу")
                    }
                    OutlinedButton(onClick = { manual(s.code) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Ввести вручную") }
                }
                is CaptureState.Error -> {
                    Text(s.text, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { state = CaptureState.Scanning }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Повторить") }
                    OutlinedButton(onClick = { manual(pendingBarcode) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Ввести вручную") }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(mode: CaptureMode, imageCapture: ImageCapture, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
    }
    DisposableEffect(mode) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            if (mode == CaptureMode.BARCODE) {
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy, scanner, onBarcode) }
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } else {
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { providerFuture.get().unbindAll() } }
    }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}

@OptIn(ExperimentalGetImage::class)
private fun analyze(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner, onBarcode: (String) -> Unit) {
    val media = proxy.image ?: return proxy.close()
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { codes ->
            codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.all(Char::isDigit) } }?.let { code ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { onBarcode(code) }
            }
        }
        .addOnCompleteListener { proxy.close() }
}

/** Rotates upright and scales so the long side is at most 1568 px (Claude vision standard tier), JPEG 85. */
private fun toScaledJpeg(image: ImageProxy): ByteArray {
    var bmp = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    if (rotation != 0) {
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
    val long = max(bmp.width, bmp.height)
    if (long > MAX_EDGE) {
        val k = MAX_EDGE.toFloat() / long
        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
    }
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}

private const val MAX_EDGE = 1568

