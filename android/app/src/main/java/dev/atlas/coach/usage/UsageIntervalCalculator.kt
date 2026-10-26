package dev.atlas.coach.usage

data class UsageIntervalCalculation(
    val intervals: List<UsageInterval>,
    val launchCountsByAppRef: Map<String, Int>,
    val lastUsedAtByAppRef: Map<String, Long>,
    val appRefs: Set<String>,
    val blindMs: Long,
)

/** Pairs only foreground transition timestamps; package names remain transient input values. */
object UsageIntervalCalculator {
    private data class OpenInterval(
        val appRef: String,
        val startUtc: Long,
        val reliability: UsageReliability,
    )

    fun calculate(
        events: List<GlobalUsageEvent>,
        window: UsageWindow,
        observedThroughUtc: Long,
        appRefForPackage: (String) -> String,
        source: String = "UsageStatsManager",
    ): UsageIntervalCalculation {
        require(source.isNotBlank())
        val observedEnd = observedThroughUtc.coerceIn(window.startUtc, window.endUtc)
        val activeByPackage = linkedMapOf<String, OpenInterval>()
        val intervals = mutableListOf<UsageInterval>()
        val launchCounts = mutableMapOf<String, Int>()
        val lastUsedAt = mutableMapOf<String, Long>()
        val appRefs = linkedSetOf<String>()

        val ordered = events.distinctBy(GlobalUsageEvent::eventId)
            .sortedWith(
                compareBy<GlobalUsageEvent> { it.timestampUtc }
                    .thenBy { if (it.type == GlobalUsageEventType.ACTIVITY_PAUSED) 0 else 1 }
                    .thenBy(GlobalUsageEvent::eventId),
            )

        for (event in ordered) {
            if (event.timestampUtc > observedEnd) break
            val appRef = appRefForPackage(event.packageName)
            require(appRef.isNotBlank()) { "App reference must not be blank" }

            if (event.timestampUtc < window.startUtc) {
                when (event.type) {
                    GlobalUsageEventType.ACTIVITY_RESUMED -> activeByPackage.putIfAbsent(
                        event.packageName,
                        OpenInterval(appRef, window.startUtc, UsageReliability.MEDIUM),
                    )
                    GlobalUsageEventType.ACTIVITY_PAUSED -> activeByPackage.remove(event.packageName)
                }
                continue
            }

            appRefs += appRef
            lastUsedAt[appRef] = maxOf(lastUsedAt[appRef] ?: 0L, event.timestampUtc)
            when (event.type) {
                GlobalUsageEventType.ACTIVITY_RESUMED -> {
                    // Duplicate activity-resumed events for the same foreground package are one session.
                    if (event.packageName !in activeByPackage) {
                        activeByPackage[event.packageName] =
                            OpenInterval(appRef, event.timestampUtc, UsageReliability.HIGH)
                        launchCounts[appRef] = (launchCounts[appRef] ?: 0) + 1
                    }
                }

                GlobalUsageEventType.ACTIVITY_PAUSED -> {
                    val open = activeByPackage.remove(event.packageName)
                    if (open != null) addInterval(intervals, open, event.timestampUtc, source, appRefs)
                }
            }
        }

        activeByPackage.values.forEach { open ->
            appRefs += open.appRef
            addInterval(
                intervals = intervals,
                open = open.copy(reliability = UsageReliability.LOW),
                endUtc = observedEnd,
                source = source,
                appRefs = appRefs,
            )
        }

        val blindMs = intervals.asSequence()
            .filter { it.reliability == UsageReliability.LOW }
            .sumOf(UsageInterval::durationMs)
        return UsageIntervalCalculation(
            intervals = intervals.sortedWith(compareBy(UsageInterval::startUtc, UsageInterval::appRef)),
            launchCountsByAppRef = launchCounts.toMap(),
            lastUsedAtByAppRef = lastUsedAt.toMap(),
            appRefs = appRefs.toSet(),
            blindMs = blindMs,
        )
    }

    private fun addInterval(
        intervals: MutableList<UsageInterval>,
        open: OpenInterval,
        endUtc: Long,
        source: String,
        appRefs: MutableSet<String>,
    ) {
        val boundedEnd = endUtc.coerceAtLeast(open.startUtc)
        if (boundedEnd > open.startUtc) {
            intervals += UsageInterval(open.appRef, open.startUtc, boundedEnd, open.reliability, source)
            appRefs += open.appRef
        }
    }
}
