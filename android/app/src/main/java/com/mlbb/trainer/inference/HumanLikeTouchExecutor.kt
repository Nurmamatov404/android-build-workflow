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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HumanLikeTouchExecutor(private val context: Context) {

    companion object {
        private const val TAG = "HumanTouchExec"
        private const val RANDOM_OFFSET_PX = 15
        private const val MAX_RANDOM_OFFSET_PX = 25
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastX = -1f
    private var lastY = -1f
    private var consecutiveFasts = 0
    private var lastActionTime = 0L

    private fun getOffset(): Int =
        if (Random.nextFloat() < 0.15f) Random.nextInt(-MAX_RANDOM_OFFSET_PX, MAX_RANDOM_OFFSET_PX + 1)
        else Random.nextInt(-RANDOM_OFFSET_PX, RANDOM_OFFSET_PX + 1)

    private fun reactionTime(actionType: String): Long {
        val base = when (actionType) {
            "ATTACK" -> 100L..400L
            "SKILL1", "SKILL2" -> 200L..600L
            "SKILL3" -> 250L..700L
            "ULTIMATE" -> 300L..900L
            "MOVE" -> 50L..200L
            "ITEM" -> 400L..1000L
            "MINIMAP" -> 300L..800L
            "RECALL" -> 500L..1200L
            "SWIPE" -> 150L..500L
            else -> 100L..500L
        }

        if (consecutiveFasts > 3) {
            val pause = Random.nextLong(400, 1200)
            consecutiveFasts = 0
            return base.first + pause
        }

        if (Random.nextFloat() < 0.08f) {
            return base.first + Random.nextLong(800, 2000)
        }

        val lag = if (Random.nextFloat() < 0.04f) Random.nextLong(500, 1500) else 0L
        consecutiveFasts++
        return base.first + Random.nextLong(0, (base.last - base.first)) + lag
    }

    fun executeTap(x: Float, y: Float, displayW: Int, displayH: Int, actionType: String = "ATTACK") {
        val ox = getOffset()
        val oy = getOffset()
        val absX = (x + ox).toInt().coerceIn(0, displayW - 1)
        val absY = (y + oy).toInt().coerceIn(0, displayH - 1)

        val delay = reactionTime(actionType)
        handler.postDelayed({
            val service = TouchEventService.instance
            if (service == null) { Log.w(TAG, "Tap dropped: TouchEventService null"); return@postDelayed }

            val path = Path().apply { moveTo(absX.toFloat(), absY.toFloat()) }
            val duration = if (Random.nextFloat() < 0.15f) Random.nextLong(120, 300) else Random.nextLong(60, 180)
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            lastActionTime = System.currentTimeMillis()
            Log.d(TAG, "Tap $actionType at ($absX, $absY) [${delay}ms dur=${duration}ms]")
        }, delay)
    }

    fun executeSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float,
                     displayW: Int, displayH: Int, actionType: String = "SWIPE") {
        val ox1 = getOffset(); val oy1 = getOffset()
        val ox2 = getOffset(); val oy2 = getOffset()
        val ax1 = (fromX + ox1).toInt().coerceIn(0, displayW - 1)
        val ay1 = (fromY + oy1).toInt().coerceIn(0, displayH - 1)
        val ax2 = (toX + ox2).toInt().coerceIn(0, displayW - 1)
        val ay2 = (toY + oy2).toInt().coerceIn(0, displayH - 1)

        val delay = reactionTime(actionType)
        handler.postDelayed({
            val service = TouchEventService.instance
            if (service == null) { Log.w(TAG, "Swipe dropped: TouchEventService null"); return@postDelayed }
            val path = Path().apply {
                moveTo(ax1.toFloat(), ay1.toFloat())
                val segments = Random.nextInt(3, 8)
                for (i in 1..segments) {
                    val t = i.toFloat() / segments
                    val wobbleX = Random.nextInt(-8, 9)
                    val wobbleY = Random.nextInt(-8, 9)
                    val px = ax1 + ((ax2 - ax1) * t).toInt() + wobbleX
                    val py = ay1 + ((ay2 - ay1) * t).toInt() + wobbleY
                    lineTo(px.toFloat(), py.toFloat())
                }
                lineTo(ax2.toFloat(), ay2.toFloat())
            }
            val duration = Random.nextLong(120, 350)
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            lastActionTime = System.currentTimeMillis()
            Log.d(TAG, "Swipe $actionType ($ax1,$ay1)->($ax2,$ay2) [${delay}ms dur=${duration}ms]")
        }, delay)
    }

    fun executeDirectionalSkill(
        skillX: Float, skillY: Float,
        directionDeg: Float, distance: Float,
        displayW: Int, displayH: Int
    ) {
        val missChance = if (Random.nextFloat() < 0.25f) {
            Random.nextFloat() * 60f - 30f
        } else 0f

        val radians = Math.toRadians((directionDeg + missChance).toDouble())
        val actualDist = distance * (0.7f + Random.nextFloat() * 0.6f)
        val dx = (cos(radians) * actualDist).toFloat()
        val dy = (sin(radians) * actualDist).toFloat()

        executeSwipe(skillX, skillY, skillX + dx, skillY + dy, displayW, displayH, "SKILL_DIR")
    }

    fun executeJoystickMove(
        joystickCenterX: Float, joystickCenterY: Float,
        directionDeg: Float, intensity: Float,
        displayW: Int, displayH: Int
    ) {
        val maxReach = (displayW * 0.04f).coerceAtLeast(20f)
        val wobbleDeg = (Random.nextFloat() - 0.5f) * 12f
        val radians = Math.toRadians((directionDeg + wobbleDeg).toDouble())
        val actualIntensity = intensity * (0.8f + Random.nextFloat() * 0.4f)
        val dx = (cos(radians) * maxReach * actualIntensity).toFloat()
        val dy = (sin(radians) * maxReach * actualIntensity).toFloat()

        val ox = getOffset(); val oy = getOffset()
        val startX = (joystickCenterX + ox).toInt().coerceIn(0, displayW - 1)
        val startY = (joystickCenterY + oy).toInt().coerceIn(0, displayH - 1)
        val endX = (joystickCenterX + dx + ox).toInt().coerceIn(0, displayW - 1)
        val endY = (joystickCenterY + dy + oy).toInt().coerceIn(0, displayH - 1)

        val delay = Random.nextLong(30, 120)
        handler.postDelayed({
            val service = TouchEventService.instance
            if (service == null) { Log.w(TAG, "Joystick dropped: service null"); return@postDelayed }

            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                val steps = Random.nextInt(2, 4)
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val wx = Random.nextInt(-5, 6)
                    val wy = Random.nextInt(-5, 6)
                    val px = startX + ((endX - startX) * t).toInt() + wx
                    val py = startY + ((endY - startY) * t).toInt() + wy
                    lineTo(px.toFloat(), py.toFloat())
                }
                lineTo(endX.toFloat(), endY.toFloat())
            }

            val duration = 400L + Random.nextLong(0, 400)
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            lastX = endX.toFloat(); lastY = endY.toFloat()
            lastActionTime = System.currentTimeMillis()
            Log.d(TAG, "Joystick ${directionDeg.toInt()}° i=${"%.1f".format(intensity)} -> ($endX,$endY) [${duration}ms]")
        }, delay)
    }

    fun executeJoystickRelease() {
        handler.postDelayed({
            val service = TouchEventService.instance
            if (service == null) return@postDelayed
            val path = Path().apply { moveTo(lastX, lastY) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 1)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            lastX = -1f; lastY = -1f
        }, Random.nextLong(30, 150))
    }

    fun executeTouch(x: Float, y: Float, action: String, displayW: Int, displayH: Int) {
        val ox = getOffset(); val oy = getOffset()
        val absX = (x * displayW + ox).toInt().coerceIn(0, displayW - 1)
        val absY = (y * displayH + oy).toInt().coerceIn(0, displayH - 1)
        val service = TouchEventService.instance ?: return
        val delayBefore = when (action) {
            "MOVE" -> Random.nextLong(30, 150)
            "DOWN" -> Random.nextLong(100, 400)
            "UP" -> Random.nextLong(100, 300)
            else -> Random.nextLong(80, 300)
        }
        handler.postDelayed({ performGesture(service, absX, absY, action) }, delayBefore)
    }

    private fun performGesture(service: AccessibilityService, x: Int, y: Int, action: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        when (action) {
            "DOWN" -> {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val duration = Random.nextLong(1, 60)
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                service.dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(), null, null)
                lastX = x.toFloat(); lastY = y.toFloat()
            }
            "MOVE" -> {
                val path = Path().apply { moveTo(lastX, lastY); lineTo(x.toFloat(), y.toFloat()) }
                val duration = Random.nextLong(80, 200)
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                service.dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(), null, null)
                lastX = x.toFloat(); lastY = y.toFloat()
            }
            "UP" -> {
                val path = Path().apply { moveTo(lastX, lastY) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 1)
                service.dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(), null, null)
                lastX = -1f; lastY = -1f
            }
            "NONE" -> {}
        }
    }

    fun reset() {
        lastX = -1f; lastY = -1f; consecutiveFasts = 0
    }
}
