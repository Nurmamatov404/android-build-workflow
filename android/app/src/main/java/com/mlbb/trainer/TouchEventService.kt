package com.mlbb.trainer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class TouchEventService : AccessibilityService() {

    companion object {
        const val TAG = "TouchEventService"
        var instance: TouchEventService? = null
            private set

        var isConnected: Boolean = false
            private set

        var displayWidth: Int = 0
            private set

        var displayHeight: Int = 0
            private set
    }

    private var recorder: TouchRecorder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (recorder == null) return

        val me = getMotionEvent(event)
        if (me == null) return

        val displayW = displayWidth
        val displayH = displayHeight

        if (displayW > 0 && displayH > 0) {
            recorder?.recordEvent(me, displayW, displayH)
        }
    }

    private fun getMotionEvent(event: AccessibilityEvent): MotionEvent? {
        return try {
            val method = AccessibilityEvent::class.java.getMethod("getMotionEvent")
            method.invoke(event) as? MotionEvent
        } catch (e: Exception) {
            Log.w(TAG, "getMotionEvent not available: ${e.message}")
            null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        instance = null
    }

    fun setRecorder(recorder: TouchRecorder?) {
        this.recorder = recorder
    }
}
