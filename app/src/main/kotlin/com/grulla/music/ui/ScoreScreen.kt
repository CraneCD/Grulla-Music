package com.grulla.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.AnnotateTool
import com.grulla.music.ListenMode
import com.grulla.music.ListeningState
import com.grulla.music.R
import com.grulla.music.ScoreUiState
import com.grulla.music.ScoreViewModel
import com.grulla.music.core.midi.GeneralMidi
import com.grulla.music.core.playback.MixState
import com.grulla.music.core.playback.MixerChannel
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
    fun setVolume(track: Int, percent: Int)
    /** Instrument (General MIDI program) of the part of [track]; null restores the score's. */
    fun setProgram(track: Int, program: Int?)
    fun setTranspose(semitones: Int)
    fun setConcertPitch(enabled: Boolean)
    fun startAnnotating()
    fun setAnnotateTool(tool: AnnotateTool)
    fun setFingering(text: String)
    fun undoAnnotation()
    fun stopAnnotating()
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
    val resources = LocalResources.current
    var pendingListen by remember { mutableStateOf<Pair<ListenMode, Set<Int>>?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (mode, tracks) = pendingListen ?: return@rememberLauncherForActivityResult
        pendingListen = null
        if (granted) viewModel.startListening(mode, tracks) else viewModel.showNotice(resources.getString(R.string.mic_permission_needed))
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
            val annotating = ui.annotating
            if (annotating != null) {
                AnnotationDock(annotating, ui.fingering, actions, dockLayout)
            } else if (listening != null) {
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
                ScoreSheet.TRANSPOSE -> TransposeSheet(ui, actions)
            }
        }
    }
}

/** The bottom sheets of the score screen. */
enum class ScoreSheet { PARTS, TEMPO, LISTEN, PRACTICE, TRANSPOSE }

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
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.library)) }
        Column(Modifier.weight(1f)) {
            Text(ui.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                ui.composer,
                when {
                    ui.listening?.mode == ListenMode.WAIT -> stringResource(R.string.status_wait_for_me)
                    ui.listening != null -> stringResource(R.string.status_follow_me)
                    bar != null && bar.total > 0 -> stringResource(R.string.bar_of, bar.bar, bar.total)
                    else -> null
                },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ui.mixer.size > 1) {
            IconButton(onClick = { onSheet(ScoreSheet.PARTS) }) { Icon(AppIcons.Parts, contentDescription = stringResource(R.string.parts)) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = stringResource(R.string.view_options)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.sheet) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.zoom_in)) },
                    leadingIcon = { Icon(AppIcons.ZoomIn, contentDescription = null) },
                    trailingIcon = { Text("${ui.scale}%", style = MaterialTheme.typography.bodySmall, color = c.graphite) },
                    onClick = actions::zoomIn,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.zoom_out)) },
                    leadingIcon = { Icon(AppIcons.ZoomOut, contentDescription = null) },
                    onClick = actions::zoomOut,
                )
                HorizontalDivider(color = c.line)
                DropdownMenuItem(
                    text = { Text(stringResource(if (ui.layoutMode == LayoutMode.PAGE) R.string.continuous_scroll else R.string.page_view)) },
                    leadingIcon = {
                        Icon(if (ui.layoutMode == LayoutMode.PAGE) AppIcons.ViewScroll else AppIcons.ViewPages, contentDescription = null)
                    },
                    onClick = {
                        menu = false
                        actions.toggleLayoutMode()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keep_notes_on_screen)) },
                    leadingIcon = { Icon(AppIcons.Check, contentDescription = null, tint = if (ui.follow) c.indigo else Color.Transparent) },
                    onClick = { actions.setFollow(!ui.follow) },
                )
                HorizontalDivider(color = c.line)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.transpose)) },
                    leadingIcon = { Icon(AppIcons.Transpose, contentDescription = null) },
                    trailingIcon = {
                        if (ui.transpose != 0) Text("%+d".format(ui.transpose), style = MaterialTheme.typography.bodySmall, color = c.graphite)
                    },
                    onClick = {
                        menu = false
                        onSheet(ScoreSheet.TRANSPOSE)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.annotate)) },
                    leadingIcon = { Icon(AppIcons.Pen, contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.startAnnotating()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.practice_log)) },
                    leadingIcon = { Icon(AppIcons.Practice, contentDescription = null) },
                    onClick = {
                        menu = false
                        onSheet(ScoreSheet.PRACTICE)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings)) },
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
                Icon(AppIcons.ChevronLeft, contentDescription = stringResource(R.string.previous_page))
            }
            Text(
                if (lastShown > ui.page) "${ui.page}–$lastShown / ${ui.pageCount}" else "${ui.page} / ${ui.pageCount}",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
            IconButton(onClick = { actions.turnPage(1) }, enabled = lastShown < ui.pageCount) {
                Icon(AppIcons.ChevronRight, contentDescription = stringResource(R.string.next_page))
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
                RoundOutlineButton(AppIcons.Stop, stringResource(R.string.stop), onClick = actions::stop, enabled = playback.hasScore)
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
    val description = stringResource(if (training) R.string.tempo_description_training else R.string.tempo_description, tempoPercent)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (training) c.indigoTint else Color.Transparent,
        contentColor = if (training) c.onIndigoTint else c.ink,
        border = BorderStroke(1.dp, if (training) c.indigo else c.line),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = description
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
        GrullaChip(stringResource(R.string.count_in), playback.countInEnabled, onClick = { actions.setCountIn(!playback.countInEnabled) })
        GrullaChip(stringResource(R.string.metronome), playback.metronomeEnabled, onClick = { actions.setMetronome(!playback.metronomeEnabled) })
        val loop = playback.loop
        if (loop == null) {
            GrullaChip(stringResource(R.string.loop), selected = false, onClick = actions::explainLoop)
        } else {
            val clearLoop = stringResource(R.string.clear_loop)
            GrullaChip(
                label = ui.loopLabel ?: stringResource(R.string.loop),
                selected = playback.loopEnabled,
                onClick = { actions.setLoopEnabled(!playback.loopEnabled) },
                trailing = {
                    Icon(
                        AppIcons.Close,
                        contentDescription = clearLoop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = clearLoop, onClick = actions::clearLoop)
                            .padding(6.dp),
                    )
                },
            )
        }
        GrullaChip(stringResource(R.string.silent), ui.silent, onClick = { actions.setSilent(!ui.silent) })
    }
}

