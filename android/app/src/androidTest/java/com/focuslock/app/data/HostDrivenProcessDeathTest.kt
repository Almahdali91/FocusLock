package com.focuslock.app.data

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.MainActivity
import com.focuslock.app.services.FocusForegroundService
import com.focuslock.app.services.FocusLockDeviceAdminReceiver
import com.focuslock.app.system.SessionClockStore
import com.focuslock.app.system.StrictModeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-driven test pair. Run setup alone, kill the target app PID using adb
 * (not am force-stop), wait for the START_STICKY restart, then run verification.
 */
@RunWith(AndroidJUnit4::class)
class HostDrivenProcessDeathTest {
    private data class Env(
        val context: Context,
        val repository: FocusRepository,
        val dpm: DevicePolicyManager,
        val admin: ComponentName,
        val original: Set<String>,
        val policy: android.content.SharedPreferences,
        val service: android.content.SharedPreferences,
        val activity: android.content.SharedPreferences,
        val testPrefs: android.content.SharedPreferences
    )

    private fun env(): Env {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, FocusLockDeviceAdminReceiver::class.java)
        return Env(
            context, FocusRepository(FocusDatabase.get(context)), dpm, admin,
            if (dpm.isDeviceOwnerApp(context.packageName)) dpm.getLockTaskPackages(admin).toSet() else emptySet(),
            context.getSharedPreferences("strict_lock_task_policy", Context.MODE_PRIVATE),
            context.getSharedPreferences("focus_service_state", Context.MODE_PRIVATE),
            context.getSharedPreferences("strict_lock_task_state", Context.MODE_PRIVATE),
            context.getSharedPreferences("host_recovery_test", Context.MODE_PRIVATE)
        )
    }

    private fun requireHostOrchestration() {
        assumeTrue(
            "Host-driven recovery tests require explicit host orchestration; pass -e hostDriven true and invoke one test method at a time",
            InstrumentationRegistry.getArguments().getString("hostDriven") == "true"
        )
    }

    private suspend fun assumeClean(e: Env) {
        assumeTrue(e.dpm.isDeviceOwnerApp(e.context.packageName))
        assumeTrue(e.repository.activeSession() == null)
        assumeFalse(e.policy.getBoolean("session_policy_owned", false))
        assumeFalse(e.service.contains(FocusForegroundService.EXTRA_SESSION_ID))
        assumeFalse(e.activity.getBoolean("owned", false))
        assumeTrue(
            "Do not run host process-death test while Android is in Lock Task",
            e.context.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
        )
    }

    @Test
    fun prepareActiveSessionForHostKill() = runBlocking {
        requireHostOrchestration()
        val e = env()
        assumeClean(e)
        val now = System.currentTimeMillis()
        val id = e.repository.startSession(
            FocusSessionEntity(
                startTime = now,
                plannedEndTime = now + 20_000L,
                durationMillis = 20_000L,
                selectedLockMode = "STANDARD",
                breakEnabled = false
            )
        )
        try {
            SessionClockStore.beginSession(e.context, id)
            e.testPrefs.edit().putStringSet("expected_allowlist", e.original).commit()
            StrictModeManager.prepareAllowlist(e.context).getOrThrow()
            e.context.startForegroundService(
                Intent(e.context, FocusForegroundService::class.java).apply {
                    action = FocusForegroundService.ACTION_START
                    putExtra(FocusForegroundService.EXTRA_SESSION_ID, id)
                }
            )
            withTimeout(5_000L) {
                while (e.service.getLong(FocusForegroundService.EXTRA_SESSION_ID, 0L) != id) delay(50L)
            }
            assertEquals(id, e.service.getLong(FocusForegroundService.EXTRA_SESSION_ID, 0L))
            assertTrue(e.policy.getBoolean("session_policy_owned", false))
            e.context.startActivity(Intent(e.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: Throwable) {
            runCatching { e.context.stopService(Intent(e.context, FocusForegroundService::class.java)) }
            runCatching { e.repository.finishSession(id, System.currentTimeMillis(), "INTERRUPTED") }
            runCatching { StrictModeManager.releaseSessionAllowlist(e.context) }
            e.testPrefs.edit().clear().commit()
            throw failure
        }
    }

    @Test
    fun verifyStickyServiceRecoveredAndCompletedSessionAfterHostKill() = runBlocking {
        requireHostOrchestration()
        val e = env()
        withTimeout(35_000L) {
            while (e.repository.activeSession() != null ||
                e.policy.getBoolean("session_policy_owned", false) ||
                e.service.contains(FocusForegroundService.EXTRA_SESSION_ID)
            ) delay(100L)
        }
        val terminal = e.repository.recentSessions.first().first()
        assertEquals("COMPLETED", terminal.sessionStatus)
        val expectedAllowlist = e.testPrefs.getStringSet("expected_allowlist", emptySet()).orEmpty().toSet()
        assertEquals(expectedAllowlist, e.dpm.getLockTaskPackages(e.admin).toSet())
        assertFalse(e.policy.getBoolean("session_policy_owned", false))
        assertFalse(e.service.contains(FocusForegroundService.EXTRA_SESSION_ID))
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE,
            e.context.getSystemService(ActivityManager::class.java).lockTaskModeState)
        SessionClockStore.clearSession(e.context)
        e.testPrefs.edit().clear().commit()
        Unit
    }

    @Test
    fun verifyBootReceiverInterruptedAndRestoredPolicy() = runBlocking {
        requireHostOrchestration()
        val e = env()
        assertNull("Boot recovery must leave no active session", e.repository.activeSession())
        assertFalse("Boot recovery must clear session policy ownership", e.policy.getBoolean("session_policy_owned", false))
        assertFalse("Boot recovery must clear the service session marker", e.service.contains(FocusForegroundService.EXTRA_SESSION_ID))
        val terminal = e.repository.recentSessions.first().first()
        assertEquals("INTERRUPTED", terminal.sessionStatus)
        val expectedAllowlist = e.testPrefs.getStringSet("expected_allowlist", emptySet()).orEmpty().toSet()
        assertEquals(expectedAllowlist, e.dpm.getLockTaskPackages(e.admin).toSet())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE,
            e.context.getSystemService(ActivityManager::class.java).lockTaskModeState)
        e.testPrefs.edit().clear().commit()
        Unit
    }

    @Test
    fun recoverAbandonedHostKillSetup() = runBlocking {
        requireHostOrchestration()
        val e = env()
        e.context.stopService(Intent(e.context, FocusForegroundService::class.java))
        e.repository.activeSession()?.let { active ->
            e.repository.finishSession(active.id, System.currentTimeMillis(), "INTERRUPTED")
        }
        val recovery = StrictModeManager.releaseSessionAllowlist(e.context)
        assertTrue("Device Owner allowlist recovery failed: ${recovery.exceptionOrNull()?.message}", recovery.isSuccess)
        SessionClockStore.clearSession(e.context)
        e.service.edit().clear().commit()
        e.activity.edit().clear().commit()
        e.testPrefs.edit().clear().commit()
        assertNull(e.repository.activeSession())
        assertFalse(e.policy.getBoolean("session_policy_owned", false))
        assertFalse(e.service.contains(FocusForegroundService.EXTRA_SESSION_ID))
        assertEquals(emptySet<String>(), e.dpm.getLockTaskPackages(e.admin).toSet())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE,
            e.context.getSystemService(ActivityManager::class.java).lockTaskModeState)
    }
}
