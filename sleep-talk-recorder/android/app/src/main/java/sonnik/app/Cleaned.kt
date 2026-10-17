package sonnik.app

import android.content.Context
import sonnik.core.Clean
import sonnik.core.Wav
import java.io.File

/**
 * Copies of the clips made easy to hear (see [Clean]), played instead of the recording while
 * "Громче" is on. They live in the cache, which Android may clear; the recordings stay as they are.
 */
object Cleaned {
    private const val DIR = "clear"
    /** Copies kept at most; the oldest go first. */
    private const val KEEP = 300

    /** The louder, cleaner copy of [clip], made now if it is missing or older than the clip. */
    fun file(ctx: Context, clip: File): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val out = File(dir, name(clip))
        if (out.exists() && out.lastModified() >= clip.lastModified()) return out
        val (audio, rate) = read(clip)
        Wav.write(out, Clean.voice(audio, rate), rate)
        prune(dir)
        return out
    }

    /** The clip was deleted: its copy goes too. */
    fun forget(ctx: Context, clip: File) {
        File(File(ctx.cacheDir, DIR), name(clip)).delete()
    }

    private fun name(clip: File) = "${clip.parentFile?.name}_${clip.name}"

    private fun read(f: File): Pair<FloatArray, Int> = Wav.Reader(f.inputStream()).use { r ->
        var audio = FloatArray(r.sampleRate * 4)
        var n = 0
        val buf = ShortArray(4096)
        while (true) {
            val got = r.read(buf)
            if (got < 0) break
            if (n + got > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, n + got))
            for (i in 0 until got) audio[n++] = buf[i] / 32768f
        }
        audio.copyOf(n) to r.sampleRate
    }

    private fun prune(dir: File) {
        val files = dir.listFiles().orEmpty()
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
    }
}
