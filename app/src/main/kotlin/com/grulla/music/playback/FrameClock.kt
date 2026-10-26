package com.grulla.music.playback

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until the next display frame and returns its timestamp
 * (System.nanoTime() based). Must be called on the main thread.
 */
suspend fun awaitFrameNanos(): Long = suspendCancellableCoroutine { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { frameTimeNanos -> continuation.resume(frameTimeNanos) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}
