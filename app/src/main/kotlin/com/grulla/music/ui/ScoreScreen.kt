package com.grulla.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(viewModel: ScoreViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openDocument(uri)
    }

    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { openDocument.launch(viewModel.pickerMimeTypes) }) {
                        Icon(AppIcons.FolderOpen, contentDescription = "Open score")
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
                    Text("${ui.scale}%", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = viewModel::zoomIn) {
                        Icon(AppIcons.ZoomIn, contentDescription = "Zoom in")
                    }
                },
            )
        },
        bottomBar = {
            TransportBar(
                ui = ui,
                playback = playback,
                onTogglePlay = viewModel::togglePlay,
                onStop = viewModel::stop,
                onTempo = viewModel::setTempoPercent,
                onCountIn = viewModel::setCountIn,
                onFollow = viewModel::setFollow,
                onPage = viewModel::showPage,
            )
        },
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
}

@Composable
private fun TransportBar(
    ui: ScoreUiState,
    playback: PlaybackState,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onTempo: (Int) -> Unit,
    onCountIn: (Boolean) -> Unit,
    onFollow: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onStop, enabled = playback.hasScore) {
                    Icon(AppIcons.Stop, contentDescription = "Stop")
                }
                FilledIconButton(onClick = onTogglePlay, enabled = playback.hasScore, modifier = Modifier.size(52.dp)) {
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
                    Text(statusText(playback.transport), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                if (ui.layoutMode == LayoutMode.PAGE && ui.pageCount > 1) {
                    IconButton(onClick = { onPage(ui.page - 1) }, enabled = ui.page > 1) {
                        Icon(AppIcons.ChevronLeft, contentDescription = "Previous page")
                    }
                    Text("${ui.page}/${ui.pageCount}", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onPage(ui.page + 1) }, enabled = ui.page < ui.pageCount) {
                        Icon(AppIcons.ChevronRight, contentDescription = "Next page")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = playback.countInEnabled,
                    onClick = { onCountIn(!playback.countInEnabled) },
                    label = { Text("Count-in") },
                )
                FilterChip(
                    selected = ui.follow,
                    onClick = { onFollow(!ui.follow) },
                    label = { Text("Follow") },
                )
            }
            TempoSlider(playback.tempoPercent, onTempo)
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

private fun statusText(transport: Transport) = when (transport) {
    Transport.STOPPED -> "Stopped"
    Transport.PREPARING -> "Preparing…"
    Transport.COUNTING_IN -> "Count-in"
    Transport.PLAYING -> "Playing"
    Transport.PAUSED -> "Paused"
}

private fun formatTime(ms: Double): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
