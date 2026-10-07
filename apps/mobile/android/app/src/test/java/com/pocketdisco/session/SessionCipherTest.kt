package com.pocketdisco.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class SessionCipherTest {
    private fun cipher() = SessionCipher(
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
    )

    @Test
    fun roundTrip() {
        val cipher = cipher()
        val value = "{\"refresh_token\":\"test-only\"}"
        val encrypted = cipher.encrypt(value)
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("test-only"))
        assertEquals(value, cipher.decrypt(encrypted))
        assertFalse(encrypted.contentEquals(cipher.encrypt(value)))
    }

    @Test
    fun tamperingIsRejected() {
        val cipher = cipher()
        val encrypted = cipher.encrypt("test-only")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(encrypted) }
    }

    @Test
    fun anotherKeyCannotReadSession() {
        val encrypted = cipher().encrypt("test-only")
        assertThrows(AEADBadTagException::class.java) { cipher().decrypt(encrypted) }
    }

    @Test
    fun boundsAreEnforced() {
        val cipher = cipher()
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt("a".repeat(16_385)) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(byteArrayOf(1, 2)) }
    }
}
