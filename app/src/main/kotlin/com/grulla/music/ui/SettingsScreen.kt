package com.grulla.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.audio.AudioEngine
import com.grulla.music.audio.EngineChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(audio: AudioEngine, onBack: () -> Unit) {
    val state by audio.state.collectAsStateWithLifecycle()
    val pickSoundFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) audio.importSoundFont(uri)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sound", style = MaterialTheme.typography.titleMedium)
            EngineOption(
                title = "FluidSynth with a SoundFont",
                detail = if (state.fluidSynthSupported) {
                    "Better instruments, exact sync with the highlighting, gapless loops."
                } else {
                    "Needs Android 8.1 or newer."
                },
                selected = state.choice == EngineChoice.FLUIDSYNTH,
                enabled = state.fluidSynthSupported,
                onClick = { audio.setChoice(EngineChoice.FLUIDSYNTH) },
            )
            EngineOption(
                title = "Android built-in MIDI",
                detail = "Basic sound, works everywhere.",
                selected = state.choice == EngineChoice.ANDROID_MIDI,
                enabled = true,
                onClick = { audio.setChoice(EngineChoice.ANDROID_MIDI) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(state.status, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("SoundFont", style = MaterialTheme.typography.titleMedium)
            Text(state.soundFontName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Use any General MIDI SoundFont (.sf2 or .sf3), for example MuseScore_General or FluidR3_GM. " +
                    "It is copied into the app.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickSoundFont.launch(arrayOf("application/octet-stream", "audio/x-soundfont", "audio/sf2", "*/*")) },
                    enabled = !state.busy && state.choice == EngineChoice.FLUIDSYNTH,
                ) { Text("Choose SoundFont…") }
                if (state.customSoundFont) {
                    OutlinedButton(onClick = audio::useDefaultSoundFont, enabled = !state.busy) { Text("Use bundled") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "Grulla Music uses separate open-source libraries:\n" +
                    "• Verovio 6.3.0 (music engraving), LGPL-3.0-or-later\n" +
                    "• FluidSynth 2.5.7 (synthesizer) with glib, libsndfile, Ogg/Vorbis, FLAC, Opus and Oboe, " +
                    "under LGPL-2.1-or-later, BSD and Apache-2.0 licenses\n" +
                    "• FluidR3 Mono GM SoundFont, MIT\n" +
                    "• AndroidX and Kotlin libraries, Apache-2.0\n" +
                    "License texts are included in the app's assets.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EngineOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
