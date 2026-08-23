package com.sounddelay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * MediaProjection 기반 오디오 캡처 + 딜레이 재생을 담당하는 포그라운드 서비스.
 * 화면을 꺼도, 다른 앱을 쓰는 동안에도 계속 동작한다.
 */
class AudioDelayService : Service() {

    companion object {
        const val ACTION_START = "com.sounddelay.app.action.START"
        const val ACTION_STOP = "com.sounddelay.app.action.STOP"
        const val ACTION_SET_DELAY = "com.sounddelay.app.action.SET_DELAY"
        const val ACTION_STATE_CHANGED = "com.sounddelay.app.action.STATE_CHANGED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_OUTPUT_USAGE = "output_usage"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "delay_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var engine: DelayEngine? = null

    // 시작할 때 자동으로 음소거한 미디어 볼륨. 정지할 때 복원한다. -1이면 저장된 값 없음.
    private var savedMediaVolume = -1

    private fun muteMediaVolume() {
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            savedMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    private fun restoreMediaVolume() {
        if (savedMediaVolume < 0) return
        val am = getSystemService(AudioManager::class.java)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0) }
        savedMediaVolume = -1
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopEverything(null) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_SET_DELAY ->
                engine?.setDelayMs(intent.getLongExtra(EXTRA_DELAY_MS, 0L))
            ACTION_STOP -> stopEverything(null)
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // startForegroundService()로 시작되었으므로 어떤 경로든 먼저 startForeground를 호출한다.
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (isRunning) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopEverything(getString(R.string.error_projection_denied))
            return
        }

        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 3_000L)
        val outputUsage =
            intent.getIntExtra(EXTRA_OUTPUT_USAGE, AudioAttributes.USAGE_ALARM)

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val proj = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException(getString(R.string.error_projection_denied))
            proj.registerCallback(projectionCallback, mainHandler)
            projection = proj

            engine = DelayEngine(this, proj, outputUsage, delayMs) {
                mainHandler.post { stopEverything(getString(R.string.error_capture_stopped)) }
            }.also { it.start() }

            // 원본 소리 소거. 미디어 볼륨 채널로 출력할 때는 지연음까지 꺼지므로 건너뛴다.
            if (outputUsage != AudioAttributes.USAGE_MEDIA) muteMediaVolume()

            isRunning = true
            broadcastState(null)
        } catch (e: Exception) {
            stopEverything(e.message ?: getString(R.string.error_start_failed))
        }
    }

    private fun stopEverything(error: String?) {
        restoreMediaVolume()
        engine?.stop()
        engine = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null

        val wasRunning = isRunning
        isRunning = false
        if (wasRunning || error != null) broadcastState(error)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastState(error: String?) {
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_ERROR, error)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioDelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_delay)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        restoreMediaVolume()
        engine?.stop()
        engine = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        isRunning = false
        super.onDestroy()
    }
}
