package com.focuslock.app.system

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import com.focuslock.app.data.BreakSessionEntity
import com.focuslock.app.data.FocusSessionEntity
import com.focuslock.app.domain.SessionClockMath

/**
 * Keeps monotonic anchors across process death. Wall time is consulted only
 * when BOOT_COUNT changes (the monotonic clock resets on a device reboot).
 */
object SessionClockStore {
    private const val PREFS = "session_clock_anchors"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_START_ELAPSED = "session_start_elapsed"
    private const val KEY_SESSION_BOOT_COUNT = "session_boot_count"
    private const val KEY_BREAK_ID = "break_id"
    private const val KEY_BREAK_START_ELAPSED = "break_start_elapsed"
    private const val KEY_BREAK_BOOT_COUNT = "break_boot_count"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentBootCount(context: Context): Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)

    fun beginSession(context: Context, sessionId: Long) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = currentBootCount(context)
        prefs(context).edit(commit = true) {
            putLong(KEY_SESSION_ID, sessionId)
            putLong(KEY_SESSION_START_ELAPSED, nowElapsed)
            putInt(KEY_SESSION_BOOT_COUNT, boot)
            remove(KEY_BREAK_ID)
            remove(KEY_BREAK_START_ELAPSED)
            remove(KEY_BREAK_BOOT_COUNT)
        }
    }

    fun sessionStartElapsed(context: Context, session: FocusSessionEntity): Long {
        val state = prefs(context)
        val boot = currentBootCount(context)
        val storedId = state.getLong(KEY_SESSION_ID, -1L)
        val storedBoot = state.getInt(KEY_SESSION_BOOT_COUNT, -1)
        val hasStoredStart = state.contains(KEY_SESSION_START_ELAPSED)
        val storedStart = state.getLong(KEY_SESSION_START_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (storedId == session.id && hasStoredStart &&
            SessionClockMath.isStoredAnchorUsable(storedBoot, boot, storedStart, nowElapsed)
        ) return storedStart

        val anchor = SessionClockMath.restoreAnchor(
            startedAtWallTime = session.startTime,
            wallNowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            bootCount = boot
        )
        state.edit(commit = true) {
            putLong(KEY_SESSION_ID, session.id)
            putLong(KEY_SESSION_START_ELAPSED, anchor.startedAtElapsedRealtime)
            putInt(KEY_SESSION_BOOT_COUNT, boot)
        }
        return anchor.startedAtElapsedRealtime
    }

    fun beginBreak(context: Context, breakId: Long) {
        val state = prefs(context)
        state.edit(commit = true) {
            putLong(KEY_BREAK_ID, breakId)
            putLong(KEY_BREAK_START_ELAPSED, SystemClock.elapsedRealtime())
            putInt(KEY_BREAK_BOOT_COUNT, currentBootCount(context))
        }
    }

    fun breakStartElapsed(context: Context, activeBreak: BreakSessionEntity): Long {
        val state = prefs(context)
        val boot = currentBootCount(context)
        val storedId = state.getLong(KEY_BREAK_ID, -1L)
        val storedBoot = state.getInt(KEY_BREAK_BOOT_COUNT, -1)
        val hasStoredStart = state.contains(KEY_BREAK_START_ELAPSED)
        val storedStart = state.getLong(KEY_BREAK_START_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (storedId == activeBreak.id && hasStoredStart &&
            SessionClockMath.isStoredAnchorUsable(storedBoot, boot, storedStart, nowElapsed)
        ) return storedStart

        val anchor = SessionClockMath.restoreAnchor(
            startedAtWallTime = activeBreak.startTime,
            wallNowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            bootCount = boot,
            maximumElapsedMillis = activeBreak.durationMillis
        )
        state.edit(commit = true) {
            putLong(KEY_BREAK_ID, activeBreak.id)
            putLong(KEY_BREAK_START_ELAPSED, anchor.startedAtElapsedRealtime)
            putInt(KEY_BREAK_BOOT_COUNT, boot)
        }
        return anchor.startedAtElapsedRealtime
    }

    fun clearBreak(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_BREAK_ID)
            remove(KEY_BREAK_START_ELAPSED)
            remove(KEY_BREAK_BOOT_COUNT)
        }
    }

    fun clearSession(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }
}
