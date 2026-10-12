package com.pocketdisco.nearby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyHandoffTest {
    @Test
    fun openingRequiresInternalBuildAndAndroidTen() {
        assertFalse(nearbyAudioSupported(false, 36))
        assertFalse(nearbyAudioSupported(true, 28))
        assertTrue(nearbyAudioSupported(true, 29))
    }

    @Test
    fun initialResumeDoesNotStopThePendingHandoff() {
        val handoff = NearbyHandoff()
        handoff.open("room-1")
        assertFalse(handoff.hostReturned())
        handoff.hostPaused()
        assertTrue(handoff.hostReturned())
        assertFalse(handoff.hostReturned())
    }

    @Test
    fun resultAndResumeCannotCloseTheSameSessionTwice() {
        val handoff = NearbyHandoff()
        handoff.open("room-1")
        handoff.hostPaused()
        assertTrue(handoff.close())
        assertFalse(handoff.hostReturned())
        assertFalse(handoff.close())
    }

    @Test
    fun closedHandoffCanOpenAnotherRoom() {
        val handoff = NearbyHandoff()
        handoff.open("room-1")
        handoff.close()
        handoff.open("room-2")
        assertTrue(handoff.close())
    }

    @Test(expected = IllegalStateException::class)
    fun secondOpenCannotReplaceAFirstSession() {
        val handoff = NearbyHandoff()
        handoff.open("room-1")
        handoff.open("room-2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRoomCannotStartAHandoff() {
        NearbyHandoff().open("")
    }
}
