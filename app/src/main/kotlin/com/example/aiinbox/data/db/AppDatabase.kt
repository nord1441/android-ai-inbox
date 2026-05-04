package com.example.aiinbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [InboxItem::class, Attachment::class, SyncStateEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(DbTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun syncStateDao(): SyncStateDao
}
