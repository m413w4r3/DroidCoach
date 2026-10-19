package dev.atlas.coach.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small screen history isolated from the controller's domain engines. */
internal class AtlasNavigationState(initialRoute: String = AtlasScreenId.HOME) {
    private var routes by mutableStateOf(listOf(initialRoute))

    val current: String get() = routes.last()
    val canGoBack: Boolean get() = current != AtlasScreenId.HOME

    fun navigate(route: String): String {
        if (route == current) return current
        val existingIndex = routes.indexOf(route)
        routes = if (existingIndex >= 0) routes.take(existingIndex + 1) else routes + route
        return current
    }

    fun selectTab(route: String): String {
        routes = listOf(route)
        return current
    }

    fun replace(route: String): String {
        routes = listOf(route)
        return current
    }

    fun back(): String? {
        if (!canGoBack) return null
        routes = if (routes.size > 1) routes.dropLast(1) else listOf(AtlasScreenId.HOME)
        return current
    }
}

internal fun AtlasNavigationState.openCoachFromLauncher() = selectTab(AtlasScreenId.COACH)
