package com.grulla.music.core.score

import com.grulla.music.core.Fixtures
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicXmlLoaderTest {
    private val minimalScore = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0"><work><work-title>Minuet &amp; Trio</work-title></work>
        <part-list/></score-partwise>
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun container(path: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container><rootfiles>
          <rootfile full-path="$path" media-type="application/vnd.recordare.musicxml+xml"/>
        </rootfiles></container>
    """.trimIndent()

    @Test
    fun `loads the compressed fixture`() {
        val score = MusicXmlLoader.load(Fixtures.bytes("${Fixtures.CHRONO}.mxl"))
        assertEquals(ScoreFormat.MXL, score.format)
        assertTrue(score.musicXml.contains("<score-partwise"))
        assertTrue(score.musicXml.contains("</score-partwise>"))
    }

    @Test
    fun `loads uncompressed MusicXML and extracts the title`() {
        val score = MusicXmlLoader.load(minimalScore.toByteArray())
        assertEquals(ScoreFormat.MUSICXML, score.format)
        assertEquals("Minuet & Trio", score.title)
    }

    @Test
    fun `follows the rootfile declared in container xml`() {
        val bytes = zip(
            "META-INF/container.xml" to container("scores/main.musicxml"),
            "decoy.xml" to "<not-a-score/>",
            "scores/main.musicxml" to minimalScore,
        )
        assertEquals("Minuet & Trio", MusicXmlLoader.load(bytes).title)
    }

    @Test
    fun `falls back to the first xml file when container xml is missing`() {
        val bytes = zip("score.xml" to minimalScore)
        assertEquals(ScoreFormat.MXL, MusicXmlLoader.load(bytes).format)
    }

    @Test
    fun `reports a rootfile that is missing from the archive`() {
        val bytes = zip("META-INF/container.xml" to container("missing.xml"))
        val error = assertFailsWith<ScoreLoadException> { MusicXmlLoader.load(bytes) }
        assertTrue(error.message!!.contains("missing.xml"))
    }

    @Test
    fun `rejects files that are not MusicXML`() {
        assertFailsWith<ScoreLoadException> { MusicXmlLoader.load("<html><body/></html>".toByteArray()) }
        assertFailsWith<ScoreLoadException> { MusicXmlLoader.load(ByteArray(0)) }
        assertFailsWith<ScoreLoadException> { MusicXmlLoader.load(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)) }
    }

    @Test
    fun `decodes UTF-16 with BOM and rewrites the declaration to UTF-8`() {
        val utf16 = minimalScore.replace("UTF-8", "UTF-16")
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16.toByteArray(Charsets.UTF_16LE)
        val xml = MusicXmlLoader.decodeXml(bytes)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), xml.take(60))
        assertFalse(xml.startsWith("﻿"))
        assertTrue(xml.contains("Minuet &amp; Trio"))
    }

    @Test
    fun `honours a declared single-byte encoding`() {
        val latin1 = "<?xml version='1.0' encoding='ISO-8859-1'?><score-partwise><work><work-title>Canción</work-title></work></score-partwise>"
        val score = MusicXmlLoader.load(latin1.toByteArray(Charsets.ISO_8859_1))
        assertEquals("Canción", score.title)
        assertTrue(score.musicXml.startsWith("<?xml version='1.0' encoding='UTF-8'?>"))
    }

    @Test
    fun `strips a UTF-8 BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + minimalScore.toByteArray()
        assertTrue(MusicXmlLoader.decodeXml(bytes).startsWith("<?xml"))
    }
}
