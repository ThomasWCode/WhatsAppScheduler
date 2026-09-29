package com.thomaswcode.whatsappscheduler.util

import androidx.annotation.StringRes

/**
 * User-facing text from strings.xml, for classes that run their tests without a Context of their own
 * (the send queue, the attention actions). The app reads resources; a test passes its own.
 */
fun interface Texts {
    fun get(@StringRes id: Int, vararg args: Any): String
}
