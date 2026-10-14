package org.wirl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun entriesSurviveSavingAndReloading() {
        val entries = listOf(
            DiagnosticEntry(1000L, "ON_DEVICE → GOOGLE", 13, "Language isn't supported.", "java.lang.Exception: x"),
            DiagnosticEntry(2000L, "SYSTEM", null, "Heard 12 characters", null),
        )
        Prefs(context).diagnostics = entries
        assertEquals(entries, Prefs(context).diagnostics)
    }

    @Test fun corruptLogReadsAsEmpty() {
        assertTrue(DiagnosticsStore.decode("not json").isEmpty())
    }
}
