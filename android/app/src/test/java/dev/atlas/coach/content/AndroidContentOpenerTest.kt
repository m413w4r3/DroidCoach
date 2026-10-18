package dev.atlas.coach.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidContentOpenerTest {
    private val request = ContentOpenRequestFactory.build(
        ContentItem("video", "Cours", "https://www.youtube.com/watch?v=aqz-KE-bpKQ"),
    )

    @Test
    fun adapterShowsChooserAndFallsBackToBrowserWhenChooserLaunchFails() {
        val calls = mutableListOf<String>()
        val opener = AndroidContentOpener(object : ContentLaunchGateway {
            override fun hasCompatibleHandler(request: ContentOpenRequest) = true
            override fun openChooser(request: ContentOpenRequest): Boolean {
                calls += "chooser"
                return false
            }
            override fun openDirect(request: ContentOpenRequest): Boolean {
                calls += "https-browser-fallback"
                return true
            }
        })

        assertEquals(ContentOpenStatus.OPENED_DIRECT_FALLBACK, opener.open(request).status)
        assertEquals(listOf("chooser", "https-browser-fallback"), calls)
    }

    @Test
    fun noHandlerOrLaunchFailureReturnsAnExplicitStateWithoutForcingAReader() {
        var launched = false
        val noHandler = AndroidContentOpener(object : ContentLaunchGateway {
            override fun hasCompatibleHandler(request: ContentOpenRequest) = false
            override fun openChooser(request: ContentOpenRequest): Boolean {
                launched = true
                return true
            }
            override fun openDirect(request: ContentOpenRequest): Boolean = true
        })
        assertEquals(ContentOpenStatus.NO_COMPATIBLE_APP, noHandler.open(request).status)
        assertFalse(launched)

        val launchFailure = AndroidContentOpener(object : ContentLaunchGateway {
            override fun openChooser(request: ContentOpenRequest): Boolean = throw SecurityException("blocked")
            override fun openDirect(request: ContentOpenRequest): Boolean = false
        })
        assertEquals(ContentOpenStatus.NO_COMPATIBLE_APP, launchFailure.open(request).status)
        assertTrue(request.uri.toString().startsWith("https://"))
    }
}
