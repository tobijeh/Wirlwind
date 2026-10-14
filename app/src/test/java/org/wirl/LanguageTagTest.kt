package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTagTest {
    @Test fun underscoreBecomesHyphen() {
        assertEquals("en-US", LanguageTag.format("en_US"))
    }

    @Test fun regionIsUppercaseAndLanguageLowercase() {
        assertEquals("en-US", LanguageTag.format("EN-us"))
        assertEquals("fr-CA", LanguageTag.format("fr_ca"))
    }

    @Test fun alreadyCorrectTagIsUnchanged() {
        assertEquals("pt-BR", LanguageTag.format("pt-BR"))
    }

    @Test fun languageOnlyStaysLowercase() {
        assertEquals("de", LanguageTag.format("DE"))
    }

    @Test fun scriptSubtagIsTitleCase() {
        assertEquals("zh-Hant-TW", LanguageTag.format("zh_hant_tw"))
    }

    @Test fun surroundingSpacesAndDoubleSeparatorsAreTidied() {
        assertEquals("en-GB", LanguageTag.format("  en--gb "))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", LanguageTag.format("   "))
    }
}
