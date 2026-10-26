package com.grulla.music

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.core.listen.FollowController
import com.grulla.music.core.listen.FollowResult
import com.grulla.music.core.listen.FollowScore
import com.grulla.music.core.listen.NoteEvent
import com.grulla.music.core.listen.PitchEstimate
import com.grulla.music.core.listen.noteName
import com.grulla.music.core.library.ScoreSettings
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
import com.grulla.music.listen.MicrophoneListener
import com.grulla.music.playback.awaitFrameNanos
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreFiles
import com.grulla.music.score.ScoreOrigin
import com.grulla.music.score.ScoreWebCallbacks
import com.grulla.music.score.WebCommand
import com.grulla.music.ui.BarInfo
import com.grulla.music.ui.ScoreActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScoreUiState(
    val title: String = "",
    val composer: String? = null,
    val loading: Boolean = false,
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
    /** False until the score has been rendered and its saved settings applied. */
    val ready: Boolean = false,
    /** "Follow me" listening mode; null when off. */
    val listening: ListeningState? = null,
    /** Label of the A–B loop, e.g. "Loop 3–5". */
    val loopLabel: String? = null,
)

data class ListeningState(
    /** Note names to play next, e.g. "E4" or "C4 / E4 / G4". */
    val expected: String,
    val step: Int,
    val total: Int,
    /** The parts being followed, e.g. "All parts" or "Piano – upper staff". */
    val part: String,
    /** What the microphone hears right now ("–" when nothing clear). */
    val hearing: String = "–",
    /** The last note that did not match, shown briefly. */
    val wrong: String? = null,
)

