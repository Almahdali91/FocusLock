package com.focuslock.app.system

import com.focuslock.app.data.FocusSessionEntity

object StrictSessionPolicy {
    fun shouldEnterLockTask(session: FocusSessionEntity?, deviceOwner: Boolean, lockTaskPermitted: Boolean): Boolean =
        session != null &&
        session.selectedLockMode == "STRICT" &&
        session.sessionStatus == "ACTIVE" &&
        deviceOwner &&
        lockTaskPermitted
}
