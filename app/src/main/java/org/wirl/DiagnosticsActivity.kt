package org.wirl

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.wirl.databinding.ActivityDiagnosticsBinding

/** The last dictation attempts: time, recognizer, error code, and any exception, with a Copy button. */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only reachable with Developer options on. Otherwise the log stays, but this screen doesn't open.
        if (!Prefs(this).developerOptions) {
            finish()
            return
        }
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val labels = WhisperPrompt.THREAD_CHOICES.map { getString(R.string.threads_label, it) }
        binding.threadsChoice.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.threadsChoice.setText(getString(R.string.threads_label, Prefs(this).whisperThreads), false)
        binding.threadsChoice.setOnItemClickListener { _, _, position, _ ->
            Prefs(this).whisperThreads = WhisperPrompt.THREAD_CHOICES[position]
            binding.threadsChoice.setText(labels[position], false)
        }
        binding.copyDiagnostics.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Wirl diagnostics", DiagnosticsLog.copyText(Prefs(this).diagnostics)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val entries = Prefs(this).diagnostics
        binding.diagnosticsText.text = DiagnosticsLog.copyText(entries)
        binding.diagnosticsEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }
}
