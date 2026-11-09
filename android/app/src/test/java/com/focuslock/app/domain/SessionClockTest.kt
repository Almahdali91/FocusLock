package com.focuslock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClockTest {
    @Test
    fun restoredDeadlineUsesWallClockOnlyToRebuildMonotonicDeadline() {
        val snapshot = SessionClockSnapshot.restore(
            wallDeadlineMillis = 20_000L,
            wallNowMillis = 12_000L,
            elapsedRealtimeMillis = 90_000L
        )
        assertEquals(98_000L, snapshot.monotonicDeadlineMillis)
        assertEquals(5_000L, snapshot.remainingAt(93_000L))
        assertEquals(0L, snapshot.remainingAt(99_000L))
    }

    @Test
    fun expiredSessionRestoresWithZeroRemaining() {
        val snapshot = SessionClockSnapshot.restore(5_000L, 8_000L, 40_000L)
        assertEquals(40_000L, snapshot.monotonicDeadlineMillis)
        assertEquals(0L, snapshot.remainingAt(40_000L))
    }

    @Test
    fun monotonicCountdownIsNotAffectedByWallClockChanges() {
        val snapshot = SessionClockSnapshot.restore(100_000L, 50_000L, 10_000L)
        assertEquals(30_000L, snapshot.remainingAt(30_000L))
    }

    @Test
    fun anchorUsesElapsedRealtimeDuringSameBootEvenIfWallClockMoves() {
        val elapsed = SessionClockMath.elapsedSince(
            startedAtWallTime = 1_000_000L,
            startedAtElapsedRealtime = 50_000L,
            anchorBootCount = 12,
            currentBootCount = 12,
            wallNowMillis = 500_000L,
            elapsedRealtimeMillis = 80_000L
        )
        assertEquals(30_000L, elapsed)
    }

    @Test
    fun anchorRestoresFromWallClockAfterDeviceReboot() {
        val elapsed = SessionClockMath.elapsedSince(
            startedAtWallTime = 1_000_000L,
            startedAtElapsedRealtime = 50_000L,
            anchorBootCount = 11,
            currentBootCount = 12,
            wallNowMillis = 1_090_000L,
            elapsedRealtimeMillis = 2_000L
        )
        assertEquals(90_000L, elapsed)
    }

    @Test
    fun restoredBreakAnchorCanBeCappedAtItsPlannedDuration() {
        val anchor = SessionClockMath.restoreAnchor(
            startedAtWallTime = 1_000L,
            wallNowMillis = 20_000L,
            elapsedRealtimeMillis = 5_000L,
            bootCount = 3,
            maximumElapsedMillis = 600_000L
        )
        assertEquals(3, anchor.bootCount)
        assertEquals(-14_000L, anchor.startedAtElapsedRealtime)
    }

    @Test
    fun storedMonotonicAnchorIsRejectedAfterElapsedClockResetEvenIfBootCountIsUnavailable() {
        assertTrue(SessionClockMath.isStoredAnchorUsable(0, 0, 50_000L, 80_000L))
        assertFalse(SessionClockMath.isStoredAnchorUsable(0, 0, 50_000L, 2_000L))
        assertFalse(SessionClockMath.isStoredAnchorUsable(11, 12, 50_000L, 80_000L))
        assertFalse(SessionClockMath.isStoredAnchorUsable(0, 0, null, 80_000L))
    }

    @Test
    fun stateMachineAllowsOnlyDeclaredTransitions() {
        assertTrue(FocusSessionStateMachine.canTransition(FocusSessionState.IDLE, FocusSessionState.PREPARING))
        assertTrue(FocusSessionStateMachine.canTransition(FocusSessionState.ACTIVE, FocusSessionState.COMPLETED))
        assertTrue(FocusSessionStateMachine.canTransition(FocusSessionState.BREAK_ACTIVE, FocusSessionState.ACTIVE))
        assertFalse(FocusSessionStateMachine.canTransition(FocusSessionState.COMPLETED, FocusSessionState.ACTIVE))
        assertFalse(FocusSessionStateMachine.canTransition(FocusSessionState.IDLE, FocusSessionState.COMPLETED))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTransitionIsRejected() {
        FocusSessionStateMachine.requireTransition(FocusSessionState.COMPLETED, FocusSessionState.ACTIVE)
    }
}
