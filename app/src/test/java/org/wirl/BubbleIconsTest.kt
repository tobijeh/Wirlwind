package org.wirl

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleIconsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun thePickerHasFifteenIconsInThePickerOrder() {
        assertEquals(
            listOf("swirl", "mic", "book", "text", "plane", "keyboard", "cloud", "sprout",
                "paw", "palette", "laptop", "heart", "bolt", "sparkles", "wave"),
            BubbleIcons.ALL.map { it.id },
        )
    }

    @Test fun everyIconLoadsAsAVector() {
        for (icon in BubbleIcons.ALL) {
            assertNotNull(icon.id, ContextCompat.getDrawable(context, icon.drawable))
        }
    }

    @Test fun aMissingOrUnknownIdFallsBackToTheSwirl() {
        assertEquals("swirl", BubbleIcons.find(null).id)
        assertEquals("swirl", BubbleIcons.find("no-such-icon").id)
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
            assertEquals(icon.id, 56, bubble.glyph.layoutParams.width)
        }
    }

    @Test fun everyStateRendersWithEveryIcon() {
        for (icon in BubbleIcons.ALL) {
            val bubble = BubbleView(context)
            bubble.setIcon(icon.drawable)

            bubble.setState(BubbleView.State.RECORDING)
            assertNotNull(icon.id, bubble.glyph.colorFilter)
            assertEquals(icon.id, View.VISIBLE, bubble.glyph.visibility)

            bubble.setState(BubbleView.State.BUSY)
            assertEquals(icon.id, View.GONE, bubble.glyph.visibility)

            bubble.setState(BubbleView.State.DONE)
            assertEquals(icon.id, View.VISIBLE, bubble.check.visibility)

            bubble.setState(BubbleView.State.IDLE)
            assertNull(icon.id, bubble.glyph.colorFilter)
            assertEquals(icon.id, View.VISIBLE, bubble.glyph.visibility)
        }
    }
}
