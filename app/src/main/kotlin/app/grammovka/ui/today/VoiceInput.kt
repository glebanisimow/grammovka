package app.grammovka.ui.today

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    /** Text recognized so far in this session: finished segments plus the current partial result. */
    var partial by mutableStateOf("")
        private set

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Finished segments of the current session. */
    private val segments = mutableListOf<String>()
    private var stopRequested = false
    private var onFinal: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts a dictation session. The platform recognizer ends a segment on the first short pause and often
     * ignores the silence-length extras, so the session restarts it after every segment and joins the texts.
     * The session ends when the user taps stop, or when a segment hears nothing after some text was already said.
     */
    fun start(onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (listening) return
        this.onFinal = onFinal
        this.onError = onError
        segments.clear()
        stopRequested = false
        listening = true
        partial = ""
        listenSegment()
    }

    private fun listenSegment() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.takeIf { it.isNotBlank() }?.let { partial = joined(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = best(results)
                if (!text.isNullOrBlank()) segments += text.trim()
                partial = joined(null)
                if (stopRequested || text.isNullOrBlank() && segments.isNotEmpty()) finish() else restart()
            }

            override fun onError(error: Int) {
                val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                when {
                    // Stopped by the user, or a long pause after something was said: the session is over.
                    segments.isNotEmpty() && (stopRequested || silence || error == SpeechRecognizer.ERROR_CLIENT) -> finish()
                    stopRequested && silence -> fail("Не расслышал. Попробуйте ещё раз.")
                    else -> fail(message(error))
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Hints only: many recognizer versions ignore them, the restart loop covers that.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
        r.startListening(intent)
    }

    /** Short delay: an immediate restart often gets ERROR_RECOGNIZER_BUSY. */
    private fun restart() {
        handler.postDelayed({ if (listening && !stopRequested) listenSegment() else if (listening) finish() }, 120)
    }

    private fun finish() {
        if (!listening) return
        listening = false
        partial = ""
        val text = segments.joinToString(" ")
        segments.clear()
        if (text.isBlank()) onError("Не расслышал. Попробуйте ещё раз.") else onFinal(text)
    }

    private fun fail(msg: String) {
        if (!listening) return
        listening = false
        partial = ""
        segments.clear()
        onError(msg)
    }

    private fun joined(current: String?): String = (segments + listOfNotNull(current)).joinToString(" ")

    /** Stops recording; the recognizer still delivers the last segment, then the whole text goes to the parser. */
    fun stop() {
        if (!listening) return
        stopRequested = true
        recognizer?.stopListening()
        // Some recognizers deliver nothing after stopListening; do not leave the session hanging.
        handler.postDelayed({ if (listening) finish() }, 2500)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        listening = false
        segments.clear()
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
