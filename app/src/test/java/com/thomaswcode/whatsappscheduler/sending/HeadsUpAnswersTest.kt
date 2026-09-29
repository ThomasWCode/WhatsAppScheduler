package com.thomaswcode.whatsappscheduler.sending

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeadsUpAnswersTest {

    @Test
    fun aButtonAnswersOnlyItsOwnCountdown() = runTest {
        val answers = HeadsUpAnswers()
        val first = answers.expect()
        // A late tap from an earlier countdown's notification: not this one's answer.
        answers.answer(first.token - 1, HeadsUpChoice.SNOOZE)
        assertFalse(first.answer.isCompleted)
        answers.answer(first.token, HeadsUpChoice.SEND)
        assertEquals(HeadsUpChoice.SEND, first.answer.await())
        answers.done(first)
        // The next countdown can't be answered by the first one's buttons.
        val second = answers.expect()
        answers.answer(first.token, HeadsUpChoice.POSTPONE)
        assertFalse(second.answer.isCompleted)
    }
}
