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
