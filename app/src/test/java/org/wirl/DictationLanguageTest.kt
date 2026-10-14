package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wirl.DictationLanguage.PackStatus

class DictationLanguageTest {
    @Test fun aLanguagePickedInSettingsWins() {
        assertEquals("fr-FR", DictationLanguage.resolve("fr-FR", "en-US"))
    }

    @Test fun deviceLanguageIsUsedWhenSupported() {
        assertEquals("de-DE", DictationLanguage.resolve("", "de-DE"))
    }

    @Test fun unsupportedDeviceLanguageDefaultsToEnglishUs() {
        assertEquals("en-US", DictationLanguage.resolve("", "zh-CN"))
    }

    @Test fun regionVariantUsesTheSupportedLanguage() {
        // Canadian French uses the French pack; Indian English uses US English.
        assertEquals("fr-FR", DictationLanguage.resolve("", "fr-CA"))
        assertEquals("en-US", DictationLanguage.resolve("", "en-IN"))
    }

    @Test fun exactMatchBeatsLanguageMatch() {
        assertEquals("en-GB", DictationLanguage.resolve("", "en-GB"))
    }

    @Test fun noMatchGivesNull() {
        assertNull(DictationLanguage.supportedMatch("xx-YY"))
    }

    @Test fun packInstalledWhenListed() {
        assertEquals(PackStatus.INSTALLED, DictationLanguage.packStatus("en-US", listOf("fr-FR", "en-US")))
    }

    @Test fun packCaseDoesNotMatter() {
        assertEquals(PackStatus.INSTALLED, DictationLanguage.packStatus("en-us", listOf("en-US")))
    }

    @Test fun packNotInstalledWhenMissing() {
        assertEquals(PackStatus.NOT_INSTALLED, DictationLanguage.packStatus("en-US", listOf("fr-FR")))
    }

    @Test fun packUnknownWhenThePhoneCannotSay() {
        assertEquals(PackStatus.UNKNOWN, DictationLanguage.packStatus("en-US", null))
    }

    @Test fun packStatusReadsClearly() {
        assertEquals("not installed (uses online recognition)", DictationLanguage.describePack(PackStatus.NOT_INSTALLED))
        assertEquals("installed (works offline)", DictationLanguage.describePack(PackStatus.INSTALLED))
    }
}
