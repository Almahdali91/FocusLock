package com.focuslock.app.domain

/** Legacy snapshot abstraction retained for unit tests; production recovery uses SessionClockStore. */
data class SessionClockSnapshot(
    val wallDeadlineMillis: Long,
    val monotonicDeadlineMillis: Long
) {
    fun remainingAt(elapsedRealtimeMillis: Long): Long =
        (monotonicDeadlineMillis - elapsedRealtimeMillis).coerceAtLeast(0L)

    companion object {
        fun restore(
            wallDeadlineMillis: Long,
            wallNowMillis: Long,
            elapsedRealtimeMillis: Long
        ): SessionClockSnapshot {
            val remaining = (wallDeadlineMillis - wallNowMillis).coerceAtLeast(0L)
            return SessionClockSnapshot(
                wallDeadlineMillis = wallDeadlineMillis,
                monotonicDeadlineMillis = elapsedRealtimeMillis + remaining
            )
        }
    }
}

data class SessionMonotonicAnchor(
    val startedAtElapsedRealtime: Long,
    val bootCount: Int
)

/** Pure time arithmetic shared by focus and break clocks. */
object SessionClockMath {
    fun isStoredAnchorUsable(
        anchorBootCount: Int?,
        currentBootCount: Int,
        anchorElapsedRealtimeMillis: Long?,
        currentElapsedRealtimeMillis: Long
    ): Boolean =
        anchorElapsedRealtimeMillis != null &&
            anchorBootCount == currentBootCount &&
            currentElapsedRealtimeMillis >= anchorElapsedRealtimeMillis

    fun restoreAnchor(
        startedAtWallTime: Long,
        wallNowMillis: Long,
        elapsedRealtimeMillis: Long,
        bootCount: Int,
        maximumElapsedMillis: Long? = null
    ): SessionMonotonicAnchor {
        val wallElapsed = (wallNowMillis - startedAtWallTime).coerceAtLeast(0L)
        val boundedElapsed = maximumElapsedMillis?.let { wallElapsed.coerceAtMost(it.coerceAtLeast(0L)) }
            ?: wallElapsed
        return SessionMonotonicAnchor(
            startedAtElapsedRealtime = elapsedRealtimeMillis - boundedElapsed,
            bootCount = bootCount
        )
    }

    fun elapsedSince(
        startedAtWallTime: Long,
        startedAtElapsedRealtime: Long?,
        anchorBootCount: Int?,
        currentBootCount: Int,
        wallNowMillis: Long,
        elapsedRealtimeMillis: Long
    ): Long {
        if (startedAtElapsedRealtime != null &&
            anchorBootCount == currentBootCount &&
            elapsedRealtimeMillis >= startedAtElapsedRealtime
        ) {
            return elapsedRealtimeMillis - startedAtElapsedRealtime
        }
        return (wallNowMillis - startedAtWallTime).coerceAtLeast(0L)
    }
}

/** States used by the legacy, test-only FocusSessionStateMachine; not the production session model. */
enum class FocusSessionState {
    IDLE, PREPARING, ACTIVE, BREAK_ACTIVE, EMERGENCY_ACCESS,
    COMPLETED, INTERRUPTED, RECOVERY_REQUIRED
}

/** Legacy pure state-transition model retained for unit tests; production transitions are enforced by repository/service status checks. */
object FocusSessionStateMachine {
    private val transitions = mapOf(
        FocusSessionState.IDLE to setOf(FocusSessionState.PREPARING),
        FocusSessionState.PREPARING to setOf(FocusSessionState.ACTIVE, FocusSessionState.IDLE, FocusSessionState.INTERRUPTED),
        FocusSessionState.ACTIVE to setOf(FocusSessionState.BREAK_ACTIVE, FocusSessionState.EMERGENCY_ACCESS, FocusSessionState.COMPLETED, FocusSessionState.INTERRUPTED, FocusSessionState.RECOVERY_REQUIRED),
        FocusSessionState.BREAK_ACTIVE to setOf(FocusSessionState.ACTIVE, FocusSessionState.EMERGENCY_ACCESS, FocusSessionState.INTERRUPTED, FocusSessionState.RECOVERY_REQUIRED),
        FocusSessionState.EMERGENCY_ACCESS to setOf(FocusSessionState.ACTIVE, FocusSessionState.BREAK_ACTIVE, FocusSessionState.INTERRUPTED, FocusSessionState.RECOVERY_REQUIRED),
        FocusSessionState.RECOVERY_REQUIRED to setOf(FocusSessionState.ACTIVE, FocusSessionState.INTERRUPTED, FocusSessionState.COMPLETED),
        FocusSessionState.COMPLETED to emptySet(),
        FocusSessionState.INTERRUPTED to emptySet()
    )

    fun canTransition(from: FocusSessionState, to: FocusSessionState): Boolean =
        to in transitions.getValue(from)

    fun requireTransition(from: FocusSessionState, to: FocusSessionState) {
        require(canTransition(from, to)) { "Invalid focus session transition: $from -> $to" }
    }
}
