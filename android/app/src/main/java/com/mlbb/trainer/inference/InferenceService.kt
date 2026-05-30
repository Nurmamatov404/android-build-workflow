package com.mlbb.trainer.inference

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mlbb.trainer.RecordingService
import com.mlbb.trainer.overlay.GameOverlayService
import java.io.File
import kotlin.random.Random

class InferenceService : Service() {

    companion object {
        const val ACTION_START = "com.mlbb.trainer.START_AI"
        const val ACTION_STOP = "com.mlbb.trainer.STOP_AI"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_HERO_ID = "hero_id"
        const val EXTRA_HERO_NAME = "hero_name"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "mlbb_ai"
        var isRunning = false; private set
        var currentApmMode = "NORMAL"; private set
        var currentLevel = 1; private set
        var currentPhase = "PLAYING"; private set
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inferenceThread: HandlerThread? = null
    private var inferenceHandler: Handler? = null
    private var tfliteModel: TFLiteModel? = null
    private var yoloDetector: YOLODetector? = null
    private var touchExecutor: HumanLikeTouchExecutor? = null
    private var screenAnalyzer: ScreenAnalyzer? = null
    private var gameStateDetector: GameStateDetector? = null
    private var modelPath = ""
    private var heroId = -1L; private var heroName = ""
    private var useYOLO = false

    private lateinit var settings: AISettings
    private val comboProvider = HeroComboProvider()
    private val learnedComboProvider = LearnedComboProvider()
    private var usingLearnedCombos = false
    private var currentCombos: List<SkillCombo> = emptyList()
    private var currentComboIndex = -1; private var currentStepIndex = 0
    private var comboCooldown = 0; private var farmCycle = 0

    private var gameKnowledge = GameKnowledge(isInitialized = false)
    private var pixelKnowledge: PixelKnowledge? = null
    private var displayWidth = 0; private var displayHeight = 0; private var displayDensity = 0
    private var actionCount = 0; private var gamePhase = GamePhase.STARTING
    private var apmMode = ApmMode.NORMAL; private var burstTimer = 0
    private var lastHeroAngle = 0f
    private var reanalyzeCounter = 0
    private val modelSeqLen = 4
    private val modelFrameInterval = 7

    private var lastLevel = 1; private var levelUpSkillPriority = listOf(0, 1, 2)
    private var lastLevelUpAction = 0; private var buyCooldown = 0
    private var deadTimer = 0; private var isAlive = true
    private var frameCount = 0L
    private var lastFrameLogTime = 0L

    private var aiState = AIState.LANE_FARM
    private var stateTimer = 0
    private val modelFrameBuffer = mutableListOf<Bitmap>()
    private var modelInferenceCounter = 0
    private var lastModelOutput: TFLiteModel.InferenceResult? = null

    private enum class GamePhase { STARTING, ANALYZING, PLAYING, STOPPED }
    private enum class ApmMode { LAZY, NORMAL, INTENSE }
    private enum class AIState {
        LANE_FARM, TEAM_FIGHT, DEAD, SHOPPING, RECALL, ROAMING
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        touchExecutor = HumanLikeTouchExecutor(this)
        screenAnalyzer = ScreenAnalyzer()
        gameStateDetector = GameStateDetector()
        settings = AISettings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: ""
                heroId = intent.getLongExtra(EXTRA_HERO_ID, -1)
                heroName = intent.getStringExtra(EXTRA_HERO_NAME) ?: ""
                settings.heroIndex = heroId.toInt()
                startInference(
                    intent.getIntExtra(EXTRA_RESULT_CODE, -1),
                    intent.getParcelableExtra<Intent>(EXTRA_DATA)
                )
            }
            ACTION_STOP -> stopInference()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInference(resultCode: Int, data: Intent?) {
        if (isRunning) return

        comboProvider.loadLearnedCombos(this, modelPath)
        usingLearnedCombos = comboProvider.hasLearnedCombos()
        if (usingLearnedCombos) {
            Log.i(TAG, "Using ${comboProvider.getLearnedCombos().size} combos learned from YouTube!")
        }

        levelUpSkillPriority = comboProvider.getLevelUpPriority(heroName)
        lastLevel = 1; lastLevelUpAction = 0; deadTimer = 0; isAlive = true; buyCooldown = 0

        currentCombos = comboProvider.getCombos(heroName, 1, settings.apmMode)
        currentComboIndex = -1; farmCycle = 0

        val notification = createNotification("AI ishga tushmoqda...")
        startForeground(NOTIFICATION_ID, notification)

        if (modelPath.isNotEmpty()) {
            tfliteModel = TFLiteModel(this, modelPath, inputSize = 224, seqLen = 4)
            if (!tfliteModel!!.load()) tfliteModel = null

            val yoloPath = modelPath.replace(".tflite", "_yolo.tflite")
            if (File(yoloPath).exists()) {
                yoloDetector = YOLODetector(this, yoloPath)
                if (yoloDetector!!.load()) {
                    screenAnalyzer?.setYOLODetector(yoloDetector)
                    useYOLO = true
                    Log.i(TAG, "YOLO object detection enabled: $yoloPath")
                } else {
                    yoloDetector = null
                }
            }
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels; displayHeight = metrics.heightPixels; displayDensity = metrics.densityDpi

        inferenceThread = HandlerThread("InferenceThread").apply { start() }
        inferenceHandler = Handler(inferenceThread!!.looper)

        if (resultCode != -1 && data != null) {
            setupMediaProjection(resultCode, data)
        } else if (RecordingService.lastProjectionResultCode != -1 && RecordingService.lastProjectionData != null) {
            Log.i(TAG, "Using stored MediaProjection from RecordingService")
            setupMediaProjection(RecordingService.lastProjectionResultCode, RecordingService.lastProjectionData!!)
        }

        if (imageReader == null) {
            Log.e(TAG, "FATAL: MediaProjection o'rnatilmadi! Ekran yozib olish ruxsati kerak.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        gamePhase = GamePhase.ANALYZING
        isRunning = true
        actionCount = 0
        apmMode = ApmMode.NORMAL

        inferenceHandler?.postDelayed({
            Log.i(TAG, "AI mode started for $heroName")
            gamePhase = GamePhase.PLAYING
            scheduleNextAction()
        }, 3000)

        Log.i(TAG, "AI inference starting for $heroName")
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 4)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MLBB-AI-${heroName}", displayWidth, displayHeight, displayDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
        if (imageReader == null) {
            Log.e(TAG, "imageReader yaratilmadi!")
            return
        }
        if (virtualDisplay == null) {
            Log.w(TAG, "virtualDisplay yaratilmadi!")
        }
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = imageToBitmap(image)
            image.close()
            if (bitmap != null) {
                inferenceHandler?.post { onFrame(bitmap) }
            }
        }, inferenceHandler)
        Log.i(TAG, "MediaProjection o'rnatildi: ${displayWidth}x${displayHeight} @ ${displayDensity}dpi")
    }

    private fun onFrame(bitmap: Bitmap) {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFrameLogTime > 5000) {
            Log.d(TAG, "FPS: ${frameCount / 5f} pk=${pixelKnowledge != null} phase=$gamePhase")
            frameCount = 0; lastFrameLogTime = now
        }

        if (gamePhase == GamePhase.ANALYZING) { analyzeScreen(bitmap); return }

        reanalyzeCounter++
        if (reanalyzeCounter >= 200 || pixelKnowledge == null) {
            reanalyzeCounter = 0

            val yoloUsed = useYOLO && yoloDetector != null
            if (yoloUsed) {
                val yoloResult = screenAnalyzer?.analyze(bitmap)
                if (yoloResult != null && yoloResult.isInitialized) {
                    gameKnowledge = yoloResult
                    pixelKnowledge = gameKnowledge.toPixelCoords(displayWidth, displayHeight)
                    Log.d(TAG, "YOLO: Joystick=(${pixelKnowledge?.joystickX},${pixelKnowledge?.joystickY}) Skills=${pixelKnowledge?.skills?.size}")
                }
            } else {
                val gk = screenAnalyzer?.analyze(bitmap)
                if (gk != null && gk.isInitialized) {
                    gameKnowledge = gk
                } else if (pixelKnowledge == null) {
                    gameKnowledge = GameKnowledge(isInitialized = true)
                }
            }

            if (pixelKnowledge == null) {
                pixelKnowledge = gameKnowledge.toPixelCoords(displayWidth, displayHeight)
            }

            val oldPK = pixelKnowledge ?: gameKnowledge.toPixelCoords(displayWidth, displayHeight)
            val gs = gameStateDetector?.detect(bitmap, displayWidth, displayHeight) ?: return
            pixelKnowledge = oldPK.copy(
                levelUpX = gs.levelUpButtonX, levelUpY = gs.levelUpButtonY,
                shopRecommendX = gs.shopRecommendX, shopRecommendY = gs.shopRecommendY,
                buyConfirmX = gs.buyConfirmX, buyConfirmY = gs.buyConfirmY,
                heroLevel = gs.heroLevel, hasLevelUp = gs.hasLevelUp,
                isShopOpen = gs.isShopOpen, isDead = gs.isDead,
                matchEnded = gs.matchEnded, inBattle = gs.inBattle
            )
            currentLevel = gs.heroLevel
            Log.d(TAG, "State: Lv${gs.heroLevel} dead=${gs.isDead} shop=${gs.isShopOpen} battle=${gs.inBattle} ended=${gs.matchEnded}" +
                    if (yoloUsed) " [YOLO]" else " [heuristic]")
        }

        runModelOnFrame(bitmap)
    }

