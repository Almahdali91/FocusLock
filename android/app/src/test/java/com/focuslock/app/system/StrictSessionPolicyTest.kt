package com.focuslock.app.system

import com.focuslock.app.data.FocusSessionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictSessionPolicyTest {
    private fun session(mode: String = "STRICT", status: String = "ACTIVE") =
        FocusSessionEntity(startTime = 1L, plannedEndTime = 100L, durationMillis = 99L,
            selectedLockMode = mode, sessionStatus = status)

    @Test fun strictActiveManagedSessionEntersLockTask() {
        assertTrue(StrictSessionPolicy.shouldEnterLockTask(session(), true, true))
    }

    @Test fun standardSessionNeverEntersLockTask() {
        assertFalse(StrictSessionPolicy.shouldEnterLockTask(session("STANDARD"), true, true))
    }

    @Test fun breakReleasesLockTask() {
        assertFalse(StrictSessionPolicy.shouldEnterLockTask(session(status = "BREAK_ACTIVE"), true, true))
    }

    @Test fun nonOwnerCannotEnterLockTask() {
        assertFalse(StrictSessionPolicy.shouldEnterLockTask(session(), false, true))
    }

    @Test fun nonAllowlistedPackageCannotEnterLockTask() {
        assertFalse(StrictSessionPolicy.shouldEnterLockTask(session(), true, false))
    }

    @Test fun missingOrFinishedSessionCannotEnterLockTask() {
        assertFalse(StrictSessionPolicy.shouldEnterLockTask(null, true, true))
        assertFalse(StrictSessionPolicy.shouldEnterLockTask(session(status = "COMPLETED"), true, true))
    }
}
