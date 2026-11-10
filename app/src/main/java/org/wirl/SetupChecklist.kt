package org.wirl

/** The first-run checklist. Each item is either done or not. */
object SetupChecklist {
    /** [SPEECH_MODEL]: the speech model chosen in Settings › Dictation is downloaded. */
    enum class Item { API_KEY, ACCESSIBILITY, MICROPHONE, BATTERY, SPEECH_MODEL }

    fun status(
        apiKeySaved: Boolean,
        accessibilityOn: Boolean,
        microphoneAllowed: Boolean,
        batteryUnrestricted: Boolean,
        speechModelReady: Boolean,
    ): Map<Item, Boolean> = mapOf(
        Item.API_KEY to apiKeySaved,
        Item.ACCESSIBILITY to accessibilityOn,
        Item.MICROPHONE to microphoneAllowed,
        Item.BATTERY to batteryUnrestricted,
        Item.SPEECH_MODEL to speechModelReady,
    )

    fun allDone(status: Map<Item, Boolean>): Boolean = Item.entries.all { status[it] == true }
}

/** Battery settings differ by phone maker; these are the names to look for. */
object BatteryTip {
    enum class Brand { OPPO_ONEPLUS, XIAOMI, SAMSUNG, OTHER }

    fun brandFor(manufacturer: String): Brand {
        val m = manufacturer.lowercase()
        return when {
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> Brand.OPPO_ONEPLUS
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> Brand.XIAOMI
            m.contains("samsung") -> Brand.SAMSUNG
            else -> Brand.OTHER
        }
    }
}