/** One row for screens with little height (phones in landscape). */
@Composable
private fun CompactTransportDock(ui: ScoreUiState, playback: PlaybackState, actions: ScoreActions, onListen: () -> Unit, onTempo: () -> Unit) {
    Dock(DockLayout.Compact) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundOutlineButton(AppIcons.Stop, stringResource(R.string.stop), onClick = actions::stop, enabled = playback.hasScore)
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
    val description = stringResource(R.string.follow_me_button)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = c.indigoTint,
        contentColor = c.onIndigoTint,
        border = BorderStroke(1.dp, c.indigo),
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(20.dp)) }
    }
}

/** The big round play button: crown red, with a halo while sound is running. */
@Composable
private fun PlayButton(playback: PlaybackState, onClick: () -> Unit, modifier: Modifier, size: Dp = 64.dp) {
    val c = Grulla.colors
    val running = playback.isRunning
    val description = stringResource(if (running) R.string.pause else R.string.play)
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
                .semantics { contentDescription = description },
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
                        state.allChordNotes -> stringResource(R.string.listen_play_every_note)
                        state.mode == ListenMode.WAIT -> stringResource(R.string.listen_your_turn)
                        else -> stringResource(R.string.listen_play)
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
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.End) {
                Text(
                    "${state.step} / ${state.total}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = c.onIndigoTint,
                )
                Text(
                    state.part,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onIndigoTint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                Text(stringResource(R.string.listen_hearing, state.hearing), style = MaterialTheme.typography.bodySmall, color = c.onIndigoTint)
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
                Text(stringResource(R.string.listen_wrong, wrong, state.expected), style = MaterialTheme.typography.labelLarge, color = c.onAmberContainer)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IndigoButton(
                stringResource(if (state.mode == ListenMode.WAIT) R.string.stop_wait_for_me else R.string.stop_listening),
                onClick = actions::stopListening,
                modifier = Modifier.weight(1f),
            )
            GhostButton(stringResource(R.string.skip_note), onClick = actions::skipNote)
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
    var choosing by remember { mutableStateOf<MixerChannel?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.parts), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.parts_explained),
            style = MaterialTheme.typography.bodyMedium,
            color = c.graphite,
        )
        for (channel in ui.mixer) {
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 4.dp))
            val label = channelLabel(channel)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                GrullaChip(stringResource(R.string.mute), channel.trackIndex in ui.mix.muted, onClick = { actions.toggleMute(channel.trackIndex) })
                GrullaChip(stringResource(R.string.solo), channel.trackIndex in ui.mix.soloed, onClick = { actions.toggleSolo(channel.trackIndex) })
            }
            if (!channel.percussion) {
                val chosen = ui.mix.programs[channel.trackIndex]
                val program = chosen ?: channel.program
                val instrument = program?.let(GeneralMidi::name) ?: stringResource(R.string.instrument_as_written)
                val description = stringResource(R.string.instrument_description, label, instrument)
                Surface(
                    onClick = { choosing = channel },
                    shape = RoundedCornerShape(10.dp),
                    color = c.tag,
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            instrument,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (chosen != null) c.indigo else c.ink,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            VolumeSlider(channel, label, ui.mix.volume(channel.trackIndex), actions)
        }
    }
    choosing?.let { channel ->
        InstrumentDialog(
            current = ui.mix.programs[channel.trackIndex] ?: channel.program,
            hasOwn = ui.mix.programs.containsKey(channel.trackIndex),
            onChoose = { program ->
                choosing = null
                actions.setProgram(channel.trackIndex, program)
            },
            onDismiss = { choosing = null },
        )
    }
}

