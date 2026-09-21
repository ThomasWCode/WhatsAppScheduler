package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult

/** Backend that delivers one message. The only implementation today drives the WhatsApp UI. */
interface MessageSender {
    suspend fun send(job: SendJob): SendResult
}
