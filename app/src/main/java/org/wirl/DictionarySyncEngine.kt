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

/** Paired computers, shared by the Dictionary screen and sync. Changes are published on [list]. */
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

    /** Adds or replaces (re-pairs) a computer. A new pairing starts with no sync history. */
    fun pair(context: Context, qr: SyncProtocol.PairingQr) = edit(context) { list ->
        list.filterNot { it.deviceId.equals(qr.deviceId, ignoreCase = true) } + PairedComputer.fromQr(qr)
    }

    fun unpair(context: Context, deviceId: String) = edit(context) { list -> list.filterNot { it.deviceId == deviceId } }

    fun update(context: Context, deviceId: String, change: (PairedComputer) -> PairedComputer) = edit(context) { list ->
        list.map { if (it.deviceId == deviceId) change(it) else it }
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
 * Runs a sync with every paired computer. Silent: failures go to Diagnostics and to the computer's status line, never
 * to a notification. The foreground sync (a screen is open) and the background worker both come through [syncAll],
 * which holds one lock, so the two never run at the same time.
 */
object DictionarySyncEngine {
    private const val TAG = "WirlSync"

    private val _running = MutableStateFlow(false)

    /** True while a sync is running, foreground or background. Screens show "Syncing…" while it is. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Returns true if every computer that could be tried was synced (false means: try again later). */
    fun syncAll(
        context: Context,
        client: SyncClient = SyncClient({ NsdDiscovery(context).find(it) }),
        phoneAddresses: () -> List<PhoneAddress> = { SyncNetwork.phoneAddresses() },
    ): Boolean = synchronized(this) {
        _running.value = true
        try {
            syncLocked(context.applicationContext, client, phoneAddresses)
        } finally {
            _running.value = false
        }
    }

    private fun syncLocked(app: Context, client: SyncClient, phoneAddresses: () -> List<PhoneAddress>): Boolean {
        val prefs = Prefs(app)
        val repository = DictionaryRepository.get(app)
        var allOk = true
        for (computer in PairedComputers.load(app)) {
            if (computer.needsPairing) continue
            val result = try {
                client.sync(computer, prefs.syncPhoneId, phoneName(app), repository.all.value)
            } catch (e: Exception) {
                SyncResult.Failed("sync error: ${e.message}", null, e)
            }
            when (result) {
                is SyncResult.Synced -> {
                    repository.applyRemote(result.entries)
                    PairedComputers.update(app, computer.deviceId) {
                        it.copy(
                            lastSyncAt = System.currentTimeMillis(),
                            lastHost = result.endpoint.address.hostAddress.orEmpty(),
                            lastPort = result.endpoint.port,
                            needsPairing = false,
                            lastError = "",
                        )
                    }
                }
                SyncResult.NeedsPairing -> {
                    PairedComputers.update(app, computer.deviceId) { it.copy(needsPairing = true, lastError = "") }
                    log(prefs, computer, "Sync refused (401): pair again", 401, null)
                }
                is SyncResult.Failed -> {
                    allOk = false
                    val problem = problemFor(computer, result, phoneAddresses)
                    PairedComputers.update(app, computer.deviceId) { it.copy(lastError = problem.id) }
                    log(prefs, computer, "Sync failed (${problem.id}): ${result.reason}", result.code, result.error)
                }
            }
        }
        return allOk
    }

    /** Different networks, not answering, or failed. Only a sync that reached no address can be a network problem. */
    fun problemFor(computer: PairedComputer, result: SyncResult.Failed, phoneAddresses: () -> List<PhoneAddress>): SyncProblem {
        if (!result.unreachable) return SyncProblem.FAILED
        val phone = runCatching(phoneAddresses).getOrDefault(emptyList())
        val known = SyncNetwork.computerAddresses(computer)
        return SyncNetwork.unreachableProblem(phone, known.ifEmpty { result.tried.map { it.address } })
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
 * Background syncs: a few seconds after a dictionary change, when the phone joins Wi-Fi, and every 30 minutes on an
 * unmetered network. Syncs while a screen is open (opening the Dictionary or Computer sync screen, after a QR scan,
 * Sync now) don't wait for WorkManager; they run right away through [ForegroundSync]. Nothing is scheduled while no
 * computer is paired.
 */
object SyncScheduler {
    private const val NOW = "wirl-sync-now"
    private const val PERIODIC = "wirl-sync-periodic"
    private const val CHANGE_DELAY_S = 3L

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

    /** The phone joined Wi-Fi. */
    fun onWifi(context: Context) {
        if (hasPaired(context)) enqueue(context, CHANGE_DELAY_S, ExistingWorkPolicy.KEEP)
    }

    /** Sets up (or removes) the 30-minute sync and the Wi-Fi trigger. Called at app start and after pairing changes. */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        runCatching {
            val work = WorkManager.getInstance(app)
            if (hasPaired(app)) {
                val periodic = PeriodicWorkRequestBuilder<DictionarySyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build()
                work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
                WifiSyncReceiver.register(app)
            } else {
                work.cancelUniqueWork(PERIODIC)
                work.cancelUniqueWork(NOW)
                WifiSyncReceiver.unregister(app)
            }
        }
    }
}

/**
 * Wakes the app when the phone joins Wi-Fi. Registered with ConnectivityManager using a PendingIntent, which
 * keeps working when the app isn't running (manifest connectivity broadcasts don't since Android 7).
 */
class WifiSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncScheduler.onWifi(context.applicationContext)
    }

    companion object {
        private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WifiSyncReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        private fun wifiRequest(): NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        fun register(context: Context) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            runCatching { cm.registerNetworkCallback(wifiRequest(), pending(context)) }
        }

        fun unregister(context: Context) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            runCatching { cm.unregisterNetworkCallback(pending(context)) }
        }
    }
}
