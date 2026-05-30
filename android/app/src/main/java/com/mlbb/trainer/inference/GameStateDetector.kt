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
        val inBattle: Boolean = false,
        val gameStarted: Boolean = false
    )

    companion object {
        private const val TAG = "GameStateDetector"
        private const val DEAD_BRIGHTNESS_THRESHOLD = 12
        private const val GAME_START_BRIGHTNESS_THRESHOLD = 80
    }

    private var lastLevel = 1
    private var darkFrameCount = 0
    private var seenBrightFrame = false

    fun detect(bitmap: Bitmap, displayW: Int, displayH: Int): GameState {
        if (bitmap.width == 0 || bitmap.height == 0) return GameState()
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val brightnessAvg = averageBrightness(pixels, w, h)

        // Qorong'i ekran (o'lim, yuklanish)
        if (brightnessAvg < DEAD_BRIGHTNESS_THRESHOLD) {
            darkFrameCount++
        } else {
            darkFrameCount = 0
            if (brightnessAvg > GAME_START_BRIGHTNESS_THRESHOLD) seenBrightFrame = true
        }

        val isDead = darkFrameCount > 15
        val gameStarted = seenBrightFrame

        // Level detection — yuqori-chap burchak
        val level = detectLevel(pixels, w, h)

        // Level up tugmasi — skill tugmalari ustidagi sariq nuqta
        val hasLevelUp = detectLevelUp(pixels, w, h)
        val levelUpPos = findLevelUp(pixels, w, h)

        // Do'kon
        val shopOpen = detectShop(pixels, w, h)
        val shopRecPos = findRecommend(pixels, w, h)
        val buyPos = findBuyConfirm(pixels, w, h)

        // Jang
        val inBattle = detectBattle(pixels, w, h)
        val ended = detectMatchEnd(pixels, w, h)

        val gold = estimateGold(pixels, w, h)

        if (level > 0) lastLevel = level

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
            inBattle = inBattle,
            gameStarted = gameStarted
        )
    }

    private fun averageBrightness(pixels: IntArray, w: Int, h: Int): Float {
        var sum = 0f; var count = 0
        for (y in 0 until h step 20)
            for (x in 0 until w step 20) {
                val p = pixels[y * w + x]
                sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
                count++
            }
        return if (count > 0) sum / count else 0f
    }

    /**
     * MLBB da hero level yuqori-chap burchakda, qahramon rasmining yonida
     * oq raqam bilan ko'rinadi. Raqam oq rangda, kichik to'rtburchak ichida.
     */
    private fun detectLevel(pixels: IntArray, w: Int, h: Int): Int {
        val scanY = (h * 0.025f).toInt().coerceIn(0, h - 1)
        val leftEdge = (w * 0.01f).toInt()
        val rightEdge = (w * 0.10f).toInt()

        var whiteCount = 0
        for (x in leftEdge until rightEdge) {
            if (scanY >= h || x >= w) continue
            val p = pixels[scanY * w + x]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val brightness = (r + g + b) / 3f
            if (brightness > 200 && r > 180 && g > 180 && b > 180) whiteCount++
        }

        if (whiteCount in 3..25) {
            return (whiteCount / 2 + 1).coerceIn(1, 15)
        }
        return lastLevel
    }

    /**
     * Level up tugmasi — skill tugmalari ustida sariq/yashil nuqta
     */
    private fun detectLevelUp(pixels: IntArray, w: Int, h: Int): Boolean {
        val scanYStart = (h * 0.65f).toInt()
        val scanYEnd = (h * 0.88f).toInt()
        var yellowCount = 0

        for (y in scanYStart until scanYEnd step 4) {
            for (x in (w * 0.60f).toInt() until w step 4) {
                if (y >= h || x >= w) continue
                val p = pixels[y * w + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > 180 && g > 150 && b < 100) yellowCount++
            }
        }
        return yellowCount > 20
    }

    /**
     * Level up tugmasi pozitsiyasi
     */
    private fun findLevelUp(pixels: IntArray, w: Int, h: Int): Pair<Int, Int> {
        val skillPositions = listOf(
            (w * 0.72f).toInt() to (h * 0.82f).toInt(),
            (w * 0.80f).toInt() to (h * 0.78f).toInt(),
            (w * 0.88f).toInt() to (h * 0.74f).toInt(),
            (w * 0.94f).toInt() to (h * 0.68f).toInt(),
        )
        for ((sx, sy) in skillPositions) {
            val checkY = (sy - h * 0.04f).toInt().coerceIn(0, h - 1)
            for (dx in -10..10) {
                val cx = (sx + dx).coerceIn(0, w - 1)
                if (checkY >= h) continue
                val p = pixels[checkY * w + cx]
                val r = Color.red(p); val g = Color.green(p)
                if (r > 200 && g > 180) return cx to checkY
            }
        }
        return -1 to -1
    }

    /**
     * Do'kon ochiqmi? — ekran markazi qorong'i bo'ladi
     */
    private fun detectShop(pixels: IntArray, w: Int, h: Int): Boolean {
        val cx = w / 2; val cy = h / 2
        var dark = 0
        for (y in (cy - 50) until (cy + 50) step 10)
            for (x in (cx - 80) until (cx + 80) step 10) {
                if (y < 0 || y >= h || x < 0 || x >= w) continue
                val p = pixels[y * w + x]
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3f < 40) dark++
            }
        return dark > 60
    }

    /**
     * "Tavsiya etilgan" tugmasi — do'kon o'ng tomoni
     */
    private fun findRecommend(pixels: IntArray, w: Int, h: Int): Pair<Int, Int> {
        val scanX = (w * 0.85f).toInt()
        var bestY = -1; var bestBright = 0f
        for (y in (h * 0.35f).toInt() until (h * 0.65f).toInt() step 4) {
            if (y >= h || scanX >= w) continue
            val p = pixels[y * w + scanX]
            val b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
            if (b > bestBright) { bestBright = b; bestY = y }
        }
        if (bestBright > 150) return scanX to bestY
        return -1 to -1
    }

    /**
     * "Sotib olish" tugmasi — pastki markaz
     */
    private fun findBuyConfirm(pixels: IntArray, w: Int, h: Int): Pair<Int, Int> {
        val y = (h * 0.82f).toInt().coerceIn(0, h - 1)
        for (dx in -60..60 step 2) {
            val x = (w / 2 + dx).coerceIn(0, w - 1)
            if (y >= h || x >= w) continue
            val p = pixels[y * w + x]
            val r = Color.red(p); val g = Color.green(p)
            if (r > 200 && g > 100) return x to y
        }
        return -1 to -1
    }

    /**
     * Jang — ekranda qizil/ko'k ranglar miqdori
     */
    private fun detectBattle(pixels: IntArray, w: Int, h: Int): Boolean {
        var redBlue = 0
        for (y in h / 2 until h step 8)
            for (x in 0 until w step 8) {
                val p = pixels[y * w + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if ((r > 180 && b < 100) || (b > 180 && r < 100)) redBlue++
            }
        return redBlue > 40
    }

    /**
     * O'yin tugaganmi? — markazda yorug' matn
     */
    private fun detectMatchEnd(pixels: IntArray, w: Int, h: Int): Boolean {
        val cx = w / 2; val cy = h / 2
        var bright = 0
        for (dy in -40..40 step 4)
            for (dx in -100..100 step 4) {
                val y = (cy + dy).coerceIn(0, h - 1)
                val x = (cx + dx).coerceIn(0, w - 1)
                val p = pixels[y * w + x]
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3f > 220) bright++
            }
        return bright > 80
    }

    private fun estimateGold(pixels: IntArray, w: Int, h: Int): Int {
        val y = (h * 0.03f).toInt().coerceIn(0, h - 1)
        var bright = 0
        for (dx in -50..50 step 4) {
            val x = (w / 2 + dx).coerceIn(0, w - 1)
            if (y >= h || x >= w) continue
            val p = pixels[y * w + x]
            if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3f > 180) bright++
        }
        return bright * 100
    }
}
