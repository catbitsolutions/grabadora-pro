package com.example.audiostudio

import kotlin.math.*

/** Audio en memoria: canales separados en punto flotante (-1..1). */
class AudioBuffer(var sampleRate: Int, var channels: Array<FloatArray>) {
    val channelCount: Int get() = channels.size
    val frames: Int get() = if (channels.isEmpty()) 0 else channels[0].size
    val durationSec: Double get() = frames.toDouble() / sampleRate
    fun copy() = AudioBuffer(sampleRate, Array(channelCount) { channels[it].copyOf() })
    fun peak(): Float {
        var p = 0f
        for (c in channels) for (v in c) { val a = abs(v); if (a > p) p = a }
        return p
    }
}

/** FFT radix-2 iterativa. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
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
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0; var cwi = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + half]; val xi = im[i + k + half]
                    val vr = xr * cwr - xi * cwi
                    val vi = xr * cwi + xi * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + half] = ur - vr; im[i + k + half] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun inverse(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        transform(re, im)
        for (i in 0 until n) { re[i] /= n; im[i] = -im[i] / n }
    }
}

/** Filtro biquad (forma transpuesta directa II). */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    fun process(x: FloatArray) {
        var z1 = 0.0; var z2 = 0.0
        for (i in x.indices) {
            val inp = x[i].toDouble()
            val out = b0 * inp + z1
            z1 = b1 * inp - a1 * out + z2
            z2 = b2 * inp - a2 * out
            x[i] = out.toFloat()
        }
    }

    companion object {
        fun highPass(fs: Int, f: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            val a0 = 1 + al
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, (-2 * c) / a0, (1 - al) / a0)
        }
        fun lowPass(fs: Int, f: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            val a0 = 1 + al
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, (-2 * c) / a0, (1 - al) / a0)
        }
        fun notch(fs: Int, f: Double, q: Double = 30.0): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            val a0 = 1 + al
            return Biquad(1 / a0, (-2 * c) / a0, 1 / a0, (-2 * c) / a0, (1 - al) / a0)
        }
        fun peaking(fs: Int, f: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
            val a0 = 1 + al / a
            return Biquad((1 + al * a) / a0, (-2 * c) / a0, (1 - al * a) / a0, (-2 * c) / a0, (1 - al / a) / a0)
        }
        fun lowShelf(fs: Int, f: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs; val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0)
            val sq = 2 * sqrt(a) * al
            val a0 = (a + 1) + (a - 1) * c + sq
            return Biquad(
                a * ((a + 1) - (a - 1) * c + sq) / a0,
                2 * a * ((a - 1) - (a + 1) * c) / a0,
                a * ((a + 1) - (a - 1) * c - sq) / a0,
                -2 * ((a - 1) + (a + 1) * c) / a0,
                ((a + 1) + (a - 1) * c - sq) / a0
            )
        }
        fun highShelf(fs: Int, f: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs; val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0)
            val sq = 2 * sqrt(a) * al
            val a0 = (a + 1) - (a - 1) * c + sq
            return Biquad(
                a * ((a + 1) + (a - 1) * c + sq) / a0,
                -2 * a * ((a - 1) + (a + 1) * c) / a0,
                a * ((a + 1) + (a - 1) * c - sq) / a0,
                2 * ((a - 1) - (a + 1) * c) / a0,
                ((a + 1) - (a - 1) * c - sq) / a0
            )
        }
    }
}

/** Reducción de ruido por sustracción espectral (Wiener) con perfil automático. */
object Denoiser {
    fun process(x: FloatArray, sampleRate: Int, strength: Float, progress: (Float) -> Unit): FloatArray {
        val n = 1024
        val hop = n / 4
        if (x.size < n * 6) return x
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
        val numFrames = (x.size - n) / hop + 1

        // 1) energía por trama (para hallar las tramas más silenciosas = ruido)
        val energy = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            var s = 0.0
            val off = f * hop
            for (i in 0 until n step 2) { val v = x[off + i].toDouble(); s += v * v }
            energy[f] = sqrt(s / (n / 2)).toFloat()
        }
        val order = energy.indices.sortedBy { energy[it] }
        val take = min(max(10, numFrames / 12), 70)

