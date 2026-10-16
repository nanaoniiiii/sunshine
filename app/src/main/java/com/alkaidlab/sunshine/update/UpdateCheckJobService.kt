package com.alkaidlab.sunshine.update

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class UpdateCheckJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var check: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        AppUpdater.initialize(applicationContext)
        check = scope.launch { val success = AppUpdater.check(false); jobFinished(params, !success) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { check?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
