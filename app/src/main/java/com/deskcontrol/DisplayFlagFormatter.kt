package com.deskcontrol

import java.util.Locale

/** Decodes public and stable framework Display flags without hidden-API reflection. */
object DisplayFlagFormatter {
    private val flagNames = listOf(
        0x00000001 to "PROTECTED",
        0x00000002 to "SECURE",
        0x00000004 to "PRIVATE",
        0x00000008 to "PRESENTATION",
        0x00000010 to "ROUND",
        0x00000020 to "INSECURE_KEYGUARD",
        0x00000040 to "SYSTEM_DECOR",
        0x00000080 to "TRUSTED",
        0x00000100 to "OWN_GROUP",
        0x00000200 to "ALWAYS_UNLOCKED",
        0x00000400 to "TOUCH_FEEDBACK_DISABLED",
        0x00000800 to "OWN_FOCUS",
        0x00001000 to "STEAL_TOP_FOCUS_DISABLED",
        0x00002000 to "REAR",
        0x00004000 to "ROTATES_WITH_CONTENT",
        0x00008000 to "CONTENT_MODE_SWITCH",
        0x40000000 to "SCALING_DISABLED"
    )
    private val knownMask = flagNames.fold(0) { mask, (flag, _) -> mask or flag }

    fun format(flags: Int): String {
        val labels = flagNames
            .filter { (flag, _) -> flags and flag != 0 }
            .map { (_, name) -> name }
            .toMutableList()
        labels += "trusted=${isTrusted(flags)}"
        labels += "private=${isPrivate(flags)}"
        labels += "unknown=${hex(flags and knownMask.inv())}"
        return "${hex(flags)}(${labels.joinToString("|")})"
    }

    fun hex(value: Int): String = String.format(Locale.US, "0x%08X", value)

    fun isTrusted(flags: Int): Boolean = flags and 0x00000080 != 0

    fun isPrivate(flags: Int): Boolean = flags and 0x00000004 != 0
}
