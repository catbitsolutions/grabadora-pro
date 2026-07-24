package com.example.audiostudio

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val hasTake: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val listenProcessed: Boolean = true,
    val hasProcessed: Boolean = false,
    val working: Boolean = false,
    val progress: Float = 0f,
    val settings: EnhanceSettings = EnhanceSettings(),
    val hwCleanup: Boolean = false,
    val format: Exporter.Format = Exporter.Format.WAV16,
    val bitrate: Int = 256,
    val status: String = "Listo para grabar"
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var ui by mutableStateOf(UiState()); private set

    private val recorder = Recorder(sampleRate = 48000, channels = 1)
    private val player = Player()

    private var original: FloatArray? = null
    private var processed: FloatArray? = null
    private val sr get() = recorder.sampleRate
    private val ch get() = recorder.channels

    init {
        recorder.onLevel = { l -> ui = ui.copy(level = l) }
        player.onPosition = { f -> ui = ui.copy(positionMs = f * 1000L / sr) }
        player.onFinished = { ui = ui.copy(playing = false, positionMs = 0) }
    }

    private fun current(): FloatArray? =
        if (ui.listenProcessed && processed != null) processed else original

    fun setStatus(t: String) { ui = ui.copy(status = t) }
    fun setHwCleanup(v: Boolean) { ui = ui.copy(hwCleanup = v) }
    fun updateSettings(block: (EnhanceSettings) -> EnhanceSettings) {
        ui = ui.copy(settings = block(ui.settings))
    }
    fun setFormat(f: Exporter.Format) {
        val br = Exporter.bitratesFor(f)
        ui = ui.copy(format = f, bitrate = if (br.isEmpty()) 0 else br.getOrElse(3) { br.last() })
    }
    fun setBitrate(b: Int) { ui = ui.copy(bitrate = b) }
    fun setListenProcessed(v: Boolean) {
        val wasPlaying = ui.playing
        stopPlayback()
        ui = ui.copy(listenProcessed = v, positionMs = 0)
        if (wasPlaying) playPause()
    }

    // ------------------------------------------------------------ grabar ---
    fun startRecording() {
        try {
            stopPlayback()
            original = null; processed = null
            recorder.start(ui.hwCleanup)
            ui = ui.copy(recording = true, paused = false, hasTake = false, hasProcessed = false,
                         elapsedMs = 0, positionMs = 0, status = "Grabando…")
            viewModelScope.launch {
                while (recorder.isRecording) {
                    ui = ui.copy(elapsedMs = recorder.recordedFrames * 1000L / sr,
                                 paused = recorder.paused)
                    delay(100)
                }
            }
        } catch (e: Throwable) {
            ui = ui.copy(recording = false, status = "Error: ${e.message}")
        }
    }

    fun togglePause() { recorder.togglePause(); ui = ui.copy(paused = recorder.paused) }

    fun stopRecording() {
        val data = recorder.stop()
        original = data
        processed = null
        ui = ui.copy(
            recording = false, paused = false, level = 0f,
            hasTake = data.isNotEmpty(), hasProcessed = false,
            durationMs = data.size / ch * 1000L / sr, positionMs = 0,
            listenProcessed = false,
            status = if (data.isEmpty()) "No se capturó audio" else "Grabación lista: escuchala antes de guardar"
        )
    }

    // -------------------------------------------------------- pre-escucha ---
    fun playPause() {
        val data = current() ?: return
        if (ui.playing) { player.stop(); ui = ui.copy(playing = false) }
        else {
            val startFrame = (ui.positionMs * sr / 1000L).toInt()
            player.play(data, sr, ch, if (startFrame >= data.size / ch - 10) 0 else startFrame)
            ui = ui.copy(playing = true)
        }
    }

    fun seekTo(ms: Long) {
        val data = current() ?: return
        val clamped = ms.coerceIn(0, ui.durationMs)
        if (ui.playing) {
            player.stop()
            player.play(data, sr, ch, (clamped * sr / 1000L).toInt())
        }
        ui = ui.copy(positionMs = clamped)
    }

    fun stopPlayback() { player.stop(); ui = ui.copy(playing = false) }

    // ------------------------------------------------------------ mejorar ---
    fun applyEnhance() {
        val src = original ?: return
        stopPlayback()
        ui = ui.copy(working = true, progress = 0f, status = "Mejorando audio…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                Enhancer.process(src.copyOf(), ch, sr, ui.settings) { p ->
                    ui = ui.copy(progress = p)
                }
            }
            processed = result
            ui = ui.copy(
                working = false, progress = 1f, hasProcessed = true, listenProcessed = true,
                durationMs = result.size / ch * 1000L / sr, positionMs = 0,
                status = "¡Listo! Comparás con el interruptor Original / Mejorado"
            )
        }
    }

    // ----------------------------------------------------------- exportar ---
    fun export() {
        val data = current() ?: return
        stopPlayback()
        ui = ui.copy(working = true, progress = 0f, status = "Exportando…")
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "Grabacion_$stamp"
                val tmp = File(ctx.cacheDir, "$name.${ui.format.ext}")
                val written = withContext(Dispatchers.Default) {
                    Exporter.export(data, ch, sr, ui.format, ui.bitrate, tmp) { p ->
                        ui = ui.copy(progress = p)
                    }
                }
                val finalName = "$name.${written.extension}"
                val where = withContext(Dispatchers.IO) {
                    Exporter.saveToMusic(ctx, written, finalName, ui.format.mime)
                }
                written.delete()
                ui = ui.copy(working = false, status = "Guardado en: $where")
            } catch (e: Throwable) {
                ui = ui.copy(working = false, status = "Error al exportar: ${e.message}")
            }
        }
    }

    fun discard() {
        stopPlayback()
        original = null; processed = null
        ui = ui.copy(hasTake = false, hasProcessed = false, durationMs = 0, positionMs = 0,
                     elapsedMs = 0, status = "Grabación eliminada")
    }

    override fun onCleared() {
        super.onCleared()
        player.stop()
        if (recorder.isRecording) recorder.stop()
    }
}
