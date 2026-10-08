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

    private fun alarmNotification(): Notification? = shadowOf(nm).allNotifications.firstOrNull { it.channelId == "alarm" }

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

    @Test fun backupRingsWithFullScreenAndInsistentSound() {
        prefs.alarmOn = true
        AlarmRingReceiver().onReceive(ctx, Intent())
        val n = assertNotNull(alarmNotification())
        assertNotNull(n.fullScreenIntent)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0, "sound repeats until turned off")
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(2, n.actions.size)
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
    }
}
