package com.mlbb.trainer

import android.content.Context
import android.view.MotionEvent
import java.io.File
import java.io.FileWriter
import java.io.IOException

class TouchRecorder(private val context: Context) {

    private var writer: FileWriter? = null
    private var eventCount = 0
    private var isRecording = false

    fun start(sessionDir: File) {
        if (isRecording) return
        try {
            val file = File(sessionDir, "touch_events.csv")
            writer = FileWriter(file, false)
            writer?.write(TouchEvent.csvHeader() + "\n")
            isRecording = true
            eventCount = 0
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun recordEvent(event: MotionEvent, displayWidth: Int, displayHeight: Int) {
        if (!isRecording) return
        val now = System.currentTimeMillis()
        val pointerCount = event.pointerCount

        try {
            for (i in 0 until pointerCount) {
                val touchEvent = TouchEvent(
                    timestampMs = now,
                    x = event.getX(i),
                    y = event.getY(i),
                    action = event.actionMasked,
                    pointerId = event.getPointerId(i),
                    pressure = event.getPressure(i),
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
                writer?.write(touchEvent.toCsvLine() + "\n")
                writer?.flush()
                eventCount++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRecording = false
        try {
            writer?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        writer = null
    }

    fun getEventCount(): Int = eventCount
}