    private fun runModelOnFrame(bitmap: Bitmap) {
        if (tfliteModel == null) return

        modelInferenceCounter++
        if (modelInferenceCounter % modelFrameInterval != 0) return

        val small = if (bitmap.width == 224 && bitmap.height == 224)
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else
            Bitmap.createScaledBitmap(bitmap, 224, 224, true)

        if (small != null) {
            modelFrameBuffer.add(small)
            if (modelFrameBuffer.size > modelSeqLen) modelFrameBuffer.removeAt(0)
        }

        if (modelFrameBuffer.size < modelSeqLen) return

        lastModelOutput = tfliteModel?.run(modelFrameBuffer.toList())
        if (lastModelOutput != null) {
            Log.d(TAG, "Model: ${lastModelOutput!!.actionType} " +
                    "(${"%.2f".format(lastModelOutput!!.primaryTouchX)},${"%.2f".format(lastModelOutput!!.primaryTouchY)})")
        }
    }

    private fun analyzeScreen(bitmap: Bitmap) {
        val gk = screenAnalyzer?.analyze(bitmap)
        if (gk != null && gk.isInitialized) {
            gameKnowledge = gk
            pixelKnowledge = gameKnowledge.toPixelCoords(displayWidth, displayHeight)
            Log.i(TAG, "Screen analyzed! Joystick:(${pixelKnowledge?.joystickX},${pixelKnowledge?.joystickY}) " +
                    "Skills:${pixelKnowledge?.skills?.size}")
        } else {
            Log.w(TAG, "Screen analysis failed, using fallback defaults")
            gameKnowledge = GameKnowledge(isInitialized = true)
            pixelKnowledge = gameKnowledge.toPixelCoords(displayWidth, displayHeight)
        }
    }

