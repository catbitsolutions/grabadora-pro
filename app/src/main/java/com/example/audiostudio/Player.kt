package com.example.audiostudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class Player {

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    @Volatile var positionFrames: Int = 0; private set
    val isPlaying: Boolean get() = running

    var onPosition: ((Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    fun play(samples: FloatArray, sampleRate: Int, channels: Int, startFrame: Int = 0) {
        stop()
        if (samples.isEmpty()) return
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask).build()
            )
            .setBufferSizeInBytes(max(minBuf, sampleRate * channels * 4 / 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        positionFrames = startFrame.coerceIn(0, samples.size / channels)
        running = true
        t.play()

        worker = thread(name = "player") {
            val totalFrames = samples.size / channels
            val block = 2048
            var f = positionFrames
            try {
                while (running && f < totalFrames) {
                    val n = min(block, totalFrames - f)
                    val written = t.write(samples, f * channels, n * channels, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                    f += written / channels
                    positionFrames = f
                    onPosition?.invoke(f)
                }
            } catch (_: Throwable) {
            } finally {
                val finished = f >= totalFrames
                running = false
                try { t.stop() } catch (_: Throwable) {}
                t.release()
                if (track === t) track = null
                if (finished) onFinished?.invoke()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        track?.let { try { it.stop() } catch (_: Throwable) {}; it.release() }
        track = null
    }

    fun seekTo(frame: Int) { positionFrames = max(0, frame) }
}
