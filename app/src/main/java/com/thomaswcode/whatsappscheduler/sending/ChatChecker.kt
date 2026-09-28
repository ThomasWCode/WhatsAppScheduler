package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.RecipientRepository
import com.thomaswcode.whatsappscheduler.domain.ChatCheck
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs "Check this chat now" on the phone: the accessibility sender does; tests fake it. */
fun interface ChatCheckRunner {
    suspend fun checkChat(name: String): ChatCheck
}

/** Holds [lock] around every send, so a chat check ([ChatChecker]) and a send never drive WhatsApp at once. */
class LockedSender(private val inner: MessageSender, private val lock: Mutex) : MessageSender {
    override suspend fun send(job: SendJob): SendResult = lock.withLock { inner.send(job) }
}

/**
 * "Check this chat now" (features F4) for a chat found by name. It takes the automation lock, so it
 * never runs during a send, and gives up at once when a send holds it rather than leaving the user
 * waiting. A chat that is found is marked as checked on its stored recipient.
 */
class ChatChecker(
    private val runner: ChatCheckRunner,
    private val lock: Mutex,
    private val recipients: RecipientRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun check(name: String, recipientId: Long?): ChatCheck {
        if (!lock.tryLock()) return ChatCheck.NotChecked("a message is being sent")
        return try {
            runner.checkChat(name).also { result ->
                if (result == ChatCheck.Found && recipientId != null) recipients.markVerified(recipientId, clock.millis())
            }
        } finally {
            lock.unlock()
        }
    }
}
