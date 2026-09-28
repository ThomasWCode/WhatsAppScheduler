package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Declarative description of a UI node. Any non-null field must match; `ids` matches if any id
 * matches. Text matching checks the node text first and then the content description.
 */
data class Selector(
    val name: String,
    val ids: List<String> = emptyList(),
    val text: Regex? = null,
    val desc: Regex? = null,
    val className: Regex? = null,
    val pkg: Regex? = null,
    val editable: Boolean? = null,
    val clickable: Boolean? = null,
) {
    fun matches(node: AccessibilityNodeInfo): Boolean {
        if (ids.isNotEmpty() && node.viewIdResourceName !in ids) return false
        if (pkg != null && !(node.packageName?.toString()?.let(pkg::matches) ?: false)) return false
        if (className != null && !(node.className?.toString()?.let(className::matches) ?: false)) return false
        if (editable != null && node.isEditable != editable) return false
        if (clickable != null && node.isClickable != clickable) return false
        if (text != null) {
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            if (!(t?.let(text::matches) ?: false) && !(d?.let(text::matches) ?: false)) return false
        }
        if (desc != null && !(node.contentDescription?.toString()?.let(desc::matches) ?: false)) return false
        return true
    }

    /** Same selector restricted to an exact visible text. */
    fun withText(exact: String) = copy(name = "$name[$exact]", text = Regex(Regex.escape(exact)))
}

/**
 * Every view id and label the automation depends on, in one place. Refresh with
 * `adb shell uiautomator dump` or the in-app inspector when WhatsApp or One UI changes.
 * Ids are listed most-likely first; text regexes are case-insensitive fallbacks.
 */
object Selectors {

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    private val waPkg = Regex("com\\.whatsapp(\\.w4b)?")
    private val sysPkg = Regex("com\\.android\\.systemui|com\\.android\\.settings|com\\.samsung\\.android\\.biometrics\\.app\\.setting|com\\.samsung\\.android\\.app\\.cocktailbarservice")

    val waEntry = Selector("wa.entry", ids = listOf("$WHATSAPP:id/entry", "$WHATSAPP_BUSINESS:id/entry"), editable = true)
    val waSend = Selector("wa.send", ids = listOf("$WHATSAPP:id/send", "$WHATSAPP_BUSINESS:id/send"))
    val waSendByDesc = Selector("wa.send.desc", pkg = waPkg, desc = Regex("(?i)^send$"), clickable = true)
    /** Search bar at the top of the chat list ("Ask Meta AI or Search"), which replaced the toolbar icon. */
    val waSearchBar = Selector("wa.searchBar", ids = listOf("$WHATSAPP:id/search_bar_inner_layout", "$WHATSAPP_BUSINESS:id/search_bar_inner_layout"))

    /** Non-clickable wrapper of [waSearchBar]; a separate selector so the clickable child wins. */
    val waSearchBarContainer = Selector("wa.searchBar.container", ids = listOf("$WHATSAPP:id/my_search_bar", "$WHATSAPP_BUSINESS:id/my_search_bar"))
    val waSearchButton = Selector("wa.search", ids = listOf("$WHATSAPP:id/menuitem_search", "$WHATSAPP_BUSINESS:id/menuitem_search"))
    val waSearchByDesc = Selector("wa.search.desc", pkg = waPkg, desc = Regex("(?i)^search.*"), clickable = true)

    /** Kept strict so a message preview that merely mentions "search" is never mistaken for the bar. */
    val waSearchByLabel = Selector("wa.search.label", pkg = waPkg, text = Regex("(?i)^((ask [\\w ]{1,20} or )?search(…|\\.{3})?|search or ask [\\w ]{1,20})$"))

    /** Every known way into chat-list search, most specific first. */
    val waSearchEntryPoints = arrayOf(waSearchBar, waSearchButton, waSearchByDesc, waSearchBarContainer, waSearchByLabel)
    val waSearchInput = Selector(
        "wa.searchInput",
        ids = listOf("$WHATSAPP:id/search_input", "$WHATSAPP:id/search_src_text", "$WHATSAPP:id/search_view_edit_text", "$WHATSAPP_BUSINESS:id/search_input", "$WHATSAPP_BUSINESS:id/search_src_text"),
        editable = true,
    )
    val waAnyEditable = Selector("wa.anyEditable", pkg = waPkg, editable = true)
    val waConversationRowTitle = Selector(
        "wa.rowTitle",
        ids = listOf("$WHATSAPP:id/conversations_row_contact_name", "$WHATSAPP:id/contact_row_name", "$WHATSAPP_BUSINESS:id/conversations_row_contact_name"),
    )
    val waChatTitle = Selector("wa.chatTitle", ids = listOf("$WHATSAPP:id/conversation_contact_name", "$WHATSAPP_BUSINESS:id/conversation_contact_name"))
    val waMessageText = Selector("wa.messageText", ids = listOf("$WHATSAPP:id/message_text", "$WHATSAPP_BUSINESS:id/message_text"))
    val waLockedChatsRow = Selector("wa.lockedChats", pkg = waPkg, text = Regex("(?i)^locked chats$"))
    val waInvalidNumberDialog = Selector("wa.invalidNumber", pkg = waPkg, text = Regex("(?i).*(isn't on whatsapp|invalid|not on whatsapp).*"))
    val waDialogOk = Selector("wa.dialogOk", pkg = waPkg, text = Regex("(?i)^(ok|cancel|not now|later)$"), clickable = true)

