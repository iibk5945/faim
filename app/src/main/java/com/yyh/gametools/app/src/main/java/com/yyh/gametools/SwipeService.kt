package com.yyh.gametools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

class SwipeService : AccessibilityService() {

    companion object {
        private const val TAG = "ffaim"
        private const val DURATION = 28L

        @Volatile
        var instance: SwipeService? = null
            private set
    }

    private val busy = AtomicBoolean(false)

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun swipe(x: Float, y: Float, dx: Float, dy: Float) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + dx, y + dy)
            }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, DURATION))
                .build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    busy.set(false)
                }

                override fun onCancelled(d: GestureDescription?) {
                    busy.set(false)
                }
            }, null)
            if (!ok) busy.set(false)
        } catch (t: Throwable) {
            Log.e(TAG, "swipe", t)
            busy.set(false)
        }
    }
}
