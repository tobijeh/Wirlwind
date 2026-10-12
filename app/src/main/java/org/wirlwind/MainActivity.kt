package org.wirlwind

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.wirlwind.databinding.ActivityMainBinding

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

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.resetPrompt.setOnClickListener { binding.prompt.setText(Prefs.DEFAULT_PROMPT) }
        binding.save.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
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
