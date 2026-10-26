package com.grulla.music.core.score

import com.grulla.music.core.Fixtures
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderedScoreTest {
    @Test
    fun `parses the payload sent by the web view`() {
        val midi = Base64.getEncoder().encodeToString(Fixtures.bytes("${Fixtures.CHRONO}.mid"))
        val timemap = Fixtures.text("${Fixtures.CHRONO}.timemap.json")
        val json = """{"pageCount":2,"timemap":$timemap,"midi":"$midi","source":"https://x/score/3","pitches":{"n1":60,"n2":64},"extra":1}"""
        val rendered = RenderedScore.parse(json)
        assertEquals(2, rendered.pageCount)
        assertEquals(549, rendered.timemap.entries.size)
        assertEquals(3, rendered.midi.tracks.size)
        assertEquals("https://x/score/3", rendered.source)
        assertEquals(mapOf("n1" to 60, "n2" to 64), rendered.pitches)
    }
}
