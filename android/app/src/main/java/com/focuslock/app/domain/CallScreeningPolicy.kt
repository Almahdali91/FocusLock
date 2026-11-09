package com.focuslock.app.domain

/** Fail-open policy: only explicitly enabled, incoming, active-session calls are screened. */
object CallScreeningPolicy {
    fun shouldReject(
        enabled: Boolean,
        incoming: Boolean,
        focusActive: Boolean,
        normalizedNumber: String?,
        isAllowedContact: Boolean
    ): Boolean = enabled && incoming && focusActive &&
        normalizedNumber != null && !isAllowedContact
}
