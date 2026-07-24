package com.example.audiostudio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Exportador de audio. Recibe las muestras en float (-1..1, intercaladas)
 * y escribe el archivo en el formato elegido.
 */
object Exporter {

    enum class Format(val ext: String, val label: String, val mime: String, val lossy: Boolean) {
        WAV16("wav", "WAV 16 bit (CD)", "audio/wav", false),
        WAV24("wav", "WAV 24 bit (estudio)", "audio/wav", false),
        WAV32("wav", "WAV 32 bit float", "audio/wav", false),
        FLAC("flac", "FLAC (sin pérdida)", "audio/flac", false),
        M4A("m4a", "M4A / AAC", "audio/mp4", true),
        OGG("ogg", "OGG / Opus", "audio/ogg", true);
    }

    /** Formatos disponibles según la versión de Android del teléfono. */
    fun availableFormats(): List<Format> =
        Format.values().filter { it != Format.OGG || Build.VERSION.SDK_INT >= 29 }

    fun bitratesFor(f: Format): List<Int> = when (f) {
        Format.M4A -> listOf(96, 128, 192, 256, 320)
        Format.OGG -> listOf(64, 96, 128, 160, 256)
        else -> emptyList()
    }

    /**
     * Exporta a un archivo temporal. Devuelve el archivo realmente escrito
     * (si un códec no existiera en el equipo, cae automáticamente a WAV 16).
     */
    fun export(
        samples: FloatArray,
        channels: Int,
        sampleRate: Int,
        format: Format,
        bitrateKbps: Int,
        outFile: File,
        onProgress: (Float) -> Unit = {}
    ): File {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        return try {
            when (format) {
                Format.WAV16 -> writeWav(outFile, samples, channels, sampleRate, 16, false, onProgress)
                Format.WAV24 -> writeWav(outFile, samples, channels, sampleRate, 24, false, onProgress)
                Format.WAV32 -> writeWav(outFile, samples, channels, sampleRate, 32, true, onProgress)
                Format.FLAC -> encodeFlac(samples, channels, sampleRate, outFile, onProgress)
                Format.M4A -> encodeMuxed(
                    samples, channels, sampleRate,
                    MediaFormat.MIMETYPE_AUDIO_AAC, bitrateKbps * 1000,
                    outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, onProgress
                )
                Format.OGG -> {
                    if (Build.VERSION.SDK_INT < 29) error("OGG/Opus necesita Android 10 o superior")
                    // Opus trabaja a 48 kHz: si hace falta, remuestreamos.
                    var s = samples
                    var sr = sampleRate
                    if (sr != 48000) { s = resampleLinear(samples, channels, sr, 48000); sr = 48000 }
                    encodeMuxed(
                        s, channels, sr, "audio/opus", bitrateKbps * 1000,
                        outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, onProgress
                    )
                }
            }
            outFile
        } catch (e: Throwable) {
            // Plan B: nunca perdemos la grabación.
            val fallback = File(outFile.parentFile, outFile.nameWithoutExtension + ".wav")
            writeWav(fallback, samples, channels, sampleRate, 16, false, onProgress)
            fallback
        }
    }

    // ---------------------------------------------------------------- WAV ---

