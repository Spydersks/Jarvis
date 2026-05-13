package com.jarvis.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("AccessibilityService", "JARVIS Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitor events for automation triggers
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Notification appeared
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // App switched
            }
        }
    }

    override fun onInterrupt() {
        Log.w("AccessibilityService", "Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun clickBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun clickHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun clickRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun clickNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun swipeDown() {
        val path = Path().apply { moveTo(500f, 300f); lineTo(500f, 1200f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun swipeUp() {
        val path = Path().apply { moveTo(500f, 1200f); lineTo(500f, 300f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun findAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findNode(root, text)?.let { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } ?: false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return extractText(root)
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, text)?.let { return it }
        }
        return null
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (node.text != null) sb.append(node.text).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractText(child))
        }
        return sb.toString()
    }
}
