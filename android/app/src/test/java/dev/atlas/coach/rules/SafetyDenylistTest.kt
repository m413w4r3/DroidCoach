package dev.atlas.coach.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SafetyDenylistTest {
    @Test
    fun coreSystemPackagesAndAtlasCannotBeAllowlistedIntoAnIntervention() {
        val evaluator = RuleEvaluator(ZoneId.of("UTC"))
        for (packageName in listOf("dev.atlas.coach", "com.android.settings", "com.android.systemui", "com.android.phone")) {
            val policy = LocalPolicy(openThreshold = 1, allowlistedPackageNames = setOf(packageName))
            assertNull(evaluator.evaluate(AppOpenEvent("id:$packageName", packageName, 1L), policy))
        }
        assertTrue(SafetyDenylist.isProtected("com.android.settings"))
    }

    @Test
    fun protectedPackagesCannotBeRemovedButPlatformCanExtendTheList() {
        val dynamic = setOf("user.home.launcher", "user.accessibility.service")
        assertFalse("dev.atlas.coach" in SafetyDenylist.selectablePackages(setOf("dev.atlas.coach", "video.app"), dynamic))
        assertFalse("user.home.launcher" in SafetyDenylist.selectablePackages(setOf("user.home.launcher", "video.app"), dynamic))
        assertTrue("video.app" in SafetyDenylist.selectablePackages(setOf("user.home.launcher", "video.app"), dynamic))
    }
}