        // 2) perfil de ruido promedio
        val noise = DoubleArray(n / 2 + 1)
        val re = DoubleArray(n); val im = DoubleArray(n)
        var used = 0
        for (k in 0 until min(take, order.size)) {
            val off = order[k] * hop
            for (i in 0 until n) { re[i] = x[off + i] * win[i]; im[i] = 0.0 }
            Fft.transform(re, im)
            for (bin in 0..n / 2) noise[bin] += hypot(re[bin], im[bin])
            used++
        }
        if (used == 0) return x
        for (i in noise.indices) noise[i] /= used
        val prof = DoubleArray(noise.size)
        for (i in noise.indices) {
            var s = 0.0; var c = 0
            for (j in max(0, i - 2)..min(noise.size - 1, i + 2)) { s += noise[j]; c++ }
            prof[i] = s / c
        }

        // 3) STFT -> ganancia por bin -> overlap-add
        val out = FloatArray(x.size)
        val prev = DoubleArray(n / 2 + 1) { 1.0 }
        val floorG = 0.06
        for (f in 0 until numFrames) {
            val off = f * hop
            for (i in 0 until n) { re[i] = x[off + i] * win[i]; im[i] = 0.0 }
            Fft.transform(re, im)
            for (bin in 0..n / 2) {
                val mr = re[bin]; val mi = im[bin]
                val p = mr * mr + mi * mi
                val nz = prof[bin] * strength
                var g = if (p > 1e-18) (p - nz * nz) / p else 0.0
                if (g.isNaN()) g = 0.0
                g = g.coerceIn(floorG, 1.0)
                g = 0.55 * prev[bin] + 0.45 * g
                prev[bin] = g
                re[bin] = mr * g; im[bin] = mi * g
                if (bin in 1 until n / 2) { re[n - bin] = re[bin]; im[n - bin] = -im[bin] }
            }
            Fft.inverse(re, im)
            for (i in 0 until n) out[off + i] += (re[i] * win[i]).toFloat()
            if (f % 96 == 0) progress(f.toFloat() / numFrames)
        }
        val scale = 1f / 1.5f  // compensación COLA de Hann con 75% de solape
        for (i in out.indices) out[i] *= scale
        val covered = (numFrames - 1) * hop + n
        for (i in 0 until min(n, out.size)) out[i] = x[i]
        for (i in max(0, covered - n) until x.size) out[i] = x[i]
        progress(1f)
        return out
    }
}

data class EnhanceOptions(
    var denoise: Boolean = true,
    var denoiseAmount: Float = 1.4f,
    var highPass: Boolean = true,
    var deHum: Boolean = false,
    var humHz: Double = 50.0,
    var eqRemaster: Boolean = true,
    var voiceMode: Boolean = true,
    var compressor: Boolean = true,
    var trimSilence: Boolean = false,
    var normalize: Boolean = true,
    var fades: Boolean = true
)

object Dsp {
    fun toMono(b: AudioBuffer): AudioBuffer {
        if (b.channelCount == 1) return b
        val n = b.frames
        val m = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (c in b.channels) s += c[i]
            m[i] = s / b.channelCount
        }
        return AudioBuffer(b.sampleRate, arrayOf(m))
    }

    fun resample(b: AudioBuffer, target: Int): AudioBuffer {
        if (b.sampleRate == target) return b
        val src = if (target < b.sampleRate) {
            val c = b.copy()
            val cut = target * 0.45
            for (ch in c.channels) {
                Biquad.lowPass(c.sampleRate, cut).process(ch)
                Biquad.lowPass(c.sampleRate, cut).process(ch)
            }
            c
        } else b
        val ratio = target.toDouble() / src.sampleRate
        val newLen = (src.frames * ratio).toInt()
        val out = Array(src.channelCount) { ci ->
            val inA = src.channels[ci]
            val o = FloatArray(newLen)
            for (i in 0 until newLen) {
                val pos = i / ratio
                val i0 = pos.toInt()
                val fr = (pos - i0).toFloat()
                val a = inA[min(i0, inA.size - 1)]
                val c = inA[min(i0 + 1, inA.size - 1)]
                o[i] = a + (c - a) * fr
            }
            o
        }
        return AudioBuffer(target, out)
    }
}

