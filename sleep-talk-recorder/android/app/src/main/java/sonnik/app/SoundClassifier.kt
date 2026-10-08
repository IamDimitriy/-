package sonnik.app

import android.content.Context
import android.util.Log
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import sonnik.core.HeuristicClassifier
import sonnik.core.SoundClass
import sonnik.core.SoundLabels
import sonnik.core.combine

/** Decides what a saved clip is: speech, snoring, a door, a car... */
fun interface SoundClassifier : AutoCloseable {
    fun classify(audio: FloatArray, sampleRate: Int): SoundClass
    override fun close() {}
}

/** Rules only: used in tests and when the neural model cannot run on a phone. */
val heuristicClassifier = SoundClassifier { audio, rate -> HeuristicClassifier.classify(audio, rate) }

/**
 * YAMNet (AudioSet, 521 sound classes) through TensorFlow Lite. The clip is cut into ~1 s windows
 * every 0.5 s; each label keeps its best score over the clip, then [SoundLabels.classify] maps
 * the labels to the app's categories.
 */
class YamnetClassifier private constructor(private val model: AudioClassifier) : SoundClassifier {

    override fun classify(audio: FloatArray, sampleRate: Int): SoundClass {
        val rules = HeuristicClassifier.classify(audio, sampleRate)
        val x = if (sampleRate == RATE) audio else resample(audio, sampleRate, RATE)
        val scores = HashMap<String, Float>()
        val tensor = model.createInputTensorAudio()
        val window = FloatArray(WINDOW)
        var start = 0
        do {
            window.fill(0f)
            val end = minOf(start + WINDOW, x.size)
            if (end > start) x.copyInto(window, 0, start, end)
            tensor.load(window)
            for (c in model.classify(tensor).firstOrNull()?.categories.orEmpty()) {
                scores.merge(c.label, c.score) { a, b -> maxOf(a, b) }
            }
            start += HOP
        } while (start + WINDOW / 2 < x.size)
        val result = combine(SoundLabels.classify(scores), rules)
        val top = scores.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} %.2f".format(it.value) }
        Log.i(TAG, "Clip: $top -> ${result.title}")
        return result
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
