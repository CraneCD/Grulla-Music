package com.grulla.music.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.grulla.music.AnnotateTool
import com.grulla.music.ListenMode
import com.grulla.music.ListeningState
import com.grulla.music.TrainerState
import com.grulla.music.core.practice.ScorePractice
import com.grulla.music.core.practice.SpeedTrainerSettings
import java.time.LocalDate
import com.grulla.music.ScoreUiState
import com.grulla.music.audio.AudioState
import com.grulla.music.audio.EngineChoice
import com.grulla.music.core.library.Library
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.core.library.Setlist
import com.grulla.music.core.library.ScoreSettings
import com.grulla.music.core.playback.MixState
import com.grulla.music.core.playback.MixerChannel
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.TimeRange
import com.grulla.music.core.playback.Transport
import com.grulla.music.score.LayoutMode
import androidx.compose.ui.unit.dp
import com.grulla.music.settings.PlayerPreferences
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens in the Crane & Ink theme, day and night, at phone size.
 * Record with `./gradlew :app:recordRoborazziDebug`, compare with
 * `./gradlew :app:verifyRoborazziDebug`. The score area shows real Verovio renders
 * of the sample (the WebView itself does not run under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun image(name: String): ImageBitmap =
        javaClass.getResourceAsStream("/screenshot-fixtures/$name")!!.use { BitmapFactory.decodeStream(it).asImageBitmap() }

    private val now = System.currentTimeMillis()

    private val ode = LibraryEntry(
        id = "ode",
        uri = "asset:samples/ode-to-joy.musicxml",
        title = "Ode to Joy",
        composer = "Ludwig van Beethoven",
        lastOpened = now - 2 * 3_600_000,
        settings = ScoreSettings(tempoPercent = 80, loopStartMs = 2500.0, loopEndMs = 10000.0, loopEnabled = true, lastPositionMs = 12_000.0),
    )
    private val peaceful = LibraryEntry(
        id = "peaceful",
        uri = "content://example/peaceful-days.mxl",
        title = "Peaceful Days",
        composer = "Yasunori Mitsuda",
        lastOpened = now - 26 * 3_600_000,
        settings = ScoreSettings(metronome = true),
    )

    /** Every action does nothing (the screens are only drawn). */
    private val NoActions: ScoreActions =
        Proxy.newProxyInstance(ScoreActions::class.java.classLoader, arrayOf(ScoreActions::class.java)) { _, _, _ -> null } as ScoreActions

    private val NoLibraryActions: LibraryActions =
        Proxy.newProxyInstance(LibraryActions::class.java.classLoader, arrayOf(LibraryActions::class.java)) { _, method, _ ->
            if (method.returnType == String::class.java) "s" else null
        } as LibraryActions

    private val gnossienne = LibraryEntry(
        id = "gnossienne",
        uri = "content://example/gnossienne-1.mxl",
        title = "Gnossienne No. 1",
        composer = "Erik Satie",
        lastOpened = 0,
        addedAt = now - 3_600_000,
    )

    private val library = Library(
        listOf(ode, peaceful, gnossienne),
        listOf(Setlist("recital", "Spring recital", listOf("peaceful", "ode")), Setlist("warmups", "Warm-ups")),
    )

    private val mixer = listOf(MixerChannel(1, "P1", "Piano – upper staff"), MixerChannel(2, "P1", "Piano – lower staff"))

    private val scoreUi = ScoreUiState(
        title = "Ode to Joy",
        composer = "Ludwig van Beethoven",
        ready = true,
        mixer = mixer,
        loopLabel = "Loop 3–5",
    )

    private val playing = PlaybackState(
        transport = Transport.PLAYING,
        positionMs = 11_250.0,
        durationMs = 40_000.0,
        tempoPercent = 80,
        loop = TimeRange(2500.0, 10_000.0),
        loopEnabled = true,
        hasScore = true,
    )

    @Composable
    private fun ScoreImage(name: String, modifier: Modifier) {
        Image(image(name), contentDescription = null, contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter, modifier = modifier.fillMaxWidth())
    }

    private fun capture(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent { GrullaTheme(mode) { content() } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun libraryDay() = capture("library-day", ThemeMode.LIGHT) {
        val thumb = image("score-day.png")
        LibraryContent(library, LibraryView(), {}, thumbnail = { _, m -> ThumbnailImage(thumb, m) }, NoLibraryActions)
    }

    @Test
    fun librarySetlistSearchNight() = capture("library-setlist-night", ThemeMode.DARK) {
        val thumb = image("score-night.png")
        LibraryContent(library, LibraryView(setlistId = "recital"), {}, thumbnail = { _, m -> ThumbnailImage(thumb, m) }, NoLibraryActions, importing = 12)
    }

    @Test
    fun libraryEmptyNight() = capture("library-empty-night", ThemeMode.DARK) {
        LibraryContent(Library(), LibraryView(), {}, thumbnail = { _, m -> ThumbnailImage(null, m) }, NoLibraryActions)
    }

    @Test
    fun scorePlayingLoopDay() = capture("score-loop-day", ThemeMode.LIGHT) {
        ScoreScreenContent(scoreUi, playing, BarInfo(5, 16), NoActions, {}, {}, { _, _ -> }) { ScoreImage("score-day.png", it) }
    }

    @Test
    fun followMeDay() = capture("follow-me-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(
            mix = MixState(soloed = setOf(1)),
            listening = ListeningState(expected = "G5", step = 20, total = 63, part = "Piano – upper staff", hearing = "F5", wrong = "F5"),
        )
        ScoreScreenContent(ui, playing.copy(transport = Transport.PAUSED), BarInfo(6, 16), NoActions, {}, {}, { _, _ -> }) { ScoreImage("score-follow.png", it) }
    }

    @Test
    fun scorePlayingNight() = capture("score-night", ThemeMode.DARK) {
        val ui = scoreUi.copy(loopLabel = null)
        val state = playing.copy(positionMs = 17_500.0, tempoPercent = 100, loop = null, loopEnabled = false, metronomeEnabled = true)
        ScoreScreenContent(ui, state, BarInfo(8, 16), NoActions, {}, {}, { _, _ -> }) { ScoreImage("score-night.png", it) }
    }

    @Test
    fun scorePageViewStoppedDay() = capture("score-page-view-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(layoutMode = LayoutMode.PAGE, page = 2, pageCount = 8, loopLabel = null)
        val state = PlaybackState(durationMs = 40_000.0, hasScore = true, countInEnabled = true)
        ScoreScreenContent(ui, state, BarInfo(1, 16), NoActions, {}, {}, { _, _ -> }) { ScoreImage("score-day.png", it) }
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp-xxhdpi")
    fun scoreLandscapePhone() = capture("score-landscape-phone", ThemeMode.LIGHT) {
        ScoreScreenContent(scoreUi.copy(silent = true), playing, BarInfo(5, 16), NoActions, {}, {}, { _, _ -> }, dockLayout = DockLayout.Compact) {
            ScoreImage("score-day.png", it)
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun scoreTabletSpread() = capture("score-tablet-spread", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(layoutMode = LayoutMode.PAGE, page = 3, pageCount = 6, spread = true)
        ScoreScreenContent(ui, playing, BarInfo(9, 16), NoActions, {}, {}, { _, _ -> }, dockLayout = DockLayout.Wide) {
            ScoreImage("score-spread.png", it)
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h900dp-xhdpi")
    fun scoreTabletop() = capture("score-tabletop", ThemeMode.DARK) {
        ScoreScreenContent(scoreUi.copy(loopLabel = null), playing.copy(loop = null, loopEnabled = false), BarInfo(5, 16), NoActions, {}, {}, { _, _ -> },
            dockLayout = DockLayout.Lower(430.dp)) {
            ScoreImage("score-night.png", it)
        }
    }

    @Test
    fun tempoTrainerDay() = capture("tempo-trainer-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(trainer = TrainerState(75, 1, 2, false), trainerSettings = SpeedTrainerSettings(70, 100, 5, 2))
        ScoreScreenContent(ui, playing.copy(tempoPercent = 75), BarInfo(4, 16), NoActions, {}, {}, { _, _ -> }, openSheet = ScoreSheet.TEMPO) {
            ScoreImage("score-day.png", it)
        }
    }

    @Test
    fun listenSheetDay() = capture("listen-sheet-day", ThemeMode.LIGHT) {
        ScoreScreenContent(scoreUi.copy(myTracks = setOf(1)), playing, BarInfo(4, 16), NoActions, {}, {}, { _, _ -> }, openSheet = ScoreSheet.LISTEN) {
            ScoreImage("score-day.png", it)
        }
    }

    @Test
    fun practiceSheetNight() = capture("practice-sheet-night", ThemeMode.DARK) {
        var log = ScorePractice().withTime(42 * 60_000L, LocalDate.now()).withTime(65 * 60_000L, LocalDate.now().minusDays(20))
        repeat(12) { log = log.withLoopPass() }
        repeat(20) { log = log.withHit(2) }
        repeat(3) { log = log.withMiss(12).withMiss(7) }
        repeat(2) { log = log.withHit(12) }
        repeat(5) { log = log.withHit(7) }
        ScoreScreenContent(scoreUi, playing, BarInfo(4, 16), NoActions, {}, {}, { _, _ -> }, practice = log, openSheet = ScoreSheet.PRACTICE) {
            ScoreImage("score-night.png", it)
        }
    }

    @Test
    fun waitForMeDay() = capture("wait-for-me-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(
            listening = ListeningState(expected = "C4 E4 G4", step = 12, total = 63, part = "Piano – upper staff", mode = ListenMode.WAIT, allChordNotes = true),
        )
        ScoreScreenContent(ui, playing.copy(transport = Transport.WAITING), BarInfo(6, 16), NoActions, {}, {}, { _, _ -> }) {
            ScoreImage("score-follow.png", it)
        }
    }

    @Test
    fun annotateDay() = capture("annotate-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(annotating = AnnotateTool.FINGERING, fingering = "3")
        ScoreScreenContent(ui, playing.copy(transport = Transport.PAUSED), BarInfo(3, 16), NoActions, {}, {}, { _, _ -> }) {
            ScoreImage("score-annotated.png", it)
        }
    }

    @Test
    fun transposeSheetNight() = capture("transpose-sheet-night", ThemeMode.DARK) {
        ScoreScreenContent(scoreUi.copy(transpose = -3), playing, BarInfo(3, 16), NoActions, {}, {}, { _, _ -> }, openSheet = ScoreSheet.TRANSPOSE) {
            ScoreImage("score-night.png", it)
        }
    }

    @Test
    fun mixerSheetDay() = capture("mixer-sheet-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(
            mixer = listOf(MixerChannel(1, "P1", "Flute", program = 73), MixerChannel(2, "P2", "Piano – upper staff", program = 0),
                MixerChannel(3, "P2", "Piano – lower staff", program = 0)),
            mix = MixState(muted = setOf(3), volumes = mapOf(1 to 120), programs = mapOf(2 to 6, 3 to 6)),
        )
        ScoreScreenContent(ui, playing, BarInfo(3, 16), NoActions, {}, {}, { _, _ -> }, openSheet = ScoreSheet.PARTS) {
            ScoreImage("score-day.png", it)
        }
    }

    @Test
    fun settingsDay() = capture("settings-day", ThemeMode.LIGHT) {
        SettingsContent(
            audio = AudioState(choice = EngineChoice.FLUIDSYNTH, status = "FluidSynth · 48 kHz", busy = false, fluidSynthSupported = true),
            themeMode = ThemeMode.SYSTEM,
            onThemeMode = {},
            player = PlayerPreferences(earlyPageTurn = true),
            onPlayer = {},
            onEngine = {},
            onChooseSoundFont = {},
            onBundledSoundFont = {},
            onBack = {},
        )
    }

    @Test
    fun settingsNight() = capture("settings-night", ThemeMode.DARK) {
        SettingsContent(
            audio = AudioState(choice = EngineChoice.FLUIDSYNTH, status = "FluidSynth · 48 kHz", busy = false, fluidSynthSupported = true),
            themeMode = ThemeMode.DARK,
            onThemeMode = {},
            player = PlayerPreferences(),
            onPlayer = {},
            onEngine = {},
            onChooseSoundFont = {},
            onBundledSoundFont = {},
            onBack = {},
        )
    }
}
