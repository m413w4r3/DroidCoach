package dev.atlas.coach.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLibraryTest {
    @Test
    fun demoSearchesAreDistinctFromSpecificVideoLinksAndLegacyMapsMigrate() {
        val catalog = ContentCatalog.demo()
        assertTrue(catalog.items.all { it.kind == ContentKind.SEARCH })
        val restoredLegacy = ContentItem.fromMap(
            mapOf(
                "id" to "old-search",
                "title" to "Recherche existante",
                "url" to "https://www.youtube.com/results?search_query=lecture",
            ),
        )
        assertEquals(ContentKind.SEARCH, restoredLegacy.kind)
        assertTrue(ContentUrlValidator.isSpecificVideoLink("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertFalse(ContentUrlValidator.isSpecificVideoLink("https://www.youtube.com/results?search_query=lecture"))
    }

    @Test
    fun manualLibraryAddsPinsPersistsAndDeletesSpecificLinks() {
        val catalog = ContentCatalog.demo()
        val added = catalog.addManualLink("Cours choisi", "https://youtu.be/aqz-KE-bpKQ", id = "chosen")
        assertEquals(ContentKind.VIDEO_LINK, added.kind)
        assertTrue(catalog.setPinned(added.id, true))
        assertEquals("chosen", catalog.items.first().id)

        val restored = ContentCatalog.fromMaps(catalog.toMaps().map { it as Map<String, Any?> })
        assertEquals(catalog.items, restored.items)
        assertTrue(restored.remove("chosen"))
        assertFalse(restored.remove("chosen"))
        assertThrows(IllegalArgumentException::class.java) {
            catalog.addManualLink("Recherche", "https://www.youtube.com/results?search_query=lecture")
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.addManualLink("Hors liste", "https://example.com/video")
        }
    }

    @Test
    fun onlySuccessfulOpensIncrementTheLocalPerItemCounterAndItCanBeDisabled() {
        val catalog = ContentCatalog.create(emptyList())
        val item = catalog.addManualLink("Vidéo", "https://www.youtube.com/watch?v=aqz-KE-bpKQ", id = "video")
        val success = object : ContentOpener {
            override fun open(request: ContentOpenRequest) =
                ContentOpenResult(ContentOpenStatus.OPENED_CHOOSER, "chooser")
        }
        val failure = object : ContentOpener {
            override fun open(request: ContentOpenRequest) =
                ContentOpenResult(ContentOpenStatus.NO_COMPATIBLE_APP, "missing")
        }

        assertEquals(ContentOpenStatus.OPENED_CHOOSER, catalog.open(item.id, success).status)
        assertEquals(1, catalog.find(item.id)?.openCount)
        assertEquals(ContentOpenStatus.NO_COMPATIBLE_APP, catalog.open(item.id, failure).status)
        assertEquals(1, catalog.find(item.id)?.openCount)
        assertTrue(catalog.setOpenCountingEnabled(item.id, false))
        catalog.open(item.id, success)
        assertEquals(1, catalog.find(item.id)?.openCount)
        assertFalse(catalog.find(item.id)!!.openCountingEnabled)
        assertTrue(catalog.toMaps().all { it.keys.none { key -> key.contains("watch", ignoreCase = true) || key.contains("history", ignoreCase = true) } })
    }
}
