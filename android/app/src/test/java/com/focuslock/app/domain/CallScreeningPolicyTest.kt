package com.focuslock.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreeningPolicyTest {
    @Test fun disabledScreeningNeverRejects() {
        assertFalse(CallScreeningPolicy.shouldReject(false, true, true, "+15551234567", false))
    }
    @Test fun outgoingCallsAreNeverRejected() {
        assertFalse(CallScreeningPolicy.shouldReject(true, false, true, "+15551234567", false))
    }
    @Test fun callsOutsideFocusAreNeverRejected() {
        assertFalse(CallScreeningPolicy.shouldReject(true, true, false, "+15551234567", false))
    }
    @Test fun allowedContactsAlwaysPass() {
        assertFalse(CallScreeningPolicy.shouldReject(true, true, true, "+15551234567", true))
    }
    @Test fun unknownIncomingNumberDuringFocusIsRejected() {
        assertTrue(CallScreeningPolicy.shouldReject(true, true, true, "+15551234567", false))
    }
    @Test fun unknownOrUnparseableNumberFailsOpen() {
        assertFalse(CallScreeningPolicy.shouldReject(true, true, true, null, false))
    }
}
