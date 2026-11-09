package com.focuslock.app.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.services.FocusForegroundService
import com.focuslock.app.services.FocusLockDeviceAdminReceiver
import com.focuslock.app.system.UninstallProtectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class UninstallProtectionInstrumentedTest {
    @Test
    fun deviceOwnerBlocksInstalledAppsAndRestoresOriginalPolicy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, FocusLockDeviceAdminReceiver::class.java)
        assumeTrue("This test requires FocusLock to be Device Owner", dpm.isDeviceOwnerApp(context.packageName))

        val installed = context.packageManager.getInstalledPackages(0)
            .map { it.packageName }.toSet()
        val originalStates = installed.associateWith { dpm.isUninstallBlocked(admin, it) }
        try {
            UninstallProtectionManager.protectInstalledPackages(context).getOrThrow()
            assertTrue("The package inventory should not be empty", installed.isNotEmpty())
            assertTrue(
                "Every installed app should be protected during the session",
                installed.all { dpm.isUninstallBlocked(admin, it) }
            )
        } finally {
            UninstallProtectionManager.release(context).getOrThrow()
        }

        assertEquals(
            "Every app's pre-session uninstall policy must be restored",
            originalStates,
            installed.associateWith { dpm.isUninstallBlocked(admin, it) }
        )
    }

    @Test
    fun activeFocusServiceEnablesAndReleasesUninstallProtection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, FocusLockDeviceAdminReceiver::class.java)
        assumeTrue("This test requires FocusLock to be Device Owner", dpm.isDeviceOwnerApp(context.packageName))

        val originalSelfState = dpm.isUninstallBlocked(admin, context.packageName)
        val repository = FocusRepository(FocusDatabase.get(context))
        val now = System.currentTimeMillis()
        val sessionId = repository.startSession(
            FocusSessionEntity(
                startTime = now,
                plannedEndTime = now + 60_000L,
                durationMillis = 60_000L,
                breakEnabled = false
            )
        )
        try {
            context.startForegroundService(
                Intent(context, FocusForegroundService::class.java).apply {
                    action = FocusForegroundService.ACTION_START
                    putExtra(FocusForegroundService.EXTRA_SESSION_ID, sessionId)
                }
            )
            awaitCondition { dpm.isUninstallBlocked(admin, context.packageName) }
            assertTrue(UninstallProtectionManager.isProtectionOwned(context))
        } finally {
            repository.finishSession(sessionId, System.currentTimeMillis(), "INTERRUPTED")
            context.startService(
                Intent(context, FocusForegroundService::class.java)
                    .setAction(FocusForegroundService.ACTION_STOP)
            )
            awaitCondition { !UninstallProtectionManager.isProtectionOwned(context) }
        }
        assertEquals(originalSelfState, dpm.isUninstallBlocked(admin, context.packageName))
    }

    private fun awaitCondition(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        assertTrue("Condition was not met within $timeoutMillis ms", condition())
    }
}
