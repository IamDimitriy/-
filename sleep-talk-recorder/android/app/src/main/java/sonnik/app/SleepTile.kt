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
 * Quick Settings tile that starts or stops the sleep recording from the notification shade.
 * Android turns the microphone on only for an app that is on screen, so starting goes through
 * [WakeActivity]; stopping needs no screen.
 */
class SleepTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh(Recorder.state.value.phase != Phase.IDLE)
    }

    override fun onClick() {
        super.onClick()
        if (Recorder.state.value.phase != Phase.IDLE) {
            Log.i("Sonnik", "Tile tapped, stopping the recorder")
            Recorder.stop(this)
            // The service stops a moment later; show the result right away.
            refresh(false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // No microphone access yet: the night screen shows what to allow.
            showScreen(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORDS, false), 51)
        } else {
            Log.i("Sonnik", "Tile tapped, opening the wake screen")
            showScreen(Intent(this, WakeActivity::class.java).putExtra(WakeActivity.EXTRA_NOW, true), 50)
        }
        refresh(false)
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

    private fun refresh(active: Boolean) {
        qsTile?.let {
            it.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "Сонник"
            if (Build.VERSION.SDK_INT >= 29) it.subtitle = if (active) "Слушаю" else "Запись сна"
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
