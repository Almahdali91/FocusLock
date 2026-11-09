package com.focuslock.app.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.focuslock.app.MainActivity
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.data.SettingsStore
import com.focuslock.app.domain.StandardProtectionRedirectPolicy
import com.focuslock.app.system.AccessibilityDisclosureConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/**
 * Best-effort selected-package protection.
 *
 * Keep the last *external foreground package* separate from FocusLock's own
 * overlay window. Accessibility overlays are attributed to this app; treating
 * that self-generated event as a foreground-app change used to remove the
 * blocker, expose the blocked app again, and immediately add the blocker back.
 */
class StandardProtectionAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { FocusRepository(FocusDatabase.get(applicationContext)) }
    private val settings by lazy { SettingsStore(applicationContext) }
    private val homePackage by lazy {
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }

    // Updated only by external app/window events. FocusLock's own overlay events
    // must never overwrite this package while the blocker is visible.
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var blockedPackage: String? = null
    @Volatile private var returningToApp: Boolean = false
    private var overlay: LinearLayout? = null
    private var checkGeneration = 0
    private var monitorJob: Job? = null
    private var safetyNetJob: Job? = null
    private var redirectRecoveryJob: Job? = null
    @Volatile private var latestPreferences: com.focuslock.app.data.UserPreferences? = null
    @Volatile private var latestSessionActive: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!AccessibilityDisclosureConsent.hasAccepted(this)) {
            // Do not observe foreground-app events unless the user first accepted
            // the in-app disclosure. This also covers manual activation in Settings.
            disableSelf()
            return
        }
        monitorJob?.cancel()
        monitorJob = scope.launch {
            combine(settings.preferences, repository.activeSessionStatus) { prefs, session ->
                prefs to (session?.sessionStatus == "ACTIVE")
            }.collect { (prefs, active) ->
                latestPreferences = prefs
                val wasActive = latestSessionActive
                latestSessionActive = active
                if (!active) {
                    safetyNetJob?.cancel()
                    safetyNetJob = null
                    withContext(Dispatchers.Main) {
                        redirectRecoveryJob?.cancel()
                        redirectRecoveryJob = null
                        returningToApp = false
                        blockedPackage = null
                        removeBlockOverlay()
                    }
                }
                if (active) {
                    val packageName = foregroundPackage
                    if (packageName != null) reconcile(packageName, prefs, active)
                }
                // The one-second safety-net exists only while a focus session is active.
                if (active && !wasActive) {
                    safetyNetJob?.cancel()
                    safetyNetJob = scope.launch {
                        while (isActive && latestSessionActive) {
                            val packageName = foregroundPackage
                            val currentPrefs = latestPreferences
                            if (packageName != null && currentPrefs != null) {
                                reconcile(packageName, currentPrefs, true)
                            }
                            delay(RECONCILE_INTERVAL_MILLIS)
                        }
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AccessibilityDisclosureConsent.hasAccepted(this)) {
            disableSelf()
            return
        }
        if (event == null || event.eventType !in WINDOW_EVENTS) return
        val packageName = event.packageName?.toString() ?: return

        // Only the real MainActivity window-state event completes a redirect.
        // FocusLock's accessibility overlay and its child views also generate events
        // under our package; treating those as completion caused the redirect latch
        // to reset at the wrong time and made protection inconsistent.
        if (packageName == applicationContext.packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.className?.toString() == MainActivity::class.java.name
            ) {
                redirectRecoveryJob?.cancel()
                redirectRecoveryJob = null
                returningToApp = false
                foregroundPackage = packageName
                blockedPackage = null
                checkGeneration++
                scope.launch(Dispatchers.Main) { removeBlockOverlay() }
            }
            return
        }
        // Ignore touch, focus and content-change events while a redirect is pending.
        if (returningToApp) return
        // A package is considered newly opened only on its actual window transition.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Ignore transient launcher recents windows while the blocker is visible.
        if (overlay != null && packageName == homePackage &&
            event.className?.toString() == android.widget.ListView::class.java.name) return

        foregroundPackage = packageName
        val generation = ++checkGeneration
        val prefs = latestPreferences ?: return
        val active = latestSessionActive
        scope.launch {
            val shouldBlock = active && packageName in prefs.blockedPackages &&
                packageName != applicationContext.packageName
            withContext(Dispatchers.Main) {
                if (generation != checkGeneration || returningToApp) return@withContext
                if (shouldBlock) {
                    // Redirect once per blocked-app entry instead of repeatedly
                    // hiding/showing an overlay on touch/content accessibility events.
                    if (blockedPackage != packageName) {
                        // Cover the blocked app immediately. Launching MainActivity here
                        // caused a brief redirect followed by the target app resurfacing.
                        blockedPackage = packageName
                        returningToApp = false
                        checkGeneration++
                        showBlockOverlay(prefs.language == "ar", packageName)
                    }
                } else {
                    blockedPackage = null
                    removeBlockOverlay()
                }
            }
        }
    }

    private suspend fun reconcile(
        packageName: String,
        prefs: com.focuslock.app.data.UserPreferences,
        sessionActive: Boolean
    ) {
        if (returningToApp) return
        val shouldBlock = sessionActive && packageName in prefs.blockedPackages &&
            packageName != applicationContext.packageName
        withContext(Dispatchers.Main) {
            if (returningToApp) return@withContext
            if (shouldBlock) {
                if (blockedPackage != packageName) {
                    // Keep the blocker attached to the blocked app instead of
                    // briefly launching MainActivity and exposing the app again.
                    blockedPackage = packageName
                    returningToApp = false
                    checkGeneration++
                    showBlockOverlay(prefs.language == "ar", packageName)
                }
            } else if (overlay != null) {
                blockedPackage = null
                removeBlockOverlay()
            }
        }
    }

    private fun showBlockOverlay(arabic: Boolean, packageName: String) {
        if (overlay != null) {
            blockedPackage = packageName
            return
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 56, 48, 56)
            setBackgroundColor(Color.rgb(7, 22, 45))
            isClickable = true
            isFocusable = false
            contentDescription = if (arabic) "حظر FocusLock" else "FocusLock app block"
        }
        val title = TextView(this).apply {
            text = if (arabic) "هذا التطبيق محجوب أثناء التركيز" else "This app is blocked during focus"
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val message = TextView(this).apply {
            text = if (arabic) "يمكنك العودة إلى FocusLock لمتابعة جلستك." else "Return to FocusLock to view your focus session."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        val button = Button(this).apply {
            text = if (arabic) "العودة إلى FocusLock" else "Return to FocusLock"
            setOnClickListener {
                val redirectTarget = blockedPackage ?: foregroundPackage
                returningToApp = true
                checkGeneration++
                removeBlockOverlay()
                if (redirectTarget != null) blockedPackage = redirectTarget
                val intent = android.content.Intent(this@StandardProtectionAccessibilityService, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                runCatching { startActivity(intent) }
                    .onSuccess { redirectTarget?.let(::armRedirectRecovery) }
                    .onFailure {
                        redirectRecoveryJob?.cancel()
                        returningToApp = false
                        blockedPackage = null
                        android.util.Log.e(TAG, "Unable to return to FocusLock", it)
                    }
            }
        }
        panel.addView(title)
        panel.addView(message)
        panel.addView(button)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        runCatching {
            windowManager.addView(panel, params)
            overlay = panel
            blockedPackage = packageName
        }.onFailure {
            overlay = null
            blockedPackage = null
            android.util.Log.e(TAG, "Unable to attach selected-app block overlay", it)
        }
    }

    /**
     * A redirect must not leave the service latched forever if Android omits or
     * delays MainActivity's TYPE_WINDOW_STATE_CHANGED event (for example when
     * SINGLE_TOP reuses an existing activity). Release only the matching pending
     * redirect and place the accessibility blocker over the target app so a
     * missing activity event cannot expose the blocked app.
     */
    private fun armRedirectRecovery(targetPackage: String) {
        redirectRecoveryJob?.cancel()
        redirectRecoveryJob = scope.launch {
            delay(REDIRECT_RECOVERY_TIMEOUT_MILLIS)
            withContext(Dispatchers.Main) {
                val redirectPendingForTarget = returningToApp && blockedPackage == targetPackage
                if (StandardProtectionRedirectPolicy.shouldShowFallbackOverlay(
                        latestSessionActive,
                        redirectPendingForTarget,
                        blockedPackage == targetPackage
                    )
                ) {
                    returningToApp = false
                    blockedPackage = null
                    foregroundPackage = targetPackage
                    android.util.Log.w(TAG, "Redirect latch recovered; retaining the block with the accessibility overlay")
                    showBlockOverlay(latestPreferences?.language == "ar", targetPackage)
                } else if (!latestSessionActive && redirectPendingForTarget) {
                    // A delayed fallback must not resurrect a blocker after the session ends or enters a break.
                    returningToApp = false
                    blockedPackage = null
                    removeBlockOverlay()
                }
                redirectRecoveryJob = null
            }
        }
    }

    private fun removeBlockOverlay() {
        val current = overlay ?: return
        overlay = null
        blockedPackage = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(current)
        }.onFailure {
            android.util.Log.w(TAG, "Block overlay removal failed", it)
        }
    }

    override fun onInterrupt() {
        removeBlockOverlay()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        safetyNetJob?.cancel()
        redirectRecoveryJob?.cancel()
        redirectRecoveryJob = null
        removeBlockOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FocusLockBlocker"
        private const val RECONCILE_INTERVAL_MILLIS = 1_000L
        private const val REDIRECT_RECOVERY_TIMEOUT_MILLIS = 2_000L
        private val WINDOW_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
    }
}
