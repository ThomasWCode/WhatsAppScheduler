package com.thomaswcode.whatsappscheduler.sending.accessibility

/**
 * Characters that never change what a text says or how it is identified: the zero-width space,
 * direction marks, embeddings and isolates, the word joiner and invisible operators, and the BOM.
 * WhatsApp puts some of these into text it shows: its search box reports "​Matlock fun" after
 * being set to "Matlock fun", and names are commonly wrapped in direction isolates.
 *
 * The zero-width joiner and non-joiner (U+200D, U+200C) are deliberately *not* here: they shape
 * emoji sequences (👩‍💻 is not 👩💻) and letters in some scripts, so two names that differ only by
 * one are different names, and treating them as equal could pick the wrong chat.
 */
const val INVISIBLE_CHARS = "[\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]"

private val invisible = Regex(INVISIBLE_CHARS)

/** [text] as the user sees it, for comparisons only: without [INVISIBLE_CHARS] and surrounding whitespace. */
fun visibleText(text: CharSequence?): String = text?.toString()?.replace(invisible, "")?.trim() ?: ""

/** True when [a] and [b] would look the same on screen. */
fun sameVisibleText(a: CharSequence?, b: CharSequence?): Boolean = visibleText(a) == visibleText(b)

/** [text] with each invisible character written out as `\uXXXX`, so log lines show what a comparison saw. */
fun showInvisible(text: String): String = text.replace(invisible) { "\\u%04X".format(it.value.single().code) }
