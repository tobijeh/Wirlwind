package org.wirl

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleIconsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun thePickerHasTwentyFiveIconsInThePickerOrder() {
        assertEquals(
            listOf("swirl", "mic", "book", "text", "plane", "keyboard", "chat", "sparkles", "sprout", "paw", "palette", "wave", "mountains", "tree", "butterfly", "bee", "hummingbird", "dolphin", "seahorse", "jellyfish", "whale", "turtle", "bird", "rocket", "pizza"),
            BubbleIcons.ALL.map { it.id },
        )
    }

    @Test fun everyIconLoads() {
        for (icon in BubbleIcons.ALL) {
            assertNotNull(icon.id, ContextCompat.getDrawable(context, icon.drawable))
        }
    }

    @Test fun aMissingOrUnknownIdFallsBackToTheSwirl() {
        assertEquals("swirl", BubbleIcons.find(null).id)
        assertEquals("swirl", BubbleIcons.find("no-such-icon").id)
    }

    @Test fun aRemovedIconFallsBackToTheSwirl() {
        for (removed in listOf("cloud", "laptop", "heart", "bolt")) {
            assertEquals(removed, "swirl", BubbleIcons.find(removed).id)
        }
    }

    @Test fun aSavedRemovedIconReadsAsTheSwirl() {
        Prefs(context).bubbleIcon = "heart"
        assertEquals("swirl", Prefs(context).bubbleIcon)
    }

    @Test fun theChosenIconIsSavedAcrossRestarts() {
        Prefs(context).bubbleIcon = "paw"
        assertEquals("paw", Prefs(context).bubbleIcon)
    }

    @Test fun resetToDefaultsRestoresTheSwirl() {
        Prefs(context).bubbleIcon = "heart"
        Prefs(context).resetToDefaults()
        assertEquals("swirl", Prefs(context).bubbleIcon)
    }

    @Test fun theSizeScalesEveryIconTheSameWay() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)
            bubble.setDiameter(100)
            assertEquals(icon.id, 100, bubble.face.layoutParams.width)
            assertEquals(icon.id, 100, bubble.face.layoutParams.height)
        }
    }

    @Test fun theChosenIconIsTheFaceInEveryState() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)
            for (state in BubbleView.State.values()) {
                bubble.setState(state)
                assertNotNull(icon.id, bubble.face.drawable)
                assertEquals(icon.id, View.VISIBLE, bubble.face.visibility)
            }
        }
    }

    @Test fun recordingShowsTheRedTintAndRing() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)
            bubble.setState(BubbleView.State.RECORDING)
            assertEquals(icon.id, View.VISIBLE, bubble.tint.visibility)
            assertEquals(icon.id, View.VISIBLE, bubble.ring.visibility)
            assertEquals(icon.id, View.GONE, bubble.scrim.visibility)
            assertEquals(icon.id, View.GONE, bubble.spinner.visibility)
            assertEquals(icon.id, View.GONE, bubble.check.visibility)
        }
    }

    @Test fun processingShowsTheScrimAndSpinnerOverTheIcon() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)
            bubble.setState(BubbleView.State.BUSY)
            assertEquals(icon.id, View.VISIBLE, bubble.scrim.visibility)
            assertEquals(icon.id, View.VISIBLE, bubble.spinner.visibility)
            assertEquals(icon.id, View.GONE, bubble.tint.visibility)
            assertEquals(icon.id, View.GONE, bubble.ring.visibility)
        }
    }

    @Test fun doneShowsTheGreenScrimAndCheckOverTheIcon() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)
            bubble.setState(BubbleView.State.DONE)
            assertEquals(icon.id, View.VISIBLE, bubble.scrim.visibility)
            assertEquals(icon.id, View.VISIBLE, bubble.check.visibility)
            assertEquals(icon.id, View.GONE, bubble.spinner.visibility)
        }
    }

    @Test fun idleShowsOnlyTheIcon() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)
            bubble.setState(BubbleView.State.IDLE)
            assertEquals(icon.id, View.GONE, bubble.tint.visibility)
            assertEquals(icon.id, View.GONE, bubble.ring.visibility)
            assertEquals(icon.id, View.GONE, bubble.scrim.visibility)
            assertEquals(icon.id, View.GONE, bubble.spinner.visibility)
            assertEquals(icon.id, View.GONE, bubble.check.visibility)
        }
    }
}
