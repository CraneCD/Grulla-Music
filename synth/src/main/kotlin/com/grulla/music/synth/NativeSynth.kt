package com.grulla.music.synth

/** JNI surface of libgrulla_synth (synth/src/main/cpp/jni_bridge.cpp). Handles are native Engine pointers. */
internal object NativeSynth {
    init {
        System.loadLibrary("grulla_synth")
    }

    /** Returns 0 on failure, with the reason in error[0]. */
    @JvmStatic external fun nativeCreate(error: Array<String?>): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeLoadSoundFont(handle: Long, path: String): Boolean

    /** packed[i] = status | data1 << 8 | data2 << 16, at timesMs[i]. */
    @JvmStatic external fun nativeSetSequence(handle: Long, timesMs: DoubleArray, packed: IntArray, endMs: Double)

    @JvmStatic external fun nativePlay(handle: Long)

    @JvmStatic external fun nativePause(handle: Long)

    @JvmStatic external fun nativeStop(handle: Long)

    @JvmStatic external fun nativeSetLoop(handle: Long, startMs: Double, endMs: Double)

    @JvmStatic external fun nativePositionMs(handle: Long): Double

    @JvmStatic external fun nativeIsPlaying(handle: Long): Boolean

    @JvmStatic external fun nativeConsumeCompleted(handle: Long): Boolean

    @JvmStatic external fun nativeSampleRate(handle: Long): Int
}
