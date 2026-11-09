package com.focuslock.app.services

import android.app.admin.DeviceAdminReceiver

/** Receiver used only when the device is explicitly provisioned by its owner/admin. */
class FocusLockDeviceAdminReceiver : DeviceAdminReceiver()
