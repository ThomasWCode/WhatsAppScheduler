package com.thomaswcode.whatsappscheduler.sending.accessibility

/**
 * Zero-width and direction-control characters. WhatsApp puts some of these into text it shows:
 * its search box reports "​Matlock fun" after being set to "Matlock fun", and names can carry
 * direction marks. They are invisible, so they must not decide whether two texts are the same.
 */
private val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")

/**
 * [text] as the user sees it, for comparisons only: without invisible characters and surrounding
 * whitespace. Both sides of a comparison go through this, so stripping the joiners that hold emoji
 * sequences together cannot make different texts equal in practice.
 */
fun visibleText(text: CharSequence?): String = text?.toString()?.replace(invisible, "")?.trim() ?: ""

/** True when [a] and [b] would look the same on screen. */
fun sameVisibleText(a: CharSequence?, b: CharSequence?): Boolean = visibleText(a) == visibleText(b)

/** [text] with each invisible character written out as `\uXXXX`, so log lines show what a comparison saw. */
fun showInvisible(text: String): String = text.replace(invisible) { "\\u%04X".format(it.value.single().code) }
