package sonnik.app

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile for the night: starts the recording; while it waits for sleep, a tap says
 * "we are asleep"; once asleep, a tap stops it. Android turns the microphone on only for an app
 * that is on screen, so starting goes through [WakeActivity]; the rest needs no screen.
 */
class SleepTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val s = Recorder.state.value
        if (s.waitingForSleep) {
            // Recording, still awake: the tap says "we are asleep now".
            Log.i("Sonnik", "Tile tapped: asleep")
            Recorder.asleep(this)
            refresh(asleep = true)
            return
        }
        if (s.phase != Phase.IDLE) {
            Log.i("Sonnik", "Tile tapped, stopping the recorder")
            Recorder.stop(this)
            // The service stops a moment later; show the result right away.
            refresh(stopped = true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // No microphone access yet: the night screen shows what to allow.
            showScreen(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORDS, false), 51)
        } else {
            Log.i("Sonnik", "Tile tapped, opening the wake screen")
            showScreen(Intent(this, WakeActivity::class.java).putExtra(WakeActivity.EXTRA_NOW, true), 50)
        }
        refresh()
    }

    private fun showScreen(intent: Intent, code: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, code, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** Off: "Запись сна"; recording, still awake: "Мы спим?" (a tap says so); asleep: "Слушаю". */
    private fun refresh(asleep: Boolean = false, stopped: Boolean = false) {
        val s = Recorder.state.value
        val active = s.phase != Phase.IDLE && !stopped
        val awake = active && s.waitingForSleep && !asleep
        qsTile?.let {
            it.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "Сонник"
            if (Build.VERSION.SDK_INT >= 29) it.subtitle = when {
                !active -> "Запись сна"
                awake -> "Мы спим?"
                else -> "Слушаю"
            }
            it.updateTile()
        }
    }

    companion object {
        /** Recording started or stopped elsewhere: ask Android to redraw the tile if it is shown. */
        fun update(ctx: Context) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, SleepTile::class.java)) }
        }
    }
}
