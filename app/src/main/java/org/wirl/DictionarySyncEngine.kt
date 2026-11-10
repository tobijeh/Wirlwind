package org.wirl

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** Paired computers, shared by the screens and sync. Changes are published on [list]. */
object PairedComputers {
    private val _list = MutableStateFlow<List<PairedComputer>>(emptyList())
    val list: StateFlow<List<PairedComputer>> = _list.asStateFlow()

    @Volatile private var loaded = false

    fun load(context: Context): List<PairedComputer> = synchronized(this) {
        Prefs(context).pairedComputers.also {
            _list.value = it
            loaded = true
        }
    }

    /** The list, read from storage only the first time. */
    fun current(context: Context): List<PairedComputer> = if (loaded) _list.value else load(context)

    /** Adds a computer, or replaces the one with the same relay and mailbox (§2). A new pairing has no sync history. */
    fun pair(context: Context, qr: SyncProtocol.PairingQr): PairedComputer {
        val computer = PairedComputer.fromQr(qr)
        edit(context) { list -> list.filterNot { it.key == computer.key } + computer }
        return computer
    }

    /** Forgets a computer on this phone only. The mailbox isn't touched (§7). */
    fun unpair(context: Context, key: String) = edit(context) { list -> list.filterNot { it.key == key } }

    fun update(context: Context, key: String, change: (PairedComputer) -> PairedComputer) = edit(context) { list ->
        list.map { if (it.key == key) change(it) else it }
    }

    private fun edit(context: Context, change: (List<PairedComputer>) -> List<PairedComputer>) = synchronized(this) {
        val prefs = Prefs(context)
        val updated = change(prefs.pairedComputers)
        prefs.pairedComputers = updated
        _list.value = updated
        loaded = true
    }
}

/**
 * Runs a sync round with every paired computer's mailbox. Silent: failures go to Diagnostics and to the computer's
 * status line, never to a notification. The foreground sync (a screen is open) and the background worker both come
 * through [syncAll], which holds one lock, so two rounds never run at once.
 */
object DictionarySyncEngine {
    private const val TAG = "WirlSync"

    private val _running = MutableStateFlow(false)

    /** True while a sync is running, foreground or background. Screens show "Syncing…" while it is. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Returns false if a computer couldn't be synced for a reason that may pass (network, server, conflicts), so the
     * background worker tries again later. [only] limits the round to one computer (by [PairedComputer.key]).
     */
    fun syncAll(
        context: Context,
        relayFor: (String) -> RelayTransport = { HttpRelay(it) },
        clock: () -> Long = System::currentTimeMillis,
        only: String? = null,
    ): Boolean = synchronized(this) {
        _running.value = true
        try {
            syncLocked(context.applicationContext, relayFor, clock, only)
        } finally {
            _running.value = false
        }
    }

    private fun syncLocked(app: Context, relayFor: (String) -> RelayTransport, clock: () -> Long, only: String?): Boolean {
        val prefs = Prefs(app)
        val repository = DictionaryRepository.get(app)
        var allOk = true
        for (computer in PairedComputers.load(app)) {
            if (only != null && computer.key != only) continue
            if (computer.needsPairing && only == null) continue
            val now = clock()
            val me = SyncProtocol.Device(prefs.syncPhoneId, phoneName(app), SyncProtocol.KIND_PHONE, now)
            try {
                val keys = SyncCrypto.derive(computer.secret)
                val outcome = SyncRound.run(relayFor(computer.relay), keys, repository.all.value, me, now)
                // Merge again with the current dictionary, so an edit made during the round isn't lost (§6).
                repository.applyRemote(outcome.entries)
                PairedComputers.update(app, computer.key) {
                    it.copy(lastSyncAt = clock(), status = "", devices = outcome.devices)
                }
            } catch (e: Exception) {
                val problem = when (e) {
                    is RelayError -> e.problem
                    is SyncCrypto.BadSecretException -> SyncProblem.NEEDS_PAIRING
                    else -> SyncProblem.FAILED
                }
                if (problem != SyncProblem.NEEDS_PAIRING) allOk = false
                PairedComputers.update(app, computer.key) { it.copy(status = problem.id) }
                log(prefs, computer, "Sync failed (${problem.id}): ${e.message}", (e as? RelayError.Server)?.code, e)
            }
        }
        return allOk
    }

