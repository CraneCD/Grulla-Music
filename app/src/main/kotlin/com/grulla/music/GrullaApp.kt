package com.grulla.music

import android.app.Application
import com.grulla.music.audio.AudioEngine
import com.grulla.music.library.LibraryRepository
import com.grulla.music.library.PracticeRepository
import com.grulla.music.settings.PlayerSettings
import com.grulla.music.ui.AppearanceSettings
import kotlinx.coroutines.MainScope

/** Holds the app-wide services. */
class GrullaApp : Application() {
    private val appScope = MainScope()

    val audio: AudioEngine by lazy { AudioEngine(this, appScope) }

    val library: LibraryRepository by lazy { LibraryRepository(this, appScope) }

    val practice: PracticeRepository by lazy { PracticeRepository(this, appScope) }

    val appearance: AppearanceSettings by lazy { AppearanceSettings(this) }

    val player: PlayerSettings by lazy { PlayerSettings(this) }
}
