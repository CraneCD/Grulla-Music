package com.grulla.music

import android.app.Application
import com.grulla.music.audio.AudioEngine
import kotlinx.coroutines.MainScope

/** Holds the app-wide services. */
class GrullaApp : Application() {
    private val appScope = MainScope()

    val audio: AudioEngine by lazy { AudioEngine(this, appScope) }
}
