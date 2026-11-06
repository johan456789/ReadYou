package me.ash.reader.domain.model.insights

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * One foreground span of the app. Persisted only once cleanly ended (on
 * background); a process death mid-session loses the row, which is acceptable
 * because DWELL events carry the real reading signal — sessions feed the
 * session-budget prior ("how long do sessions at this hour usually last").
 */
@Entity(
    tableName = "reading_session",
    indices = [Index("accountId", "startedAt")],
)
data class ReadingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Int,
    val startedAt: Date,
    val endedAt: Date,
    val durationMs: Long,
    val entryPoint: Int? = null,
)

object SessionEntryPoint {
    const val UNKNOWN = 0
    const val COLD_START = 1
}
