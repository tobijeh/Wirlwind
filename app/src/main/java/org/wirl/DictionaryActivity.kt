package org.wirl

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.wirl.databinding.ActivityDictionaryBinding
import org.wirl.databinding.DialogEntryBinding
import org.wirl.databinding.ItemPairedComputerBinding

/**
 * The personal dictionary: list, search, add, edit, swipe to delete (with undo), import/export, and pairing with
 * computers for Wi-Fi sync (docs/sync-protocol.md).
 */
class DictionaryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDictionaryBinding
    private lateinit var repository: DictionaryRepository
    private lateinit var model: DictionaryViewModel
    private val adapter = EntryAdapter()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeExport(it) }
        }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readImport(it) }
    }
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onScanned(it) }
    }
    private val localNetworkLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        SyncScheduler.syncNow(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = DictionaryRepository.get(this)
        model = DictionaryViewModel(repository)

        // RecyclerView draws nothing without a layout manager, so this line is required.
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        ItemTouchHelper(SwipeToDelete()).attachToRecyclerView(binding.list)

        binding.search.doAfterTextChanged { model.query.value = it?.toString().orEmpty() }
        binding.add.setOnClickListener { showEditor(null) }
        binding.addFirst.setOnClickListener { showEditor(null) }
        binding.more.setOnClickListener { showMoreMenu() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PairedComputers.list.collect { renderSync(it) } }
                // Keep "5 min ago" current while the screen is open.
                while (true) {
                    delay(60_000)
                    renderSync(PairedComputers.list.value)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Quick add can save while this screen is in the background; pick up anything new.
        repository.reload()
        // Opening the dictionary syncs with paired computers, in the background.
        inBackground {
            PairedComputers.load(applicationContext)
            SyncScheduler.ensureScheduled(applicationContext)
            SyncScheduler.syncNow(applicationContext)
        }
    }

    /** Sync bookkeeping off the main thread. A failure here must never affect the dictionary screen. */
    private fun inBackground(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { android.util.Log.w("WirlSync", "sync setup failed", it) }
        }
    }

    private fun renderSync(computers: List<PairedComputer>) {
        val container = binding.syncStatus
        container.removeAllViews()
        container.visibility = if (computers.isEmpty()) View.GONE else View.VISIBLE
        val now = System.currentTimeMillis()
        for (computer in computers.sortedBy { it.name.lowercase() }) {
            val row = ItemPairedComputerBinding.inflate(layoutInflater, container, false)
            row.syncText.text = when {
                computer.needsPairing -> getString(R.string.pairing_invalid, computer.name)
                computer.lastSyncAt > 0 ->
                    getString(R.string.synced_with, computer.name, TimeAgo.format(this, computer.lastSyncAt, now))
                else -> getString(R.string.not_synced_yet, computer.name)
            }
            row.pairAgain.visibility = if (computer.needsPairing) View.VISIBLE else View.GONE
            row.pairAgain.setOnClickListener { startPairing() }
            row.unpair.setOnClickListener { confirmUnpair(computer) }
            container.addView(row.root)
        }
    }

    private fun startPairing() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.pair_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun onScanned(text: String) {
        val qr = SyncProtocol.parsePairingQr(text)
        if (qr == null) {
            say(getString(R.string.pair_invalid_code))
            return
        }
        inBackground {
            PairedComputers.pair(applicationContext, qr)
            SyncScheduler.ensureScheduled(applicationContext)
            SyncScheduler.syncNow(applicationContext)
        }
        say(getString(R.string.paired_with, qr.name))
        if (LocalNetworkPermission.needsRequest(this)) localNetworkLauncher.launch(LocalNetworkPermission.NAME)
    }

    private fun confirmUnpair(computer: PairedComputer) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.unpair_confirm, computer.name))
            .setPositiveButton(R.string.unpair) { _, _ ->
                inBackground {
                    PairedComputers.unpair(applicationContext, computer.deviceId)
                    SyncScheduler.ensureScheduled(applicationContext)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: DictionaryUiState) {
        adapter.submit(state.entries)
        binding.empty.visibility = if (state.total == 0) View.VISIBLE else View.GONE
        binding.noMatch.visibility = if (state.total > 0 && state.entries.isEmpty()) View.VISIBLE else View.GONE
        binding.noMatch.text = getString(R.string.no_match, state.query)
    }

    private fun showMoreMenu() {
        PopupMenu(this, binding.more).apply {
            inflate(R.menu.dictionary_more)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_pair -> startPairing()
                    R.id.menu_import -> importLauncher.launch(arrayOf("*/*"))
                    R.id.menu_export -> exportLauncher.launch("wirl-dictionary.json")
                }
                true
            }
            show()
        }
    }

    private fun showEditor(existing: DictionaryEntry?) {
        val form = DialogEntryBinding.inflate(layoutInflater)
        form.entryTerm.setText(existing?.term.orEmpty())
        form.entrySounds.setText(existing?.soundsLike?.joinToString(", ").orEmpty())
        form.entryNote.setText(existing?.note.orEmpty())

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_term else R.string.edit_term)
            .setView(form.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) builder.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(existing) }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val term = form.entryTerm.text.toString().trim()
                if (term.isEmpty()) {
                    form.entryTerm.error = getString(R.string.term_required)
                    return@setOnClickListener
                }
                val entry = DictionaryEntry(
                    term = term,
                    soundsLike = DictionaryEditor.parseSoundsLike(form.entrySounds.text.toString()),
                    note = form.entryNote.text.toString(),
                )
                repository.update { list ->
                    if (existing == null) {
                        DictionaryEditor.merge(list, listOf(entry))
                    } else {
                        DictionaryEditor.replace(list, DictionaryEditor.indexOfEntry(list, existing), entry)
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(entry: DictionaryEntry) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> deleteWithUndo(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteWithUndo(entry: DictionaryEntry) {
        repository.update { list -> DictionaryEditor.delete(list, DictionaryEditor.indexOfEntry(list, entry)) }
        Snackbar.make(binding.root, getString(R.string.deleted_term, entry.term), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { repository.update { DictionaryEditor.restore(it, entry) } }
            .show()
    }

    /** Swipe left on a row to delete it. The undo snackbar brings it back. */
    private inner class SwipeToDelete : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val entry = adapter.itemAt(vh.bindingAdapterPosition) ?: return
            deleteWithUndo(entry)
        }
    }

    private fun writeExport(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()
                ?.use { it.write(DictionaryJson.encodeExport(repository.entries.value)) }
        }.onSuccess { say(getString(R.string.exported)) }
            .onFailure { say(getString(R.string.export_failed)) }
    }

    private fun readImport(uri: Uri) {
        val incoming = runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            DictionaryJson.decodeImport(text)
        }.getOrElse {
            say(getString(R.string.import_failed))
            return
        }
        repository.update { DictionaryEditor.merge(it, incoming) }
        say(getString(R.string.imported, incoming.size))
    }

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private var items: List<DictionaryEntry> = emptyList()

        fun submit(list: List<DictionaryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): DictionaryEntry? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.term.text = entry.term
            val details = listOfNotNull(
                entry.soundsLike.takeIf { it.isNotEmpty() }
                    ?.let { getString(R.string.sounds_like_summary, it.joinToString(", ")) },
                entry.note.takeIf { it.isNotBlank() },
            ).joinToString("\n")
            holder.details.text = details
            holder.details.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { showEditor(entry) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val term: TextView = view.findViewById(R.id.term)
            val details: TextView = view.findViewById(R.id.details)
        }
    }
}