    val keyguardPasswordField = Selector(
        "keyguard.password",
        ids = listOf("com.android.systemui:id/pinEntry", "com.android.systemui:id/passwordEntry", "com.android.systemui:id/lockPassword"),
        editable = true,
    )
    val keyguardAnyEditable = Selector("keyguard.anyEditable", pkg = Regex("com\\.android\\.systemui"), editable = true)
    /** One UI's OK key is `key_enter_text`: a plain TextView that is not clickable and has no clickable parent. */
    val keyguardEnter = Selector(
        "keyguard.enter",
        ids = listOf("com.android.systemui:id/key_enter", "com.android.systemui:id/key_enter_text", "com.android.systemui:id/keyguard_enter", "com.android.systemui:id/ok_button", "com.android.systemui:id/confirm_button"),
    )
    val keyguardEnterByText = Selector("keyguard.enter.text", pkg = Regex("com\\.android\\.systemui"), text = Regex("(?i)^(enter|ok|done|confirm|next)$"))
    val keyguardDelete = Selector("keyguard.delete", ids = listOf("com.android.systemui:id/delete_button"))
    val keyguardWrongPin = Selector(
        "keyguard.wrongPin",
        ids = listOf("com.android.systemui:id/sec_bouncer_message_area", "com.android.systemui:id/bouncer_message_area", "com.android.systemui:id/keyguard_message_area"),
        text = Regex("(?i).*\\b(incorrect|wrong|try again)\\b.*"),
    )

    /** Digit key on a numeric keypad: matches by id `keyN` or by visible text. */
    fun keypadDigit(digit: Char) = Selector(
        "keypad.$digit",
        pkg = Regex("com\\.android\\.systemui"),
        text = Regex("^$digit(\\W.*)?$"),
        clickable = true,
    )

    fun keypadDigitById(digit: Char) = Selector("keypad.id.$digit", ids = listOf("com.android.systemui:id/key$digit"))

    val authUseCredentialButton = Selector(
        "auth.useCredential",
        pkg = sysPkg,
        text = Regex("(?i)^(use (device |screen |phone )?(pin|password|passcode|pattern|screen lock)|pin|password|use credentials?|enter pin)$"),
        clickable = true,
    )
    /** A dedicated "use PIN" button; always safe to press. */
    val authUseCredentialById = Selector("auth.useCredential.id", ids = listOf("com.android.systemui:id/button_use_credential"))

    /**
     * A biometric prompt's negative button. What it does depends on its text, so it is only pressed
     * when that text asks for the PIN or the secret code (B26): on many prompts it is "Cancel", and
     * on One UI's Chat Lock prompt it is "Use secret code".
     */
    val authNegativeButton = Selector(
        "auth.negative",
        ids = listOf("com.android.systemui:id/button_negative", "com.android.systemui:id/negative_button", "com.samsung.android.biometrics.app.setting:id/button_negative"),
    )

    /** Label of the negative button when a locked chat takes WhatsApp's own secret code instead of the device PIN. */
    val useSecretCodeLabel = Regex("(?i)^use (the )?secret code$")

    /** WhatsApp's "Enter your secret code" sheet for a locked chat opened directly (e.g. by a wa.me link). */
    val waSecretCodeField = Selector(
        "wa.secretCode",
        ids = listOf("$WHATSAPP:id/secret_code_edit_text", "$WHATSAPP_BUSINESS:id/secret_code_edit_text"),
        editable = true,
    )
    val waSecretCodeContinue = Selector(
        "wa.secretCode.continue",
        ids = listOf("$WHATSAPP:id/secret_code_authenticate_button", "$WHATSAPP_BUSINESS:id/secret_code_authenticate_button"),
    )
    val authPromptTitle = Selector("auth.prompt", pkg = sysPkg, text = Regex("(?i).*(unlock|verify|confirm|fingerprint|face|biometric|locked chats|screen lock).*"))
    val authPasswordField = Selector(
        "auth.password",
        ids = listOf("com.android.systemui:id/lockPassword", "com.android.settings:id/password_entry", "com.android.systemui:id/pinEntry", "com.android.systemui:id/passwordEntry"),
        editable = true,
    )
    val authAnyEditable = Selector("auth.anyEditable", pkg = sysPkg, editable = true)
    val authEnterByText = Selector("auth.enter", pkg = sysPkg, text = Regex("(?i)^(enter|ok|done|confirm|next|continue)$"), clickable = true)
}
