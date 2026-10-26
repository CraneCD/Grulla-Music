package com.grulla.music.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.ListeningState
import com.grulla.music.ScoreUiState
import com.grulla.music.ScoreViewModel
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.TempoRange
import com.grulla.music.core.playback.Transport
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreView
import kotlin.math.roundToInt

/** Everything the score screen can ask for; implemented by [ScoreViewModel]. */
interface ScoreActions {
    fun togglePlay()
    fun stop()
    fun setTempoPercent(percent: Int)
    fun setCountIn(enabled: Boolean)
    fun setMetronome(enabled: Boolean)
    fun setLoopEnabled(enabled: Boolean)
    fun clearLoop()
    /** Explains how to set a loop (the Loop chip when no loop exists). */
    fun explainLoop()
    fun setFollow(enabled: Boolean)
    fun showPage(page: Int)
    fun seekToFraction(fraction: Float)
    fun zoomIn()
    fun zoomOut()
    fun toggleLayoutMode()
    fun toggleMute(track: Int)
    fun toggleSolo(track: Int)
    fun stopListening()
    fun skipNote()
}

@Composable
fun ScoreScreen(viewModel: ScoreViewModel, night: Boolean, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startListening() else viewModel.showNotice("Follow me needs permission to use the microphone.")
    }
    val toggleListening = {
        when {
            ui.listening != null -> viewModel.stopListening()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                viewModel.startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissError()
    }
    LaunchedEffect(ui.notice) {
        val message = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissNotice()
    }
    ScoreScreenContent(
        ui = ui,
        playback = playback,
        bar = viewModel.barAt(playback.positionMs),
        actions = viewModel,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onListen = toggleListening,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { modifier ->
        ScoreView(
            commands = viewModel.webCommands,
            callbacks = viewModel,
            scoreBytes = viewModel::currentScoreBytes,
            night = night,
            modifier = modifier,
        )
    }
}

/** Current bar and bar count, for the top bar ("bar 5 of 16"). */
data class BarInfo(val bar: Int, val total: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreenContent(
    ui: ScoreUiState,
    playback: PlaybackState,
    bar: BarInfo?,
    actions: ScoreActions,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onListen: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    score: @Composable (Modifier) -> Unit,
) {
    val c = Grulla.colors
    var showMixer by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = c.paper,
        topBar = {
            ScoreTopBar(ui, bar, onBack, onOpenSettings, onParts = { showMixer = true }, actions = actions)
        },
        bottomBar = {
            val listening = ui.listening
            if (listening != null) {
                ListeningDock(listening, actions)
            } else {
                TransportDock(ui, playback, actions, onListen = onListen, onTempo = { showTempo = true })
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Box(
            Modifier
                // The dock's rounded corners sit over the score.
                .padding(top = padding.calculateTopPadding(), bottom = (padding.calculateBottomPadding() - 20.dp).coerceAtLeast(0.dp))
                .fillMaxSize()
                .background(c.sheet),
        ) {
            score(Modifier.fillMaxSize())
            if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.crown)
            if (ui.layoutMode == LayoutMode.PAGE && ui.pageCount > 1) {
                PagePill(ui, actions, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
            }
        }
    }

    if (showMixer) {
        ModalBottomSheet(
            onDismissRequest = { showMixer = false },
            containerColor = c.sheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MixerSheet(ui, actions)
        }
    }
    if (showTempo) {
        ModalBottomSheet(
            onDismissRequest = { showTempo = false },
            containerColor = c.sheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TempoSheet(playback.tempoPercent, actions::setTempoPercent)
        }
    }
}

@Composable
private fun ScoreTopBar(
    ui: ScoreUiState,
    bar: BarInfo?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onParts: () -> Unit,
    actions: ScoreActions,
) {
    val c = Grulla.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.paper)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Library") }
        Column(Modifier.weight(1f)) {
            Text(ui.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                ui.composer,
                when {
                    ui.listening != null -> "listening"
                    bar != null && bar.total > 0 -> "bar ${bar.bar} of ${bar.total}"
                    else -> null
                },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ui.mixer.size > 1) {
            IconButton(onClick = onParts) { Icon(AppIcons.Parts, contentDescription = "Parts") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "View options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.sheet) {
                DropdownMenuItem(
                    text = { Text("Zoom in") },
                    leadingIcon = { Icon(AppIcons.ZoomIn, contentDescription = null) },
                    trailingIcon = { Text("${ui.scale}%", style = MaterialTheme.typography.bodySmall, color = c.graphite) },
                    onClick = actions::zoomIn,
                )
                DropdownMenuItem(
                    text = { Text("Zoom out") },
                    leadingIcon = { Icon(AppIcons.ZoomOut, contentDescription = null) },
                    onClick = actions::zoomOut,
                )
                HorizontalDivider(color = c.line)
                DropdownMenuItem(
                    text = { Text(if (ui.layoutMode == LayoutMode.PAGE) "Continuous scroll" else "Page view") },
                    leadingIcon = {
                        Icon(if (ui.layoutMode == LayoutMode.PAGE) AppIcons.ViewScroll else AppIcons.ViewPages, contentDescription = null)
                    },
                    onClick = {
                        menu = false
                        actions.toggleLayoutMode()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Keep notes on screen") },
                    leadingIcon = { Icon(AppIcons.Check, contentDescription = null, tint = if (ui.follow) c.indigo else Color.Transparent) },
                    onClick = { actions.setFollow(!ui.follow) },
                )
                HorizontalDivider(color = c.line)
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(AppIcons.Settings, contentDescription = null) },
                    onClick = {
                        menu = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

@Composable
private fun PagePill(ui: ScoreUiState, actions: ScoreActions, modifier: Modifier) {
    val c = Grulla.colors
    Surface(modifier = modifier, shape = CircleShape, color = c.sheet, shadowElevation = 4.dp, border = BorderStroke(1.dp, c.line)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.showPage(ui.page - 1) }, enabled = ui.page > 1) {
                Icon(AppIcons.ChevronLeft, contentDescription = "Previous page")
            }
            Text("${ui.page} / ${ui.pageCount}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
            IconButton(onClick = { actions.showPage(ui.page + 1) }, enabled = ui.page < ui.pageCount) {
                Icon(AppIcons.ChevronRight, contentDescription = "Next page")
            }
        }
    }
}

/** The bottom dock while playing: progress, Stop / Play / tempo, and practice toggles. */
@Composable
private fun TransportDock(ui: ScoreUiState, playback: PlaybackState, actions: ScoreActions, onListen: () -> Unit, onTempo: () -> Unit) {
    val c = Grulla.colors
    Dock {
        val duration = playback.durationMs.coerceAtLeast(1.0)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatTime(playback.positionMs), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
            ProgressTrack(
                fraction = (playback.positionMs / duration).toFloat(),
                loop = playback.loop?.let { (it.startMs / duration).toFloat()..(it.endMs / duration).toFloat() },
                onSeek = actions::seekToFraction,
                enabled = playback.hasScore,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTime(playback.durationMs),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = c.graphite,
            )
        }
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundOutlineButton(AppIcons.Stop, "Stop", onClick = actions::stop, enabled = playback.hasScore)
                FollowMeButton(onListen, enabled = playback.hasScore)
            }
            PlayButton(playback, actions::togglePlay, Modifier.align(Alignment.Center))
            Surface(
                onClick = onTempo,
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, c.line),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(44.dp)
                    .semantics { contentDescription = "Tempo ${playback.tempoPercent} percent, change" },
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Metronome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${playback.tempoPercent}%", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GrullaChip("Count-in", playback.countInEnabled, onClick = { actions.setCountIn(!playback.countInEnabled) })
            GrullaChip("Metronome", playback.metronomeEnabled, onClick = { actions.setMetronome(!playback.metronomeEnabled) })
            val loop = playback.loop
            if (loop == null) {
                GrullaChip("Loop", selected = false, onClick = actions::explainLoop)
            } else {
                GrullaChip(
                    label = ui.loopLabel ?: "Loop",
                    selected = playback.loopEnabled,
                    onClick = { actions.setLoopEnabled(!playback.loopEnabled) },
                    trailing = {
                        Icon(
                            AppIcons.Close,
                            contentDescription = "Clear loop",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = "Clear loop", onClick = actions::clearLoop)
                                .padding(6.dp),
                        )
                    },
                )
            }
        }
    }
}

/** Starts Follow me: indigo, because it is about the player ("you"). */
@Composable
private fun FollowMeButton(onClick: () -> Unit, enabled: Boolean) {
    val c = Grulla.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = c.indigoTint,
        contentColor = c.onIndigoTint,
        border = BorderStroke(1.dp, c.indigo),
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = "Follow me: listen to my playing" },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(20.dp)) }
    }
}

