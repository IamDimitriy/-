package sonnik.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sonnik.core.Episode
import sonnik.core.Minute
import sonnik.core.SoundClass
import sonnik.core.SoundKind
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Nights.root(ctx).deleteRecursively()
        Recorder.reset()
    }

    @After fun tearDown() = Recorder.reset()

    private fun night() = compose.setContent { SonnikTheme { NightScreen(onOpenRecords = {}, liveClock = false) } }
    private fun records() = compose.setContent { SonnikTheme { RecordsScreen() } }

    /** The records list loads on a background thread. */
    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun idleScreenTellsWhenRecordingStarts() {
        Prefs(ctx).startMinute = (LocalDateTime.now().hour + 3) % 24 * 60 // never "inside the night" now
        Prefs(ctx).endMinute = ((LocalDateTime.now().hour + 4) % 24) * 60
        night()
        compose.onNodeWithText("Запись начнётся сама", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Начать сейчас").assertIsDisplayed()
    }

    @Test fun switchingAutoStartOff() {
        Prefs(ctx).startMinute = (LocalDateTime.now().hour + 3) % 24 * 60
        Prefs(ctx).endMinute = ((LocalDateTime.now().hour + 4) % 24) * 60
        night()
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        // The status card is scrolled out of view now, so check it exists rather than is visible.
        compose.onNodeWithText("Автозапуск выключен").assertExists()
        assertFalse(Prefs(ctx).autoStart)
    }

    @Test fun skippingTheNightAndUndoing() {
        Prefs(ctx).startMinute = (LocalDateTime.now().hour + 3) % 24 * 60
        Prefs(ctx).endMinute = ((LocalDateTime.now().hour + 4) % 24) * 60
        night()
        compose.onNodeWithText("Пропустить ночь").performClick()
        compose.onNodeWithText("Эту ночь пропускаю").assertIsDisplayed()
        assertTrue(Scheduler.isTonightSkipped(ctx))
        compose.onNodeWithText("Всё-таки записать").performClick()
        compose.onNodeWithText("Запись начнётся сама", substring = true).assertIsDisplayed()
        assertFalse(Scheduler.isTonightSkipped(ctx))
    }

    @Test fun recordingStateShowsLiveCount() {
        val now = System.currentTimeMillis()
        Recorder.update { RecorderState(Phase.RECORDING, saveFrom = now, stopAt = now + 3_600_000, clips = 2, lastClipAt = now) }
        night()
        compose.onNodeWithText("Слушаю").assertIsDisplayed()
        compose.onNodeWithText("2 фразы", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Остановить").assertIsDisplayed()
        compose.onNodeWithText("Послушать").assertIsDisplayed()
    }

    @Test fun waitingStateOffersToSkipTheNight() {
        val now = System.currentTimeMillis()
        Recorder.update { RecorderState(Phase.WAITING, saveFrom = now + 600_000, stopAt = now + 3_600_000) }
        night()
        compose.onNodeWithText("Жду", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Не записывать эту ночь").assertIsDisplayed()
    }

    @Test fun setupListAsksForTheMicrophone() {
        shadowOf(ctx).denyPermissions(Manifest.permission.RECORD_AUDIO)
        night()
        compose.onNodeWithText("Чтобы всё работало само").assertIsDisplayed()
        compose.onNodeWithText("Доступ к микрофону").assertIsDisplayed()
    }

    @Test fun alarmSettingsAppearWhenSwitchedOn() {
        night()
        compose.onNodeWithText("Умный будильник").performScrollTo()
        compose.onNodeWithText("Разбудить не позже").assertDoesNotExist()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("Разбудить не позже").assertExists()
        compose.onNodeWithText("30 мин").assertExists()
        assertTrue(Prefs(ctx).alarmOn)
    }

    @Test fun recordingWithAlarmShowsTheWakeWindow() {
        val now = System.currentTimeMillis()
        Recorder.update {
            RecorderState(
                Phase.RECORDING, saveFrom = now, stopAt = now + 3_600_000, snoreMinutes = 12,
                alarmAt = now + 3_000_000, alarmWindow = 30,
            )
        }
        night()
        compose.onNodeWithText("храп 12 мин", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Разбужу между", substring = true).assertIsDisplayed()
    }

    @Test fun nightGraphAndSnoring() {
        val dir = Nights.dirFor(ctx, LocalDateTime.parse("2026-10-09T00:00"))
        repeat(30) { Nights.appendMinute(dir, Minute(it, 0.0, if (it in 10..24) 25.0 else 0.0, 0.0, 0.05)) }
        records()
        waitForText("храп 15 мин")
        compose.onNodeWithContentDescription("График ночи").assertIsDisplayed()
        compose.onNodeWithText("00:30").assertIsDisplayed()
    }

    @Test fun filterByKindOfSound() {
        val dir = Nights.dirFor(ctx, LocalDateTime.parse("2026-10-09T00:00"))
        val ep = Episode(0.0, 16000, FloatArray(16000 * 3), -20.0, 3.0)
        Nights.save(dir, LocalDateTime.parse("2026-10-09T01:00:00"), ep)
        Nights.save(dir, LocalDateTime.parse("2026-10-09T02:00:00"), ep, SoundClass(SoundKind.STREET, "dog"))
        Nights.save(dir, LocalDateTime.parse("2026-10-09T03:00:00"), ep, SoundClass(SoundKind.SNORE))
        records()
        waitForText("Ночь на 9 октября")
        compose.onNodeWithText("Улица · собака · 3 с").assertExists()
        compose.onNodeWithText("1 фраза · 2 звука", substring = true).assertExists()
        compose.onNodeWithText("Улица 1").performClick()
        compose.onNodeWithText("02:00:00").assertExists()
        compose.onNodeWithText("01:00:00").assertDoesNotExist()
        compose.onNodeWithText("03:00:00").assertDoesNotExist()
        compose.onNodeWithText("Все").performClick()
        compose.onNodeWithText("01:00:00").assertExists()
    }

    @Test fun noSensitivitySetting() {
        night()
        compose.onNodeWithText("Чувствительность").assertDoesNotExist()
        compose.onNodeWithText("Записывать любые звуки").assertDoesNotExist()
    }

    @Test fun recordsEmptyState() {
        records()
        waitForText("Здесь появятся ночи")
        compose.onNodeWithText("Здесь появятся ночи", substring = true).assertIsDisplayed()
    }

    @Test fun recordsListAndDelete() {
        val dir = Nights.dirFor(ctx, LocalDateTime.parse("2026-10-09T00:00"))
        val ep = Episode(0.0, 16000, FloatArray(16000 * 3), -20.0, 3.0)
        Nights.save(dir, LocalDateTime.parse("2026-10-09T03:12:45"), ep)
        Nights.save(dir, LocalDateTime.parse("2026-10-09T04:00:10"), ep)
        records()
        waitForText("Ночь на 9 октября")
        compose.onNodeWithText("Ночь на 9 октября").assertIsDisplayed()
        compose.onNodeWithText("1 ночь · 2 фразы", substring = true).assertIsDisplayed()
        compose.onNodeWithText("03:12:45").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Удалить").onFirst().performClick()
        compose.onNodeWithText("Удалить запись?").assertIsDisplayed()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { Nights.list(ctx).single().clips.size == 1 }
        assertEquals("04:00:10", Nights.list(ctx).single().clips.single().at.toLocalTime().toString())
    }
}
