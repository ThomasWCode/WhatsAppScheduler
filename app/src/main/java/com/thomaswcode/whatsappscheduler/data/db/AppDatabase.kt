package com.thomaswcode.whatsappscheduler.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.thomaswcode.whatsappscheduler.domain.TargetKind

class Converters {
    @TypeConverter fun targetKindToString(kind: TargetKind): String = kind.name
    @TypeConverter fun stringToTargetKind(value: String): TargetKind = TargetKind.valueOf(value)
    @TypeConverter fun statusToString(status: SendStatus): String = status.name

    /** Lenient, so a row written by a newer version (after a downgrade) reads as FAILED instead of crashing every launch. */
    @TypeConverter fun stringToStatus(value: String): SendStatus =
        SendStatus.entries.firstOrNull { it.name == value } ?: SendStatus.FAILED
}

@Database(entities = [Schedule::class, SendLog::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun sendLogDao(): SendLogDao

    companion object {
        /** Builds the single on-device database. */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scheduler.db").build()
    }
}
