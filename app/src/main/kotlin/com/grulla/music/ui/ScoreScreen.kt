package com.grulla.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.ListenMode
import com.grulla.music.ListeningState
import com.grulla.music.ScoreUiState
import com.grulla.music.ScoreViewModel
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.TempoRange
import com.grulla.music.core.playback.Transport
import com.grulla.music.core.practice.ScorePractice
import com.grulla.music.core.practice.SpeedTrainerSettings
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreView
import java.time.LocalDate
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
    /** Next (+1) or previous (-1) page, spread or screenful. */
    fun turnPage(direction: Int)
    /** Silence every part: the score scrolls at the tempo, for reading hands-free. */
    fun setSilent(silent: Boolean)
    fun seekToFraction(fraction: Float)
    fun zoomIn()
    fun zoomOut()
    fun toggleLayoutMode()
    fun toggleMute(track: Int)
    fun toggleSolo(track: Int)
    fun stopListening()
    fun skipNote()
    fun setTrainer(settings: SpeedTrainerSettings)
    fun setTrainerOn(on: Boolean)
    /** Loop bars [first]..[last] (bar numbers). */
    fun loopBars(first: Int, last: Int)
}

@Composable
fun ScoreScreen(
    viewModel: ScoreViewModel,
    night: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    tabletop: Tabletop? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val practice by viewModel.practice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingListen by remember { mutableStateOf<Pair<ListenMode, Set<Int>>?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (mode, tracks) = pendingListen ?: return@rememberLauncherForActivityResult
        pendingListen = null
        if (granted) viewModel.startListening(mode, tracks) else viewModel.showNotice("Follow me needs permission to use the microphone.")
    }
    val startListening = { mode: ListenMode, tracks: Set<Int> ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startListening(mode, tracks)
        } else {
            pendingListen = mode to tracks
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
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
        onListen = startListening,
        practice = practice,
        snackbarHost = { SnackbarHost(snackbar) },
        dockLayout = DockLayout.forScreen(LocalConfiguration.current, tabletop),
    ) { modifier ->
        ScoreView(host = viewModel.webHost, commands = viewModel.webCommands, night = night, modifier = modifier)
    }
}

/** A foldable half open like a laptop: the score above the hinge, the controls below it. */
data class Tabletop(val lowerHeight: Dp)

/** How the transport dock is laid out for the screen size and posture. */
sealed interface DockLayout {
    /** Phones in portrait: three rows (progress, buttons, toggles). */
    data object Regular : DockLayout

    /** Little height (phones in landscape): everything in one row. */
    data object Compact : DockLayout

    /** Wide screens: the regular dock, centred at a readable width. */
    data object Wide : DockLayout

    /** Tabletop posture: the dock fills the lower half of the screen. */
    data class Lower(val height: Dp) : DockLayout

