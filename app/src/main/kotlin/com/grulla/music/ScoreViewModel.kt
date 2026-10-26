package com.grulla.music

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grulla.music.core.playback.MixState
import com.grulla.music.core.playback.Mixer
import com.grulla.music.core.playback.MixerChannel
import com.grulla.music.core.playback.PlaybackController
import com.grulla.music.core.playback.PlaybackListener
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.Segment
import com.grulla.music.core.playback.TimeRange
import com.grulla.music.core.score.LoadedScore
import com.grulla.music.core.score.PartListParser
import com.grulla.music.core.score.RenderedScore
import com.grulla.music.core.timemap.Timemap
import com.grulla.music.playback.MediaPlayerMidiPlayer
import com.grulla.music.playback.awaitFrameNanos
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreFiles
import com.grulla.music.score.ScoreOrigin
import com.grulla.music.score.ScoreWebCallbacks
import com.grulla.music.score.WebCommand
import com.grulla.music.ui.TransportActions
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
    /** Short, non-error message (e.g. how to finish setting a loop). */
    val notice: String? = null,
    /** One strip per staff/part, for mute and solo. */
    val mixer: List<MixerChannel> = emptyList(),
    val mix: MixState = MixState(),
)

class ScoreViewModel(application: Application) :
    AndroidViewModel(application),
    ScoreWebCallbacks,
    PlaybackListener,
    TransportActions {
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

    /** The rendered score's timemap and MusicXML (for measure lookups and the part list). */
    private var timemap: Timemap? = null
    private var musicXml: String? = null

    /** First measure of a loop being set with two long presses. */
    private var pendingLoopStart: String? = null

    /** Measures shaded for the current loop. */
    private var loopMeasures: Pair<String, String>? = null

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
        timemap = null
        musicXml = score.musicXml
        pendingLoopStart = null
        loopMeasures = null
        scoreBytes = score.musicXml.toByteArray(Charsets.UTF_8)
        scoreVersion++
        mutableUi.update {
            it.copy(title = title, loading = true, error = null, page = 1, pageCount = 0, mixer = emptyList(), mix = MixState())
        }
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
        val xml = musicXml
        viewModelScope.launch {
            val (rendered, channels) = runCatching {
                withContext(Dispatchers.Default) {
                    val rendered = RenderedScore.parse(payloadJson)
                    // The mixer is optional: a part list we cannot read just means no mixer.
                    val parts = xml?.let { runCatching { PartListParser.parse(it) }.getOrNull() }.orEmpty()
                    rendered to Mixer.channels(parts, rendered.midi)
                }
            }.getOrElse { error ->
                mutableUi.update { it.copy(loading = false, error = "Could not prepare playback: ${error.message}") }
                return@launch
            }
            if (rendered.source != expectedSource || ScoreOrigin.scoreUrl(scoreVersion) != expectedSource) return@launch
            timemap = rendered.timemap
            playback.setScore(rendered.timemap, rendered.midi)
            mutableUi.update { it.copy(loading = false, pageCount = rendered.pageCount, mixer = channels, mix = MixState()) }
        }
    }

    override fun onMeasureTapped(measureId: String) {
        playback.seekToMeasure(measureId)
    }

    /** Two long presses set a loop: the first marks A, the second B (in either order). */
    override fun onMeasureLongPressed(measureId: String) {
        val map = timemap ?: return
        val first = pendingLoopStart
        if (first == null) {
            pendingLoopStart = measureId
            commandChannel.trySend(WebCommand.SetLoop(measureId, measureId))
            mutableUi.update { it.copy(notice = "Loop start set. Long-press the last measure of the loop.") }
            return
        }
        pendingLoopStart = null
        val (start, end) = map.rangeOfMeasures(first, measureId) ?: return
        val inOrder = (map.measureStartMs(first) ?: 0.0) <= (map.measureStartMs(measureId) ?: 0.0)
        val measures = if (inOrder) first to measureId else measureId to first
        loopMeasures = measures
        playback.setLoop(TimeRange(start, end))
        commandChannel.trySend(WebCommand.SetLoop(measures.first, measures.second))
        mutableUi.update { it.copy(notice = null) }
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

    override fun togglePlay() = playback.togglePlay()

    fun pause() = playback.pause()

    override fun stop() = playback.stop()

    override fun setTempoPercent(percent: Int) = playback.setTempoPercent(percent)

    override fun setCountIn(enabled: Boolean) = playback.setCountInEnabled(enabled)

    override fun setMetronome(enabled: Boolean) = playback.setMetronomeEnabled(enabled)

    override fun setLoopEnabled(enabled: Boolean) {
        val measures = loopMeasures ?: return
        playback.setLoopEnabled(enabled)
        commandChannel.trySend(if (enabled) WebCommand.SetLoop(measures.first, measures.second) else WebCommand.SetLoop(null, null))
    }

    override fun clearLoop() {
        pendingLoopStart = null
        loopMeasures = null
        playback.setLoop(null)
        commandChannel.trySend(WebCommand.SetLoop(null, null))
        mutableUi.update { it.copy(notice = null) }
    }

    fun toggleMute(track: Int) = updateMix { it.toggleMute(track) }

    fun toggleSolo(track: Int) = updateMix { it.toggleSolo(track) }

    private fun updateMix(change: (MixState) -> MixState) {
        mutableUi.update { it.copy(mix = change(it.mix)) }
        val state = mutableUi.value
        playback.setSilencedTracks(state.mix.silencedTracks(state.mixer))
    }

    fun dismissNotice() = mutableUi.update { it.copy(notice = null) }

    override fun setFollow(enabled: Boolean) = mutableUi.update { it.copy(follow = enabled) }

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

    override fun showPage(page: Int) {
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
