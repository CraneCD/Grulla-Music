package com.grulla.music.core.score

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** A part of the score (an instrument or voice) and how many staves it uses. */
data class ScorePart(val id: String, val name: String, val staves: Int)

/** Reads the parts of a MusicXML document (partwise or timewise). */
object PartListParser {
    fun parse(musicXml: String): List<ScorePart> {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
            try {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (_: Exception) {
                // Not supported by every parser; the entity resolver below covers it.
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            // Never fetch the MusicXML DTD from the network.
            reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(musicXml)))
        } catch (e: Exception) {
            throw ScoreLoadException("Could not read the parts of this score: ${e.message}", e)
        }
        return handler.result()
    }

    private class Handler : DefaultHandler() {
        private val names = LinkedHashMap<String, String>()
        private val staves = HashMap<String, Int>()
        private var scorePartId: String? = null
        private var partId: String? = null
        private val text = StringBuilder()
        private var capture = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "score-part" -> scorePartId = attributes.getValue("id")?.also { names.putIfAbsent(it, "") }
                "part" -> partId = attributes.getValue("id")
                "part-name", "staves" -> {
                    text.setLength(0)
                    capture = true
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "score-part" -> scorePartId = null
                "part" -> partId = null
                "part-name" -> {
                    capture = false
                    val id = scorePartId
                    if (id != null && names[id].isNullOrEmpty()) names[id] = text.toString().trim()
                }
                "staves" -> {
                    capture = false
                    val id = partId
                    val count = text.toString().trim().toIntOrNull()
                    if (id != null && count != null && count > 0) staves[id] = maxOf(staves[id] ?: 1, count)
                }
            }
        }

        fun result(): List<ScorePart> = names.entries.mapIndexed { index, (id, name) ->
            ScorePart(id, name.ifBlank { "Part ${index + 1}" }, staves[id] ?: 1)
        }
    }
}
