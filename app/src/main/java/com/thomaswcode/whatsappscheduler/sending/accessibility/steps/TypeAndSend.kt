package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/** Puts the message in the composer (if not already there) and taps Send unless dry-running. */
class TypeAndSend(private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "typeAndSend"

    suspend fun run(message: String, dryRun: Boolean, timeoutMs: Long) {
        val entry = driver.waitFor(step, Selectors.waEntry, timeoutMs)
        entry.refresh()
        if (entry.text?.toString() != message) driver.setText(step, entry, message)
        if (dryRun) {
            log.info("$step: DRY RUN, not tapping send; clearing composer")
            driver.setText(step, entry, "")
            return
        }
        val (_, send) = driver.waitForAny(step, Selectors.waSend, Selectors.waSendByDesc, timeoutMs = timeoutMs)
        driver.click(step, send)
        delay(400)
    }
}
