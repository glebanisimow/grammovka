package app.grammovka.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import java.io.ByteArrayOutputStream
import kotlin.math.max

const val MAX_PHOTOS = 4

/** «Без таблицы»: up to four shots (food, cut, package), an optional hint, then one estimate. */
@Composable
fun PhotoPanel(
    shots: List<ByteArray>,
    hint: String,
    onHint: (String) -> Unit,
    onShoot: () -> Unit,
    onGallery: () -> Unit,
    onRemove: (Int) -> Unit,
    onEstimate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Снимите сам продукт, разрез и упаковку: до $MAX_PHOTOS фото. Claude определит, что это, а цифры возьмёт из баз.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        if (shots.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(shots) { i, jpeg -> Thumb(jpeg) { onRemove(i) } }
            }
        }
        OutlinedTextField(
            hint, onHint, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Подсказка, необязательно") }, placeholder = { Text("ржаная, с семечками") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShoot, enabled = shots.size < MAX_PHOTOS, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) {
                Text("Снять · ${shots.size}/$MAX_PHOTOS")
            }
            OutlinedButton(onClick = onGallery, enabled = shots.size < MAX_PHOTOS, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) {
                Text("Из галереи")
            }
        }
        Button(onClick = onEstimate, enabled = shots.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            Text(if (shots.isEmpty()) "Оценить" else "Оценить по ${shots.size} фото")
        }
    }
}

@Composable
private fun Thumb(jpeg: ByteArray, onRemove: () -> Unit) {
    val bmp = remember(jpeg) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
    }
    Box(Modifier.size(72.dp)) {
        bmp?.let { Image(it, null, Modifier.size(72.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop) }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), CircleShape),
        ) {
            Icon(GIcons.Close, "Убрать фото", Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

/** Gallery image → upright (ImageDecoder applies EXIF), long side ≤ 1568 px, JPEG 85. */
fun uriToScaledJpeg(context: Context, uri: Uri): ByteArray {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val long = max(info.size.width, info.size.height)
        if (long > MAX_EDGE_PX) {
            val k = MAX_EDGE_PX.toFloat() / long
            decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}

/** Claude vision standard tier: images above ~1568 px on the long side are downscaled by the API anyway. */
const val MAX_EDGE_PX = 1568
