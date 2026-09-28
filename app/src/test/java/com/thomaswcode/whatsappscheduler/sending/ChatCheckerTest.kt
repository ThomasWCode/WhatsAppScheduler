package com.thomaswcode.whatsappscheduler.sending

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.RecipientRepository
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.domain.ChatCheck
import com.thomaswcode.whatsappscheduler.domain.ChatTarget
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Check this chat now" (features F4) and the lock it shares with sends. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatCheckerTest {

    private lateinit var db: AppDatabase
    private lateinit var recipients: RecipientRepository
    private val at: Instant = Instant.parse("2026-09-28T12:00:00Z")
    private val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        recipients = RecipientRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aChatThatIsFoundIsMarkedChecked() = runTest {
        val crew = recipients.resolve(RecipientDraft(TargetKind.GROUP, "Work crew"))
        val checker = ChatChecker({ ChatCheck.Found }, Mutex(), recipients, clock)
        assertEquals(ChatCheck.Found, checker.check("Work crew", crew.id))
        assertEquals(at.toEpochMilli(), recipients.get(crew.id)!!.verifiedAtMillis)
    }

    @Test
    fun aChatThatIsNotFoundIsNotMarked() = runTest {
        val crew = recipients.resolve(RecipientDraft(TargetKind.GROUP, "Work crew"))
        val checker = ChatChecker({ ChatCheck.NotFound("No chat named 'Work crew'") }, Mutex(), recipients, clock)
        assertTrue(checker.check("Work crew", crew.id) is ChatCheck.NotFound)
        assertNull(recipients.get(crew.id)!!.verifiedAtMillis)
    }

    @Test
    fun noCheckWhileASendHoldsTheLock() = runTest {
        val lock = Mutex().apply { lock() }
        var ran = false
        val checker = ChatChecker({ ran = true; ChatCheck.Found }, lock, recipients, clock)
        assertTrue(checker.check("Work crew", null) is ChatCheck.NotChecked)
        assertFalse("WhatsApp isn't touched during a send", ran)
    }

    @Test
    fun aSendWaitsForACheckToFinish() = runTest {
        val lock = Mutex()
        val sender = LockedSender(object : MessageSender { override suspend fun send(job: SendJob) = SendResult.Sent }, lock)
        val job = SendJob(1, ChatTarget(TargetKind.GROUP, "Work crew", null, false), "Hi", dryRun = false, attempt = 1)
        lock.lock()
        val send = async { sender.send(job) }
        runCurrent()
        assertFalse(send.isCompleted)
        lock.unlock()
        assertEquals(SendResult.Sent, send.await())
    }

    @Test
    fun renamingAChatMeansCheckingItAgain() = runTest {
        val crew = recipients.resolve(RecipientDraft(TargetKind.GROUP, "Work crew"))
        recipients.markVerified(crew.id, at.toEpochMilli())
        // Chat Lock changed, same name: still the chat that was found.
        recipients.updateDetails(recipients.get(crew.id)!!.copy(isLocked = true))
        assertEquals(at.toEpochMilli(), recipients.get(crew.id)!!.verifiedAtMillis)
        recipients.updateDetails(recipients.get(crew.id)!!.copy(whatsappName = "Work crew 2026"))
        assertNull(recipients.get(crew.id)!!.verifiedAtMillis)
    }
}
