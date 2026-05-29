package com.mlbb.trainer.inference

data class UIRegion(
    val label: String,
    val centerX: Float,
    val centerY: Float,
    val radius: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f
)

data class GameKnowledge(
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,

    val joystickCenter: UIRegion = UIRegion("joystick", 0.12f, 0.78f, 0.06f),
    val joystickKnob: UIRegion = UIRegion("joystick_knob", 0.12f, 0.78f, 0.02f),

    val skillButtons: List<UIRegion> = listOf(
        UIRegion("skill1", 0.72f, 0.82f, 0.035f),
        UIRegion("skill2", 0.80f, 0.78f, 0.035f),
        UIRegion("skill3", 0.88f, 0.74f, 0.035f),
        UIRegion("ultimate", 0.94f, 0.68f, 0.04f),
    ),

    val attackButton: UIRegion = UIRegion("attack", 0.88f, 0.85f, 0.035f),
    val recallButton: UIRegion = UIRegion("recall", 0.05f, 0.50f, 0.025f),
    val minimap: UIRegion = UIRegion("minimap", 0.94f, 0.06f, 0f, 0.08f, 0.12f),

    val battleSpell: UIRegion = UIRegion("battle_spell", 0.68f, 0.86f, 0.03f),
    val itemSlots: List<UIRegion> = listOf(
        UIRegion("item1", 0.44f, 0.88f, 0.02f),
        UIRegion("item2", 0.50f, 0.88f, 0.02f),
        UIRegion("item3", 0.56f, 0.88f, 0.02f),
    ),

    val isInitialized: Boolean = false
) {
    fun skillButton(index: Int): UIRegion {
        return skillButtons.getOrElse(index) { skillButtons.first() }
    }

    fun toPixelCoords(w: Int, h: Int): PixelKnowledge {
        return PixelKnowledge(
            joystickX = (joystickCenter.centerX * w).toInt(),
            joystickY = (joystickCenter.centerY * h).toInt(),
            joystickRadius = (joystickCenter.radius * w).toInt().coerceAtLeast(40),
            skills = skillButtons.map { PixelSkillRegion(
                label = it.label,
                x = (it.centerX * w).toInt(),
                y = (it.centerY * h).toInt(),
                radius = (it.radius * w).toInt().coerceAtLeast(25)
            )},
            attackX = (attackButton.centerX * w).toInt(),
            attackY = (attackButton.centerY * h).toInt(),
            attackRadius = (attackButton.radius * w).toInt().coerceAtLeast(25),
            recallX = (recallButton.centerX * w).toInt(),
            recallY = (recallButton.centerY * h).toInt(),
            minimapLeft = ((minimap.centerX - minimap.width/2) * w).toInt(),
            minimapTop = ((minimap.centerY - minimap.height/2) * h).toInt(),
            minimapRight = ((minimap.centerX + minimap.width/2) * w).toInt(),
            minimapBottom = ((minimap.centerY + minimap.height/2) * h).toInt(),
        )
    }
}

data class PixelSkillRegion(
    val label: String,
    val x: Int,
    val y: Int,
    val radius: Int
)

data class PixelKnowledge(
    val joystickX: Int,
    val joystickY: Int,
    val joystickRadius: Int,
    val skills: List<PixelSkillRegion>,
    val attackX: Int,
    val attackY: Int,
    val attackRadius: Int,
    val recallX: Int,
    val recallY: Int,
    val minimapLeft: Int,
    val minimapTop: Int,
    val minimapRight: Int,
    val minimapBottom: Int,

    val levelUpX: Int = -1,
    val levelUpY: Int = -1,
    val shopRecommendX: Int = -1,
    val shopRecommendY: Int = -1,
    val buyConfirmX: Int = -1,
    val buyConfirmY: Int = -1,

    val heroLevel: Int = 1,
    val hasLevelUp: Boolean = false,
    val isShopOpen: Boolean = false,
    val isDead: Boolean = false,
    val matchEnded: Boolean = false,
    val inBattle: Boolean = false
)
