package com.alkaidlab.sunshine.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return
        AppUpdater.initialize(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { AppUpdater.refreshDownload(id) } finally { pending.finish() } }
    }
}
