package com.jni.videodownloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert
    fun insert(e: DownloadEntity): Long

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getById(id: Long): DownloadEntity?

    @Query("UPDATE downloads SET progress = :progress, status = :status WHERE id = :id")
    fun updateProgress(id: Long, progress: Int, status: String)

    @Query("UPDATE downloads SET status = :status, file_path = :filePath, progress = 100 WHERE id = :id")
    fun setCompleted(id: Long, status: String, filePath: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    fun setStatus(id: Long, status: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    fun delete(id: Long)
}
