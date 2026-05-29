package com.mlbb.trainer.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteModel(
    private val context: Context,
    modelPath: String,
    private val inputSize: Int = 224,
    private val seqLen: Int = 4,
    private val useGpu: Boolean = true
) {
    companion object {
        private const val TAG = "TFLiteModel"
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isLoaded = false

    // Mean and std for ImageNet normalization
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    data class InferenceResult(
        val coordinates: FloatArray,  // [x1, y1, x2, y2] normalized [0,1]
        val actionLogits: FloatArray  // [DOWN, MOVE, UP, NONE]
    ) {
        val actionType: String
            get() {
                val idx = actionLogits.indices.maxByOrNull { actionLogits[it] } ?: 3
                return arrayOf("DOWN", "MOVE", "UP", "NONE")[idx]
            }

        val primaryTouchX: Float get() = coordinates[0]
        val primaryTouchY: Float get() = coordinates[1]
    }

    fun load(): Boolean {
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return false
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                if (useGpu) {
                    try {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                    } catch (e: Exception) {
                        Log.w(TAG, "GPU delegate not available, using CPU")
                    }
                }
            }

            val buffer = loadModelFile(modelPath)
            interpreter = Interpreter(buffer, options)
            isLoaded = true
            Log.i(TAG, "Model loaded successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
            return false
        }
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val file = File(path)
        return FileInputStream(file).channel.map(
            FileChannel.MapMode.READ_ONLY, 0, file.length()
        )
    }

    fun run(frames: List<Bitmap>): InferenceResult? {
        if (!isLoaded || interpreter == null) return null
        if (frames.size != seqLen) {
            Log.w(TAG, "Expected $seqLen frames, got ${frames.size}")
            return null
        }

        try {
            val inputBuffer = preprocessFrames(frames)

            val outputCoords = Array(1) { FloatArray(4) }
            val outputActions = Array(1) { FloatArray(4) }

            val outputs = mapOf(
                0 to outputCoords,
                1 to outputActions
            )

            interpreter?.runForMultipleInputsOutputs(
                arrayOf(inputBuffer), outputs
            )

            return InferenceResult(
                coordinates = outputCoords[0],
                actionLogits = outputActions[0]
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            return null
        }
    }

    private fun preprocessFrames(frames: List<Bitmap>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            seqLen * 3 * inputSize * inputSize * 4
        ).apply {
            order(ByteOrder.nativeOrder())
        }

        for (frame in frames) {
            val resized = Bitmap.createScaledBitmap(frame, inputSize, inputSize, true)
            val pixels = IntArray(inputSize * inputSize)
            resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            for (pixel in pixels) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                buffer.putFloat((r - mean[0]) / std[0])
                buffer.putFloat((g - mean[1]) / std[1])
                buffer.putFloat((b - mean[2]) / std[2])
            }
        }

        buffer.rewind()
        return buffer
    }

    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
        } catch (e: Exception) {}
        isLoaded = false
    }
}
