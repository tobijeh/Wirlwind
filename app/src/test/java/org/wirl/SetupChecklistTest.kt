package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wirl.SetupChecklist.Item

class SetupChecklistTest {
    @Test fun eachItemReflectsItsOwnStatus() {
        val status = SetupChecklist.status(
            apiKeySaved = true,
            accessibilityOn = false,
            microphoneAllowed = true,
            batteryUnrestricted = false,
            dictationTestPassed = true,
        )
        assertEquals(true, status[Item.API_KEY])
        assertEquals(false, status[Item.ACCESSIBILITY])
        assertEquals(true, status[Item.MICROPHONE])
        assertEquals(false, status[Item.BATTERY])
        assertEquals(true, status[Item.DICTATION_TEST])
    }

    @Test fun checklistIsNotCompleteUntilEveryItemIsDone() {
        val almost = SetupChecklist.status(true, true, true, true, dictationTestPassed = false)
        assertFalse(SetupChecklist.allDone(almost))
    }

    @Test fun checklistIsCompleteWhenEveryItemIsDone() {
        assertTrue(SetupChecklist.allDone(SetupChecklist.status(true, true, true, true, true)))
    }

    @Test fun eachItemCanBeFlippedOnItsOwn() {
        for (item in Item.entries) {
            val values = Item.entries.associateWith { it != item }
            assertFalse("only ${item.name} missing should be incomplete", SetupChecklist.allDone(values))
        }
    }

    @Test fun batteryTipsMatchTheMakers() {
        assertEquals(BatteryTip.Brand.OPPO_ONEPLUS, BatteryTip.brandFor("OPPO"))
        assertEquals(BatteryTip.Brand.OPPO_ONEPLUS, BatteryTip.brandFor("OnePlus"))
        assertEquals(BatteryTip.Brand.XIAOMI, BatteryTip.brandFor("Xiaomi"))
        assertEquals(BatteryTip.Brand.XIAOMI, BatteryTip.brandFor("Redmi"))
        assertEquals(BatteryTip.Brand.SAMSUNG, BatteryTip.brandFor("samsung"))
        assertEquals(BatteryTip.Brand.OTHER, BatteryTip.brandFor("Google"))
    }
}
