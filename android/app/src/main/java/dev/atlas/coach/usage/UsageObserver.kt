package dev.atlas.coach.usage

import dev.atlas.coach.rules.FrictionPresenter
import dev.atlas.coach.rules.FrictionPresentationResult
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.rules.RuleEvaluatorState
import dev.atlas.coach.rules.SafetyDenylist

/**
 * Consent-gated polling coordinator. Call from a visible-Activity lifecycle loop (10 s interval);
 * a periodic worker may call it later, but WorkManager's 15 min minimum is not real-time blocking.
 */
class UsageObserver(
    private val eventSource: UsageEventSource,
    private val accessStatus: UsageAccessStatus,
    private val consentStore: ObservationConsentStore,
    private val cursorStore: UsageCursorStore,
    private val stateStore: UsageObserverStateStore,
    private val recordStore: ObservationRecordStore,
    private val ruleEvaluator: RuleEvaluator,
    private val policyProvider: () -> LocalPolicy,
    private val frictionPresenter: FrictionPresenter,
    private val platformProtectedPackages: () -> Set<String> = { emptySet() },
    private val transientForegroundPackages: Set<String> = SafetyDenylist.transientForegroundPackages,
) {
    private var lastPollUtc: Long? = null

    /** Accept only a specific purpose and a non-empty package allowlist chosen by the user. */
    @Synchronized
    fun acceptConsent(consent: ObservationConsent, nowUtc: Long) {
        require(consent.isExplicit) { "Observation requires explicit purpose and app selection" }
        val protected = platformProtectedPackages()
        require(consent.allowlistedPackageNames.none { SafetyDenylist.isProtected(it, protected) }) {
            "Safety-exempt packages cannot be observed"
        }
        recordStore.keepOnlyPackages(consent.allowlistedPackageNames)
        cursorStore.saveCursor(UsageCursor(nowUtc)) // Never backfill events from before this consent.
        stateStore.clearSnapshot()
        ruleEvaluator.restore(RuleEvaluatorState())
        consentStore.saveConsent(consent) // Enable collection only after the baseline and purge succeed.
        lastPollUtc = null
    }

    /** Revocation is synchronous: gate first, then purge the cursor, counters and observed history. */
    @Synchronized
    fun revokeConsent() {
        consentStore.saveConsent(ObservationConsent.REVOKED)
        cursorStore.clearCursor()
        stateStore.clearSnapshot()
        recordStore.clearRecords()
        ruleEvaluator.restore(RuleEvaluatorState())
        lastPollUtc = null
    }

    /** Permission loss stops reads and clears the cursor so a later grant cannot replay old events. */
    @Synchronized
    fun poll(nowUtc: Long): UsagePollResult {
        val consent = consentStore.currentConsent()
        if (!consent.isExplicit) return UsagePollResult(UsagePollStatus.DISABLED)
        val cutoff = if (nowUtc < UsageRetention.EVENT_RETENTION_MILLIS) 0L
        else nowUtc - UsageRetention.EVENT_RETENTION_MILLIS
        recordStore.pruneBefore(cutoff)
        if (!accessStatus.isAllowed()) {
            cursorStore.clearCursor()
            stateStore.clearSnapshot()
            ruleEvaluator.restore(RuleEvaluatorState())
            lastPollUtc = null
            return UsagePollResult(UsagePollStatus.PERMISSION_REQUIRED)
        }

        val previousPoll = lastPollUtc
        if (previousPoll != null && nowUtc >= previousPoll &&
            nowUtc - previousPoll < UsageRetention.VISIBLE_POLL_INTERVAL_MILLIS
        ) {
            return UsagePollResult(UsagePollStatus.THROTTLED)
        }
        lastPollUtc = nowUtc

        val cursor = cursorStore.currentCursor()
        if (cursor == null) {
            cursorStore.saveCursor(UsageCursor(nowUtc))
            return UsagePollResult(UsagePollStatus.BASELINE_ESTABLISHED)
        }

        val lookbackStart = if (nowUtc < UsageRetention.MAX_LOOKBACK_MILLIS) 0L
        else nowUtc - UsageRetention.MAX_LOOKBACK_MILLIS
        val boundedCursor = cursor.copy(timestampUtc = maxOf(cursor.timestampUtc, lookbackStart))
        val batch = try {
            eventSource.query(boundedCursor, nowUtc, UsageRetention.MAX_RAW_EVENTS_PER_POLL)
        } catch (_: SecurityException) {
            // Settings may revoke the AppOp between the preflight check and the binder call.
            cursorStore.clearCursor()
            stateStore.clearSnapshot()
            ruleEvaluator.restore(RuleEvaluatorState())
            lastPollUtc = null
            return UsagePollResult(UsagePollStatus.PERMISSION_REQUIRED)
        }
        if (batch.nextCursor.timestampUtc >= cursor.timestampUtc) cursorStore.saveCursor(batch.nextCursor)

        val snapshot = stateStore.currentSnapshot()
        val seen = snapshot.recentSourceEventTimestampsUtc.toMutableMap()
        var foreground = snapshot.foregroundPackage
        var foregroundAt = snapshot.foregroundTimestampUtc
        var foregroundWasTransient = snapshot.foregroundWasTransient
        var stableForeground = snapshot.stableForegroundPackage
        val accepted = mutableListOf<dev.atlas.coach.rules.AppOpenEvent>()
        val interventions = mutableListOf<dev.atlas.coach.rules.Intervention>()
        val dynamicProtected = platformProtectedPackages()
        val basePolicy = policyProvider()
        val config = basePolicy.copy(
            allowlistedPackageNames = consent.allowlistedPackageNames,
            excludedPackageNames = basePolicy.excludedPackageNames + dynamicProtected,
        )

        batch.events.asSequence()
            .filter { it.timestampUtc <= nowUtc }
            .sortedWith(compareBy(UsageSourceEvent::timestampUtc, UsageSourceEvent::eventId))
            .forEach { event ->
                if (event.timestampUtc <= consent.grantedAtUtc!!) return@forEach
                if (seen.containsKey(event.eventId)) return@forEach
                seen[event.eventId] = event.timestampUtc

                val isTransient = event.packageName in transientForegroundPackages
                if (event.packageName == foreground) {
                    foregroundAt = event.timestampUtc
                    return@forEach // Activity/configuration repeats within the same foreground app.
                }

                val isNotificationReturn = !isTransient &&
                    foregroundWasTransient && event.packageName == stableForeground &&
                    foregroundAt != null && event.timestampUtc >= foregroundAt!! &&
                    event.timestampUtc - foregroundAt!! <= UsageRetention.NOTIFICATION_SHADE_RETURN_WINDOW_MILLIS

                foreground = event.packageName
                foregroundAt = event.timestampUtc
                foregroundWasTransient = isTransient
                if (!isTransient) stableForeground = event.packageName

                if (isTransient || isNotificationReturn) return@forEach
                if (event.packageName !in consent.allowlistedPackageNames) return@forEach
                if (SafetyDenylist.isProtected(event.packageName, dynamicProtected)) return@forEach
                if (event.packageName in config.excludedPackageNames || event.packageName in config.vitalPackageNames) {
                    return@forEach
                }

                val appEvent = dev.atlas.coach.rules.AppOpenEvent(event.eventId, event.packageName, event.timestampUtc)
                accepted += appEvent
                recordStore.appendRecord(ObservationRecord(event.eventId, event.packageName, event.timestampUtc))
                val intervention = ruleEvaluator.evaluate(appEvent, config)
                if (intervention != null) {
                    interventions += intervention
                    // A denied/unavailable notification is a visible integration status, never a reason to block.
                    runCatching { frictionPresenter.present(intervention) }
                        .getOrDefault(FrictionPresentationResult.UNAVAILABLE)
                }
            }

        val retainedSeen = seen.entries
            .filter { it.value >= cutoff }
            .sortedByDescending { it.value }
            .take(UsageRetention.MAX_RECENT_SOURCE_EVENT_IDS)
            .associate { it.key to it.value }
        stateStore.saveSnapshot(
            UsageObserverSnapshot(
                foregroundPackage = foreground,
                foregroundTimestampUtc = foregroundAt,
                foregroundWasTransient = foregroundWasTransient,
                stableForegroundPackage = stableForeground,
                recentSourceEventTimestampsUtc = retainedSeen,
            ),
        )

        return UsagePollResult(UsagePollStatus.PROCESSED, accepted, interventions, batch.hasMore)
    }

    @Synchronized
    fun deleteHistory() {
        recordStore.clearRecords()
        ruleEvaluator.restore(RuleEvaluatorState())
    }

    @Synchronized
    fun exportHistoryCsv(): String = buildString {
        appendLine("event_id,package_name,timestamp_utc")
        recordStore.allRecords().sortedBy(ObservationRecord::timestampUtc).forEach { record ->
            append(csv(record.eventId)).append(',')
                .append(csv(record.packageName)).append(',')
                .append(record.timestampUtc).appendLine()
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