    private fun scheduleNextAction() {
        if (!isRunning || gamePhase != GamePhase.PLAYING) return
        updateApmMode()

        val delay = when (apmMode) {
            ApmMode.LAZY -> Random.nextLong(400, 1200)
            ApmMode.NORMAL -> Random.nextLong(150, 600)
            ApmMode.INTENSE -> Random.nextLong(60, 250)
        }

        inferenceHandler?.postDelayed({
            if (!isRunning) return@postDelayed
            executeGameAction()
            actionCount++
            scheduleNextAction()
        }, delay)
    }

    private fun updateApmMode() {
        val pk = pixelKnowledge
        if (pk != null && pk.isDead) { apmMode = ApmMode.LAZY; return }

        if (settings.isAuto()) {
            burstTimer++
            if (burstTimer > 30) {
                burstTimer = 0
                val r = Random.nextFloat()
                apmMode = when {
                    r < 0.15f -> ApmMode.LAZY
                    r < 0.65f -> ApmMode.NORMAL
                    else -> ApmMode.INTENSE
                }
                if (pk != null && pk.inBattle) apmMode = ApmMode.INTENSE
                currentApmMode = apmMode.name
            }
        } else {
            apmMode = when (settings.apmMode) {
                "LAZY" -> ApmMode.LAZY; "INTENSE" -> ApmMode.INTENSE; else -> ApmMode.NORMAL
            }
            currentApmMode = apmMode.name
        }

        if (actionCount > 0 && actionCount % 50 == 0) {
            val pauseExtra = Random.nextLong(1000, 3000)
            inferenceHandler?.postDelayed({
                if (isRunning) scheduleNextAction()
            }, pauseExtra)
        }
    }

