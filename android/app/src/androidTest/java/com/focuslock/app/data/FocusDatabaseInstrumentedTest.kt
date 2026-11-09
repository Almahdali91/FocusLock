package com.focuslock.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class FocusDatabaseInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun repositorySessionLifecycleAndAuditEventsAreAtomic() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val repository = FocusRepository(db)
            val now = 1_800_000_000_000L
            val sessionId = repository.startSession(
                FocusSessionEntity(
                    startTime = now,
                    plannedEndTime = now + 3_600_000,
                    durationMillis = 3_600_000
                )
            )
            assertNotNull(repository.activeSession())
            assertEquals(1, db.events().observeRecent().first().size)

            var duplicateRejected = false
            try {
                repository.startSession(
                    FocusSessionEntity(
                        startTime = now + 1,
                        plannedEndTime = now + 60_001,
                        durationMillis = 60_000
                    )
                )
            } catch (_: IllegalStateException) {
                duplicateRejected = true
            }
            assertTrue("A second active session must be rejected", duplicateRejected)

            val firstFinish = repository.finishSession(sessionId, now + 3_600_000, "COMPLETED")
            val duplicateFinish = repository.finishSession(sessionId, now + 3_600_001, "COMPLETED")
            assertTrue("The first completion must update the active session", firstFinish)
            assertFalse("A terminal session must not be completed twice", duplicateFinish)
            assertNull(repository.activeSession())
            val savedSession = db.sessions().completedBetween(0, Long.MAX_VALUE).single()
            assertEquals("COMPLETED", savedSession.sessionStatus)
            assertEquals(now + 3_600_000, savedSession.actualEndTime)
            val terminalEvents = db.events().observeRecent().first()
                .filter { it.sessionId == sessionId && it.eventType == "SESSION_COMPLETED" }
            assertEquals("Exactly one terminal audit event must be stored", 1, terminalEvents.size)
            assertEquals(2, db.events().observeRecent().first().size)
            assertEquals(3_600_000L, db.sessions().totalFocusBetween(0, Long.MAX_VALUE))
        } finally {
            db.close()
        }
    }

    @Test
    fun foreignKeysCascadeAndContactUniquenessAreEnforced() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val sessionId = db.sessions().insert(
                FocusSessionEntity(
                    startTime = 1000,
                    plannedEndTime = 61000,
                    durationMillis = 60000
                )
            )
            db.breaks().insert(BreakSessionEntity(focusSessionId = sessionId, startTime = 61000))
            db.events().insert(
                SessionEventEntity(sessionId = sessionId, eventType = "TEST", eventTimestamp = 61000)
            )
            db.sessions().deleteAll()
            assertTrue(db.breaks().forSession(sessionId).isEmpty())
            assertTrue(db.events().observeForSession(sessionId).first().isEmpty())

            db.allowedContacts().insert(
                AllowedContactEntity(contactId = "a", displayName = "A", normalizedPhoneNumber = "+15550001")
            )
            var duplicateRejected = false
            try {
                db.allowedContacts().insert(
                    AllowedContactEntity(contactId = "b", displayName = "B", normalizedPhoneNumber = "+15550001")
                )
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                duplicateRejected = true
            }
            assertTrue("Duplicate normalized phone numbers must be rejected", duplicateRejected)
        } finally {
            db.close()
        }
    }

    @Test
    fun settingsBreakContactAndNotificationCrudWorks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        val repository = FocusRepository(db)
        try {
            val settings = UserSettingsEntity(language = "ar", theme = "dark")
            db.userSettings().upsert(settings)
            assertEquals("ar", db.userSettings().observe().first()!!.language)

            val sessionId = db.sessions().insert(
                FocusSessionEntity(startTime = 1000, plannedEndTime = 61000, durationMillis = 60000)
            )
            val breakId = db.breaks().insert(
                BreakSessionEntity(focusSessionId = sessionId, startTime = 61000)
            )
            val savedBreak = db.breaks().forSession(sessionId).single()
            db.breaks().update(savedBreak.copy(breakStatus = "COMPLETED", endTime = 121000))
            assertEquals("COMPLETED", db.breaks().forSession(sessionId).single().breakStatus)
            assertTrue(breakId > 0)

            val contactId = db.allowedContacts().insert(
                AllowedContactEntity(contactId = "contact-1", displayName = "Before", normalizedPhoneNumber = "+15550002")
            )
            val contact = db.allowedContacts().observeAll().first().single()
            db.allowedContacts().update(contact.copy(displayName = "After"))
            assertEquals("After", db.allowedContacts().observeAll().first().single().displayName)
            db.allowedContacts().delete(contactId)
            assertTrue(db.allowedContacts().observeAll().first().isEmpty())

            val notificationId = db.notifications().insert(
                NotificationRecordEntity(sourcePackage = "example.app", title = "Title", content = "Body", receivedAt = 2000)
            )
            db.notifications().markRead(notificationId)
            assertTrue(db.notifications().observeVisible().first().single().isRead)
            assertTrue(repository.deleteNotification(notificationId))
            assertTrue(db.notifications().observeVisible().first().isEmpty())
            db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM notification_records WHERE id = ?",
                arrayOf(notificationId)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Deleted notification content must no longer exist in SQLite", 0, cursor.getInt(0))
            }
            assertFalse("Deleting the same notification twice must report no row", repository.deleteNotification(notificationId))
        } finally {
            db.close()
        }
    }


    @Test
    fun smartBreakTransitionsAreAtomicAndDoNotFinishFocusSession() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val repository = FocusRepository(db)
            val start = 1_800_000_000_000L
            val duration = 4 * 60 * 60 * 1000L
            val sessionId = repository.startSession(
                FocusSessionEntity(startTime = start, plannedEndTime = start + duration,
                    durationMillis = duration, breakEnabled = true)
            )

            val firstBreak = repository.startBreakIfDue(sessionId, start + 2 * 60 * 60 * 1000L, 600_000L)
            assertNotNull("First break must be earned after two focus hours", firstBreak)
            assertEquals("BREAK_ACTIVE", repository.activeSession()!!.sessionStatus)
            assertNull("A concurrent/repeated start must not create another break",
                repository.startBreakIfDue(sessionId, start + 2 * 60 * 60 * 1000L, 600_000L))
            assertEquals(1, repository.breaksForSession(sessionId).size)

            assertTrue(repository.completeActiveBreak(sessionId, start + 2 * 60 * 60 * 1000L + 600_000L))
            assertFalse("A completed break cannot be completed twice",
                repository.completeActiveBreak(sessionId, start + 2 * 60 * 60 * 1000L + 600_001L))
            assertEquals("ACTIVE", repository.activeSession()!!.sessionStatus)

            val secondBreak = repository.startBreakIfDue(sessionId,
                start + 3 * 60 * 60 * 1000L + 600_000L, 600_000L)
            assertNotNull("Second break must be earned after the next focus hour", secondBreak)
            assertEquals(2, repository.breaksForSession(sessionId).size)
            assertEquals("ACTIVE", repository.activeSession()!!.copy(sessionStatus = "ACTIVE").sessionStatus)

            val events = db.events().observeRecent().first()
            assertEquals(2, events.count { it.eventType == "BREAK_STARTED" })
            assertEquals(1, events.count { it.eventType == "BREAK_COMPLETED" })
            assertTrue("Focus session remains active during and between breaks",
                repository.activeSession()!!.sessionStatus == "BREAK_ACTIVE")
        } finally {
            db.close()
        }
    }

    @Test
    fun breakSchedulingAcceptsMonotonicFocusElapsedIndependentOfWallClock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val repository = FocusRepository(db)
            val start = 1_800_000_000_000L
            val sessionId = repository.startSession(
                FocusSessionEntity(
                    startTime = start,
                    plannedEndTime = start + 4 * 60 * 60 * 1000L,
                    durationMillis = 4 * 60 * 60 * 1000L,
                    breakEnabled = true
                )
            )

            // Simulate a wall-clock jump backwards while monotonic focus time reaches two hours.
            val startedBreak = repository.startBreakIfDue(
                sessionId = sessionId,
                now = start - 30_000L,
                breakDurationMillis = 600_000L,
                focusElapsedMillis = 2 * 60 * 60 * 1000L
            )
            assertNotNull("Break scheduling must use monotonic focus elapsed time", startedBreak)
            assertEquals("BREAK_ACTIVE", repository.activeSession()!!.sessionStatus)

            val actualBreakDuration = 660_000L
            assertTrue(repository.completeActiveBreak(
                sessionId,
                now = start + 2 * 60 * 60 * 1000L + actualBreakDuration,
                actualDurationMillis = actualBreakDuration
            ))
            assertEquals(
                actualBreakDuration,
                repository.breaksForSession(sessionId).single().durationMillis
            )
            assertEquals("ACTIVE", repository.activeSession()!!.sessionStatus)
        } finally {
            db.close()
        }
    }

    @Test
    fun interruptedSessionClosesActiveBreakAndPersistsSingleTerminalEvent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val repository = FocusRepository(db)
            val start = 1_800_000_000_000L
            val sessionId = repository.startSession(FocusSessionEntity(startTime = start, plannedEndTime = start + 14_400_000L, durationMillis = 14_400_000L, breakEnabled = true))
            val activeBreak = repository.startBreakIfDue(sessionId, start + 7_200_000L, 600_000L, 7_200_000L)
            assertNotNull(activeBreak)
            assertTrue(repository.finishSession(sessionId, start + 7_500_000L, "INTERRUPTED"))
            assertFalse(repository.finishSession(sessionId, start + 7_600_000L, "INTERRUPTED"))
            assertNull(repository.activeSession())
            val savedBreak = repository.breaksForSession(sessionId).single()
            assertEquals("INTERRUPTED", savedBreak.breakStatus)
            assertEquals(start + 7_500_000L, savedBreak.endTime)
            assertEquals(300_000L, savedBreak.durationMillis)
            val events = db.events().observeRecent().first().filter { it.sessionId == sessionId }
            assertEquals(1, events.count { it.eventType == "SESSION_INTERRUPTED" })
            assertEquals(1, events.count { it.eventType == "BREAK_INTERRUPTED" })
        } finally { db.close() }
    }
    @Test
    fun migrationOneToTwoPreservesExistingSessions() = runBlocking {
        val name = "focuslock-migration-test.db"
        context.deleteDatabase(name)
        val legacyFile = context.getDatabasePath(name)
        legacyFile.parentFile?.mkdirs()
        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacyFile, null)
        legacy.execSQL("""CREATE TABLE focus_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            startTime INTEGER NOT NULL,
            plannedEndTime INTEGER NOT NULL,
            durationMillis INTEGER NOT NULL,
            actualEndTime INTEGER,
            sessionStatus TEXT NOT NULL,
            selectedLockMode TEXT NOT NULL,
            breakEnabled INTEGER NOT NULL,
            createdAt INTEGER NOT NULL
        )""")
        legacy.execSQL("""CREATE INDEX index_focus_sessions_startTime ON focus_sessions(startTime)""")
        legacy.execSQL("""CREATE INDEX index_focus_sessions_sessionStatus ON focus_sessions(sessionStatus)""")
        legacy.execSQL("""INSERT INTO focus_sessions
            (id,startTime,plannedEndTime,durationMillis,actualEndTime,sessionStatus,selectedLockMode,breakEnabled,createdAt)
            VALUES (42,1000,61000,60000,NULL,'ACTIVE','STANDARD',1,1000)""")
        legacy.version = 1
        legacy.close()

        val migratedDb = Room.databaseBuilder(context, FocusDatabase::class.java, name)
            .addMigrations(FocusDatabase.MIGRATION_1_2)
            .build()
        try {
            val preserved = migratedDb.sessions().activeSession()
            assertNotNull(preserved)
            assertEquals(42L, preserved!!.id)
            assertEquals("ACTIVE", preserved.sessionStatus)

            val tableNames = mutableSetOf<String>()
            migratedDb.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).use { cursor ->
                while (cursor.moveToNext()) tableNames.add(cursor.getString(0))
            }
            assertTrue(tableNames.contains("break_sessions"))
            assertTrue(tableNames.contains("user_settings"))
            assertTrue(tableNames.contains("allowed_contacts"))
            assertTrue(tableNames.contains("notification_records"))
            assertTrue(tableNames.contains("session_events"))
        } finally {
            migratedDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun clearUserDataRemovesLegacySettingsAndAllUserOwnedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val repository = FocusRepository(db)
            db.userSettings().upsert(UserSettingsEntity(language = "ar", theme = "light"))
            val sessionId = db.sessions().insert(
                FocusSessionEntity(startTime = 1_000, plannedEndTime = 61_000, durationMillis = 60_000)
            )
            db.breaks().insert(BreakSessionEntity(focusSessionId = sessionId, startTime = 61_000))
            db.events().insert(SessionEventEntity(sessionId = sessionId, eventType = "TEST", eventTimestamp = 1_000))
            db.allowedContacts().insert(
                AllowedContactEntity(contactId = "wipe-test", displayName = "Test", normalizedPhoneNumber = "+15550123")
            )
            db.notifications().insert(
                NotificationRecordEntity(sourcePackage = "wipe.test", title = "Sensitive title", content = "Sensitive body", receivedAt = 1_000)
            )

            repository.clearUserData()

            fun rowCount(table: String): Int = db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM $table"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals("Legacy settings must also be erased", 0, rowCount("user_settings"))
            assertEquals(0, rowCount("focus_sessions"))
            assertEquals(0, rowCount("break_sessions"))
            assertEquals(0, rowCount("session_events"))
            assertEquals(0, rowCount("allowed_contacts"))
            assertEquals(0, rowCount("notification_records"))
        } finally {
            db.close()
        }
    }

    @Test
    fun notificationRepositoryKeepsOnlyLatestFiveHundredRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FocusDatabase::class.java).build()
        try {
            val repository = FocusRepository(db)
            repeat(505) { index ->
                repository.saveNotification(
                    NotificationRecordEntity(
                        sourcePackage = "test.app",
                        title = "Title $index",
                        content = "Body",
                        receivedAt = index.toLong()
                    )
                )
            }
            val saved = db.notifications().observeVisible().first()
            assertEquals(500, saved.size)
            assertEquals(504L, saved.first().receivedAt)
            assertEquals(5L, saved.last().receivedAt)
        } finally {
            db.close()
        }
    }

}


