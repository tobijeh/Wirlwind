package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wirl.DictationLanguage.PackStatus

class DictationLanguageTest {
    @Test fun theGoogleLanguageFollowsTheModel() {
        assertEquals("en-US", SpeechLanguage.ENGLISH.googleTag("fr-FR"))
        assertEquals("phone English is kept", "en-GB", SpeechLanguage.ENGLISH.googleTag("en_gb"))
        assertEquals("yue-Hant-HK", SpeechLanguage.CANTONESE.googleTag("en-GB"))
        assertEquals("zh-CN", SpeechLanguage.MANDARIN.googleTag("en-US"))
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
