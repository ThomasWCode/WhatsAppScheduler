package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.RecipientDraft
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

/** What a check found, and the stored recipient it is remembered on (created for a new chat that was found). */
data class CheckOutcome(val result: ChatCheck, val recipientId: Long?)

/**
 * "Check this chat now" (features F4) for a chat found by name. It takes the automation lock, so it
 * never runs during a send, and gives up at once when a send holds it rather than leaving the user
 * waiting. A chat that is found is marked as checked on its stored recipient, which is created for a
 * name typed but not yet saved, so the check is remembered and isn't offered again.
 */
class ChatChecker(
    private val runner: ChatCheckRunner,
    private val lock: Mutex,
    private val recipients: RecipientRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun check(chat: RecipientDraft): CheckOutcome {
        if (!lock.tryLock()) return CheckOutcome(ChatCheck.NotChecked("a message is being sent"), chat.recipientId)
        val result = try {
            runner.checkChat(chat.chatName)
        } finally {
            lock.unlock()
        }
        if (result is ChatCheck.NotFound) {
            // A definite miss: an earlier tick no longer holds (renamed, or left, since). A check that
            // couldn't tell leaves it.
            recipients.existing(chat)?.let { recipients.clearVerified(it.id) }
        }
        if (result != ChatCheck.Found) return CheckOutcome(result, chat.recipientId)
        val id = recipients.resolve(chat).id
        recipients.markVerified(id, clock.millis())
        return CheckOutcome(result, id)
    }
}
