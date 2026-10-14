package org.wirl

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/** The system clipboard. Restores the user's clip a moment after pasting, so the target app has finished reading it. */
class AndroidClipboard(context: Context) : ClipboardPort<ClipData> {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    override fun current(): ClipData? = runCatching { manager.primaryClip }.getOrNull()

    override fun set(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    override fun restore(saved: ClipData?) {
        handler.postDelayed({
            if (saved != null) {
                manager.setPrimaryClip(saved)
            } else if (Build.VERSION.SDK_INT >= 28) {
                manager.clearPrimaryClip()
            }
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
