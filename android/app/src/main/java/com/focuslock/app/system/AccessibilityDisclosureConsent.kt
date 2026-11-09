package com.focuslock.app.system

import android.content.Context
import androidx.core.content.edit

/**
 * Records the user's affirmative in-app disclosure acceptance before the
 * Standard Protection Accessibility service is allowed to observe app transitions.
 */
object AccessibilityDisclosureConsent {
    private const val PREFS = "accessibility_disclosure"
    private const val KEY_ACCEPTED = "standard_protection_disclosure_accepted"

    fun hasAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ACCEPTED, true)
        }
    }
}
