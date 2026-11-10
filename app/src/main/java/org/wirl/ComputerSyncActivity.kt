package org.wirl

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.wirl.databinding.ActivityComputerSyncBinding

/**
 * Computer sync, opened from the Settings Home row and the Setup checklist: pair with Wirl for Windows by QR code, and
 * one card per paired computer with its status, Sync now, Pair again and Unpair. Opening it syncs right away.
 */
class ComputerSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComputerSyncBinding
    private val pairing = PairingFlow(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComputerSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backComputerSync.setOnClickListener { finish() }
        binding.pairComputer.setOnClickListener { pairing.startPairing() }
        pairing.observe { computers, syncing -> render(computers, syncing) }
        pairing.showRepairNoticeIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        pairing.syncNow()
    }

    private fun render(computers: List<PairedComputer>, syncing: Boolean) {
        binding.noComputers.visibility = if (computers.isEmpty()) View.VISIBLE else View.GONE
        pairing.renderCards(binding.computerCards, computers, syncing, R.color.settings_text_secondary, R.color.settings_warning)
    }
}