    private fun executeGameAction() {
        val pk = pixelKnowledge
        if (pk == null) { Log.w(TAG, "executeGameAction: pixelKnowledge null, waiting for analysis"); return }
        if (displayWidth == 0 || displayHeight == 0) return

        if (pk.matchEnded) { Log.i(TAG, "Match ended, stopping AI")
            stopInference(); return }

        comboCooldown = (comboCooldown - 1).coerceAtLeast(0)
        buyCooldown = (buyCooldown - 1).coerceAtLeast(0)

        val level = pk.heroLevel.coerceAtLeast(1)
        if (level != lastLevel) {
            lastLevel = level; currentLevel = level
            currentCombos = comboProvider.getCombos(heroName, level, settings.apmMode)
        }

        if (settings.autoLevelUp && pk.hasLevelUp && pk.levelUpX > 0) {
            doLevelUp(pk); return
        }

        updateAIState(pk)
        stateTimer++

        currentPhase = aiState.name

        if (lastModelOutput != null) {
            val model = lastModelOutput!!
            when (model.actionType) {
                "DOWN" -> {
                    touchExecutor?.executeTouch(model.primaryTouchX, model.primaryTouchY, "DOWN", displayWidth, displayHeight)
                    Log.d(TAG, "Model: DOWN at ${"%.2f".format(model.primaryTouchX)},${"%.2f".format(model.primaryTouchY)}")
                }
                "MOVE" -> {
                    touchExecutor?.executeTouch(model.primaryTouchX, model.primaryTouchY, "MOVE", displayWidth, displayHeight)
                    Log.d(TAG, "Model: MOVE to ${"%.2f".format(model.primaryTouchX)},${"%.2f".format(model.primaryTouchY)}")
                }
                "UP" -> {
                    touchExecutor?.executeTouch(0f, 0f, "UP", displayWidth, displayHeight)
                    Log.d(TAG, "Model: UP")
                }
                "NONE" -> executeHeuristicAction(pk)
            }
            return
        }

        executeHeuristicAction(pk)
    }

    private fun executeHeuristicAction(pk: PixelKnowledge) {
        when (aiState) {
            AIState.DEAD -> { return }
            AIState.SHOPPING -> {
                if (settings.autoBuyItems && buyCooldown == 0 && pk.shopRecommendX > 0) doBuyItem(pk)
                return
            }
            AIState.RECALL -> { return }
            AIState.LANE_FARM -> executeLaneFarm(pk)
            AIState.TEAM_FIGHT -> executeTeamFight(pk)
            AIState.ROAMING -> executeRoaming(pk)
        }
    }

