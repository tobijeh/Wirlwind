package org.wirl

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptChipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 0L

    private fun chip() = TranscriptChip(context) { now }

    /** Gives the chip its size and runs layout, as the window manager would. */
    private fun layout(chip: TranscriptChip) {
        val h = chip.maxHeightPx
        chip.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        chip.layout(0, 0, 300, h)
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Each line is a separate line of text. Robolectric doesn't wrap by font width, so line breaks are used to make the
    // text taller than the chip; the scrolling logic is the same either way.
    private val lines = (1..24).map { "line $it" }

    @Test fun scrollsToLatestAsPartialResultsGrow() {
        val chip = chip()
        layout(chip)
        var transcript = ""
        var previousScroll = 0
        for (line in lines) {
            transcript = if (transcript.isEmpty()) line else "$transcript\n$line"
            chip.show(transcript)
            layout(chip)
            // Scrolling only ever moves down as the text grows.
            assertTrue(chip.scrollView.scrollY >= previousScroll)
            previousScroll = chip.scrollView.scrollY
        }
        val bottom = chip.textView.height - chip.scrollView.height
        assertTrue("text should be taller than the chip", bottom > 0)
        assertEquals(bottom, chip.scrollView.scrollY)
        assertEquals(transcript, chip.textView.text.toString())
    }

    @Test fun touchPausesAutoScrollThenItResumesAfterThreeSeconds() {
        val chip = chip()
        layout(chip)
        now = 0
        chip.show(lines.take(12).joinToString("\n"))
        layout(chip)

        // The user drags back up to read.
        now = 1_000
        chip.onUserTouch()
        chip.scrollView.scrollTo(0, 0)
        chip.show(lines.joinToString("\n"))
        layout(chip)
        assertEquals("no auto-scroll while the user is touching the chip", 0, chip.scrollView.scrollY)

        // Three seconds after the last touch, auto-scroll resumes.
        now = 4_000
        chip.show(lines.joinToString("\n") + "\nmore")
        layout(chip)
        val bottom = chip.textView.height - chip.scrollView.height
        assertEquals(bottom, chip.scrollView.scrollY)
    }

    @Test fun chipHeightIsAboutThreeLines() {
        val chip = chip()
        assertTrue(chip.maxHeightPx > 0)
        assertEquals(3 * chip.textView.lineHeight + 2 * (6 * context.resources.displayMetrics.density).toInt(), chip.maxHeightPx)
    }
}
