package com.example.audiostudio

import kotlin.math.*

data class EnhanceSettings(
    val trimSilence: Boolean = true,
    val highPassHz: Float = 80f,       // 0 = apagado
    val humNotch: Int = 0,             // 0 = apagado, 50 o 60 Hz
    val denoise: Boolean = true,
    val denoiseAmount: Float = 0.7f,   // 0..1
    val deEsser: Boolean = true,
    val warmthDb: Float = 1.5f,
    val presenceDb: Float = 2.5f,
    val airDb: Float = 1.5f,
    val compress: Boolean = true,
    val compressAmount: Float = 0.5f,  // 0..1
    val targetPeakDb: Float = -1.0f,
    val fadeMs: Int = 8
)

object Enhancer {

    fun process(
        input: FloatArray, channels: Int, sampleRate: Int,
        s: EnhanceSettings, onProgress: (Float) -> Unit = {}
    ): FloatArray {
        if (input.isEmpty()) return input
        val chans = deinterleave(input, channels)

        for (c in chans.indices) {
            var x = chans[c]
            val base = c.toFloat() / channels
            if (s.highPassHz > 0f) x = biquad(x, Biquad.highPass(sampleRate, s.highPassHz, 0.707f))
            if (s.humNotch > 0) {
                var f = s.humNotch.toFloat()
                while (f < 320f && f < sampleRate * 0.45f) { x = biquad(x, Biquad.notch(sampleRate, f, 20f)); f += s.humNotch }
            }
            if (s.denoise) x = spectralDenoise(x, s.denoiseAmount) { p ->
                onProgress((base + p / channels) * 0.8f)
            }
            if (s.deEsser) x = deEss(x, sampleRate)
            if (s.warmthDb != 0f) x = biquad(x, Biquad.lowShelf(sampleRate, 180f, s.warmthDb))
            if (s.presenceDb != 0f) x = biquad(x, Biquad.peaking(sampleRate, 3400f, 1.0f, s.presenceDb))
            if (s.airDb != 0f) x = biquad(x, Biquad.highShelf(sampleRate, 9000f, s.airDb))
            chans[c] = x
            onProgress((c + 1f) / channels * 0.85f)
        }

        var out = interleave(chans, channels)
        if (s.compress) out = compressor(out, channels, sampleRate, s.compressAmount)
        onProgress(0.9f)
        out = normalize(out, s.targetPeakDb)
        out = limiter(out, channels, sampleRate)
        if (s.trimSilence) out = trimSilence(out, channels, sampleRate)
        fade(out, channels, sampleRate, s.fadeMs)
        onProgress(1f)
        return out
    }

    // ------------------------------------------------------------ canales ---
    private fun deinterleave(x: FloatArray, ch: Int): Array<FloatArray> {
        if (ch == 1) return arrayOf(x.copyOf())
        val frames = x.size / ch
        return Array(ch) { c -> FloatArray(frames) { f -> x[f * ch + c] } }
    }

    private fun interleave(chs: Array<FloatArray>, ch: Int): FloatArray {
        if (ch == 1) return chs[0]
        val frames = chs[0].size
        val out = FloatArray(frames * ch)
        for (c in 0 until ch) for (f in 0 until frames) out[f * ch + c] = chs[c][f]
        return out
    }

