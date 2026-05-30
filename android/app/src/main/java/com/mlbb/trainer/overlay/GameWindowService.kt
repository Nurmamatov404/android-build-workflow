package com.mlbb.trainer.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.mlbb.trainer.MainActivity
import com.mlbb.trainer.RecordingService
import com.mlbb.trainer.TouchEventService
import com.mlbb.trainer.database.AppDatabase
import com.mlbb.trainer.inference.InferenceService

class GameWindowService : Service() {

    companion object {
        const val ACTION_SHOW = "com.mlbb.trainer.SHOW_GAME_WINDOW"
        const val ACTION_HIDE = "com.mlbb.trainer.HIDE_GAME_WINDOW"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "mlbb_game_window"
        private const val TAG = "GameWindow"

        var isShowing = false
            private set

        private val MLBB_PACKAGES = listOf(
            "com.mobile.legends",
            "com.moonton.mlbb",
            "com.mobilelegends.mlbb"
        )
    }

    private var windowManager: WindowManager? = null
    private var controlView: LinearLayout? = null

    private var launchButton: Button? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var statusText: TextView? = null
    private var apmText: TextView? = null
    private var levelText: TextView? = null
    private var phaseText: TextView? = null

    private var displayWidth = 0
    private var displayHeight = 0
    private var controlWidth = 0
    private val handler = Handler(Looper.getMainLooper())
    private var statusPollRun = false

    private var isAiRunning = false
    private var isAiStartedFlag = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        controlWidth = (displayWidth * 0.08f).toInt().coerceIn(70, 120)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIFICATION_ID, createNotification("O'yin oynasi faol"))
                showWindow()
            }
            ACTION_HIDE -> hideWindow()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideWindow()
        super.onDestroy()
    }

    private fun showWindow() {
        if (isShowing) return
        isShowing = true

        controlView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD1A1A2E.toInt())
            setPadding(6, 16, 6, 16)
        }

        buildControlPanel()

        val params = WindowManager.LayoutParams(
            controlWidth, displayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try {
            windowManager?.addView(controlView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay qo'shib bo'lmadi", e)
            isShowing = false
            return
        }

        startStatusPolling()
    }

    private fun buildControlPanel() {
        val panel = controlView ?: return

        panel.addView(TextView(this).apply {
            text = "\uD83E\uDD16"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })
        panel.addView(TextView(this).apply {
            text = "MLBB"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD700.toInt())
            setPadding(0, 0, 0, 4)
        })

        launchButton = Button(this).apply {
            text = "\u25B6 MLBB"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1565C0.toInt())
            setPadding(4, 6, 4, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            setOnClickListener { launchMLBB() }
        }
        panel.addView(launchButton)

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        startButton = Button(this).apply {
            text = "\u25B6 AI"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2E7D32.toInt())
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            setOnClickListener { startAI() }
        }
        panel.addView(startButton)

        stopButton = Button(this).apply {
            text = "\u25A0 Stop"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFC62828.toInt())
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            setOnClickListener { stopAI() }
            isEnabled = false
        }
        panel.addView(stopButton)

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        statusText = TextView(this).apply {
            text = "Holat: Bo'sh"
            textSize = 9f
            setTextColor(0xFF88FF88.toInt())
            gravity = Gravity.CENTER
        }
        panel.addView(statusText)

        apmText = TextView(this).apply {
            text = "APM: --"
            textSize = 8f
            setTextColor(0xFF88CCFF.toInt())
            gravity = Gravity.CENTER
        }
        panel.addView(apmText)

        levelText = TextView(this).apply {
            text = "Lv: --"
            textSize = 8f
            setTextColor(0xFF88FF88.toInt())
            gravity = Gravity.CENTER
        }
        panel.addView(levelText)

        phaseText = TextView(this).apply {
            text = "Faza: ---"
            textSize = 8f
            setTextColor(0xFFFFCC88.toInt())
            gravity = Gravity.CENTER
        }
        panel.addView(phaseText)

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        panel.addView(Button(this).apply {
            text = "\u2715"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF546E7A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { hideWindow(); stopSelf() }
        })
    }

    private fun launchMLBB() {
        for (pkg in MLBB_PACKAGES) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(intent)
                    statusText?.text = "MLBB ishga tushdi"
                    return
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "MLBB o'rnatilmagan!", Toast.LENGTH_LONG).show()
    }

    private fun startAI() {
        if (isAiRunning || isAiStartedFlag) return
        if (TouchEventService.instance == null) {
            Toast.makeText(this, "Maxsus imkoniyatlar xizmati yoqilmagan!", Toast.LENGTH_LONG).show()
            return
        }

        isAiStartedFlag = true
        statusText?.text = "AI boshlanmoqda..."

        val heroInfo = runBlocking(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@GameWindowService)
                val heroes = db.heroDao().getAllHeroesList()
                val chosen = heroes.firstOrNull()
                Triple(chosen?.id ?: -1L, chosen?.name ?: "", chosen?.modelPath ?: "")
            } catch (_: Exception) {
                Triple(-1L, "", "")
            }
        }

        val (heroId, heroName, modelPath) = heroInfo
        RecordingService.lastProjectionResultCode = -1
        RecordingService.lastProjectionData = null
        val reqIntent = Intent(this@GameWindowService, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_PROJECTION
            putExtra(MainActivity.EXTRA_HERO_ID, heroId)
            putExtra(MainActivity.EXTRA_HERO_NAME, heroName)
            putExtra(MainActivity.EXTRA_MODEL_PATH, modelPath)
            putExtra(MainActivity.EXTRA_START_AI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(reqIntent)
        Toast.makeText(this@GameWindowService,
            "Ekranni yozib olish ruxsatini bering", Toast.LENGTH_LONG).show()
    }

    private fun stopAI() {
        if (!isAiRunning) return
        isAiStartedFlag = false
        val intent = Intent(this, InferenceService::class.java).apply {
            action = InferenceService.ACTION_STOP
        }
        startService(intent)
        isAiRunning = false
        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        statusText?.text = "AI to'xtatildi"
        InferenceService.displayFrameCallback = null
    }

    private fun startStatusPolling() {
        if (statusPollRun) return
        statusPollRun = true
        handler.post(object : Runnable {
            override fun run() {
                if (!isShowing) { statusPollRun = false; return }
                if (InferenceService.isRunning) {
                    if (!isAiRunning) {
                        isAiRunning = true
                        startButton?.isEnabled = false
                        stopButton?.isEnabled = true
                        statusText?.text = "AI ishlamoqda"
                    }
                    apmText?.text = "APM: ${InferenceService.currentApmMode}"
                    levelText?.text = "Lv: ${InferenceService.currentLevel}"
                    phaseText?.text = "Faza: ${InferenceService.currentPhase}"
                } else if (isAiRunning) {
                    isAiRunning = false
                    isAiStartedFlag = false
                    startButton?.isEnabled = true
                    stopButton?.isEnabled = false
                    statusText?.text = "AI to'xtadi"
                    InferenceService.displayFrameCallback = null
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun hideWindow() {
        if (!isShowing) return
        isShowing = false
        statusPollRun = false
        InferenceService.displayFrameCallback = null
        if (isAiRunning) stopAI()
        isAiStartedFlag = false
        try { controlView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        controlView = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("MLBB Oyin oynasi")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "MLBB Oyin oynasi",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "O'yin oynasi xizmati"
                        setShowBadge(false)
                    }
                )
        }
    }
}
