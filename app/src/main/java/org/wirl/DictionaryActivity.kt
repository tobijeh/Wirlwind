package org.wirl

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import org.wirl.databinding.ActivityDictionaryBinding
import org.wirl.databinding.DialogEntryBinding

/** The personal dictionary: list, search, add, edit, swipe to delete (with undo), and import/export. */
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
        binding.importButton.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.exportButton.setOnClickListener { exportLauncher.launch("wirl-dictionary.json") }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Quick add can save while this screen is in the background; pick up anything new.
        repository.reload()
    }

    private fun render(state: DictionaryUiState) {
        adapter.submit(state.entries)
        binding.empty.visibility = if (state.total == 0) View.VISIBLE else View.GONE
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
            .setAction(R.string.undo) { repository.update { DictionaryEditor.merge(it, listOf(entry)) } }
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
                ?.use { it.write(DictionaryJson.encode(repository.entries.value)) }
        }.onSuccess { say(getString(R.string.exported)) }
            .onFailure { say(getString(R.string.export_failed)) }
    }

    private fun readImport(uri: Uri) {
        val incoming = runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            DictionaryJson.decode(text)
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
