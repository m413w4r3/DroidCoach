package dev.atlas.coach.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LauncherAppRulesTest {
    private val apps = listOf(
        LauncherAppModel("Notes", "com.example.notes", "NotesActivity"),
        LauncherAppModel("École", "com.example.school", "SchoolActivity"),
        LauncherAppModel("Mail", "com.example.mail", "MailActivity"),
        LauncherAppModel("École", "com.example.school", "DuplicateActivity"),
    )

    @Test
    fun sortingIsAlphabeticalAndRemovesDuplicatePackages() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            val sorted = LauncherAppRules.sort(apps)
            assertEquals(listOf("com.example.school", "com.example.mail", "com.example.notes"), sorted.map { it.appPackage })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun searchIgnoresAccentsAndMatchesNamesAndPackageNames() {
        assertEquals(listOf("com.example.school"), LauncherAppRules.filter(apps, "ecole").map { it.appPackage })
        assertEquals(listOf("com.example.mail"), LauncherAppRules.filter(apps, "EXAMPLE.MAIL").map { it.appPackage })
        assertEquals(3, LauncherAppRules.filter(apps, " ").size)
    }

    @Test
    fun searchRanksExactThenPrefixThenPartialBeforeAlphabeticalTies() {
        val candidates = listOf(
            LauncherAppModel("Desk Clock", "pkg.partial", null),
            LauncherAppModel("Clockwork", "pkg.prefix", null),
            LauncherAppModel("Clock", "pkg.exact", null),
            LauncherAppModel("Alarm Clock", "pkg.other", null),
        )

        assertEquals(
            listOf("pkg.exact", "pkg.prefix", "pkg.other", "pkg.partial"),
            LauncherAppRules.filter(candidates, "clock").map { it.appPackage },
        )
    }

    @Test
    fun favoritesThenOptionalLocalRecencyRankEqualMatches() {
        val candidates = listOf(
            LauncherAppModel("Clock C", "pkg.c", null),
            LauncherAppModel("Clock A", "pkg.a", null),
            LauncherAppModel("Clock B", "pkg.b", null),
        )

        assertEquals(
            listOf("pkg.c", "pkg.b", "pkg.a"),
            LauncherAppRules.filter(
                candidates,
                "clock",
                pinnedPackages = setOf("pkg.b", "pkg.c"),
                recentPackages = listOf("pkg.c", "pkg.b"),
            ).map { it.appPackage },
        )
        assertEquals(
            listOf("pkg.b", "pkg.c", "pkg.a"),
            LauncherAppRules.filter(candidates, "", pinnedPackages = setOf("pkg.b"), recentPackages = listOf("pkg.c", "pkg.b"))
                .map { it.appPackage },
        )
    }

    @Test
    fun favoritesAreSortedAndToggleWithoutChangingOtherPackages() {
        val favorites = LauncherAppRules.favorites(apps, setOf("com.example.school", "com.example.mail"))
        assertEquals(listOf("com.example.school", "com.example.mail"), favorites.map { it.appPackage })

        val removed = LauncherAppRules.toggleFavorite(setOf("com.example.mail", "com.example.notes"), "com.example.mail")
        assertEquals(setOf("com.example.notes"), removed)
        val added = LauncherAppRules.toggleFavorite(removed, "com.example.school")
        assertTrue("com.example.school" in added)
        assertEquals(removed, LauncherAppRules.toggleFavorite(removed, " "))
    }
}
