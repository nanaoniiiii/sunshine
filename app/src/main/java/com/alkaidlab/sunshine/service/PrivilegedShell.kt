package com.alkaidlab.sunshine.service

import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PrivilegedShell {
    private val readers = Executors.newCachedThreadPool { task -> Thread(task, "Sunshine-Shell-Read").apply { isDaemon = true } }
    fun available(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }
    fun run(arguments: Array<String>, timeoutMs: Long = 1800, acceptedExitCodes: Set<Int> = setOf(0)): String? {
        if (!available()) return null
        var process: Process? = null
        var stage = "lookup"
        return try {
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            stage = "invoke"
            val p = method.invoke(null, arguments, null, null) as ShizukuRemoteProcess
            process = p
            stage = "read"
            val reader = readers.submit<String> { p.inputStream.bufferedReader().use { it.readText() } }
            stage = "wait"
            if (!p.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroy(); reader.cancel(true); null }
            else if (p.exitValue() !in acceptedExitCodes) { reader.cancel(true); null } else reader.get(250, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            SunshineLogManager.log("APP_SHELL_ERR", "Privileged $stage failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally { process?.destroy() }
    }
}
