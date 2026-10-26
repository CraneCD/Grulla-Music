package com.grulla.music

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.core.library.ScoreSettings
import com.grulla.music.core.listen.ChordEvent
import com.grulla.music.core.listen.FollowController
import com.grulla.music.core.listen.FollowResult
import com.grulla.music.core.listen.FollowScore
import com.grulla.music.core.listen.FollowStep
import com.grulla.music.core.listen.NoteEvent
import com.grulla.music.core.listen.PitchEstimate
import com.grulla.music.core.listen.noteName
import com.grulla.music.core.playback.MixState
import com.grulla.music.core.playback.Mixer
import com.grulla.music.core.playback.MixerChannel
import com.grulla.music.core.playback.PlaybackController
import com.grulla.music.core.playback.PlaybackListener
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.Segment
import com.grulla.music.core.playback.TimeRange
import com.grulla.music.core.playback.Transport
import com.grulla.music.core.practice.ScorePractice
import com.grulla.music.core.practice.SpeedTrainer
import com.grulla.music.core.practice.SpeedTrainerSettings
import com.grulla.music.core.score.LoadedScore
import com.grulla.music.core.score.PartListParser
import com.grulla.music.core.score.RenderedScore
import com.grulla.music.core.score.ScoreLoadException
import com.grulla.music.core.timemap.Timemap
import com.grulla.music.listen.MicrophoneListener
import com.grulla.music.playback.awaitFrameNanos
import com.grulla.music.score.LayoutMode
import com.grulla.music.score.ScoreFiles
import com.grulla.music.score.ScoreOrigin
import com.grulla.music.score.ScoreWebCallbacks
import com.grulla.music.score.ScoreWebViewHost
import com.grulla.music.score.WebCommand
import com.grulla.music.ui.BarInfo
import com.grulla.music.ui.ScoreActions
import com.grulla.music.ui.channelLabel
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    /** Page view shows two pages side by side ([page] is the left one). */
    val spread: Boolean = false,
    /** All parts silent: the score scrolls at the tempo (with the metronome, if on) for hands-free reading. */
    val silent: Boolean = false,
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
    /** Speed trainer settings of this score, and its progress while it runs (null when off). */
    val trainerSettings: SpeedTrainerSettings = SpeedTrainerSettings(),
    val trainer: TrainerState? = null,
    /** The part(s) the user plays, chosen when starting Follow me / Wait for me. */
    val myTracks: Set<Int> = emptySet(),
    /** Semitones, applied to notation and sound. */
    val transpose: Int = 0,
    val concertPitch: Boolean = false,
    /** Drawing on the score with this tool; null when not annotating. */
    val annotating: AnnotateTool? = null,
    /** The fingering the text tool places. */
    val fingering: String = "1",
)

/** Annotation tools; [js] is the tool name in annotate.js. */
enum class AnnotateTool(val js: String) { PEN("pen"), FINGERING("text"), BREATH("breath"), ERASER("erase") }

data class TrainerState(val tempoPercent: Int, val passes: Int, val repeats: Int, val reachedTarget: Boolean)

/** FOLLOW: the score waits for you in silence. WAIT: the other parts play and wait for you. */
enum class ListenMode { FOLLOW, WAIT }

