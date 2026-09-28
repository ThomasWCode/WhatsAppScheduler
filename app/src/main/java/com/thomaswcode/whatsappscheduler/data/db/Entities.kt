package com.thomaswcode.whatsappscheduler.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.thomaswcode.whatsappscheduler.domain.ChatTarget
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind

/**
 * Someone (or some group) messages have been scheduled to. Remembers what was learned once, the
 * name WhatsApp shows, the number and whether the chat is behind Chat Lock, so the quick sheet can
 * offer them again. Schedules keep their own copy of these fields, which is what is actually sent.
 */
@Entity(tableName = "recipients", indices = [Index("lastUsedAtMillis")])
data class Recipient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: TargetKind,
    val displayName: String,
    /** The chat's name in WhatsApp, for the search path, when it differs from [displayName]. */
    val whatsappName: String? = null,
    val phoneE164: String? = null,
    val isLocked: Boolean = false,
    val lastUsedAtMillis: Long = 0,
    val useCount: Int = 0,
    /** When "Check this chat now" last found the chat by name (F4). */
    val verifiedAtMillis: Long? = null,
)

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(entity = Recipient::class, parentColumns = ["id"], childColumns = ["recipientId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("recipientId"), Index("batchId")],
)
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
    /** Technical detail of the last run, for Logs and diagnosis; [lastStatus] is what the UI reads. */
    val lastResult: String? = null,
    val recipientId: Long? = null,
    /** Shared by the schedules one quick-sheet message created for several recipients. */
    val batchId: String? = null,
    /** The time a postponed occurrence was originally due, shown as "moved from". */
    val postponedFromMillis: Long? = null,
    /** Why this schedule is waiting on the user, or null when it isn't. */
    val attention: Attention? = null,
    val lastStatus: SendStatus? = null,
    /** For a missed occurrence of a series held as its own one-off: the series it came from. */
    val sourceScheduleId: Long? = null,
) {
    val target: ChatTarget get() = ChatTarget(targetKind, targetName, phoneE164, isLocked)
    val rule: Recurrence get() = RecurrenceCodec.decode(recurrence)
}

/** Stored by name as text, so adding a value needs no migration. UNCERTAIN: failed after Send was tapped. */
enum class SendStatus { SENT, FAILED, SKIPPED, DRY_RUN, UNCERTAIN, MISSED }

/** What a schedule is waiting on the user for. */
enum class Attention {
    /** Its alarm could not fire in time, so it was not sent. */
    MISSED,

    /** Automation failed before Send was tapped, after every retry. */
    FAILED,

    /** Something failed after Send was tapped: it may have gone out. */
    UNCERTAIN,

    /** Nothing is sent until the user picks a time (Postpone… was dismissed). */
    HELD,
}

@Entity(
    tableName = "send_log",
    foreignKeys = [ForeignKey(entity = Schedule::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("scheduleId"), Index("atMillis")],
)
data class SendLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null once the schedule is deleted: history outlives the schedule. */
    val scheduleId: Long?,
    val atMillis: Long,
    val status: SendStatus,
    val targetName: String,
    val detail: String?,
    /** The occurrence this run was for, when it was for one. */
    val occurrenceAtMillis: Long? = null,
)
