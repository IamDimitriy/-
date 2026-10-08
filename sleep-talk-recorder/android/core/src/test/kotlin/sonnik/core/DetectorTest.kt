package sonnik.core

import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SR = 16000
private val rnd = Random(1)

private fun noise(s: Double, level: Double = 0.002) = FloatArray((SR * s).toInt()) {
    (sqrt(-2 * ln(rnd.nextDouble().coerceAtLeast(1e-9))) * cos(2 * PI * rnd.nextDouble()) * level).toFloat()
}

private fun speech(s: Double, level: Double = 0.1): FloatArray {
    val raw = DoubleArray((SR * s).toInt()) { i ->
        val t = i.toDouble() / SR
        var v = 0.0
        for (k in 2 until 20) v += sin(2 * PI * 150 * k * t) / (1 + abs(150.0 * k - 800) / 400)
        v * 0.5 * (1 + sin(2 * PI * 4 * t))
    }
    val peak = raw.maxOf { abs(it) }
    return FloatArray(raw.size) { (raw[it] / peak * level).toFloat() }
}

private fun snore(s: Double, level: Double = 0.2) = FloatArray((SR * s).toInt()) { i ->
    (level * sin(2 * PI * 70 * i / SR) * (0.6 + 0.4 * sin(2 * PI * 30 * i / SR))).toFloat()
}

private operator fun FloatArray.plus(o: FloatArray) = FloatArray(size) { this[it] + o[it] }
private fun cat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size }); var o = 0
    for (p in parts) { p.copyInto(out, o); o += p.size }
    return out
}

private fun run(audio: FloatArray): List<Episode> {
    val d = Detector(SR)
    val out = ArrayList<Episode>()
    var i = 0
    while (i < audio.size) {
        val n = minOf(1600, audio.size - i)
        out += d.process(audio.copyOfRange(i, i + n))
        i += n
    }
    return out + d.flush()
}

class DetectorTest {
    @Test fun quietNightHasNoEpisodes() = assertEquals(0, run(noise(60.0)).size)

    @Test fun speechIsCapturedWithPreroll() {
        val ev = run(cat(noise(20.0), noise(3.0) + speech(3.0), noise(20.0)))
        assertEquals(1, ev.size)
        assertTrue(ev[0].startS in 18.0..20.5, "start ${ev[0].startS}")
        assertTrue(ev[0].durationS in 4.0..9.0, "duration ${ev[0].durationS}")
    }

    @Test fun twoPhrasesAreTwoEpisodes() {
        val p = { noise(2.0) + speech(2.0) }
        assertEquals(2, run(cat(noise(10.0), p(), noise(15.0), p(), noise(10.0))).size)
    }

    @Test fun snoringAndClicksAreIgnored() {
        val click = FloatArray(SR * 2).also { it.fill(0.8f, 100, 140) }
        assertEquals(0, run(cat(noise(10.0), noise(5.0) + snore(5.0), noise(5.0), noise(2.0) + click, noise(10.0))).size)
    }

    @Test fun shortPcmInputWorks() {
        val audio = cat(noise(10.0), noise(2.0) + speech(2.0), noise(5.0))
        val pcm = ShortArray(audio.size) { (audio[it] * 32767).toInt().toShort() }
        val d = Detector(SR)
        assertEquals(1, d.process(pcm, pcm.size).size + d.flush().size)
    }

    @Test fun wavRoundTripsDuration() {
        val f = File.createTempFile("clip", ".wav")
        Wav.write(f, FloatArray(SR * 3), SR)
        assertEquals(3.0, Wav.durationS(f), 1e-6)
        f.delete()
    }
}

class NightWindowTest {
    private val w = NightWindow(LocalTime.MIDNIGHT, LocalTime.of(7, 0))
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test fun midnightWindow() {
        assertTrue(w.contains(at("2026-10-09T00:00")))
        assertTrue(w.contains(at("2026-10-09T06:59")))
        assertFalse(w.contains(at("2026-10-09T07:00")))
        assertFalse(w.contains(at("2026-10-08T23:59")))
        assertEquals(at("2026-10-09T00:00"), w.nextStart(at("2026-10-08T22:10")))
        assertEquals(at("2026-10-10T00:00"), w.nextStart(at("2026-10-09T00:00")))
        assertEquals(at("2026-10-09T07:00"), w.endFor(at("2026-10-09T03:00")))
        assertEquals(at("2026-10-09T07:00"), w.endFor(at("2026-10-08T22:00")))
    }

    @Test fun windowAcrossMidnight() {
        val n = NightWindow(LocalTime.of(23, 30), LocalTime.of(7, 0))
        assertTrue(n.contains(at("2026-10-08T23:45")))
        assertTrue(n.contains(at("2026-10-09T03:00")))
        assertFalse(n.contains(at("2026-10-09T12:00")))
        assertEquals(at("2026-10-09T07:00"), n.endFor(at("2026-10-08T23:45")))
        assertEquals(at("2026-10-09T07:00"), n.endFor(at("2026-10-09T03:00")))
        assertEquals(at("2026-10-10T07:00"), n.endFor(at("2026-10-09T12:00")))
    }
}
