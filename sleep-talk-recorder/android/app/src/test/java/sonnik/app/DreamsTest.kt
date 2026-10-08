package sonnik.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sonnik.core.Dream
import sonnik.core.DreamMood
import sonnik.core.Episode
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dictation that "hears" whatever the test says. */
class FakeDictation(override val available: Boolean = true) : Dictation {
    var listening = false
    private var final: ((String) -> Unit)? = null
    private var partial: ((String) -> Unit)? = null

    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onStopped: (String?) -> Unit) {
        listening = true; partial = onPartial; final = onFinal
    }

    override fun stop() { listening = false }

    fun hear(words: String) { partial?.invoke(words); final?.invoke(words) }
}

private fun clean(ctx: Context) {
    File(ctx.filesDir, "dreams").deleteRecursively()
    Nights.root(ctx).deleteRecursively()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DreamStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() = clean(ctx)

    @Test fun savedDreamsAreListedNewestFirst() {
        DreamStore.save(ctx, Dream("a", LocalDateTime.parse("2026-10-08T07:00"), "Старый сон"))
        DreamStore.save(ctx, Dream("b", LocalDateTime.parse("2026-10-09T07:10"), "Новый сон", DreamMood.ANXIOUS, "мысли"))
        val list = DreamStore.list(ctx)
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(DreamMood.ANXIOUS, list[0].mood)
        assertEquals("мысли", list[0].notes)
    }

    @Test fun savingAgainUpdatesTheSameDream() {
        val d = DreamStore.new(text = "Начало")
        DreamStore.save(ctx, d)
        DreamStore.save(ctx, d.copy(text = "Начало и конец"))
        assertEquals(listOf("Начало и конец"), DreamStore.list(ctx).map { it.text })
    }

    @Test fun deleteRemovesIt() {
        val d = DreamStore.new(text = "x")
        DreamStore.save(ctx, d)
        DreamStore.delete(ctx, d)
        assertTrue(DreamStore.list(ctx).isEmpty())
    }

    @Test fun dreamIsLinkedToItsNight() {
        val dir = Nights.dirFor(ctx, LocalDateTime.parse("2026-10-09T00:00"))
        Nights.save(dir, LocalDateTime.parse("2026-10-09T03:00:00"), Episode(0.0, 16000, FloatArray(16000), -20.0, 1.0))
        val d = Dream("x", LocalDateTime.parse("2026-10-09T07:05"), "сон")
        val night = assertNotNull(DreamStore.nightOf(ctx, d))
        assertEquals(1, DreamStore.factsOf(night)!!.phrases)
        assertEquals(null, DreamStore.nightOf(ctx, d.copy(createdAt = LocalDateTime.parse("2026-10-20T07:00"))))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DreamScreensTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val tab = mutableIntStateOf(TAB_DREAMS)
    private val editing = mutableStateOf<DreamEdit?>(null)
    private val dictation = FakeDictation()

    @Before fun setUp() {
        clean(ctx)
        shadowOf(ctx).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun app() = compose.setContent { SonnikTheme { App(tab, editing, dictation) } }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun emptyJournalExplainsItself() {
        app()
        waitForText("Записывайте сны сразу после пробуждения")
        compose.onNodeWithText("Записать сон").assertIsDisplayed()
    }

    @Test fun typingADreamSavesIt() {
        app()
        compose.onNodeWithText("Записать сон").performClick()
        compose.onNodeWithText("Запомни сон").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Что снилось")).performTextInput("Я плыл по реке из молока")
        compose.onNodeWithText("Тревожный").performScrollTo().performClick()
        compose.onNodeWithText("Готово").performClick()
        val saved = DreamStore.list(ctx).single()
        assertEquals("Я плыл по реке из молока", saved.text)
        assertEquals(DreamMood.ANXIOUS, saved.mood)
        assertEquals(TAB_DREAMS, tab.intValue)
        waitForText("Я плыл по реке из молока")
    }

    @Test fun dictationFillsTheText() {
        app()
        compose.onNodeWithText("Записать сон").performClick()
        compose.onNodeWithText("Говорить").performClick()
        assertTrue(dictation.listening)
        dictation.hear("мне снилось что я летаю")
        dictation.hear("над городом")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Мне снилось что я летаю над городом")).assertExists()
        compose.onNodeWithText("Стоп").performClick()
        assertTrue(!dictation.listening)
    }

    @Test fun afterTheAlarmListeningStartsByItself() {
        editing.value = DreamEdit(DreamStore.new(), listen = true)
        app()
        compose.waitForIdle()
        assertTrue(dictation.listening)
        compose.onNodeWithText("Слушаю", substring = true).assertIsDisplayed()
    }

    @Test fun emptyDreamIsNotSaved() {
        editing.value = DreamEdit(DreamStore.new(), listen = false)
        app()
        compose.onNodeWithText("Готово").performClick()
        assertTrue(DreamStore.list(ctx).isEmpty())
    }

    @Test fun interpretationIsSharedWithAPrompt() {
        editing.value = DreamEdit(DreamStore.new(text = "Я искал ключи в бесконечном коридоре"), listen = false)
        app()
        compose.onNodeWithText("Толковать в Claude или ChatGPT").performScrollTo().performClick()
        val chooser = shadowOf(ctx).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue("Я искал ключи в бесконечном коридоре" in text)
        assertTrue("Не предсказывай будущее" in text)
    }

    @Test fun searchFindsDreams() {
        DreamStore.save(ctx, Dream("a", LocalDateTime.parse("2026-10-08T07:00"), "Сон про море и чаек"))
        DreamStore.save(ctx, Dream("b", LocalDateTime.parse("2026-10-09T07:00"), "Сон про экзамен"))
        app()
        waitForText("Сон про экзамен")
        compose.onNode(hasSetTextAction() and hasText("Поиск по снам")).performTextInput("море")
        compose.onNodeWithText("Сон про море и чаек").assertExists()
        compose.onNodeWithText("Сон про экзамен").assertDoesNotExist()
    }

    @Test fun deletingADream() {
        val d = Dream("a", LocalDateTime.parse("2026-10-08T07:00"), "Сон для удаления")
        DreamStore.save(ctx, d)
        editing.value = DreamEdit(d, listen = false)
        app()
        compose.onNodeWithText("Удалить сон").performScrollTo().performClick()
        compose.onNodeWithText("Удалить").performClick()
        assertTrue(DreamStore.list(ctx).isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WakeUpToDreamTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun newDreamExtraOpensTheEditor() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        clean(ctx)
        val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_NEW_DREAM, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithText("Запомни сон").assertIsDisplayed()
        }
    }
}
