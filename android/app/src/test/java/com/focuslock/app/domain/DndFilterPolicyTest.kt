package com.focuslock.app.domain

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DndFilterPolicyTest {
    @Test
    fun allRestrictiveInterruptionFiltersAreActive() {
        assertTrue(DndFilterPolicy.isDndActive(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(DndFilterPolicy.isDndActive(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(DndFilterPolicy.isDndActive(NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    @Test
    fun normalAndUnknownInterruptionFiltersAreNotReportedAsActive() {
        assertFalse(DndFilterPolicy.isDndActive(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(DndFilterPolicy.isDndActive(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }
}
