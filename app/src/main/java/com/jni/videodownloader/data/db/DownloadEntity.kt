package com.jni.videodownloader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "thumbnail") val thumbnail: String?,
    @ColumnInfo(name = "file_path") val filePath: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
