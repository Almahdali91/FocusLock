package com.focuslock.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.system.UninstallProtectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Extends the active Device Owner uninstall policy to packages installed mid-session. */
class FocusPackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (FocusRepository(FocusDatabase.get(appContext)).activeSession() != null) {
                    UninstallProtectionManager.protectNewPackage(appContext, packageName)
                        .onFailure { Log.e(TAG, "Could not protect newly installed package: $packageName", it) }
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to apply uninstall protection to package: $packageName", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FocusLockUninstall"
    }
}
