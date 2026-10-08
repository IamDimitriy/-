package sonnik.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, WAITING, RECORDING }

data class RecorderState(
    val phase: Phase = Phase.IDLE,
    /** When saving starts (epoch ms). Before it the microphone is on but nothing is kept. */
    val saveFrom: Long = 0,
    val stopAt: Long = 0,
    /** Phrases saved so far. */
    val clips: Int = 0,
    /** Other saved sounds: snoring samples, creaks, the street. */
    val sounds: Int = 0,
    val lastClipAt: Long = 0,
    /** 0..1, how loud the room is relative to the trigger level. */
    val level: Float = 0f,
    val calibrated: Boolean = false,
    val snoreMinutes: Int = 0,
    /** When the alarm will ring at the latest (epoch ms), or 0 when it is off. */
    val alarmAt: Long = 0,
    val alarmWindow: Int = 0,
)

/** Process-wide view of the recording service, observed by the UI. */
object Recorder {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    internal fun update(fn: (RecorderState) -> RecorderState) = _state.update(fn)
    internal fun reset() { _state.value = RecorderState() }

    /** [now] = keep everything from this moment; otherwise wait for the night window to begin. */
    fun start(ctx: Context, now: Boolean, demoWav: String? = null) {
        val i = Intent(ctx, RecorderService::class.java).putExtra(RecorderService.EXTRA_NOW, now)
        if (demoWav != null) i.putExtra(RecorderService.EXTRA_DEMO_WAV, demoWav)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
    }
}
