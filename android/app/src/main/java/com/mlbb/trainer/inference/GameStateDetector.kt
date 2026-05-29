package com.mlbb.trainer.inference

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

class GameStateDetector {

    data class GameState(
        val heroLevel: Int = 1,
        val hasLevelUp: Boolean = false,
        val levelUpButtonX: Int = -1,
        val levelUpButtonY: Int = -1,
        val isShopOpen: Boolean = false,
        val shopRecommendX: Int = -1,
        val shopRecommendY: Int = -1,
        val buyConfirmX: Int = -1,
        val buyConfirmY: Int = -1,
        val estimatedGold: Int = 0,
        val isDead: Boolean = false,
        val matchEnded: Boolean = false,
        val inBattle: Boolean = false
    )

    private var lastLevel = 1
    private var darkFrameCount = 0

    fun detect(bitmap: Bitmap, displayW: Int, displayH: Int, pixelKnowledge: PixelKnowledge): GameState {
        if (bitmap.width == 0 || bitmap.height == 0) return GameState()
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val brightnessAvg = averageBrightness(pixels, w, h)
        val isDead = brightnessAvg < 15 && darkFrameCount > 10

        if (brightnessAvg < 15) darkFrameCount++
        else darkFrameCount = 0

        val level = estimateLevel(pixels, w, h)
        val hasLevelUp = detectLevelUpButton(pixels, w, h, level)
        val levelUpPos = findLevelUpButton(pixels, w, h)

        val shopOpen = detectShopOpen(pixels, w, h)
        val shopRecPos = findRecommendButton(pixels, w, h, displayW, displayH)
        val buyPos = findBuyConfirm(pixels, w, h)
        val gold = estimateGold(pixels, w, h)
        val inBattle = detectBattle(pixels, w, h)
        val ended = detectMatchEnd(pixels, w, h)

        lastLevel = if (level > lastLevel) level else lastLevel

        return GameState(
            heroLevel = lastLevel,
            hasLevelUp = hasLevelUp,
            levelUpButtonX = levelUpPos.first,
            levelUpButtonY = levelUpPos.second,
            isShopOpen = shopOpen,
            shopRecommendX = shopRecPos.first,
            shopRecommendY = shopRecPos.second,
            buyConfirmX = buyPos.first,
            buyConfirmY = buyPos.second,
            estimatedGold = gold,
            isDead = isDead,
            matchEnded = ended,
            inBattle = inBattle
        )
    }

    private fun averageBrightness(pixels: IntArray, w: Int, h: Int): Float {
        val step = 20; var sum = 0f; var count = 0
        for (y in 0 until h step step)
            for (x in 0 until w step step) {
                val p = pixels[y * w + x]; sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f; count++
            }
        return sum / count
    }

    private fun estimateLevel(pixels: IntArray, w: Int, h: Int): Int {
        val y = (h * 0.04f).toInt().coerceIn(0, h - 1)
        var levelTextCount = 0
        for (x in (w / 3) until (w * 2 / 3)) {
            val p = pixels[y * w + x]
            val b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
            if (b > 150) levelTextCount++
        }
        if (levelTextCount > 20) darkFrameCount = 0
        return lastLevel
    }

