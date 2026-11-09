package com.focuslock.app.presentation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.data.FocusSessionEntity
import com.focuslock.app.data.SettingsStore
import com.focuslock.app.services.FocusForegroundService
import com.focuslock.app.system.PermissionManager
import com.focuslock.app.system.SessionClockStore
import com.focuslock.app.system.ProtectionCapabilityManager
import com.focuslock.app.system.StrictSessionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns screen/session state and lifecycle-independent session orchestration.
 * UI permission prompts and navigation rendering remain in Compose.
 */
class FocusLockAppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = FocusRepository(FocusDatabase.get(application))
    val settings = SettingsStore(application)

    private val _active = MutableStateFlow<FocusSessionEntity?>(null)
    val active: StateFlow<FocusSessionEntity?> = _active.asStateFlow()

    private val _remaining = MutableStateFlow(0L)
    val remaining: StateFlow<Long> = _remaining.asStateFlow()

    private val _screen = MutableStateFlow("home")
    val screen: StateFlow<String> = _screen.asStateFlow()

    private val _durationMinutes = MutableStateFlow(60)
    val durationMinutes: StateFlow<Int> = _durationMinutes.asStateFlow()

    private val _breaksEnabled = MutableStateFlow(true)
    val breaksEnabled: StateFlow<Boolean> = _breaksEnabled.asStateFlow()

    private val _selectedLockMode = MutableStateFlow("STANDARD")
    val selectedLockMode: StateFlow<String> = _selectedLockMode.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _showOnboarding = MutableStateFlow(false)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    private val _showPermissionChecklist = MutableStateFlow(false)
    val showPermissionChecklist: StateFlow<Boolean> = _showPermissionChecklist.asStateFlow()

    private var sessionObserver: Job? = null
    private var initialized = false

    fun setScreen(value: String) { _screen.value = value }
    fun setDurationMinutes(value: Int) { _durationMinutes.value = value.coerceIn(5, 480) }
    fun setBreaksEnabled(value: Boolean) { _breaksEnabled.value = value }
    fun setSelectedLockMode(value: String) { _selectedLockMode.value = value }
    fun setShowOnboarding(value: Boolean) { _showOnboarding.value = value }
    fun setShowPermissionChecklist(value: Boolean) { _showPermissionChecklist.value = value }

    fun initialize(context: Context, requiredProtectionReady: Boolean) {
        if (initialized) return
        initialized = true
        _showPermissionChecklist.value = !context.getSharedPreferences(
            "focus_permission_checklist", Context.MODE_PRIVATE
        ).getBoolean("seen", false) || !requiredProtectionReady

        viewModelScope.launch {
            val restored = repository.activeSession()
            _showOnboarding.value = !settings.hasCompletedOnboarding()
            _showPermissionChecklist.value = _showPermissionChecklist.value || !requiredProtectionReady
            _breaksEnabled.value = settings.preferences.first().breaksEnabled
            _selectedLockMode.value = settings.preferences.first().lockMode
            if (restored != null) {
                _active.value = restored
                val serviceStart = runCatching { startFocusService(context, restored) }
                if (serviceStart.isFailure) {
                    android.util.Log.e(TAG, "Could not restore the focus foreground service; reconciling the persisted session.", serviceStart.exceptionOrNull())
                    repository.finishSession(restored.id, System.currentTimeMillis(), "INTERRUPTED")
                    SessionClockStore.clearSession(context)
                    _active.value = null
                    _screen.value = "home"
                }
            }
            _loaded.value = true
        }
        observeSessionClock(context)
    }

    fun refreshRequiredAccess(context: Context, protectionAccessReady: Boolean) {
        val current = _active.value ?: return
        if (protectionAccessReady) return
        viewModelScope.launch {
            val persisted = repository.activeSession()?.takeIf { it.id == current.id }
            if (persisted != null) {
                context.startService(Intent(context, FocusForegroundService::class.java)
                    .setAction(FocusForegroundService.ACTION_STOP))
                repository.finishSession(persisted.id, System.currentTimeMillis(), "INTERRUPTED")
                SessionClockStore.clearSession(context)
            }
            _active.value = null
            _screen.value = "permissions"
        }
    }

    fun startSession(context: Context, effectiveLockMode: String, onPermissionMissing: () -> Unit) {
        if (_active.value != null) return
        viewModelScope.launch {
            val latestPreferences = settings.preferences.first()
            val accessibilityEnabled = effectiveLockMode == "STRICT" ||
                PermissionManager.isStandardAccessibilityEnabled(context)
            val missing = PermissionManager.missingRequirementsForMode(
                effectiveLockMode,
                latestPreferences.blockedPackages,
                accessibilityEnabled
            )
            if (missing.isNotEmpty()) {
                _screen.value = "permission_required"
                onPermissionMissing()
                return@launch
            }
            val now = System.currentTimeMillis()
            val millis = _durationMinutes.value * 60_000L
            settings.setBreaksEnabled(_breaksEnabled.value)
            settings.setLockMode(_selectedLockMode.value)
            val row = FocusSessionEntity(
                startTime = now,
                plannedEndTime = now + millis,
                durationMillis = millis,
                selectedLockMode = effectiveLockMode,
                breakEnabled = _breaksEnabled.value
            )
            val id = repository.startSession(row)
            val started = row.copy(id = id)
            SessionClockStore.beginSession(context, id)
            try {
                startFocusService(context, started)
                _active.value = started
                _screen.value = "home"
            } catch (error: Exception) {
                // Compensate for a persisted session if Android rejects service startup.
                android.util.Log.e(TAG, "Could not start the focus foreground service; rolling back the new session.", error)
                repository.finishSession(id, System.currentTimeMillis(), "INTERRUPTED")
                SessionClockStore.clearSession(context)
                _active.value = null
                _screen.value = "home"
            }
        }
    }

    fun markOnboardingComplete() {
        viewModelScope.launch {
            settings.setOnboardingCompleted()
            _showOnboarding.value = false
            _screen.value = "permissions"
        }
    }

    fun completePermissionChecklist(context: Context) {
        context.getSharedPreferences("focus_permission_checklist", Context.MODE_PRIVATE)
            .edit { putBoolean("seen", true) }
        _showPermissionChecklist.value = false
    }

    private fun observeSessionClock(context: Context) {
        sessionObserver?.cancel()
        sessionObserver = viewModelScope.launch {
            repository.activeSessionStatus.collectLatest { current ->
                if (current == null) {
                    _active.value = null
                    _remaining.value = 0L
                } else {
                    _active.value = current
                    repository.observeBreaks(current.id).collectLatest { breaks ->
                        val sessionStartElapsed = SessionClockStore.sessionStartElapsed(context, current)
                        while (true) {
                            val nowElapsed = SystemClock.elapsedRealtime()
                            val activeBreak = breaks.firstOrNull { it.breakStatus == "ACTIVE" }
                            _remaining.value = if (activeBreak != null) {
                                val breakStartElapsed = SessionClockStore.breakStartElapsed(context, activeBreak)
                                (activeBreak.durationMillis - (nowElapsed - breakStartElapsed)).coerceAtLeast(0L)
                            } else {
                                val completedBreakMillis = breaks.filter { it.breakStatus == "COMPLETED" }
                                    .sumOf { it.durationMillis }
                                (current.durationMillis - (nowElapsed - sessionStartElapsed - completedBreakMillis))
                                    .coerceAtLeast(0L)
                            }
                            delay(1_000L)
                        }
                    }
                }
            }
        }
    }

    private fun startFocusService(context: Context, session: FocusSessionEntity) {
        val intent = Intent(context, FocusForegroundService::class.java).apply {
            action = FocusForegroundService.ACTION_START
            putExtra(FocusForegroundService.EXTRA_SESSION_ID, session.id)
        }
        context.startForegroundService(intent)
    }

    override fun onCleared() {
        sessionObserver?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "FocusLockViewModel"
    }
}

