package com.example.audiostudio

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

object WavIO {

    fun pcmToWav(pcm: File, wav: File, sampleRate: Int, channels: Int) {
        BufferedOutputStream(FileOutputStream(wav), 1 shl 16).use { os ->
            os.write(header(pcm.length(), sampleRate, channels, 16, false))
            BufferedInputStream(FileInputStream(pcm), 1 shl 16).use { ins ->
                val b = ByteArray(1 shl 16)
                while (true) { val r = ins.read(b); if (r <= 0) break; os.write(b, 0, r) }
            }
        }
    }

    fun write(buf: AudioBuffer, out: File, bits: Int) {
        val ch = buf.channelCount
        val n = buf.frames
        val bps = bits / 8
        val dataLen = n.toLong() * ch * bps
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
            os.write(header(dataLen, buf.sampleRate, ch, bits, bits == 32))
            val block = 4096
            val bb = ByteBuffer.allocate(block * ch * bps).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < n) {
                val cnt = min(block, n - i)
                bb.clear()
                for (f in 0 until cnt) for (c in 0 until ch) {
                    val v = buf.channels[c][i + f].coerceIn(-1f, 1f)
                    when (bits) {
                        16 -> bb.putShort((v * 32767f).roundToInt().toShort())
                        24 -> {
                            val x = (v * 8388607f).roundToInt()
                            bb.put((x and 0xFF).toByte())
                            bb.put(((x shr 8) and 0xFF).toByte())
                            bb.put(((x shr 16) and 0xFF).toByte())
                        }
                        else -> bb.putFloat(v)
                    }
                }
                os.write(bb.array(), 0, bb.position())
                i += cnt
            }
        }
    }

    fun read(file: File): AudioBuffer {
        BufferedInputStream(FileInputStream(file), 1 shl 16).use { ins ->
            val riff = ByteArray(12)
            readFully(ins, riff, 12)
            require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "El archivo no es WAV" }
            var fmt = 1; var ch = 1; var fs = 44100; var bits = 16; var haveFmt = false
            while (true) {
                val h = ByteArray(8)
                if (!tryRead(ins, h)) throw IOException("WAV incompleto")
                val id = String(h, 0, 4)
                val size = le32(h, 4)
                if (id == "fmt ") {
                    val d = ByteArray(size); readFully(ins, d, size)
                    fmt = le16(d, 0); ch = le16(d, 2); fs = le32(d, 4); bits = le16(d, 14)
                    haveFmt = true
                } else if (id == "data") {
                    require(haveFmt) { "WAV sin cabecera fmt" }
                    val bps = bits / 8
                    val frames = size / (bps * ch)
                    val out = Array(ch) { FloatArray(frames) }
                    val block = 4096
                    val tmp = ByteArray(block * bps * ch)
                    var f = 0
                    while (f < frames) {
                        val cnt = min(block, frames - f)
                        val want = cnt * bps * ch
                        readFully(ins, tmp, want)
                        var p = 0
                        for (k in 0 until cnt) for (c in 0 until ch) {
                            out[c][f + k] = when {
                                bits == 16 -> le16s(tmp, p) / 32768f
                                bits == 24 -> le24s(tmp, p) / 8388608f
                                bits == 32 && fmt == 3 -> Float.fromBits(le32(tmp, p))
                                bits == 32 -> le32(tmp, p) / 2147483648f
                                bits == 8 -> ((tmp[p].toInt() and 0xFF) - 128) / 128f
                                else -> 0f
                            }
                            p += bps
                        }
                        f += cnt
                    }
                    return AudioBuffer(fs, out)
                } else {
                    skipFully(ins, (size + (size and 1)).toLong())
                }
            }
        }
    }

    private fun header(dataLen: Long, fs: Int, ch: Int, bits: Int, isFloat: Boolean): ByteArray {
        val byteRate = fs * ch * bits / 8
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36 + dataLen).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(if (isFloat) 3 else 1)
        bb.putShort(ch.toShort())
        bb.putInt(fs)
        bb.putInt(byteRate)
        bb.putShort((ch * bits / 8).toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen.toInt())
        return bb.array()
    }

    private fun readFully(ins: InputStream, b: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val r = ins.read(b, off, len - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }

    private fun tryRead(ins: InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val r = ins.read(b, off, b.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) { if (ins.read() < 0) return else left-- } else left -= s
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le16s(b: ByteArray, o: Int) = le16(b, o).toShort().toInt()
    private fun le24s(b: ByteArray, o: Int): Int {
        val v = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)
        return if (v and 0x800000 != 0) v or -0x1000000 else v
    }
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
