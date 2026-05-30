package com.mlbb.trainer.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class YOLODetector(
    private val context: Context,
    modelFile: String,
    private val inputSize: Int = 416,
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.5f
) {
    companion object {
        private const val TAG = "YOLODetector"

        val LABELS = listOf(
            "joystick", "skill1", "skill2", "skill3",
            "ultimate", "attack", "recall", "minimap"
        )
    }

    data class Detection(
        val label: String,
        val labelIndex: Int,
        val confidence: Float,
        val rect: RectF
    ) {
        val centerX: Float get() = rect.centerX()
        val centerY: Float get() = rect.centerY()
        val width: Float get() = rect.width()
        val height: Float get() = rect.height()
    }

    private var interpreter: Interpreter? = null
    private var isLoaded = false
    private val modelFile: String = modelFile

    private val numClasses = LABELS.size
    private val outputSize: Int

    init {
        outputSize = inputSize / 32
    }

    fun load(): Boolean {
        try {
            val file = File(modelFile)
            if (!file.exists()) {
                Log.w(TAG, "YOLO model not found: $modelFile")
                return false
            }
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val buffer = loadModelFile(modelFile)
            interpreter = Interpreter(buffer, options)
            isLoaded = true
            Log.i(TAG, "YOLO model loaded: ${file.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load YOLO model: ${e.message}")
            return false
        }
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val file = File(path)
        return FileInputStream(file).channel.map(
            FileChannel.MapMode.READ_ONLY, 0, file.length()
        )
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isLoaded || interpreter == null) return emptyList()

        try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = preprocess(resized)
            val output = Array(1) { FloatArray(outputSize * outputSize * (numClasses + 5)) }
            interpreter?.run(inputBuffer, output)

            return postprocess(output[0], bitmap.width.toFloat(), bitmap.height.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "YOLO detection failed: ${e.message}")
            return emptyList()
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    private fun postprocess(
        rawOutput: FloatArray,
        imageW: Float, imageH: Float
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val gridSize = outputSize
        val numAnchors = 1
        val stride = numClasses + 5

        for (i in 0 until gridSize * gridSize) {
            val offset = i * stride
            val cx = (rawOutput[offset + 0])
            val cy = (rawOutput[offset + 1])
            val bw = (rawOutput[offset + 2])
            val bh = (rawOutput[offset + 3])
            val objConf = sigmoid(rawOutput[offset + 4])

            if (objConf < confidenceThreshold) continue

            val classScores = FloatArray(numClasses)
            var bestClass = 0
            var bestScore = 0f
            for (c in 0 until numClasses) {
                classScores[c] = sigmoid(rawOutput[offset + 5 + c]) * objConf
                if (classScores[c] > bestScore) {
                    bestScore = classScores[c]
                    bestClass = c
                }
            }

            if (bestScore < confidenceThreshold) continue

            val gridX = i % gridSize
            val gridY = i / gridSize

            val absCx = ((sigmoid(cx) + gridX) / gridSize) * imageW
            val absCy = ((sigmoid(cy) + gridY) / gridSize) * imageH
            val absW = exp(bw) * (imageW / gridSize)
            val absH = exp(bh) * (imageH / gridSize)

            val left = (absCx - absW / 2).coerceAtLeast(0f)
            val top = (absCy - absH / 2).coerceAtLeast(0f)
            val right = (absCx + absW / 2).coerceAtMost(imageW)
            val bottom = (absCy + absH / 2).coerceAtMost(imageH)

            detections.add(Detection(
                label = LABELS[bestClass],
                labelIndex = bestClass,
                confidence = bestScore,
                rect = RectF(left, top, right, bottom)
            ))
        }

        return nonMaxSuppression(detections)
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return detections

        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        val used = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (used[i]) continue
            result.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (sorted[i].labelIndex == sorted[j].labelIndex &&
                    iou(sorted[i].rect, sorted[j].rect) > iouThreshold) {
                    used[j] = true
                }
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interLeft >= interRight || interTop >= interBottom) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        return interArea / (areaA + areaB - interArea)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        isLoaded = false
    }
}
