package sonnik.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Speech to text for telling a dream. Tests use a fake. */
interface Dictation {
    val available: Boolean

    /** [onPartial] shows the words of the current utterance; [onFinal] adds a finished one to the text. */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onStopped: (error: String?) -> Unit)
    fun stop()
}

/**
 * Android's speech recogniser (Russian, offline when the phone has the language pack).
 * The recogniser ends after every pause, so it is restarted until the user taps stop:
 * a dream is told with plenty of pauses.
 */
class SpeechDictation(private val ctx: Context) : Dictation {
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onStopped: (String?) -> Unit) {
        stop()
        wanted = true
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)

        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle) {
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onResults(b: Bundle) {
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onFinal)
                onPartial("")
                if (wanted) r.startListening(intent) else onStopped(null)
            }

            override fun onError(error: Int) {
                onPartial("")
                // A pause with no words is not a problem: just listen again.
                val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (wanted && silence) {
                    r.startListening(intent)
                } else {
                    wanted = false
                    Log.w("Sonnik", "Dictation error $error")
                    onStopped(if (silence) null else errorText(error))
                }
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(type: Int, p: Bundle?) {}
        })
        r.startListening(intent)
    }

    override fun stop() {
        wanted = false
        recognizer?.let {
            runCatching { it.stopListening() }
            it.destroy()
        }
        recognizer = null
    }

    private fun errorText(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Распознаванию речи нужен интернет или русский пакет для офлайн-режима (Настройки → Язык и ввод)."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят. Попробуйте ещё раз."
        else -> "Распознавание речи остановилось. Можно продолжить или дописать текстом."
    }
}