data class ListeningState(
    /** Note names to play next, e.g. "E4" or "C4 E4 G4". */
    val expected: String,
    val step: Int,
    val total: Int,
    /** The parts being followed, e.g. "All parts" or "Piano – upper staff". */
    val part: String,
    /** What the microphone hears right now ("–" when nothing clear). */
    val hearing: String = "–",
    /** The last note that did not match, shown briefly. */
    val wrong: String? = null,
    val mode: ListenMode = ListenMode.FOLLOW,
    /** The next step is a chord and every note of it must be heard. */
    val allChordNotes: Boolean = false,
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

    /** The score's WebView, kept while the user visits the library or settings. */
    val webHost = ScoreWebViewHost(application, this, ::currentScoreBytes)

    /** UTF-8 MusicXML of the current score, served to the WebView (read from a WebView thread). */
    @Volatile
    private var scoreBytes: ByteArray? = null
    private var scoreVersion = 0
    private var webReady = false
    private var openJob: Job? = null

    /** URI of the score in the WebView, so reopening it from the library is instant. */
    private var currentUri: String? = null

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

    /** The practice log of the open score. */
    val practice: StateFlow<ScorePractice?> = combine(entryId, app.practice.log) { id, log -> id?.let { log[it] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        autosaveSettings()
        countPracticeTime()
        viewModelScope.launch {
            app.player.preferences.collect { commandChannel.trySend(WebCommand.SetOptions(earlyTurn = it.earlyPageTurn)) }
        }
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
        if (uri == currentUri && rendered != null && mutableUi.value.error == null) {
            // Still laid out in the kept WebView: just show it again.
            viewModelScope.launch { library.opened(uri, mutableUi.value.title, mutableUi.value.composer) }
            return
        }
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
                mutableUi.update { it.copy(loading = false, error = openError(e)) }
                return@launch
            }
            val title = score.title ?: name.ifBlank { app.getString(R.string.untitled_score) }
            currentUri = uri
            val entry = library.opened(uri, title, score.composer)
            annotationsJson = withContext(Dispatchers.IO) { runCatching { library.loadAnnotations(entry.id) }.getOrNull() } ?: NO_ANNOTATIONS
            entryId.value = entry.id
            savedSettings = entry.settings
            thumbnailWanted = library.thumbnailFile(entry) == null
            showScore(score, title, entry.settings)
            mutableUi.update { it.copy(composer = score.composer) }
        }
    }

    private fun showScore(score: LoadedScore, title: String, settings: ScoreSettings) {
        trainer = null
        playback.holdAt(null)
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
                silent = false,
                spread = false,
                trainer = null,
                scale = settings.scale ?: DEFAULT_SCALE,
                transpose = settings.transpose,
                concertPitch = settings.concertPitch,
                annotating = null,
                layoutMode = if (settings.pageLayout == true) LayoutMode.PAGE else LayoutMode.CONTINUOUS,
                follow = settings.follow,
            )
        }
        if (webReady) sendLoad()
    }

    private fun sendLoad() {
        if (scoreBytes == null) return
        val state = mutableUi.value
        val transpose = if (state.transpose == 0) "" else "%+d".format(state.transpose)
        commandChannel.trySend(WebCommand.Load(ScoreOrigin.scoreUrl(scoreVersion), state.scale, state.layoutMode, transpose, state.concertPitch))
        commandChannel.trySend(WebCommand.SetAnnotations(annotationsJson))
        state.annotating?.let { commandChannel.trySend(WebCommand.Annotate(it.js, state.fingering)) }
    }

    /** Lays the score out again from the file, keeping the settings (after transposing). */
    private fun reload() {
        stopListening()
        saveSettingsNow()
        playback.stop()
        scoreVersion++
        rendered = null
        timemap = null
        playback.clearScore()
        mutableUi.update { it.copy(loading = true, ready = false) }
        if (webReady) sendLoad()
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
        trainer = ui.trainerSettings,
        trainerOn = ui.trainer != null,
        myTracks = ui.myTracks,
        transpose = ui.transpose,
        concertPitch = ui.concertPitch,
        trackVolumes = ui.mix.volumes,
        trackPrograms = ui.mix.programs,
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

    /** A file that could not be opened, in words. */
    private fun openError(e: Exception): String = when {
        e is SecurityException -> app.getString(R.string.error_no_access)
        e is ScoreLoadException -> when (e.reason) {
            ScoreLoadException.Reason.EMPTY -> app.getString(R.string.error_empty)
            ScoreLoadException.Reason.NOT_MUSICXML -> app.getString(R.string.error_not_musicxml)
            ScoreLoadException.Reason.TOO_LARGE -> app.getString(R.string.error_too_large)
            ScoreLoadException.Reason.UNREADABLE -> app.getString(R.string.error_unreadable_detail, e.message ?: "")
        }
        else -> app.getString(R.string.error_cannot_open)
    }

    /** Bar position for the top bar, e.g. bar 5 of 16. */
    fun barAt(positionMs: Double): BarInfo? = timemap?.let { BarInfo(it.barAt(positionMs), it.barCount) }

    private fun loopLabel(range: TimeRange): String? {
        val map = timemap ?: return null
        val first = map.barAt(range.startMs)
        val last = map.barAt(range.endMs - 1)
        return if (first == last) app.getString(R.string.loop_bar, first) else app.getString(R.string.loop_bars, first, last)
    }

    /** Restores what was saved for this score, once it has been rendered. */
    private fun applySettings(settings: ScoreSettings, map: Timemap) {
        playback.setTempoPercent(settings.tempoPercent)
        playback.setCountInEnabled(settings.countIn)
        playback.setMetronomeEnabled(settings.metronome)
        val tracks = mutableUi.value.mixer.map { it.trackIndex }.toSet()
        updateMix {
            MixState(
                muted = settings.mutedTracks intersect tracks,
                soloed = settings.soloedTracks intersect tracks,
                volumes = settings.trackVolumes.filterKeys { it in tracks },
                programs = settings.trackPrograms.filterKeys { it in tracks },
            )
        }
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
        mutableUi.update { it.copy(trainerSettings = settings.trainer, myTracks = settings.myTracks intersect tracks) }
        if (settings.trainerOn && playback.state.value.loop != null) setTrainerOn(true)
        if (settings.lastPositionMs > 0) playback.seekTo(settings.lastPositionMs)
    }

    // --- Callbacks from the WebView ------------------------------------------------

    override fun onWebReady(verovioVersion: String) {
        webReady = true
        commandChannel.trySend(WebCommand.SetOptions(earlyTurn = app.player.preferences.value.earlyPageTurn))
        // Also covers a re-created WebView: it starts empty, so render the current score again.
        sendLoad()
    }

    override fun onFirstPage() {
        mutableUi.update { it.copy(loading = false) }
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
                mutableUi.update { it.copy(loading = false, error = app.getString(R.string.error_prepare_playback, e.message ?: "")) }
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
            mutableUi.update { it.copy(notice = app.getString(R.string.loop_start_set)) }
            return
        }
        pendingLoopStart = null
        setLoopMeasures(first, measureId)
        mutableUi.update { it.copy(notice = null) }
    }

    private fun setLoopMeasures(first: String, last: String) {
        val map = timemap ?: return
        val (start, end) = map.rangeOfMeasures(first, last) ?: return
        val inOrder = (map.measureStartMs(first) ?: 0.0) <= (map.measureStartMs(last) ?: 0.0)
        val measures = if (inOrder) first to last else last to first
        loopMeasures = measures
        playback.setLoop(TimeRange(start, end))
        commandChannel.trySend(WebCommand.SetLoop(measures.first, measures.second))
        mutableUi.update { it.copy(loopLabel = loopLabel(TimeRange(start, end))) }
    }

    override fun onZoomChanged(scale: Int) {
        mutableUi.update { it.copy(scale = scale) }
    }

    override fun onPageChanged(page: Int, pageCount: Int, spread: Boolean) {
        mutableUi.update { it.copy(page = page, pageCount = pageCount, spread = spread) }
    }

    override fun onWebError(message: String) {
        val text = when {
            "could not read this file as MusicXML" in message -> app.getString(R.string.error_not_musicxml)
            message == ScoreWebCallbacks.RENDERER_GONE -> app.getString(R.string.error_renderer_gone)
            else -> app.getString(R.string.error_score_view, message)
        }
        mutableUi.update { it.copy(loading = false, error = text) }
    }

    // --- Callbacks from the playback controller ------------------------------------

    override fun onSegmentChanged(segment: Segment?, follow: Boolean) {
        if (this.follow != null) return // Listening shows the notes to play instead.
        commandChannel.trySend(WebCommand.Highlight(segment?.noteIds.orEmpty(), follow && mutableUi.value.follow))
    }

    override fun onCountInBeat(beat: Int, total: Int) {
        commandChannel.trySend(WebCommand.CountIn(beat, total))
    }

    override fun onPlaybackError(message: String) {
        mutableUi.update { it.copy(error = app.getString(R.string.error_playback, message)) }
    }

    // --- Follow me / Wait for me: listen to the player ---------------------------------

    private var follow: FollowController? = null
    private var microphone: MicrophoneListener? = null
    private var listenMode = ListenMode.FOLLOW
    private var followedPart = ""

    /**
     * Starts listening. [tracks] are the MIDI tracks of the part(s) the user plays
     * (empty: everything). Follow me waits silently; Wait for me plays the other
     * parts and holds at each of the user's notes. Call only with RECORD_AUDIO granted.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun startListening(mode: ListenMode, tracks: Set<Int>) {
        val score = rendered ?: return
        if (follow != null) return
        val state = mutableUi.value
        val chosen = tracks intersect state.mixer.map { it.trackIndex }.toSet()
        mutableUi.update { it.copy(myTracks = chosen) }
        val steps = FollowScore.build(score.timemap, score.pitches, score.midi, chosen.takeIf { it.isNotEmpty() })
        if (steps.steps.isEmpty()) {
            mutableUi.update { it.copy(notice = app.getString(R.string.no_notes_to_follow)) }
            return
        }
        if (mode == ListenMode.WAIT && state.mixer.all { it.trackIndex in chosen || it.trackIndex in state.mix.silencedTracks(state.mixer) }) {
            mutableUi.update { it.copy(notice = app.getString(R.string.wait_needs_other_part)) }
            return
        }
        val prefs = app.player.preferences.value
        playback.pause()
        val controller = FollowController(steps, chordsNeedAllNotes = prefs.chordsNeedAllNotes).also {
            it.startAt(playbackState.value.positionMs)
        }
        followedPart = state.mixer.filter { it.trackIndex in chosen }.map { channelLabel(app.resources, it) }
            .takeIf { it.isNotEmpty() && it.size < state.mixer.size }?.joinToString(", ") ?: app.getString(R.string.all_parts)
        val mic = MicrophoneListener(onNote = ::onHeard, onLevel = ::onHearing, onChord = ::onChordHeard, chordSensitivity = prefs.chordSensitivity)
        if (!mic.start(app)) {
            mutableUi.update { it.copy(error = app.getString(R.string.error_microphone)) }
            return
        }
        follow = controller
        microphone = mic
        listenMode = mode
        if (mode == ListenMode.WAIT) {
            applySilenced()
            playback.holdAt(controller.currentStep?.timeMs)
            playback.play()
        }
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
        val waiting = listenMode == ListenMode.WAIT
        stopListening(seek = !waiting)
        if (waiting) {
            playback.play() // The accompaniment plays to the end.
        } else {
            commandChannel.trySend(WebCommand.Highlight(emptyList(), false))
        }
        mutableUi.update { it.copy(notice = app.getString(R.string.well_done)) }
    }

    override fun stopListening() = stopListening(seek = true)

    private fun stopListening(seek: Boolean) {
        microphone?.stop()
        microphone = null
        val controller = follow ?: return
        follow = null
        mutableUi.update { it.copy(listening = null) }
        if (listenMode == ListenMode.WAIT) {
            playback.holdAt(null)
            playback.pause()
            listenMode = ListenMode.FOLLOW
            applySilenced()
            return
        }
        // Playback continues from where the player got to.
        if (seek) controller.currentStep?.let { playback.seekTo(it.timeMs) }
    }

    private fun onHeard(event: NoteEvent) {
        val controller = follow ?: return
        val step = controller.currentStep ?: return
        if (listenMode == ListenMode.WAIT && !nearHold(step.timeMs)) return // Most likely the accompaniment.
        val result = controller.onNote(event)
        record(result, step)
        when (result) {
            FollowResult.ADVANCED, FollowResult.SKIPPED_AHEAD -> showFollowStep()
            FollowResult.WRONG -> mutableUi.update { it.copy(listening = it.listening?.copy(wrong = noteName(event.midi))) }
            FollowResult.FINISHED -> onHeardFinished()
            FollowResult.IGNORED -> Unit
        }
    }

    private fun onChordHeard(event: ChordEvent) {
        val controller = follow ?: return
        val step = controller.currentStep ?: return
        val result = controller.onChord(event)
        record(result, step)
        when (result) {
            FollowResult.ADVANCED -> showFollowStep()
            FollowResult.FINISHED -> onHeardFinished()
            else -> Unit
        }
    }

    /** In Wait for me, notes only count close to where playback waits for them. */
    private fun nearHold(stepMs: Double): Boolean {
        val state = playbackState.value
        return state.transport == Transport.WAITING || state.positionMs >= stepMs - WAIT_LISTEN_WINDOW_MS
    }

    /** Practice log: a right note is a hit in its bar, a wrong one a miss. */
    private fun record(result: FollowResult, step: FollowStep) {
        val id = entryId.value ?: return
        val bar = timemap?.barAt(step.timeMs) ?: return
        when (result) {
            FollowResult.ADVANCED, FollowResult.FINISHED -> app.practice.update(id) { it.withHit(bar) }
            FollowResult.WRONG -> app.practice.update(id) { it.withMiss(bar) }
            else -> Unit
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
        microphone?.expectedChord = controller.expectedChord
        if (listenMode == ListenMode.WAIT) playback.holdAt(step.timeMs)
        commandChannel.trySend(WebCommand.Highlight(step.noteIds, follow = true, kind = WebCommand.Highlight.Kind.LISTEN))
        val expected = step.pitches.sorted().joinToString(" ") { noteName(it) }
        mutableUi.update {
            it.copy(
                listening = ListeningState(
                    expected = expected,
                    step = controller.state.stepIndex + 1,
                    total = controller.state.total,
                    part = followedPart,
                    hearing = it.listening?.hearing ?: "–",
                    mode = listenMode,
                    allChordNotes = controller.expectedChord != null,
                ),
            )
        }
    }

    // --- Practice: speed trainer, loop passes, time ------------------------------------

    private var trainer: SpeedTrainer? = null

    override fun onLoopPass() {
        entryId.value?.let { id -> app.practice.update(id) { it.withLoopPass() } }
        trainer?.let { t ->
            t.onPass()?.let { tempo -> playback.setTempoPercent(tempo) }
            showTrainer()
        }
        val controller = follow
        val loop = playbackState.value.activeLoop
        if (controller != null && loop != null) {
            controller.startAt(loop.startMs)
            showFollowStep()
        }
    }

    override fun setTrainer(settings: SpeedTrainerSettings) {
        val normalized = settings.normalized()
        mutableUi.update { it.copy(trainerSettings = normalized) }
        if (trainer != null) startTrainer(normalized)
    }

    override fun setTrainerOn(on: Boolean) {
        if (!on) {
            trainer = null
            mutableUi.update { it.copy(trainer = null) }
            return
        }
        if (playbackState.value.loop == null) {
            showNotice(app.getString(R.string.trainer_needs_loop))
            return
        }
        playback.setLoopEnabled(true)
        loopMeasures?.let { commandChannel.trySend(WebCommand.SetLoop(it.first, it.second)) }
        startTrainer(mutableUi.value.trainerSettings)
    }

    private fun startTrainer(settings: SpeedTrainerSettings) {
        val t = SpeedTrainer(settings)
        trainer = t
        playback.setTempoPercent(t.tempoPercent)
        showTrainer()
    }

    private fun showTrainer() {
        val t = trainer ?: return
        mutableUi.update { it.copy(trainer = TrainerState(t.tempoPercent, t.passes, t.settings.repeats, t.reachedTarget)) }
    }

    /** Adds practice time while playing or listening. */
    private fun countPracticeTime() {
        viewModelScope.launch {
            while (true) {
                delay(PRACTICE_TICK_MS)
                val id = entryId.value ?: continue
                if (playbackState.value.isRunning || follow != null) {
                    app.practice.update(id) { it.withTime(PRACTICE_TICK_MS, LocalDate.now()) }
                }
            }
        }
    }

    /** Loops bars [first]..[last] (bar numbers), e.g. from the practice log's trouble bars. */
    override fun loopBars(first: Int, last: Int) {
        val map = timemap ?: return
        val a = map.measureIdOfBar(first) ?: return
        val b = map.measureIdOfBar(last) ?: return
        setLoopMeasures(a, b)
    }

    // --- User actions --------------------------------------------------------------

    override fun togglePlay() {
        if (mutableUi.value.annotating != null) stopAnnotating()
        stopListening()
        playback.togglePlay()
    }

    fun pause() = playback.pause()

    override fun stop() {
        stopListening()
        playback.stop()
    }

    override fun setTempoPercent(percent: Int) {
        if (trainer != null) setTrainerOn(false) // Choosing a tempo by hand ends the trainer.
        playback.setTempoPercent(percent)
    }

    override fun setCountIn(enabled: Boolean) = playback.setCountInEnabled(enabled)

    override fun setMetronome(enabled: Boolean) = playback.setMetronomeEnabled(enabled)

    override fun setLoopEnabled(enabled: Boolean) {
        val measures = loopMeasures ?: return
        playback.setLoopEnabled(enabled)
        commandChannel.trySend(if (enabled) WebCommand.SetLoop(measures.first, measures.second) else WebCommand.SetLoop(null, null))
    }

    override fun explainLoop() = showNotice(app.getString(R.string.loop_explained))

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
        setTrainerOn(false)
        pendingLoopStart = null
        loopMeasures = null
        playback.setLoop(null)
        commandChannel.trySend(WebCommand.SetLoop(null, null))
        mutableUi.update { it.copy(notice = null, loopLabel = null) }
    }

    override fun setSilent(silent: Boolean) {
        mutableUi.update { it.copy(silent = silent) }
        updateMix { it }
    }

    override fun toggleMute(track: Int) = updateMix { it.toggleMute(track) }

    override fun toggleSolo(track: Int) = updateMix { it.toggleSolo(track) }

    private fun updateMix(change: (MixState) -> MixState) {
        mutableUi.update { it.copy(mix = change(it.mix)) }
        applySilenced()
    }

    /** Mute / solo, Silent, and in Wait for me the user's own part. */
    private fun applySilenced() {
        val state = mutableUi.value
        val everything = rendered?.midi?.tracks?.indices?.toSet().orEmpty()
        val silenced = when {
            state.silent -> everything
            follow != null && listenMode == ListenMode.WAIT -> state.mix.silencedTracks(state.mixer) + state.myTracks
            else -> state.mix.silencedTracks(state.mixer)
        }
        playback.setSilencedTracks(silenced)
        playback.setTrackSound(state.mix.volumes, state.mix.programs)
    }

    // --- Mixer: volume and instrument -------------------------------------------------

    override fun setVolume(track: Int, percent: Int) = updateMix { it.withVolume(track, percent) }

    /** Sets the instrument of the part [track] belongs to (all its staves); null restores the score's. */
    override fun setProgram(track: Int, program: Int?) {
        val mixer = mutableUi.value.mixer
        val partId = mixer.firstOrNull { it.trackIndex == track }?.partId
        val tracks = if (partId == null) listOf(track) else mixer.filter { it.partId == partId }.map { it.trackIndex }
        updateMix { it.withProgram(tracks, program) }
    }

    // --- Transpose ---------------------------------------------------------------------

    override fun setTranspose(semitones: Int) {
        val value = semitones.coerceIn(-MAX_TRANSPOSE, MAX_TRANSPOSE)
        if (value == mutableUi.value.transpose) return
        mutableUi.update { it.copy(transpose = value) }
        reload()
    }

    override fun setConcertPitch(enabled: Boolean) {
        if (enabled == mutableUi.value.concertPitch) return
        mutableUi.update { it.copy(concertPitch = enabled) }
        reload()
    }

    // --- Annotations ----------------------------------------------------------------------

    /** The open score's marks, as last reported by the score view. */
    private var annotationsJson = NO_ANNOTATIONS

    override fun onAnnotationsChanged(json: String) {
        annotationsJson = json
        entryId.value?.let { library.saveAnnotations(it, json) }
    }

    override fun startAnnotating() {
        stopListening()
        playback.pause()
        setAnnotateTool(mutableUi.value.annotating ?: AnnotateTool.PEN)
    }

    override fun setAnnotateTool(tool: AnnotateTool) {
        mutableUi.update { it.copy(annotating = tool) }
        commandChannel.trySend(WebCommand.Annotate(tool.js, mutableUi.value.fingering))
    }

    override fun setFingering(text: String) {
        mutableUi.update { it.copy(fingering = text, annotating = AnnotateTool.FINGERING) }
        commandChannel.trySend(WebCommand.Annotate(AnnotateTool.FINGERING.js, text))
    }

    override fun undoAnnotation() {
        commandChannel.trySend(WebCommand.UndoAnnotation)
    }

    override fun stopAnnotating() {
        mutableUi.update { it.copy(annotating = null) }
        commandChannel.trySend(WebCommand.Annotate(null))
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

    /** Page-turn pedal or the page buttons: +1 next, -1 previous. */
    override fun turnPage(direction: Int) {
        commandChannel.trySend(WebCommand.Turn(direction))
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
        webHost.destroy()
    }

    companion object {
        // Verovio scale in percent; must match MIN_SCALE / MAX_SCALE in score.js.
        const val DEFAULT_SCALE = 40
        const val MIN_SCALE = 15
        const val MAX_SCALE = 120
        private const val SCALE_STEP = 5
        private const val AUTOSAVE_DELAY_MS = 800L
        private const val THUMBNAIL_WIDTH = 240
        private const val NO_ANNOTATIONS = "{\"marks\":[]}"
        const val MAX_TRANSPOSE = 12
        private const val PRACTICE_TICK_MS = 5_000L

        /** Wait for me: notes heard earlier than this before the expected one are taken as the accompaniment. */
        private const val WAIT_LISTEN_WINDOW_MS = 400.0
    }
}
