package com.grulla.music.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {
    private val catalog = Catalog.decode(
        """
        {"version":1,
         "sources":{"pdmx":{"name":"PDMX","url":"https://zenodo.org","license":"CC0"}},
         "future_field": true,
         "scores":[
          {"id":"a","t":"Für Elise","c":"Ludwig van Beethoven","i":["Piano"],"p":1,"b":103,"d":2,"r":4.8,"src":"pdmx","lic":"PD","f":"scores/pdmx/01/a.mxl","kb":12},
          {"id":"b","t":"Ode to Joy","c":"Ludwig van Beethoven","i":["Violin","Piano"],"p":2,"b":24,"d":1,"r":4.2,"src":"pdmx","lic":"CC0","f":"scores/pdmx/02/b.mxl"},
          {"id":"c","t":"Gnossienne No. 1","c":"Erik Satie","i":["Piano"],"b":40,"d":2,"src":"lieder","lic":"CC0","f":"scores/lieder/03/c.mxl","new":1},
          {"id":"d","t":"Ständchen","c":"Franz Schubert","i":["Voice","Piano"],"b":60,"src":"lieder","lic":"CC0","f":"scores/lieder/04/d.mxl","set":"Schwanengesang"},
          {"id":"e","t":"Elise's Waltz","c":"Traditional","i":["Guitar"],"b":32,"d":1,"r":3.0,"src":"pdmx","lic":"PD","f":"scores/pdmx/05/e.mxl"}
         ]}
        """.trimIndent(),
    )

    @Test
    fun `search ignores case and accents and needs every word`() {
        assertEquals(listOf("a"), catalog.search("fur elise").map { it.id })
        assertEquals(listOf("d"), catalog.search("STANDCHEN schubert").map { it.id })
        assertEquals(listOf("d"), catalog.search("schwanengesang").map { it.id })
        assertEquals(setOf("a", "b"), catalog.search("beethoven").map { it.id }.toSet())
        assertTrue(catalog.search("bach").isEmpty())
    }

    @Test
    fun `title matches come first, then rating`() {
        // "Elise's Waltz" starts with the query, "Für Elise" only contains it.
        assertEquals(listOf("e", "a"), catalog.search("elise").map { it.id })
        // Same relevance: the better rated first.
        assertEquals(listOf("a", "b"), catalog.search("beethoven").map { it.id })
        // A title that starts with the query beats one that only contains it.
        assertEquals(listOf("b"), catalog.search("ode to").map { it.id })
        // Without a query: best rated first.
        assertEquals(listOf("a", "b", "e"), catalog.search("").take(3).map { it.id })
    }

    @Test
    fun `filters by instrument, difficulty and source`() {
        assertEquals(listOf("b"), catalog.search("", CatalogFilter(instrument = "Violin")).map { it.id })
        assertEquals(setOf("b", "e"), catalog.search("", CatalogFilter(difficulty = 1)).map { it.id }.toSet())
        assertEquals(setOf("c", "d"), catalog.search("", CatalogFilter(source = "lieder")).map { it.id }.toSet())
        assertEquals("Piano", catalog.instruments.first())
        assertEquals(setOf("Piano", "Violin", "Voice", "Guitar"), catalog.instruments.toSet())
        assertEquals("PDMX", catalog.sources["pdmx"]?.name)
    }
}
