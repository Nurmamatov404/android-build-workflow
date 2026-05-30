package com.mlbb.trainer.inference

data class SkillCombo(
    val name: String,
    val steps: List<ComboStep>,
    val minLevel: Int = 1,
    val apmRequired: String = "NORMAL"
)

data class ComboStep(
    val action: String,
    val skillIndex: Int = -1,
    val directionDeg: Float? = null,
    val delayBefore: Long = 0L,
    val delayAfter: Long = 0L
)

class HeroComboProvider {

    private val learnedProvider = LearnedComboProvider()
    private var learnedCombos: List<LearnedCombo> = emptyList()
    private var learnedPriority: List<Int> = listOf(1, 2, 1, 3, 1, 2, 1, 2, 2, 3, 2, 1, 3, 1, 2)

    fun loadLearnedCombos(context: android.content.Context, modelPath: String) {
        val comboJsonPath = modelPath.replace(".tflite", "_combos.json")
        val comboFile = java.io.File(comboJsonPath)
        if (comboFile.exists()) {
            learnedProvider.loadFromFile(context, comboJsonPath)
            learnedCombos = learnedProvider.getCombos()
            learnedPriority = learnedProvider.getLevelUpPriority()
            android.util.Log.i("HeroCombo", "Loaded ${learnedCombos.size} learned combos")
        }
    }

    fun hasLearnedCombos(): Boolean = learnedCombos.isNotEmpty()

    fun getLearnedCombos(): List<LearnedCombo> = learnedCombos

