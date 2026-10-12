package org.wirlwind

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** "Clean up" entry in the text selection menu. Translucent; shows only a spinner. */
class ProcessTextActivity : AppCompatActivity() {

    // Config changes are declared in the manifest, so onCreate runs once per request.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (text.isBlank()) {
            toast(getString(R.string.no_text))
            finishCancelled()
            return
        }

        lifecycleScope.launch {
            try {
                val cleaned = ClaudeClient.clean(this@ProcessTextActivity, text)
                if (readOnly) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cleaned text", cleaned))
                    toast(getString(R.string.copied))
                    finishCancelled()
                } else {
                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, cleaned))
                    finish()
                }
            } catch (e: CleanupException) {
                toast(e.message ?: "Error")
                finishCancelled()
            }
        }
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
