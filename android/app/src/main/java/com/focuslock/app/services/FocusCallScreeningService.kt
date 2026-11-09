package com.focuslock.app.services

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.domain.CallScreeningPolicy
import com.focuslock.app.domain.PhoneNumberNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional user-selected call-screening role. Fail-open on any uncertainty:
 * calls are never rejected unless the user enabled screening, a focus session
 * is active, and the caller is not in the explicitly allowed local contacts list.
 */
class FocusCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val mode = prefs.getString(KEY_MODE, MODE_FOCUS_ONLY) ?: MODE_FOCUS_ONLY
        val exemptAllowed = prefs.getBoolean(KEY_EXEMPT_ALLOWED, true)
        Log.i(TAG, "onScreenCall received: direction=${callDetails.callDirection}, enabled=$enabled, mode=$mode")
        if (!enabled || callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            Log.i(TAG, "Allowing call: screening disabled or call is not incoming")
            allow(callDetails)
            return
        }

        scope.launch {
            // Any database, normalization, or timeout uncertainty must fail open.
            // Without this guard an exception could escape the coroutine before
            // respondToCall(), leaving Android without an explicit allow response.
            val shouldReject = runCatching {
                withTimeoutOrNull(3_000L) {
                    val rawNumber = callDetails.handle?.schemeSpecificPart
                    val number = rawNumber?.let(PhoneNumberNormalizer::normalize)
                    if (number == null) {
                        Log.w(TAG, "Allowing call: caller number is unavailable or could not be normalized")
                        return@withTimeoutOrNull false
                    }
                    val database = FocusDatabase.get(applicationContext)
                    val activeSession = database.sessions().activeSession()
                    val focusActive = mode == MODE_ALWAYS ||
                        activeSession?.sessionStatus == "ACTIVE"
                    val allowed = FocusRepository(database).isAllowedContactNumber(number)
                    val reject = CallScreeningPolicy.shouldReject(
                        enabled = true,
                        incoming = true,
                        focusActive = focusActive,
                        normalizedNumber = number,
                        isAllowedContact = allowed && exemptAllowed
                    )
                    Log.i(
                        TAG,
                        "Call decision inputs: focusActive=$focusActive, sessionStatus=${activeSession?.sessionStatus}, " +
                            "numberPresent=true, allowedContact=$allowed, exemptAllowed=$exemptAllowed, reject=$reject"
                    )
                    reject
                } ?: false
            }.onFailure {
                Log.w(TAG, "Call screening lookup failed; allowing the call.", it)
            }.getOrDefault(false)

            Log.i(TAG, "Screening decision: reject=$shouldReject, mode=$mode, exemptAllowed=$exemptAllowed")
            if (shouldReject) {
                respondToCall(callDetails, CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build())
            } else {
                allow(callDetails)
            }
        }
    }

    private fun allow(details: Call.Details) {
        respondToCall(details, CallResponse.Builder().setDisallowCall(false).build())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "call_screening_preferences"
        const val KEY_ENABLED = "enabled"
        const val KEY_MODE = "mode"
        const val KEY_EXEMPT_ALLOWED = "exempt_allowed"
        const val MODE_FOCUS_ONLY = "focus_only"
        const val MODE_ALWAYS = "always"
        private const val TAG = "FocusLockCallScreen"
    }
}
