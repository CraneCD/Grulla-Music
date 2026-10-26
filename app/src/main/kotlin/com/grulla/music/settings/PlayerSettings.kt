package com.grulla.music.settings

import android.content.Context
import androidx.core.content.edit
import com.grulla.music.core.listen.ChordSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide reading and practice preferences (the per-score ones live in the library). */
data class PlayerPreferences(
    /** Page view: show the next page while the last bar of the current one is playing. */
    val earlyPageTurn: Boolean = false,
    /** Arrow keys turn pages too (most Bluetooth pedals send them); Page Up / Down always do. */
    val arrowKeysTurnPages: Boolean = true,
    /** Follow me: a chord counts only when every note is heard (otherwise any one of them). */
    val chordsNeedAllNotes: Boolean = false,
    val chordSensitivity: ChordSensitivity = ChordSensitivity.NORMAL,
)

class PlayerSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val preferences: StateFlow<PlayerPreferences> = mutable.asStateFlow()

    fun update(change: (PlayerPreferences) -> PlayerPreferences) {
        val next = change(mutable.value)
        prefs.edit {
            putBoolean(KEY_EARLY_TURN, next.earlyPageTurn)
            putBoolean(KEY_ARROW_KEYS, next.arrowKeysTurnPages)
            putBoolean(KEY_ALL_CHORD_NOTES, next.chordsNeedAllNotes)
            putString(KEY_CHORD_SENSITIVITY, next.chordSensitivity.name)
        }
        mutable.value = next
    }

    private fun read(): PlayerPreferences {
        val defaults = PlayerPreferences()
        return PlayerPreferences(
            earlyPageTurn = prefs.getBoolean(KEY_EARLY_TURN, defaults.earlyPageTurn),
            arrowKeysTurnPages = prefs.getBoolean(KEY_ARROW_KEYS, defaults.arrowKeysTurnPages),
            chordsNeedAllNotes = prefs.getBoolean(KEY_ALL_CHORD_NOTES, defaults.chordsNeedAllNotes),
            chordSensitivity = prefs.getString(KEY_CHORD_SENSITIVITY, null)
                ?.let { runCatching { ChordSensitivity.valueOf(it) }.getOrNull() } ?: defaults.chordSensitivity,
        )
    }

    private companion object {
        const val KEY_EARLY_TURN = "early_page_turn"
        const val KEY_ARROW_KEYS = "arrow_keys_turn_pages"
        const val KEY_ALL_CHORD_NOTES = "chords_need_all_notes"
        const val KEY_CHORD_SENSITIVITY = "chord_sensitivity"
    }
}
