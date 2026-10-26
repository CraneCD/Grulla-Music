package com.grulla.music.audio

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.edit
import com.grulla.music.playback.MediaPlayerMidiPlayer
import com.grulla.music.synth.FluidSynthMidiPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class EngineChoice { FLUIDSYNTH, ANDROID_MIDI }

data class AudioState(
    val choice: EngineChoice = EngineChoice.FLUIDSYNTH,
    /** Display name of the SoundFont in use. */
    val soundFontName: String = AudioEngine.DEFAULT_SOUNDFONT_NAME,
    val customSoundFont: Boolean = false,
    /** What is actually playing, e.g. "FluidSynth · 48 kHz". */
    val status: String = "Starting…",
    val busy: Boolean = true,
    /** FluidSynth needs AAudio (Android 8.1+). */
    val fluidSynthSupported: Boolean = AudioEngine.FLUIDSYNTH_SUPPORTED,
)

/**
 * The app-wide audio engine: FluidSynth with a SoundFont by default, Android's
 * built-in MIDI synthesizer as a fallback. Owns the shared [player].
 */
class AudioEngine(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val soundFontDir = File(app.filesDir, "soundfonts")
    private val mediaPlayer = MediaPlayerMidiPlayer(app)
    private var fluidSynth: FluidSynthMidiPlayer? = null
    private var loadedSoundFont: File? = null
    private val mutex = Mutex()

    val player = SwitchableMidiPlayer(mediaPlayer)

    private val mutableState = MutableStateFlow(
        AudioState(
            choice = savedChoice(),
            soundFontName = prefs.getString(KEY_CUSTOM_NAME, null) ?: DEFAULT_SOUNDFONT_NAME,
            customSoundFont = prefs.getString(KEY_CUSTOM_NAME, null) != null,
        ),
    )
    val state: StateFlow<AudioState> = mutableState.asStateFlow()

    init {
        scope.launch {
            apply()
            player.markReady()
        }
    }

    fun setChoice(choice: EngineChoice) {
        prefs.edit { putString(KEY_ENGINE, choice.name) }
        mutableState.update { it.copy(choice = choice) }
        scope.launch { apply() }
    }

    /** Copies a user-chosen .sf2 / .sf3 into app storage and switches to it. */
    fun importSoundFont(uri: Uri) {
        scope.launch {
            mutableState.update { it.copy(busy = true, status = "Copying SoundFont…") }
            val result = runCatching { withContext(Dispatchers.IO) { copySoundFont(uri) } }
            result.onSuccess { name ->
                prefs.edit { putString(KEY_CUSTOM_NAME, name) }
                mutableState.update { it.copy(soundFontName = name, customSoundFont = true) }
                apply()
            }
            result.onFailure { error ->
                mutableState.update { it.copy(busy = false, status = "Could not use that file: ${error.message}") }
            }
        }
    }

    fun useDefaultSoundFont() {
        prefs.edit { remove(KEY_CUSTOM_NAME) }
        File(soundFontDir, CUSTOM_FILE).delete()
        mutableState.update { it.copy(soundFontName = DEFAULT_SOUNDFONT_NAME, customSoundFont = false) }
        scope.launch { apply() }
    }

    private suspend fun apply() = mutex.withLock {
        mutableState.update { it.copy(busy = true, status = "Starting…") }
        if (state.value.choice == EngineChoice.ANDROID_MIDI || !FLUIDSYNTH_SUPPORTED) {
            player.use(mediaPlayer)
            val note = if (FLUIDSYNTH_SUPPORTED) "" else " (FluidSynth needs Android 8.1 or newer)"
            mutableState.update { it.copy(busy = false, status = "Android built-in MIDI$note") }
            return@withLock
        }
        val result = runCatching { withContext(Dispatchers.IO) { startFluidSynth() } }
        result.onSuccess { synth ->
            player.use(synth)
            val khz = synth.sampleRate / 1000.0
            mutableState.update { it.copy(busy = false, status = "FluidSynth · ${"%.1f".format(khz).removeSuffix(".0")} kHz") }
        }
        result.onFailure { error ->
            player.use(mediaPlayer)
            mutableState.update {
                it.copy(busy = false, status = "FluidSynth unavailable (${error.message}); using Android built-in MIDI")
            }
        }
    }

    /** Creates the engine once and (re)loads the SoundFont if it changed. Blocking. */
    private fun startFluidSynth(): FluidSynthMidiPlayer {
        val synth = fluidSynth ?: FluidSynthMidiPlayer.create().getOrThrow().also { fluidSynth = it }
        val file = currentSoundFontFile()
        if (file != loadedSoundFont) {
            check(synth.loadSoundFont(file.absolutePath)) { "the SoundFont could not be loaded" }
            loadedSoundFont = file
        }
        return synth
    }

    private fun currentSoundFontFile(): File {
        val custom = File(soundFontDir, CUSTOM_FILE)
        if (prefs.getString(KEY_CUSTOM_NAME, null) != null && custom.isFile) return custom
        // FluidSynth reads SoundFonts from files, so the bundled one is copied out of the APK once.
        val bundled = File(soundFontDir, DEFAULT_SOUNDFONT_FILE)
        val expectedSize = app.assets.openFd("soundfonts/$DEFAULT_SOUNDFONT_FILE").use { it.length }
        if (!bundled.isFile || bundled.length() != expectedSize) {
            soundFontDir.mkdirs()
            val partial = File(soundFontDir, "$DEFAULT_SOUNDFONT_FILE.part")
            app.assets.open("soundfonts/$DEFAULT_SOUNDFONT_FILE").use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(bundled)
        }
        return bundled
    }

    private fun copySoundFont(uri: Uri): String {
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "SoundFont"
        soundFontDir.mkdirs()
        val partial = File(soundFontDir, "$CUSTOM_FILE.part")
        resolver.openInputStream(uri)?.use { input -> partial.outputStream().use { input.copyTo(it) } }
            ?: error("the file could not be opened")
        // A SoundFont is a RIFF file of form "sfbk" (both .sf2 and .sf3).
        val header = partial.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "sfbk") {
            partial.delete()
            error("it is not a SoundFont (.sf2 / .sf3)")
        }
        val target = File(soundFontDir, CUSTOM_FILE)
        target.delete()
        partial.renameTo(target)
        loadedSoundFont = null
        return name
    }

    private fun savedChoice(): EngineChoice {
        val default = if (FLUIDSYNTH_SUPPORTED) EngineChoice.FLUIDSYNTH else EngineChoice.ANDROID_MIDI
        return prefs.getString(KEY_ENGINE, null)?.let { runCatching { EngineChoice.valueOf(it) }.getOrNull() } ?: default
    }

    companion object {
        const val DEFAULT_SOUNDFONT_NAME = "FluidR3 Mono GM (bundled)"
        private const val DEFAULT_SOUNDFONT_FILE = "FluidR3Mono_GM.sf3"
        private const val CUSTOM_FILE = "custom.sf"
        private const val KEY_ENGINE = "engine"
        private const val KEY_CUSTOM_NAME = "custom_soundfont_name"

        /** AAudio exists from Android 8.0, but 8.1 fixed its early bugs. */
        val FLUIDSYNTH_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }
}
