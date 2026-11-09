package com.focuslock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakPolicyTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test fun noBreakForThirtyMinutes() = assertEquals(0, BreakPolicy.breakCount(30 * minute))
    @Test fun noBreakForExactlyTwoHours() = assertEquals(0, BreakPolicy.breakCount(2 * hour))
    @Test fun oneBreakForThreeHours() = assertEquals(1, BreakPolicy.breakCount(3 * hour))
    @Test fun twoBreaksForFourHours() = assertEquals(2, BreakPolicy.breakCount(4 * hour))
    @Test fun threeBreaksForFiveHours() = assertEquals(3, BreakPolicy.breakCount(5 * hour))
    @Test fun sixBreaksForEightHours() = assertEquals(6, BreakPolicy.breakCount(8 * hour))
    @Test fun disabledBreaksAlwaysZero() = assertEquals(0, BreakPolicy.breakCount(8 * hour, false))
    @Test fun breakBoundariesAreTwoHoursThenHourly() {
        assertEquals(2 * hour, BreakPolicy.breakAtElapsedMillis(0))
        assertEquals(3 * hour, BreakPolicy.breakAtElapsedMillis(1))
        assertEquals(4 * hour, BreakPolicy.breakAtElapsedMillis(2))
    }
    @Test fun breakStartsOnlyAtEarnedBoundaryAndBeforeFinalFocusCompletion() {
        assertFalse(BreakPolicy.shouldStartBreak(3 * hour, 2 * hour - 1, true))
        assertTrue(BreakPolicy.shouldStartBreak(3 * hour, 2 * hour, true))
        assertFalse(BreakPolicy.shouldStartBreak(3 * hour, 3 * hour, true))
        assertFalse(BreakPolicy.shouldStartBreak(4 * hour, 3 * hour, false))
    }
}