    private fun writeWav(
        file: File, samples: FloatArray, channels: Int, sampleRate: Int,
        bits: Int, isFloat: Boolean, onProgress: (Float) -> Unit
    ): File {
        val bytesPerSample = bits / 8
        val dataSize = samples.size * bytesPerSample
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            out.write(wavHeader(dataSize, channels, sampleRate, bits, isFloat))
            val chunk = 8192
            val buf = ByteBuffer.allocate(chunk * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                buf.clear()
                val end = min(i + chunk, samples.size)
                while (i < end) {
                    val v = samples[i].coerceIn(-1f, 1f)
                    when {
                        isFloat -> buf.putFloat(v)
                        bits == 16 -> buf.putShort((v * 32767f).roundToInt().toShort())
                        else -> { // 24 bits
                            val x = (v * 8388607f).roundToInt()
                            buf.put((x and 0xFF).toByte())
                            buf.put(((x shr 8) and 0xFF).toByte())
                            buf.put(((x shr 16) and 0xFF).toByte())
                        }
                    }
                    i++
                }
                out.write(buf.array(), 0, buf.position())
                onProgress(i.toFloat() / samples.size)
            }
        }
        onProgress(1f)
        return file
    }

    private fun wavHeader(
        dataSize: Int, channels: Int, sampleRate: Int, bits: Int, isFloat: Boolean
    ): ByteArray {
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataSize); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(if (isFloat) 3 else 1)          // 1 = PCM entero, 3 = IEEE float
        b.putShort(channels.toShort()); b.putInt(sampleRate); b.putInt(byteRate)
        b.putShort(blockAlign.toShort()); b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(dataSize)
        return b.array()
    }

    // --------------------------------------------------------------- FLAC ---

    private fun encodeFlac(
        samples: FloatArray, channels: Int, sampleRate: Int, outFile: File,
        onProgress: (Float) -> Unit
    ): File {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, sampleRate * channels * 16)
        fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        BufferedOutputStream(FileOutputStream(outFile), 1 shl 16).use { out ->
            runCodec(codec, samples, channels, sampleRate, onProgress) { buffer, info, isConfig ->
                val bytes = ByteArray(info.size)
                buffer.position(info.offset); buffer.get(bytes)
                if (isConfig || bytes.isNotEmpty()) out.write(bytes)
            }
        }
        onProgress(1f)
        return outFile
    }

    // -------------------------------------------------- AAC (M4A) / OPUS ---

    private fun encodeMuxed(
        samples: FloatArray, channels: Int, sampleRate: Int, mime: String,
        bitRate: Int, outFile: File, muxerFormat: Int, onProgress: (Float) -> Unit
    ): File {
        val codec = MediaCodec.createEncoderByType(mime)
        val fmt = MediaFormat.createAudioFormat(mime, sampleRate, channels)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outFile.absolutePath, muxerFormat)
        var track = -1
        var started = false

        runCodec(codec, samples, channels, sampleRate, onProgress,
            onFormatChanged = { f -> track = muxer.addTrack(f); muxer.start(); started = true }
        ) { buffer, info, isConfig ->
            if (!isConfig && started && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                muxer.writeSampleData(track, buffer, info)
            }
        }

        if (started) muxer.stop()
        muxer.release()
        onProgress(1f)
        return outFile
    }

    /** Bucle genérico de MediaCodec: mete PCM 16 bits y entrega paquetes comprimidos. */
    private inline fun runCodec(
        codec: MediaCodec,
        samples: FloatArray,
        channels: Int,
        sampleRate: Int,
        crossinline onProgress: (Float) -> Unit,
        crossinline onFormatChanged: (MediaFormat) -> Unit = {},
        crossinline onData: (ByteBuffer, MediaCodec.BufferInfo, Boolean) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        val totalFrames = samples.size / channels
        val bytesPerFrame = 2 * channels
        var framesFed = 0
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val cap = buf.capacity() / bytesPerFrame
                        val n = min(cap, totalFrames - framesFed)
                        val ptsUs = framesFed.toLong() * 1_000_000L / sampleRate
                        if (n > 0) {
                            var i = framesFed * channels
                            val end = i + n * channels
                            while (i < end) {
                                buf.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                                i++
                            }
                            codec.queueInputBuffer(inIdx, 0, n * bytesPerFrame, ptsUs, 0)
                            framesFed += n
                            onProgress(0.95f * framesFed / totalFrames.coerceAtLeast(1))
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec.outputFormat)
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (info.size > 0) onData(out, info, isConfig)
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
        }
    }

    // -------------------------------------------------------- utilidades ---

    private fun resampleLinear(input: FloatArray, ch: Int, from: Int, to: Int): FloatArray {
        val inFrames = input.size / ch
        val outFrames = (inFrames.toLong() * to / from).toInt()
        val out = FloatArray(outFrames * ch)
        val step = from.toDouble() / to
        for (f in 0 until outFrames) {
            val src = f * step
            val i0 = src.toInt()
            val i1 = min(i0 + 1, inFrames - 1)
            val t = (src - i0).toFloat()
            for (c in 0 until ch) {
                val a = input[i0 * ch + c]; val b = input[i1 * ch + c]
                out[f * ch + c] = a + (b - a) * t
            }
        }
        return out
    }

    /** Copia el archivo a Música/AudioStudio para que quede visible en el teléfono. */
    fun saveToMusic(context: Context, src: File, displayName: String, mime: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AudioStudio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
                ?: error("No se pudo crear el archivo en Música")
            cr.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            cv.clear(); cv.put(MediaStore.Audio.Media.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
            "Música/AudioStudio/$displayName"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AudioStudio")
            dir.mkdirs()
            val dst = File(dir, displayName)
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        }
    }
}
