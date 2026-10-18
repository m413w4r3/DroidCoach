package dev.atlas.coach.rules

import dev.atlas.coach.focus.Clock
import java.util.UUID

/** Generates synthetic app-open events only; it never queries or launches installed apps. */
class AppEventSimulator(
    private val clock: Clock,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun generate(
        packageName: String,
        count: Int,
        intervalSeconds: Long,
        firstTimestampUtc: Long = clock.nowMillis(),
    ): List<AppOpenEvent> {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(count >= 0) { "count must be non-negative" }
        require(intervalSeconds >= 0L) { "intervalSeconds must be non-negative" }
        return List(count) { index ->
            val offset = index.toLong() * intervalSeconds * 1_000L
            val timestamp = if (offset > 0L && firstTimestampUtc > Long.MAX_VALUE - offset) {
                Long.MAX_VALUE
            } else {
                firstTimestampUtc + offset
            }
            AppOpenEvent(
                eventId = eventIdFactory(),
                packageName = packageName,
                timestampUtc = timestamp,
            )
        }
    }
}
