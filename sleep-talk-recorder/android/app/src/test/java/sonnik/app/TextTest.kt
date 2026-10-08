package sonnik.app

import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

class TextTest {
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test fun russianPlurals() {
        assertEquals(
            listOf("1 фраза", "2 фразы", "5 фраз", "11 фраз", "12 фраз", "21 фраза", "22 фразы", "0 фраз", "111 фраз"),
            listOf(1, 2, 5, 11, 12, 21, 22, 0, 111).map(::phrases),
        )
        assertEquals("ночи", plural(3, "ночь", "ночи", "ночей"))
    }

    @Test fun countdown() {
        assertEquals("Через минуту", until(at("2026-10-08T23:59:30"), at("2026-10-09T00:00")))
        assertEquals("Через 45 мин", until(at("2026-10-08T23:15"), at("2026-10-09T00:00")))
        assertEquals("Через 2 ч", until(at("2026-10-08T22:00"), at("2026-10-09T00:00")))
        assertEquals("Через 3 ч 20 мин", until(at("2026-10-08T20:40"), at("2026-10-09T00:00")))
    }

    @Test fun dayWords() {
        assertEquals("сегодня", dayWord(at("2026-10-09T01:00"), at("2026-10-09T23:30")))
        assertEquals("завтра", dayWord(at("2026-10-08T22:00"), at("2026-10-09T00:00")))
        assertEquals("послезавтра", dayWord(at("2026-10-08T22:00"), at("2026-10-10T00:00")))
    }

    @Test fun sizes() = assertEquals("4,2 МБ", megabytes(4_404_019))
}