    // ------------------------------------------------------------- filtros ---
    class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
        companion object {
            private fun n(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                       (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun highPass(sr: Int, f: Float, q: Float): Biquad {
                val w = 2 * PI * f / sr; val cw = cos(w); val sw = sin(w); val al = sw / (2 * q)
                return n((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
            }
            fun notch(sr: Int, f: Float, q: Float): Biquad {
                val w = 2 * PI * f / sr; val cw = cos(w); val sw = sin(w); val al = sw / (2 * q)
                return n(1.0, -2 * cw, 1.0, 1 + al, -2 * cw, 1 - al)
            }
            fun peaking(sr: Int, f: Float, q: Float, db: Float): Biquad {
                val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr
                val cw = cos(w); val sw = sin(w); val al = sw / (2 * q)
                return n(1 + al * A, -2 * cw, 1 - al * A, 1 + al / A, -2 * cw, 1 - al / A)
            }
            fun lowShelf(sr: Int, f: Float, db: Float): Biquad {
                val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr
                val cw = cos(w); val sw = sin(w); val al = sw / 2 * sqrt(2.0); val s2 = 2 * sqrt(A) * al
                return n(A * ((A + 1) - (A - 1) * cw + s2), 2 * A * ((A - 1) - (A + 1) * cw),
                         A * ((A + 1) - (A - 1) * cw - s2),
                         (A + 1) + (A - 1) * cw + s2, -2 * ((A - 1) + (A + 1) * cw),
                         (A + 1) + (A - 1) * cw - s2)
            }
            fun highShelf(sr: Int, f: Float, db: Float): Biquad {
                val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr
                val cw = cos(w); val sw = sin(w); val al = sw / 2 * sqrt(2.0); val s2 = 2 * sqrt(A) * al
                return n(A * ((A + 1) + (A - 1) * cw + s2), -2 * A * ((A - 1) + (A + 1) * cw),
                         A * ((A + 1) + (A - 1) * cw - s2),
                         (A + 1) - (A - 1) * cw + s2, 2 * ((A - 1) - (A + 1) * cw),
                         (A + 1) - (A - 1) * cw - s2)
            }
        }
    }

    private fun biquad(x: FloatArray, f: Biquad): FloatArray {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val xn = x[i]
            val y = f.b0 * xn + f.b1 * x1 + f.b2 * x2 - f.a1 * y1 - f.a2 * y2
            x2 = x1; x1 = xn; y2 = y1; y1 = y
            out[i] = y
        }
        return out
    }

    // ------------------------------------------- reducción de ruido (FFT) ---
    private fun spectralDenoise(x: FloatArray, amount: Float, onProgress: (Float) -> Unit): FloatArray {
        val n = 1024; val hop = n / 4
        if (x.size < n * 3) return x
        val win = FloatArray(n) { 0.5f - 0.5f * cos(2.0 * PI * it / n).toFloat() }
        val bins = n / 2 + 1
        val re = FloatArray(n); val im = FloatArray(n)

        // Pase 1: estimación del ruido (mínimos por banda)
        val noise = FloatArray(bins) { Float.MAX_VALUE }
        val smooth = FloatArray(bins)
        var first = true
        var pos = 0
        while (pos + n <= x.size) {
            for (i in 0 until n) { re[i] = x[pos + i] * win[i]; im[i] = 0f }
            fft(re, im)
            for (b in 0 until bins) {
                val m = hypot(re[b].toDouble(), im[b].toDouble()).toFloat()
                smooth[b] = if (first) m else 0.7f * smooth[b] + 0.3f * m
                if (smooth[b] < noise[b]) noise[b] = smooth[b]
            }
            first = false
            pos += hop
        }
        val over = 1.0f + 2.5f * amount
        val floorG = max(0.03f, 0.30f * (1f - amount))

        // Pase 2: sustracción espectral + solapamiento
        val out = FloatArray(x.size)
        pos = 0
        while (pos + n <= x.size) {
            for (i in 0 until n) { re[i] = x[pos + i] * win[i]; im[i] = 0f }
            fft(re, im)
            for (b in 0 until bins) {
                val mag = hypot(re[b].toDouble(), im[b].toDouble()).toFloat()
                if (mag > 1e-9f) {
                    val g = max(floorG, (mag - over * noise[b]) / mag)
                    re[b] *= g; im[b] *= g
                    if (b in 1 until n / 2) { re[n - b] *= g; im[n - b] *= g }
                }
            }
            ifft(re, im)
            for (i in 0 until n) out[pos + i] += re[i] * win[i] / 1.5f
            pos += hop
            if ((pos / hop) % 64 == 0) onProgress(pos.toFloat() / x.size)
        }
        // Conservamos los bordes sin procesar
        for (i in 0 until n) out[i] = x[i]
        for (i in max(0, out.size - n) until out.size) out[i] = x[i]
        return out
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val br = re[i + k + len / 2]; val bi = im[i + k + len / 2]
                    val vr = br * cr - bi * ci; val vi = br * ci + bi * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun ifft(re: FloatArray, im: FloatArray) {
        for (i in im.indices) im[i] = -im[i]
        fft(re, im)
        val n = re.size
        if (b in 1 until n / 2) { re[n - b] *= g; im[n - b] *= g }

    // ------------------------------------------------------------ de-esser ---
    private fun deEss(x: FloatArray, sr: Int): FloatArray {
        val hi = biquad(x, Biquad.highPass(sr, 5500f, 0.707f))
        val out = FloatArray(x.size)
        var env = 0f
        val atk = exp(-1.0 / (0.001 * sr)).toFloat()
        val rel = exp(-1.0 / (0.060 * sr)).toFloat()
        val thr = 0.06f
        for (i in x.indices) {
            val a = abs(hi[i])
            env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
            val g = if (env > thr) (thr / env).coerceIn(0.25f, 1f) else 1f
            out[i] = x[i] - (1f - g) * hi[i]
        }
        return out
    }

    // ----------------------------------------------------------- dinámica ---
    private fun compressor(x: FloatArray, ch: Int, sr: Int, amount: Float): FloatArray {
        val thrDb = -24f + 8f * (1f - amount)
        val ratio = 1.5f + 4.5f * amount
        val atk = exp(-1.0 / (0.010 * sr)).toFloat()
        val rel = exp(-1.0 / (0.180 * sr)).toFloat()
        val makeup = 10f.pow((-thrDb * (1f - 1f / ratio) * 0.6f) / 20f)
        val frames = x.size / ch
        var env = 0f
        val out = FloatArray(x.size)
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(x[f * ch + c]))
            env = if (peak > env) atk * env + (1 - atk) * peak else rel * env + (1 - rel) * peak
            val db = 20f * log10(max(env, 1e-7f))
            val gainDb = if (db > thrDb) -(db - thrDb) * (1f - 1f / ratio) else 0f
            val g = 10f.pow(gainDb / 20f) * makeup
            for (c in 0 until ch) out[f * ch + c] = x[f * ch + c] * g
        }
        return out
    }

    private fun normalize(x: FloatArray, targetDb: Float): FloatArray {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak < 1e-6f) return x
        val g = 10f.pow(targetDb / 20f) / peak
        for (i in x.indices) x[i] *= g
        return x
    }

    private fun limiter(x: FloatArray, ch: Int, sr: Int): FloatArray {
        val ceil = 0.98f
        val rel = exp(-1.0 / (0.050 * sr)).toFloat()
        var g = 1f
        val frames = x.size / ch
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(x[f * ch + c]))
            val need = if (peak * g > ceil) ceil / peak else 1f
            g = if (need < g) need else g * rel + need * (1 - rel)
            for (c in 0 until ch) x[f * ch + c] = (x[f * ch + c] * g).coerceIn(-1f, 1f)
        }
        return x
    }

    private fun trimSilence(x: FloatArray, ch: Int, sr: Int): FloatArray {
        val frames = x.size / ch
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        val thr = max(peak * 0.008f, 0.0008f)   // ~ -42 dB
        var start = 0
        while (start < frames) {
            var p = 0f; for (c in 0 until ch) p = max(p, abs(x[start * ch + c]))
            if (p > thr) break
            start++
        }
        var end = frames - 1
        while (end > start) {
            var p = 0f; for (c in 0 until ch) p = max(p, abs(x[end * ch + c]))
            if (p > thr) break
            end--
        }
        if (end <= start) return x
        val pad = (sr * 0.12f).toInt()
        val s = max(0, start - pad); val e = min(frames - 1, end + pad)
        return x.copyOfRange(s * ch, (e + 1) * ch)
    }

    private fun fade(x: FloatArray, ch: Int, sr: Int, ms: Int) {
        val nf = min((sr * ms / 1000), x.size / ch / 2)
        for (f in 0 until nf) {
            val g = f.toFloat() / nf
            for (c in 0 until ch) {
                x[f * ch + c] *= g
                x[(x.size / ch - 1 - f) * ch + c] *= g
            }
        }
    }
}
