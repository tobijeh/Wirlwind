package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    private fun entry(i: Int, code: Int? = null, stack: String? = null) =
        DiagnosticEntry(time = i.toLong(), engines = "ON_DEVICE", errorCode = code, message = "attempt $i", stackTrace = stack)

    @Test fun keepsOnlyTheLastTwentyAttempts() {
        var log = emptyList<DiagnosticEntry>()
        for (i in 1..25) log = DiagnosticsLog.add(log, entry(i))
        assertEquals(20, log.size)
        assertEquals("attempt 6", log.first().message)
        assertEquals("attempt 25", log.last().message)
    }

    @Test fun copyTextIsNewestFirstAndIncludesCodeAndStackTrace() {
        val text = DiagnosticsLog.copyText(
            listOf(entry(1), entry(2, code = 13, stack = "java.lang.SecurityException: boom\n\tat Wirl.kt:1")),
        )
        assertTrue(text.indexOf("attempt 2") < text.indexOf("attempt 1"))
        assertTrue(text.contains("Error code: 13"))
        assertTrue(text.contains("java.lang.SecurityException: boom"))
    }

    @Test fun emptyLogSaysSo() {
        assertEquals("No dictation attempts yet.", DiagnosticsLog.copyText(emptyList()))
    }
}
