package com.ozil.omnivoice

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types dictated text into whichever text box is focused in ANY app.
 * The user must enable it once in Settings > Accessibility > OmniVoice.
 */
class TypeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TypeAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Inserts [text] at the cursor of the focused input field. Returns true on success. */
    fun insert(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false

        val showingHint = if (Build.VERSION.SDK_INT >= 26) node.isShowingHintText else false
        val current = if (showingHint) "" else (node.text?.toString() ?: "")

        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) {
            s = current.length
            e = current.length
        }
        if (s > e) {
            val t = s
            s = e
            e = t
        }

        val before = current.substring(0, s)
        val after = current.substring(e)
        val needSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            text.first() !in ".,?!:;\n"
        val inserted = (if (needSpace) " " else "") + text

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before + inserted + after
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val pos = before.length + inserted.length
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }
}
