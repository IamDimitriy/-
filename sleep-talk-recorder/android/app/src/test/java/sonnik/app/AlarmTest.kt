package sonnik.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sonnik.core.SmartWake
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val prefs = Prefs(ctx)

    @Before fun setUp() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(ctx)
        Recorder.reset()
    }

    @After fun tearDown() = Recorder.reset()

    private fun alarmNotification(): Notification? = shadowOf(nm).getNotification(Notifications.ALARM_ID)

    /** The last service started or stopped, if it is the alarm melody. */
    private fun alarmServiceStarted() = shadowOf(ctx).nextStartedService?.component?.className == AlarmService::class.java.name
    private fun alarmServiceStopped() = shadowOf(ctx).nextStoppedService?.component?.className == AlarmService::class.java.name

    @Test fun backupAlarmIsSetForTheWakeUpTime() {
        prefs.alarmOn = true
        prefs.alarmMinute = 6 * 60 + 45
        Scheduler.sync(ctx)
        val expected = Alarm.epochMs(SmartWake.nextAlarm(LocalDateTime.now(), prefs.alarmTime))
        assertTrue(shadowOf(am).scheduledAlarms.any { it.triggerAtMs == expected }, "no alarm at the wake-up time")
    }

    @Test fun noAlarmWhenSwitchedOff() {
        prefs.alarmOn = false
        assertNull(Alarm.next(ctx))
    }

    @Test fun backupRingsWithFullScreenAndTheMelodyService() {
        prefs.alarmOn = true
        AlarmRingReceiver().onReceive(ctx, Intent())
        val n = assertNotNull(alarmNotification())
        assertNotNull(n.fullScreenIntent)
        assertEquals(Notifications.CH_RINGING, n.channelId, "silent: the service plays the melody")
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(2, n.actions.size)
        assertTrue(alarmServiceStarted(), "the melody plays at alarm volume, not as a notification sound")
    }

    @Test fun ringingChannelIsSilentSoTheMelodyIsNotDoubled() {
        val ch = nm.getNotificationChannel(Notifications.CH_RINGING)
        assertNull(ch.sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
    }

    @Test fun turningOffStopsTheMelody() {
        prefs.alarmOn = true
        Alarm.ring(ctx, LocalDateTime.now(), smart = false)
        AlarmActionReceiver().onReceive(ctx, Intent(AlarmActionReceiver.ACTION_DISMISS))
        assertTrue(alarmServiceStopped())
    }

    @Test fun fallbackNotificationPlaysTheAlarmSoundItself() {
        val n = Notifications.alarmNotification(ctx, smart = false, sound = true)
        assertEquals("alarm", n.channelId)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0, "sound repeats until turned off")
    }

    @Test fun alarmChannelPlaysAnAlarmSound() {
        val ch = nm.getNotificationChannel("alarm")
        assertNotNull(ch.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, ch.audioAttributes.usage)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
    }

    @Test fun ringsOnlyOncePerMorning() {
        prefs.alarmOn = true
        val at = LocalDateTime.now().withSecond(0).withNano(0)
        Alarm.ring(ctx, at, smart = true)
        nm.cancelAll()
        Alarm.ring(ctx, at, smart = false) // the backup at the wake-up time
        assertNull(alarmNotification())
        assertEquals(at, prefs.rangFor)
        // The next wake-up is tomorrow now.
        assertTrue(Alarm.next(ctx)!!.isAfter(at))
    }

    @Test fun dismissStopsTheSound() {
        prefs.alarmOn = true
        Alarm.ring(ctx, LocalDateTime.now(), smart = false)
        AlarmActionReceiver().onReceive(ctx, Intent(AlarmActionReceiver.ACTION_DISMISS))
        assertNull(alarmNotification())
    }

    @Test fun snoozeRingsAgainInTenMinutes() {
        prefs.alarmOn = true
        Alarm.ring(ctx, LocalDateTime.now(), smart = false)
        val before = System.currentTimeMillis()
        AlarmActionReceiver().onReceive(ctx, Intent(AlarmActionReceiver.ACTION_SNOOZE))
        assertNull(alarmNotification())
        assertTrue(
            shadowOf(am).scheduledAlarms.any { it.triggerAtMs in before + 9 * 60_000..before + 11 * 60_000 },
            "snooze alarm in ~10 minutes",
        )
        AlarmRingReceiver().onReceive(ctx, Intent().putExtra(AlarmRingReceiver.EXTRA_SNOOZE, true))
        assertNotNull(alarmNotification())
    }

    /** Alarm on, with no other alarm (night start, backup) anywhere near the next 10 minutes. */
    private fun alarmHoursAway() {
        prefs.alarmOn = true
        prefs.autoStart = false
        prefs.alarmMinute = (LocalDateTime.now().hour + 3) % 24 * 60
    }

    private fun ringsAgainIn10Min(from: Long) =
        shadowOf(am).scheduledAlarms.any { it.triggerAtMs in from + 9 * 60_000..from + 11 * 60_000 }

    @Test fun ringsAgainUntilTurnedOff() {
        alarmHoursAway()
        val before = System.currentTimeMillis()
        Alarm.ring(ctx, LocalDateTime.now(), smart = true)
        assertTrue(ringsAgainIn10Min(before), "the ringing stops after 10 minutes, so it rings again")
        AlarmActionReceiver().onReceive(ctx, Intent(AlarmActionReceiver.ACTION_DISMISS))
        assertFalse(ringsAgainIn10Min(before), "turned off: no repeat")
        assertNull(alarmNotification())
    }

    @Test fun ringsAtMostThreeTimesInARow() {
        alarmHoursAway()
        val before = System.currentTimeMillis()
        val again = Intent().putExtra(AlarmRingReceiver.EXTRA_SNOOZE, true)
        Alarm.ring(ctx, LocalDateTime.now(), smart = false)
        am.cancel(Alarm.snoozeIntent(ctx)) // the repeat went off
        AlarmRingReceiver().onReceive(ctx, again)
        assertTrue(ringsAgainIn10Min(before), "second ring, one more to come")
        am.cancel(Alarm.snoozeIntent(ctx))
        AlarmRingReceiver().onReceive(ctx, again)
        assertNotNull(alarmNotification())
        assertFalse(ringsAgainIn10Min(before), "the third ring is the last")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmScreenTest {
    @get:Rule val compose = createAndroidComposeRule<AlarmActivity>()

    @Test fun turningOffClosesTheScreen() {
        compose.onNodeWithText("Доброе утро").assertExists()
        compose.onNodeWithText("Ещё 10 минут").assertExists()
        compose.onNodeWithText("Выключить").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
        // Awake now: the dream journal opens to write the dream down.
        val next = shadowOf(compose.activity).nextStartedActivity
        assertTrue(next.getBooleanExtra(MainActivity.EXTRA_NEW_DREAM, false))
    }
}
