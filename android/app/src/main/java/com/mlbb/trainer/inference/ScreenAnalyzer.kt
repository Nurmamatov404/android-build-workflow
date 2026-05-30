package com.mlbb.trainer.inference

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class ScreenAnalyzer {

    companion object {
        private const val TAG = "ScreenAnalyzer"
        private const val MIN_JOYSTICK_AREA = 2000
        private const val MIN_SKILL_AREA = 400
    }

    data class AnalysisResult(
        val joystickX: Int, val joystickY: Int, val joystickRadius: Int,
        val skillButtons: List<SkillDetect>,
        val attackX: Int, val attackY: Int, val attackRadius: Int,
        val minimapRegion: MinimapRegion,
        val displayWidth: Int, val displayHeight: Int,
        val yoloUsed: Boolean = false
    )

    data class SkillDetect(val label: String, val x: Int, val y: Int, val radius: Int, val confidence: Float)
    data class MinimapRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private var yoloDetector: YOLODetector? = null

    fun setYOLODetector(detector: YOLODetector?) {
        yoloDetector = detector
    }

    fun analyze(bitmap: Bitmap): GameKnowledge {
        if (bitmap.width == 0 || bitmap.height == 0) return GameKnowledge(isInitialized = false)

        val w = bitmap.width
        val h = bitmap.height
        Log.d(TAG, "Analyzing screen: ${w}x${h}")

        val yoloDetections = yoloDetector?.detect(bitmap) ?: emptyList()
        val yoloUsed = yoloDetections.isNotEmpty()

        if (yoloUsed) {
            Log.d(TAG, "YOLO detected ${yoloDetections.size} UI elements")
            return buildFromYOLO(yoloDetections, w, h)
        }

        return buildFromHeuristic(bitmap, w, h)
    }

    private fun buildFromYOLO(detections: List<YOLODetector.Detection>, w: Int, h: Int): GameKnowledge {
        val detMap = detections.groupBy { it.label }

        fun getRect(label: String): RectF? = detMap[label]?.firstOrNull()?.rect
        fun getCenter(label: String): Pair<Int, Int>? {
            val r = getRect(label) ?: return null
            return (r.centerX().toInt()) to (r.centerY().toInt())
        }

        val joystick = getCenter("joystick") ?: ((w * 0.12f).toInt() to (h * 0.78f).toInt())
        val jr = (getRect("joystick")?.let { max(it.width(), it.height()) / 2f }?.toInt()
            ?: (w * 0.06f).toInt()).coerceAtLeast(40)

        val attack = getCenter("attack") ?: ((w * 0.88f).toInt() to (h * 0.85f).toInt())
        val ar = (getRect("attack")?.let { max(it.width(), it.height()) / 2f }?.toInt()
            ?: (w * 0.035f).toInt()).coerceAtLeast(25)

        val minimapRect = getRect("minimap")
        val ml = minimapRect?.left?.toInt() ?: (w * 0.88f).toInt()
        val mt = minimapRect?.top?.toInt() ?: 0
        val mr = minimapRect?.right?.toInt() ?: (w - 1)
        val mb = minimapRect?.bottom?.toInt() ?: (h * 0.12f).toInt()

        val yoloSkills = listOf("skill1", "skill2", "skill3", "ultimate").mapNotNull { label ->
            getRect(label)?.let { r ->
                SkillDetect(
                    label = label,
                    x = r.centerX().toInt(),
                    y = r.centerY().toInt(),
                    radius = (max(r.width(), r.height()) / 2f).toInt().coerceAtLeast(25),
                    confidence = detMap[label]?.firstOrNull()?.confidence ?: 0.5f
                )
            }
        }

        val defaultSkills = listOf(
            SkillDetect("skill1", (w * 0.72f).toInt(), (h * 0.82f).toInt(), (w * 0.035f).toInt(), 0.5f),
            SkillDetect("skill2", (w * 0.80f).toInt(), (h * 0.78f).toInt(), (w * 0.035f).toInt(), 0.5f),
            SkillDetect("skill3", (w * 0.88f).toInt(), (h * 0.74f).toInt(), (w * 0.035f).toInt(), 0.5f),
            SkillDetect("ultimate", (w * 0.94f).toInt(), (h * 0.68f).toInt(), (w * 0.04f).toInt(), 0.5f),
        )

        val skills = if (yoloSkills.size >= 3) yoloSkills else defaultSkills

        return GameKnowledge(
            displayWidth = w,
            displayHeight = h,
            joystickCenter = UIRegion("joystick", joystick.first.toFloat()/w, joystick.second.toFloat()/h, jr.toFloat()/w),
            skillButtons = skills.map { UIRegion(it.label, it.x.toFloat()/w, it.y.toFloat()/h, it.radius.toFloat()/w) },
            attackButton = UIRegion("attack", attack.first.toFloat()/w, attack.second.toFloat()/h, ar.toFloat()/w),
            recallButton = UIRegion("recall",
                (detMap["recall"]?.firstOrNull()?.rect?.centerX() ?: (w * 0.05f)) / w,
                (detMap["recall"]?.firstOrNull()?.rect?.centerY() ?: (h * 0.50f)) / h,
                0.025f),
            minimap = UIRegion("minimap", ((ml+mr)/2).toFloat()/w, ((mt+mb)/2).toFloat()/h,
                0f, (mr-ml).toFloat()/w, (mb-mt).toFloat()/h),
            isInitialized = true
        )
    }

    private fun buildFromHeuristic(bitmap: Bitmap, w: Int, h: Int): GameKnowledge {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val joystick = detectJoystick(pixels, w, h)
        val skillButtons = detectSkillButtons(pixels, w, h, joystick)
        val attack = detectAttackButton(pixels, w, h, skillButtons)
        val minimap = detectMinimap(pixels, w, h)

        if (joystick != null) {
            Log.d(TAG, "Heuristic joystick at (${joystick.first}, ${joystick.second})")
        }
        Log.d(TAG, "Heuristic detected ${skillButtons.size} skill buttons")

        val defaultSkillPositions = listOf(
            SkillDetect("skill1", (w * 0.72f).toInt(), (h * 0.82f).toInt(), (w * 0.035f).toInt(), 0.5f),
            SkillDetect("skill2", (w * 0.80f).toInt(), (h * 0.78f).toInt(), (w * 0.035f).toInt(), 0.5f),
            SkillDetect("skill3", (w * 0.88f).toInt(), (h * 0.74f).toInt(), (w * 0.035f).toInt(), 0.5f),
            SkillDetect("ultimate", (w * 0.94f).toInt(), (h * 0.68f).toInt(), (w * 0.04f).toInt(), 0.5f),
        )
        val actualSkills = if (skillButtons.isNotEmpty()) skillButtons else defaultSkillPositions

        val jx = joystick?.first ?: (w * 0.12f).toInt()
        val jy = joystick?.second ?: (h * 0.78f).toInt()
        val jr = (w * 0.06f).toInt().coerceAtLeast(40)

        val ax = attack?.first ?: (w * 0.88f).toInt()
        val ay = attack?.second ?: (h * 0.85f).toInt()
        val ar = (w * 0.035f).toInt().coerceAtLeast(25)

        val ml = minimap?.left ?: (w * 0.88f).toInt()
        val mt = minimap?.top ?: 0
        val mr = minimap?.right ?: (w - 1)
        val mb = minimap?.bottom ?: (h * 0.12f).toInt()

        val skills = actualSkills.map { it.copy(radius = it.radius.coerceAtLeast(25)) }

        return GameKnowledge(
            displayWidth = w,
            displayHeight = h,
            joystickCenter = UIRegion("joystick", jx.toFloat()/w, jy.toFloat()/h, jr.toFloat()/w),
            skillButtons = skills.map { UIRegion(it.label, it.x.toFloat()/w, it.y.toFloat()/h, it.radius.toFloat()/w) },
            attackButton = UIRegion("attack", ax.toFloat()/w, ay.toFloat()/h, ar.toFloat()/w),
            minimap = UIRegion("minimap", ((ml+mr)/2).toFloat()/w, ((mt+mb)/2).toFloat()/h, 0f, (mr-ml).toFloat()/w, (mb-mt).toFloat()/h),
            isInitialized = true
        )
    }

    private fun detectJoystick(pixels: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        val bottomQuarter = h * 2 / 3
        val leftQuarter = w / 2
        var bestX = -1; var bestY = -1; var bestScore = 0f

        val step = 8
        for (y in bottomQuarter until h step step) {
            for (x in 0 until leftQuarter step step) {
                val idx = y * w + x
                if (idx >= pixels.size) continue
                val pixel = pixels[idx]
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)

                val brightness = (r + g + b) / 3f
                val isDark = brightness < 80
                val hasGreenHint = g > r * 1.1f && g > b * 1.1f && brightness > 30

                if (isDark || hasGreenHint) {
                    val score = 1f + countSimilar(pixels, w, h, x, y, 30)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x; bestY = y
                    }
                }
            }
        }

        if (bestScore > MIN_JOYSTICK_AREA / (step * step)) {
            return Pair(bestX, bestY)
        }
        return null
    }

    private fun detectSkillButtons(pixels: IntArray, w: Int, h: Int, joystick: Pair<Int, Int>?): List<SkillDetect> {
        val bottomHalf = h / 2
        val rightThird = w * 2 / 3
        val buttons = mutableListOf<SkillDetect>()

        val step = 6
        for (y in bottomHalf until h step step) {
            for (x in rightThird until w step step) {
                val idx = y * w + x
                if (idx >= pixels.size) continue
                val pixel = pixels[idx]
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                val brightness = (r + g + b) / 3f

                if (brightness in 30f..120f) {
                    val area = countSimilar(pixels, w, h, x, y, 20)
                    if (area > MIN_SKILL_AREA) {
                        val isDuplicate = buttons.any {
                            sqrt(((it.x - x).toFloat().pow2() + (it.y - y).toFloat().pow2())) < 50
                        }
                        if (!isDuplicate) {
                            val conf = (area / 5000f).coerceAtMost(1f)
                            buttons.add(SkillDetect("skill_${buttons.size}", x, y, 25, conf))
                        }
                    }
                }
            }
        }

        buttons.sortByDescending { it.y }
        val labeled = mutableListOf<SkillDetect>()
        for ((i, btn) in buttons.withIndex()) {
            val label = when {
                i == buttons.size - 1 && btn.y < h * 0.7f -> "ultimate"
                i == 0 -> "skill3"
                i == 1 -> "skill2"
                else -> "skill1"
            }
            labeled.add(btn.copy(label = label))
        }
        labeled.sortBy { it.x }

        return labeled
    }

    private fun detectAttackButton(pixels: IntArray, w: Int, h: Int, skills: List<SkillDetect>): Pair<Int, Int>? {
        val bottomArea = h * 4 / 5
        val rightEdge = w * 4 / 5

        var bestX = -1; var bestY = -1; var bestScore = 0f
        val step = 6

        for (y in bottomArea until h step step) {
            for (x in rightEdge until w step step) {
                val isSkill = skills.any { sqrt(((it.x - x).toFloat().pow2() + (it.y - y).toFloat().pow2())) < 60 }
                if (isSkill) continue

                val idx = y * w + x
                if (idx >= pixels.size) continue
                val pixel = pixels[idx]
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                val brightness = (r + g + b) / 3f

                if (brightness > 100) {
                    val area = countSimilar(pixels, w, h, x, y, 30)
                    if (area > MIN_SKILL_AREA / 2 && area.toFloat() > bestScore) {
                        bestScore = area.toFloat()
                        bestX = x; bestY = y
                    }
                }
            }
        }

        return if (bestScore > 0) Pair(bestX, bestY) else null
    }

    private fun detectMinimap(pixels: IntArray, w: Int, h: Int): MinimapRegion? {
        val rightCol = w * 9 / 10
        val topHalf = h / 3

        var foundLeft = w; var foundRight = 0; var foundTop = h; var foundBottom = 0

        val step = 4
        for (y in 0 until topHalf step step) {
            for (x in rightCol until w step step) {
                val idx = y * w + x
                if (idx >= pixels.size) continue
                val pixel = pixels[idx]
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)

                val isDark = (r + g + b) / 3 < 50
                val hasBlue = b > r * 1.3f && b > g * 1.3f

                if (isDark || hasBlue) {
                    if (x < foundLeft) foundLeft = x
                    if (x > foundRight) foundRight = x
                    if (y < foundTop) foundTop = y
                    if (y > foundBottom) foundBottom = y
                }
            }
        }

        val area = (foundRight - foundLeft) * (foundBottom - foundTop)
        return if (area > 500 && foundLeft < foundRight && foundTop < foundBottom) {
            MinimapRegion(foundLeft, foundTop, foundRight, foundBottom)
        } else null
    }

    private fun countSimilar(pixels: IntArray, w: Int, h: Int, cx: Int, cy: Int, threshold: Int): Int {
        val centerPixel = pixels[cy * w + cx]
        val cr = Color.red(centerPixel); val cg = Color.green(centerPixel); val cb = Color.blue(centerPixel)
        val radius = 40
        var count = 0

        for (dy in -radius..radius step 2) {
            for (dx in -radius..radius step 2) {
                val x = cx + dx; val y = cy + dy
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val idx = y * w + x
                if (idx >= pixels.size) continue
                val p = pixels[idx]
                val dr = abs(Color.red(p) - cr); val dg = abs(Color.green(p) - cg); val db = abs(Color.blue(p) - cb)
                if (dr + dg + db < threshold) count++
            }
        }
        return count
    }

    private fun Float.pow2(): Float = this * this
}
