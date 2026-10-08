package sonnik.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchedulerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val prefs = Prefs(ctx)
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(ctx)
        Recorder.reset()
    }

    @After fun tearDown() = Recorder.reset()

    @Test fun defaultsAreMidnightToSevenWithAutoStart() {
        assertTrue(prefs.autoStart)
        assertEquals("00:00", Prefs.format(prefs.startMinute))
        assertEquals("07:00", Prefs.format(prefs.endMinute))
        assertEquals(at("2026-10-09T00:00"), Scheduler.nextStart(ctx, at("2026-10-08T22:00")))
    }

    @Test fun alarmIsSetOneMinuteBeforeTheNight() {
        Scheduler.sync(ctx)
        val alarm = shadowOf(am).peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertEquals(ms(Scheduler.nextStart(ctx)!!.minusMinutes(1)), alarm.triggerAtMs)
    }

    @Test fun turningAutoStartOffCancelsTheAlarm() {
        Scheduler.sync(ctx)
        prefs.autoStart = false
        Scheduler.sync(ctx)
        assertNull(shadowOf(am).peekNextScheduledAlarm())
        assertNull(Scheduler.nextStart(ctx))
    }

    @Test fun alarmFiringAtTheStartArmsTheNextNight() {
        // At 23:59 "tonight" is already the night that is starting now.
        assertEquals(at("2026-10-10T00:00"), Scheduler.nextStart(ctx, at("2026-10-08T23:59")))
        assertEquals(at("2026-10-09T00:00"), Scheduler.nextStart(ctx, at("2026-10-08T23:58:30")))
    }

    @Test fun skippingTonightMovesToTomorrow() {
        Scheduler.skipTonight(ctx, true)
        assertTrue(Scheduler.isTonightSkipped(ctx))
        val tonight = prefs.window.nextStart(LocalDateTime.now().plusMinutes(1))
        assertEquals(tonight.plusDays(1), Scheduler.nextStart(ctx))
        assertEquals(ms(tonight.plusDays(1).minusMinutes(1)), shadowOf(am).peekNextScheduledAlarm()!!.triggerAtMs)

        Scheduler.skipTonight(ctx, false)
        assertFalse(Scheduler.isTonightSkipped(ctx))
        assertEquals(tonight, Scheduler.nextStart(ctx))
    }

    @Test fun alarmShowsFullScreenPrompt() {
        AlarmReceiver().onReceive(ctx, Intent())
        val n = shadowOf(nm).allNotifications.single()
        assertNotNull(n.fullScreenIntent, "full-screen intent opens the app over the lock screen")
        assertEquals("start", n.channelId)
    }

    @Test fun startChannelIsSilent() {
        val ch = nm.getNotificationChannel("start")
        assertNull(ch.sound)
        assertFalse(ch.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
    }

    @Test fun alarmDoesNothingWhileAlreadyRecording() {
        Recorder.update { it.copy(phase = Phase.WAITING) }
        AlarmReceiver().onReceive(ctx, Intent())
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun alarmDoesNothingWhenAutoStartIsOff() {
        prefs.autoStart = false
        AlarmReceiver().onReceive(ctx, Intent())
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun bootReArmsTheAlarm() {
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNotNull(shadowOf(am).peekNextScheduledAlarm())
    }
}