    private fun detectLevelUpButton(pixels: IntArray, w: Int, h: Int, currentLevel: Int): Boolean {
        val scanYStart = (h * 0.65f).toInt()
        val scanYEnd = (h * 0.90f).toInt()
        val scanXStart = (w * 0.60f).toInt()
        var brightSpots = 0

        for (y in scanYStart until scanYEnd step 4) {
            for (x in scanXStart until w step 4) {
                val p = pixels[y * w + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val brightness = (r + g + b) / 3f
                val isYellow = r > 180 && g > 160 && b < 100
                val isWhite = brightness > 200
                if (isYellow || isWhite) brightSpots++
            }
        }
        return brightSpots > 30
    }

    private fun findLevelUpButton(pixels: IntArray, w: Int, h: Int): Pair<Int, Int> {
        val skillButtons = listOf(
            (w * 0.72f).toInt() to (h * 0.82f).toInt(),
            (w * 0.80f).toInt() to (h * 0.78f).toInt(),
            (w * 0.88f).toInt() to (h * 0.74f).toInt(),
            (w * 0.94f).toInt() to (h * 0.68f).toInt(),
        )
        for ((sx, sy) in skillButtons) {
            val checkY = (sy - h * 0.04f).toInt().coerceIn(0, h - 1)
            if (checkY >= h) continue
            for (dx in -10..10) {
                val cx = (sx + dx).coerceIn(0, w - 1)
                if (cx >= w) continue
                val p = pixels[checkY * w + cx]
                val r = Color.red(p); val g = Color.green(p)
                if (r > 200 && g > 180) {
                    return cx to checkY
                }
            }
        }
        return -1 to -1
    }

    private fun detectShopOpen(pixels: IntArray, w: Int, h: Int): Boolean {
        val centerX = w / 2; val centerY = h / 2
        var darkCount = 0; val step = 10
        for (y in (centerY - 50) until (centerY + 50) step step) {
            for (x in (centerX - 50) until (centerX + 50) step step) {
                if (y < 0 || y >= h || x < 0 || x >= w) continue
                val p = pixels[y * w + x]
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3f < 30) darkCount++
            }
        }
        return darkCount > 50
    }

    private fun findRecommendButton(pixels: IntArray, w: Int, h: Int, dw: Int, dh: Int): Pair<Int, Int> {
        val scanX = (dw * 0.85f).toInt().coerceIn(0, w - 1)
        val scanYStart = (dh * 0.35f).toInt().coerceIn(0, h - 1)
        val scanYEnd = (dh * 0.65f).toInt().coerceIn(0, h - 1)

        var bestBright = 0f; var bestY = -1
        for (y in scanYStart until scanYEnd step 4) {
            if (y >= h || scanX >= w) continue
            val p = pixels[y * w + scanX]
            val b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
            if (b > bestBright) { bestBright = b; bestY = y }
        }
        if (bestBright > 150) return (dw * 0.85f).toInt() to (bestY * dh / h)
        return -1 to -1
    }

    private fun findBuyConfirm(pixels: IntArray, w: Int, h: Int): Pair<Int, Int> {
        val bottomY = (h * 0.85f).toInt().coerceIn(0, h - 1)
        val cx = w / 2
        for (dx in -50..50 step 2) {
            val x = (cx + dx).coerceIn(0, w - 1)
            if (bottomY >= h || x >= w) continue
            val p = pixels[bottomY * w + x]
            val r = Color.red(p); val g = Color.green(p)
            if (r > 200 && g > 100) return (cx * 2 * w / dw) to (bottomY * 2 * h / dh)
        }
        return -1 to -1
    }

    private fun estimateGold(pixels: IntArray, w: Int, h: Int): Int {
        val topY = (h * 0.03f).toInt().coerceIn(0, h - 1)
        val centerX = w / 2
        var goldDigits = 0
        for (dx in -40..40 step 4) {
            val x = (centerX + dx).coerceIn(0, w - 1)
            if (topY >= h || x >= w) continue
            val p = pixels[topY * w + x]
            val b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
            if (b > 180) goldDigits++
        }
        return goldDigits * 100
    }

    private fun detectBattle(pixels: IntArray, w: Int, h: Int): Boolean {
        val bottomHalf = h / 2
        var redCount = 0; var blueCount = 0
        for (y in bottomHalf until h step 6) {
            for (x in 0 until w step 6) {
                val p = pixels[y * w + x]
                val r = Color.red(p); val b = Color.blue(p)
                if (r > 180 && b < 100) redCount++
                if (b > 180 && r < 100) blueCount++
            }
        }
        return redCount + blueCount > 50
    }

    private fun detectMatchEnd(pixels: IntArray, w: Int, h: Int): Boolean {
        val cy = h / 2; val cx = w / 2
        var brightCenter = 0
        for (dy in -30..30 step 4) {
            for (dx in -80..80 step 4) {
                val y = (cy + dy).coerceIn(0, h - 1)
                val x = (cx + dx).coerceIn(0, w - 1)
                if (y >= h || x >= w) continue
                val p = pixels[y * w + x]
                val b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
                if (b > 200) brightCenter++
            }
        }
        return brightCenter > 100
    }
}
