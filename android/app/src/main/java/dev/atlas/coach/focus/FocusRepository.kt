package dev.atlas.coach.focus

/** Synchronous API: the S1 module has no coroutine or persistence dependency. */
interface FocusRepository {
    fun start(plan: FocusPlan, directiveId: String? = null): FocusSession
    fun start(durationSeconds: Long, directiveId: String? = null): FocusSession =
        start(FocusPlan.custom(durationSeconds), directiveId)
    fun pause(id: String): FocusSession?
    fun resume(id: String): FocusSession?
    fun finish(id: String): FocusSession?
    fun cancel(id: String): FocusSession?
}

class InMemoryFocusRepository(
    clock: Clock,
    idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) : FocusRepository {
    private val engine = FocusEngine(clock, idFactory)

    override fun start(durationSeconds: Long, directiveId: String?): FocusSession =
        engine.start(durationSeconds, directiveId)

    override fun start(plan: FocusPlan, directiveId: String?): FocusSession = engine.start(plan, directiveId)

    override fun pause(id: String): FocusSession? = engine.pause(id)

    override fun resume(id: String): FocusSession? = engine.resume(id)

    override fun finish(id: String): FocusSession? = engine.finish(id)

    override fun cancel(id: String): FocusSession? = engine.cancel(id)

    fun currentSession(): FocusSession? = engine.currentSession()

    fun snapshot(): FocusSession? = engine.snapshot()

    fun restore(session: FocusSession): FocusSession = engine.restore(session)

    fun restore(values: Map<String, Any?>): FocusSession = engine.restore(values)
}
