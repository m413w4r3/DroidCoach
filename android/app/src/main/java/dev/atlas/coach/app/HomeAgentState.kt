package dev.atlas.coach.app

import dev.atlas.coach.focus.FocusStatus

/** UI contract for HOME. Lot D can provide this state without coupling HOME to its transport layer. */
data class HomeAgentState(
    val directive: HomeDirective,
    val focusCard: HomeAgentCard? = null,
    val contentCard: HomeAgentCard? = null,
    val agentStatus: HomeAgentStatus = HomeAgentStatus.LOCAL_ONLY,
) {
    companion object {
        /** Deterministic offline adapter used until another provider is explicitly connected. */
        fun localDefault(controller: AtlasController): HomeAgentState {
            val focus = controller.focusSession?.takeIf {
                it.status == FocusStatus.RUNNING || it.status == FocusStatus.PAUSED
            }?.let { session ->
                HomeAgentCard(
                    title = "Focus en cours",
                    body = "${if (session.status == FocusStatus.PAUSED) "En pause" else "En cours"} · " +
                        "${session.remainingSeconds / 60} min restantes dans cette phase.",
                    actionLabel = "Ouvrir Focus",
                    action = HomeCardAction.OPEN_FOCUS,
                )
            }
            val content = controller.contentItems.firstOrNull()?.takeIf {
                controller.capabilities[AtlasController.CAP_CONTENT] == true
            }?.let { item ->
                HomeAgentCard(
                    title = "Suggestion de contenu",
                    body = item.title,
                    actionLabel = "Ouvrir la bibliothèque",
                    action = HomeCardAction.OPEN_LIBRARY,
                )
            }
            val agent = controller.agentHomeState
            val connected = controller.agentStatus == dev.atlas.coach.sync.agent.coach.AgentStatus.CONNECTED
            val status = if (connected) HomeAgentStatus.CONNECTED else HomeAgentStatus.LOCAL_ONLY
            val agentDirective = agent.directive?.let { d ->
                HomeDirective(
                    title = d.title,
                    body = d.body,
                    primaryAction = when (d.primaryAction) {
                        "focus.start" -> HomePrimaryAction.START_FOCUS
                        "open_coach" -> HomePrimaryAction.OPEN_COACH
                        else -> HomePrimaryAction.NONE
                    },
                )
            }
            val proposalCard = agent.focusProposal
                ?.takeIf { it.state == dev.atlas.coach.sync.agent.AgentFocusCardState.PROPOSED || it.state == dev.atlas.coach.sync.agent.AgentFocusCardState.PLANNED }
                ?.let { p ->
                    HomeAgentCard(
                        title = "Session proposée",
                        body = "${p.label} · ${p.focusMinutes} min",
                        actionLabel = "Voir la session",
                        action = HomeCardAction.OPEN_FOCUS,
                    )
                }
            val agentContent = agent.contentRecommendation?.let { c ->
                HomeAgentCard(
                    title = "Suggestion du coach",
                    body = c.title,
                    actionLabel = "Ouvrir la bibliothèque",
                    action = HomeCardAction.OPEN_LIBRARY,
                )
            }
            if (agentDirective != null) {
                return HomeAgentState(
                    directive = agentDirective,
                    focusCard = focus ?: proposalCard,
                    contentCard = agentContent ?: content,
                    agentStatus = status,
                )
            }
            return HomeAgentState(
                directive = HomeDirective(
                    title = controller.directive.title,
                    body = controller.directive.reason,
                    primaryAction = if (controller.capabilities[AtlasController.CAP_FOCUS] == true) {
                        HomePrimaryAction.START_FOCUS
                    } else {
                        HomePrimaryAction.OPEN_COACH
                    },
                ),
                focusCard = focus,
                contentCard = content,
                agentStatus = HomeAgentStatus.LOCAL_ONLY,
            )
        }
    }
}

data class HomeDirective(
    val title: String,
    val body: String,
    val primaryAction: HomePrimaryAction,
)

data class HomeAgentCard(
    val title: String,
    val body: String,
    val actionLabel: String,
    val action: HomeCardAction,
)

enum class HomePrimaryAction { START_FOCUS, OPEN_COACH, NONE }
enum class HomeCardAction { OPEN_FOCUS, OPEN_COACH, OPEN_LIBRARY }
enum class HomeAgentStatus { CONNECTED, UNAVAILABLE, LOCAL_ONLY }

/** Limits the displayed title by Unicode code point, leaving the original for its detail dialog. */
fun truncateHomeDirectiveTitle(title: String, maxCodePoints: Int = 60): String {
    require(maxCodePoints > 0) { "maxCodePoints must be positive" }
    val count = title.codePointCount(0, title.length)
    if (count <= maxCodePoints) return title
    val end = title.offsetByCodePoints(0, maxCodePoints - 1)
    return title.substring(0, end).trimEnd() + "…"
}
