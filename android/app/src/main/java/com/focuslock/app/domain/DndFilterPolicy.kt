package com.focuslock.app.domain

import android.app.NotificationManager

/** Treat every restrictive Android interruption filter as an active DND state. */
object DndFilterPolicy {
    fun isDndActive(interruptionFilter: Int): Boolean = when (interruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        else -> false
    }
}
