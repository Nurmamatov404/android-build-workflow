package com.mlbb.trainer.inference

import android.content.Context
import android.content.SharedPreferences

class AISettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mlbb_ai_settings", Context.MODE_PRIVATE)

    var randomOffset: Int
        get() = prefs.getInt("random_offset", 15)
        set(v) = prefs.edit().putInt("random_offset", v).apply()

    var skillMissRate: Int
        get() = prefs.getInt("skill_miss_rate", 25)
        set(v) = prefs.edit().putInt("skill_miss_rate", v).apply()

    var apmMode: String
        get() = prefs.getString("apm_mode", "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString("apm_mode", v).apply()

    var skillDirection: String
        get() = prefs.getString("skill_direction", "RANDOM") ?: "RANDOM"
        set(v) = prefs.edit().putString("skill_direction", v).apply()

    var joystickWobble: String
        get() = prefs.getString("joystick_wobble", "MEDIUM") ?: "MEDIUM"
        set(v) = prefs.edit().putString("joystick_wobble", v).apply()

    var autoLevelUp: Boolean
        get() = prefs.getBoolean("auto_level_up", true)
        set(v) = prefs.edit().putBoolean("auto_level_up", v).apply()

    var autoBuyItems: Boolean
        get() = prefs.getBoolean("auto_buy_items", true)
        set(v) = prefs.edit().putBoolean("auto_buy_items", v).apply()

    var useHeroCombos: Boolean
        get() = prefs.getBoolean("use_hero_combos", true)
        set(v) = prefs.edit().putBoolean("use_hero_combos", v).apply()

    var heroIndex: Int
        get() = prefs.getInt("hero_index", 0)
        set(v) = prefs.edit().putInt("hero_index", v).apply()

    var levelUpSequence: String
        get() = prefs.getString("level_up_seq", "SKILL1,SKILL2,ULTIMATE") ?: "SKILL1,SKILL2,ULTIMATE"
        set(v) = prefs.edit().putString("level_up_seq", v).apply()

    fun getOffsetRange(): IntRange = when (randomOffset) {
        10 -> -10..10; 15 -> -15..15; 25 -> -25..25; 50 -> -50..50
        else -> -15..15
    }

    fun getMissRate(): Float = skillMissRate / 100f
    fun isAuto() = apmMode == "AUTO"
    fun isSmartDirection() = skillDirection == "SMART"
}
