package com.grulla.music.core.score

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

enum class ScoreFormat {
    /** Uncompressed MusicXML (.musicxml / .xml). */
    MUSICXML,

    /** Compressed MusicXML (.mxl): a zip archive with META-INF/container.xml. */
    MXL,
}

/** A score ready to be handed to the renderer: always a UTF-8 MusicXML document. */
data class LoadedScore(
    val musicXml: String,
    val format: ScoreFormat,
    val title: String?,
    val composer: String? = null,
)

class ScoreLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns the raw bytes of a .mxl / .musicxml / .xml file into a MusicXML string.
 *
 * Compressed files are unpacked here (rather than in Verovio) so that the WebView
 * only ever receives one format, and so that malformed archives produce a
 * readable error instead of a blank page.
 */
object MusicXmlLoader {
    /** Upper bound for the uncompressed size of an .mxl, to defend against zip bombs. */
    const val MAX_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun detectFormat(bytes: ByteArray): ScoreFormat =
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)) ScoreFormat.MXL
        else ScoreFormat.MUSICXML

    fun load(bytes: ByteArray): LoadedScore {
        if (bytes.isEmpty()) throw ScoreLoadException("The file is empty.")
        val format = detectFormat(bytes)
        val xml = when (format) {
            ScoreFormat.MXL -> decodeXml(extractRootFile(bytes))
            ScoreFormat.MUSICXML -> decodeXml(bytes)
        }
        if (!looksLikeMusicXml(xml)) {
            throw ScoreLoadException("This file does not look like MusicXML (no <score-partwise> or <score-timewise> root).")
        }
        return LoadedScore(musicXml = xml, format = format, title = extractTitle(xml), composer = extractComposer(xml))
    }

    /** Returns the bytes of the score document referenced by an .mxl archive. */
    internal fun extractRootFile(zipBytes: ByteArray): ByteArray {
        val entries = unzip(zipBytes)
        val rootPath = entries[CONTAINER_PATH]?.let(::rootFileFromContainer)
            ?: entries.keys.firstOrNull { !it.startsWith("META-INF/") && it.endsWith(".xml", ignoreCase = true) }
            ?: entries.keys.firstOrNull { it.endsWith(".musicxml", ignoreCase = true) }
            ?: throw ScoreLoadException("The .mxl archive does not contain a MusicXML document.")
        return entries[rootPath]
            ?: throw ScoreLoadException("The .mxl archive refers to '$rootPath', which is missing.")
    }

    private fun unzip(zipBytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val out = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNCOMPRESSED_BYTES) {
                            throw ScoreLoadException("The .mxl archive is too large to open.")
                        }
                        out.write(buffer, 0, n)
                    }
                    result[entry.name.removePrefix("/")] = out.toByteArray()
                }
            }
        } catch (e: ScoreLoadException) {
            throw e
        } catch (e: Exception) {
            throw ScoreLoadException("The .mxl archive could not be read: ${e.message}", e)
        }
        if (result.isEmpty()) throw ScoreLoadException("The .mxl archive is empty.")
        return result
    }

    private fun rootFileFromContainer(containerXml: ByteArray): String? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val document = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(containerXml))
        } catch (e: Exception) {
            throw ScoreLoadException("META-INF/container.xml is not valid XML.", e)
        }
        val rootFiles = document.getElementsByTagName("rootfile")
        val candidates = (0 until rootFiles.length).map { rootFiles.item(it) as Element }
        // The first rootfile is the main score; later ones may be PDFs or alternate renditions.
        val chosen = candidates.firstOrNull { it.getAttribute("media-type").let { type -> type.isEmpty() || "musicxml" in type } }
            ?: candidates.firstOrNull()
        return chosen?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Not all parsers (e.g. Android's) support every feature; the defaults are acceptable.
        }
    }

    /**
     * Decodes XML bytes honouring a byte order mark or the encoding declared in the
     * XML prolog, strips the BOM and rewrites the declaration to UTF-8 so that the
     * resulting string can be passed to Verovio as-is.
     */
    fun decodeXml(bytes: ByteArray): String {
        val (charset, bomLength) = when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            // UTF-16 without a BOM: '<' followed or preceded by a zero byte.
            bytes.startsWith(0x3C, 0x00) -> Charsets.UTF_16LE to 0
            bytes.startsWith(0x00, 0x3C) -> Charsets.UTF_16BE to 0
            else -> (declaredCharset(bytes) ?: Charsets.UTF_8) to 0
        }
        val text = String(bytes, bomLength, bytes.size - bomLength, charset).removePrefix("﻿")
        val declared = ENCODING_DECL.find(text)?.groups?.get(2) ?: return text
        return text.replaceRange(declared.range, "UTF-8")
    }

    private val ENCODING_DECL = Regex("""^(<\?xml[^>]*?encoding\s*=\s*["'])([^"']+)(["'])""")

    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val name = ENCODING_DECL.find(head)?.groupValues?.get(2) ?: return null
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            null
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

    private fun looksLikeMusicXml(xml: String): Boolean =
        xml.contains("<score-partwise") || xml.contains("<score-timewise")

    private val TITLE_PATTERNS = listOf(
        Regex("""<work-title>\s*([^<]+?)\s*</work-title>"""),
        Regex("""<movement-title>\s*([^<]+?)\s*</movement-title>"""),
    )

    internal fun extractTitle(xml: String): String? =
        TITLE_PATTERNS.firstNotNullOfOrNull { it.find(xml)?.groupValues?.get(1) }?.let(::unescapeXml)

    private val COMPOSER = Regex("""<creator\s+type\s*=\s*["']composer["']\s*>\s*([^<]+?)\s*</creator>""")

    internal fun extractComposer(xml: String): String? = COMPOSER.find(xml)?.groupValues?.get(1)?.let(::unescapeXml)

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
