package com.mlbb.trainer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mlbb.trainer.MainActivity
import com.mlbb.trainer.RecordingService
import com.mlbb.trainer.database.AppDatabase
import com.mlbb.trainer.database.Hero
import com.mlbb.trainer.inference.InferenceService
import com.mlbb.trainer.TouchEventService
import kotlinx.coroutines.*

class GameOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.mlbb.trainer.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.mlbb.trainer.HIDE_OVERLAY"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "mlbb_overlay"

        var isOverlayShowing = false
            private set
        var isAiRunning = false
    }

    private var overlayView: FloatingOverlayView? = null
    private var heroListJob: Job? = null
    private var aiStatusJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentHeroId: Long = -1
    private var currentHeroName: String = ""
    private var currentModelPath: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val preselectId = intent.getLongExtra("preselect_hero_id", -1)
                val preselectName = intent.getStringExtra("preselect_hero_name") ?: ""
                if (preselectId > 0) {
                    currentHeroId = preselectId
                    currentHeroName = preselectName
                }
                showOverlay()
            }
            ACTION_HIDE -> hideOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (isOverlayShowing) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Qoplama ruxsati talab qilinadi!", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        }

        val notification = createNotification("Qoplama faol")
        startForeground(NOTIFICATION_ID, notification)

        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        overlayView = FloatingOverlayView(this, wm, object : FloatingOverlayView.OverlayCallback {
            override fun onStartAI(heroId: Long, heroName: String) {
                currentHeroId = heroId
                currentHeroName = heroName
                scope.launch {
                    val hero = AppDatabase.getDatabase(this@GameOverlayService)
                        .heroDao().getHeroById(heroId)
                    currentModelPath = hero?.modelPath ?: ""
                    withContext(Dispatchers.Main) {
                        if (hero?.modelStatus != "ready") {
                            Toast.makeText(this@GameOverlayService,
                                "$heroName uchun o'qitilgan model yo'q! Avval .tflite import qiling.",
                                Toast.LENGTH_LONG).show()
                        } else {
                            startAiMode()
                        }
                    }
                }
            }
            override fun onStopAI() {
                stopAiMode()
            }
            override fun onExit() {
                stopAiMode()
                hideOverlay()
                stopSelf()
            }
            override fun onHeroSelected(heroId: Long, heroName: String) {
                currentHeroId = heroId
                currentHeroName = heroName
            }
        })

        overlayView?.show()
        isOverlayShowing = true

        if (currentHeroId > 0) {
            overlayView?.preselectHero(currentHeroId)
        }

        startHeroListPolling()
        startAiStatusPolling()
    }

    private fun hideOverlay() {
        overlayView?.hide()
        overlayView = null
        isOverlayShowing = false
        heroListJob?.cancel()
        aiStatusJob?.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {}
    }

    private fun startAiStatusPolling() {
        aiStatusJob = scope.launch {
            while (isActive) {
                withContext(Dispatchers.Main) {
                    if (isAiRunning) {
                        overlayView?.updateStatus(
                            InferenceService.currentApmMode,
                            "Lv${InferenceService.currentLevel}",
                            InferenceService.currentPhase
                        )
                    } else {
                        overlayView?.updateStatus("--", "--", "---")
                    }
                }
                delay(500)
            }
        }
    }

    private fun startHeroListPolling() {
        heroListJob = scope.launch {
            while (isActive) {
                try {
                    val heroes = AppDatabase.getDatabase(this@GameOverlayService)
                        .heroDao().getAllHeroesList()
                    withContext(Dispatchers.Main) {
                        overlayView?.updateHeroList(heroes)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(3000)
            }
        }
    }

    private fun startAiMode() {
        if (isAiRunning) return
        if (currentHeroId < 0 || currentModelPath.isEmpty()) {
            Toast.makeText(this, "Qahramon/model tanlanmagan!", Toast.LENGTH_SHORT).show()
            return
        }

        if (TouchEventService.instance == null) {
            Toast.makeText(this, "Maxsus imkoniyatlar xizmati yoqilmagan! Sozlamalar > Maxsus imkoniyatlar > MLBB AI Trener", Toast.LENGTH_LONG).show()
            return
        }

        // Recording faol bo'lsa — to'xtatamiz (bitta MediaProjection token faqat bitta VirtualDisplay)
        if (RecordingService.isRecording) {
            val stopIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startService(stopIntent)
            RecordingService.lastProjectionResultCode = -1
            RecordingService.lastProjectionData = null
        }

        // Hardoim yangi MediaProjection token so'raymiz — eski token ishlamaydi
        val reqIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_PROJECTION
            putExtra(MainActivity.EXTRA_HERO_ID, currentHeroId)
            putExtra(MainActivity.EXTRA_HERO_NAME, currentHeroName)
            putExtra(MainActivity.EXTRA_MODEL_PATH, currentModelPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(reqIntent)
        Toast.makeText(this, "Ekranni yozib olish ruxsatini bering", Toast.LENGTH_LONG).show()
    }

    private fun stopAiMode() {
        if (!isAiRunning) return
        val intent = Intent(this, InferenceService::class.java).apply {
            action = InferenceService.ACTION_STOP
        }
        startService(intent)
        isAiRunning = false
        overlayView?.setRecordingStatus(false)
        Toast.makeText(this, "AI to'xtatildi", Toast.LENGTH_SHORT).show()
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MLBB AI Qoplamasi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MLBB Qoplamasi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Qoplama xizmati bildirishnomasi"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }
}