    private fun updateAIState(pk: PixelKnowledge) {
        val wasDead = aiState == AIState.DEAD

        if (pk.isDead) { aiState = AIState.DEAD; deadTimer++; isAlive = false; return }
        if (wasDead) { deadTimer = 0; buyCooldown = 10; isAlive = true
            aiState = AIState.LANE_FARM; stateTimer = 0 }

        if (settings.autoBuyItems && pk.isShopOpen && pk.shopRecommendX > 0) {
            aiState = AIState.SHOPPING; return
        }
        if (aiState == AIState.SHOPPING && !pk.isShopOpen) {
            aiState = AIState.LANE_FARM; stateTimer = 0
        }

        if (aiState == AIState.RECALL) {
            if (stateTimer > 30 || pk.isShopOpen) {
                if (pk.isShopOpen) { aiState = AIState.SHOPPING; return }
                aiState = AIState.LANE_FARM; stateTimer = 0
            }
            return
        }

        if (pk.inBattle) { aiState = AIState.TEAM_FIGHT; stateTimer = 0; return }
        if (aiState == AIState.TEAM_FIGHT && !pk.inBattle) {
            aiState = AIState.LANE_FARM; stateTimer = 0
        }

        if (stateTimer > 40 && Random.nextFloat() < 0.15f) {
            aiState = AIState.ROAMING; stateTimer = 0
        }
        if (aiState == AIState.ROAMING && stateTimer > 15) {
            aiState = AIState.LANE_FARM; stateTimer = 0
        }
    }

    private fun executeLaneFarm(pk: PixelKnowledge) {
        when (Random.nextInt(12)) {
            0, 1 -> doAttack(pk)
            2, 3 -> doSkill(pk, 0)
            4, 5 -> doSkill(pk, 1)
            6 -> doMoveJoystick(pk)
            7 -> doMinimapTap(pk)
            8 -> if (Random.nextFloat() < 0.3f) { aiState = AIState.RECALL; stateTimer = 0; doRecall(pk) }
            9 -> if (Random.nextFloat() < 0.5f && settings.useHeroCombos && currentCombos.isNotEmpty()) executeCombo(pk)
            else -> doMoveJoystick(pk)
        }
    }

    private fun executeTeamFight(pk: PixelKnowledge) {
        updateApmMode()
        when (apmMode) {
            ApmMode.LAZY -> executeNormalAction(pk)
            ApmMode.NORMAL -> executeIntenseAction(pk)
            ApmMode.INTENSE -> {
                executeIntenseAction(pk)
                if (Random.nextFloat() < 0.4f && settings.useHeroCombos && currentCombos.isNotEmpty()) {
                    scheduleDelayed(100, 300) { executeCombo(pk) }
                }
            }
        }
    }

    private fun executeRoaming(pk: PixelKnowledge) {
        when (Random.nextInt(8)) {
            0, 1 -> doMoveJoystick(pk)
            2 -> doMinimapTap(pk)
            3 -> doAttack(pk)
            4, 5 -> doSkill(pk, 0)
            6 -> doSkill(pk, 1)
            7 -> doMoveJoystick(pk)
        }
    }

    private fun executeCombo(pk: PixelKnowledge) {
        currentComboIndex = Random.nextInt(currentCombos.size)
        val combo = currentCombos[currentComboIndex]
        currentStepIndex = 0
        comboCooldown = 5 + Random.nextInt(10)
        executeComboStep(pk, combo)
    }

    private fun executeComboStep(pk: PixelKnowledge, combo: SkillCombo) {
        if (!isRunning || gamePhase != GamePhase.PLAYING) return
        if (currentStepIndex >= combo.steps.size) return

        val step = combo.steps[currentStepIndex]
        currentStepIndex++

        inferenceHandler?.postDelayed({
            if (!isRunning) return@postDelayed
            when (step.action) {
                "skill" -> doSkill(pk, step.skillIndex.coerceAtMost(pk.skills.size - 1))
                "skill_dir" -> doDirectionalSkill(pk, step.skillIndex.coerceAtMost(pk.skills.size - 1), step.directionDeg)
                "ultimate" -> doUltimate(pk)
                "attack" -> doAttack(pk)
                "move" -> {
                    val angle = if (step.directionDeg != null) step.directionDeg else Random.nextFloat() * 360f
                    touchExecutor?.executeJoystickMove(
                        pk.joystickX.toFloat(), pk.joystickY.toFloat(),
                        angle, 0.6f, displayWidth, displayHeight
                    )
                }
            }
            if (currentStepIndex < combo.steps.size) executeComboStep(pk, combo)
        }, step.delayBefore + Random.nextLong(0, step.delayAfter))
    }

