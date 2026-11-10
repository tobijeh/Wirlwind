package org.wirl

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
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
    /**
     * Syncs every paired computer, or only [only] (a [PairedComputer.key]: Sync now on one card, which also retries
     * a computer marked "pair again"). With [pair], the scanned computer is saved first and synced right away.
     */
    fun start(owner: LifecycleOwner, context: Context, pair: SyncProtocol.PairingQr? = null, only: String? = null) {
        val app = context.applicationContext
        owner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val target = if (pair != null) PairedComputers.pair(app, pair).key else only
                if (pair == null) PairedComputers.load(app)
                SyncScheduler.ensureScheduled(app)
                // A round already running (say, the background worker) publishes its result; don't queue another,
                // unless the user asked for this computer specifically.
                if (target != null || !DictionarySyncEngine.running.value) {
                    if (PairedComputers.current(app).isNotEmpty()) DictionarySyncEngine.syncAll(app, only = target)
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
    /** Syncs now, in the foreground. [only] limits it to one computer. */
    fun syncNow(only: String? = null) = ForegroundSync.start(activity, activity, only = only)

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
        when (val result = SyncProtocol.parsePairingQr(text)) {
            SyncProtocol.QrResult.OldVersion -> say(activity.getString(R.string.pair_old_code), long = true)
            SyncProtocol.QrResult.Invalid -> say(activity.getString(R.string.pair_invalid_code))
            is SyncProtocol.QrResult.Valid -> {
                Prefs(activity).syncRepairNotice = false
                ForegroundSync.start(activity, activity, pair = result.qr)
                say(activity.getString(R.string.paired_with, result.qr.name))
            }
        }
    }

    /**
     * Once after the upgrade from Wi-Fi sync, when old pairings were dropped: "Pair again with your computer's new
     * code", with Pair now. [onPair] opens the scanner, or the Computer sync screen from Settings.
     */
    fun showRepairNoticeIfNeeded(onPair: () -> Unit = { startPairing() }) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch(Dispatchers.IO) {
            // Reading the pairings drops the old ones and sets the notice.
            runCatching { PairedComputers.load(app) }
            val prefs = Prefs(app)
            if (!prefs.syncRepairNotice) return@launch
            prefs.syncRepairNotice = false
            launch(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.sync_repair_notice)
                    .setPositiveButton(R.string.pair_now) { _, _ -> onPair() }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }
    }

    fun confirmUnpair(computer: PairedComputer) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(R.string.unpair_confirm, computer.name))
            .setPositiveButton(R.string.unpair) { _, _ ->
                val app = activity.applicationContext
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        PairedComputers.unpair(app, computer.key)
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
            row.syncText.text = SyncStatusText.line(activity, computer, state, now, withName = true)
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
            card.syncNow.setOnClickListener { syncNow(only = computer.key) }
            val others = SyncStatus.otherPhones(computer, myPhoneId)
            card.computerAlsoSynced.text = activity.getString(R.string.also_synced, others.joinToString(", "))
            card.computerAlsoSynced.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
            card.pairAgain.visibility = if (state == SyncState.NeedsPairing) View.VISIBLE else View.GONE
            card.pairAgain.setOnClickListener { startPairing() }
            card.unpair.setOnClickListener { confirmUnpair(computer) }
            container.addView(card.root)
        }
    }

    private val myPhoneId: String by lazy { Prefs(activity).syncPhoneId }

    private fun sorted(computers: List<PairedComputer>) = computers.sortedBy { it.name.lowercase() }

    private fun say(text: String, long: Boolean = false) =
        Toast.makeText(activity, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
