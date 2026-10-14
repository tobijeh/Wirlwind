package org.wirl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.wirl.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.prompt.setText(prefs.systemPrompt)
        binding.bubbleToggle.isChecked = prefs.bubbleEnabled
        binding.bubbleToggle.setOnCheckedChangeListener { _, checked -> prefs.bubbleEnabled = checked }
        binding.doneCheckToggle.isChecked = prefs.showDoneCheck
        binding.doneCheckToggle.setOnCheckedChangeListener { _, checked -> prefs.showDoneCheck = checked }
        binding.doneMessageToggle.isChecked = prefs.showDoneMessage
        binding.doneMessageToggle.setOnCheckedChangeListener { _, checked -> prefs.showDoneMessage = checked }

        setUpBubbleAppearance()

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.resetPrompt.setOnClickListener { binding.prompt.setText(Prefs.DEFAULT_PROMPT) }
        binding.save.setOnClickListener { save() }
        binding.openDictionary.setOnClickListener { startActivity(Intent(this, DictionaryActivity::class.java)) }
        binding.micButton.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.openVoiceSettings.setOnClickListener { openVoiceSettings() }
        binding.openDiagnostics.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        binding.testDictation.setOnClickListener { testDictation() }

        binding.dictationCleanup.isChecked = prefs.dictationCleanup
        binding.dictationCleanup.setOnCheckedChangeListener { _, checked -> prefs.dictationCleanup = checked }
        binding.dictationLanguage.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, DictationLanguages.options.map { it.second }),
        )
        binding.dictationLanguage.setText(DictationLanguages.labelFor(prefs.dictationLanguage), false)
        binding.dictationLanguage.setOnItemClickListener { _, _, position, _ ->
            prefs.dictationLanguage = DictationLanguages.options[position].first
            refreshStatus()
        }
        binding.downloadPack.setOnClickListener {
            val language = DictationLanguage.resolve(prefs.dictationLanguage, Locale.getDefault().toLanguageTag())
            val started = SpeechPacks.download(this, language)
            Toast.makeText(
                this,
                if (started) R.string.download_started else R.string.download_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onDestroy() {
        tester?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Size and opacity sliders. Values save as you drag, so the floating bubble updates live. */
    private fun setUpBubbleAppearance() {
        val preview = BubbleView(this)
        binding.bubblePreview.addView(preview)
        preview.layoutParams = FrameLayout.LayoutParams(0, 0, Gravity.CENTER)

        fun render() {
            val size = prefs.bubbleSizeDp
            val opacity = prefs.bubbleOpacity
            binding.bubbleSizeLabel.text = getString(R.string.bubble_size_value, size)
            binding.bubbleOpacityLabel.text = getString(R.string.bubble_opacity_value, opacity)
            preview.setDiameter((size * resources.displayMetrics.density).roundToInt())
            preview.alpha = opacity / 100f
        }

        binding.bubbleSize.value = prefs.bubbleSizeDp.toFloat()
        binding.bubbleOpacity.value = prefs.bubbleOpacity.toFloat()
        binding.bubbleSize.addOnChangeListener { _, value, _ ->
            prefs.bubbleSizeDp = value.roundToInt()
            render()
        }
        binding.bubbleOpacity.addOnChangeListener { _, value, _ ->
            prefs.bubbleOpacity = value.roundToInt()
            render()
        }
        render()
    }

    private fun refreshEngineRow() {
        val detection = SpeechEngineAndroid.detect(this)
        val tag = LanguageTag.format(DictationLanguage.resolve(prefs.dictationLanguage, Locale.getDefault().toLanguageTag()))
        val order = SpeechEngine.order(detection, prefs.onDevicePackInstalled(tag))
        val first = order.firstOrNull()?.label
        binding.speechEngine.text = "${getString(R.string.speech_engine_title)}: " +
            (first ?: SpeechEngine.describe(detection, onDevicePackInstalled = false))
        binding.openVoiceSettings.visibility = if (order.isEmpty()) View.VISIBLE else View.GONE
    }

    private var tester: EngineTester? = null

    private fun testDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            binding.testResults.text = getString(R.string.test_needs_mic)
            return
        }
        binding.testResults.text = getString(R.string.test_running)
        val runner = tester ?: EngineTester(this) { lines -> binding.testResults.text = lines.joinToString("\n") }
            .also { tester = it }
        runner.run(DictationLanguage.resolve(prefs.dictationLanguage, Locale.getDefault().toLanguageTag()))
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshStatus()
    }

    private fun save() {
        val key = binding.apiKey.text?.toString().orEmpty().trim()
        if (key.isNotEmpty()) {
            prefs.apiKey = key
            binding.apiKey.setText("")
        }
        prefs.systemPrompt = binding.prompt.text?.toString().orEmpty()
        binding.prompt.setText(prefs.systemPrompt)
        refreshStatus()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val key = prefs.apiKey
        binding.apiKeyStatus.text = if (key.isEmpty()) {
            getString(R.string.api_key_missing)
        } else {
            "${getString(R.string.api_key_saved)}: …${key.takeLast(4)}"
        }
        binding.lastResult.text = "Last cleanup: ${prefs.lastResult}"
        binding.openDictionary.text = getString(R.string.dictionary_button, prefs.dictionary.size)
        val micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        binding.micButton.text = "${getString(R.string.mic_title)}: " +
            getString(if (micAllowed) R.string.mic_allowed else R.string.mic_tap_to_allow)
        binding.micButton.isEnabled = !micAllowed

        val language = DictationLanguage.resolve(prefs.dictationLanguage, Locale.getDefault().toLanguageTag())
        val tag = LanguageTag.format(language)
        binding.packStatus.text = getString(R.string.dictation_language_now, DictationLanguages.labelFor(language))
        SpeechPacks.check(this, tag) { status ->
            prefs.setOnDevicePackInstalled(tag, status == DictationLanguage.PackStatus.INSTALLED)
            refreshEngineRow()
            binding.packStatus.text = listOf(
                getString(R.string.dictation_language_now, DictationLanguages.labelFor(language)),
                getString(R.string.pack_status, DictationLanguage.describePack(status)),
            ).joinToString("\n")
            // Offer the download unless the pack is confirmed installed.
            binding.downloadPack.visibility =
                if (status == DictationLanguage.PackStatus.INSTALLED) View.GONE else View.VISIBLE
        }

        refreshEngineRow()
        val enabled = isServiceEnabled()
        binding.accessibilityStatus.setText(
            if (enabled) R.string.accessibility_on else R.string.accessibility_off,
        )
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, BubbleService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == expected }
    }
}
