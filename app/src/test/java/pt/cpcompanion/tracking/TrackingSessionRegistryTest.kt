package pt.cpcompanion.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSessionRegistryTest {
    @Test
    fun duplicateLaunchClaimIsRejectedAtomically() {
        TrackingSessionRegistry.markStopped()

        assertTrue(TrackingSessionRegistry.tryClaimLaunch("ticket-a", now = 1_000L))
        assertFalse(TrackingSessionRegistry.tryClaimLaunch("ticket-a", now = 1_001L))
        TrackingSessionRegistry.markStopped("ticket-a")
    }

    @Test
    fun runningSessionIsProcessLocalAndTicketSpecific() {
        TrackingSessionRegistry.markStopped()
        TrackingSessionRegistry.markStarted("ticket-a")

        assertTrue(TrackingSessionRegistry.isRunning("ticket-a"))
        assertFalse(TrackingSessionRegistry.isRunning("ticket-b"))

        TrackingSessionRegistry.markStopped("ticket-a")
        assertFalse(TrackingSessionRegistry.isRunning("ticket-a"))
    }

    @Test
    fun differentTicketsCanRunConcurrently() {
        TrackingSessionRegistry.markStopped()

        assertTrue(TrackingSessionRegistry.tryClaimLaunch("ticket-a", now = 1_000L))
        assertTrue(TrackingSessionRegistry.tryClaimLaunch("ticket-b", now = 1_001L))
        assertTrue(TrackingSessionRegistry.isRunning("ticket-a", now = 1_002L))
        assertTrue(TrackingSessionRegistry.isRunning("ticket-b", now = 1_002L))

        TrackingSessionRegistry.markStopped()
    }
}
