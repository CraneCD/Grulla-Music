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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.R
import com.grulla.music.audio.AudioEngine
import com.grulla.music.audio.AudioState
import com.grulla.music.audio.EngineChoice
import com.grulla.music.core.listen.ChordSensitivity
import com.grulla.music.settings.AppLanguage
import com.grulla.music.settings.PlayerPreferences
import com.grulla.music.settings.PlayerSettings

@Composable
fun SettingsScreen(audio: AudioEngine, appearance: AppearanceSettings, player: PlayerSettings, onBack: () -> Unit) {
    val state by audio.state.collectAsStateWithLifecycle()
    val mode by appearance.mode.collectAsStateWithLifecycle()
    val playerPreferences by player.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        language = AppLanguage.current(context),
        version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() },
        onLanguage = { AppLanguage.set(context, it) },
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
    /** The chosen app language, or null where Android has no per-app language (before 13). */
    language: AppLanguage? = null,
    onLanguage: (AppLanguage) -> Unit = {},
    version: String? = null,
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
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section(stringResource(R.string.appearance)) {
                Column(Modifier.selectableGroup()) {
                    Choice(stringResource(R.string.theme_system), stringResource(R.string.theme_system_detail), themeMode == ThemeMode.SYSTEM) {
                        onThemeMode(ThemeMode.SYSTEM)
                    }
                    Choice(stringResource(R.string.theme_day), stringResource(R.string.theme_day_detail), themeMode == ThemeMode.LIGHT) {
                        onThemeMode(ThemeMode.LIGHT)
                    }
                    Choice(stringResource(R.string.theme_night), stringResource(R.string.theme_night_detail), themeMode == ThemeMode.DARK) {
                        onThemeMode(ThemeMode.DARK)
                    }
                }
            }

            if (language != null) {
                Section(stringResource(R.string.language)) {
                    Column(Modifier.selectableGroup()) {
                        Choice(stringResource(R.string.language_system), stringResource(R.string.language_system_detail), language == AppLanguage.SYSTEM) {
                            onLanguage(AppLanguage.SYSTEM)
                        }
                        // Each language is named in itself.
                        Choice("English", null, language == AppLanguage.ENGLISH) { onLanguage(AppLanguage.ENGLISH) }
                        Choice("Español", null, language == AppLanguage.SPANISH) { onLanguage(AppLanguage.SPANISH) }
                    }
                }
            }

            Section(stringResource(R.string.reading)) {
                Toggle(
                    stringResource(R.string.early_turn),
                    stringResource(R.string.early_turn_detail),
                    player.earlyPageTurn,
                ) { on -> onPlayer { it.copy(earlyPageTurn = on) } }
                Toggle(
                    stringResource(R.string.arrow_keys),
                    stringResource(R.string.arrow_keys_detail),
                    player.arrowKeysTurnPages,
                ) { on -> onPlayer { it.copy(arrowKeysTurnPages = on) } }
            }

            Section(stringResource(R.string.follow_me)) {
                Toggle(
                    stringResource(R.string.all_chord_notes),
                    stringResource(R.string.all_chord_notes_detail),
                    player.chordsNeedAllNotes,
                ) { on -> onPlayer { it.copy(chordsNeedAllNotes = on) } }
                if (player.chordsNeedAllNotes) {
                    Text(stringResource(R.string.how_strict), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    Column(Modifier.selectableGroup()) {
                        Choice(stringResource(R.string.strict_relaxed), stringResource(R.string.strict_relaxed_detail), player.chordSensitivity == ChordSensitivity.RELAXED) {
                            onPlayer { it.copy(chordSensitivity = ChordSensitivity.RELAXED) }
                        }
                        Choice(stringResource(R.string.strict_normal), stringResource(R.string.strict_normal_detail), player.chordSensitivity == ChordSensitivity.NORMAL) {
                            onPlayer { it.copy(chordSensitivity = ChordSensitivity.NORMAL) }
                        }
                        Choice(stringResource(R.string.strict_strict), stringResource(R.string.strict_strict_detail), player.chordSensitivity == ChordSensitivity.STRICT) {
                            onPlayer { it.copy(chordSensitivity = ChordSensitivity.STRICT) }
                        }
                    }
                }
            }

            Section(stringResource(R.string.sound)) {
                Column(Modifier.selectableGroup()) {
                    Choice(
                        stringResource(R.string.engine_fluidsynth),
                        stringResource(if (audio.fluidSynthSupported) R.string.engine_fluidsynth_detail else R.string.engine_fluidsynth_unsupported),
                        audio.choice == EngineChoice.FLUIDSYNTH,
                        enabled = audio.fluidSynthSupported,
                    ) { onEngine(EngineChoice.FLUIDSYNTH) }
                    Choice(stringResource(R.string.engine_android), stringResource(R.string.engine_android_detail), audio.choice == EngineChoice.ANDROID_MIDI) {
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
                    stringResource(R.string.soundfont_explained),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.graphite,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    GhostButton(stringResource(R.string.choose_soundfont), onClick = onChooseSoundFont, enabled = !audio.busy && audio.choice == EngineChoice.FLUIDSYNTH)
                    if (audio.customSoundFont) GhostButton(stringResource(R.string.use_bundled), onClick = onBundledSoundFont, enabled = !audio.busy)
                }
            }

            Section(stringResource(R.string.about)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CraneMark(size = 36.dp)
                    Column {
                        Text("Grulla Music", style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(version?.let { stringResource(R.string.version, it) }, "Crane & Ink").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.graphite,
                        )
                    }
                }
                Text(
                    stringResource(R.string.privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.graphite,
                )
                Text(
                    stringResource(R.string.about_libraries),
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
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(top = 10.dp)
                .semantics { heading() },
        )
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
private fun Choice(title: String, detail: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
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
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.graphite)
        }
    }
}