/** Volume of one part, applied when the slider is released (it re-prepares the music). */
@Composable
private fun VolumeSlider(channel: MixerChannel, label: String, percent: Int, actions: ScoreActions) {
    val c = Grulla.colors
    val description = stringResource(R.string.volume_of, label)
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.volume), style = MaterialTheme.typography.bodySmall, color = c.graphite, modifier = Modifier.width(60.dp))
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { actions.setVolume(channel.trackIndex, (value / 10).roundToInt() * 10) },
            valueRange = 0f..MixState.MAX_VOLUME.toFloat(),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = description },
        )
        Text(
            "${(value / 10).roundToInt() * 10}%",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** The 128 General MIDI instruments by family, plus "as written". */
@Composable
private fun InstrumentDialog(current: Int?, hasOwn: Boolean, onChoose: (Int?) -> Unit, onDismiss: () -> Unit) {
    val c = Grulla.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text(stringResource(R.string.instrument)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (hasOwn) {
                    item {
                        DropdownMenuItem(text = { Text(stringResource(R.string.as_written_in_score), color = c.indigo) }, onClick = { onChoose(null) })
                    }
                }
                for ((family, first) in GeneralMidi.families) {
                    item { Overline(family, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
                    items((first until first + 8).toList()) { program ->
                        DropdownMenuItem(
                            text = { Text(GeneralMidi.name(program)) },
                            trailingIcon = { if (program == current) Icon(AppIcons.Check, contentDescription = stringResource(R.string.current), tint = c.indigo) },
                            onClick = { onChoose(program) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Semitones up or down for notation and sound, and concert pitch for transposing instruments. */
@Composable
private fun TransposeSheet(ui: ScoreUiState, actions: ScoreActions) {
    val c = Grulla.colors
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.transpose), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.transpose_explained),
            style = MaterialTheme.typography.bodyMedium,
            color = c.graphite,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            RoundOutlineButton(AppIcons.Minus, stringResource(R.string.down_semitone), onClick = { actions.setTranspose(ui.transpose - 1) }, size = 56.dp,
                enabled = ui.transpose > -ScoreViewModel.MAX_TRANSPOSE)
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (ui.transpose == 0) "0" else "%+d".format(ui.transpose), style = MaterialTheme.typography.displayMedium)
                Text(semitoneName(ui.transpose), style = MaterialTheme.typography.bodySmall, color = c.graphite)
            }
            RoundOutlineButton(AppIcons.Add, stringResource(R.string.up_semitone), onClick = { actions.setTranspose(ui.transpose + 1) }, size = 56.dp,
                enabled = ui.transpose < ScoreViewModel.MAX_TRANSPOSE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            GhostButton(stringResource(R.string.octave_down), onClick = { actions.setTranspose(ui.transpose - 12) }, enabled = ui.transpose - 12 >= -ScoreViewModel.MAX_TRANSPOSE)
            GhostButton(stringResource(R.string.reset), onClick = { actions.setTranspose(0) }, enabled = ui.transpose != 0)
            GhostButton(stringResource(R.string.octave_up), onClick = { actions.setTranspose(ui.transpose + 12) }, enabled = ui.transpose + 12 <= ScoreViewModel.MAX_TRANSPOSE)
        }
        HorizontalDivider(color = c.line)
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = ui.concertPitch, role = Role.Switch, onValueChange = actions::setConcertPitch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.concert_pitch), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.concert_pitch_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.graphite,
                )
            }
            Switch(checked = ui.concertPitch, onCheckedChange = null)
        }
    }
}

