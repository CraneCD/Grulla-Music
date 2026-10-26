package com.grulla.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.grulla.music.ui.GrullaTheme
import com.grulla.music.ui.ScoreScreen

class MainActivity : ComponentActivity() {
    private val viewModel: ScoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleViewIntent(intent)
        setContent {
            GrullaTheme {
                // Don't keep playing when the user leaves the app.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
                val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.openDocument(uri)
                }
                ScoreScreen(viewModel, onOpenFile = { openDocument.launch(viewModel.pickerMimeTypes) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Grulla Music" from a file manager. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(viewModel::openDocument)
    }
}
