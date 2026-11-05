package app.grammovka.ui.today

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Voice input through the platform recognizer, ru-RU, with partial results shown in the text field.
 * The final text goes into the same parser as typed input (spec, phase 2).
 *
 * API: https://developer.android.com/reference/android/speech/SpeechRecognizer
 * (main thread only; on Android 11+ the manifest declares a `<queries>` entry for RecognitionService).
 */
class VoiceController(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { partial = it }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                partial = ""
                val text = best(results)
                if (text.isNullOrBlank()) onError("Не расслышал. Попробуйте ещё раз.") else onFinal(text)
            }

            override fun onError(error: Int) {
                listening = false
                partial = ""
                onError(message(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        partial = ""
        r.startListening(intent)
    }

    /** Stops recording; the recognizer still delivers the final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал. Попробуйте ещё раз."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ничего не услышал."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Распознаванию речи нужна сеть. Введите текстом."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято. Попробуйте через секунду."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Русский язык для распознавания не установлен на телефоне."
        else -> "Не получилось распознать речь (код $error)."
    }
}

@Composable
fun rememberVoiceController(): VoiceController {
    val context = LocalContext.current
    val c = remember { VoiceController(context.applicationContext) }
    val current by rememberUpdatedState(c)
    DisposableEffect(Unit) { onDispose { current.release() } }
    return c
}
