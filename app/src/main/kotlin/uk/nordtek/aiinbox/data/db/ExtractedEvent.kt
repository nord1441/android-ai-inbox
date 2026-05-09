package uk.nordtek.aiinbox.data.db

import androidx.room.ColumnInfo

data class ExtractedEvent(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "start_millis") val startMillis: Long?,
    @ColumnInfo(name = "end_millis") val endMillis: Long?,
    @ColumnInfo(name = "location") val location: String?,
    @ColumnInfo(name = "confidence") val confidence: Float,
)
