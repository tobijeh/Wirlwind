package org.wirl

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One color scheme for the whole app: dark, like Settings, whatever the phone's light or dark mode. Setup headings are
 * white, so they can be read on the dark cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorSchemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        Prefs(context).setupShown = true
    }

    @After fun tearDown() {
        Prefs.secureFactory = savedFactory
    }

    private fun color(activity: Activity, attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
    }

    private fun <A : Activity> check(type: Class<A>, block: (A) -> Unit) {
        ActivityScenario.launch(type).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                block(activity)
            }
        }
    }

    private fun setupHeadingsAreWhiteOnDark() = check(SetupActivity::class.java) { activity ->
        val white = activity.getColor(R.color.settings_text)
        for (id in listOf(R.id.apiStatus, R.id.accessStatus, R.id.micStatus, R.id.batteryStatus, R.id.testStatus)) {
            assertEquals("heading ${activity.resources.getResourceEntryName(id)}", white, activity.findViewById<TextView>(id).currentTextColor)
        }
        assertEquals(activity.getColor(R.color.settings_bg), color(activity, android.R.attr.colorBackground))
    }

    private fun everyScreenUsesTheSettingsColors() {
        for (type in listOf(MainActivity::class.java, SetupActivity::class.java, DictionaryActivity::class.java, ComputerSyncActivity::class.java)) {
            check(type) { activity ->
                assertEquals(type.simpleName, activity.getColor(R.color.settings_bg), color(activity, android.R.attr.colorBackground))
                assertEquals(type.simpleName, activity.getColor(R.color.settings_text), color(activity, android.R.attr.textColorPrimary))
            }
        }
    }

    @Test @Config(qualifiers = "notnight") fun setupHeadingsAreWhiteInLightMode() = setupHeadingsAreWhiteOnDark()

    @Test @Config(qualifiers = "night") fun setupHeadingsAreWhiteInDarkMode() = setupHeadingsAreWhiteOnDark()

    @Test @Config(qualifiers = "notnight") fun screensLookTheSameInLightMode() = everyScreenUsesTheSettingsColors()

    @Test @Config(qualifiers = "night") fun screensLookTheSameInDarkMode() = everyScreenUsesTheSettingsColors()

    @Test @Config(qualifiers = "night") fun paletteTokensDontChangeWithTheMode() {
        assertEquals(context.getColor(R.color.settings_bg), context.getColor(R.color.paper))
        assertEquals(context.getColor(R.color.settings_card), context.getColor(R.color.card))
    }
}
