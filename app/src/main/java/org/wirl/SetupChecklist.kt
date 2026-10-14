package org.wirl

/** The first-run checklist. Each item is either done or not. */
object SetupChecklist {
    enum class Item { API_KEY, ACCESSIBILITY, MICROPHONE, BATTERY, DICTATION_TEST }

    fun status(
        apiKeySaved: Boolean,
        accessibilityOn: Boolean,
        microphoneAllowed: Boolean,
        batteryUnrestricted: Boolean,
        dictationTestPassed: Boolean,
    ): Map<Item, Boolean> = mapOf(
        Item.API_KEY to apiKeySaved,
        Item.ACCESSIBILITY to accessibilityOn,
        Item.MICROPHONE to microphoneAllowed,
        Item.BATTERY to batteryUnrestricted,
        Item.DICTATION_TEST to dictationTestPassed,
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
