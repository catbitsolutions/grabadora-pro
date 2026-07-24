package com.example.audiostudio

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot() }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val t = ms / 1000
    return String.format(Locale.US, "%02d:%02d.%01d", t / 60, t % 60, (ms % 1000) / 100)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val ui = vm.ui
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording() else vm.setStatus("Necesito permiso de micrófono")
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Audio Studio") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------------- Grabación ----------------
            ElevatedCard {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmt(if (ui.recording) ui.elapsedMs else ui.durationMs),
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ui.level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    Text("Nivel de entrada  •  48 kHz / 32 bit float",
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !ui.working,
                            onClick = {
                                if (ui.recording) vm.stopRecording()
                                else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        ) { Text(if (ui.recording) "■  Detener" else "●  Grabar") }
                        if (ui.recording) {
                            OutlinedButton(onClick = { vm.togglePause() }) {
                                Text(if (ui.paused) "▶ Reanudar" else "❚❚ Pausar")
                            }
                        }
                    }
                    if (!ui.recording && !ui.hasTake) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = ui.hwCleanup, onCheckedChange = { vm.setHwCleanup(it) })
                            Spacer(Modifier.width(8.dp))
                            Text("Filtro de ruido del teléfono (para voz)")
                        }
                    }
                }
            }

            // ---------------- Pre-escucha ----------------
            if (ui.hasTake) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pre-escucha", style = MaterialTheme.typography.titleMedium)
                        Slider(
                            value = ui.positionMs.toFloat(),
                            onValueChange = { vm.seekTo(it.toLong()) },
                            valueRange = 0f..(if (ui.durationMs > 0) ui.durationMs.toFloat() else 1f)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmt(ui.positionMs), style = MaterialTheme.typography.labelMedium)
                            Text(fmt(ui.durationMs), style = MaterialTheme.typography.labelMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.playPause() }, enabled = !ui.working) {
                                Text(if (ui.playing) "❚❚ Pausar" else "▶ Reproducir")
                            }
                            if (ui.hasProcessed) {
                                Switch(checked = ui.listenProcessed,
                                    onCheckedChange = { vm.setListenProcessed(it) })
                                Text(if (ui.listenProcessed) "Mejorado" else "Original")
                            }
                        }
                    }
                }

                // ---------------- Mejora / remasterización ----------------
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Mejorar / limpiar / remasterizar",
                            style = MaterialTheme.typography.titleMedium)

                        SwitchRow("Reducción de ruido de fondo", ui.settings.denoise) { v ->
                            vm.updateSettings { it.copy(denoise = v) }
                        }
                        if (ui.settings.denoise) SliderRow(
                            "Intensidad", ui.settings.denoiseAmount, 0f..1f
                        ) { v -> vm.updateSettings { it.copy(denoiseAmount = v) } }

                        SwitchRow("Quitar retumbe (pasa-altos 80 Hz)", ui.settings.highPassHz > 0f) { v ->
                            vm.updateSettings { it.copy(highPassHz = if (v) 80f else 0f) }
                        }
                        SwitchRow("Quitar zumbido eléctrico 50 Hz", ui.settings.humNotch == 50) { v ->
                            vm.updateSettings { it.copy(humNotch = if (v) 50 else 0) }
                        }
                        SwitchRow("De-esser (suavizar S)", ui.settings.deEsser) { v ->
                            vm.updateSettings { it.copy(deEsser = v) }
                        }
                        SwitchRow("Compresor (volumen parejo)", ui.settings.compress) { v ->
                            vm.updateSettings { it.copy(compress = v) }
                        }
                        if (ui.settings.compress) SliderRow(
                            "Fuerza del compresor", ui.settings.compressAmount, 0f..1f
                        ) { v -> vm.updateSettings { it.copy(compressAmount = v) } }

                        SliderRow("Calidez (graves) dB", ui.settings.warmthDb, -6f..6f) { v ->
                            vm.updateSettings { it.copy(warmthDb = v) }
                        }
                        SliderRow("Presencia (voz) dB", ui.settings.presenceDb, -6f..6f) { v ->
                            vm.updateSettings { it.copy(presenceDb = v) }
                        }
                        SliderRow("Aire (agudos) dB", ui.settings.airDb, -6f..6f) { v ->
                            vm.updateSettings { it.copy(airDb = v) }
                        }
                        SwitchRow("Recortar silencios al inicio/final", ui.settings.trimSilence) { v ->
                            vm.updateSettings { it.copy(trimSilence = v) }
                        }

                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { vm.applyEnhance() }, enabled = !ui.working,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("✨ Aplicar mejoras")
                        }
                    }
                }

                // ---------------- Exportar ----------------
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Exportar", style = MaterialTheme.typography.titleMedium)

                        var fmtOpen by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { fmtOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Formato: ${ui.format.label}")
                            }
                            DropdownMenu(expanded = fmtOpen, onDismissRequest = { fmtOpen = false }) {
                                Exporter.availableFormats().forEach { f ->
                                    DropdownMenuItem(text = { Text(f.label) },
                                        onClick = { vm.setFormat(f); fmtOpen = false })
                                }
                            }
                        }

                        val brs = Exporter.bitratesFor(ui.format)
                        if (brs.isNotEmpty()) {
                            var brOpen by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { brOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Calidad: ${ui.bitrate} kbps")
                                }
                                DropdownMenu(expanded = brOpen, onDismissRequest = { brOpen = false }) {
                                    brs.forEach { b ->
                                        DropdownMenuItem(text = { Text("$b kbps") },
                                            onClick = { vm.setBitrate(b); brOpen = false })
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.export() }, enabled = !ui.working,
                                modifier = Modifier.weight(1f)) { Text("💾 Guardar") }
                            OutlinedButton(onClick = { vm.discard() }, enabled = !ui.working,
                                modifier = Modifier.weight(1f)) { Text("🗑 Eliminar") }
                        }
                    }
                }
            }

            if (ui.working) {
                LinearProgressIndicator(
                    progress = { ui.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(ui.status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit
) {
    Column {
        Text("$label: ${String.format(Locale.US, "%.1f", value)}",
            style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
