package sonnik.app

import android.content.Context
import android.util.Log
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import sonnik.core.Heard
import sonnik.core.HeuristicClassifier

/** Listens to a stretch of sound: the network's label scores per window plus the rules' guess. */
fun interface SoundClassifier : AutoCloseable {
    fun listen(audio: FloatArray, sampleRate: Int): Heard
    override fun close() {}
}

/** Rules only: used in tests and when the neural model cannot run on a phone. */
val heuristicClassifier = SoundClassifier { audio, rate -> Heard(emptyList(), HeuristicClassifier.classify(audio, rate)) }

/**
 * YAMNet (AudioSet, 521 sound classes) through TensorFlow Lite. The sound is cut into ~1 s windows
 * every 0.5 s and each window's scores are kept; [Heard] decides what it was.
 */
class YamnetClassifier private constructor(private val model: AudioClassifier) : SoundClassifier {

    override fun listen(audio: FloatArray, sampleRate: Int): Heard {
        val rules = HeuristicClassifier.classify(audio, sampleRate)
        val x = if (sampleRate == RATE) audio else resample(audio, sampleRate, RATE)
        val windows = ArrayList<Map<String, Float>>()
        val tensor = model.createInputTensorAudio()
        val window = FloatArray(WINDOW)
        var start = 0
        do {
            window.fill(0f)
            val end = minOf(start + WINDOW, x.size)
            if (end > start) x.copyInto(window, 0, start, end)
            tensor.load(window)
            val scores = HashMap<String, Float>()
            for (c in model.classify(tensor).firstOrNull()?.categories.orEmpty()) {
                // The 521 scores of a window are mostly near zero; a long clip has hundreds of windows.
                if (c.score >= 0.02f) scores.merge(c.label, c.score) { a, b -> maxOf(a, b) }
            }
            windows += scores
            start += HOP
        } while (start + WINDOW / 2 < x.size)
        return Heard(windows, rules)
    }

    override fun close() = model.close()

    companion object {
        private const val TAG = "Sonnik"
        private const val RATE = 16000
        private const val WINDOW = 15600 // 0.975 s, YAMNet's input
        private const val HOP = 8000

        /** The YAMNet classifier, or rules only if the model cannot be loaded on this phone. */
        fun createOrFallback(ctx: Context): SoundClassifier = try {
            YamnetClassifier(AudioClassifier.createFromFile(ctx, "yamnet.tflite"))
        } catch (t: Throwable) { // includes UnsatisfiedLinkError on unsupported CPUs
            Log.w(TAG, "Sound classifier unavailable, using rules", t)
            EventLog.add(ctx, "Нейросеть распознавания звуков не запустилась на этом телефоне, работают простые правила")
            heuristicClassifier
        }

        private fun resample(a: FloatArray, from: Int, to: Int): FloatArray {
            val n = (a.size.toLong() * to / from).toInt()
            return FloatArray(n) { i ->
                val pos = i.toDouble() * from / to
                val j = pos.toInt()
                val f = (pos - j).toFloat()
                val x0 = a[minOf(j, a.size - 1)]
                val x1 = a[minOf(j + 1, a.size - 1)]
                x0 + (x1 - x0) * f
            }
        }
    }
}
