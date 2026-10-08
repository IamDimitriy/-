package sonnik.app

import android.content.Context
import android.util.Log
import sonnik.core.Retention
import java.time.LocalDateTime

/**
 * Deletes clips that [Retention] no longer keeps: snoring and other sounds older than a month.
 * Phrases stay until the user deletes them. Only clip files are removed, never a night's folder
 * or its activity.csv, so the night and its graphs stay in the list.
 */
object Cleanup {
    private const val TAG = "Sonnik"

    /** Deletes expired clips and returns how many were deleted. Reads the disk: not for the main thread. */
    fun run(ctx: Context, now: LocalDateTime = LocalDateTime.now()): Int {
        var deleted = 0
        var bytes = 0L
        for (night in Nights.list(ctx)) {
            for (clip in night.clips) {
                if (Retention.keep(clip.sound.kind, clip.at, now)) continue
                val size = clip.file.length()
                if (clip.file.delete()) {
                    deleted++
                    bytes += size
                } else {
                    Log.w(TAG, "Cleanup: cannot delete ${clip.file}")
                }
            }
        }
        Log.i(TAG, "Cleanup: deleted $deleted sound clips older than ${Retention.OTHER_DAYS} days, ${bytes / 1024} KB")
        return deleted
    }
}
