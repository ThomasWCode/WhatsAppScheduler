package com.thomaswcode.whatsappscheduler.util

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * User-facing text from strings.xml, for code outside composition or tested without a Context of its
 * own (the send queue, the attention actions, the list's status lines). The app reads its resources
 * through [of]; a test can pass its own.
 */
interface Texts {
    fun get(@StringRes id: Int, vararg args: Any): String

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String

    companion object {
        /** The strings of [resources]. */
        fun of(resources: Resources): Texts = object : Texts {
            override fun get(id: Int, vararg args: Any): String = resources.getString(id, *args)

            override fun plural(id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)
        }
    }
}
