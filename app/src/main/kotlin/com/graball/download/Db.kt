package com.graball.download

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Plain string constants -- simplest status representation, no TypeConverter needed. */
object Status {
    const val QUEUED = "QUEUED"
    const val RESOLVING = "RESOLVING"
    const val RUNNING = "RUNNING"
    const val MUXING = "MUXING"
    const val MOVING = "MOVING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    /** User-initiated hold: not active, not stale-resettable, not picked up by nextQueued(). */
    const val PAUSED = "PAUSED"

    /** Keep in sync with the SQL literals in DownloadDao's active-state queries. */
    val ACTIVE = listOf(RUNNING, MUXING, MOVING, RESOLVING)
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val formatId: String,
    val needsMux: Boolean,
    val ext: String,
    val status: String = Status.QUEUED,
    val progressPct: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val etaSec: Long = 0,
    val fragIndex: Int = 0,
    val fragCount: Int = 0,
    val errorClass: String? = null,
    val rawLog: String? = null,
    val mediaUri: String? = null,
    val engineVersion: String? = null,
    /** Host to export WebView cookies for at download time; null = no cookies. Never holds a value. */
    val cookieDomain: String? = null,
    val createdAt: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    // keep in sync with Status.ACTIVE
    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('RUNNING','MUXING','MOVING','RESOLVING')")
    suspend fun countActive(): Int

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun countAll(): Int

    // crash recovery: MOVING is recoverable per spec, just requeue and redo the move
    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status IN ('RUNNING','MUXING','MOVING','RESOLVING')")
    suspend fun resetStale()
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class GraballDb : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var instance: GraballDb? = null

        fun getInstance(context: Context): GraballDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GraballDb::class.java,
                    "graball.db",
                ).build().also { instance = it }
            }
    }
}
