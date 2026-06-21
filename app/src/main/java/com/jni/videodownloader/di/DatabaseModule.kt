package com.jni.videodownloader.di

import android.content.Context
import androidx.room.Room
import com.jni.videodownloader.data.db.AppDatabase
import com.jni.videodownloader.data.db.DownloadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "downloads.db").build()

    @Provides
    fun dao(db: AppDatabase): DownloadDao = db.downloadDao()
}
