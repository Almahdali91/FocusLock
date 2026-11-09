package com.focuslock.app.data

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusRecoveryInstrumentedTest {
    private data class Snapshot(
        val context: Context,
        val repository: FocusRepository,
        val dpm: DevicePolicyManager,
        val admin: ComponentName,
        val originalAllowlist: Set<String>,
        val policyPrefs: android.content.SharedPreferences,
        val activityPrefs: android.content.SharedPreferences,
        val servicePrefs: android.content.SharedPreferences
    )

    private fun snapshot(): Snapshot {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, FocusLockDeviceAdminReceiver::class.java)
        return Snapshot(
            context = context,
            repository = FocusRepository(FocusDatabase.get(context)),
            dpm = dpm,
            admin = admin,
            originalAllowlist = if (dpm.isDeviceOwnerApp(context.packageName)) dpm.getLockTaskPackages(admin).toSet() else emptySet(),
            policyPrefs = context.getSharedPreferences("strict_lock_task_policy", Context.MODE_PRIVATE),
            activityPrefs = context.getSharedPreferences("strict_lock_task_state", Context.MODE_PRIVATE),
            servicePrefs = context.getSharedPreferences("focus_service_state", Context.MODE_PRIVATE)
        )
    }

    private suspend fun assumeSafe(env: Snapshot) {
        assumeTrue("Recovery integration tests require the dedicated Device Owner emulator", env.dpm.isDeviceOwnerApp(env.context.packageName))
        assumeTrue("A pre-existing focus session must not be overwritten", env.repository.activeSession() == null)
        assumeFalse("A prior DPM recovery must not be overwritten", env.policyPrefs.getBoolean("session_policy_owned", false))
        assumeFalse("A prior Activity Lock Task ownership marker must not be overwritten", env.activityPrefs.getBoolean("owned", false))
        assumeFalse("A previously started service must not be overwritten", env.servicePrefs.contains(FocusForegroundService.EXTRA_SESSION_ID))
        val activityManager = env.context.getSystemService(ActivityManager::class.java)
        assumeTrue("Do not run recovery tests while Android is in Lock Task", activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE)
    }

    private fun startIntent(context: Context, sessionId: Long) =
        Intent(context, FocusForegroundService::class.java).apply {
            action = FocusForegroundService.ACTION_START
            putExtra(FocusForegroundService.EXTRA_SESSION_ID, sessionId)
        }

    private fun stopIntent(context: Context) =
        Intent(context, FocusForegroundService::class.java).setAction(FocusForegroundService.ACTION_STOP)

    private suspend fun awaitCleanup(env: Snapshot) {
        withTimeout(15_000L) {
            while (env.policyPrefs.getBoolean("session_policy_owned", false) ||
                env.servicePrefs.contains(FocusForegroundService.EXTRA_SESSION_ID) ||
                env.repository.activeSession() != null
            ) {
                delay(100L)
            }
        }
    }

    private suspend fun cleanup(env: Snapshot, sessionId: Long?) {
        runCatching { env.context.stopService(Intent(env.context, FocusForegroundService::class.java)) }
        sessionId?.let { id ->
            runCatching {
                val active = env.repository.activeSession()
                if (active?.id == id) env.repository.finishSession(id, System.currentTimeMillis(), "INTERRUPTED")
            }
        }
        runCatching { StrictModeManager.releaseSessionAllowlist(env.context) }
        runCatching { SessionClockStore.clearSession(env.context) }
        runCatching { env.dpm.setLockTaskPackages(env.admin, env.originalAllowlist.toTypedArray()) }
        env.policyPrefs.edit().clear().commit()
        env.policyPrefs.edit().putBoolean("session_policy_owned", false).commit()
        env.activityPrefs.edit().putBoolean("owned", false).commit()
        env.servicePrefs.edit().clear().commit()
    }

    @Test
    fun normalSessionCompletionStopsServiceAndRestoresDeviceOwnerPolicy() = runBlocking {
        val env = snapshot()
        assumeSafe(env)
        var sessionId: Long? = null
        try {
            val now = System.currentTimeMillis()
            val row = FocusSessionEntity(
                startTime = now,
                plannedEndTime = now + 2_000L,
                durationMillis = 1_500L,
                selectedLockMode = "STRICT",
                breakEnabled = false
            )
            sessionId = env.repository.startSession(row)
            SessionClockStore.beginSession(env.context, sessionId)
            StrictModeManager.prepareAllowlist(env.context).getOrThrow()

            env.context.startForegroundService(startIntent(env.context, sessionId))
            awaitCleanup(env)

            val terminal = env.repository.recentSessions.first().first { it.id == sessionId }
            assertEquals("COMPLETED", terminal.sessionStatus)
            assertEquals(env.originalAllowlist, env.dpm.getLockTaskPackages(env.admin).toSet())
            assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
            assertFalse(env.activityPrefs.getBoolean("owned", false))
            assertFalse(env.servicePrefs.contains(FocusForegroundService.EXTRA_SESSION_ID))
            assertEquals(ActivityManager.LOCK_TASK_MODE_NONE,
                env.context.getSystemService(ActivityManager::class.java).lockTaskModeState)
        } finally {
            cleanup(env, sessionId)
        }
    }

    @Test
    fun bootRecoveryInterruptsActiveSessionAndRestoresOwnedDevicePolicy() = runBlocking {
        val env = snapshot()
        assumeSafe(env)
        var sessionId: Long? = null
        try {
            val now = System.currentTimeMillis()
            sessionId = env.repository.startSession(
                FocusSessionEntity(
                    startTime = now,
                    plannedEndTime = now + 120_000L,
                    durationMillis = 120_000L,
                    selectedLockMode = "STRICT",
                    breakEnabled = false
                )
            )
            SessionClockStore.beginSession(env.context, sessionId)
            StrictModeManager.prepareAllowlist(env.context).getOrThrow()
            env.activityPrefs.edit().putBoolean("owned", true).commit()

            // Exercise the exact recovery routine used by the protected system receiver.
            com.focuslock.app.services.FocusBootRecoveryReceiver.recoverAfterBoot(env.context)
            withTimeout(15_000L) {
                while (env.policyPrefs.getBoolean("session_policy_owned", false) ||
                    env.repository.activeSession() != null
                ) delay(100L)
            }

            val terminal = env.repository.recentSessions.first().first { it.id == sessionId }
            assertEquals("INTERRUPTED", terminal.sessionStatus)
            assertEquals(env.originalAllowlist, env.dpm.getLockTaskPackages(env.admin).toSet())
            assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
            assertFalse(env.activityPrefs.getBoolean("owned", false))
            assertFalse(env.servicePrefs.contains(FocusForegroundService.EXTRA_SESSION_ID))
            assertEquals(ActivityManager.LOCK_TASK_MODE_NONE,
                env.context.getSystemService(ActivityManager::class.java).lockTaskModeState)
        } finally {
            cleanup(env, sessionId)
        }
    }

    @Test
    fun repeatedStartAndStopCommandsAreIdempotentAndLeaveNoSessionPolicy() = runBlocking {
        val env = snapshot()
        assumeSafe(env)
        var lastSessionId: Long? = null
        try {
            repeat(5) {
                val now = System.currentTimeMillis()
                val sessionId = env.repository.startSession(
                    FocusSessionEntity(
                        startTime = now,
                        plannedEndTime = now + 120_000L,
                        durationMillis = 120_000L,
                        selectedLockMode = "STRICT",
                        breakEnabled = false
                    )
                )
                lastSessionId = sessionId
                SessionClockStore.beginSession(env.context, sessionId)
                StrictModeManager.prepareAllowlist(env.context).getOrThrow()

                env.context.startForegroundService(startIntent(env.context, sessionId))
                delay(250L)
                env.context.startForegroundService(startIntent(env.context, sessionId))
                delay(250L)

                env.repository.finishSession(sessionId, System.currentTimeMillis(), "INTERRUPTED")
                env.context.startService(stopIntent(env.context))
                delay(100L)
                env.context.startService(stopIntent(env.context))
                awaitCleanup(env)

                assertNull(env.repository.activeSession())
                assertEquals(env.originalAllowlist, env.dpm.getLockTaskPackages(env.admin).toSet())
                assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
                assertFalse(env.servicePrefs.contains(FocusForegroundService.EXTRA_SESSION_ID))
                assertEquals(ActivityManager.LOCK_TASK_MODE_NONE,
                    env.context.getSystemService(ActivityManager::class.java).lockTaskModeState)
            }
        } finally {
            cleanup(env, lastSessionId)
        }
    }
}