    private fun doLevelUp(pk: PixelKnowledge) {
        val skillIndex = levelUpSkillPriority.getOrElse(
            (lastLevelUpAction).coerceAtMost(levelUpSkillPriority.size - 1)
        ) { lastLevelUpAction % 3 }
        lastLevelUpAction++

        when (skillIndex) {
            0 -> touchExecutor?.executeTap(pk.skills.getOrNull(0)?.x?.toFloat() ?: 0f,
                pk.skills.getOrNull(0)?.y?.toFloat() ?: 0f, displayWidth, displayHeight, "LEVEL_UP1")
            1 -> touchExecutor?.executeTap(pk.skills.getOrNull(1)?.x?.toFloat() ?: 0f,
                pk.skills.getOrNull(1)?.y?.toFloat() ?: 0f, displayWidth, displayHeight, "LEVEL_UP2")
            2 -> touchExecutor?.executeTap(pk.skills.getOrNull(2)?.x?.toFloat() ?: 0f,
                pk.skills.getOrNull(2)?.y?.toFloat() ?: 0f, displayWidth, displayHeight, "LEVEL_UP3")
        }

        touchExecutor?.executeTap(pk.levelUpX.toFloat(), pk.levelUpY.toFloat(),
            displayWidth, displayHeight, "LEVEL_UP_CONFIRM")

        Log.d(TAG, "Level up: skill$skillIndex (total ${lastLevelUpAction})")
    }

    private fun doBuyItem(pk: PixelKnowledge) {
        touchExecutor?.executeTap(pk.shopRecommendX.toFloat(), pk.shopRecommendY.toFloat(),
            displayWidth, displayHeight, "BUY_RECOMMEND")
        if (pk.buyConfirmX > 0) {
            inferenceHandler?.postDelayed({
                if (isRunning) touchExecutor?.executeTap(pk.buyConfirmX.toFloat(),
                    pk.buyConfirmY.toFloat(), displayWidth, displayHeight, "BUY_CONFIRM")
            }, Random.nextLong(100, 300))
        }
        buyCooldown = 20
        Log.d(TAG, "Auto buy item")
    }

    private fun executeLazyAction(pk: PixelKnowledge) {
        when (Random.nextInt(10)) {
            0 -> doRandomLook(pk)
            1 -> doRecall(pk)
            2 -> doMinimapTap(pk)
            else -> doMoveJoystick(pk)
        }
    }

    private fun executeNormalAction(pk: PixelKnowledge) {
        when (Random.nextInt(24)) {
            0 -> doRecall(pk)
            1 -> doRandomLook(pk)
            2, 3 -> doMinimapTap(pk)
            4, 5 -> doAttack(pk)
            6, 7, 8 -> doSkill(pk, 0)
            9, 10 -> doSkill(pk, 1)
            11, 12 -> doSkill(pk, 2)
            13 -> doUltimate(pk)
            14, 15 -> doMoveAndAttack(pk)
            16 -> doBattleSpell(pk)
            17 -> doItemUse(pk)
            else -> doMoveJoystick(pk)
        }
    }

    private fun executeIntenseAction(pk: PixelKnowledge) {
        val hasUlt = lastLevel >= 4
        when (Random.nextInt(if (hasUlt) 8 else 6)) {
            0 -> doAttack(pk)
            1 -> { doSkill(pk, 0)
                if (Random.nextFloat() < 0.5f) scheduleDelayed(100, 250) { doSkill(pk, 1) }
                if (hasUlt && Random.nextFloat() < 0.3f) scheduleDelayed(250, 400) { doUltimate(pk) }
            }
            2 -> { doMoveJoystick(pk)
                if (Random.nextFloat() < 0.4f) scheduleDelayed(50, 150) { doAttack(pk) }
            }
            3 -> if (hasUlt) doUltimate(pk) else doSkill(pk, 2)
            4 -> doSkill(pk, 1)
            5 -> { doSkill(pk, 2)
                if (Random.nextFloat() < 0.4f) scheduleDelayed(150, 300) { doSkill(pk, 0) }
            }
            6 -> doMoveAndAttack(pk)
            7 -> doBattleSpell(pk)
        }
    }

