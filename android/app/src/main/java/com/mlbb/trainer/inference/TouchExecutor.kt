package com.mlbb.trainer.inference

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mlbb.trainer.TouchEventService

class TouchExecutor(private val context: Context) {

    companion object {
        private const val TAG = "TouchExecutor"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastX = -1f
    private var lastY = -1f
    private var isTouching = false

    fun executeTouch(x: Float, y: Float, action: String, displayW: Int, displayH: Int) {
        // Convert from normalized [0,1] to absolute pixel coordinates
        val absX = (x * displayW).toInt().coerceIn(0, displayW - 1)
        val absY = (y * displayH).toInt().coerceIn(0, displayH - 1)

        val service = TouchEventService.instance
        if (service == null) {
            Log.w(TAG, "TouchEventService not available")
            return
        }

        // Use AccessibilityService's dispatchGesture for touch simulation
        handler.post {
            performGesture(service, absX, absY, action)
        }
    }

    private fun performGesture(service: AccessibilityService, x: Int, y: Int, action: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatch requires Android 7+")
            return
        }

        when (action) {
            "DOWN" -> {
                val path = Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                }
                val gestureBuilder = GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, 1
                        )
                    )
                service.dispatchGesture(gestureBuilder.build(), null, null)
                lastX = x.toFloat()
                lastY = y.toFloat()
                isTouching = true
                Log.d(TAG, "Touch DOWN at ($x, $y)")
            }

            "MOVE" -> {
                if (!isTouching) {
                    // Start a new touch if not already touching
                    val path = Path().apply {
                        moveTo(x.toFloat(), y.toFloat())
                    }
                    val gestureBuilder = GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, 1
                            )
                        )
                    service.dispatchGesture(gestureBuilder.build(), null, null)
                } else {
                    // Create a move gesture from last position
                    val path = Path().apply {
                        moveTo(lastX, lastY)
                        lineTo(x.toFloat(), y.toFloat())
                    }
                    val gestureBuilder = GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, 50  // 50ms duration
                            )
                        )
                    service.dispatchGesture(gestureBuilder.build(), null, null)
                }
                lastX = x.toFloat()
                lastY = y.toFloat()
                isTouching = true
                Log.d(TAG, "Touch MOVE to ($x, $y)")
            }

            "UP" -> {
                if (isTouching) {
                    val path = Path().apply {
                        moveTo(lastX, lastY)
                    }
                    val gestureBuilder = GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, 1
                            )
                        )
                    service.dispatchGesture(gestureBuilder.build(), null, null)
                }
                isTouching = false
                lastX = -1f
                lastY = -1f
                Log.d(TAG, "Touch UP")
            }

            "NONE" -> {
                // No action needed
            }
        }
    }

    fun reset() {
        isTouching = false
        lastX = -1f
        lastY = -1f
    }
}
