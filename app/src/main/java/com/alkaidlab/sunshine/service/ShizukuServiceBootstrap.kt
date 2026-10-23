package com.alkaidlab.sunshine.service

import android.content.Context
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * UserService sends its Binder through the manager's provider. Some OEMs block
 * that provider even while the shell server and the app permission remain live.
 * Prepare through the existing server, before depending on any UserService.
 */
object ShizukuServiceBootstrap {
    private val mutex = Mutex()
    @Volatile private var attemptedBinder: IBinder? = null
    @Volatile var managerPackage: String? = null
        private set

    fun invalidate() { attemptedBinder = null }

    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!PrivilegedShell.available()) return@withLock
            val binder = Shizuku.getBinder() ?: return@withLock
            if (attemptedBinder === binder) return@withLock
            try {
                val installed = ManagerBootstrapPolicy.managers.mapNotNull { name ->
                    runCatching { context.packageManager.getApplicationInfo(name, 0) }.getOrNull()?.let {
                        name to (listOf(it.sourceDir) + it.splitSourceDirs.orEmpty())
                    }
                }.toMap()
                val classPath = PrivilegedShell.run(arrayOf("printenv", "CLASSPATH")).orEmpty()
                val manager = ManagerBootstrapPolicy.activeManager(classPath, installed)
                managerPackage = manager
                if (manager == null) {
                    SunshineLogManager.log("SHIZUKU_PREPARE", "当前管理器未能精确识别，保留正常服务绑定")
                    return@withLock
                }
                val changed = mutableListOf<String>()
                // Official Shizuku/compatible manager callback providers run in user 0.
                for (operation in listOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")) {
                    val before = PrivilegedShell.run(arrayOf("cmd", "appops", "get", "--user", "0", manager, operation))
                    if (before != null && ManagerBootstrapPolicy.restricted(before, operation)) {
                        if (PrivilegedShell.run(arrayOf("cmd", "appops", "set", "--user", "0", manager, operation, "allow")) != null)
                            changed += operation
                    }
                }
                var whitelist = PrivilegedShell.run(arrayOf("cmd", "deviceidle", "whitelist"))
                for (name in listOf(manager, context.packageName).distinct()) {
                    if (whitelist != null && !ManagerBootstrapPolicy.whitelisted(whitelist, name)) {
                        PrivilegedShell.run(arrayOf("cmd", "deviceidle", "whitelist", "+$name"))
                        whitelist = PrivilegedShell.run(arrayOf("cmd", "deviceidle", "whitelist"))
                    }
                }
                val exempt = whitelist?.let { ManagerBootstrapPolicy.whitelisted(it, manager) } ?: false
                SunshineLogManager.log("SHIZUKU_PREPARE", "管理器 $manager：后台准备完成，电池豁免=$exempt，解除后台限制=${changed.joinToString().ifEmpty { "无需修改" }}")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                SunshineLogManager.log("SHIZUKU_PREPARE", "后台准备未完成：${error.javaClass.simpleName}；继续尝试服务绑定")
            } finally {
                // A failed preparation must not create an immediate handler retry loop.
                // A real bind timeout invalidates this attempt for the next retry.
                if (Shizuku.getBinder() === binder) attemptedBinder = binder
            }
        }
    }
}
