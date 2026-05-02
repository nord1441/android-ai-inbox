package com.example.aiinbox.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class DbTypeConverters {
    private val json = Json { encodeDefaults = true }

    @TypeConverter
    fun stringListToJson(list: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), list)

    @TypeConverter
    fun stringListFromJson(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList()
        else json.decodeFromString(ListSerializer(String.serializer()), s)

    @TypeConverter
    fun stringSetToJson(set: Set<String>): String =
        json.encodeToString(SetSerializer(String.serializer()), set)

    @TypeConverter
    fun stringSetFromJson(s: String?): Set<String> =
        if (s.isNullOrBlank()) emptySet()
        else json.decodeFromString(SetSerializer(String.serializer()), s)

    @TypeConverter
    fun itemStatusToString(s: ItemStatus): String = s.name

    @TypeConverter
    fun itemStatusFromString(s: String): ItemStatus = ItemStatus.valueOf(s)
}
