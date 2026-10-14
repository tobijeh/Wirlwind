package org.wirl

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.View

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun savedEntriesAreDrawnInTheList() {
        Prefs(context).dictionary = listOf(DictionaryEntry("Sela", listOf("salad"), "web app"))
        ActivityScenario.launch(DictionaryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                list.layout(0, 0, 1080, 1920)
                assertEquals(1, list.childCount)
            }
        }
    }
}
