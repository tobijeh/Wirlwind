package org.wirl

import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.wirl.databinding.ActivityMainBinding
import org.wirl.databinding.ItemWhisperModelBinding
import org.wirl.databinding.SectionHeaderBinding
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        /** Opens Settings on a page instead of Home. */
        const val EXTRA_PAGE = "org.wirl.page"
        const val PAGE_DICTATION = "dictation"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val pairing = PairingFlow(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.styleNotes.setText(prefs.styleNotes)
        binding.bubbleToggle.isChecked = prefs.bubbleEnabled
        binding.bubbleToggle.setOnCheckedChangeListener { _, checked ->
            prefs.bubbleEnabled = checked
            refreshHome()
        }
        binding.doneCheckToggle.isChecked = prefs.showDoneCheck
        binding.doneCheckToggle.setOnCheckedChangeListener { _, checked -> prefs.showDoneCheck = checked }
        binding.doneMessageToggle.isChecked = prefs.showDoneMessage
        binding.doneMessageToggle.setOnCheckedChangeListener { _, checked -> prefs.showDoneMessage = checked }

        setUpBubbleAppearance()

        setupNavigation()
        observeComputerSync()
        // Once after the upgrade from Wi-Fi sync: the old pairing was removed, so ask to pair again.
        pairing.showRepairNoticeIfNeeded { startActivity(Intent(this, ComputerSyncActivity::class.java)) }
        binding.styleNotes.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveStyleNotes() }
        binding.apiKey.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveApiKey() }
        binding.apiKey.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) saveApiKey()
            false
        }
        binding.appVersion.setOnClickListener {
            // Like Android's build number: seven taps reveal the developer options.
            if (++versionTaps >= 7) refreshDeveloperOptions()
        }
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
        if (!prefs.setupShown) startActivity(Intent(this, SetupActivity::class.java))
        binding.micButton.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.advancedToggle.setOnClickListener {
            binding.advancedBody.visibility = if (binding.advancedBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
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
        setupSections()
        setupWhisperSettings()
        binding.downloadPack.setOnClickListener {
            val started = SpeechPacks.download(this, googleLanguage())
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

    private fun modelLabel(model: WhisperModel): String = getString(
        when (model) {
            WhisperModel.FAST -> R.string.whisper_model_fast
            WhisperModel.ACCURATE -> R.string.whisper_model_accurate
            WhisperModel.CANTONESE -> R.string.whisper_model_cantonese
            WhisperModel.MANDARIN -> R.string.whisper_model_mandarin
        },
    )

    /** The model's plain name for the home summary, without the file name or the language note. */
    private fun shortModelName(model: WhisperModel): String = getString(
        when (model) {
            WhisperModel.FAST -> R.string.model_name_fast
            WhisperModel.ACCURATE -> R.string.model_name_accurate
            WhisperModel.CANTONESE -> R.string.model_name_cantonese
            WhisperModel.MANDARIN -> R.string.model_name_mandarin
        },
    )

    /** Google's recognizer language: always the chosen model's language. */
    private fun googleLanguage(): String = prefs.whisperModel.language.googleTag(Locale.getDefault().toLanguageTag())

    private fun languageName(language: SpeechLanguage): String = getString(
        when (language) {
            SpeechLanguage.ENGLISH -> R.string.language_english
            SpeechLanguage.CANTONESE -> R.string.language_cantonese
            SpeechLanguage.MANDARIN -> R.string.language_mandarin
        },
    )

    /** Whether the selected model's engine loads on this phone. */
    private fun nativeLoads(model: WhisperModel): Boolean = ModelLibraries.loads(model)

    /** The Speech quality cards, in picker order. */
    private val modelRows: List<Pair<WhisperModel, ItemWhisperModelBinding>> by lazy {
        listOf(
            WhisperModel.FAST to binding.modelFast,
            WhisperModel.ACCURATE to binding.modelAccurate,
            WhisperModel.CANTONESE to binding.modelCantonese,
            WhisperModel.MANDARIN to binding.modelMandarin,
        )
    }

    /** Each settings card gets its icon tile, a title, and one line about what it controls. */
    private fun setupSections() {
        section(binding.cardClaude, R.drawable.ux_wand, R.string.section_claude, R.string.section_claude_sub)
        section(binding.cardDictation, R.drawable.ux_mic, R.string.section_dictation, R.string.section_dictation_sub)
        section(binding.cardSpeech, R.drawable.ux_waves, R.string.section_speech, R.string.section_speech_sub)
        section(binding.cardBubble, R.drawable.ux_send, R.string.section_bubble, R.string.section_bubble_sub)
        section(binding.cardIcon, R.drawable.ux_logo_tile, R.string.section_icon, R.string.section_icon_sub)
        section(binding.cardAppearance, R.drawable.ux_wand, R.string.section_appearance, R.string.section_appearance_sub)
    }

    private fun section(header: SectionHeaderBinding, icon: Int, title: Int, subtitle: Int) {
        header.sectionIcon.setImageResource(icon)
        header.sectionTitle.setText(title)
        header.sectionSubtitle.setText(subtitle)
    }

    /**
     * Diagnostics is hidden unless Developer options is on. The log itself is kept either way. The Developer card shows
     * once the version has been tapped seven times, or when the option is already on.
     */
    private fun refreshDeveloperOptions() {
        val shown = if (prefs.developerOptions) View.VISIBLE else View.GONE
        binding.openDiagnostics.visibility = shown
        // Checking Google's recognizers is for troubleshooting, so it lives with Diagnostics.
        binding.testDictation.visibility = shown
        binding.testResults.visibility = shown
        binding.developerCard.visibility = if (prefs.developerOptions || versionTaps >= 7) View.VISIBLE else View.GONE
    }

    private var versionTaps = 0

    /** Each home row opens its page. Dictionary and Setup have their own screens, so they open those. */
    private fun setupNavigation() {
        val pages = listOf(binding.pageHome, binding.pageDictation, binding.pageCleanup, binding.pageBubble, binding.pageAbout)
        fun show(page: View) = pages.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
        // Setup's "Speech model downloaded" button opens the Dictation page directly.
        if (intent?.getStringExtra(EXTRA_PAGE) == PAGE_DICTATION) show(binding.pageDictation)
        binding.rowDictation.setOnClickListener { show(binding.pageDictation) }
        binding.rowCleanup.setOnClickListener { show(binding.pageCleanup) }
        binding.rowBubble.setOnClickListener { show(binding.pageBubble) }
        binding.rowAbout.setOnClickListener { show(binding.pageAbout) }
        binding.rowDictionary.setOnClickListener { startActivity(Intent(this, DictionaryActivity::class.java)) }
        binding.rowComputerSync.setOnClickListener { startActivity(Intent(this, ComputerSyncActivity::class.java)) }
        binding.rowSetup.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        binding.backDictation.setOnClickListener { show(binding.pageHome) }
        binding.backCleanup.setOnClickListener { show(binding.pageHome) }
        binding.backBubble.setOnClickListener { show(binding.pageHome) }
        binding.backAbout.setOnClickListener { show(binding.pageHome) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.pageHome.visibility == View.VISIBLE) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    show(binding.pageHome)
                }
            }
        })
        binding.appVersion.text = getString(R.string.version_label, appVersionName())
    }

    private fun appVersionName(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    /** The home screen: the on/off line, the health line, and each row's current value. */
    private fun refreshHome() {
        binding.statusTitle.setText(if (prefs.bubbleEnabled) R.string.status_on else R.string.status_off)
        binding.statusSubtitle.setText(R.string.status_subtitle)
        val missing = HomeSummary.needingAttention(SetupActivity.status(this))
        binding.healthLine.text = if (missing == 0) {
            getString(R.string.health_ok)
        } else {
            resources.getQuantityString(R.plurals.health_attention, missing, missing)
        }
        binding.healthLine.setTextColor(
            getColor(if (missing == 0) R.color.settings_success else R.color.settings_warning),
        )
        // The model's name says its language, so the row matches the Dictation page.
        binding.dictationSummary.text = shortModelName(prefs.whisperModel)
        binding.cleanupSummary.text = when {
            !prefs.dictationCleanup -> getString(R.string.summary_cleanup_off)
            prefs.styleNotes.isNotBlank() -> getString(R.string.summary_cleanup_notes, getString(R.string.summary_cleanup_on))
            else -> getString(R.string.summary_cleanup_on)
        }
        val terms = DictionarySync.visible(prefs.dictionary).size
        binding.dictionarySummary.text = resources.getQuantityString(R.plurals.summary_dictionary, terms, terms)
        refreshComputerSync(PairedComputers.list.value, DictionarySyncEngine.running.value)
        binding.bubbleSummary.setText(R.string.summary_bubble)
        if (missing == 0) {
            binding.setupSummary.text = getString(R.string.summary_setup_all_set)
            binding.setupSummary.setTextColor(getColor(R.color.settings_success))
        } else {
            binding.setupSummary.text = resources.getQuantityString(R.plurals.summary_setup_attention, missing, missing)
            binding.setupSummary.setTextColor(getColor(R.color.settings_warning))
        }
        binding.aboutSummary.text = getString(R.string.summary_about, appVersionName())
    }

    /** The Computer sync row: "Pair with your computer", "Synced with … 2 min ago", or an amber problem. */
    private fun refreshComputerSync(computers: List<PairedComputer>, syncing: Boolean) {
        val (text, warning) = SyncStatusText.home(this, computers, syncing, System.currentTimeMillis())
        binding.computerSyncSummary.text = text
        binding.computerSyncSummary.setTextColor(getColor(if (warning) R.color.settings_warning else R.color.settings_text_secondary))
    }

    /** Keeps the Computer sync row in step with pairing changes and syncs that finish while Settings is open. */
    private fun observeComputerSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) { runCatching { PairedComputers.load(applicationContext) } }
                launch {
                    PairedComputers.list.combine(DictionarySyncEngine.running) { list, running -> list to running }
                        .collect { (list, running) -> refreshComputerSync(list, running) }
                }
                while (true) {
                    delay(60_000)
                    refreshComputerSync(PairedComputers.list.value, DictionarySyncEngine.running.value)
                }
            }
        }
    }

    private fun saveApiKey() {
        val key = binding.apiKey.text?.toString().orEmpty().trim()
        if (key.isNotEmpty()) {
            prefs.apiKey = key
            binding.apiKey.setText("")
            refreshStatus()
        }
    }

    private fun saveStyleNotes() {
        prefs.styleNotes = binding.styleNotes.text?.toString().orEmpty()
    }

    override fun onPause() {
        // Style notes save as the screen leaves, even if the field still has focus.
        saveStyleNotes()
        super.onPause()
    }

    private fun setupWhisperSettings() {
        modelRows.forEach { (model, row) -> bindModelRow(row, model) }
        binding.mobileDataSwitch.isChecked = prefs.allowMobileData
        binding.mobileDataSwitch.setOnCheckedChangeListener { _, checked -> prefs.allowMobileData = checked }
        binding.bannerDownload.setOnClickListener { downloadModel(prefs.whisperModel) }
        refreshWhisper()
    }

    private fun bindModelRow(row: ItemWhisperModelBinding, model: WhisperModel) {
        row.select.text = shortModelName(model)
        row.description.text = getString(
            when (model) {
                WhisperModel.FAST -> R.string.quality_fast_desc
                WhisperModel.ACCURATE -> R.string.quality_accurate_desc
                WhisperModel.CANTONESE -> R.string.quality_cantonese_desc
                WhisperModel.MANDARIN -> R.string.quality_mandarin_desc
            },
        )
        row.select.setOnClickListener {
            // Ticking turns the model on for quick switch. It never starts a download: that's only the Download button.
            if (row.select.isChecked && !isModelReady(model)) {
                refreshWhisper()
                return@setOnClickListener
            }
            val choice = ModelCycle.toggle(prefs.activeModels, prefs.whisperModel, ::isModelReady, model, row.select.isChecked)
            if (choice == null) {
                Toast.makeText(this, R.string.quick_switch_keep_one, Toast.LENGTH_SHORT).show()
            } else {
                prefs.activeModels = choice.active
                prefs.whisperModel = choice.current
            }
            refreshWhisper()
        }
        row.useNow.setOnClickListener {
            prefs.activeModels = prefs.activeModels + model
            prefs.whisperModel = model
            refreshWhisper()
        }
        row.download.setOnClickListener { downloadModel(model) }
        row.cancel.setOnClickListener {
            ModelDownloads.cancel(model)
            row.cancel.isEnabled = false
        }
        row.delete.setOnClickListener { confirmDelete(model) }
    }

    /** Redraws the speech section: the engine in use and why, the banner, and each model's status and buttons. */
    private fun refreshWhisper() {
        val selected = prefs.whisperModel
        val store = WhisperApp.store(this)
        val selectedReady = store.isReady(selected.spec)
        val anyReady = WhisperModel.entries.any { store.isReady(it.spec) }
        binding.modelBanner.visibility = if (anyReady) View.GONE else View.VISIBLE
        binding.engineStatus.text = engineStatusText(currentEngine(), selected)
        modelRows.forEach { (model, row) -> refreshRow(row, model, selected) }
        // The demo switches between the user's own ticked models once there are two, otherwise EN and 粵.
        binding.quickSwitchDemo.tags = ModelCycle.members(prefs.activeModels, selected, ::isModelReady).map { it.tag }
    }

    /**
     * The engine dictation uses right now: the chosen model on this phone, or Google's recognizer as the backup while
     * that model isn't downloaded or its engine won't load. There's no setting for it.
     */
    private fun currentEngine(): EngineUse {
        val selected = prefs.whisperModel
        return EngineChoice.use(DictationEngine.WHISPER, WhisperApp.store(this).isReady(selected.spec), nativeLoads(selected))
    }

    private fun googleInUse(): Boolean = currentEngine().engine == DictationEngine.GOOGLE

    private fun engineStatusText(use: EngineUse, selected: WhisperModel): String = when {
        use.engine == DictationEngine.WHISPER -> getString(
            if (selected.engine == ModelEngine.SENSE_VOICE) R.string.engine_using_sensevoice else R.string.engine_using_whisper,
            modelLabel(selected),
        )
        use.reason == FallbackReason.MODEL_MISSING -> getString(R.string.engine_fallback_model, modelLabel(selected))
        else -> getString(R.string.engine_fallback_native, if (selected.engine == ModelEngine.SENSE_VOICE) "SenseVoice" else "Whisper")
    }

    private fun refreshRow(row: ItemWhisperModelBinding, model: WhisperModel, selected: WhisperModel) {
        val store = WhisperApp.store(this)
        val state = ModelStatus.of(store, model)
        val on = model in prefs.activeModels
        row.select.isChecked = on
        // Only a downloaded model can be ticked on. One that's already on can always be ticked off.
        row.select.isEnabled = on || state == ModelState.Ready
        row.useNow.visibility = if (state == ModelState.Ready && model != selected) View.VISIBLE else View.GONE
        row.status.text = when (state) {
            ModelState.Ready -> getString(
                when {
                    model == selected -> R.string.model_status_in_use
                    on -> R.string.model_status_ready
                    else -> R.string.model_status_off
                },
            )
            // An archive model is unpacked after the last byte arrives, which takes a little while.
            is ModelState.Downloading -> if (state.percent >= 100 && model.spec.isArchive) {
                getString(R.string.model_status_unpacking)
            } else {
                getString(R.string.model_status_downloading, state.percent)
            }
            is ModelState.Partial -> getString(R.string.model_status_partial, state.percent)
            ModelState.NotDownloaded -> if (model.sharesFilesWith.isNotEmpty()) {
                getString(R.string.model_size_shared, Formatter.formatShortFileSize(this, model.sizeBytes))
            } else {
                getString(R.string.model_size, Formatter.formatShortFileSize(this, model.sizeBytes))
            }
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
        // Cancel while bytes are arriving. Unpacking can't be stopped halfway, so it's hidden then.
        row.cancel.visibility = if (downloading && (state as ModelState.Downloading).percent < 100) View.VISIBLE else View.GONE
        row.cancel.isEnabled = !ModelDownloads.isCancelled(model)
        // Chosen but not on the phone: say what dictation does meanwhile, next to the Download button.
        val chosenMissing = model == selected && state != ModelState.Ready && !downloading
        row.notice.visibility = if (chosenMissing) View.VISIBLE else View.GONE
        row.notice.text = getString(
            when (model.language) {
                SpeechLanguage.CANTONESE -> R.string.model_not_downloaded_notice_cantonese
                SpeechLanguage.MANDARIN -> R.string.model_not_downloaded_notice_mandarin
                SpeechLanguage.ENGLISH -> R.string.model_not_downloaded_notice
            },
            Formatter.formatShortFileSize(this, model.sizeBytes),
        )
    }

    private fun isModelReady(model: WhisperModel): Boolean = WhisperApp.store(this).isReady(model.spec)

    /**
     * A finished download turns its model on for quick switch. If the model in use isn't on the phone (dictation is using
     * Google's recognizer as the backup), the new one becomes the one in use.
     */
    private fun onModelDownloaded(model: WhisperModel) {
        prefs.activeModels = prefs.activeModels + model
        if (!isModelReady(prefs.whisperModel)) prefs.whisperModel = model
    }

    private fun confirmDelete(model: WhisperModel) {
        MaterialAlertDialogBuilder(this)
            .setMessage(
                if (model.sharesFilesWith.isNotEmpty()) {
                    getString(R.string.model_delete_confirm_shared)
                } else {
                    getString(R.string.model_delete_confirm, modelLabel(model))
                },
            )
            .setPositiveButton(R.string.model_delete) { _, _ ->
                WhisperApp.store(this).delete(model.spec)
                // A deleted model leaves quick switch. The one in use stays chosen and uses Google's recognizer meanwhile.
                val gone = setOf(model) + model.sharesFilesWith
                prefs.activeModels = prefs.activeModels - (gone - prefs.whisperModel)
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
                WhisperApp.downloader(this).download(model.spec, cancelled = { ModelDownloads.isCancelled(model) }) { done, total ->
                    // Redraw only when the whole percentage moves, so the progress bar doesn't flood the UI thread.
                    if (ModelDownloads.update(model, ModelStatus.percent(done, total))) runOnUiThread { refreshWhisper() }
                }
            }
            ModelDownloads.finish(model)
            runOnUiThread {
                when (val error = result.exceptionOrNull()) {
                    null -> onModelDownloaded(model)
                    is ModelDownloader.CancelledException ->
                        Toast.makeText(this, R.string.model_download_cancelled, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(
                        this,
                        getString(R.string.whisper_download_failed, error.message ?: "unknown error"),
                        Toast.LENGTH_LONG,
                    ).show()
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
        refreshHome()
    }

    private lateinit var bubblePreviewView: BubbleView
    private val iconCells = mutableMapOf<String, FrameLayout>()

    /** Preview, icon, size and opacity. Each change saves as it happens, so the floating bubble updates live. */
    private fun setUpBubbleAppearance() {
        bubblePreviewView = BubbleView(this)
        binding.bubblePreview.addView(bubblePreviewView)
        bubblePreviewView.layoutParams = FrameLayout.LayoutParams(0, 0, Gravity.CENTER)
        setUpIconPicker()

        binding.bubbleSize.value = prefs.bubbleSizeDp.toFloat()
        binding.bubbleOpacity.value = prefs.bubbleOpacity.toFloat()
        binding.bubbleSize.addOnChangeListener { _, value, _ ->
            prefs.bubbleSizeDp = value.roundToInt()
            renderBubbleAppearance()
        }
        binding.bubbleOpacity.addOnChangeListener { _, value, _ ->
            prefs.bubbleOpacity = value.roundToInt()
            renderBubbleAppearance()
        }
        renderBubbleAppearance()
    }

    private fun renderBubbleAppearance() {
        val size = prefs.bubbleSizeDp
        val opacity = prefs.bubbleOpacity
        binding.bubbleSizeLabel.text = getString(R.string.bubble_size_value, size)
        binding.bubbleOpacityLabel.text = getString(R.string.bubble_opacity_value, opacity)
        bubblePreviewView.setIcon(BubbleIcons.find(prefs.bubbleIcon).drawable)
        bubblePreviewView.setDiameter((size * resources.displayMetrics.density).roundToInt())
        bubblePreviewView.alpha = opacity / 100f
        refreshIconPicker()
    }

    /** Five circles per row. Tapping one saves it. The chosen one gets an accent ring and a check badge. */
    private fun setUpIconPicker() {
        val density = resources.displayMetrics.density
        val cellPx = (52 * density).roundToInt()
        val badgePx = (18 * density).roundToInt()
        val badgePad = (3 * density).roundToInt()
        val ringPx = (3 * density).roundToInt()
        binding.iconGrid.removeAllViews()
        iconCells.clear()
        BubbleIcons.ALL.chunked(5).forEach { rowIcons ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            rowIcons.forEach { icon ->
                val slot = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val circle = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(cellPx, cellPx, Gravity.CENTER)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        prefs.bubbleIcon = icon.id
                        renderBubbleAppearance()
                    }
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(icon.drawable)
                        scaleType = ImageView.ScaleType.FIT_XY
                        layoutParams = FrameLayout.LayoutParams(cellPx, cellPx, Gravity.CENTER)
                    })
                    addView(View(this@MainActivity).apply {
                        tag = "ring"
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(ringPx, ContextCompat.getColor(this@MainActivity, R.color.settings_accent))
                        }
                        visibility = View.GONE
                        layoutParams = FrameLayout.LayoutParams(cellPx, cellPx, Gravity.CENTER)
                    })
                    addView(ImageView(this@MainActivity).apply {
                        tag = "check"
                        setImageResource(R.drawable.ic_check)
                        setPadding(badgePad, badgePad, badgePad, badgePad)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(ContextCompat.getColor(this@MainActivity, R.color.settings_success))
                        }
                        layoutParams = FrameLayout.LayoutParams(badgePx, badgePx, Gravity.BOTTOM or Gravity.END)
                    })
                }
                slot.addView(circle)
                row.addView(slot)
                iconCells[icon.id] = circle
            }
            binding.iconGrid.addView(row)
        }
        refreshIconPicker()
    }

    private fun refreshIconPicker() {
        val selected = BubbleIcons.find(prefs.bubbleIcon).id
        for (icon in BubbleIcons.ALL) {
            val circle = iconCells[icon.id] ?: continue
            val on = icon.id == selected
            circle.findViewWithTag<View>("ring").visibility = if (on) View.VISIBLE else View.GONE
            circle.findViewWithTag<View>("check").visibility = if (on) View.VISIBLE else View.GONE
            val label = getString(icon.label)
            circle.contentDescription = if (on) getString(R.string.icon_selected, label) else label
            circle.isSelected = on
        }
    }

    private fun refreshEngineRow() {
        val detection = SpeechEngineAndroid.detect(this)
        val order = SpeechEngine.order(detection, prefs.onDevicePackInstalled(googleLanguage()))
        val first = order.firstOrNull()?.label
        binding.speechEngine.text = "${getString(R.string.speech_engine_title)}: " +
            (first ?: SpeechEngine.describe(detection, onDevicePackInstalled = false))
        val google = googleInUse()
        binding.speechEngine.visibility = if (google) View.VISIBLE else View.GONE
        binding.openVoiceSettings.visibility = if (google && order.isEmpty()) View.VISIBLE else View.GONE
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
        runner.run(googleLanguage())
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

    private fun refreshStatus() {
        val key = prefs.apiKey
        binding.apiKeyStatus.text = if (key.isEmpty()) {
            getString(R.string.api_key_missing)
        } else {
            "${getString(R.string.api_key_saved)}: …${key.takeLast(4)}"
        }
        val micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // The button shows only when the microphone still needs allowing.
        binding.micButton.visibility = if (micAllowed) View.GONE else View.VISIBLE
        binding.micButton.text = getString(R.string.mic_allow_button)
        // The Microphone card is only for a problem: hidden once the microphone is allowed.
        val micVisibility = if (micAllowed) View.GONE else View.VISIBLE
        binding.cardDictation.root.visibility = micVisibility
        binding.micCard.visibility = micVisibility
        binding.micState.text = getString(R.string.mic_state_needed)
        binding.micState.setTextColor(getColor(R.color.settings_warning))

        // The speech-pack controls only apply to Google's recognizer, so they show only while it's the backup in use.
        val google = googleInUse()
        binding.packStatus.visibility = if (google) View.VISIBLE else View.GONE

        val tag = googleLanguage()
        val languageLabel = languageName(prefs.whisperModel.language)
        binding.packStatus.text = getString(R.string.dictation_language_now, languageLabel)
        SpeechPacks.check(this, tag) { status ->
            prefs.setOnDevicePackInstalled(tag, status == DictationLanguage.PackStatus.INSTALLED)
            refreshEngineRow()
            binding.packStatus.text = listOf(
                getString(R.string.dictation_language_now, languageLabel),
                getString(R.string.pack_status, DictationLanguage.describePack(status)),
            ).joinToString("\n")
            // Offer the download unless the pack is confirmed installed.
            binding.downloadPack.visibility =
                if (google && status != DictationLanguage.PackStatus.INSTALLED) View.VISIBLE else View.GONE
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
