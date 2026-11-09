package com.focuslock.app.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionManagerTest {
    @Test
    fun accessibilityIsOptionalWhenNoBlockedAppsAreSelected() {
        assertTrue(PermissionManager.isStandardProtectionReady(emptySet(), false))
        assertTrue(PermissionManager.isStandardProtectionReady(emptySet(), true))
    }

    @Test
    fun accessibilityIsRequiredWhenBlockedAppsAreSelected() {
        val selected = setOf("com.android.chrome")
        assertFalse(PermissionManager.isStandardProtectionReady(selected, false))
        assertTrue(PermissionManager.isStandardProtectionReady(selected, true))
    }

    @Test
    fun multipleBlockedAppsStillRequireOneEnabledAccessibilityService() {
        val selected = setOf("com.android.chrome", "com.whatsapp")
        assertFalse(PermissionManager.isStandardProtectionReady(selected, false))
        assertTrue(PermissionManager.isStandardProtectionReady(selected, true))
    }

    @Test
    fun strictManagedModeDoesNotRequireAccessibilityForSelectedApps() {
        val selected = setOf("com.android.chrome", "com.whatsapp")
        assertTrue(PermissionManager.isProtectionReady("STRICT", selected, false))
        assertTrue(PermissionManager.missingRequirementsForMode("STRICT", selected, false).isEmpty())
    }

    @Test
    fun standardModeStillRequiresAccessibilityForSelectedApps() {
        val selected = setOf("com.android.chrome")
        assertFalse(PermissionManager.isProtectionReady("STANDARD", selected, false))
        assertTrue(PermissionManager.missingRequirementsForMode("STANDARD", selected, false).contains("standard_accessibility"))
        assertTrue(PermissionManager.isProtectionReady("STANDARD", selected, true))
    }

    @Test
    fun unknownProtectionModeFailsSafeAsStandard() {
        val selected = setOf("com.android.chrome")
        assertFalse(PermissionManager.isProtectionReady("UNKNOWN", selected, false))
        assertTrue(PermissionManager.isProtectionReady("UNKNOWN", selected, true))
    }
}
