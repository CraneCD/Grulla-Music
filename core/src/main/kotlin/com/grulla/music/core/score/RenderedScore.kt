package com.grulla.music.core.score

import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.MidiReader
import com.grulla.music.core.timemap.Timemap
import com.grulla.music.core.timemap.TimemapEntry
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the WebView reports after Verovio has rendered a score (see `score.js`, onScoreLoaded). */
class RenderedScore(
    val pageCount: Int,
    val timemap: Timemap,
    val midi: MidiFile,
    /** URL the score was loaded from, used to discard results for a score that was replaced meanwhile. */
    val source: String?,
    /** MIDI pitch of each note id (for listening mode). */
    val pitches: Map<String, Int> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private class Payload(
            val pageCount: Int = 0,
            val timemap: List<TimemapEntry> = emptyList(),
            val midi: String = "",
            val source: String? = null,
            val pitches: Map<String, Int> = emptyMap(),
        )

        fun parse(payloadJson: String): RenderedScore {
            val payload = json.decodeFromString<Payload>(payloadJson)
            val midi = MidiReader.read(Base64.getDecoder().decode(payload.midi))
            return RenderedScore(payload.pageCount, Timemap(payload.timemap), midi, payload.source, payload.pitches)
        }
    }
}