    companion object {
        fun forScreen(configuration: Configuration, tabletop: Tabletop?): DockLayout = when {
            tabletop != null -> Lower(tabletop.lowerHeight)
            configuration.screenHeightDp in 1 until 480 -> Compact
            configuration.screenWidthDp >= 600 -> Wide
            else -> Regular
        }
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
    onListen: (ListenMode, Set<Int>) -> Unit,
    practice: ScorePractice? = null,
    snackbarHost: @Composable () -> Unit = {},
    dockLayout: DockLayout = DockLayout.Regular,
    openSheet: ScoreSheet? = null,
    score: @Composable (Modifier) -> Unit,
) {
    val c = Grulla.colors
    var sheet by remember { mutableStateOf(openSheet) }

    Scaffold(
        containerColor = c.paper,
        topBar = {
            ScoreTopBar(ui, bar, onBack, onOpenSettings, onSheet = { sheet = it }, actions = actions)
        },
        bottomBar = {
            val listening = ui.listening
            val onMic = { sheet = ScoreSheet.LISTEN }
            if (listening != null) {
                ListeningDock(listening, actions, dockLayout)
            } else if (dockLayout == DockLayout.Compact) {
                CompactTransportDock(ui, playback, actions, onListen = onMic, onTempo = { sheet = ScoreSheet.TEMPO })
            } else {
                TransportDock(ui, playback, actions, onListen = onMic, onTempo = { sheet = ScoreSheet.TEMPO }, layout = dockLayout)
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

    val open = sheet
    if (open != null) {
        val close = { sheet = null }
        ModalBottomSheet(
            onDismissRequest = close,
            containerColor = c.sheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            when (open) {
                ScoreSheet.PARTS -> MixerSheet(ui, actions)
                ScoreSheet.TEMPO -> TempoSheet(ui, playback, actions)
                ScoreSheet.LISTEN -> ListenSheet(ui) { mode, tracks ->
                    close()
                    onListen(mode, tracks)
                }
                ScoreSheet.PRACTICE -> PracticeSheet(practice) { first, last ->
                    close()
                    actions.loopBars(first, last)
                }
            }
        }
    }
}

/** The bottom sheets of the score screen. */
enum class ScoreSheet { PARTS, TEMPO, LISTEN, PRACTICE }

@Composable
private fun ScoreTopBar(
    ui: ScoreUiState,
    bar: BarInfo?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSheet: (ScoreSheet) -> Unit,
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
                    ui.listening?.mode == ListenMode.WAIT -> "wait for me"
                    ui.listening != null -> "follow me"
                    bar != null && bar.total > 0 -> "bar ${bar.bar} of ${bar.total}"
                    else -> null
                },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ui.mixer.size > 1) {
            IconButton(onClick = { onSheet(ScoreSheet.PARTS) }) { Icon(AppIcons.Parts, contentDescription = "Parts") }
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
                    text = { Text("Practice log") },
                    leadingIcon = { Icon(AppIcons.Practice, contentDescription = null) },
                    onClick = {
                        menu = false
                        onSheet(ScoreSheet.PRACTICE)
                    },
                )
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
            val lastShown = if (ui.spread) minOf(ui.page + 1, ui.pageCount) else ui.page
            IconButton(onClick = { actions.turnPage(-1) }, enabled = ui.page > 1) {
                Icon(AppIcons.ChevronLeft, contentDescription = "Previous page")
            }
            Text(
                if (lastShown > ui.page) "${ui.page}–$lastShown / ${ui.pageCount}" else "${ui.page} / ${ui.pageCount}",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
            IconButton(onClick = { actions.turnPage(1) }, enabled = lastShown < ui.pageCount) {
                Icon(AppIcons.ChevronRight, contentDescription = "Next page")
            }
        }
    }
}

/** The bottom dock while playing: progress, Stop / Play / tempo, and practice toggles. */
@Composable
private fun TransportDock(
    ui: ScoreUiState,
    playback: PlaybackState,
    actions: ScoreActions,
    onListen: () -> Unit,
    onTempo: () -> Unit,
    layout: DockLayout,
) {
    Dock(layout) {
        PositionBar(playback, actions)
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundOutlineButton(AppIcons.Stop, "Stop", onClick = actions::stop, enabled = playback.hasScore)
                FollowMeButton(onListen, enabled = playback.hasScore)
            }
            PlayButton(playback, actions::togglePlay, Modifier.align(Alignment.Center))
            TempoPill(playback.tempoPercent, onTempo, Modifier.align(Alignment.CenterEnd), training = ui.trainer != null)
        }
        PracticeToggles(ui, playback, actions, Modifier)
    }
}

/** Elapsed time, the seekable progress track (with the loop) and the duration. */
@Composable
private fun PositionBar(playback: PlaybackState, actions: ScoreActions) {
    val c = Grulla.colors
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
}

