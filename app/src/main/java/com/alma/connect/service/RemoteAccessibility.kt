package com.alma.connect.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import com.alma.connect.network.Hub
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibility : AccessibilityService() {
    companion object { @Volatile var inst: RemoteAccessibility? = null }
    override fun onServiceConnected() { inst = this; Hub.a11y.value = true }
    override fun onUnbind(i: Intent?): Boolean { inst = null; Hub.a11y.value = false; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun typeText(s: String) {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val cur = n.text?.toString() ?: ""
        val t = if (s == "\b") cur.dropLast(1) else cur + s
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) })
    }

    // Live touch: continued strokes so the phone follows the finger while it drags.
    private var stroke: GestureDescription.StrokeDescription? = null
    private var busy = false
    private var pend: FloatArray? = null
    private var ending = false
    private var px = 0f
    private var py = 0f

    fun liveStart(x: Float, y: Float) { stroke = null; busy = false; pend = null; ending = false; px = x; py = y }
    fun livePush(tx: Float, ty: Float, end: Boolean) {
        if (busy) { pend = floatArrayOf(tx, ty); if (end) ending = true; return }
        seg(tx, ty, end)
    }
    private fun seg(tx: Float, ty: Float, end: Boolean) {
        val p = Path().apply { moveTo(px, py); if (tx != px || ty != py) lineTo(tx, ty) }
        val s = stroke
        val d = if (s == null) GestureDescription.StrokeDescription(p, 0, 40, !end) else s.continueStroke(p, 0, 40, !end)
        stroke = d; px = tx; py = ty; busy = true
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(d).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { next() }
            override fun onCancelled(g: GestureDescription?) { next() }
        }, null)
        if (!ok) busy = false
    }
    private fun next() {
        busy = false
        val n = pend; pend = null
        if (n != null) seg(n[0], n[1], ending)
    }

    fun stroke(xs: FloatArray, ys: FloatArray, ms: Long) {
        val p = Path().apply { moveTo(xs[0], ys[0]); for (i in 1 until xs.size) lineTo(xs[i], ys[i]) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, swipe: Boolean) {
        val p = Path().apply { moveTo(x1, y1); if (swipe) lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        dispatchGesture(g, null, null)
    }
}
