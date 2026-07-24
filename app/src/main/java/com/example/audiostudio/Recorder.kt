package com.example.audiostudio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class Recorder(val sampleRate: Int = 48000, val channels: Int = 1) {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val chunks = ArrayList<FloatArray>()

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var recordedFrames: Long = 0L; private set

    var onLevel: ((Float) -> Unit)? = null      // 0..1 (pico)
    var onFrames: ((Long) -> Unit)? = null

    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(useHardwareCleanup: Boolean = false) {
        stop()
        chunks.clear(); recordedFrames = 0

        val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBuf > 0) { "El equipo no soporta esta configuración de audio" }
        val bufSize = max(minBuf * 4, sampleRate * channels * 4 / 2)

        val source = if (useHardwareCleanup) MediaRecorder.AudioSource.VOICE_RECOGNITION
                     else MediaRecorder.AudioSource.MIC

        val r = AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT, bufSize)
        check(r.state == AudioRecord.STATE_INITIALIZED) { "No se pudo iniciar el micrófono" }

        if (useHardwareCleanup) {
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(r.audioSessionId)?.apply { enabled = true }
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        }

        record = r
        running = true
        paused = false
        r.startRecording()

        worker = thread(name = "recorder") {
            val buf = FloatArray(4096 * channels)
            while (running) {
                val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                var peak = 0f
                for (i in 0 until n) { val a = abs(buf[i]); if (a > peak) peak = a }
                onLevel?.invoke(peak.coerceIn(0f, 1f))
                if (!paused) {
                    chunks.add(buf.copyOf(n))
                    recordedFrames += n / channels
                    onFrames?.invoke(recordedFrames)
                }
            }
        }
    }

    fun togglePause() { paused = !paused }

    /** Detiene y devuelve todo lo grabado. */
    fun stop(): FloatArray {
        running = false
        worker?.join(800); worker = null
        record?.let { try { it.stop() } catch (_: Throwable) {}; it.release() }
        record = null
        ns?.release(); ns = null
        agc?.release(); agc = null
        aec?.release(); aec = null
        return takeSamples()
    }

    private fun takeSamples(): FloatArray {
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var p = 0
        for (c in chunks) { System.arraycopy(c, 0, out, p, c.size); p += c.size }
        return out
    }
}
