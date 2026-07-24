package com.example.audiostudio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/** Motor de grabación PCM crudo (máxima calidad, sin compresión). */
class RecorderEngine(
    val sampleRate: Int,
    val channelCount: Int,
    private val audioSource: Int,
    private val useSystemNs: Boolean
) {
    var onLevel: ((Float) -> Unit)? = null
    var onTimeMs: ((Long) -> Unit)? = null

    @Volatile var isRecording = false; private set
    @Volatile var isPaused = false
    @Volatile var totalFrames = 0L; private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun start(out: File) {
        val chCfg = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, chCfg, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "Configuración de audio no soportada por el dispositivo" }
        val bufSize = max(minBuf * 4, sampleRate * channelCount * 2 / 4)

        val ar = try {
            AudioRecord(audioSource, sampleRate, chCfg, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sampleRate, chCfg,
                AudioFormat.ENCODING_PCM_16BIT, bufSize)
        }
        check(ar.state == AudioRecord.STATE_INITIALIZED) { "No se pudo inicializar el micrófono" }

        if (useSystemNs) {
            runCatching { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(ar.audioSessionId).also { it.enabled = true } }
            runCatching { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(ar.audioSessionId).also { it.enabled = true } }
        }

        record = ar
        totalFrames = 0L
        isRecording = true
        isPaused = false
        ar.startRecording()
        thread = Thread { loop(out, ar) }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop(out: File, ar: AudioRecord) {
        val frames = 2048
        val shorts = ShortArray(frames * channelCount)
        val bytes = ByteArray(shorts.size * 2)
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
            var lastCb = 0L
            while (isRecording) {
                val read = ar.read(shorts, 0, shorts.size)
                if (read <= 0) continue
                var peak = 0
                for (i in 0 until read) {
                    val v = abs(shorts[i].toInt()); if (v > peak) peak = v
                }
                if (!isPaused) {
                    var bi = 0
                    for (i in 0 until read) {
                        val s = shorts[i].toInt()
                        bytes[bi++] = (s and 0xFF).toByte()
                        bytes[bi++] = ((s shr 8) and 0xFF).toByte()
                    }
                    os.write(bytes, 0, bi)
                    totalFrames += read / channelCount
                }
                val now = System.currentTimeMillis()
                if (now - lastCb > 60) {
                    lastCb = now
                    onLevel?.invoke(if (isPaused) 0f else peak / 32768f)
                    onTimeMs?.invoke(totalFrames * 1000L / sampleRate)
                }
            }
            os.flush()
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        runCatching { thread?.join(2500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        record = null; ns = null; agc = null; thread = null
    }
}
