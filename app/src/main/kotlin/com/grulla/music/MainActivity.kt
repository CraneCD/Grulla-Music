package com.grulla.music

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.grulla.music.ui.FindScoresScreen
import com.grulla.music.ui.GrullaTheme
import com.grulla.music.ui.LibraryScreen
import com.grulla.music.ui.ScoreScreen
import com.grulla.music.ui.SettingsScreen
import com.grulla.music.ui.Tabletop
import com.grulla.music.ui.isNightTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private enum class Screen { LIBRARY, SCORE, SETTINGS, FIND }

class MainActivity : ComponentActivity() {
    private val viewModel: ScoreViewModel by viewModels()

    /** Set when an "Open with" intent arrives; the UI then switches to the score. */
    private var openedFromIntent by mutableStateOf(false)

    /** Whether the score is on screen, for page-turn pedals. */
    private var scoreShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleViewIntent(intent)
        val app = application as GrullaApp
        setContent {
            val themeMode by app.appearance.mode.collectAsStateWithLifecycle()
            val night = isNightTheme(themeMode)
            // Status and navigation bar icons follow the app's theme, not only the system's.
            DisposableEffect(night) {
                val bars = if (night) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            val tabletop by tabletopPosture().collectAsStateWithLifecycle(null)
            GrullaTheme(themeMode) {
                var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                SideEffect { scoreShown = screen == Screen.SCORE }
                var settingsReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                if (openedFromIntent) {
                    openedFromIntent = false
                    screen = Screen.SCORE
                }
                // Don't keep playing when the user leaves the app.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.leaveScore() }
                val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        viewModel.openDocument(uri, keepAccess = true)
                        screen = Screen.SCORE
                    }
                }
                val toLibrary = {
                    viewModel.leaveScore()
                    screen = Screen.LIBRARY
                }
                when (screen) {
                    Screen.LIBRARY -> LibraryScreen(
                        repository = app.library,
                        onOpenEntry = {
                            viewModel.openEntry(it)
                            screen = Screen.SCORE
                        },
                        onOpenFile = { openDocument.launch(viewModel.pickerMimeTypes) },
                        onOpenSample = {
                            viewModel.openSample()
                            screen = Screen.SCORE
                        },
                        onOpenSettings = {
                            settingsReturn = Screen.LIBRARY
                            screen = Screen.SETTINGS
                        },
                        onRemoved = { app.practice.remove(it.id) },
                        onFindScores = { screen = Screen.FIND },
                    )
                    Screen.SCORE -> {
                        BackHandler(onBack = toLibrary)
                        ScoreScreen(
                            viewModel,
                            night = night,
                            onBack = toLibrary,
                            onOpenSettings = {
                                viewModel.leaveScore()
                                settingsReturn = Screen.SCORE
                                screen = Screen.SETTINGS
                            },
                            tabletop = tabletop,
                        )
                    }
                    Screen.FIND -> {
                        BackHandler { screen = Screen.LIBRARY }
                        FindScoresScreen(
                            repository = app.catalog,
                            onOpen = { uri ->
                                viewModel.openDownloaded(uri)
                                screen = Screen.SCORE
                            },
                            onBack = { screen = Screen.LIBRARY },
                        )
                    }
                    Screen.SETTINGS -> {
                        BackHandler { screen = settingsReturn }
                        SettingsScreen(app.audio, app.appearance, app.player, onBack = { screen = settingsReturn })
                    }
                }
            }
        }
    }

    /**
     * Bluetooth page-turn pedals act as keyboards. Page Up / Down always turn the
     * page; arrow keys (the other common pedal mode) unless switched off in settings.
     */
    // Activity.dispatchKeyEvent is public framework API; only androidx's override of it is marked restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (scoreShown) {
            val arrows = (application as GrullaApp).player.preferences.value.arrowKeysTurnPages
            val direction = PedalKeys.direction(event.keyCode, arrows)
            if (direction != 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) viewModel.turnPage(direction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A foldable half open with a horizontal hinge (like a laptop), as the room below the hinge. */
    private fun tabletopPosture(): Flow<Tabletop?> =
        WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this).map { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL
            } ?: return@map null
            val window = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
            val below = window.bottom - fold.bounds.bottom
            if (below <= 0) null else Tabletop((below / resources.displayMetrics.density).dp)
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Grulla Music" from a file manager. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // A lasting grant is only possible if the sending app offered one.
        val persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        viewModel.openDocument(uri, keepAccess = persistable)
        openedFromIntent = true
    }
}
