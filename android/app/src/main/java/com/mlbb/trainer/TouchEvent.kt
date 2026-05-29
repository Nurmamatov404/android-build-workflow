package com.mlbb.trainer

data class TouchEvent(
    val timestampMs: Long,
    val x: Float,
    val y: Float,
    val action: Int,
    val pointerId: Int,
    val pressure: Float = 0f,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0
) {
    fun toCsvLine(): String {
        return "$timestampMs,$x,$y,$action,$pointerId,$pressure,$displayWidth,$displayHeight"
    }

    companion object {
        fun csvHeader(): String = "timestamp_ms,x,y,action,pointer_id,pressure,display_width,display_height"

        fun actionToString(action: Int): String = when (action) {
            android.view.MotionEvent.ACTION_DOWN -> "DOWN"
            android.view.MotionEvent.ACTION_UP -> "UP"
            android.view.MotionEvent.ACTION_MOVE -> "MOVE"
            android.view.MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            android.view.MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            android.view.MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER($action)"
        }
    }
}
