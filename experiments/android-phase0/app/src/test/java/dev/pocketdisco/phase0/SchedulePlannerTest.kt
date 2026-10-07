package dev.pocketdisco.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SchedulePlannerTest {
    @Test
    fun wakesEarlyOutsideFinalWindow() {
        assertEquals(ScheduleDecision.Wait(975), SchedulePlanner.decide(1_000))
    }

    @Test
    fun waitsExactRemainderInsideFinalWindow() {
        assertEquals(ScheduleDecision.Wait(50), SchedulePlanner.decide(50))
        assertEquals(ScheduleDecision.Wait(1), SchedulePlanner.decide(1))
    }

    @Test
    fun executesAtOrAfterTarget() {
        assertSame(ScheduleDecision.Execute, SchedulePlanner.decide(0))
        assertSame(ScheduleDecision.Execute, SchedulePlanner.decide(-1))
    }
}
