package org.wirl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun defaultsWhenNothingIsSaved() {
        val prefs = Prefs(context)
        assertEquals(56, prefs.bubbleSizeDp)
        assertEquals(100, prefs.bubbleOpacity)
        assertTrue(prefs.bubbleEnabled)
        assertTrue(prefs.showDoneCheck)
        assertFalse(prefs.showDoneMessage)
        assertEquals("", prefs.styleNotes)
        assertTrue(prefs.dictionary.isEmpty())
    }

    @Test fun sizeAndOpacityPersistAcrossInstances() {
        Prefs(context).apply {
            bubbleSizeDp = 72
            bubbleOpacity = 45
        }
        assertEquals(72, Prefs(context).bubbleSizeDp)
        assertEquals(45, Prefs(context).bubbleOpacity)
    }

    @Test fun sizeAndOpacityAreClampedToTheirRanges() {
        Prefs(context).bubbleSizeDp = 200
        assertEquals(80, Prefs(context).bubbleSizeDp)
        Prefs(context).bubbleOpacity = 5
        assertEquals(30, Prefs(context).bubbleOpacity)
    }

    @Test fun styleNotesArePersistedTrimmed() {
        Prefs(context).styleNotes = "  I write casually.  "
        assertEquals("I write casually.", Prefs(context).styleNotes)
    }

    @Test fun styleNotesCanBeCleared() {
        Prefs(context).styleNotes = "Casual."
        Prefs(context).styleNotes = "   "
        assertEquals("", Prefs(context).styleNotes)
    }

    @Test fun settingsVersionThreeDiscardsAnOldCustomPrompt() {
        // Version 2 saved an editable prompt. It's dropped, not moved into style notes.
        context.getSharedPreferences("wirl", Context.MODE_PRIVATE).edit()
            .clear()
            .putInt(Prefs.KEY_SETTINGS_VERSION, 2)
            .putString(Prefs.KEY_PROMPT, "Old custom prompt the user wrote.")
            .commit()
        val prefs = Prefs(context)
        assertEquals("", prefs.styleNotes)
        assertNull(context.getSharedPreferences("wirl", Context.MODE_PRIVATE).getString(Prefs.KEY_PROMPT, null))
    }

    @Test fun resetToDefaultsKeepsTheApiKeyAndDictionary() {
        Prefs(context).apply {
            bubbleSizeDp = 72
            styleNotes = "Casual."
            dictationLanguage = "fr-FR"
            dictionary = listOf(DictionaryEntry("Sela"))
        }
        Prefs(context).resetToDefaults()
        val prefs = Prefs(context)
        assertEquals(56, prefs.bubbleSizeDp)
        assertEquals("", prefs.styleNotes)
        assertEquals("", prefs.dictationLanguage)
        assertEquals(listOf("Sela"), prefs.dictionary.map { it.term })
    }

    @Test fun dictionaryPersists() {
        val entries = listOf(DictionaryEntry("Sela", listOf("salad"), "web app"))
        Prefs(context).dictionary = entries
        assertEquals(entries, Prefs(context).dictionary)
    }

    @Test fun corruptDictionaryReadsAsEmpty() {
        context.getSharedPreferences("wirl", Context.MODE_PRIVATE)
            .edit().putString(Prefs.KEY_DICTIONARY, "not json").commit()
        assertTrue(Prefs(context).dictionary.isEmpty())
    }

    @Test fun doneTogglesPersist() {
        Prefs(context).apply {
            showDoneCheck = false
            showDoneMessage = true
        }
        assertFalse(Prefs(context).showDoneCheck)
        assertTrue(Prefs(context).showDoneMessage)
    }

    @Test fun dictationDefaults() {
        val prefs = Prefs(context)
        assertTrue(prefs.dictationCleanup)
        assertEquals("", prefs.dictationLanguage)
    }

    @Test fun dictationSettingsPersist() {
        Prefs(context).apply {
            dictationCleanup = false
            dictationLanguage = "fr-FR"
        }
        assertFalse(Prefs(context).dictationCleanup)
        assertEquals("fr-FR", Prefs(context).dictationLanguage)
    }

    @Test fun dictationCleanupIsOnByDefault() {
        context.getSharedPreferences("wirl", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(Prefs(context).dictationCleanup)
    }

    @Test fun olderSavedOffIsReplacedBySettingsVersionTwo() {
        // An earlier version saved "off" without a settings version. It must not stick.
        context.getSharedPreferences("wirl", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean(Prefs.KEY_DICTATION_CLEANUP, false)
            .commit()
        assertTrue(Prefs(context).dictationCleanup)
    }

    @Test fun userTurningCleanupOffSticksAfterMigration() {
        context.getSharedPreferences("wirl", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).dictationCleanup = false
        assertFalse(Prefs(context).dictationCleanup)
    }
}
