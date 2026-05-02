package com.example.aiinbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [InboxItem::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DbTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
}
