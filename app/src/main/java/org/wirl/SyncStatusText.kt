package org.wirl

import android.content.Context

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

/** What a paired computer's status line says. Pure, so the choice can be tested without resources. */
sealed class SyncState {
    object NeedsPairing : SyncState()
    object Syncing : SyncState()

    /** Can't reach the sync service, or the round didn't finish. Both are retried. */
    data class Problem(val problem: SyncProblem) : SyncState()
    data class Synced(val at: Long) : SyncState()
    object NotSynced : SyncState()

    /** Shown in amber: the user has something to do, or something is wrong. */
    val needsAttention: Boolean get() = this is NeedsPairing || this is Problem
}

object SyncStatus {
    /** A computer's state. "Pair again" beats everything; "Syncing…" shows over an older error or sync time. */
    fun of(computer: PairedComputer, syncing: Boolean): SyncState {
        val problem = computer.problem
        return when {
            problem == SyncProblem.NEEDS_PAIRING -> SyncState.NeedsPairing
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

    /** The other phones in the group, from the device list of the last round. This phone isn't one of them. */
    fun otherPhones(computer: PairedComputer, myId: String): List<String> =
        computer.devices.filter { it.kind == SyncProtocol.KIND_PHONE && it.id != myId }.map { it.name }.sorted()

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
    /**
     * The status line for one computer. On the Computer sync cards the name is shown above it ([withName] false);
     * the Dictionary screen's compact rows put the name first for messages that don't contain it.
     */
    fun line(context: Context, computer: PairedComputer, state: SyncState, now: Long, withName: Boolean = false): String {
        val name = computer.name
        val message = when (state) {
            SyncState.NeedsPairing -> context.getString(R.string.pairing_invalid)
            SyncState.Syncing -> return context.getString(R.string.syncing_with, name)
            is SyncState.Problem -> context.getString(
                if (state.problem == SyncProblem.UNREACHABLE) R.string.sync_error_unreachable else R.string.sync_error_failed,
            )
            is SyncState.Synced -> return context.getString(R.string.synced_with, name, TimeAgo.format(context, state.at, now))
            SyncState.NotSynced -> return context.getString(R.string.not_synced_yet, name)
        }
        return if (withName) context.getString(R.string.sync_line_named, name, message) else message
    }

    /** The Home row's value line: short, with the details on the Computer sync screen. Second: show it in amber. */
    fun home(context: Context, computers: List<PairedComputer>, syncing: Boolean, now: Long): Pair<String, Boolean> {
        val (computer, state) = SyncStatus.homePick(computers, syncing)
            ?: return context.getString(R.string.home_sync_pair) to false
        val text = when (state) {
            SyncState.NeedsPairing -> context.getString(R.string.home_sync_needs_pairing, computer.name)
            is SyncState.Problem -> context.getString(
                if (state.problem == SyncProblem.UNREACHABLE) R.string.home_sync_cant_reach else R.string.home_sync_failed,
            )
            else -> line(context, computer, state, now)
        }
        return text to state.needsAttention
    }
}
