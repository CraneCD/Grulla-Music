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
import com.grulla.music.ListeningState
import com.grulla.music.ScoreUiState
import com.grulla.music.audio.AudioState
import com.grulla.music.audio.EngineChoice
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.core.library.ScoreSettings
import com.grulla.music.core.playback.MixState
import com.grulla.music.core.playback.MixerChannel
import com.grulla.music.core.playback.PlaybackState
import com.grulla.music.core.playback.TimeRange
import com.grulla.music.core.playback.Transport
import com.grulla.music.score.LayoutMode
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

    private object NoActions : ScoreActions {
        override fun togglePlay() = Unit
        override fun stop() = Unit
        override fun setTempoPercent(percent: Int) = Unit
        override fun setCountIn(enabled: Boolean) = Unit
        override fun setMetronome(enabled: Boolean) = Unit
        override fun setLoopEnabled(enabled: Boolean) = Unit
        override fun clearLoop() = Unit
        override fun explainLoop() = Unit
        override fun setFollow(enabled: Boolean) = Unit
        override fun showPage(page: Int) = Unit
        override fun seekToFraction(fraction: Float) = Unit
        override fun zoomIn() = Unit
        override fun zoomOut() = Unit
        override fun toggleLayoutMode() = Unit
        override fun toggleMute(track: Int) = Unit
        override fun toggleSolo(track: Int) = Unit
        override fun stopListening() = Unit
        override fun skipNote() = Unit
    }

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
        LibraryContent(listOf(ode, peaceful), thumbnail = { _, m -> ThumbnailImage(thumb, m) }, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryEmptyNight() = capture("library-empty-night", ThemeMode.DARK) {
        LibraryContent(emptyList(), thumbnail = { _, m -> ThumbnailImage(null, m) }, {}, {}, {}, {}, {})
    }

    @Test
    fun scorePlayingLoopDay() = capture("score-loop-day", ThemeMode.LIGHT) {
        ScoreScreenContent(scoreUi, playing, BarInfo(5, 16), NoActions, {}, {}, {}) { ScoreImage("score-day.png", it) }
    }

    @Test
    fun followMeDay() = capture("follow-me-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(
            mix = MixState(soloed = setOf(1)),
            listening = ListeningState(expected = "G5", step = 20, total = 63, part = "Piano – upper staff", hearing = "F5", wrong = "F5"),
        )
        ScoreScreenContent(ui, playing.copy(transport = Transport.PAUSED), BarInfo(6, 16), NoActions, {}, {}, {}) { ScoreImage("score-follow.png", it) }
    }

    @Test
    fun scorePlayingNight() = capture("score-night", ThemeMode.DARK) {
        val ui = scoreUi.copy(loopLabel = null)
        val state = playing.copy(positionMs = 17_500.0, tempoPercent = 100, loop = null, loopEnabled = false, metronomeEnabled = true)
        ScoreScreenContent(ui, state, BarInfo(8, 16), NoActions, {}, {}, {}) { ScoreImage("score-night.png", it) }
    }

    @Test
    fun scorePageViewStoppedDay() = capture("score-page-view-day", ThemeMode.LIGHT) {
        val ui = scoreUi.copy(layoutMode = LayoutMode.PAGE, page = 2, pageCount = 8, loopLabel = null)
        val state = PlaybackState(durationMs = 40_000.0, hasScore = true, countInEnabled = true)
        ScoreScreenContent(ui, state, BarInfo(1, 16), NoActions, {}, {}, {}) { ScoreImage("score-day.png", it) }
    }

    @Test
    fun settingsDay() = capture("settings-day", ThemeMode.LIGHT) {
        SettingsContent(
            audio = AudioState(choice = EngineChoice.FLUIDSYNTH, status = "FluidSynth · 48 kHz", busy = false, fluidSynthSupported = true),
            themeMode = ThemeMode.SYSTEM,
            onThemeMode = {},
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
            onEngine = {},
            onChooseSoundFont = {},
            onBundledSoundFont = {},
            onBack = {},
        )
    }
}
