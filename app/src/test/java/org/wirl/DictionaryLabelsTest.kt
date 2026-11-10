package org.wirl

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.wirl.databinding.DialogEntryBinding
import org.wirl.databinding.DialogQuickAddBinding

/** The dictionary's field labels match Wirl for Windows. Only the words on screen; the JSON names don't change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryLabelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val themed = ContextThemeWrapper(context, R.style.Theme_Wirl)
    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
    }

    @After fun tearDown() {
        Prefs(context).dictionary = emptyList()
        Prefs.secureFactory = savedFactory
    }

    private fun layoutOf(view: android.view.View): TextInputLayout {
        var p = view.parent
        while (p !is TextInputLayout) p = p.parent
        return p
    }

    private fun check(field: android.view.View, label: String, placeholder: String, helper: String?) {
        val layout = layoutOf(field)
        assertEquals(label, layout.hint.toString())
        assertEquals(placeholder, layout.placeholderText.toString())
        assertEquals(helper, layout.helperText?.toString())
    }

    @Test fun theEntryFormUsesTheNewLabels() {
        val form = DialogEntryBinding.inflate(LayoutInflater.from(themed))
        check(form.entryTerm, "Correct spelling", "What you want typed, e.g. Sela", null)
        check(
            form.entrySounds, "Wirl types it as", "The wrong words Wirl types instead, e.g. salad, Stella",
            "Copy them from what Wirl actually typed. Separate with commas.",
        )
        check(
            form.entryNote, "Only when (optional)", "e.g. my brother's name, not the verb",
            "Only needed if a wrong word is also a real word you sometimes mean.",
        )
    }

    @Test fun quickAddSaysTheSelectionIsWhatWirlTyped() {
        val form = DialogQuickAddBinding.inflate(LayoutInflater.from(themed))
        assertEquals("Wirl typed", form.selectedLabel.text.toString())
        check(form.quickTerm, "Correct spelling", "What you want typed instead", null)
        check(
            form.quickNote, "Only when (optional)", "e.g. my brother's name, not the verb",
            "Only needed if a wrong word is also a real word you sometimes mean.",
        )
    }

    @Test fun theListShowsTypedAs() {
        Prefs(context).dictionary = listOf(DictionaryEntry("Sela", listOf("salad", "Stella")))
        DictionaryRepository.get(context).reload()
        ActivityScenario.launch(DictionaryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                val list = activity.findViewById<RecyclerView>(R.id.list)
                list.measure(0, 0)
                list.layout(0, 0, 1080, 2000)
                val row = list.getChildAt(0)
                assertEquals("typed as: salad, Stella", row.findViewById<TextView>(R.id.details).text.toString())
            }
        }
    }
}
