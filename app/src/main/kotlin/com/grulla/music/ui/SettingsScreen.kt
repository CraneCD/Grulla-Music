package com.grulla.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.audio.AudioEngine
import com.grulla.music.audio.AudioState
import com.grulla.music.audio.EngineChoice
import com.grulla.music.core.listen.ChordSensitivity
import com.grulla.music.settings.PlayerPreferences
import com.grulla.music.settings.PlayerSettings

@Composable
fun SettingsScreen(audio: AudioEngine, appearance: AppearanceSettings, player: PlayerSettings, onBack: () -> Unit) {
    val state by audio.state.collectAsStateWithLifecycle()
    val mode by appearance.mode.collectAsStateWithLifecycle()
    val playerPreferences by player.preferences.collectAsStateWithLifecycle()
    val pickSoundFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) audio.importSoundFont(uri)
    }
    SettingsContent(
        audio = state,
        themeMode = mode,
        onThemeMode = appearance::setMode,
        player = playerPreferences,
        onPlayer = player::update,
        onEngine = audio::setChoice,
        onChooseSoundFont = { pickSoundFont.launch(arrayOf("application/octet-stream", "audio/x-soundfont", "audio/sf2", "*/*")) },
        onBundledSoundFont = audio::useDefaultSoundFont,
        onBack = onBack,
    )
}

@Composable
fun SettingsContent(
    audio: AudioState,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    player: PlayerPreferences,
    onPlayer: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
    onEngine: (EngineChoice) -> Unit,
    onChooseSoundFont: () -> Unit,
    onBundledSoundFont: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Grulla.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Appearance") {
                Column(Modifier.selectableGroup()) {
                    Choice("Follow system", "Day or night with your phone's dark theme.", themeMode == ThemeMode.SYSTEM) { onThemeMode(ThemeMode.SYSTEM) }
                    Choice("Always day", "Paper and ink.", themeMode == ThemeMode.LIGHT) { onThemeMode(ThemeMode.LIGHT) }
                    Choice("Always night", "A dark page with light notes, for low light.", themeMode == ThemeMode.DARK) { onThemeMode(ThemeMode.DARK) }
                }
            }

            Section("Reading") {
                Toggle(
                    "Turn pages on the last bar",
                    "In page view, show the next page while the last bar of this one plays.",
                    player.earlyPageTurn,
                ) { on -> onPlayer { it.copy(earlyPageTurn = on) } }
                Toggle(
                    "Arrow keys turn pages",
                    "For Bluetooth page-turn pedals. Page Up and Page Down always turn pages.",
                    player.arrowKeysTurnPages,
                ) { on -> onPlayer { it.copy(arrowKeysTurnPages = on) } }
            }

            Section("Follow me") {
                Toggle(
                    "Chords: every note must sound",
                    "A chord counts only when all its notes are heard. Off: any one note of the chord is enough.",
                    player.chordsNeedAllNotes,
                ) { on -> onPlayer { it.copy(chordsNeedAllNotes = on) } }
                if (player.chordsNeedAllNotes) {
                    Text("How strict", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    Column(Modifier.selectableGroup()) {
                        Choice("Relaxed", "Soft or distant instruments, rooms with echo.", player.chordSensitivity == ChordSensitivity.RELAXED) {
                            onPlayer { it.copy(chordSensitivity = ChordSensitivity.RELAXED) }
                        }
                        Choice("Normal", "Piano and guitar close to the phone.", player.chordSensitivity == ChordSensitivity.NORMAL) {
                            onPlayer { it.copy(chordSensitivity = ChordSensitivity.NORMAL) }
                        }
                        Choice("Strict", "Every note clearly audible.", player.chordSensitivity == ChordSensitivity.STRICT) {
                            onPlayer { it.copy(chordSensitivity = ChordSensitivity.STRICT) }
                        }
                    }
                }
            }

            Section("Sound") {
                Column(Modifier.selectableGroup()) {
                    Choice(
                        "FluidSynth with a SoundFont",
                        if (audio.fluidSynthSupported) "Better instruments, exact sync with the highlighting, gapless loops." else "Needs Android 8.1 or newer.",
                        audio.choice == EngineChoice.FLUIDSYNTH,
                        enabled = audio.fluidSynthSupported,
                    ) { onEngine(EngineChoice.FLUIDSYNTH) }
                    Choice("Android built-in MIDI", "Basic sound, works everywhere.", audio.choice == EngineChoice.ANDROID_MIDI) {
                        onEngine(EngineChoice.ANDROID_MIDI)
                    }
                }
                HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (audio.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.indigo)
                    Text(audio.status, style = MaterialTheme.typography.bodyMedium, color = c.graphite)
                }
            }

            Section("SoundFont") {
                Text(audio.soundFontName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use any General MIDI SoundFont (.sf2 or .sf3), for example MuseScore_General or FluidR3_GM. It is copied into the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.graphite,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    GhostButton("Choose SoundFont…", onClick = onChooseSoundFont, enabled = !audio.busy && audio.choice == EngineChoice.FLUIDSYNTH)
                    if (audio.customSoundFont) GhostButton("Use bundled", onClick = onBundledSoundFont, enabled = !audio.busy)
                }
            }

            Section("About") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CraneMark(size = 36.dp)
                    Column {
                        Text("Grulla Music", style = MaterialTheme.typography.titleLarge)
                        Text("Crane & Ink", style = MaterialTheme.typography.bodySmall, color = c.graphite)
                    }
                }
                Text(
                    "Built on separate open-source libraries: Verovio 6.3.0 (LGPL-3.0-or-later); FluidSynth 2.5.7 with glib, " +
                        "libsndfile, Ogg/Vorbis, FLAC, Opus and Oboe (LGPL-2.1-or-later, BSD, Apache-2.0); FluidR3 Mono GM " +
                        "SoundFont (MIT); Fraunces and Inter fonts (SIL Open Font License); AndroidX and Kotlin (Apache-2.0). " +
                        "License texts are included in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.graphite,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp))
        SheetCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Grulla.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.graphite)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Grulla.colors
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.ink else c.graphite)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.graphite)
        }
    }
}
