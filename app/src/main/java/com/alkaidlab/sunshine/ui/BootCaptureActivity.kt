package com.alkaidlab.sunshine.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.alkaidlab.sunshine.service.BootCaptureBootstrap
import com.alkaidlab.sunshine.service.ServiceStartupSettings
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.alkaidlab.sunshine.service.SunshineService

/** Does not show the app UI or request new user consent during boot. */
class BootCaptureActivity : Activity() {
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { closeTask() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setLayout(1, 1)
        timeoutHandler.postDelayed(timeout, 10000)
        if (!ServiceStartupSettings.read(this).instantConnect || !BootCaptureBootstrap.authorized(this) ||
            getSystemService(KeyguardManager::class.java).isDeviceLocked ||
            SunshineRuntimeManager.getInstance().getMediaProjection() != null) {
            closeTask()
            return
        }
        if (savedInstanceState != null) return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
        } catch (error: RuntimeException) {
            SunshineLogManager.log("BOOT_CAPTURE", "Capture grant unavailable: ${error.javaClass.simpleName}")
            closeTask()
        }
    }

    @Deprecated("Framework activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null &&
            ServiceStartupSettings.read(this).instantConnect && BootCaptureBootstrap.authorized(this)) {
            SunshineService.start(this, data)
        } else SunshineLogManager.log("BOOT_CAPTURE", "No capture grant returned; service stays available")
        closeTask()
    }

    private fun closeTask() { if (isTaskRoot) finishAndRemoveTask() else finish() }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeout)
        super.onDestroy()
    }
}
