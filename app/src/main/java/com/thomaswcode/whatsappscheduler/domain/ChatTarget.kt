package com.thomaswcode.whatsappscheduler.domain

enum class TargetKind { CONTACT, GROUP }

/**
 * Where a message goes. Contacts are opened by phone number through the official deep link;
 * groups (and anything without a number) are found by exact chat name inside WhatsApp.
 * [isLocked] marks chats behind WhatsApp Chat Lock, which adds an authentication step.
 */
data class ChatTarget(
    val kind: TargetKind,
    val name: String,
    val phoneE164: String?,
    val isLocked: Boolean,
) {
    /** True when the phone-number deep link can be used to open this chat. */
    val canDeepLink: Boolean get() = kind == TargetKind.CONTACT && !phoneE164.isNullOrBlank()
}
