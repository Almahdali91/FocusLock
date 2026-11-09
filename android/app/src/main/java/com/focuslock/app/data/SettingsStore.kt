package com.focuslock.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.focusLockStore by preferencesDataStore(name = "focuslock_settings")

class SettingsStore(private val context: Context) {
    private val languageKey = stringPreferencesKey("language")
    private val darkKey = booleanPreferencesKey("dark_theme")
    private val breakKey = booleanPreferencesKey("break_enabled")
    private val onboardingKey = booleanPreferencesKey("onboarding_completed")
    private val blockedPackagesKey = stringSetPreferencesKey("standard_blocked_packages")
    private val lockModeKey = stringPreferencesKey("lock_mode")

    val preferences = context.focusLockStore.data.map { prefs ->
        UserPreferences(
            language = prefs[languageKey] ?: "en",
            darkTheme = prefs[darkKey] ?: true,
            breaksEnabled = prefs[breakKey] ?: true,
            blockedPackages = prefs[blockedPackagesKey] ?: emptySet(),
            lockMode = prefs[lockModeKey] ?: "STANDARD"
        )
    }

    suspend fun hasCompletedOnboarding(): Boolean =
        context.focusLockStore.data.map { it[onboardingKey] ?: false }.first()

    suspend fun setOnboardingCompleted() {
        context.focusLockStore.edit { it[onboardingKey] = true }
    }

    suspend fun setLanguage(value: String) {
        require(value == "ar" || value == "en")
        context.focusLockStore.edit { it[languageKey] = value }
    }

    suspend fun setDarkTheme(value: Boolean) {
        context.focusLockStore.edit { it[darkKey] = value }
    }

    suspend fun setBreaksEnabled(value: Boolean) {
        context.focusLockStore.edit { it[breakKey] = value }
    }

    suspend fun setLockMode(value: String) {
        require(value == "STANDARD" || value == "STRICT")
        context.focusLockStore.edit { it[lockModeKey] = value }
    }

    suspend fun setBlockedPackages(value: Set<String>) {
        context.focusLockStore.edit { it[blockedPackagesKey] = value }
    }

    suspend fun setBlockedPackage(packageName: String, enabled: Boolean) {
        context.focusLockStore.edit { prefs ->
            val updated = prefs[blockedPackagesKey]?.toMutableSet() ?: mutableSetOf()
            if (enabled) updated.add(packageName) else updated.remove(packageName)
            prefs[blockedPackagesKey] = updated
        }
    }

    suspend fun clear() {
        context.focusLockStore.edit { it.clear() }
    }
}

data class UserPreferences(
    val language: String = "en",
    val darkTheme: Boolean = true,
    val breaksEnabled: Boolean = true,
    val blockedPackages: Set<String> = emptySet(),
    val lockMode: String = "STANDARD"
)
