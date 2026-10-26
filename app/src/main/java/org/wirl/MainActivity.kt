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
import org.wirl.databinding.ItemWhisperModelBinding
import org.wirl.databinding.SectionHeaderBinding
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
        binding.developerOptions.isChecked = prefs.developerOptions
        binding.developerOptions.setOnCheckedChangeListener { _, checked ->
            prefs.developerOptions = checked
            refreshDeveloperOptions()
        }
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
        setupSections()
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

    private val nativeLoads: Boolean by lazy { WhisperLibrary.loads }

    /** Each settings card gets its icon tile, a title, and one line about what it controls. */
    private fun setupSections() {
        section(binding.cardClaude, R.drawable.ux_wand, R.string.section_claude, R.string.section_claude_sub)
        section(binding.cardDictation, R.drawable.ux_mic, R.string.section_dictation, R.string.section_dictation_sub)
        section(binding.cardSpeech, R.drawable.ux_waves, R.string.section_speech, R.string.section_speech_sub)
        section(binding.cardBubble, R.drawable.ux_send, R.string.section_bubble, R.string.section_bubble_sub)
        section(binding.cardDictionary, R.drawable.ux_text, R.string.section_dictionary, R.string.section_dictionary_sub)
        section(binding.cardDeveloper, R.drawable.ux_refresh, R.string.section_developer, R.string.section_developer_sub)
    }

    private fun section(header: SectionHeaderBinding, icon: Int, title: Int, subtitle: Int) {
        header.sectionIcon.setImageResource(icon)
        header.sectionTitle.setText(title)
        header.sectionSubtitle.setText(subtitle)
    }

    /** Diagnostics is hidden unless Developer options is on. The log itself is kept either way. */
    private fun refreshDeveloperOptions() {
        binding.openDiagnostics.visibility = if (prefs.developerOptions) View.VISIBLE else View.GONE
    }

    private fun setupWhisperSettings() {
        val engines = DictationEngine.entries
        binding.engineChoice.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, engines.map(::engineLabel)))
        binding.engineChoice.setText(engineLabel(prefs.dictationEngine), false)
        binding.engineChoice.setOnItemClickListener { _, _, position, _ ->
            prefs.dictationEngine = engines[position]
            refreshWhisper()
        }
        bindModelRow(binding.modelFast, WhisperModel.FAST)
        bindModelRow(binding.modelAccurate, WhisperModel.ACCURATE)
        bindModelRow(binding.modelMultilingual, WhisperModel.MULTILINGUAL)
        binding.mobileDataSwitch.isChecked = prefs.allowMobileData
        binding.mobileDataSwitch.setOnCheckedChangeListener { _, checked -> prefs.allowMobileData = checked }
        binding.bannerDownload.setOnClickListener { downloadModel(prefs.whisperModel) }
        refreshWhisper()
    }

    private fun bindModelRow(row: ItemWhisperModelBinding, model: WhisperModel) {
        row.select.text = getString(
            R.string.model_row_title,
            modelLabel(model),
            Formatter.formatShortFileSize(this, model.sizeBytes),
        )
        row.select.setOnClickListener {
            prefs.whisperModel = model
            if (!WhisperApp.store(this).isReady(model.spec)) {
                Toast.makeText(this, getString(R.string.selection_not_downloaded, modelLabel(model)), Toast.LENGTH_LONG).show()
            }
            refreshWhisper()
        }
        row.download.setOnClickListener { downloadModel(model) }
        row.delete.setOnClickListener { confirmDelete(model) }
    }

    /** Redraws the speech section: the engine in use and why, the banner, and each model's status and buttons. */
    private fun refreshWhisper() {
        val selected = prefs.whisperModel
        val store = WhisperApp.store(this)
        val selectedReady = store.isReady(selected.spec)
        val anyReady = WhisperModel.entries.any { store.isReady(it.spec) }
        binding.modelBanner.visibility = if (anyReady) View.GONE else View.VISIBLE
        binding.engineStatus.text = engineStatusText(
            EngineChoice.use(prefs.dictationEngine, selectedReady, nativeLoads),
            selected,
        )
        refreshRow(binding.modelFast, WhisperModel.FAST, selected)
        refreshRow(binding.modelAccurate, WhisperModel.ACCURATE, selected)
        refreshRow(binding.modelMultilingual, WhisperModel.MULTILINGUAL, selected)
    }

    private fun engineStatusText(use: EngineUse, selected: WhisperModel): String = when {
        prefs.dictationEngine == DictationEngine.GOOGLE -> getString(R.string.engine_using_google)
        use.engine == DictationEngine.WHISPER -> getString(R.string.engine_using_whisper, modelLabel(selected))
        use.reason == FallbackReason.MODEL_MISSING -> getString(R.string.engine_fallback_model, modelLabel(selected))
        else -> getString(R.string.engine_fallback_native)
    }

    private fun refreshRow(row: ItemWhisperModelBinding, model: WhisperModel, selected: WhisperModel) {
        val store = WhisperApp.store(this)
        val state = ModelStatus.of(store, model)
        row.select.isChecked = model == selected
        row.status.text = when (state) {
            ModelState.Ready -> getString(R.string.model_status_ready)
            is ModelState.Downloading -> getString(R.string.model_status_downloading, state.percent)
            is ModelState.Partial -> getString(R.string.model_status_partial, state.percent)
            ModelState.NotDownloaded -> getString(R.string.model_status_not_downloaded)
        }
        val downloading = state is ModelState.Downloading
        row.progress.visibility = if (downloading) View.VISIBLE else View.GONE
        row.progress.progress = (state as? ModelState.Downloading)?.percent ?: 0
        row.download.visibility = if (state == ModelState.NotDownloaded || state is ModelState.Partial) View.VISIBLE else View.GONE
        row.download.text = if (state is ModelState.Partial) {
            getString(R.string.model_resume)
        } else {
            getString(R.string.model_download, Formatter.formatShortFileSize(this, model.sizeBytes))
        }
        row.delete.visibility = if (state == ModelState.Ready || state is ModelState.Partial) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(model: WhisperModel) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.model_delete_confirm, modelLabel(model)))
            .setPositiveButton(R.string.model_delete) { _, _ ->
                WhisperApp.store(this).delete(model.spec)
                refreshWhisper()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Downloads [model] on a background thread, with progress. Only starts on an unmetered network, or on mobile data when
     * the user has allowed it. A partial download is resumed.
     */
    private fun downloadModel(model: WhisperModel) {
        if (ModelDownloads.isDownloading(model)) return
        when (DownloadPolicy.check(WhisperApp.isOnline(this), WhisperApp.isUnmetered(this), prefs.allowMobileData)) {
            DownloadCheck.NO_NETWORK -> return Toast.makeText(this, R.string.no_network, Toast.LENGTH_LONG).show()
            DownloadCheck.NEEDS_MOBILE_DATA -> return Toast.makeText(this, R.string.needs_mobile_data, Toast.LENGTH_LONG).show()
            DownloadCheck.ALLOWED -> Unit
        }
        ModelDownloads.start(model)
        refreshWhisper()
        Thread({
            val result = runCatching {
                WhisperApp.downloader(this).download(model.spec) { done, total ->
                    // Redraw only when the whole percentage moves, so the progress bar doesn't flood the UI thread.
                    if (ModelDownloads.update(model, ModelStatus.percent(done, total))) runOnUiThread { refreshWhisper() }
                }
            }
            ModelDownloads.finish(model)
            runOnUiThread {
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
        refreshDeveloperOptions()
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
