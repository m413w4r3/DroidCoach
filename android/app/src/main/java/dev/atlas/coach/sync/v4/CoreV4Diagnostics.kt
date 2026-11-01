package dev.atlas.coach.sync.v4

import android.util.Log
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Process-local, opt-in trace for diagnosing Core V4 sync without retaining request data. */
internal object CoreV4Diagnostics {
    const val MAX_EVENTS = 256
    private const val LOG_TAG = "ATLAS_V4_DIAG"
    private const val MAX_DURATION_MS = 86_400_000L

    enum class WorkKind { IMMEDIATE, PERIODIC, UNKNOWN }

    enum class Stage {
        PASS_START,
        PASS_END,
        ERROR,
        USER_PERSISTED,
        WORK_SUBMITTED,
        BEFORE_DISPATCH,
        PAYLOAD_READY,
        UI_RESULT,
        CONSENT_PREPARE,
        CONSENT_DISPATCH,
        CONSENT_RESPONSE,
        ACK_ATTEMPT,
        ACK_RESULT,
        PLAN_PREPARE,
        PLAN_DISPATCH,
        PLAN_RESPONSE,
        PLAN_ACK_ATTEMPT,
        PLAN_ACK_RESULT,
        STATUS_PREPARE,
        STATUS_DISPATCH,
        STATUS_RESPONSE,
        STATUS_ACK_ATTEMPT,
        STATUS_ACK_RESULT,
        QUEUED,
        DISPATCHED,
        SOCKET_SEND_ACCEPTED,
        SOCKET_SEND_REJECTED,
        RESPONSE_RECEIVED,
        CORRELATED,
        TIMEOUT,
        EXPIRED,
        CANCELLED,
        QUEUE_FULL,
        OFFLINE,
        SESSION_CLOSED,
        SESSION_REPLACED,
    }

    enum class State {
        STARTED,
        PREPARING,
        PREPARED,
        DISPATCHED,
        SENT,
        RESPONSE,
        CORRELATED,
        ACKNOWLEDGED,
        QUEUED,
        FAILED,
        SKIPPED,
        COMPLETE,
        CLOSED,
        UNKNOWN,
    }

    enum class Result {
        NONE,
        STARTED,
        SUCCESS,
        RETRY,
        FAILURE,
        APPLIED,
        SUPERSEDED,
        MISSING,
        ACCEPTED,
        REJECTED,
        CORRELATED,
        TIMEOUT,
        EXPIRED,
        CANCELLED,
        QUEUE_FULL,
        OFFLINE,
        PROTOCOL,
        SESSION_REPLACED,
        LOCAL_CLOSE,
        CLOSE_1000,
        CLOSE_1001,
        CLOSE_1006,
        CLOSE_4001,
        CLOSE_4003,
        CLOSE_OTHER,
    }

    enum class AckResult { NONE, APPLIED, SUPERSEDED, MISSING }

    data class ExecutionContext(
        val workerOrdinal: Long,
        val executionGeneration: Long,
        val workKind: WorkKind,
        val runAttempt: Int,
    )

    data class Event(
        val seq: Long,
        val elapsedMs: Long,
        val workerOrdinal: Long,
        val executionGeneration: Long,
        val workKind: WorkKind,
        val runAttempt: Int,
        val category: CoreV4Category?,
        val consentVersion: Int?,
        val state: State,
        val stage: Stage,
        val result: Result,
        val ackResult: AckResult,
        val durationMs: Long,
        val sessionGeneration: Long,
        val correlationTag: String?,
    )

    @Volatile
    var enabled: Boolean = false
        private set

    private val workerOrdinals = AtomicLong()
    private val executionGenerations = AtomicLong()
    private val eventSequence = AtomicLong()
    private val originNanos = System.nanoTime()
    private val executionContext = ThreadLocal<ExecutionContext?>()
    private val lock = Any()
    private val events = ArrayDeque<Event>(MAX_EVENTS)

    fun enableForDiagnostics() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun snapshot(): List<Event> = synchronized(lock) {
        Collections.unmodifiableList(ArrayList(events))
    }

    fun drain(): List<Event> = synchronized(lock) {
        val copy = Collections.unmodifiableList(ArrayList(events))
        events.clear()
        copy
    }

