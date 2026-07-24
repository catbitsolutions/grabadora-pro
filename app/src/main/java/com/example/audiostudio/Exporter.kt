package com.example.audiostudio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

object Exporter {

    data class Fmt(
        val label: String,
        val ext: String,
        val fileMime: String,
        val codecMime: String? = null,
        val container: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        val bits: Int = 16,
        val bitrate: Int = 192_000,
        val forceRate: Int = 0,
        val forceMono: Boolean = false
    )

    fun formats(): List<Fmt> {
        val list = mutableListOf(
            Fmt("WAV 16-bit · sin pérdida", "wav", "audio/wav", bits = 16),
            Fmt("WAV 24-bit · alta resolución", "wav", "audio/wav", bits = 24),
            Fmt("M4A/AAC 256 kbps · máxima", "m4a", "audio/mp4", "audio/mp4a-latm", bitrate = 256_000),
            Fmt("M4A/AAC 192 kbps · alta", "m4a", "audio/mp4", "audio/mp4a-latm", bitrate = 192_000),
            Fmt("M4A/AAC 128 kbps · normal", "m4a", "audio/mp4", "audio/mp4a-latm", bitrate = 128_000)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            list.add(
                Fmt("OGG/Opus 128 kbps", "ogg", "audio/ogg", "audio/opus",
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, bitrate = 128_000, forceRate = 48000)
            )
        }
        list.add(
            Fmt("3GP/AMR-WB · voz, mu
