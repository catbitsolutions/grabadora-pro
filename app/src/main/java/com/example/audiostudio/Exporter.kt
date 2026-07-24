package com.example.audiostudio

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Exporter {

    /**
     * Exporta un archivo PCM crudo a un archivo WAV estándar con encabezado RIFF.
     */
    fun exportToWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int) {
        val pcmData = pcmFile.readBytes()
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 16 / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen >> 8) and 0xff).toByte()
        header[6] = ((totalDataLen >> 16) and 0xff).toByte()
        header[7] = ((totalDataLen >> 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate >> 8) and 0xff).toByte()
        header[26] = ((sampleRate >> 16) and 0xff).toByte()
        header[27] = ((sampleRate >> 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate >> 8) and 0xff).toByte()
        header[30] = ((byteRate >> 16) and 0xff).toByte()
        header[31] = ((byteRate >> 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte() // data
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen >> 8) and 0xff).toByte()
        header[42] = ((totalAudioLen >> 16) and 0xff).toByte()
        header[43] = ((totalAudioLen >> 24) and 0xff).toByte()

        FileOutputStream(wavFile).use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }

    /**
     * Guarda el archivo final en la carpeta pública de Descargas de Android
     * para que el usuario pueda encontrarlo fácilmente.
     */
    fun saveToPublicDirectory(context: Context, srcFile: File, fileName: String): File? {
        val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val destFile = File(targetDir, fileName)
        return try {
            FileInputStream(srcFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}