    fun clear() {
        synchronized(lock) { events.clear() }
    }

    fun currentExecutionContext(): ExecutionContext? = executionContext.get()

    fun beginExecution(workKind: WorkKind, runAttempt: Int): ExecutionContext {
        val context = ExecutionContext(
            workerOrdinal = workerOrdinals.incrementAndGet(),
            executionGeneration = executionGenerations.incrementAndGet(),
            workKind = workKind,
            runAttempt = runAttempt.coerceIn(0, 1_000),
        )
        executionContext.set(context)
        record(stage = Stage.PASS_START, state = State.STARTED, result = Result.STARTED, context = context)
        return context
    }

    fun endExecution(context: ExecutionContext, result: Result) {
        record(stage = Stage.PASS_END, state = State.COMPLETE, result = result, context = context)
        if (executionContext.get() == context) executionContext.remove()
    }

    fun record(
        stage: Stage,
        state: State = State.UNKNOWN,
        result: Result = Result.NONE,
        ackResult: AckResult = AckResult.NONE,
        category: CoreV4Category? = null,
        consentVersion: Int? = null,
        durationMs: Long = 0L,
        sessionGeneration: Long = 0L,
        requestId: String? = null,
        context: ExecutionContext? = executionContext.get(),
    ) {
        if (!enabled) return
        val event = synchronized(lock) {
            if (!enabled) return
            val current = context
            val item = Event(
                seq = eventSequence.incrementAndGet(),
                elapsedMs = ((System.nanoTime() - originNanos) / 1_000_000L).coerceAtLeast(0L),
                workerOrdinal = current?.workerOrdinal ?: 0L,
                executionGeneration = current?.executionGeneration ?: 0L,
                workKind = current?.workKind ?: WorkKind.UNKNOWN,
                runAttempt = current?.runAttempt ?: 0,
                category = category,
                consentVersion = consentVersion?.coerceIn(0, Int.MAX_VALUE),
                state = state,
                stage = stage,
                result = result,
                ackResult = ackResult,
                durationMs = durationMs.coerceIn(0L, MAX_DURATION_MS),
                sessionGeneration = sessionGeneration.coerceAtLeast(0L),
                correlationTag = requestId?.let(::correlationTag),
            )
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast(item)
            item
        }
        runCatching { Log.i(LOG_TAG, formatLogLine(event)) }
    }

    /** Returns the exact redacted line used by Android logging, for privacy assertions in JVM tests. */
    internal fun formatLogLine(event: Event): String = event.toLogLine()

    /** Only canonical request IDs are accepted; the raw value is never retained or logged. */
    fun correlationTag(requestId: String): String? {
        if (!requestId.matches(REQUEST_ID_PATTERN)) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun categoryForOperation(operation: String, payload: org.json.JSONObject? = null): CoreV4Category? = when (operation) {
        "v4.consent.state.upsert" -> payload?.optString("category")?.let { wire ->
            CoreV4Category.entries.firstOrNull { it.wireValue == wire }
        }
        "v4.plan.snapshot.upsert" -> CoreV4Category.DAY_PLAN
        "v4.activity-status.events.append" -> CoreV4Category.ACTIVITY_STATUS
        "v4.usage.summary.upsert" -> CoreV4Category.USAGE_SUMMARY
        "v4.goal.snapshot.upsert" -> CoreV4Category.GOAL_PROGRESS
        "v4.training.snapshot.upsert" -> CoreV4Category.TRAINING_PLAN
        else -> null
    }

    private fun Event.toLogLine(): String = buildString(240) {
        append("seq=").append(seq)
        append(" elapsedMs=").append(elapsedMs)
        append(" workerOrdinal=").append(workerOrdinal)
        append(" executionGeneration=").append(executionGeneration)
        append(" workKind=").append(workKind)
        append(" runAttempt=").append(runAttempt)
        append(" category=").append(category ?: "NONE")
        append(" consentVersion=").append(consentVersion ?: 0)
        append(" state=").append(state)
        append(" stage=").append(stage)
        append(" result=").append(result)
        append(" ackResult=").append(ackResult)
        append(" durationMs=").append(durationMs)
        append(" sessionGeneration=").append(sessionGeneration)
        append(" correlationTag=").append(correlationTag ?: "NONE")
    }

    private val REQUEST_ID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
}
