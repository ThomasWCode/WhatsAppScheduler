package com.thomaswcode.whatsappscheduler.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY CASE WHEN nextAtMillis IS NULL THEN 1 ELSE 0 END, nextAtMillis ASC")
    fun observeAll(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules")
    suspend fun getAll(): List<Schedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): Schedule?

    @Query("SELECT * FROM schedules WHERE id = :id")
    fun observeById(id: Long): Flow<Schedule?>

    @Insert
    suspend fun insert(schedule: Schedule): Long

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)
}

@Dao
interface SendLogDao {
    @Query("SELECT * FROM send_log ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SendLog>>

    @Insert
    suspend fun insert(log: SendLog)

    @Query("DELETE FROM send_log WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
