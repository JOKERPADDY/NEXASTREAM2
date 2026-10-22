package com.nexastream.app.database.dao

import androidx.room.*
import com.nexastream.app.models.Download
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE id NOT LIKE 'local_%' ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE id NOT LIKE 'local_%' ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<Download>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): Download?

    @Query("SELECT * FROM downloads WHERE url = :url")
    suspend fun getByUrl(url: String): Download?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: Download)

    @Update
    suspend fun update(download: Download)

    @Delete
    suspend fun delete(download: Download)

    @Query("UPDATE downloads SET status = :status, progress = :progress, downloadedSize = :downloadedSize, totalSize = :totalSize, downloadSpeed = :downloadSpeed, etaSeconds = :etaSeconds, waitingReason = :waitingReason, errorMessage = NULL WHERE id = :id AND (status != 'COMPLETED' OR :status = 'COMPLETED')")
    suspend fun updateProgress(
        id: String,
        status: Download.Status,
        progress: Int,
        downloadedSize: Long,
        totalSize: Long,
        downloadSpeed: Long,
        etaSeconds: Long?,
        waitingReason: Int = 0
    )

    @Query("UPDATE downloads SET status = 'FAILED', downloadSpeed = 0, etaSeconds = NULL, waitingReason = 0, errorMessage = :errorMessage WHERE id = :id AND status != 'COMPLETED'")
    suspend fun updateFailure(id: String, errorMessage: String?)

    @Query("UPDATE downloads SET status = :status, downloadSpeed = 0, etaSeconds = NULL, waitingReason = 0, errorMessage = NULL WHERE id = :id AND status != 'COMPLETED'")
    suspend fun updateStatus(id: String, status: Download.Status)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE downloads SET status = 'FAILED', downloadSpeed = 0, etaSeconds = NULL WHERE status = 'DOWNLOADING'")
    suspend fun resetActiveDownloads()
}
