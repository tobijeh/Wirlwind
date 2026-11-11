package org.wirl

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The system clipboard. Restores the user's clip a moment after pasting, so the target app has finished reading it, and
 * only while the clipboard still holds the dictation.
 */
class AndroidClipboard(context: Context) : ClipboardPort<ClipData> {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    override fun current(): ClipData? = runCatching { manager.primaryClip }.getOrNull()

    override fun set(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    override fun restoreIfStillOurs(saved: ClipData?, ours: String, done: (ClipboardRestore) -> Unit) {
        handler.postDelayed({
            // Android 10+ may not let Wirl read the clipboard; then it's left alone rather than emptied.
            val now = runCatching { manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
            val decision = ClipboardRestore.decide(now, ours)
            if (ClipboardRestore.putsBack(decision)) {
                if (saved != null) {
                    manager.setPrimaryClip(saved)
                } else if (Build.VERSION.SDK_INT >= 28) {
                    manager.clearPrimaryClip()
                }
            }
            done(decision)
        }, RESTORE_DELAY_MS)
    }

    companion object {
        private const val RESTORE_DELAY_MS = 800L
    }
}

/** The focused field as a [FieldPort]. */
class NodeField(private val node: AccessibilityNodeInfo) : FieldPort {
    override fun paste(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

    override fun setText(text: String, cursor: Int): Boolean = node.setFieldText(text, cursor)

    /** Null when the node is gone (refresh fails) or the field has lost focus. */
    override fun readText(): String? {
        if (!runCatching { node.refresh() }.getOrDefault(false)) return null
        if (!node.isFocused) return null
        return node.text?.toString()
    }
}

/** Replaces the whole field text and puts the cursor at [cursor]. */
fun AccessibilityNodeInfo.setFieldText(text: String, cursor: Int): Boolean {
    val args = android.os.Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    if (!performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
    val sel = android.os.Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
    }
    performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    return true
}
