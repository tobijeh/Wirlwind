package org.wirl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** "just now", "5 min ago", "2 hours ago", "3 days ago". Pure, so it can be tested without resources. */
object TimeAgo {
    sealed class Span {
        object JustNow : Span()
        data class Minutes(val n: Int) : Span()
        data class Hours(val n: Int) : Span()
        data class Days(val n: Int) : Span()
    }

    fun span(then: Long, now: Long): Span {
        val minutes = ((now - then).coerceAtLeast(0) / 60_000).toInt()
        return when {
            minutes < 1 -> Span.JustNow
            minutes < 60 -> Span.Minutes(minutes)
            minutes < 24 * 60 -> Span.Hours(minutes / 60)
            else -> Span.Days(minutes / (24 * 60))
        }
    }

    fun format(context: Context, then: Long, now: Long): String = when (val s = span(then, now)) {
        TimeAgo.Span.JustNow -> context.getString(R.string.time_just_now)
        is TimeAgo.Span.Minutes -> context.resources.getQuantityString(R.plurals.time_minutes_ago, s.n, s.n)
        is TimeAgo.Span.Hours -> context.resources.getQuantityString(R.plurals.time_hours_ago, s.n, s.n)
        is TimeAgo.Span.Days -> context.resources.getQuantityString(R.plurals.time_days_ago, s.n, s.n)
    }
}

/**
 * Android's local network permission. Newer Android versions require it before an app can reach devices on the
 * local network; older ones don't define it, and then nothing is asked.
 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    /** True if this Android version has the permission and Wirl doesn't hold it yet. */
    fun needsRequest(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val defined = runCatching { context.packageManager.getPermissionInfo(NAME, 0) }.isSuccess
        return defined && context.checkSelfPermission(NAME) != PackageManager.PERMISSION_GRANTED
    }
}

/** What a paired computer's status line says. Pure, so the choice can be tested without resources. */
sealed class SyncState {
    object NeedsPairing : SyncState()
    object Syncing : SyncState()
    data class Problem(val problem: SyncProblem) : SyncState()
    data class Synced(val at: Long) : SyncState()
    object NotSynced : SyncState()

    /** Shown in amber: the user has something to do. */
    val needsAttention: Boolean get() = this is NeedsPairing || this is Problem
}

object SyncStatus {
    /** A computer's state. "Pair again" beats everything; "Syncing…" shows over an older error or sync time. */
    fun of(computer: PairedComputer, syncing: Boolean): SyncState {
        val problem = computer.problem
        return when {
            computer.needsPairing -> SyncState.NeedsPairing
            syncing -> SyncState.Syncing
            problem != null -> SyncState.Problem(problem)
            computer.lastSyncAt > 0 -> SyncState.Synced(computer.lastSyncAt)
            else -> SyncState.NotSynced
        }
    }

    /**
     * The computer the Home row talks about: the one that most needs attention (pair again, then a problem), then
     * one that's syncing, then one not synced yet, then the most recently synced. Null when nothing is paired.
     */
    fun homePick(computers: List<PairedComputer>, syncing: Boolean): Pair<PairedComputer, SyncState>? =
        computers.map { it to of(it, syncing) }.minWithOrNull(
            compareBy<Pair<PairedComputer, SyncState>> { rank(it.second) }
                .thenByDescending { it.first.lastSyncAt }
                .thenBy { it.first.name.lowercase() },
        )

    private fun rank(state: SyncState): Int = when (state) {
        SyncState.NeedsPairing -> 0
        is SyncState.Problem -> 1
        SyncState.Syncing -> 2
        SyncState.NotSynced -> 3
        is SyncState.Synced -> 4
    }
}

/** The words for [SyncState], shared by the Dictionary screen, the Computer sync screen and the Home row. */
object SyncStatusText {
    /** The full status line for one computer. */
    fun line(context: Context, computer: PairedComputer, state: SyncState, now: Long): String {
        val name = computer.name
        return when (state) {
            SyncState.NeedsPairing -> context.getString(R.string.pairing_invalid, name)
            SyncState.Syncing -> context.getString(R.string.syncing_with, name)
            is SyncState.Problem -> when (state.problem) {
                SyncProblem.DIFFERENT_NETWORK -> context.getString(R.string.sync_error_different_network, name)
                SyncProblem.NOT_ANSWERING -> context.getString(R.string.sync_error_not_answering, name)
                SyncProblem.FAILED -> context.getString(R.string.sync_error_failed, name)
            }
            is SyncState.Synced -> context.getString(R.string.synced_with, name, TimeAgo.format(context, state.at, now))
            SyncState.NotSynced -> context.getString(R.string.not_synced_yet, name)
        }
    }

    /** The Home row's value line: short, with the details on the Computer sync screen. */
    fun home(context: Context, computers: List<PairedComputer>, syncing: Boolean, now: Long): Pair<String, Boolean> {
        val (computer, state) = SyncStatus.homePick(computers, syncing)
            ?: return context.getString(R.string.home_sync_pair) to false
        val name = computer.name
        val text = when (state) {
            SyncState.NeedsPairing -> context.getString(R.string.home_sync_needs_pairing, name)
            is SyncState.Problem -> if (state.problem == SyncProblem.FAILED) {
                context.getString(R.string.home_sync_failed, name)
            } else {
                context.getString(R.string.home_sync_cant_reach, name)
            }
            else -> line(context, computer, state, now)
        }
        return text to state.needsAttention
    }
}