/** "a whole tone up", "a fifth down"... */
@Composable
internal fun semitoneName(semitones: Int): String {
    if (semitones == 0) return stringResource(R.string.as_written)
    val interval = stringArrayResource(R.array.intervals)[kotlin.math.abs(semitones)]
    return stringResource(if (semitones > 0) R.string.interval_up else R.string.interval_down, interval)
}

/** Annotation tools: pencil, fingerings, breath marks, eraser, undo. */
@Composable
private fun AnnotationDock(tool: AnnotateTool, fingering: String, actions: ScoreActions, layout: DockLayout) {
    val c = Grulla.colors
    Dock(if (layout == DockLayout.Compact) DockLayout.Regular else layout) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.annotate), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.two_fingers_scroll), style = MaterialTheme.typography.bodySmall, color = c.graphite)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GrullaChip(stringResource(R.string.pencil), tool == AnnotateTool.PEN, onClick = { actions.setAnnotateTool(AnnotateTool.PEN) }, leadingIcon = AppIcons.Pen)
            GrullaChip(stringResource(R.string.fingering), tool == AnnotateTool.FINGERING, onClick = { actions.setAnnotateTool(AnnotateTool.FINGERING) }, leadingIcon = AppIcons.Fingering)
            GrullaChip(stringResource(R.string.breath), tool == AnnotateTool.BREATH, onClick = { actions.setAnnotateTool(AnnotateTool.BREATH) }, leadingIcon = AppIcons.Breath)
            GrullaChip(stringResource(R.string.eraser), tool == AnnotateTool.ERASER, onClick = { actions.setAnnotateTool(AnnotateTool.ERASER) }, leadingIcon = AppIcons.Eraser)
        }
        if (tool == AnnotateTool.FINGERING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (digit in listOf("1", "2", "3", "4", "5")) {
                    GrullaChip(digit, fingering == digit, onClick = { actions.setFingering(digit) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.undo), onClick = actions::undoAnnotation, icon = AppIcons.Undo)
            IndigoButton(stringResource(R.string.done), onClick = actions::stopAnnotating, modifier = Modifier.weight(1f))
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
        Text(stringResource(R.string.tempo), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
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
            GhostButton(stringResource(R.string.reset_to_100), onClick = { apply(TempoRange.DEFAULT_PERCENT) })
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
            Text(stringResource(R.string.speed_trainer), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    running == null && !hasLoop -> stringResource(R.string.trainer_needs_loop)
                    running == null -> pluralStringResource(R.plurals.trainer_explained, settings.repeats, settings.repeats)
                    running.reachedTarget -> stringResource(R.string.trainer_target_reached, running.tempoPercent)
                    else -> stringResource(R.string.trainer_progress, running.tempoPercent, running.passes + 1, running.repeats)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (running != null) c.indigo else c.graphite,
            )
        }
        Switch(checked = running != null, onCheckedChange = null)
    }
    Stepper(stringResource(R.string.trainer_start), "${settings.startPercent}%", { actions.setTrainer(settings.copy(startPercent = settings.startPercent - 5)) }) {
        actions.setTrainer(settings.copy(startPercent = settings.startPercent + 5))
    }
    Stepper(stringResource(R.string.trainer_target), "${settings.targetPercent}%", { actions.setTrainer(settings.copy(targetPercent = settings.targetPercent - 5)) }) {
        actions.setTrainer(settings.copy(targetPercent = settings.targetPercent + 5))
    }
    Stepper(stringResource(R.string.trainer_step), "+${settings.stepPercent}%", { actions.setTrainer(settings.copy(stepPercent = settings.stepPercent - 1)) }) {
        actions.setTrainer(settings.copy(stepPercent = settings.stepPercent + 1))
    }
    Stepper(stringResource(R.string.trainer_passes), "${settings.repeats}", { actions.setTrainer(settings.copy(repeats = settings.repeats - 1)) }) {
        actions.setTrainer(settings.copy(repeats = settings.repeats + 1))
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RoundOutlineButton(AppIcons.Minus, stringResource(R.string.less, label), onClick = onMinus, size = 40.dp)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
        )
        RoundOutlineButton(AppIcons.Add, stringResource(R.string.more, label), onClick = onPlus, size = 40.dp)
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
        Text(stringResource(R.string.follow_me_button), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (ui.mixer.size > 1) {
            Text(stringResource(R.string.your_part), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (channel in ui.mixer) {
                    GrullaChip(
                        channelLabel(channel),
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
            title = stringResource(R.string.follow_me),
            detail = stringResource(R.string.follow_me_explained),
            onClick = { onStart(ListenMode.FOLLOW, mine) },
        )
        ModeCard(
            title = stringResource(R.string.wait_for_me),
            detail = stringResource(if (others.isEmpty()) R.string.wait_for_me_choose_part else R.string.wait_for_me_explained),
            enabled = others.isNotEmpty(),
            onClick = { onStart(ListenMode.WAIT, mine) },
        )
        if (ui.mixer.isNotEmpty() && mine.size < all.size) {
            Text(
                stringResource(R.string.mix_still_applies),
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
        Text(stringResource(R.string.practice), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(stringResource(R.string.last_7_days), formatDuration(log.recentMs(LocalDate.now())), Modifier.weight(1f))
            Stat(stringResource(R.string.in_total), formatDuration(log.totalMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(stringResource(R.string.loops_played), "${log.loopPasses}", Modifier.weight(1f))
            Stat(stringResource(R.string.follow_me), log.accuracy?.let { stringResource(R.string.percent_right, (it * 100).roundToInt()) } ?: "–", Modifier.weight(1f))
        }
        Text(stringResource(R.string.trouble_bars), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        val trouble = log.troubleBars()
        if (trouble.isEmpty()) {
            Text(
                stringResource(R.string.trouble_bars_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = c.graphite,
            )
        }
        for (bar in trouble) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bar_n, bar.bar), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        pluralStringResource(R.plurals.wrong_of_notes, bar.attempts, bar.misses, bar.attempts),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.graphite,
                    )
                }
                MissMeter(bar.missRate.toFloat(), Modifier.width(72.dp))
                Spacer(Modifier.width(12.dp))
                GhostButton(stringResource(R.string.loop), onClick = { onLoopBar(bar.bar, bar.bar) })
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
@Composable
internal fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) {
        stringResource(R.string.duration_hours, minutes / 60, "%02d".format(minutes % 60))
    } else {
        stringResource(R.string.duration_minutes, minutes)
    }
}

/** A mixer strip's name: the part, and which staff for parts on several staves. */
@Composable
internal fun channelLabel(channel: MixerChannel): String = channelLabel(LocalResources.current, channel)

internal fun channelLabel(resources: Resources, channel: MixerChannel): String {
    val part = channel.partName ?: return channel.label
    return when {
        channel.staves <= 1 -> part
        channel.staves == 2 -> resources.getString(if (channel.staff == 1) R.string.staff_upper else R.string.staff_lower, part)
        else -> resources.getString(R.string.staff_n, part, channel.staff)
    }
}

internal fun formatTime(ms: Double): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
