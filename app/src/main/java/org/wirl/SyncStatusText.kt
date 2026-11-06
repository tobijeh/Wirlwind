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
