package com.mlbb.trainer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.mlbb.trainer.START_RECORDING"
        const val ACTION_STOP = "com.mlbb.trainer.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "mlbb_recording"

        var lastProjectionResultCode: Int = -1
        var lastProjectionData: Intent? = null

        var isRecording = false
            private set

        var currentSessionDir: File? = null
            private set

        var frameCount = 0
            private set

        var touchCount = 0
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var screenCapture: ScreenCaptureEngine? = null
    private var touchRecorder: TouchRecorder? = null
    private var sessionDir: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                if (resultCode != -1 && data != null) {
                    startRecording(resultCode, data)
                }
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        val notification = createNotification("Yozib olish boshlanmoqda...", false)
        startForeground(NOTIFICATION_ID, notification)

        sessionDir = createSessionDir()
        currentSessionDir = sessionDir

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val densityDpi = metrics.densityDpi

        touchRecorder = TouchRecorder(this).apply {
            sessionDir?.let { start(it) }
        }

        TouchEventService.instance?.setRecorder(touchRecorder)

        screenCapture = ScreenCaptureEngine(
            mediaProjection!!, metrics, densityDpi
        ).apply {
            sessionDir?.let { start(it, fps = 10) }
        }

        isRecording = true
        updateNotification("Yozilmoqda... (0 kadr)")
    }

    private fun stopRecording() {
        if (!isRecording) return

        screenCapture?.stop()
        touchRecorder?.stop()

        mediaProjection?.stop()

        TouchEventService.instance?.setRecorder(null)

        frameCount = screenCapture?.getFrameCount() ?: 0
        touchCount = touchRecorder?.getEventCount() ?: 0

        isRecording = false
        currentSessionDir = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        sendBroadcast(Intent("com.mlbb.trainer.RECORDING_STOPPED").apply {
            putExtra("frames", frameCount)
            putExtra("touches", touchCount)
            putExtra("session_path", sessionDir?.absolutePath ?: "")
        })
    }

    private fun createSessionDir(): File {
        val baseDir = File(
            getExternalFilesDir(null), "MLBB_Trainer"
        )
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(baseDir, "session_$timestamp")
        dir.mkdirs()
        File(dir, "frames").mkdirs()
        return dir
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text, true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(text: String, showStop: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MLBB Trener")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MLBB Yozib Olish",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Yozib olish seansi bildirishnomasi"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (isRecording) {
            stopRecording()
        }
        super.onDestroy()
    }
}
