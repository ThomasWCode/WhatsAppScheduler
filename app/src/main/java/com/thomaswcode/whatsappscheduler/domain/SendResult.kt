package com.thomaswcode.whatsappscheduler.domain

sealed class SendResult {
    data object Sent : SendResult()
    data object DryRun : SendResult()
    data class Failed(val step: String, val reason: String, val windowDump: String? = null) : SendResult()
}

/** One unit of work handed to a [com.thomaswcode.whatsappscheduler.sending.MessageSender]. */
data class SendJob(
    val scheduleId: Long,
    val target: ChatTarget,
    val message: String,
    val dryRun: Boolean,
    val attempt: Int,
)
