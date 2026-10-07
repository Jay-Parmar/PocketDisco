package dev.pocketdisco.phase0

sealed interface ScheduleDecision {
    data object Execute : ScheduleDecision

    data class Wait(val delayMs: Long) : ScheduleDecision
}
object SchedulePlanner {
    private const val FINAL_WINDOW_MS = 50L
    private const val EARLY_WAKE_MS = 25L

    fun decide(remainingMs: Long): ScheduleDecision = when {
        remainingMs <= 0L -> ScheduleDecision.Execute
        remainingMs > FINAL_WINDOW_MS -> ScheduleDecision.Wait(remainingMs - EARLY_WAKE_MS)
        else -> ScheduleDecision.Wait(remainingMs)
    }
}
