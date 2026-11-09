package com.focuslock.app.data

import androidx.room.withTransaction
import com.focuslock.app.domain.PhoneNumberNormalizer
import kotlinx.coroutines.flow.Flow

class FocusRepository(private val database: FocusDatabase) {
    private companion object { const val MAX_SAVED_NOTIFICATIONS = 500 }

    val recentSessions: Flow<List<FocusSessionEntity>> = database.sessions().observeRecent()
    val activeSessionStatus: Flow<FocusSessionEntity?> = database.sessions().observeActive()
    val allowedContacts: Flow<List<AllowedContactEntity>> = database.allowedContacts().observeAll()
    val notifications: Flow<List<NotificationRecordEntity>> = database.notifications().observeVisible()

    suspend fun activeSession(): FocusSessionEntity? = database.sessions().activeSession()

    suspend fun isAllowedContactNumber(normalized: String): Boolean =
        database.allowedContacts().allNumbers().any { PhoneNumberNormalizer.equivalent(it, normalized) }
    suspend fun activeBreak(sessionId: Long): BreakSessionEntity? = database.breaks().activeForSession(sessionId)
    suspend fun breaksForSession(sessionId: Long): List<BreakSessionEntity> = database.breaks().forSession(sessionId)

    suspend fun startSession(session: FocusSessionEntity): Long = database.withTransaction {
        check(database.sessions().activeSession() == null) { "An active focus session already exists." }
        val id = database.sessions().insert(session)
        database.events().insert(SessionEventEntity(sessionId = id, eventType = "SESSION_STARTED", eventTimestamp = session.startTime, description = "Focus session started"))
        id
    }

    /** Atomically creates at most one break at each earned focus boundary. */
    suspend fun startBreakIfDue(
        sessionId: Long,
        now: Long,
        breakDurationMillis: Long,
        focusElapsedMillis: Long? = null
    ): BreakSessionEntity? = database.withTransaction {
            val session = database.sessions().activeSession()?.takeIf { it.id == sessionId } ?: return@withTransaction null
            if (!session.breakEnabled || session.sessionStatus != "ACTIVE") return@withTransaction null
            if (database.breaks().activeForSession(sessionId) != null) return@withTransaction null
            val completed = database.breaks().completedForSession(sessionId)
            val focusElapsed = (focusElapsedMillis ?: (now - session.startTime - completed.sumOf { it.durationMillis }))
                .coerceAtLeast(0L)
            val boundary = com.focuslock.app.domain.BreakPolicy.breakAtElapsedMillis(completed.size)
            if (focusElapsed < boundary || focusElapsed >= session.durationMillis) return@withTransaction null
            val breakId = database.breaks().insert(
                BreakSessionEntity(focusSessionId = sessionId, startTime = now,
                    endTime = now + breakDurationMillis, durationMillis = breakDurationMillis, breakStatus = "ACTIVE")
            )
            check(database.sessions().setStatus(sessionId, "ACTIVE", "BREAK_ACTIVE") == 1)
            database.events().insert(SessionEventEntity(sessionId = sessionId, eventType = "BREAK_STARTED", eventTimestamp = now, description = "Smart break started"))
            BreakSessionEntity(breakId, sessionId, now, now + breakDurationMillis, breakDurationMillis, "ACTIVE")
        }

    /** Completes only the currently active break; safe against duplicate service ticks. */
    suspend fun completeActiveBreak(
        sessionId: Long,
        now: Long,
        actualDurationMillis: Long? = null
    ): Boolean = database.withTransaction {
        val session = database.sessions().activeSession()?.takeIf { it.id == sessionId } ?: return@withTransaction false
        if (session.sessionStatus != "BREAK_ACTIVE") return@withTransaction false
        val activeBreak = database.breaks().activeForSession(sessionId) ?: return@withTransaction false
        if (actualDurationMillis == null && (activeBreak.endTime ?: Long.MAX_VALUE) > now) return@withTransaction false
        if (actualDurationMillis != null && actualDurationMillis < activeBreak.durationMillis) return@withTransaction false
        database.breaks().update(activeBreak.copy(
            endTime = now,
            durationMillis = (actualDurationMillis ?: activeBreak.durationMillis).coerceAtLeast(0L),
            breakStatus = "COMPLETED"
        ))
        check(database.sessions().setStatus(sessionId, "BREAK_ACTIVE", "ACTIVE") == 1)
        database.events().insert(SessionEventEntity(sessionId = sessionId, eventType = "BREAK_COMPLETED", eventTimestamp = now, description = "Smart break completed"))
        true
    }

    suspend fun finishSession(
        id: Long,
        endedAt: Long,
        status: String,
        activeBreakDurationMillis: Long? = null
    ): Boolean = database.withTransaction {
        require(status in setOf("COMPLETED", "INTERRUPTED", "RECOVERY_REQUIRED"))
        val changed = database.sessions().finish(id, endedAt, status)
        if (changed == 0) return@withTransaction false
        val activeBreak = database.breaks().activeForSession(id)
        if (activeBreak != null) {
            database.breaks().update(activeBreak.copy(
                endTime = endedAt,
                durationMillis = (activeBreakDurationMillis ?: (endedAt - activeBreak.startTime))
                    .coerceAtLeast(0L),
                breakStatus = "INTERRUPTED"
            ))
            database.events().insert(SessionEventEntity(
                sessionId = id, eventType = "BREAK_INTERRUPTED",
                eventTimestamp = endedAt, description = "Break interrupted because the focus session ended"
            ))
        }
        database.events().insert(SessionEventEntity(sessionId = id, eventType = "SESSION_$status", eventTimestamp = endedAt, description = "Focus session $status"))
        true
    }

    fun observeBreaks(sessionId: Long): Flow<List<BreakSessionEntity>> = database.breaks().observeForSession(sessionId)
    suspend fun addAllowedContact(contact: AllowedContactEntity): Long = database.allowedContacts().insert(contact)
    suspend fun deleteAllowedContact(id: Long) = database.allowedContacts().delete(id)
    suspend fun saveNotification(record: NotificationRecordEntity): Long = database.withTransaction {
        val id = database.notifications().insert(record)
        database.notifications().pruneToLimit(MAX_SAVED_NOTIFICATIONS)
        id
    }
    /** Permanently removes the notification row and its stored title/content. */
    suspend fun deleteNotification(id: Long): Boolean =
        database.notifications().deleteById(id) == 1
    suspend fun deleteAllSessions() = database.sessions().deleteAll()
    /** Removes all user-owned Room data, including the retained legacy settings table. */
    suspend fun clearUserData() = database.withTransaction {
        // Foreign-key CASCADE removes dependent breaks and session events with their sessions.
        database.sessions().deleteAll()
        database.allowedContacts().deleteAll()
        database.notifications().deleteAll()
        database.events().deleteAll()
        database.userSettings().deleteAll()
    }
}