@Composable
private fun TempoPill(tempoPercent: Int, onClick: () -> Unit, modifier: Modifier = Modifier, training: Boolean = false) {
    val c = Grulla.colors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (training) c.indigoTint else Color.Transparent,
        contentColor = if (training) c.onIndigoTint else c.ink,
        border = BorderStroke(1.dp, if (training) c.indigo else c.line),
        modifier = modifier
            .height(48.dp)
            .semantics {
                contentDescription = "Tempo $tempoPercent percent" + (if (training) ", speed trainer on" else "") + ", change"
            },
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Metronome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("$tempoPercent%", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

/** Count-in, metronome, loop and silent scrolling. */
@Composable
private fun PracticeToggles(ui: ScoreUiState, playback: PlaybackState, actions: ScoreActions, modifier: Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
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
        GrullaChip("Silent", ui.silent, onClick = { actions.setSilent(!ui.silent) })
    }
}

/** One row for screens with little height (phones in landscape). */
@Composable
private fun CompactTransportDock(ui: ScoreUiState, playback: PlaybackState, actions: ScoreActions, onListen: () -> Unit, onTempo: () -> Unit) {
    Dock(DockLayout.Compact) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundOutlineButton(AppIcons.Stop, "Stop", onClick = actions::stop, enabled = playback.hasScore)
            FollowMeButton(onListen, enabled = playback.hasScore)
            PlayButton(playback, actions::togglePlay, Modifier, size = 56.dp)
            TempoPill(playback.tempoPercent, onTempo, training = ui.trainer != null)
            Box(Modifier.weight(1f)) { PositionBar(playback, actions) }
            PracticeToggles(ui, playback, actions, Modifier.weight(1f))
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
private fun PlayButton(playback: PlaybackState, onClick: () -> Unit, modifier: Modifier, size: Dp = 64.dp) {
    val c = Grulla.colors
    val running = playback.isRunning
    Box(
        modifier
            .size(size + 12.dp)
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
                .size(size)
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
private fun ListeningDock(state: ListeningState, actions: ScoreActions, layout: DockLayout) {
    val c = Grulla.colors
    Dock(if (layout == DockLayout.Compact) DockLayout.Regular else layout) {
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
                Text(
                    when {
                        state.allChordNotes -> "Play every note"
                        state.mode == ListenMode.WAIT -> "Your turn: play"
                        else -> "Play"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onIndigoTint,
                )
                Text(
                    state.expected,
                    // A chord's note names need a smaller size to fit.
                    style = if (state.expected.length > 4) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 1.1f)
                    },
                    color = c.onIndigoTint,
                    maxLines = 2,
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
            IndigoButton(
                if (state.mode == ListenMode.WAIT) "Stop Wait for me" else "Stop listening",
                onClick = actions::stopListening,
                modifier = Modifier.weight(1f),
            )
            GhostButton("Skip note", onClick = actions::skipNote)
        }
    }
}

@Composable
private fun Dock(layout: DockLayout, content: @Composable () -> Unit) {
    val c = Grulla.colors
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (layout is DockLayout.Lower) Modifier.heightIn(min = layout.height) else Modifier)
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f))
            .clip(shape)
            .background(c.sheet)
            .then(if (c.night) Modifier.border(1.dp, c.line, shape) else Modifier)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (layout == DockLayout.Compact) 10.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .then(if (layout == DockLayout.Compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 640.dp).fillMaxWidth()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
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

/** Tempo picker (applies when the slider is released, since every change re-prepares the music) and the speed trainer. */
@Composable
private fun TempoSheet(ui: ScoreUiState, playback: PlaybackState, actions: ScoreActions) {
    val c = Grulla.colors
    var value by remember(playback.tempoPercent) { mutableFloatStateOf(playback.tempoPercent.toFloat()) }
    fun apply(percent: Int) {
        value = TempoRange.clampPercent(percent).toFloat()
        actions.setTempoPercent(value.roundToInt())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tempo", style = MaterialTheme.typography.headlineSmall)
        Text("${value.roundToInt()}%", style = MaterialTheme.typography.displayMedium, color = c.ink)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { actions.setTempoPercent(value.roundToInt()) },
            valueRange = TempoRange.MIN_PERCENT.toFloat()..TempoRange.MAX_PERCENT.toFloat(),
            steps = (TempoRange.MAX_PERCENT - TempoRange.MIN_PERCENT) / 5 - 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("−5", onClick = { apply(value.roundToInt() - 5) })
            GhostButton("+5", onClick = { apply(value.roundToInt() + 5) })
            Spacer(Modifier.weight(1f))
            GhostButton("Reset to 100%", onClick = { apply(TempoRange.DEFAULT_PERCENT) })
        }
        HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 4.dp))
        TrainerSection(ui, hasLoop = playback.loop != null, actions)
    }
}

/** Speed trainer: start slow, go up a step every few passes of the loop. */
@Composable
private fun TrainerSection(ui: ScoreUiState, hasLoop: Boolean, actions: ScoreActions) {
    val c = Grulla.colors
    val settings = ui.trainerSettings
    val running = ui.trainer
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = running != null, role = Role.Switch, onValueChange = actions::setTrainerOn),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Speed trainer", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    running == null && !hasLoop -> "Set a loop first: long-press its first and last bar."
                    running == null -> "Repeats the loop, faster every ${settings.repeats} passes."
                    running.reachedTarget -> "Target reached: ${running.tempoPercent}%."
                    else -> "At ${running.tempoPercent}% · pass ${running.passes + 1} of ${running.repeats}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (running != null) c.indigo else c.graphite,
            )
        }
        Switch(checked = running != null, onCheckedChange = null)
    }
    Stepper("Start", "${settings.startPercent}%", { actions.setTrainer(settings.copy(startPercent = settings.startPercent - 5)) }) {
        actions.setTrainer(settings.copy(startPercent = settings.startPercent + 5))
    }
    Stepper("Target", "${settings.targetPercent}%", { actions.setTrainer(settings.copy(targetPercent = settings.targetPercent - 5)) }) {
        actions.setTrainer(settings.copy(targetPercent = settings.targetPercent + 5))
    }
    Stepper("Step", "+${settings.stepPercent}%", { actions.setTrainer(settings.copy(stepPercent = settings.stepPercent - 1)) }) {
        actions.setTrainer(settings.copy(stepPercent = settings.stepPercent + 1))
    }
    Stepper("Passes per step", "${settings.repeats}", { actions.setTrainer(settings.copy(repeats = settings.repeats - 1)) }) {
        actions.setTrainer(settings.copy(repeats = settings.repeats + 1))
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RoundOutlineButton(AppIcons.Minus, "Less $label", onClick = onMinus, size = 40.dp)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
        )
        RoundOutlineButton(AppIcons.Add, "More $label", onClick = onPlus, size = 40.dp)
    }
}

