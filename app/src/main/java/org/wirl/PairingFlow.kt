package org.wirl

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.wirl.databinding.ItemComputerCardBinding
import org.wirl.databinding.ItemPairedComputerBinding

/**
 * A sync while the user is looking: when a sync screen opens, after a QR scan, and on "Sync now". It runs right away on
 * [Dispatchers.IO], tied to the screen's lifecycle, instead of waiting for WorkManager. It goes through the same
 * [DictionarySyncEngine.syncAll] lock as the background worker, so the two never overlap.
 */
object ForegroundSync {
    fun start(owner: LifecycleOwner, context: Context, pair: SyncProtocol.PairingQr? = null) {
        val app = context.applicationContext
        owner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (pair != null) PairedComputers.pair(app, pair) else PairedComputers.load(app)
                SyncScheduler.ensureScheduled(app)
                // A sync already running (say, the background worker) will publish its result; don't queue another.
                if (pair != null || !DictionarySyncEngine.running.value) {
                    if (PairedComputers.current(app).any { !it.needsPairing }) DictionarySyncEngine.syncAll(app)
                }
            }.onFailure { Log.w("WirlSync", "foreground sync failed", it) }
        }
    }
}

/**
 * Pairing and paired-computer rows, shared by the Dictionary screen and the Computer sync screen: the QR scanner, the
 * local network permission, Unpair, Sync now, and the status lines. Create it as a property of the activity, so its
 * result launchers are registered before the activity starts.
 */
class PairingFlow(private val activity: AppCompatActivity) {
    private val scanLauncher = activity.registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onScanned(it) }
    }
    private val localNetworkLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        syncNow()
    }

    /** Syncs now, in the foreground. */
    fun syncNow() = ForegroundSync.start(activity, activity)

    fun startPairing() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(activity.getString(R.string.pair_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /** Handles a scanned code. Public so tests can feed a code without the camera. */
    fun onScanned(text: String) {
        val qr = SyncProtocol.parsePairingQr(text)
        if (qr == null) {
            say(activity.getString(R.string.pair_invalid_code))
            return
        }
        ForegroundSync.start(activity, activity, pair = qr)
        say(activity.getString(R.string.paired_with, qr.name))
        if (LocalNetworkPermission.needsRequest(activity)) localNetworkLauncher.launch(LocalNetworkPermission.NAME)
    }

    fun confirmUnpair(computer: PairedComputer) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(R.string.unpair_confirm, computer.name))
            .setPositiveButton(R.string.unpair) { _, _ ->
                val app = activity.applicationContext
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        PairedComputers.unpair(app, computer.deviceId)
                        SyncScheduler.ensureScheduled(app)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Calls [render] with the paired computers and the syncing flag whenever either changes, and every minute. */
    fun observe(render: (List<PairedComputer>, Boolean) -> Unit) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PairedComputers.list.combine(DictionarySyncEngine.running) { list, running -> list to running }
                        .collect { (list, running) -> render(list, running) }
                }
                // Keep "5 min ago" current while the screen is open.
                while (true) {
                    delay(60_000)
                    render(PairedComputers.list.value, DictionarySyncEngine.running.value)
                }
            }
        }
    }

    /** The Dictionary screen's compact rows: status line, Pair again, Unpair. */
    fun renderRows(container: LinearLayout, computers: List<PairedComputer>, syncing: Boolean) {
        container.removeAllViews()
        container.visibility = if (computers.isEmpty()) View.GONE else View.VISIBLE
        val now = System.currentTimeMillis()
        for (computer in sorted(computers)) {
            val state = SyncStatus.of(computer, syncing)
            val row = ItemPairedComputerBinding.inflate(LayoutInflater.from(activity), container, false)
            row.syncText.text = SyncStatusText.line(activity, computer, state, now)
            row.pairAgain.visibility = if (state == SyncState.NeedsPairing) View.VISIBLE else View.GONE
            row.pairAgain.setOnClickListener { startPairing() }
            row.unpair.setOnClickListener { confirmUnpair(computer) }
            container.addView(row.root)
        }
    }

    /** The Computer sync screen's cards: name, status line, Sync now, Pair again, Unpair. */
    fun renderCards(
        container: LinearLayout,
        computers: List<PairedComputer>,
        syncing: Boolean,
        @ColorRes normal: Int,
        @ColorRes warning: Int,
    ) {
        container.removeAllViews()
        val now = System.currentTimeMillis()
        for (computer in sorted(computers)) {
            val state = SyncStatus.of(computer, syncing)
            val card = ItemComputerCardBinding.inflate(LayoutInflater.from(activity), container, false)
            card.computerName.text = computer.name
            card.computerStatus.text = SyncStatusText.line(activity, computer, state, now)
            card.computerStatus.setTextColor(activity.getColor(if (state.needsAttention) warning else normal))
            card.computerSyncing.visibility = if (state == SyncState.Syncing) View.VISIBLE else View.GONE
            card.syncNow.visibility = if (state == SyncState.NeedsPairing) View.GONE else View.VISIBLE
            card.syncNow.isEnabled = !syncing
            card.syncNow.setOnClickListener { syncNow() }
            card.pairAgain.visibility = if (state == SyncState.NeedsPairing) View.VISIBLE else View.GONE
            card.pairAgain.setOnClickListener { startPairing() }
            card.unpair.setOnClickListener { confirmUnpair(computer) }
            container.addView(card.root)
        }
    }

    private fun sorted(computers: List<PairedComputer>) = computers.sortedBy { it.name.lowercase() }

    private fun say(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
