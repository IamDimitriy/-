package sonnik.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 FFT of [n] points (a power of two), with its tables computed once. */
internal class Fft(val n: Int) {
    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
    }

    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }

    /** Transforms [re] + i·[im] in place; [inverse] gives the inverse transform, scaled by 1/n. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) -1.0 else 1.0
        var len = 2
        while (len <= n) {
            val step = n / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cosT[k * step]; val s = sign * sinT[k * step]
                    val a = start + k; val b = a + len / 2
                    val xr = re[b] * c - im[b] * s
                    val xi = re[b] * s + im[b] * c
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
