package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wirl.WhisperModel.ACCURATE
import org.wirl.WhisperModel.CANTONESE
import org.wirl.WhisperModel.FAST
import org.wirl.WhisperModel.MANDARIN

/** Quick switch: which models a swipe on the recording bubble moves between, and ticking them in Settings. */
class ModelCycleTest {
    private val allReady: (WhisperModel) -> Boolean = { true }

    @Test fun theLabelsAreShortAndDistinct() {
        assertEquals(listOf("EN", "EN+", "粵", "普"), WhisperModel.entries.map { it.tag })
    }

    @Test fun theCycleIsTheTickedDownloadedModelsInPickerOrder() {
        val ready = { m: WhisperModel -> m != ACCURATE }
        assertEquals(listOf(FAST, CANTONESE), ModelCycle.members(setOf(CANTONESE, ACCURATE, FAST), FAST, ready))
        assertEquals("the model in use always counts, even before its download", listOf(FAST, MANDARIN),
            ModelCycle.members(setOf(MANDARIN), FAST) { it == MANDARIN })
    }

    @Test fun oneModelHasNowhereToSwitchTo() {
        assertFalse(ModelCycle.canSwitch(setOf(FAST), FAST, allReady))
        assertFalse("not downloaded, so not in the cycle", ModelCycle.canSwitch(setOf(FAST, CANTONESE), FAST) { it == FAST })
        assertTrue(ModelCycle.canSwitch(setOf(FAST, CANTONESE), FAST, allReady))
    }

    @Test fun upGoesToTheNextAndDownToThePreviousWrappingRound() {
        val on = setOf(FAST, CANTONESE, MANDARIN)
        assertEquals(CANTONESE, ModelCycle.step(on, FAST, allReady, 1))
        assertEquals(FAST, ModelCycle.step(on, MANDARIN, allReady, 1))
        assertEquals(MANDARIN, ModelCycle.step(on, FAST, allReady, -1))
        assertEquals(FAST, ModelCycle.step(on, CANTONESE, allReady, -1))
    }

    @Test fun fastAndAccurateCanBothBeInTheCycle() {
        assertEquals(ACCURATE, ModelCycle.step(setOf(FAST, ACCURATE), FAST, allReady, 1))
    }

    @Test fun tickingOnKeepsTheModelInUse() {
        assertEquals(ModelCycle.Choice(setOf(FAST, CANTONESE), FAST), ModelCycle.toggle(setOf(FAST), FAST, allReady, CANTONESE, on = true))
    }

    @Test fun tickingOffAnotherModelKeepsTheModelInUse() {
        assertEquals(ModelCycle.Choice(setOf(FAST), FAST), ModelCycle.toggle(setOf(FAST, CANTONESE), FAST, allReady, CANTONESE, on = false))
    }

    @Test fun tickingOffTheModelInUseMovesToADownloadedOne() {
        val ready = { m: WhisperModel -> m == MANDARIN }
        assertEquals(ModelCycle.Choice(setOf(ACCURATE, MANDARIN), MANDARIN),
            ModelCycle.toggle(setOf(FAST, ACCURATE, MANDARIN), FAST, ready, FAST, on = false))
    }

    @Test fun theLastTickedModelCantBeTickedOff() {
        assertNull(ModelCycle.toggle(setOf(FAST), FAST, allReady, FAST, on = false))
    }

    @Test fun onlyAMostlyVerticalSwipeSwitches() {
        assertEquals("up: next", 1, QuickSwitch.stepFor(0f, -100f, 80f))
        assertEquals("down: previous", -1, QuickSwitch.stepFor(10f, 100f, 80f))
        assertEquals("not far enough", 0, QuickSwitch.stepFor(0f, -60f, 80f))
        assertEquals("sideways is Slide to cancel", 0, QuickSwitch.stepFor(-120f, -100f, 80f))
    }

    @Test fun recordingShowsTheLanguageOnlyWhenThereIsAChoice() {
        assertEquals("粵", QuickSwitch.startLabel(canSwitch = true, model = CANTONESE))
        assertEquals("EN+", QuickSwitch.startLabel(canSwitch = true, model = ACCURATE))
        assertNull("one language: nothing to say", QuickSwitch.startLabel(canSwitch = false, model = FAST))
    }
}
