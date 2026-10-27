package org.wirl

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.wirl.databinding.ActivityManualSetupBinding

/** First-run checklist, also opened from Settings. Each item shows its live status and a button. */
class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityManualSetupBinding
    private lateinit var prefs: Prefs
    private var tester: EngineTester? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        prefs.setupShown = true

        binding.saveKey.setOnClickListener {
            val key = binding.keyField.text?.toString().orEmpty().trim()
            if (key.isNotEmpty()) {
                prefs.apiKey = key
                binding.keyField.setText("")
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        binding.getKey.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
        }
        binding.openAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.allowMic.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.openBattery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
        binding.runTest.setOnClickListener { runTest() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        tester?.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val status = status(this)
        val done = SetupChecklist.allDone(status)
        binding.allSet.visibility = if (done) View.VISIBLE else View.GONE
        binding.checklist.visibility = if (done) View.GONE else View.VISIBLE

        row(SetupChecklist.Item.API_KEY, status, binding.apiStatus)
        row(SetupChecklist.Item.ACCESSIBILITY, status, binding.accessStatus)
        row(SetupChecklist.Item.MICROPHONE, status, binding.micStatus)
        row(SetupChecklist.Item.BATTERY, status, binding.batteryStatus)
        row(SetupChecklist.Item.DICTATION_TEST, status, binding.testStatus)
        binding.batteryTip.text = getString(tipFor(Build.MANUFACTURER))
    }

    companion object {
        /** Live status for every checklist item. Also used by Settings to hide the Setup button once all are done. */
        fun status(context: android.content.Context): Map<SetupChecklist.Item, Boolean> {
            val prefs = Prefs(context)
            return SetupChecklist.status(
                apiKeySaved = prefs.apiKey.isNotBlank(),
                accessibilityOn = isAccessibilityOn(context),
                microphoneAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
                dictationTestPassed = prefs.engineTestPassed,
            )
        }

        fun isAccessibilityOn(context: android.content.Context): Boolean {
            val expected = ComponentName(context, BubbleService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            return splitter.any { ComponentName.unflattenFromString(it) == expected }
        }
    }

    /** A green check when the item is done, an amber warning when it still needs attention. */
    private fun row(item: SetupChecklist.Item, status: Map<SetupChecklist.Item, Boolean>, view: android.widget.TextView) {
        val done = status[item] == true
        view.text = getString(labelFor(item))
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (done) R.drawable.ic_done_setup else R.drawable.ic_warning_settings,
            0,
            0,
            0,
        )
        view.setTextColor(getColor(R.color.ink))
    }

    private fun labelFor(item: SetupChecklist.Item): Int = when (item) {
        SetupChecklist.Item.API_KEY -> R.string.setup_api_key
        SetupChecklist.Item.ACCESSIBILITY -> R.string.setup_accessibility
        SetupChecklist.Item.MICROPHONE -> R.string.setup_microphone
        SetupChecklist.Item.BATTERY -> R.string.setup_battery
        SetupChecklist.Item.DICTATION_TEST -> R.string.setup_test
    }

    private fun tipFor(manufacturer: String): Int = when (BatteryTip.brandFor(manufacturer)) {
        BatteryTip.Brand.OPPO_ONEPLUS -> R.string.battery_tip_oppo
        BatteryTip.Brand.XIAOMI -> R.string.battery_tip_xiaomi
        BatteryTip.Brand.SAMSUNG -> R.string.battery_tip_samsung
        BatteryTip.Brand.OTHER -> R.string.battery_tip_other
    }

    private fun runTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            binding.testResults.text = getString(R.string.test_needs_mic)
            return
        }
        binding.testResults.text = getString(R.string.test_running)
        val runner = tester ?: EngineTester(this) { lines ->
            binding.testResults.text = lines.joinToString("\n")
            refresh()
        }.also { tester = it }
        runner.run(DictationLanguage.resolve(prefs.dictationLanguage, java.util.Locale.getDefault().toLanguageTag()))
    }
}
