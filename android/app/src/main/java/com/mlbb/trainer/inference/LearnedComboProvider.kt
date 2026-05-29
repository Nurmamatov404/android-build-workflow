package com.mlbb.trainer.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LearnedCombo(
    val name: String,
    val steps: List<String>,
    val minLevel: Int,
    val frequency: Float,
    val confidence: Float
)

class LearnedComboProvider {

    private var combos: List<LearnedCombo> = emptyList()
    private var levelUpPriority: List<Int> = listOf(1, 2, 1, 3, 1, 2, 1, 2, 2, 3, 2, 1, 3, 1, 2)
    private var isLoaded = false

    fun loadFromFile(context: Context, filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        return try {
            val json = JSONObject(file.readText())
            val comboArray = json.optJSONArray("combos") ?: JSONArray()
            combos = (0 until comboArray.length()).map { i ->
                val c = comboArray.getJSONObject(i)
                LearnedCombo(
                    name = c.optString("name", "combo_$i"),
                    steps = (0 until c.getJSONArray("steps").length())
                        .map { c.getJSONArray("steps").getString(it) },
                    minLevel = c.optInt("min_level", 1),
                    frequency = c.optDouble("frequency", 0.0).toFloat(),
                    confidence = c.optDouble("confidence", 0.0).toFloat()
                )
            }

            val priorityArray = json.optJSONArray("level_up_priority")
            if (priorityArray != null) {
                levelUpPriority = (0 until priorityArray.length()).map { priorityArray.getInt(it) }
            }

            isLoaded = true
            Log.i(TAG, "Loaded ${combos.size} learned combos from $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load combos: ${e.message}")
            false
        }
    }

    fun getCombos(minLevel: Int = 1, apmMode: String = "NORMAL"): List<LearnedCombo> {
        if (!isLoaded) return emptyList()
        val valid = combos.filter { it.minLevel <= minLevel }
        return if (apmMode != "INTENSE") {
            valid.filter { it.confidence > 0.3f || it.name.contains("farm") }
        } else valid
    }

    fun getTopCombos(minLevel: Int = 1, limit: Int = 3): List<LearnedCombo> {
        return getCombos(minLevel).sortedByDescending { it.frequency }.take(limit)
    }

    fun getLevelUpPriority(): List<Int> = levelUpPriority

    fun getActionSequence(name: String): List<String> {
        return combos.find { it.name == name }?.steps ?: emptyList()
    }

    fun getTotalCombos(): Int = combos.size
    fun isEmpty(): Boolean = combos.isEmpty()
    fun isLoadedSuccessfully(): Boolean = isLoaded

    companion object {
        private const val TAG = "LearnedCombo"
    }
}
