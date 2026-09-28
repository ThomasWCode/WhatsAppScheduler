package com.thomaswcode.whatsappscheduler.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.thomaswcode.whatsappscheduler.domain.ChatTarget
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val message: String,
    val targetKind: TargetKind,
    val targetName: String,
    val phoneE164: String?,
    val isLocked: Boolean,
    val firstAtMillis: Long,
    val zoneId: String,
    val recurrence: String,
    val nextAtMillis: Long?,
    val enabled: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastRunAtMillis: Long? = null,
    val lastResult: String? = null,
) {
    val target: ChatTarget get() = ChatTarget(targetKind, targetName, phoneE164, isLocked)
    val rule: Recurrence get() = RecurrenceCodec.decode(recurrence)
}

/** Stored by name as text, so adding a value needs no migration. UNCERTAIN: failed after Send was tapped. */
enum class SendStatus { SENT, FAILED, SKIPPED, DRY_RUN, UNCERTAIN }

@Entity(
    tableName = "send_log",
    foreignKeys = [ForeignKey(entity = Schedule::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scheduleId"), Index("atMillis")],
)
data class SendLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val atMillis: Long,
    val status: SendStatus,
    val targetName: String,
    val detail: String?,
)