object Enhancer {
    fun process(src: AudioBuffer, o: EnhanceOptions, progress: (Int, String) -> Unit): AudioBuffer {
        var buf = src.copy()
        val fs = buf.sampleRate

        if (o.trimSilence) { progress(4, "Recortando silencios…"); buf = trim(buf) }

        if (o.highPass) {
            progress(10, "Filtrando ruido de graves…")
            val f = if (o.voiceMode) 85.0 else 32.0
            for (c in buf.channels) { Biquad.highPass(fs, f).process(c); Biquad.highPass(fs, f).process(c) }
        }

        if (o.deHum) {
            progress(18, "Eliminando zumbido eléctrico…")
            for (c in buf.channels) {
                var f = o.humHz
                var k = 0
                while (f < fs / 2.0 * 0.9 && k < 6) { Biquad.notch(fs, f, 35.0).process(c); f += o.humHz; k++ }
            }
        }

        if (o.denoise) {
            progress(25, "Reduciendo ruido…")
            for (i in buf.channels.indices) {
                val base = 25 + i * (40 / buf.channelCount)
                buf.channels[i] = Denoiser.process(buf.channels[i], fs, o.denoiseAmount) { p ->
                    progress(base + (40 / buf.channelCount * p).toInt(), "Reduciendo ruido…")
                }
            }
        }

        if (o.eqRemaster) {
            progress(70, "Ecualizando (remaster)…")
            for (c in buf.channels) {
                if (o.voiceMode) {
                    Biquad.peaking(fs, 300.0, 1.0, -2.5).process(c)
                    Biquad.peaking(fs, 3200.0, 0.9, 2.5).process(c)
                    Biquad.highShelf(fs, 8000.0, 2.0).process(c)
                } else {
                    Biquad.lowShelf(fs, 90.0, 1.5).process(c)
                    Biquad.peaking(fs, 400.0, 1.0, -1.5).process(c)
                    Biquad.highShelf(fs, 10000.0, 2.0).process(c)
                }
            }
        }

        if (o.compressor) { progress(80, "Compresor de dinámica…"); compress(buf, -18.0, 3.0, 12.0, 160.0) }
        if (o.normalize) { progress(88, "Normalizando…"); normalize(buf, -1.0) }
        progress(93, "Limitador…"); compress(buf, -0.5, 20.0, 1.0, 60.0); clip(buf)
        if (o.fades) { progress(97, "Fades…"); fade(buf, 0.015) }
        progress(100, "Listo ✔")
        return buf
    }

    private fun compress(b: AudioBuffer, thrDb: Double, ratio: Double, atkMs: Double, relMs: Double) {
        val fs = b.sampleRate
        val atk = exp(-1.0 / (fs * atkMs / 1000.0))
        val rel = exp(-1.0 / (fs * relMs / 1000.0))
        var gDb = 0.0
        for (i in 0 until b.frames) {
            var peak = 0f
            for (c in b.channels) { val a = abs(c[i]); if (a > peak) peak = a }
            val db = 20 * log10(max(peak.toDouble(), 1e-9))
            val target = if (db > thrDb) (thrDb - db) * (1 - 1 / ratio) else 0.0
            val coef = if (target < gDb) atk else rel
            gDb = target + coef * (gDb - target)
            val g = 10.0.pow(gDb / 20.0).toFloat()
            for (c in b.channels) c[i] = c[i] * g
        }
    }

    private fun normalize(b: AudioBuffer, targetDb: Double) {
        val p = b.peak()
        if (p < 1e-6f) return
        var g = (10.0.pow(targetDb / 20.0) / p).toFloat()
        if (g > 60f) g = 60f
        for (c in b.channels) for (i in c.indices) c[i] = c[i] * g
    }

    private fun clip(b: AudioBuffer) {
        for (c in b.channels) for (i in c.indices) c[i] = c[i].coerceIn(-0.999f, 0.999f)
    }

    private fun fade(b: AudioBuffer, sec: Double) {
        val n = min((b.sampleRate * sec).toInt(), b.frames / 2)
        if (n <= 1) return
        for (c in b.channels) {
            for (i in 0 until n) c[i] = c[i] * (i.toFloat() / n)
            for (i in 0 until n) c[c.size - 1 - i] = c[c.size - 1 - i] * (i.toFloat() / n)
        }
    }

    private fun trim(b: AudioBuffer): AudioBuffer {
        val thr = 10.0.pow(-45.0 / 20.0).toFloat()
        var start = 0
        var end = b.frames - 1
        loop@ for (i in 0 until b.frames) {
            for (c in b.channels) if (abs(c[i]) > thr) { start = i; break@loop }
        }
        loop2@ for (i in b.frames - 1 downTo 0) {
            for (c in b.channels) if (abs(c[i]) > thr) { end = i; break@loop2 }
        }
        val pad = b.sampleRate / 10
        start = max(0, start - pad)
        end = min(b.frames - 1, end + pad)
        if (end - start < b.sampleRate / 10) return b
        return AudioBuffer(b.sampleRate, Array(b.channelCount) { b.channels[it].copyOfRange(start, end + 1) })
    }
}
