package com.chargeguard.data.history

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history_events", indices = [Index("timestamp")])
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val timestamp: Long,
    val detail: String?,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(event: HistoryEventEntity): Long

    @Query("SELECT * FROM history_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<HistoryEventEntity>>

    @Query("DELETE FROM history_events WHERE id NOT IN (SELECT id FROM history_events ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM history_events")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM history_events")
    suspend fun count(): Int
}

@Database(entities = [HistoryEventEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
