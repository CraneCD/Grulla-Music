package com.grulla.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.grulla.music.ui.GrullaTheme
import com.grulla.music.ui.LibraryScreen
import com.grulla.music.ui.ScoreScreen
import com.grulla.music.ui.SettingsScreen

private enum class Screen { LIBRARY, SCORE, SETTINGS }

class MainActivity : ComponentActivity() {
    private val viewModel: ScoreViewModel by viewModels()

    /** Set when an "Open with" intent arrives; the UI then switches to the score. */
    private var openedFromIntent by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleViewIntent(intent)
        val app = application as GrullaApp
        setContent {
            GrullaTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
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
                    )
                    Screen.SCORE -> {
                        BackHandler(onBack = toLibrary)
                        ScoreScreen(
                            viewModel,
                            onBack = toLibrary,
                            onOpenSettings = {
                                viewModel.leaveScore()
                                settingsReturn = Screen.SCORE
                                screen = Screen.SETTINGS
                            },
                        )
                    }
                    Screen.SETTINGS -> {
                        BackHandler { screen = settingsReturn }
                        SettingsScreen(app.audio, onBack = { screen = settingsReturn })
                    }
                }
            }
        }
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
