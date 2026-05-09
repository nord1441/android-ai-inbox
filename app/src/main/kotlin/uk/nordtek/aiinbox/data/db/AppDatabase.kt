package uk.nordtek.aiinbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [InboxItem::class, Attachment::class, FsSyncStateEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(DbTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun fsSyncStateDao(): FsSyncStateDao
}
