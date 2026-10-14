package org.wirl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        binding.styleNotes.setText(prefs.styleNotes)
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
        binding.resetDefaults.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reset_defaults_confirm)
                .setPositiveButton(R.string.reset_defaults) { _, _ ->
                    prefs.resetToDefaults()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.openSetup.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        if (!prefs.setupShown) startActivity(Intent(this, SetupActivity::class.java))
        binding.save.setOnClickListener { save() }
        binding.openDictionary.setOnClickListener { startActivity(Intent(this, DictionaryActivity::class.java)) }
        binding.micButton.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.openVoiceSettings.setOnClickListener { openVoiceSettings() }
        binding.openDiagnostics.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        binding.testDictation.setOnClickListener { testDictation() }

        binding.dictationCleanup.isChecked = prefs.dictationCleanup
        binding.dictationCleanup.setOnCheckedChangeListener { _, checked -> prefs.dictationCleanup = checked }
        binding.silenceBeeps.isChecked = prefs.silenceBeeps
        binding.silenceBeeps.setOnCheckedChangeListener { _, checked -> prefs.silenceBeeps = checked }
        binding.dictationLanguage.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, DictationLanguages.options.map { it.second }),
        )
        binding.dictationLanguage.setText(DictationLanguages.labelFor(prefs.dictationLanguage), false)
        binding.dictationLanguage.setOnItemClickListener { _, _, position, _ ->
            prefs.dictationLanguage = DictationLanguages.options[position].first
            refreshStatus()
        }
        setupWhisperSettings()
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

    private fun engineLabel(engine: DictationEngine): String = getString(
        if (engine == DictationEngine.WHISPER) R.string.whisper_engine_whisper else R.string.whisper_engine_google,
    )

    private fun modelLabel(model: WhisperModel): String = getString(
        when (model) {
            WhisperModel.FAST -> R.string.whisper_model_fast
            WhisperModel.ACCURATE -> R.string.whisper_model_accurate
            WhisperModel.MULTILINGUAL -> R.string.whisper_model_multilingual
        },
    )

    private fun setupWhisperSettings() {
        val engines = DictationEngine.entries
        binding.engineChoice.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, engines.map(::engineLabel)))
        binding.engineChoice.setText(engineLabel(prefs.dictationEngine), false)
        binding.engineChoice.setOnItemClickListener { _, _, position, _ ->
            prefs.dictationEngine = engines[position]
            refreshWhisper()
        }
        val models = WhisperModel.entries
        binding.modelChoice.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, models.map(::modelLabel)))
        binding.modelChoice.setText(modelLabel(prefs.whisperModel), false)
        binding.modelChoice.setOnItemClickListener { _, _, position, _ ->
            prefs.whisperModel = models[position]
            refreshWhisper()
        }
        binding.downloadWhisper.setOnClickListener { downloadModel() }
        refreshWhisper()
    }

    private var downloading = false

    private fun refreshWhisper() {
        val model = prefs.whisperModel
        val size = Formatter.formatShortFileSize(this, model.sizeBytes)
        val ready = WhisperApp.store(this).isReady(model.spec)
        binding.whisperStatus.text = if (ready) {
            getString(R.string.whisper_status_ready, size)
        } else {
            getString(R.string.whisper_status_missing, size)
        }
        binding.downloadWhisper.text = getString(R.string.whisper_download, size)
        binding.downloadWhisper.visibility = if (ready) View.GONE else View.VISIBLE
        binding.downloadWhisper.isEnabled = !downloading
        binding.whisperProgress.visibility = if (downloading) View.VISIBLE else View.GONE
    }

    /** Downloads the chosen model on a background thread, showing progress. Resumes a partial download if there is one. */
    private fun downloadModel() {
        val model = prefs.whisperModel
        downloading = true
        binding.whisperProgress.progress = 0
        refreshWhisper()
        Thread({
            val result = runCatching {
                WhisperApp.downloader(this).download(model.spec) { done, total ->
                    runOnUiThread { binding.whisperProgress.progress = (done * 100 / total).toInt() }
                }
            }
            runOnUiThread {
                downloading = false
                result.exceptionOrNull()?.let {
                    Toast.makeText(this, getString(R.string.whisper_download_failed, it.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                }
                refreshWhisper()
            }
        }, "wirl-model-download").start()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshWhisper()
        // The Setup button goes away once every checklist item is done.
        binding.openSetup.visibility = if (SetupChecklist.allDone(SetupActivity.status(this))) View.GONE else View.VISIBLE
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
        prefs.styleNotes = binding.styleNotes.text?.toString().orEmpty()
        binding.styleNotes.setText(prefs.styleNotes)
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