/** The big round play button: crown red, with a halo while sound is running. */
@Composable
private fun PlayButton(playback: PlaybackState, onClick: () -> Unit, modifier: Modifier) {
    val c = Grulla.colors
    val running = playback.isRunning
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (running) c.crownHalo else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            enabled = playback.hasScore,
            shape = CircleShape,
            color = if (playback.hasScore) c.crown else c.line,
            contentColor = c.onCrown,
            modifier = Modifier
                .size(64.dp)
                .semantics { contentDescription = if (running) "Pause" else "Play" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (playback.transport == Transport.PREPARING) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = c.onCrown, strokeWidth = 2.5.dp)
                } else {
                    Icon(if (running) AppIcons.Pause else AppIcons.Play, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** The dock in Follow me: what to play next, what was heard, and the way out. */
@Composable
private fun ListeningDock(state: ListeningState, actions: ScoreActions) {
    val c = Grulla.colors
    Dock {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.indigoTint)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(c.indigoHalo),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(c.indigo), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Mic, contentDescription = null, tint = c.onIndigo, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Play", style = MaterialTheme.typography.labelMedium, color = c.onIndigoTint)
                Text(
                    state.expected,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 1.1f),
                    color = c.onIndigoTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${state.step} / ${state.total}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = c.onIndigoTint,
                )
                Text(state.part, style = MaterialTheme.typography.bodySmall, color = c.onIndigoTint, maxLines = 1)
                Text("Hearing ${state.hearing}", style = MaterialTheme.typography.bodySmall, color = c.onIndigoTint)
            }
        }
        val wrong = state.wrong
        if (wrong != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.amberContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AppIcons.Warning, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
                Text("Heard $wrong. Try ${state.expected}.", style = MaterialTheme.typography.labelLarge, color = c.onAmberContainer)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IndigoButton("Stop listening", onClick = actions::stopListening, modifier = Modifier.weight(1f))
            GhostButton("Skip note", onClick = actions::skipNote)
        }
    }
}

