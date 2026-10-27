package org.wirl

/** The home screen's health line, worked out from the setup checklist. Pure, so it's tested without Android. */
object HomeSummary {
    /** How many setup items aren't done yet. Zero means everything is set up. */
    fun needingAttention(status: Map<SetupChecklist.Item, Boolean>): Int = status.values.count { !it }
}
