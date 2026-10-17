package sonnik.core

/**
 * What the classifier heard in a stretch of sound: the neural network's label scores for each
 * ~1 s window ([windows] is empty when the network is not available) and the rule-based guess.
 *
 * Decides between a sure phrase, an unclear one and no voice at all. A night of white noise has
 * hundreds of windows, and the network now and then gives one of them a modest "Speech" score;
 * taking the best window of a long clip turned that into a "phrase". So the confidence is the
 * mean of the two most speech-like windows of a short, already voice-trimmed piece: a single
 * spike is not enough.
 */
class Heard(val windows: List<Map<String, Float>>, val rules: SoundClass) {

    /** How sure it is that this is a voice, 0..1. */
    val speech: Float by lazy {
        if (windows.isEmpty()) return@lazy if (rules.kind == SoundKind.SPEECH) RULES_SPEECH else 0f
        val best = windows.map { w -> w.filterKeys { SoundLabels.isSpeech(it) }.values.maxOrNull() ?: 0f }
            .sortedDescending()
        if (best.size == 1) best[0] else (best[0] + best[1]) / 2
    }

    /** [SoundKind.SPEECH] for a sure phrase, [SoundKind.UNSURE] for an unclear one, null for no voice. */
    val voice: SoundKind? get() = when {
        speech >= SURE -> SoundKind.SPEECH
        speech >= MAYBE -> SoundKind.UNSURE
        else -> null
    }

    /** The voice with its detail (whisper, shout, laughter) when the network names one. */
    val voiceClass: SoundClass? get() = when (voice) {
        SoundKind.SPEECH -> best.entries.filter { SoundLabels.isSpeech(it.key) }.maxByOrNull { it.value }
            ?.let { SoundLabels.classOf(it.key) } ?: SoundClass(SoundKind.SPEECH)
        SoundKind.UNSURE -> SoundClass(SoundKind.UNSURE)
        else -> null
    }

    /**
     * What a sound that is not a voice is (snoring, a door, a car...), or null for silence and
     * steady noise: those are never kept. Without the network only the rules' snoring counts.
     */
    val sound: SoundClass? get() {
        val model = if (windows.isEmpty()) null else SoundLabels.nonSpeech(best, minScore = SAMPLE_MIN)
        return combine(model, rules)?.takeIf { it.kind != SoundKind.SPEECH && it.kind != SoundKind.UNSURE }
    }

    /** Each label's best score over the windows. */
    val best: Map<String, Float> by lazy {
        val m = HashMap<String, Float>()
        for (w in windows) for ((k, v) in w) m.merge(k, v) { a, b -> maxOf(a, b) }
        m
    }

    /** "Speech 0.91, Silence 0.20, Breathing 0.12" for logs and the night report. */
    fun top(n: Int = 3): String =
        if (windows.isEmpty()) "правила: ${rules.title}"
        else best.entries.sortedByDescending { it.value }.take(n).joinToString { "${it.key} %.2f".format(java.util.Locale.ROOT, it.value) }

    companion object {
        /** A sure phrase. */
        const val SURE = 0.5f
        /** Below this it is not a voice at all; between the two, "unclear". */
        const val MAYBE = 0.2f
        /** How sure the rules alone are of speech (the network could not run on this phone). */
        const val RULES_SPEECH = 0.6f
        /** A non-speech label must be at least this sure to keep a sample of it. */
        const val SAMPLE_MIN = 0.3f
    }
}
