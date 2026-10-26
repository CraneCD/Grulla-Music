package com.grulla.music

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grulla.music.core.playback.PlaybackController
import com.grulla.music.core.playback.PlaybackListener
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.Segment
import com.grulla.music.core.score.LoadedScore
import com.grulla.music.core.score.RenderedScore
import com.grulla.music.playback.MediaPlayerMidiPlayer
import com.grulla.music.playback.awaitFrameNanos
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreFiles
import com.grulla.music.score.ScoreOrigin
import com.grulla.music.score.ScoreWebCallbacks
import com.grulla.music.score.WebCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScoreUiState(
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val scale: Int = ScoreViewModel.DEFAULT_SCALE,
    val layoutMode: LayoutMode = LayoutMode.CONTINUOUS,
    val page: Int = 1,
    val pageCount: Int = 0,
    /** Scroll / turn pages automatically to keep the playing notes on screen. */
    val follow: Boolean = true,
)

class ScoreViewModel(application: Application) : AndroidViewModel(application), ScoreWebCallbacks, PlaybackListener {
    private val files = ScoreFiles(application)

    private val mutableUi = MutableStateFlow(ScoreUiState())
    val ui: StateFlow<ScoreUiState> = mutableUi.asStateFlow()

    private val playback = PlaybackController(
        player = MediaPlayerMidiPlayer(application),
        scope = viewModelScope,
        awaitFrameNanos = ::awaitFrameNanos,
        listener = this,
    )
    val playbackState: StateFlow<PlaybackState> = playback.state

    private val commandChannel = Channel<WebCommand>(Channel.UNLIMITED)
    val webCommands: Flow<WebCommand> = commandChannel.receiveAsFlow()

    /** UTF-8 MusicXML of the current score, served to the WebView (read from a WebView thread). */
    @Volatile
    private var scoreBytes: ByteArray? = null
    private var scoreVersion = 0
    private var webReady = false
    private var openJob: Job? = null

    val pickerMimeTypes: Array<String> get() = files.pickerMimeTypes

    init {
        openWith(fallbackTitle = "Sample") { files.openAsset(ScoreFiles.SAMPLE_ASSET) }
    }

    fun currentScoreBytes(): ByteArray? = scoreBytes

    fun openDocument(uri: Uri) = openWith { files.open(uri) }

    private fun openWith(fallbackTitle: String = "Score", read: () -> Pair<LoadedScore, String>) {
        openJob?.cancel()
        mutableUi.update { it.copy(loading = true, error = null) }
        openJob = viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { read() } }
            result.onSuccess { (score, name) -> showScore(score, score.title ?: name.ifBlank { fallbackTitle }) }
            result.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableUi.update { it.copy(loading = false, error = error.message ?: "Could not open the file.") }
            }
        }
    }

    private fun showScore(score: LoadedScore, title: String) {
        playback.clearScore()
        scoreBytes = score.musicXml.toByteArray(Charsets.UTF_8)
        scoreVersion++
        mutableUi.update { it.copy(title = title, loading = true, error = null, page = 1, pageCount = 0) }
        if (webReady) sendLoad()
    }

    private fun sendLoad() {
        if (scoreBytes == null) return
        val state = mutableUi.value
        commandChannel.trySend(WebCommand.Load(ScoreOrigin.scoreUrl(scoreVersion), state.scale, state.layoutMode))
    }

    // --- Callbacks from the WebView ------------------------------------------------

    override fun onWebReady(verovioVersion: String) {
        webReady = true
        // Also covers a re-created WebView: it starts empty, so render the current score again.
        sendLoad()
    }

    override fun onScoreRendered(payloadJson: String) {
        val expectedSource = ScoreOrigin.scoreUrl(scoreVersion)
        viewModelScope.launch {
            val rendered = runCatching { withContext(Dispatchers.Default) { RenderedScore.parse(payloadJson) } }
                .getOrElse { error ->
                    mutableUi.update { it.copy(loading = false, error = "Could not prepare playback: ${error.message}") }
                    return@launch
                }
            if (rendered.source != expectedSource || ScoreOrigin.scoreUrl(scoreVersion) != expectedSource) return@launch
            playback.setScore(rendered.timemap, rendered.midi)
            mutableUi.update { it.copy(loading = false, pageCount = rendered.pageCount) }
        }
    }

    override fun onMeasureTapped(measureId: String) {
        playback.seekToMeasure(measureId)
    }

    override fun onZoomChanged(scale: Int) {
        mutableUi.update { it.copy(scale = scale) }
    }

    override fun onPageChanged(page: Int, pageCount: Int) {
        mutableUi.update { it.copy(page = page, pageCount = pageCount) }
    }

    override fun onWebError(message: String) {
        mutableUi.update { it.copy(loading = false, error = message) }
    }

    // --- Callbacks from the playback controller ------------------------------------

    override fun onSegmentChanged(segment: Segment?, follow: Boolean) {
        commandChannel.trySend(WebCommand.Highlight(segment?.noteIds.orEmpty(), follow && mutableUi.value.follow))
    }

    override fun onCountInBeat(beat: Int, total: Int) {
        commandChannel.trySend(WebCommand.CountIn(beat, total))
    }

    override fun onPlaybackError(message: String) {
        mutableUi.update { it.copy(error = message) }
    }

    // --- User actions --------------------------------------------------------------

    fun togglePlay() = playback.togglePlay()

    fun pause() = playback.pause()

    fun stop() = playback.stop()

    fun setTempoPercent(percent: Int) = playback.setTempoPercent(percent)

    fun setCountIn(enabled: Boolean) = playback.setCountInEnabled(enabled)

    fun setFollow(enabled: Boolean) = mutableUi.update { it.copy(follow = enabled) }

    fun zoomIn() = setScale(mutableUi.value.scale + SCALE_STEP)

    fun zoomOut() = setScale(mutableUi.value.scale - SCALE_STEP)

    private fun setScale(scale: Int) {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        mutableUi.update { it.copy(scale = clamped) }
        sendLayout()
    }

    fun toggleLayoutMode() {
        mutableUi.update {
            it.copy(layoutMode = if (it.layoutMode == LayoutMode.PAGE) LayoutMode.CONTINUOUS else LayoutMode.PAGE)
        }
        sendLayout()
    }

    fun showPage(page: Int) {
        commandChannel.trySend(WebCommand.ShowPage(page))
    }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    private fun sendLayout() {
        val state = mutableUi.value
        commandChannel.trySend(WebCommand.SetLayout(state.scale, state.layoutMode))
    }

    override fun onCleared() {
        playback.release()
        commandChannel.close()
    }

    companion object {
        // Verovio scale in percent; must match MIN_SCALE / MAX_SCALE in score.js.
        const val DEFAULT_SCALE = 40
        const val MIN_SCALE = 15
        const val MAX_SCALE = 120
        private const val SCALE_STEP = 5
    }
}