    private fun log(prefs: Prefs, computer: PairedComputer, message: String, code: Int?, error: Throwable?) {
        Log.w(TAG, "${computer.name}: $message", error)
        prefs.diagnostics = DiagnosticsLog.add(
            prefs.diagnostics,
            DiagnosticEntry(
                time = System.currentTimeMillis(),
                engines = "SYNC ${computer.name}",
                errorCode = code,
                message = message,
                stackTrace = error?.let { Log.getStackTraceString(it) },
            ),
        )
    }

    fun phoneName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Android" }
}

/** Runs [DictionarySyncEngine.syncAll] in the background. Retries later (WorkManager backoff) on failure. */
class DictionarySyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result =
        if (DictionarySyncEngine.syncAll(applicationContext) || runAttemptCount >= 5) Result.success() else Result.retry()
}

/**
 * Background syncs, on any connection (mobile data is fine): about 2 seconds after a dictionary change, when the
 * network comes back, and every 30 minutes. Syncs while a screen is open (opening the Dictionary or Computer sync
 * screen, after a QR scan, Sync now) don't wait for WorkManager; they run right away through [ForegroundSync].
 * Nothing is scheduled while no computer is paired.
 */
object SyncScheduler {
    private const val NOW = "wirl-sync-now"
    private const val PERIODIC = "wirl-sync-periodic"
    private const val CHANGE_DELAY_S = 2L

    private fun hasPaired(context: Context) = PairedComputers.current(context).any { !it.needsPairing }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun enqueue(context: Context, delaySeconds: Long, policy: ExistingWorkPolicy) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DictionarySyncWorker>()
                .setConstraints(connected())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
        }
    }

    /** The dictionary changed. Debounced: a burst of edits makes one sync. */
    fun afterChange(context: Context) {
        if (hasPaired(context)) enqueue(context, CHANGE_DELAY_S, ExistingWorkPolicy.REPLACE)
    }

    /** The network came back. */
    fun onNetwork(context: Context) {
        if (hasPaired(context)) enqueue(context, CHANGE_DELAY_S, ExistingWorkPolicy.KEEP)
    }

    /** Sets up (or removes) the 30-minute sync and the network trigger. Called at app start and after pairing changes. */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        runCatching {
            val work = WorkManager.getInstance(app)
            if (hasPaired(app)) {
                val periodic = PeriodicWorkRequestBuilder<DictionarySyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(connected())
                    .build()
                // UPDATE, so a 30-minute job saved by the Wi-Fi version (unmetered only) picks up the new constraint.
                work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
                NetworkSyncReceiver.register(app)
            } else {
                work.cancelUniqueWork(PERIODIC)
                work.cancelUniqueWork(NOW)
                NetworkSyncReceiver.unregister(app)
            }
        }
    }
}

/**
 * Wakes the app when a network with internet becomes available. Registered with ConnectivityManager using a
 * PendingIntent, which keeps working when the app isn't running (manifest connectivity broadcasts don't since
 * Android 7).
 */
class NetworkSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncScheduler.onNetwork(context.applicationContext)
    }

    companion object {
        private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NetworkSyncReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        private fun internetRequest(): NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        /** The Wi-Fi version registered a receiver class that no longer exists. Its registration is removed. */
        private fun legacyPending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent().setClassName(context.packageName, "org.wirl.WifiSyncReceiver"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        fun register(context: Context) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            runCatching { cm.unregisterNetworkCallback(legacyPending(context)) }
            runCatching { cm.registerNetworkCallback(internetRequest(), pending(context)) }
        }

        fun unregister(context: Context) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            runCatching { cm.unregisterNetworkCallback(pending(context)) }
        }
    }
}
