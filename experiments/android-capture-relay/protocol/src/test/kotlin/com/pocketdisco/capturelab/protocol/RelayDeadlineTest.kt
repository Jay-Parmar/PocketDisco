package com.pocketdisco.capturelab.protocol

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDeadlineTest {
    @Test
    fun `write completion during a deadline check does not expire the peer`() {
        val deadline = AtomicLong(200)
        var reads = 0

        assertFalse(deadlineExpired(100) {
            reads++
            deadline.getAndSet(0)
        })

        assertEquals(1, reads)
        assertEquals(0L, deadline.get())
    }

    @Test
    fun `idle and future deadlines never expire`() {
        assertFalse(deadlineExpired(100) { 0 })
        assertFalse(deadlineExpired(100) { 101 })
    }

    @Test
    fun `active deadline expires at its boundary`() {
        assertTrue(deadlineExpired(100) { 100 })
        assertTrue(deadlineExpired(101) { 100 })
    }

    @Test
    fun `deadline checks preserve nano time wraparound`() {
        val deadline = Long.MIN_VALUE + 10
        assertFalse(deadlineExpired(Long.MAX_VALUE - 10) { deadline })
        assertTrue(deadlineExpired(Long.MIN_VALUE + 11) { deadline })
    }
}
