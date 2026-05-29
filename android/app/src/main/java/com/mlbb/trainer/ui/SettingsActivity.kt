package com.mlbb.trainer.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mlbb.trainer.R
import com.mlbb.trainer.inference.AISettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: AISettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "AI Settings"

        settings = AISettings(this)
        loadSettings()

        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.resetDefaultsButton).setOnClickListener { resetDefaults() }

        val missSeek = findViewById<SeekBar>(R.id.missRateSeek)
        missSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, b: Boolean) {
                findViewById<TextView>(R.id.missRateValue).text = "$v%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun loadSettings() {
        when (settings.randomOffset) {
            10 -> findViewById<RadioButton>(R.id.offset10).isChecked = true
            15 -> findViewById<RadioButton>(R.id.offset15).isChecked = true
            25 -> findViewById<RadioButton>(R.id.offset25).isChecked = true
            50 -> findViewById<RadioButton>(R.id.offset50).isChecked = true
        }

        findViewById<SeekBar>(R.id.missRateSeek).progress = settings.skillMissRate
        findViewById<TextView>(R.id.missRateValue).text = "${settings.skillMissRate}%"

        when (settings.apmMode) {
            "AUTO" -> findViewById<RadioButton>(R.id.apmAuto).isChecked = true
            "LAZY" -> findViewById<RadioButton>(R.id.apmLazy).isChecked = true
            "NORMAL" -> findViewById<RadioButton>(R.id.apmNormal).isChecked = true
            "INTENSE" -> findViewById<RadioButton>(R.id.apmIntense).isChecked = true
        }

        when (settings.skillDirection) {
            "RANDOM" -> findViewById<RadioButton>(R.id.dirRandom).isChecked = true
            "SMART" -> findViewById<RadioButton>(R.id.dirSmart).isChecked = true
        }

        when (settings.joystickWobble) {
            "OFF" -> findViewById<RadioButton>(R.id.wobbleOff).isChecked = true
            "LIGHT" -> findViewById<RadioButton>(R.id.wobbleLight).isChecked = true
            "MEDIUM" -> findViewById<RadioButton>(R.id.wobbleMedium).isChecked = true
            "HEAVY" -> findViewById<RadioButton>(R.id.wobbleHeavy).isChecked = true
        }

        findViewById<CheckBox>(R.id.autoLevelUpCheck).isChecked = settings.autoLevelUp
        findViewById<CheckBox>(R.id.autoBuyCheck).isChecked = settings.autoBuyItems
        findViewById<CheckBox>(R.id.useCombosCheck).isChecked = settings.useHeroCombos
    }

    private fun saveSettings() {
        settings.randomOffset = when {
            findViewById<RadioButton>(R.id.offset10).isChecked -> 10
            findViewById<RadioButton>(R.id.offset15).isChecked -> 15
            findViewById<RadioButton>(R.id.offset25).isChecked -> 25
            findViewById<RadioButton>(R.id.offset50).isChecked -> 50
            else -> 15
        }
        settings.skillMissRate = findViewById<SeekBar>(R.id.missRateSeek).progress
        settings.apmMode = when {
            findViewById<RadioButton>(R.id.apmAuto).isChecked -> "AUTO"
            findViewById<RadioButton>(R.id.apmLazy).isChecked -> "LAZY"
            findViewById<RadioButton>(R.id.apmNormal).isChecked -> "NORMAL"
            findViewById<RadioButton>(R.id.apmIntense).isChecked -> "INTENSE"
            else -> "AUTO"
        }
        settings.skillDirection = when {
            findViewById<RadioButton>(R.id.dirRandom).isChecked -> "RANDOM"
            findViewById<RadioButton>(R.id.dirSmart).isChecked -> "SMART"
            else -> "RANDOM"
        }
        settings.joystickWobble = when {
            findViewById<RadioButton>(R.id.wobbleOff).isChecked -> "OFF"
            findViewById<RadioButton>(R.id.wobbleLight).isChecked -> "LIGHT"
            findViewById<RadioButton>(R.id.wobbleMedium).isChecked -> "MEDIUM"
            findViewById<RadioButton>(R.id.wobbleHeavy).isChecked -> "HEAVY"
            else -> "MEDIUM"
        }
        settings.autoLevelUp = findViewById<CheckBox>(R.id.autoLevelUpCheck).isChecked
        settings.autoBuyItems = findViewById<CheckBox>(R.id.autoBuyCheck).isChecked
        settings.useHeroCombos = findViewById<CheckBox>(R.id.useCombosCheck).isChecked

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetDefaults() {
        findViewById<RadioButton>(R.id.offset15).isChecked = true
        findViewById<SeekBar>(R.id.missRateSeek).progress = 25
        findViewById<RadioButton>(R.id.apmAuto).isChecked = true
        findViewById<RadioButton>(R.id.dirRandom).isChecked = true
        findViewById<RadioButton>(R.id.wobbleMedium).isChecked = true
        findViewById<CheckBox>(R.id.autoLevelUpCheck).isChecked = true
        findViewById<CheckBox>(R.id.autoBuyCheck).isChecked = true
        findViewById<CheckBox>(R.id.useCombosCheck).isChecked = true
        Toast.makeText(this, "Defaults restored. Save to apply.", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
