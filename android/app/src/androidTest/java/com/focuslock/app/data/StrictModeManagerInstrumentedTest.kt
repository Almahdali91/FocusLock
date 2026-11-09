package com.focuslock.app.data

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.services.FocusLockDeviceAdminReceiver
import com.focuslock.app.system.StrictModeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrictModeManagerInstrumentedTest {
    private data class Environment(
        val context: Context,
        val dpm: DevicePolicyManager,
        val admin: ComponentName,
        val policyPrefs: android.content.SharedPreferences,
        val activityPrefs: android.content.SharedPreferences,
        val originalAllowlist: Set<String>
    )

    private fun environment(): Environment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, FocusLockDeviceAdminReceiver::class.java)
        return Environment(
            context = context,
            dpm = dpm,
            admin = admin,
            policyPrefs = context.getSharedPreferences("strict_lock_task_policy", Context.MODE_PRIVATE),
            activityPrefs = context.getSharedPreferences("strict_lock_task_state", Context.MODE_PRIVATE),
            originalAllowlist = if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.getLockTaskPackages(admin).toSet()
            } else emptySet()
        )
    }

    private fun assumeRecoverySafe(env: Environment) {
        assumeTrue("Requires the dedicated Device Owner emulator", env.dpm.isDeviceOwnerApp(env.context.packageName))
        assumeFalse("Do not alter policy while a focus session is active", runBlocking {
            FocusDatabase.get(env.context).sessions().activeSession() != null
        })
        val activityManager = env.context.getSystemService(ActivityManager::class.java)
        assumeTrue("Do not alter policy while Android is in Lock Task", activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE)
        assumeFalse("Do not overwrite a pending recovery record", env.policyPrefs.getBoolean("session_policy_owned", false))
        assumeFalse("Do not overwrite an outstanding Activity Lock Task ownership marker", env.activityPrefs.getBoolean("owned", false))
    }

    @Test
    fun releaseWithoutOwnedPolicyIsSafeNoOp() {
        val env = environment()
        assumeFalse("Do not interfere with an active FocusLock DPM policy", env.policyPrefs.getBoolean("session_policy_owned", false))
        val before = env.originalAllowlist

        StrictModeManager.releaseSessionAllowlist(env.context).getOrThrow()

        assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
        if (env.dpm.isDeviceOwnerApp(env.context.packageName)) {
            assertEquals(before, env.dpm.getLockTaskPackages(env.admin).toSet())
        }
    }

    @Test
    fun deviceOwnerTerminalRecoveryRestoresPreviousAllowlistAndClearsOwnership() {
        val env = environment()
        assumeRecoverySafe(env)
        val packageName = env.context.packageName
        val previousActivityOwned = env.activityPrefs.getBoolean("owned", false)
        val previousOriginal = env.policyPrefs.getStringSet("original_packages", emptySet()).orEmpty().toSet()
        val original = env.originalAllowlist - packageName

        try {
            // Simulate a Strict Lock Task session whose Activity has disappeared.
            env.policyPrefs.edit()
                .putStringSet("original_packages", original)
                .putBoolean("session_policy_owned", true)
                .commit()
            env.activityPrefs.edit().putBoolean("owned", true).commit()
            env.dpm.setLockTaskPackages(env.admin, (original + packageName).toTypedArray())
            assertTrue(env.dpm.isLockTaskPermitted(packageName))

            StrictModeManager.releaseSessionAllowlist(env.context).getOrThrow()

            assertEquals(original, env.dpm.getLockTaskPackages(env.admin).toSet())
            assertFalse(env.dpm.isLockTaskPermitted(packageName))
            assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
            assertFalse(env.activityPrefs.getBoolean("owned", true))

            // Recovery is idempotent; a second cleanup must not mutate policy.
            StrictModeManager.releaseSessionAllowlist(env.context).getOrThrow()
            assertEquals(original, env.dpm.getLockTaskPackages(env.admin).toSet())
        } finally {
            env.dpm.setLockTaskPackages(env.admin, env.originalAllowlist.toTypedArray())
            env.policyPrefs.edit()
                .putStringSet("original_packages", previousOriginal)
                .putBoolean("session_policy_owned", false)
                .commit()
            env.activityPrefs.edit().putBoolean("owned", previousActivityOwned).commit()
        }
    }

    @Test
    fun recoveryPreservesPreExistingAllowlistWhenFocusLockDoesNotOwnLockTask() {
        val env = environment()
        assumeRecoverySafe(env)
        val packageName = env.context.packageName
        val previousActivityOwned = env.activityPrefs.getBoolean("owned", false)
        val previousOriginal = env.policyPrefs.getStringSet("original_packages", emptySet()).orEmpty().toSet()
        val preExistingAllowlist = env.originalAllowlist + packageName

        try {
            // FocusLock was already permitted by an administrator before this
            // session; another kiosk controller may own the running Lock Task.
            env.policyPrefs.edit()
                .putStringSet("original_packages", preExistingAllowlist)
                .putBoolean("session_policy_owned", true)
                .commit()
            env.activityPrefs.edit().putBoolean("owned", false).commit()
            env.dpm.setLockTaskPackages(env.admin, preExistingAllowlist.toTypedArray())

            StrictModeManager.releaseSessionAllowlist(env.context).getOrThrow()

            assertEquals(preExistingAllowlist, env.dpm.getLockTaskPackages(env.admin).toSet())
            assertTrue(env.dpm.isLockTaskPermitted(packageName))
            assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
            assertFalse(env.activityPrefs.getBoolean("owned", true))
        } finally {
            env.dpm.setLockTaskPackages(env.admin, env.originalAllowlist.toTypedArray())
            env.policyPrefs.edit()
                .putStringSet("original_packages", previousOriginal)
                .putBoolean("session_policy_owned", false)
                .commit()
            env.activityPrefs.edit().putBoolean("owned", previousActivityOwned).commit()
        }
    }

    @Test
    fun failedAllowlistRestoreRetainsRecoverySnapshotAndSucceedsOnRetry() {
        val env = environment()
        assumeRecoverySafe(env)
        val previousOwned = env.policyPrefs.getBoolean("session_policy_owned", false)
        val previousOriginal = env.policyPrefs.getStringSet("original_packages", emptySet()).orEmpty().toSet()
        val simulatedOriginal = env.originalAllowlist + env.context.packageName

        try {
            env.policyPrefs.edit()
                .putStringSet("original_packages", simulatedOriginal)
                .putBoolean("session_policy_owned", true)
                .commit()

            var writeAttempts = 0
            val failed = StrictModeManager.releaseSessionAllowlist(env.context) { _, _, _ ->
                writeAttempts++
                throw IllegalStateException("Injected policy write failure")
            }
            assertEquals(1, writeAttempts)
            assertTrue("Injected policy failure must be reported", failed.isFailure)
            assertTrue("Recovery ownership must remain durable after DPM failure",
                env.policyPrefs.getBoolean("session_policy_owned", false))
            assertEquals(simulatedOriginal,
                env.policyPrefs.getStringSet("original_packages", emptySet()).orEmpty().toSet())
            assertEquals(env.originalAllowlist, env.dpm.getLockTaskPackages(env.admin).toSet())

            // Retry through the real DevicePolicyManager after the transient failure.
            StrictModeManager.releaseSessionAllowlist(env.context).getOrThrow()

            assertFalse(env.policyPrefs.getBoolean("session_policy_owned", false))
            assertEquals(simulatedOriginal, env.dpm.getLockTaskPackages(env.admin).toSet())
        } finally {
            env.dpm.setLockTaskPackages(env.admin, env.originalAllowlist.toTypedArray())
            env.policyPrefs.edit()
                .putStringSet("original_packages", previousOriginal)
                .putBoolean("session_policy_owned", previousOwned)
                .commit()
        }
    }

    @Test
    fun releaseClearsStaleMarkerWhenDeviceOwnerAuthorityWasLost() {
        val env = environment()
        assumeFalse(
            "This recovery case requires a device where FocusLock is no longer Device Owner",
            env.dpm.isDeviceOwnerApp(env.context.packageName)
        )
        val previousOwned = env.policyPrefs.getBoolean("session_policy_owned", false)
        val previousOriginal = env.policyPrefs.getStringSet("original_packages", emptySet()).orEmpty().toSet()

        try {
            env.policyPrefs.edit()
                .putStringSet("original_packages", setOf("com.example.previous"))
                .putBoolean("session_policy_owned", true)
                .commit()

            StrictModeManager.releaseSessionAllowlist(env.context).getOrThrow()

            assertFalse("The stale ownership marker must be cleared",
                env.policyPrefs.getBoolean("session_policy_owned", true))
            assertFalse("The obsolete allowlist snapshot must be removed",
                env.policyPrefs.contains("original_packages"))
        } finally {
            env.policyPrefs.edit()
                .putStringSet("original_packages", previousOriginal)
                .putBoolean("session_policy_owned", previousOwned)
                .commit()
        }
    }
}