    private fun scheduleDelayed(min: Long, max: Long, action: () -> Unit) {
        inferenceHandler?.postDelayed({ if (isRunning) action() }, Random.nextLong(min, max))
    }

    private fun doMoveJoystick(pk: PixelKnowledge) {
        if (Random.nextFloat() < 0.12f) { touchExecutor?.executeJoystickRelease(); return }
        val change = if (Random.nextFloat() < 0.3f) Random.nextFloat() * 90f - 45f else 0f
        lastHeroAngle = (lastHeroAngle + change) % 360f
        val angle = if (Random.nextFloat() < 0.4f) Random.nextFloat() * 360f else lastHeroAngle
        lastHeroAngle = angle
        val intensity = when (apmMode) {
            ApmMode.LAZY -> 0.2f + Random.nextFloat() * 0.3f
            ApmMode.NORMAL -> 0.4f + Random.nextFloat() * 0.5f
            ApmMode.INTENSE -> 0.7f + Random.nextFloat() * 0.3f
        }
        touchExecutor?.executeJoystickMove(
            pk.joystickX.toFloat(), pk.joystickY.toFloat(),
            angle, intensity, displayWidth, displayHeight
        )
    }

    private fun doAttack(pk: PixelKnowledge) {
        touchExecutor?.executeTap(pk.attackX.toFloat(), pk.attackY.toFloat(), displayWidth, displayHeight, "ATTACK")
        if (apmMode == ApmMode.INTENSE && Random.nextFloat() < 0.3f) {
            scheduleDelayed(80, 200) {
                touchExecutor?.executeTap(pk.attackX.toFloat(), pk.attackY.toFloat(), displayWidth, displayHeight, "ATTACK")
            }
        }
    }

    private fun doSkill(pk: PixelKnowledge, skillIndex: Int) {
        val skill = pk.skills.getOrNull(skillIndex) ?: return
        val useDirectional = when (skillIndex) {
            0 -> Random.nextFloat() < 0.3f; 1 -> Random.nextFloat() < 0.4f
            2 -> Random.nextFloat() < 0.5f; else -> Random.nextFloat() < 0.3f
        }
        if (useDirectional) {
            val angle = getSkillDirection()
            val dist = (displayWidth * 0.08f).coerceAtLeast(50f)
            touchExecutor?.executeDirectionalSkill(skill.x.toFloat(), skill.y.toFloat(),
                angle, dist, displayWidth, displayHeight)
        } else {
            val actionType = when (skillIndex) { 0 -> "SKILL1"; 1 -> "SKILL2"; 2 -> "SKILL3"; else -> "SKILL1" }
            touchExecutor?.executeTap(skill.x.toFloat(), skill.y.toFloat(), displayWidth, displayHeight, actionType)
        }
    }

    private fun doDirectionalSkill(pk: PixelKnowledge, skillIndex: Int, fixedAngle: Float?) {
        val skill = pk.skills.getOrNull(skillIndex) ?: return
        val angle = fixedAngle ?: getSkillDirection()
        val dist = (displayWidth * 0.1f).coerceAtLeast(60f)
        touchExecutor?.executeDirectionalSkill(skill.x.toFloat(), skill.y.toFloat(),
            angle, dist, displayWidth, displayHeight)
    }

    private fun getSkillDirection(): Float {
        return when {
            settings.isSmartDirection() && lastHeroAngle != 0f ->
                lastHeroAngle + if (Random.nextFloat() < settings.getMissRate()) Random.nextFloat() * 60f - 30f else 0f
            settings.isSmartDirection() -> Random.nextFloat() * 360f
            else -> {
                if (Random.nextFloat() < settings.getMissRate()) {
                    val missAngle = Random.nextFloat() * 60f - 30f
                    val base = if (lastHeroAngle != 0f) lastHeroAngle else Random.nextFloat() * 360f
                    (base + missAngle) % 360f
                } else Random.nextFloat() * 360f
            }
        }
    }

