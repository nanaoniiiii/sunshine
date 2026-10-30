package com.alkaidlab.sunshine.update

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Same process as capture: a new sibling dataSync process can revoke capture on Android 13. */
internal class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    companion object {
        private const val ID = 2044
        private const val PAUSE = "com.alkaidlab.sunshine.PAUSE_UPDATE"
        fun start(context: Context) { context.startForegroundService(Intent(context, UpdateDownloadService::class.java)) }
    }

    private fun notification(message: String, progress: Int): android.app.Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, com.alkaidlab.sunshine.ui.MainActivity::class.java)
            .putExtra(AppUpdater.OPEN_UPDATE, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, ID, Intent(this, UpdateDownloadService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "sunshine_updates").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Sunshine 更新下载").setContentText(message).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .addAction(0, "暂停下载", pause).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppUpdater.initialize(this)
        startForeground(ID, notification(AppUpdater.state.value.message, AppUpdater.state.value.progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (observer == null) observer = scope.launch {
            var lastNotification = 0L
            AppUpdater.state.collect { state ->
                if (!state.downloading) { stopSelf(); return@collect }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastNotification >= 1000 || state.progress == 100) {
                    lastNotification = now
                    try { getSystemService(android.app.NotificationManager::class.java).notify(ID, notification(state.message, state.progress)) }
                    catch (_: SecurityException) { /* In-app progress remains usable without notification permission. */ }
                }
            }
        }
        if (intent?.action == PAUSE) {
            AppUpdater.cancelDownload()
            return START_NOT_STICKY
        }
        AppUpdater.runManagedDownload()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        if (AppUpdater.state.value.downloading) AppUpdater.suspendManagedDownload()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
