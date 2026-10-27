package dev.atlas.coach.calendar.write

/**
 * Durable operation journal boundary. A file-backed implementation must encode only operation IDs,
 * activity/plan/calendar/event IDs, idempotency keys, states, hashes, and fixed error codes. It must
 * commit each record by writing a sibling temporary file, flushing it, then atomically renaming it;
 * serialize operations for the same activity across callers; recovery must ignore incomplete
 * temporary files and preserve the last complete journal.
 */
interface CalendarWriteJournal {
    fun record(operation: CalendarWriteOperation)

    fun findByOperation(operationId: String): CalendarWriteOperation?

    fun findByActivity(activityId: String): List<CalendarWriteOperation>

    fun findByIdempotencyKey(idempotencyKey: String): CalendarWriteOperation?

    fun updateState(
        operationId: String,
        state: CalendarWriteOperationState,
        eventId: Long? = null,
        errorCode: CalendarWriteError? = null,
    )
}

/** Optional capability for journals that can enumerate operations for process restart recovery. */
interface CalendarWriteJournalSnapshot {
    fun allOperations(): List<CalendarWriteOperation>
}

/** Thread-safe in-memory implementation for JVM tests and local demonstrations. */
class InMemoryCalendarWriteJournal : CalendarWriteJournal, CalendarWriteJournalSnapshot {
    private val operations = linkedMapOf<String, CalendarWriteOperation>()

    @Synchronized
    override fun record(operation: CalendarWriteOperation) {
        val byId = operations[operation.operationId]
        require(byId == null || byId == operation) { "operationId already records a different operation" }
        val byKey = operations.values.firstOrNull { it.idempotencyKey == operation.idempotencyKey }
        require(byKey == null || byKey.operationId == operation.operationId) {
            "idempotencyKey already records another operation"
        }
        operations[operation.operationId] = operation
    }

    @Synchronized
    override fun findByOperation(operationId: String): CalendarWriteOperation? = operations[operationId]

    @Synchronized
    override fun findByActivity(activityId: String): List<CalendarWriteOperation> =
        operations.values.filter { it.activityId == activityId }

    @Synchronized
    override fun findByIdempotencyKey(idempotencyKey: String): CalendarWriteOperation? =
        operations.values.firstOrNull { it.idempotencyKey == idempotencyKey }

    @Synchronized
    override fun allOperations(): List<CalendarWriteOperation> = operations.values.toList()

    @Synchronized
    override fun updateState(
        operationId: String,
        state: CalendarWriteOperationState,
        eventId: Long?,
        errorCode: CalendarWriteError?,
    ) {
        val previous = operations[operationId] ?: return
        operations[operationId] = previous.copy(
            state = state,
            eventId = eventId ?: previous.eventId,
            errorCode = errorCode,
        )
    }
}
