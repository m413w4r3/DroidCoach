package dev.atlas.coach.content

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCatalogTest {
    @Test
    fun demoCatalogIsLimitedAndRequestsUseValidatedHttpsUris() {
        val catalog = ContentCatalog.demo()
        assertTrue(catalog.items.size <= 3)
        assertEquals(3, catalog.items.size)
        catalog.items.forEach { item ->
            val request = ContentOpenRequestFactory.build(item)
            assertEquals(ContentOpenRequestFactory.ACTION_VIEW, request.action)
            assertTrue(request.uri.scheme == "https")
            assertEquals(URI(item.url), request.uri)
        }
        assertEquals(catalog.items, ContentCatalog.fromMaps(catalog.toMaps().map { it as Map<String, Any?> }).items)
    }

    @Test
    fun rejectsHttpJavascriptAndUnknownHosts() {
        listOf(
            "http://www.youtube.com/watch?v=demo",
            "javascript:alert(1)",
            "https://example.com/watch?v=demo",
        ).forEach { url ->
            assertFalse(ContentUrlValidator.isValid(url))
            try {
                ContentCatalog.create(listOf(ContentItem("bad", "Bad", url)))
                throw AssertionError("Expected URL rejection for $url")
            } catch (_: IllegalArgumentException) {
                // Expected validation rejection.
            }
        }
        assertTrue(ContentUrlValidator.isValid("https://youtu.be/aqz-KE-bpKQ"))
    }

    @Test
    fun chooserFailureFallsBackToDirectThenReportsMissingApp() {
        val request = ContentOpenRequestFactory.build(ContentCatalog.demo().items.first())
        val attempted = mutableListOf<String>()
        val fallback = FallbackContentOpener(object : ContentLaunchGateway {
            override fun openChooser(request: ContentOpenRequest): Boolean {
                attempted += "chooser"
                return false
            }

            override fun openDirect(request: ContentOpenRequest): Boolean {
                attempted += "direct"
                return true
            }
        })
        assertEquals(ContentOpenStatus.OPENED_DIRECT_FALLBACK, fallback.open(request).status)
        assertEquals(listOf("chooser", "direct"), attempted)

        val noApp = FallbackContentOpener(object : ContentLaunchGateway {
            override fun openChooser(request: ContentOpenRequest) = false
            override fun openDirect(request: ContentOpenRequest) = false
        }).open(request)
        assertEquals(ContentOpenStatus.NO_COMPATIBLE_APP, noApp.status)
        assertTrue(noApp.message.contains("Aucune application"))
    }

    @Test
    fun missingHandlerIsDetectedBeforeChooserIsLaunched() {
        val request = ContentOpenRequestFactory.build(ContentCatalog.demo().items.first())
        var chooserLaunched = false
        var directLaunched = false
        val opener = FallbackContentOpener(object : ContentLaunchGateway {
            override fun hasCompatibleHandler(request: ContentOpenRequest) = false
            override fun openChooser(request: ContentOpenRequest): Boolean {
                chooserLaunched = true
                return true
            }
            override fun openDirect(request: ContentOpenRequest): Boolean {
                directLaunched = true
                return true
            }
        })

        assertEquals(ContentOpenStatus.NO_COMPATIBLE_APP, opener.open(request).status)
        assertFalse(chooserLaunched)
        assertFalse(directLaunched)
    }
}
