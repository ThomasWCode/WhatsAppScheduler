package com.thomaswcode.whatsappscheduler.data.db

import android.content.Context
import androidx.room.AutoMigration
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

    @TypeConverter fun attentionToString(attention: Attention?): String? = attention?.name

    /** Lenient like [stringToStatus]; an unknown reason reads as HELD, so nothing is sent until the user looks. */
    @TypeConverter fun stringToAttention(value: String?): Attention? =
        value?.let { name -> Attention.entries.firstOrNull { it.name == name } ?: Attention.HELD }
}

/**
 * Every schema change ships with a migration (T1): a missing one would crash the app on every
 * launch. Version 2 adds recipients, batches, postponing, attention and typed statuses, and lets
 * send history outlive its schedule.
 */
@Database(
    entities = [Schedule::class, SendLog::class, Recipient::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun sendLogDao(): SendLogDao
    abstract fun recipientDao(): RecipientDao

    companion object {
        const val NAME = "scheduler.db"

        /** Builds the single on-device database. */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
