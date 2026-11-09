package com.focuslock.app.domain

import com.focuslock.app.system.ProtectionCapabilityManager
import com.focuslock.app.system.ProtectionTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionTierResolutionTest {
    @Test fun managedDeviceWithLockTaskGetsEnterpriseTier() {
        assertEquals(
            ProtectionTier.ENTERPRISE_LOCK_TASK,
            ProtectionCapabilityManager.resolveTier(
                accessibilityEnabled = false,
                deviceOwner = true,
                lockTaskPermitted = true
            )
        )
    }

    @Test fun deviceOwnerWithoutLockTaskDoesNotClaimEnterpriseProtection() {
        assertEquals(
            ProtectionTier.MANUAL_ONLY,
            ProtectionCapabilityManager.resolveTier(
                accessibilityEnabled = false,
                deviceOwner = true,
                lockTaskPermitted = false
            )
        )
    }

    @Test fun accessibilityEnablesStandardTierWithoutDeviceOwner() {
        assertEquals(
            ProtectionTier.STANDARD_ACCESSIBILITY,
            ProtectionCapabilityManager.resolveTier(
                accessibilityEnabled = true,
                deviceOwner = false,
                lockTaskPermitted = false
            )
        )
    }

    @Test fun missingCapabilitiesFallsBackToManualTier() {
        assertEquals(
            ProtectionTier.MANUAL_ONLY,
            ProtectionCapabilityManager.resolveTier(
                accessibilityEnabled = false,
                deviceOwner = false,
                lockTaskPermitted = false
            )
        )
    }

    @Test fun accessibilityTakesPrecedenceOverUnpermittedDeviceOwner() {
        assertEquals(
            ProtectionTier.STANDARD_ACCESSIBILITY,
            ProtectionCapabilityManager.resolveTier(
                accessibilityEnabled = true,
                deviceOwner = true,
                lockTaskPermitted = false
            )
        )
    }
}