    private val allCombos = mapOf(
        "FANNY" to listOf(
            SkillCombo("farming", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2, delayAfter = 50)
            ), minLevel = 1),
            SkillCombo("cable_pull", listOf(
                ComboStep("skill_dir", 2, 180f, delayBefore = 200),
                ComboStep("skill", 1, delayAfter = 100),
                ComboStep("skill_dir", 2, 0f),
            ), minLevel = 4),
            SkillCombo("ult_burst", listOf(
                ComboStep("skill_dir", 2, 0f),
                ComboStep("ultimate", 3, delayAfter = 50),
                ComboStep("skill", 1), ComboStep("skill", 2),
            ), minLevel = 4, apmRequired = "INTENSE"),
        ),
        "LING" to listOf(
            SkillCombo("poke", listOf(
                ComboStep("skill", 1), ComboStep("skill_dir", 2, 270f),
            ), minLevel = 1),
            SkillCombo("ult_combo", listOf(
                ComboStep("skill_dir", 2, 270f),
                ComboStep("skill", 1, delayAfter = 100),
                ComboStep("ultimate", 3),
                ComboStep("skill_dir", 2, 90f),
            ), minLevel = 4, apmRequired = "INTENSE"),
            SkillCombo("escape", listOf(
                ComboStep("skill", 1), ComboStep("skill_dir", 2, 90f),
            ), minLevel = 1),
        ),
        "HAYABUSA" to listOf(
            SkillCombo("farming", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2, delayAfter = 100),
            ), minLevel = 1),
            SkillCombo("full_combo", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2),
                ComboStep("ultimate", 3, delayAfter = 50),
                ComboStep("skill", 1), ComboStep("skill", 2),
            ), minLevel = 4, apmRequired = "INTENSE"),
        ),
        "CHOU" to listOf(
            SkillCombo("farming", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2),
            ), minLevel = 1),
            SkillCombo("combo_1", listOf(
                ComboStep("skill", 2), ComboStep("skill_dir", 1, 0f),
                ComboStep("ultimate", 3), ComboStep("skill", 2),
            ), minLevel = 4),
        ),
        "GUSION" to listOf(
            SkillCombo("farm", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2),
            ), minLevel = 1),
            SkillCombo("oneshot", listOf(
                ComboStep("skill_dir", 2, 0f), ComboStep("skill_dir", 1, 0f),
                ComboStep("skill", 1), ComboStep("ultimate", 3),
            ), minLevel = 4, apmRequired = "INTENSE"),
        ),
        "JOY" to listOf(
            SkillCombo("rhythm", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2),
                ComboStep("skill_dir", 1, 0f),
            ), minLevel = 1),
            SkillCombo("full", listOf(
                ComboStep("skill", 2), ComboStep("skill_dir", 1, 0f),
                ComboStep("skill", 2), ComboStep("skill_dir", 1, 0f),
                ComboStep("skill", 2), ComboStep("skill_dir", 1, 0f),
                ComboStep("ultimate", 3),
            ), minLevel = 4, apmRequired = "INTENSE"),
        ),
        "BENEDETTA" to listOf(
            SkillCombo("farm", listOf(ComboStep("skill", 1), ComboStep("skill", 2)), 1),
            SkillCombo("combo", listOf(
                ComboStep("skill", 2), ComboStep("skill", 1),
                ComboStep("ultimate", 3), ComboStep("skill", 2),
            ), 4, "INTENSE"),
        ),
        "LANCE" to listOf(
            SkillCombo("poke", listOf(
                ComboStep("skill_dir", 1, 0f), ComboStep("skill", 2),
            ), 1),
            SkillCombo("full", listOf(
                ComboStep("skill_dir", 1, 0f), ComboStep("skill", 2),
                ComboStep("skill_dir", 1, 0f), ComboStep("skill", 2),
                ComboStep("ultimate", 3), ComboStep("skill_dir", 1, 0f),
            ), 4, "INTENSE"),
        ),
        "ALDOUS" to listOf(
            SkillCombo("stack", listOf(ComboStep("skill", 1)), 1),
            SkillCombo("combo", listOf(
                ComboStep("ultimate", 3), ComboStep("skill_dir", 1, 0f),
                ComboStep("skill", 2),
            ), 4),
        ),
        "KAGURA" to listOf(
            SkillCombo("poke", listOf(ComboStep("skill", 1), ComboStep("skill", 2)), 1),
            SkillCombo("full", listOf(
                ComboStep("skill", 1), ComboStep("skill", 2),
                ComboStep("skill", 1), ComboStep("skill", 2),
                ComboStep("ultimate", 3), ComboStep("skill", 1),
            ), 4, "INTENSE"),
        ),
    )

    private val farmRotation = listOf(
        ComboStep("skill", 0), ComboStep("skill", 1),
        ComboStep("attack"), ComboStep("move"),
        ComboStep("skill", 0), ComboStep("attack"),
        ComboStep("move"), ComboStep("move"),
        ComboStep("skill", 1), ComboStep("attack"),
        ComboStep("move"), ComboStep("skill", 0),
        ComboStep("attack"), ComboStep("attack"),
        ComboStep("skill", 1), ComboStep("move"),
    )

    fun getCombos(heroName: String, level: Int, apmMode: String): List<SkillCombo> {
        val upper = heroName.uppercase()
        val heroCombos = allCombos[upper]
        val hardcoded = if (heroCombos != null) {
            heroCombos.filter { it.minLevel <= level }
                .filter { apmMode == "INTENSE" || it.apmRequired != "INTENSE" }
        } else {
            listOf(SkillCombo("basic_farm", farmRotation, minLevel = 1))
        }

        val learnedSkillCombos = learnedCombos
            .filter { it.minLevel <= level }
            .map { lc -> SkillCombo(
                name = "learned_${lc.name}",
                steps = convertLearnedSteps(lc.steps),
                minLevel = lc.minLevel,
                apmRequired = if (lc.confidence > 0.6f) "INTENSE" else "NORMAL"
            )}

        val merged = hardcoded + learnedSkillCombos
        return merged.take(8)
    }

    private fun convertLearnedSteps(steps: List<String>): List<ComboStep> {
        val skillIndex = mapOf(
            "skill1" to 0, "skill2" to 1, "skill3" to 2,
            "ultimate" to 3, "attack" to -2, "move" to -3
        )
        return steps.mapNotNull { action ->
            when (action) {
                "attack" -> ComboStep("attack", delayAfter = 80)
                "move" -> ComboStep("move", delayAfter = 100)
                "skill1" -> ComboStep("skill", 0, delayAfter = 60)
                "skill2" -> ComboStep("skill", 1, delayAfter = 60)
                "skill3" -> ComboStep("skill", 2, delayAfter = 60)
                "ultimate" -> ComboStep("ultimate", 3, delayAfter = 100)
                else -> null
            }
        }
    }

    fun getFarmRotation(): List<ComboStep> = farmRotation

    fun getLevelUpPriority(heroName: String): List<Int> {
        if (hasLearnedCombos()) {
            val lp = learnedProvider.getLevelUpPriority()
            if (lp.size >= 5) return lp
        }
        return when (heroName.uppercase()) {
            "FANNY" -> listOf(2, 1, 2, 3, 2, 1, 2, 1, 1, 3, 1, 2, 3, 2, 1)
            "LING" -> listOf(1, 2, 1, 3, 1, 2, 1, 2, 2, 3, 2, 1, 3, 1, 2)
            "HAYABUSA" -> listOf(1, 2, 1, 3, 1, 2, 1, 2, 2, 3, 2, 1, 3, 1, 2)
            "CHOU" -> listOf(2, 1, 2, 3, 2, 1, 2, 1, 1, 3, 1, 2, 3, 2, 1)
            "GUSION" -> listOf(2, 1, 2, 3, 2, 1, 2, 1, 1, 3, 1, 2, 3, 2, 1)
            "BENEDETTA" -> listOf(1, 2, 1, 3, 1, 2, 1, 2, 2, 3, 2, 1, 3, 1, 2)
            else -> listOf(1, 2, 1, 3, 1, 2, 1, 2, 2, 3, 2, 1, 3, 1, 2)
        }
    }
}
