package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryJsonTest {
    @Test fun roundTripKeepsEverything() {
        val entries = listOf(DictionaryEntry("Sela", listOf("salad", "Sella"), "web app"), DictionaryEntry("Alex"))
        assertEquals(entries, DictionaryJson.decode(DictionaryJson.encode(entries)))
    }

    @Test fun missingOptionalFieldsUseDefaults() {
        assertEquals(listOf(DictionaryEntry("Sela")), DictionaryJson.decode("""[{"term":"Sela"}]"""))
    }

    @Test fun entriesWithoutATermAreSkipped() {
        assertEquals(listOf(DictionaryEntry("Sela")), DictionaryJson.decode("""[{"term":"  "},{"term":"Sela"}]"""))
    }

    @Test fun notAnArrayThrows() {
        assertThrows(Exception::class.java) { DictionaryJson.decode("not json") }
    }
}