/** Choose your part and how to practise with the microphone. */
@Composable
private fun ListenSheet(ui: ScoreUiState, onStart: (ListenMode, Set<Int>) -> Unit) {
    val c = Grulla.colors
    val all = ui.mixer.map { it.trackIndex }.toSet()
    var mine by remember { mutableStateOf(ui.myTracks.ifEmpty { all }) }
    val others = all - mine - ui.mix.silencedTracks(ui.mixer)
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Practise with the microphone", style = MaterialTheme.typography.headlineSmall)
        if (ui.mixer.size > 1) {
            Text("Your part", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (channel in ui.mixer) {
                    GrullaChip(
                        channel.label,
                        selected = channel.trackIndex in mine,
                        onClick = {
                            val next = if (channel.trackIndex in mine) mine - channel.trackIndex else mine + channel.trackIndex
                            if (next.isNotEmpty()) mine = next
                        },
                    )
                }
            }
        }
        ModeCard(
            title = "Follow me",
            detail = "The score waits for you and moves on with every right note. No sound.",
            onClick = { onStart(ListenMode.FOLLOW, mine) },
        )
        ModeCard(
            title = "Wait for me",
            detail = if (others.isEmpty()) {
                "Choose only your own part: the other parts play along and wait for each of your notes."
            } else {
                "The other parts play along and wait for each of your notes. Headphones help the microphone hear only you."
            },
            enabled = others.isNotEmpty(),
            onClick = { onStart(ListenMode.WAIT, mine) },
        )
        if (ui.mixer.isNotEmpty() && mine.size < all.size) {
            Text(
                "Mute and solo in Parts still apply to what you hear.",
                style = MaterialTheme.typography.bodySmall,
                color = c.graphite,
            )
        }
    }
}

@Composable
private fun ModeCard(title: String, detail: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Grulla.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) c.indigoTint else c.tag,
        contentColor = if (enabled) c.onIndigoTint else c.graphite,
        border = BorderStroke(1.dp, if (enabled) c.indigo else c.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(AppIcons.Mic, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Time spent, loops, Follow me accuracy and the bars that need work. */
@Composable
private fun PracticeSheet(practice: ScorePractice?, onLoopBar: (Int, Int) -> Unit) {
    val c = Grulla.colors
    val log = practice ?: ScorePractice()
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Practice", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Last 7 days", formatDuration(log.recentMs(LocalDate.now())), Modifier.weight(1f))
            Stat("In total", formatDuration(log.totalMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Loops played", "${log.loopPasses}", Modifier.weight(1f))
            Stat("Follow me", log.accuracy?.let { "${(it * 100).roundToInt()}% right" } ?: "–", Modifier.weight(1f))
        }
        Text("Trouble bars", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        val trouble = log.troubleBars()
        if (trouble.isEmpty()) {
            Text(
                "Play with Follow me and the bars with the most wrong notes show up here, ready to loop.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.graphite,
            )
        }
        for (bar in trouble) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bar ${bar.bar}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${bar.misses} wrong of ${bar.attempts} notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.graphite,
                    )
                }
                MissMeter(bar.missRate.toFloat(), Modifier.width(72.dp))
                Spacer(Modifier.width(12.dp))
                GhostButton("Loop", onClick = { onLoopBar(bar.bar, bar.bar) })
            }
        }
    }
}

/** Share of wrong notes as a short amber bar. */
@Composable
private fun MissMeter(fraction: Float, modifier: Modifier) {
    val c = Grulla.colors
    Box(
        modifier
            .height(6.dp)
            .clip(CircleShape)
            .background(c.line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(c.amber),
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = Grulla.colors
    SheetCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.graphite)
        }
    }
}

/** "1 h 05 min", "12 min", "0 min". */
internal fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "%d h %02d min".format(minutes / 60, minutes % 60) else "$minutes min"
}

internal fun formatTime(ms: Double): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