class ScoreViewModel(application: Application) :
    AndroidViewModel(application),
    ScoreWebCallbacks,
    PlaybackListener,
    ScoreActions {
    private val app = application as GrullaApp
    private val files = ScoreFiles(application)
    private val library = app.library

    private val mutableUi = MutableStateFlow(ScoreUiState())
    val ui: StateFlow<ScoreUiState> = mutableUi.asStateFlow()

    private val playback = PlaybackController(
        player = app.audio.player,
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

    /** Library entry of the open score; its settings are saved as they change. */
    private val entryId = MutableStateFlow<String?>(null)
    private var savedSettings: ScoreSettings = ScoreSettings()
    private var thumbnailWanted = false

    /** The rendered score's timemap and MusicXML (for measure lookups and the part list). */
    private var rendered: RenderedScore? = null
    private var timemap: Timemap? = null
    private var musicXml: String? = null

    /** First measure of a loop being set with two long presses. */
    private var pendingLoopStart: String? = null

    /** Measures shaded for the current loop. */
    private var loopMeasures: Pair<String, String>? = null

    val pickerMimeTypes: Array<String> get() = files.pickerMimeTypes

    init {
        autosaveSettings()
    }

    fun currentScoreBytes(): ByteArray? = scoreBytes

    // --- Opening scores --------------------------------------------------------------

    fun openEntry(entry: LibraryEntry) = open(entry.uri)

    fun openSample() = open(ScoreFiles.SAMPLE_URI)

    /**
     * Opens a file chosen with the system picker (or sent by another app). Picker
     * results can keep their read permission, so the score stays in the library.
     */
    fun openDocument(uri: Uri, keepAccess: Boolean) {
        if (keepAccess) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        open(uri.toString())
    }

    private fun open(uri: String) {
        stopListening()
        saveSettingsNow()
        openJob?.cancel()
        playback.stop()
        mutableUi.update { it.copy(loading = true, ready = false, error = null) }
        openJob = viewModelScope.launch {
            val (score, name) = try {
                withContext(Dispatchers.IO) { files.open(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is SecurityException) "This file is no longer accessible. Open it again from the file picker." else e.message
                mutableUi.update { it.copy(loading = false, error = message ?: "Could not open the file.") }
                return@launch
            }
            val title = score.title ?: name.ifBlank { "Score" }
            val entry = library.opened(uri, title, score.composer)
            entryId.value = entry.id
            savedSettings = entry.settings
            thumbnailWanted = library.thumbnailFile(entry) == null
            showScore(score, title, entry.settings)
            mutableUi.update { it.copy(composer = score.composer) }
        }
    }

    private fun showScore(score: LoadedScore, title: String, settings: ScoreSettings) {
        playback.clearScore()
        rendered = null
        timemap = null
        musicXml = score.musicXml
        pendingLoopStart = null
        loopMeasures = null
        scoreBytes = score.musicXml.toByteArray(Charsets.UTF_8)
        scoreVersion++
        mutableUi.update {
            it.copy(
                title = title,
                composer = null,
                loopLabel = null,
                loading = true,
                ready = false,
                error = null,
                page = 1,
                pageCount = 0,
                mixer = emptyList(),
                mix = MixState(),
                scale = settings.scale ?: DEFAULT_SCALE,
                layoutMode = if (settings.pageLayout == true) LayoutMode.PAGE else LayoutMode.CONTINUOUS,
                follow = settings.follow,
            )
        }
        if (webReady) sendLoad()
    }

    private fun sendLoad() {
        if (scoreBytes == null) return
        val state = mutableUi.value
        commandChannel.trySend(WebCommand.Load(ScoreOrigin.scoreUrl(scoreVersion), state.scale, state.layoutMode))
    }

    /** Leaving the score screen: stop the music and remember where we were. */
    fun leaveScore() {
        stopListening()
        playback.pause()
        saveSettingsNow()
    }

    // --- Per-score settings ------------------------------------------------------------

    private fun currentSettings(ui: ScoreUiState, playback: PlaybackState) = ScoreSettings(
        scale = ui.scale,
        pageLayout = ui.layoutMode == LayoutMode.PAGE,
        tempoPercent = playback.tempoPercent,
        countIn = playback.countInEnabled,
        metronome = playback.metronomeEnabled,
        follow = ui.follow,
        loopStartMs = playback.loop?.startMs,
        loopEndMs = playback.loop?.endMs,
        loopEnabled = playback.loopEnabled,
        mutedTracks = ui.mix.muted,
        soloedTracks = ui.mix.soloed,
        lastPositionMs = playback.positionMs,
    )

    /** Saves settings when they have been stable for a moment (so not continuously while playing). */
    @OptIn(FlowPreview::class)
    private fun autosaveSettings() {
        viewModelScope.launch {
            combine(entryId, ui, playbackState) { id, ui, playback ->
                if (id == null || !ui.ready) null else id to currentSettings(ui, playback)
            }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { (id, settings) ->
                    if (settings != savedSettings) {
                        savedSettings = settings
                        library.saveSettings(id, settings)
                    }
                }
        }
    }

    private fun saveSettingsNow() {
        val id = entryId.value ?: return
        if (!mutableUi.value.ready) return
        val settings = currentSettings(mutableUi.value, playbackState.value)
        if (settings != savedSettings) {
            savedSettings = settings
            library.saveSettings(id, settings)
        }
    }

    /** Bar position for the top bar, e.g. bar 5 of 16. */
    fun barAt(positionMs: Double): BarInfo? = timemap?.let { BarInfo(it.barAt(positionMs), it.barCount) }

    private fun loopLabel(range: TimeRange): String? {
        val map = timemap ?: return null
        val first = map.barAt(range.startMs)
        val last = map.barAt(range.endMs - 1)
        return if (first == last) "Loop bar $first" else "Loop $first–$last"
    }

    /** Restores what was saved for this score, once it has been rendered. */
    private fun applySettings(settings: ScoreSettings, map: Timemap) {
        playback.setTempoPercent(settings.tempoPercent)
        playback.setCountInEnabled(settings.countIn)
        playback.setMetronomeEnabled(settings.metronome)
        val tracks = mutableUi.value.mixer.map { it.trackIndex }.toSet()
        updateMix { MixState(muted = settings.mutedTracks intersect tracks, soloed = settings.soloedTracks intersect tracks) }
        val start = settings.loopStartMs
        val end = settings.loopEndMs
        if (start != null && end != null && end > start && end <= map.durationMs + 1) {
            val first = map.measureAt(start)
            val last = map.measureAt(end - 1)
            if (first != null && last != null) {
                loopMeasures = first.id to last.id
                playback.setLoop(TimeRange(start, end))
                mutableUi.update { it.copy(loopLabel = loopLabel(TimeRange(start, end))) }
                playback.setLoopEnabled(settings.loopEnabled)
                if (settings.loopEnabled) commandChannel.trySend(WebCommand.SetLoop(first.id, last.id))
            }
        }
        if (settings.lastPositionMs > 0) playback.seekTo(settings.lastPositionMs)
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
            val (rendered, channels) = try {
                withContext(Dispatchers.Default) {
                    val rendered = RenderedScore.parse(payloadJson)
                    // The mixer is optional: a part list we cannot read just means no mixer.
                    val parts = xml?.let { runCatching { PartListParser.parse(it) }.getOrNull() }.orEmpty()
                    rendered to Mixer.channels(parts, rendered.midi)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableUi.update { it.copy(loading = false, error = "Could not prepare playback: ${e.message}") }
                return@launch
            }
            if (rendered.source != expectedSource || ScoreOrigin.scoreUrl(scoreVersion) != expectedSource) return@launch
            stopListening()
            this@ScoreViewModel.rendered = rendered
            timemap = rendered.timemap
            playback.setScore(rendered.timemap, rendered.midi)
            mutableUi.update { it.copy(loading = false, pageCount = rendered.pageCount, mixer = channels, mix = MixState()) }
            applySettings(savedSettings, rendered.timemap)
            mutableUi.update { it.copy(ready = true) }
            if (thumbnailWanted) {
                thumbnailWanted = false
                commandChannel.trySend(WebCommand.Thumbnail(THUMBNAIL_WIDTH))
            }
        }
    }

    override fun onThumbnail(pngDataUrl: String) {
        val id = entryId.value ?: return
        val png = runCatching { Base64.decode(pngDataUrl.substringAfter("base64,"), Base64.DEFAULT) }.getOrNull() ?: return
        library.saveThumbnail(id, png)
    }

    override fun onMeasureTapped(measureId: String) {
        val follow = follow
        if (follow != null) {
            // While listening, tapping a measure moves the expected position there.
            follow.startAt(timemap?.measureStartMs(measureId) ?: return)
            showFollowStep()
            return
        }
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
        mutableUi.update { it.copy(notice = null, loopLabel = loopLabel(TimeRange(start, end))) }
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

    // --- "Follow me": listen to the player --------------------------------------------

    private var follow: FollowController? = null
    private var microphone: MicrophoneListener? = null
    private var followedPart = "All parts"

    /** Call only with the RECORD_AUDIO permission granted. */
    @android.annotation.SuppressLint("MissingPermission")
    fun startListening() {
        val score = rendered ?: return
        if (follow != null) return
        playback.pause()
        // Follow the parts that are audible in the mixer: solo or mute the others to choose your part.
        val state = mutableUi.value
        val silenced = state.mix.silencedTracks(state.mixer)
        val tracks = state.mixer.map { it.trackIndex }.filter { it !in silenced }.toSet().takeIf { state.mixer.isNotEmpty() }
        val steps = FollowScore.build(score.timemap, score.pitches, score.midi, tracks)
        if (steps.steps.isEmpty()) {
            mutableUi.update { it.copy(notice = "There are no notes to follow in the selected parts.") }
            return
        }
        val controller = FollowController(steps).also { it.startAt(playbackState.value.positionMs) }
        followedPart = state.mixer.filter { tracks == null || it.trackIndex in tracks }.map { it.label }
            .takeIf { it.isNotEmpty() && it.size < state.mixer.size }?.joinToString(", ") ?: "All parts"
        val mic = MicrophoneListener(onNote = ::onHeard, onLevel = ::onHearing)
        if (!mic.start(app)) {
            mutableUi.update { it.copy(error = "The microphone could not be opened.") }
            return
        }
        follow = controller
        microphone = mic
        showFollowStep()
    }

    override fun skipNote() {
        val controller = follow ?: return
        if (controller.skip() == FollowResult.FINISHED) {
            onHeardFinished()
        } else {
            showFollowStep()
        }
    }

    private fun onHeardFinished() {
        stopListening()
        commandChannel.trySend(WebCommand.Highlight(emptyList(), false))
        mutableUi.update { it.copy(notice = "Well done! You reached the end.") }
    }

    override fun stopListening() {
        microphone?.stop()
        microphone = null
        val controller = follow ?: return
        follow = null
        mutableUi.update { it.copy(listening = null) }
        // Playback continues from where the player got to.
        controller.currentStep?.let { playback.seekTo(it.timeMs) }
    }

    private fun onHeard(event: NoteEvent) {
        val controller = follow ?: return
        when (controller.onNote(event)) {
            FollowResult.ADVANCED, FollowResult.SKIPPED_AHEAD -> showFollowStep()
            FollowResult.WRONG -> mutableUi.update { it.copy(listening = it.listening?.copy(wrong = noteName(event.midi))) }
            FollowResult.FINISHED -> onHeardFinished()
            FollowResult.IGNORED -> Unit
        }
    }

    private fun onHearing(estimate: PitchEstimate) {
        val midi = estimate.midi
        val name = if (midi != null && estimate.confidence >= 0.75) noteName(Math.round(midi).toInt()) else "–"
        mutableUi.update { state -> state.copy(listening = state.listening?.copy(hearing = name)) }
    }

    private fun showFollowStep() {
        val controller = follow ?: return
        val step = controller.currentStep ?: return
        commandChannel.trySend(WebCommand.Highlight(step.noteIds, follow = true, kind = WebCommand.Highlight.Kind.LISTEN))
        val expected = step.pitches.sorted().joinToString(" / ") { noteName(it) }
        mutableUi.update {
            it.copy(
                listening = ListeningState(
                    expected = expected,
                    step = controller.state.stepIndex + 1,
                    total = controller.state.total,
                    part = followedPart,
                    hearing = it.listening?.hearing ?: "–",
                ),
            )
        }
    }

    // --- User actions --------------------------------------------------------------

    override fun togglePlay() {
        stopListening()
        playback.togglePlay()
    }

    fun pause() = playback.pause()

    override fun stop() {
        stopListening()
        playback.stop()
    }

    override fun setTempoPercent(percent: Int) = playback.setTempoPercent(percent)

    override fun setCountIn(enabled: Boolean) = playback.setCountInEnabled(enabled)

    override fun setMetronome(enabled: Boolean) = playback.setMetronomeEnabled(enabled)

    override fun setLoopEnabled(enabled: Boolean) {
        val measures = loopMeasures ?: return
        playback.setLoopEnabled(enabled)
        commandChannel.trySend(if (enabled) WebCommand.SetLoop(measures.first, measures.second) else WebCommand.SetLoop(null, null))
    }

    override fun explainLoop() = showNotice("To loop a passage, long-press its first bar, then its last bar.")

    override fun seekToFraction(fraction: Float) {
        val map = timemap ?: return
        val time = fraction.coerceIn(0f, 1f) * map.durationMs
        val follow = follow
        if (follow != null) {
            follow.startAt(time)
            showFollowStep()
        } else {
            playback.seekTo(time)
        }
    }

    override fun clearLoop() {
        pendingLoopStart = null
        loopMeasures = null
        playback.setLoop(null)
        commandChannel.trySend(WebCommand.SetLoop(null, null))
        mutableUi.update { it.copy(notice = null, loopLabel = null) }
    }

    override fun toggleMute(track: Int) = updateMix { it.toggleMute(track) }

    override fun toggleSolo(track: Int) = updateMix { it.toggleSolo(track) }

    private fun updateMix(change: (MixState) -> MixState) {
        mutableUi.update { it.copy(mix = change(it.mix)) }
        val state = mutableUi.value
        playback.setSilencedTracks(state.mix.silencedTracks(state.mixer))
    }

    override fun setFollow(enabled: Boolean) = mutableUi.update { it.copy(follow = enabled) }

    override fun zoomIn() = setScale(mutableUi.value.scale + SCALE_STEP)

    override fun zoomOut() = setScale(mutableUi.value.scale - SCALE_STEP)

    private fun setScale(scale: Int) {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        mutableUi.update { it.copy(scale = clamped) }
        sendLayout()
    }

    override fun toggleLayoutMode() {
        mutableUi.update {
            it.copy(layoutMode = if (it.layoutMode == LayoutMode.PAGE) LayoutMode.CONTINUOUS else LayoutMode.PAGE)
        }
        sendLayout()
    }

    override fun showPage(page: Int) {
        commandChannel.trySend(WebCommand.ShowPage(page))
    }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    fun dismissNotice() = mutableUi.update { it.copy(notice = null) }

    fun showNotice(message: String) = mutableUi.update { it.copy(notice = message) }

    private fun sendLayout() {
        val state = mutableUi.value
        commandChannel.trySend(WebCommand.SetLayout(state.scale, state.layoutMode))
    }

    override fun onCleared() {
        stopListening()
        saveSettingsNow()
        playback.release()
        commandChannel.close()
    }

    companion object {
        // Verovio scale in percent; must match MIN_SCALE / MAX_SCALE in score.js.
        const val DEFAULT_SCALE = 40
        const val MIN_SCALE = 15
        const val MAX_SCALE = 120
        private const val SCALE_STEP = 5
        private const val AUTOSAVE_DELAY_MS = 800L
        private const val THUMBNAIL_WIDTH = 240
    }
}