@Composable
private fun Dock(content: @Composable () -> Unit) {
    val c = Grulla.colors
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), ambientColor = Color.Black.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(c.sheet)
            .then(if (c.night) Modifier.border(1.dp, c.line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) else Modifier)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun MixerSheet(ui: ScoreUiState, actions: ScoreActions) {
    val c = Grulla.colors
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Parts", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Mute a part, or solo it to hear it alone. Follow me listens to the parts you can hear.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.graphite,
        )
        for (channel in ui.mixer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(channel.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                GrullaChip("Mute", channel.trackIndex in ui.mix.muted, onClick = { actions.toggleMute(channel.trackIndex) })
                GrullaChip("Solo", channel.trackIndex in ui.mix.soloed, onClick = { actions.toggleSolo(channel.trackIndex) })
            }
        }
    }
}

/** Tempo picker; applies when the slider is released, since every change re-prepares the music. */
@Composable
private fun TempoSheet(tempoPercent: Int, onTempo: (Int) -> Unit) {
    val c = Grulla.colors
    var value by remember { mutableFloatStateOf(tempoPercent.toFloat()) }
    fun apply(percent: Int) {
        value = TempoRange.clampPercent(percent).toFloat()
        onTempo(value.roundToInt())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tempo", style = MaterialTheme.typography.headlineSmall)
        Text("${value.roundToInt()}%", style = MaterialTheme.typography.displayMedium, color = c.ink)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onTempo(value.roundToInt()) },
            valueRange = TempoRange.MIN_PERCENT.toFloat()..TempoRange.MAX_PERCENT.toFloat(),
            steps = (TempoRange.MAX_PERCENT - TempoRange.MIN_PERCENT) / 5 - 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("−5", onClick = { apply(value.roundToInt() - 5) })
            GhostButton("+5", onClick = { apply(value.roundToInt() + 5) })
            Spacer(Modifier.weight(1f))
            GhostButton("Reset to 100%", onClick = { apply(TempoRange.DEFAULT_PERCENT) })
        }
    }
}

internal fun formatTime(ms: Double): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
