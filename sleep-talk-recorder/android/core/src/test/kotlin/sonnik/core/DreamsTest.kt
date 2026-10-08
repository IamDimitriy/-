package sonnik.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DreamsTest {
    private fun dream(text: String, notes: String = "", mood: DreamMood? = null) =
        Dream("1", LocalDateTime.parse("2026-10-09T07:05"), text, mood, notes)

    @Test fun titleIsTheFirstSentence() {
        assertEquals("Я опаздывал на поезд", dream("Я опаздывал на поезд. Потом вокзал превратился в школу.").title)
        assertEquals("Без текста", dream("   ").title)
        assertTrue(dream("а".repeat(100)).title.endsWith("…"))
        assertTrue(dream("а".repeat(100)).title.length <= 60)
    }

    @Test fun searchIgnoresCaseAndYo() {
        val d = dream("Летал над морем, потом ёлка в снегу", notes = "Наверное, про отпуск")
        assertTrue(DreamSearch.matches(d, "МОРЕМ"))
        assertTrue(DreamSearch.matches(d, "елка"))
        assertTrue(DreamSearch.matches(d, "отпуск море"))
        assertFalse(DreamSearch.matches(d, "поезд"))
        assertTrue(DreamSearch.matches(d, "  "))
    }

    @Test fun promptCarriesTheDreamMoodAndNight() {
        val p = DreamPrompt.build(
            dream("Я искал ключи в бесконечном коридоре.", mood = DreamMood.ANXIOUS),
            NightFacts(phrases = 3, sounds = 2, snoreMinutes = 15),
        )
        assertTrue("Я искал ключи в бесконечном коридоре." in p)
        assertTrue("9 октября" in p)
        assertTrue("Настроение сна: тревожный" in p)
        assertTrue("фраз во сне: 3" in p && "15 мин" in p)
        assertTrue("Не предсказывай будущее" in p)
    }

    @Test fun promptWithoutNightFacts() {
        val p = DreamPrompt.build(dream("Просто сон"), NightFacts(0, 0, 0))
        assertFalse("Той ночью" in p)
        assertFalse("Настроение" in p)
    }

    @Test fun moodIds() {
        assertEquals(DreamMood.NIGHTMARE, DreamMood.byId("nightmare"))
        assertEquals(null, DreamMood.byId("x"))
    }
}
