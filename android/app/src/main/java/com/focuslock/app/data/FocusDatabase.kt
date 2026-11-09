package com.focuslock.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "focus_sessions", indices = [Index(value = ["startTime"]), Index(value = ["sessionStatus"])])
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val plannedEndTime: Long,
    val durationMillis: Long,
    val actualEndTime: Long? = null,
    val sessionStatus: String = "ACTIVE",
    val selectedLockMode: String = "STANDARD",
    val breakEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** Legacy Room table retained for on-disk schema compatibility; production settings are stored in DataStore. */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val language: String = "en",
    val theme: String = "dark",
    val defaultFocusDuration: Long = 3600000L,
    val breakEnabled: Boolean = true,
    val breakDuration: Long = 600000L,
    val callMode: String = "DISABLED",
    val autoReplyEnabled: Boolean = false,
    val notificationMode: String = "DEFAULT",
    val lockMode: String = "STANDARD",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "break_sessions",
    foreignKeys = [ForeignKey(entity = FocusSessionEntity::class, parentColumns = ["id"], childColumns = ["focusSessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("focusSessionId"), Index("startTime")]
)
data class BreakSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val focusSessionId: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMillis: Long = 600000L,
    val breakStatus: String = "PLANNED"
)

@Entity(tableName = "allowed_contacts", indices = [Index(value = ["normalizedPhoneNumber"], unique = true)])
data class AllowedContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: String,
    val displayName: String,
    val normalizedPhoneNumber: String,
    val isAllowed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notification_records", indices = [Index("receivedAt"), Index("isRead")])
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val title: String?,
    val content: String?,
    val receivedAt: Long,
    val isRead: Boolean = false,
    // Legacy soft-delete flag retained for schema compatibility; deletion is now physical SQL DELETE.
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "session_events",
    foreignKeys = [ForeignKey(entity = FocusSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("eventTimestamp"), Index("eventType")]
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val eventType: String,
    val eventTimestamp: Long,
    val description: String? = null
)

@Dao
interface FocusSessionDao {
    @Insert
    suspend fun insert(session: FocusSessionEntity): Long

    @Query("UPDATE focus_sessions SET actualEndTime = :endedAt, sessionStatus = :status WHERE id = :id AND sessionStatus IN ('ACTIVE', 'BREAK_ACTIVE', 'RECOVERY_REQUIRED')")
    suspend fun finish(id: Long, endedAt: Long, status: String): Int

    @Query("SELECT * FROM focus_sessions WHERE sessionStatus IN ('ACTIVE', 'BREAK_ACTIVE', 'RECOVERY_REQUIRED') ORDER BY id DESC LIMIT 1")
    suspend fun activeSession(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC")
    fun observeRecent(): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE sessionStatus IN ('ACTIVE', 'BREAK_ACTIVE', 'RECOVERY_REQUIRED') ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE startTime >= :from AND startTime < :to AND sessionStatus = 'COMPLETED'")
    suspend fun completedBetween(from: Long, to: Long): List<FocusSessionEntity>

    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM focus_sessions WHERE sessionStatus = 'COMPLETED' AND startTime >= :from AND startTime < :to")
    suspend fun totalFocusBetween(from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE sessionStatus = :status AND startTime >= :from AND startTime < :to")
    suspend fun countByStatusBetween(status: String, from: Long, to: Long): Int

    @Query("UPDATE focus_sessions SET sessionStatus = :status WHERE id = :id AND sessionStatus = :expected")
    suspend fun setStatus(id: Long, expected: String, status: String): Int

    @Query("DELETE FROM focus_sessions")
    suspend fun deleteAll()
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun observe(): Flow<UserSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: UserSettingsEntity)

    @Query("DELETE FROM user_settings")
    suspend fun deleteAll()
}

@Dao
interface BreakSessionDao {
    @Insert
    suspend fun insert(entity: BreakSessionEntity): Long

    @Update
    suspend fun update(entity: BreakSessionEntity)

    @Query("SELECT * FROM break_sessions WHERE focusSessionId = :sessionId ORDER BY startTime")
    fun observeForSession(sessionId: Long): Flow<List<BreakSessionEntity>>

    @Query("SELECT * FROM break_sessions WHERE focusSessionId = :sessionId ORDER BY startTime")
    suspend fun forSession(sessionId: Long): List<BreakSessionEntity>

    @Query("SELECT * FROM break_sessions WHERE focusSessionId = :sessionId AND breakStatus = 'ACTIVE' LIMIT 1")
    suspend fun activeForSession(sessionId: Long): BreakSessionEntity?

    @Query("SELECT * FROM break_sessions WHERE focusSessionId = :sessionId AND breakStatus = 'COMPLETED' ORDER BY startTime")
    suspend fun completedForSession(sessionId: Long): List<BreakSessionEntity>

    @Query("DELETE FROM break_sessions WHERE focusSessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface AllowedContactDao {
    @Query("SELECT * FROM allowed_contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<AllowedContactEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: AllowedContactEntity): Long

    @Update
    suspend fun update(contact: AllowedContactEntity)

    @Query("SELECT * FROM allowed_contacts WHERE normalizedPhoneNumber = :number LIMIT 1")
    suspend fun findByNormalizedNumber(number: String): AllowedContactEntity?

    @Query("SELECT normalizedPhoneNumber FROM allowed_contacts WHERE isAllowed = 1")
    suspend fun allNumbers(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM allowed_contacts WHERE normalizedPhoneNumber = :number AND isAllowed = 1)")
    suspend fun isAllowedNumber(number: String): Boolean

    @Query("DELETE FROM allowed_contacts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM allowed_contacts")
    suspend fun deleteAll()
}

@Dao
interface NotificationRecordDao {
    @Insert
    suspend fun insert(record: NotificationRecordEntity): Long

    @Query("SELECT * FROM notification_records WHERE isDeleted = 0 ORDER BY receivedAt DESC")
    fun observeVisible(): Flow<List<NotificationRecordEntity>>

    // Retained for the legacy database instrumented test; no production UI marks records read.
    @Query("UPDATE notification_records SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM notification_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM notification_records WHERE id NOT IN (SELECT id FROM notification_records ORDER BY receivedAt DESC, id DESC LIMIT :keep)")
    suspend fun pruneToLimit(keep: Int): Int

    @Query("DELETE FROM notification_records")
    suspend fun deleteAll()
}

@Dao
interface SessionEventDao {
    @Insert
    suspend fun insert(event: SessionEventEntity): Long

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY eventTimestamp")
    fun observeForSession(sessionId: Long): Flow<List<SessionEventEntity>>

    @Query("SELECT * FROM session_events ORDER BY eventTimestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SessionEventEntity>>

    @Query("DELETE FROM session_events")
    suspend fun deleteAll()
}

@Database(
    entities = [
        FocusSessionEntity::class, UserSettingsEntity::class, BreakSessionEntity::class,
        AllowedContactEntity::class, NotificationRecordEntity::class, SessionEventEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun sessions(): FocusSessionDao
    abstract fun userSettings(): UserSettingsDao
    abstract fun breaks(): BreakSessionDao
    abstract fun allowedContacts(): AllowedContactDao
    abstract fun notifications(): NotificationRecordDao
    abstract fun events(): SessionEventDao

    companion object {
        @Volatile private var INSTANCE: FocusDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_sessions_startTime ON focus_sessions(startTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_sessions_sessionStatus ON focus_sessions(sessionStatus)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS user_settings (id INTEGER NOT NULL, language TEXT NOT NULL, theme TEXT NOT NULL, defaultFocusDuration INTEGER NOT NULL, breakEnabled INTEGER NOT NULL, breakDuration INTEGER NOT NULL, callMode TEXT NOT NULL, autoReplyEnabled INTEGER NOT NULL, notificationMode TEXT NOT NULL, lockMode TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS break_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, focusSessionId INTEGER NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER, durationMillis INTEGER NOT NULL, breakStatus TEXT NOT NULL, FOREIGN KEY(focusSessionId) REFERENCES focus_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_break_sessions_focusSessionId ON break_sessions(focusSessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_break_sessions_startTime ON break_sessions(startTime)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS allowed_contacts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, contactId TEXT NOT NULL, displayName TEXT NOT NULL, normalizedPhoneNumber TEXT NOT NULL, isAllowed INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_allowed_contacts_normalizedPhoneNumber ON allowed_contacts(normalizedPhoneNumber)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS notification_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourcePackage TEXT NOT NULL, title TEXT, content TEXT, receivedAt INTEGER NOT NULL, isRead INTEGER NOT NULL, isDeleted INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_receivedAt ON notification_records(receivedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_isRead ON notification_records(isRead)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS session_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, eventType TEXT NOT NULL, eventTimestamp INTEGER NOT NULL, description TEXT, FOREIGN KEY(sessionId) REFERENCES focus_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_events_sessionId ON session_events(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_events_eventTimestamp ON session_events(eventTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_events_eventType ON session_events(eventType)")
            }
        }

        fun get(context: Context): FocusDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context, FocusDatabase::class.java, "focuslock.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
        }
    }
}
