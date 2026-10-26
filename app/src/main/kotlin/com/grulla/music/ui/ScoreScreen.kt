package com.grulla.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grulla.music.ScoreUiState
import com.grulla.music.ScoreViewModel
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.TempoRange
import com.grulla.music.core.playback.Transport
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreView
import kotlin.math.roundToInt

/** What the transport bar can do; implemented by [ScoreViewModel]. */
interface TransportActions {
    fun togglePlay()
    fun stop()
    fun setTempoPercent(percent: Int)
    fun setCountIn(enabled: Boolean)
    fun setMetronome(enabled: Boolean)
    fun setLoopEnabled(enabled: Boolean)
    fun clearLoop()
    fun setFollow(enabled: Boolean)
    fun showPage(page: Int)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(viewModel: ScoreViewModel, onOpenFile: () -> Unit, onOpenSettings: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showMixer by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onOpenFile) {
                        Icon(AppIcons.FolderOpen, contentDescription = "Open score")
                    }
                    if (ui.mixer.size > 1) {
                        IconButton(onClick = { showMixer = true }) {
                            Icon(AppIcons.Tune, contentDescription = "Parts")
                        }
                    }
                    IconButton(onClick = viewModel::toggleLayoutMode) {
                        if (ui.layoutMode == LayoutMode.PAGE) {
                            Icon(AppIcons.ViewScroll, contentDescription = "Continuous scroll")
                        } else {
                            Icon(AppIcons.ViewPages, contentDescription = "Page view")
                        }
                    }
                    IconButton(onClick = viewModel::zoomOut) {
                        Icon(AppIcons.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = viewModel::zoomIn) {
                        Icon(AppIcons.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(AppIcons.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = { TransportBar(ui, playback, viewModel) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ScoreView(
                commands = viewModel.webCommands,
                callbacks = viewModel,
                scoreBytes = viewModel::currentScoreBytes,
                modifier = Modifier.fillMaxSize(),
            )
            if (ui.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showMixer) {
        ModalBottomSheet(onDismissRequest = { showMixer = false }) {
            MixerSheet(ui, onMute = viewModel::toggleMute, onSolo = viewModel::toggleSolo)
        }
    }
}

@Composable
private fun MixerSheet(ui: ScoreUiState, onMute: (Int) -> Unit, onSolo: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Parts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Mute a part, or solo it to hear it alone. Practise one hand by muting the other staff.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (channel in ui.mixer) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(channel.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                FilterChip(
                    selected = channel.trackIndex in ui.mix.muted,
                    onClick = { onMute(channel.trackIndex) },
                    label = { Text("Mute") },
                )
                FilterChip(
                    selected = channel.trackIndex in ui.mix.soloed,
                    onClick = { onSolo(channel.trackIndex) },
                    label = { Text("Solo") },
                )
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun TransportBar(ui: ScoreUiState, playback: PlaybackState, actions: TransportActions) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = actions::stop, enabled = playback.hasScore) {
                    Icon(AppIcons.Stop, contentDescription = "Stop")
                }
                FilledIconButton(onClick = actions::togglePlay, enabled = playback.hasScore, modifier = Modifier.size(52.dp)) {
                    if (playback.isRunning) {
                        Icon(AppIcons.Pause, contentDescription = "Pause")
                    } else {
                        Icon(AppIcons.Play, contentDescription = "Play")
                    }
                }
                Column {
                    Text(
                        "${formatTime(playback.positionMs)} / ${formatTime(playback.durationMs)}",
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(statusText(playback), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                if (ui.layoutMode == LayoutMode.PAGE && ui.pageCount > 1) {
                    IconButton(onClick = { actions.showPage(ui.page - 1) }, enabled = ui.page > 1) {
                        Icon(AppIcons.ChevronLeft, contentDescription = "Previous page")
                    }
                    Text("${ui.page}/${ui.pageCount}", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { actions.showPage(ui.page + 1) }, enabled = ui.page < ui.pageCount) {
                        Icon(AppIcons.ChevronRight, contentDescription = "Next page")
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = playback.countInEnabled,
                    onClick = { actions.setCountIn(!playback.countInEnabled) },
                    label = { Text("Count-in") },
                )
                FilterChip(
                    selected = playback.metronomeEnabled,
                    onClick = { actions.setMetronome(!playback.metronomeEnabled) },
                    label = { Text("Metronome") },
                )
                FilterChip(
                    selected = playback.loopEnabled,
                    enabled = playback.loop != null,
                    onClick = { actions.setLoopEnabled(!playback.loopEnabled) },
                    label = { Text(if (playback.loop == null) "Loop: long-press 2 bars" else "Loop A–B") },
                    trailingIcon = if (playback.loop != null) {
                        {
                            Icon(
                                AppIcons.Close,
                                contentDescription = "Clear loop",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable(onClickLabel = "Clear loop", onClick = actions::clearLoop),
                            )
                        }
                    } else {
                        null
                    },
                )
                FilterChip(
                    selected = ui.follow,
                    onClick = { actions.setFollow(!ui.follow) },
                    label = { Text("Follow") },
                )
            }
            TempoSlider(playback.tempoPercent, actions::setTempoPercent)
        }
    }
}

/** Applies the tempo when the drag ends, since every change re-prepares the MIDI file. */
@Composable
private fun TempoSlider(tempoPercent: Int, onTempo: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(tempoPercent.toFloat()) }
    if (!dragging) value = tempoPercent.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onTempo(TempoRange.DEFAULT_PERCENT) }, modifier = Modifier.width(112.dp)) {
            Text("Tempo ${value.roundToInt()}%", maxLines = 1)
        }
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                dragging = false
                onTempo(value.roundToInt())
            },
            valueRange = TempoRange.MIN_PERCENT.toFloat()..TempoRange.MAX_PERCENT.toFloat(),
            steps = (TempoRange.MAX_PERCENT - TempoRange.MIN_PERCENT) / 5 - 1,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun statusText(playback: PlaybackState) = when (playback.transport) {
    Transport.STOPPED -> "Stopped"
    Transport.PREPARING -> "Preparing…"
    Transport.COUNTING_IN -> "Count-in"
    Transport.PLAYING -> if (playback.activeLoop != null) "Looping" else "Playing"
    Transport.PAUSED -> "Paused"
}

internal fun formatTime(ms: Double): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
