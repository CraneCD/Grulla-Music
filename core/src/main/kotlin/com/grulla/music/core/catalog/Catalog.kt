package com.grulla.music.core.catalog

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * One score of the online catalogue (built by tools/catalog/build_catalog.py; short
 * field names keep the downloaded index small).
 */
@Serializable
data class CatalogScore(
    val id: String,
    @SerialName("t") val title: String,
    @SerialName("c") val composer: String,
    /** Instrument families, e.g. ["Voice", "Piano"]. */
    @SerialName("i") val instruments: List<String> = emptyList(),
    @SerialName("p") val parts: Int = 1,
    @SerialName("b") val bars: Int = 0,
    @SerialName("s") val seconds: Int? = null,
    /** 1 easy, 2 medium, 3 hard (from note density), when known. */
    @SerialName("d") val difficulty: Int? = null,
    /** Rating on the source site (0-5), when known. */
    @SerialName("r") val rating: Double? = null,
    /** Key into [Catalog.sources]. */
    val src: String,
    /** "CC0" or "PD" (public domain mark). */
    val lic: String,
    /** Path of the .mxl file, relative to the catalogue's base URL. */
    @SerialName("f") val file: String,
    @SerialName("kb") val sizeKb: Int = 0,
    /** The score's page at its source. */
    val link: String? = null,
    /** Collection the score belongs to, e.g. a song cycle. */
    val set: String? = null,
    val lang: String? = null,
) {
    /** Lowercase, accent-free text that searches look in. */
    @Transient
    internal val haystack: String = normalize(listOfNotNull(title, composer, set, instruments.joinToString(" ")).joinToString(" "))

    @Transient
    internal val titleKey: String = normalize(title)

    @Transient
    internal val composerKey: String = normalize(composer)
}

@Serializable
data class CatalogSource(val name: String, val url: String, val license: String)

/** What to narrow a search to; null fields do not filter. */
data class CatalogFilter(
    val instrument: String? = null,
    val difficulty: Int? = null,
    val source: String? = null,
)

/** The online score catalogue. Immutable. */
@Serializable
data class Catalog(
    val version: Int = 1,
    val sources: Map<String, CatalogSource> = emptyMap(),
    val scores: List<CatalogScore> = emptyList(),
) {
    /** Instrument families by how many scores use them, most first. */
    val instruments: List<String> by lazy {
        scores.flatMap { it.instruments }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * Scores matching every word of [query] (in title, composer, collection or
     * instruments; case and accents ignored), best first: title matches before
     * composer matches, then by rating.
     */
    fun search(query: String, filter: CatalogFilter = CatalogFilter(), limit: Int = 200): List<CatalogScore> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        val matches = scores.asSequence()
            .filter { filter.instrument == null || filter.instrument in it.instruments }
            .filter { filter.difficulty == null || it.difficulty == filter.difficulty }
            .filter { filter.source == null || it.src == filter.source }
            .filter { score -> words.all { it in score.haystack } }
            .toList()
        if (words.isEmpty()) return matches.sortedByDescending { it.rating ?: 0.0 }.take(limit)
        val phrase = words.joinToString(" ")
        return matches.sortedWith(
            compareByDescending<CatalogScore> { relevance(it, words, phrase) }
                .thenByDescending { it.rating ?: 0.0 }
                .thenBy { it.title },
        ).take(limit)
    }

    private fun relevance(score: CatalogScore, words: List<String>, phrase: String): Int {
        var points = 0
        if (score.titleKey.startsWith(phrase)) points += 8 else if (phrase in score.titleKey) points += 5
        if (phrase in score.composerKey) points += 4
        points += words.count { word -> score.titleKey.split(' ').any { it.startsWith(word) } }
        return points
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun decode(text: String): Catalog = json.decodeFromString(serializer(), text)
    }
}

internal fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
