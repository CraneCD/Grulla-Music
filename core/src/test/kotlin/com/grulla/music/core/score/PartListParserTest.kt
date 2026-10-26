package com.grulla.music.core.score

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PartListParserTest {
    @Test
    fun `reads part names and staff counts`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
            <score-partwise version="4.0">
              <part-list>
                <part-group type="start" number="1"/>
                <score-part id="P1"><part-name>Violin &amp; Viola</part-name></score-part>
                <score-part id="P2"><part-name>Piano</part-name></score-part>
                <score-part id="P3"><part-name></part-name></score-part>
              </part-list>
              <part id="P1"><measure number="1"><attributes><divisions>1</divisions></attributes></measure></part>
              <part id="P2"><measure number="1"><attributes><staves>2</staves></attributes></measure></part>
              <part id="P3"><measure number="1"/></part>
            </score-partwise>
        """.trimIndent()
        assertEquals(
            listOf(ScorePart("P1", "Violin & Viola", 1), ScorePart("P2", "Piano", 2), ScorePart("P3", "Part 3", 1)),
            PartListParser.parse(xml),
        )
    }

    @Test
    fun `reports malformed XML`() {
        assertFailsWith<ScoreLoadException> { PartListParser.parse("<score-partwise><part-list>") }
    }
}
