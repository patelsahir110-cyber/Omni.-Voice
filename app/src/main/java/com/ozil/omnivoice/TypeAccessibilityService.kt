package com.ozil.omnivoice

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Types dictated text into whichever text box is focused in ANY app.
 * The user must enable it once in Settings > Accessibility > OmniVoice.
 *
 * It remembers exactly where its previous word ended, so every new word continues
 * from that point, even after a long pause and even if the app resets the cursor.
 */
class TypeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TypeAccessibilityService? = null
    }

    // What the text box contained right after our last insert, and where the cursor was.
    private var lastText: String? = null
    private var lastPos = 0

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

    private fun findInputNode(): AccessibilityNodeInfo? {
        try {
            val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (n != null && n.isEditable) return n
        } catch (_: Exception) {
        }
        // Fallback: look through every app window (keyboard or overlay may be "active").
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val n = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (n != null && n.isEditable) return n
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Appends [text] at the point where we last typed. Returns true on success. */
    fun insert(text: String): Boolean {
        if (text.isEmpty()) return false
        val node = findInputNode() ?: return false

        val showingHint = if (Build.VERSION.SDK_INT >= 26) node.isShowingHintText else false
        val current = if (showingHint) "" else (node.text?.toString() ?: "")

        var s: Int
        var e: Int
        val remembered = lastText
        if (remembered != null && current == remembered) {
            // Nothing changed since our last word: continue exactly from there.
            s = lastPos.coerceIn(0, current.length)
            e = s
        } else {
            s = node.textSelectionStart
            e = node.textSelectionEnd
            val invalid = s < 0 || e < 0 || s > current.length || e > current.length
            // Many apps report cursor 0 after a text change; treat that as "end of text".
            val fakeStart = s == 0 && e == 0 && current.isNotEmpty()
            if (invalid || fakeStart) {
                s = current.length
                e = current.length
            }
            if (s > e) {
                val t = s
                s = e
                e = t
            }
        }

        val before = current.substring(0, s)
        val after = current.substring(e)
        val needSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            text.first() !in ".,?!:;\n"
        val inserted = (if (needSpace) " " else "") + text
        val newText = before + inserted + after

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val pos = before.length + inserted.length
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            lastText = newText
            lastPos = pos
        }
        return ok
    }
}
