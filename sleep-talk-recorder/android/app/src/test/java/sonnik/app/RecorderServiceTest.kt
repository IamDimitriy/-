package sonnik.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs the real recording service with a fake microphone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecorderServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(ctx).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(ctx)
        Nights.root(ctx).deleteRecursively()
        Recorder.reset()
    }

    @After fun tearDown() {
        RecorderService.inputFactory = null
        Recorder.reset()
    }

    private fun start(now: Boolean) = Robolectric.buildService(
        RecorderService::class.java,
        Intent(ctx, RecorderService::class.java).putExtra(RecorderService.EXTRA_NOW, now),
    ).create().startCommand(0, 1)

    /** The worker thread posts its results to the main looper; pump it until [done]. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $what")
    }

    private fun titles() = shadowOf(nm).allNotifications.map { it.extras.getCharSequence("android.title").toString() }

    @Test fun keepsPhrasesDropsSnoringAndReportsInTheMorning() {
        val input = FakeInput(TestAudio.night())
        RecorderService.inputFactory = { _, _ -> input }
        val service = start(now = true)
        assertEquals(Phase.RECORDING, Recorder.state.value.phase)

        waitFor("session end") { Recorder.state.value.phase == Phase.IDLE }
        val clips = Nights.list(ctx).single().clips
        assertEquals(2, clips.size, "two phrases, snoring ignored")
        assertTrue(clips.all { it.durationS in 3.0..9.0 }, clips.map { it.durationS }.toString())
        assertTrue(input.closed)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertTrue("За ночь: 2 фразы" in titles(), titles().toString())
    }

    @Test fun showsLiveProgressWhileRecording() {
        RecorderService.inputFactory = { _, _ -> FakeInput(TestAudio.night(), loop = true) }
        val service = start(now = true)
        waitFor("first phrase") { Recorder.state.value.clips >= 1 }
        val s = Recorder.state.value
        assertEquals(Phase.RECORDING, s.phase)
        assertTrue(s.lastClipAt > 0)
        assertTrue(s.stopAt > s.saveFrom)
        val ongoing = shadowOf(nm).allNotifications.first { it.extras.getCharSequence("android.title").toString().startsWith("Слушаю") }
        assertTrue(ongoing.extras.getCharSequence("android.text").toString().startsWith("Записано:"))
        service.withIntent(Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)).startCommand(0, 2)
        waitFor("stop") { Recorder.state.value.phase == Phase.IDLE }
    }

    @Test fun stopButtonEndsTheSessionAndKeepsWhatWasRecorded() {
        val input = FakeInput(TestAudio.night(), loop = true)
        RecorderService.inputFactory = { _, _ -> input }
        val service = start(now = true)
        waitFor("first phrase") { Recorder.state.value.clips >= 1 }
        service.withIntent(Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)).startCommand(0, 2)
        waitFor("stop") { Recorder.state.value.phase == Phase.IDLE }
        assertTrue(input.closed)
        assertTrue(Nights.list(ctx).single().clips.isNotEmpty())
    }

    @Test fun beforeTheNightNothingIsKept() {
        // The night starts in two hours; the mic is on to learn the room, but phrases are dropped.
        val prefs = Prefs(ctx)
        val start = LocalTime.now().plusHours(2)
        prefs.startMinute = start.hour * 60 + start.minute
        prefs.endMinute = (prefs.startMinute + 6 * 60) % (24 * 60)
        RecorderService.inputFactory = { _, _ -> FakeInput(TestAudio.night()) }
        start(now = false)
        assertEquals(Phase.WAITING, Recorder.state.value.phase)
        waitFor("session end") { Recorder.state.value.phase == Phase.IDLE }
        assertTrue(Nights.list(ctx).isEmpty())
        assertTrue(titles().none { it.startsWith("За ночь") })
    }

    @Test fun withoutMicPermissionItExplainsAndStops() {
        shadowOf(ctx).denyPermissions(Manifest.permission.RECORD_AUDIO)
        var opened = false
        RecorderService.inputFactory = { _, _ -> opened = true; FakeInput(TestAudio.night()) }
        val service = start(now = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!opened)
        assertEquals(Phase.IDLE, Recorder.state.value.phase)
        assertTrue("Сонник не записывает" in titles(), titles().toString())
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }

    @Test fun busyMicIsReported() {
        RecorderService.inputFactory = { _, _ ->
            object : AudioInput {
                override val sampleRate = 16000
                override fun start() = false
                override fun read(buf: ShortArray) = -1
                override fun close() {}
            }
        }
        start(now = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Phase.IDLE, Recorder.state.value.phase)
        assertTrue("Сонник не записывает" in titles())
    }
}
