/*
 * Adapted from Olauncher@f8e8d6497c457734c7a3171d36374157678e1f33:
 * app/src/main/java/app/olauncher/data/AppModel.kt, GPL-3.0.
 * The profile and shortcut variants are excluded; ATLAS uses text labels and package pins only.
 */
package dev.atlas.coach.launcher

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class LauncherAppModel(
    val appLabel: String,
    val appPackage: String,
    val activityClassName: String?,
) : Comparable<LauncherAppModel> {
    override fun compareTo(other: LauncherAppModel): Int = LauncherAppRules.compare(this, other)
}

/** Pure list rules shared by the Android package adapter and JVM tests. */
object LauncherAppRules {
    fun sort(apps: Iterable<LauncherAppModel>): List<LauncherAppModel> =
        apps.distinctBy(LauncherAppModel::appPackage).sortedWith(::compare)

    fun filter(
        apps: Iterable<LauncherAppModel>,
        query: String,
        pinnedPackages: Set<String> = emptySet(),
        recentPackages: List<String> = emptyList(),
    ): List<LauncherAppModel> {
        val normalizedQuery = normalize(query)
        val recentRank = recentPackages.withIndex().associate { it.value to it.index }
        return apps.distinctBy(LauncherAppModel::appPackage)
            .filter { app ->
                normalizedQuery.isEmpty() || matchRank(app, normalizedQuery) >= 0
            }
            .sortedWith { left, right ->
                val byMatch = matchRank(left, normalizedQuery).compareTo(matchRank(right, normalizedQuery))
                if (byMatch != 0) return@sortedWith byMatch

                val byFavorite = (left.appPackage !in pinnedPackages).compareTo(right.appPackage !in pinnedPackages)
                if (byFavorite != 0) return@sortedWith byFavorite

                val byRecent = (recentRank[left.appPackage] ?: Int.MAX_VALUE)
                    .compareTo(recentRank[right.appPackage] ?: Int.MAX_VALUE)
                if (byRecent != 0) return@sortedWith byRecent
                compare(left, right)
            }
    }

    fun favorites(apps: Iterable<LauncherAppModel>, pinnedPackages: Set<String>): List<LauncherAppModel> =
        sort(apps).filter { it.appPackage in pinnedPackages }

    fun toggleFavorite(pinnedPackages: Set<String>, packageName: String): Set<String> {
        if (packageName.isBlank()) return pinnedPackages
        return if (packageName in pinnedPackages) pinnedPackages - packageName else pinnedPackages + packageName
    }

    internal fun compare(left: LauncherAppModel, right: LauncherAppModel): Int {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val byLabel = collator.compare(left.appLabel, right.appLabel)
        return if (byLabel != 0) byLabel else left.appPackage.compareTo(right.appPackage)
    }

    private fun matchRank(app: LauncherAppModel, query: String): Int {
        if (query.isEmpty()) return 0
        val label = normalize(app.appLabel)
        val packageName = normalize(app.appPackage)
        return when {
            label == query || packageName == query -> 0
            label.startsWith(query) || packageName.startsWith(query) -> 1
            label.contains(query) || packageName.contains(query) -> 2
            else -> -1
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
}