    private fun doUltimate(pk: PixelKnowledge) {
        val ult = pk.skills.lastOrNull() ?: return
        val angle = getSkillDirection()
        val dist = (displayWidth * 0.12f).coerceAtLeast(70f)
        touchExecutor?.executeDirectionalSkill(ult.x.toFloat(), ult.y.toFloat(), angle, dist, displayWidth, displayHeight)
    }

    private fun doMoveAndAttack(pk: PixelKnowledge) {
        val angle = if (Random.nextFloat() < 0.7f) lastHeroAngle else Random.nextFloat() * 360f
        touchExecutor?.executeJoystickMove(pk.joystickX.toFloat(), pk.joystickY.toFloat(), angle, 0.7f, displayWidth, displayHeight)
        scheduleDelayed(150, 400) {
            touchExecutor?.executeTap(pk.attackX.toFloat(), pk.attackY.toFloat(), displayWidth, displayHeight, "ATTACK")
        }
    }

    private fun doRecall(pk: PixelKnowledge) {
        touchExecutor?.executeTap(pk.recallX.toFloat(), pk.recallY.toFloat(), displayWidth, displayHeight, "RECALL")
    }

    private fun doMinimapTap(pk: PixelKnowledge) {
        if (pk.minimapLeft >= pk.minimapRight || pk.minimapTop >= pk.minimapBottom) return
        val mx = Random.nextInt(pk.minimapLeft, pk.minimapRight + 1).toFloat()
        val my = Random.nextInt(pk.minimapTop, pk.minimapBottom + 1).toFloat()
        touchExecutor?.executeTap(mx, my, displayWidth, displayHeight, "MINIMAP")
    }

    private fun doItemUse(pk: PixelKnowledge) {
        touchExecutor?.executeTap(
            (displayWidth * 0.45f + Random.nextFloat() * 0.12f * displayWidth),
            (displayHeight * 0.88f), displayWidth, displayHeight, "ITEM"
        )
    }

    private fun doBattleSpell(pk: PixelKnowledge) {
        touchExecutor?.executeTap((displayWidth * 0.68f), (displayHeight * 0.86f), displayWidth, displayHeight, "BATTLE_SPELL")
    }

    private fun doRandomLook(pk: PixelKnowledge) {
        val rx = Random.nextInt(displayWidth / 3, displayWidth).toFloat()
        val ry = Random.nextInt(0, displayHeight / 2).toFloat()
        touchExecutor?.executeTap(rx, ry, displayWidth, displayHeight, "MINIMAP")
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val planes = image.planes; if (planes.isEmpty()) return null
        val buffer = planes[0].buffer; val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        buffer.rewind()

        if (rowPadding == 0) {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(image.width * image.height)
        val rowBytes = image.width * pixelStride
        val line = ByteArray(rowBytes)
        for (y in 0 until image.height) {
            buffer.position(y * rowStride)
            buffer.get(line)
            for (x in 0 until image.width) {
                val i = x * 4
                val a = line[i + 3].toInt() and 0xFF
                val r = line[i].toInt() and 0xFF
                val g = line[i + 1].toInt() and 0xFF
                val b = line[i + 2].toInt() and 0xFF
                pixels[y * image.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return bitmap
    }

    private fun stopInference() {
        if (!isRunning) return
        gamePhase = GamePhase.STOPPED
        tfliteModel?.close()
        touchExecutor?.reset()
        touchExecutor?.executeJoystickRelease()
        inferenceHandler?.removeCallbacksAndMessages(null)
        inferenceThread?.quitSafely()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        mediaProjection?.stop()
        isRunning = false
        GameOverlayService.isAiRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "AI stopped for $heroName")
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("MLBB AI - $heroName").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW).build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MLBB AI", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AI xulosa xizmati"; setShowBadge(false)
                }
            )
        }
    }

    override fun onDestroy() { if (isRunning) stopInference(); super.onDestroy() }
    private val TAG = "InferenceService"
}
