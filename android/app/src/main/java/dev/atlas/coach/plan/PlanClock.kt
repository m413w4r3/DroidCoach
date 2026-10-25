package dev.atlas.coach.plan

fun interface PlanClock {
    fun nowUtcMillis(): Long
}

class FixedPlanClock(private val fixedUtcMillis: Long) : PlanClock {
    override fun nowUtcMillis(): Long = fixedUtcMillis
}
