package org.wirl

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.wirl.databinding.DialogQuickAddBinding

/** "Add to Wirl dictionary" in the text selection menu. Asks what the selected text should be. */
class QuickAddActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (selected.isEmpty()) {
            finish()
            return
        }

        val repository = DictionaryRepository.get(this)
        val form = DialogQuickAddBinding.inflate(layoutInflater)
        form.selected.text = selected

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_add_title)
            .setView(form.root)
            .setPositiveButton(R.string.add_and_fix, null)
            .setNeutralButton(R.string.spelled_right, null)
            .setNegativeButton(android.R.string.cancel, null)
            // Any way the dialog closes ends this activity. Cancelling leaves the selection alone.
            .setOnDismissListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val term = form.quickTerm.text.toString().trim()
                if (term.isEmpty()) {
                    form.quickTerm.error = getString(R.string.term_required)
                    return@setOnClickListener
                }
                repository.update {
                    DictionaryEditor.addMishearing(it, term, selected, form.quickNote.text.toString())
                }
                if (readOnly) {
                    say(getString(R.string.quick_added))
                } else {
                    // Replace the selection with the correct term.
                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, term))
                }
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                repository.update { DictionaryEditor.addTerm(it, selected, form.quickNote.text.toString()) }
                say(getString(R.string.quick_added))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
