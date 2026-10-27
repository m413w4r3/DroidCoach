package dev.atlas.coach.calendar.write

import org.json.JSONObject
import java.io.File

/**
 * File-backed durable journal. Each operation is one versioned JSON file. A sibling temp file is
 * flushed with `fsync` before an atomic rename, and the directory index is rebuilt from complete
 * `.json` records on startup. Version 1 stores operation IDs, activity/plan/calendar/event IDs,
 * idempotency key, operation type/state, expected and prior content hashes, and a stable error code;
 * it does not store event titles or account details. This meets the durable journal contract without Android database
 * APIs or a transaction spanning independent operation records; incomplete temp files are ignored.
 */
class FileCalendarWriteJournal(
    private val directory: File,
    private val migration: CalendarWriteRecordMigration =
        CalendarWriteFileStorage.migrationForCurrentVersion(FORMAT_VERSION),
) : CalendarWriteJournal, CalendarWriteJournalSnapshot {
    private val monitor = CalendarWriteFileStorage.lockFor(directory)
    private val operations = linkedMapOf<String, CalendarWriteOperation>()
    private val activityIndex = linkedMapOf<String, MutableList<String>>()
    private val idempotencyIndex = linkedMapOf<String, String>()
    private val quarantinedIds = mutableSetOf<String>()

    init {
        synchronized(monitor) {
            CalendarWriteFileStorage.ensureDirectory(directory)
            reloadFromDisk()
        }
    }

    override fun record(operation: CalendarWriteOperation) = synchronized(monitor) {
        reloadFromDisk()
        require(operation.operationId !in quarantinedIds) { "operation record is quarantined" }
        val byId = operations[operation.operationId]
        require(byId == null || byId == operation) { "operationId already records a different operation" }
        val byKey = operations.values.firstOrNull { it.idempotencyKey == operation.idempotencyKey }
        require(byKey == null || byKey.operationId == operation.operationId) {
            "idempotencyKey already records another operation"
        }
        if (byId == null) {
            write(operation)
            operations[operation.operationId] = operation
            index(operation)
        }
    }

    override fun findByOperation(operationId: String): CalendarWriteOperation? = synchronized(monitor) {
        reloadFromDisk()
        operations[operationId]
    }

    override fun findByActivity(activityId: String): List<CalendarWriteOperation> = synchronized(monitor) {
        reloadFromDisk()
        activityIndex[activityId].orEmpty().mapNotNull(operations::get)
    }

    override fun findByIdempotencyKey(idempotencyKey: String): CalendarWriteOperation? = synchronized(monitor) {
        reloadFromDisk()
        idempotencyIndex[idempotencyKey]?.let(operations::get)
    }

    override fun allOperations(): List<CalendarWriteOperation> = synchronized(monitor) {
        reloadFromDisk()
        operations.values.toList()
    }

    override fun updateState(
        operationId: String,
        state: CalendarWriteOperationState,
        eventId: Long?,
        errorCode: CalendarWriteError?,
    ) = synchronized(monitor) {
        reloadFromDisk()
        val previous = operations[operationId] ?: return@synchronized
        val updated = previous.copy(
            state = state,
            eventId = eventId ?: previous.eventId,
            errorCode = errorCode,
        )
        write(updated)
        operations[operationId] = updated
    }

    private fun write(operation: CalendarWriteOperation) {
        CalendarWriteFileStorage.writeAtomically(
            directory,
            "${operation.operationId}.json",
            operation.toJson().toString(),
        )
    }

    private fun reloadFromDisk() {
        val prior = operations.toMap()
        val loaded = linkedMapOf<String, CalendarWriteOperation>()
        quarantinedIds.clear()
        CalendarWriteFileStorage.jsonRecords(directory).forEach { (name, raw) ->
            val expectedId = name.removeSuffix(".json")
            val parsed = runCatching { raw.toOperation() }.getOrNull()
            val operation = parsed?.takeIf { it.operationId == expectedId }
                ?: prior[expectedId]
                ?: run {
                    quarantinedIds += expectedId
                    return@forEach
                }
            val existing = loaded[operation.operationId]
            require(existing == null || existing == operation) { "duplicate operation record" }
            val keyCollision = loaded.values.firstOrNull { it.idempotencyKey == operation.idempotencyKey }
            require(keyCollision == null || keyCollision.operationId == operation.operationId) {
                "duplicate idempotency key in journal files"
            }
            loaded[operation.operationId] = operation
        }
        operations.clear()
        operations.putAll(loaded)
        activityIndex.clear()
        idempotencyIndex.clear()
        loaded.values.forEach(::index)
    }

    private fun index(operation: CalendarWriteOperation) {
        activityIndex.getOrPut(operation.activityId) { mutableListOf() }.add(operation.operationId)
        idempotencyIndex[operation.idempotencyKey] = operation.operationId
    }

    private fun CalendarWriteOperation.toJson(): JSONObject = JSONObject()
        .put("schema_version", FORMAT_VERSION)
        .put("operation_id", operationId)
        .put("type", type.name)
        .put("activity_id", activityId)
        .put("plan_id", planId)
        .put("calendar_id", calendarId)
        .put("event_id", eventId ?: JSONObject.NULL)
        .put("payload_hash", payloadHash)
        .put("previous_content_hash", previousContentHash ?: JSONObject.NULL)
        .put("idempotency_key", idempotencyKey)
        .put("state", state.name)
        .put("error_code", errorCode?.name ?: JSONObject.NULL)

    private fun String.toOperation(): CalendarWriteOperation {
        val json = CalendarWriteFileStorage.decodeCurrent(this, FORMAT_VERSION, migration)
        return CalendarWriteOperation(
            operationId = json.getString("operation_id"),
            type = CalendarWriteOperationType.valueOf(json.getString("type")),
            activityId = json.getString("activity_id"),
            planId = json.getString("plan_id"),
            calendarId = json.getLong("calendar_id"),
            eventId = json.longOrNull("event_id"),
            payloadHash = json.getString("payload_hash"),
            previousContentHash = json.stringOrNull("previous_content_hash"),
            idempotencyKey = json.getString("idempotency_key"),
            state = CalendarWriteOperationState.valueOf(json.getString("state")),
            errorCode = json.stringOrNull("error_code")?.let(CalendarWriteError::valueOf),
        )
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
