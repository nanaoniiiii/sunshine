package com.alkaidlab.sunshine.service

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.*
import android.view.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Shell-owned display adapter, launched by Sunshine's existing Shizuku authorization. */
class VirtualDisplayUserService(context: Context) : ISunshineDisplayController.Stub() {
    // Shizuku 12.x instantiates user services with Class.newInstance(), while
    // newer starters can supply Context. Both paths use the same shell adapter.
    constructor() : this(legacySystemContext())

    companion object {
        private fun legacySystemContext(): Context {
            val type = Class.forName("android.app.ActivityThread")
            val activityThread = type.getMethod("currentActivityThread").invoke(null)
                ?: type.getMethod("systemMain").invoke(null)
            return type.getMethod("getSystemContext").invoke(activityThread) as Context
        }
    }

    private val shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val thread = HandlerThread("Sunshine-Virtual-Display").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    private var nativeDexMirror: NativeDexMirror? = null
    private var localDex: SamsungLocalDex? = null
    private var standaloneDex: SamsungStandaloneDex? = null
    private var dexRouteDetail = ""
    private var controls: DesktopControlOverlay? = null
    private var owner: IBinder? = null
    private val died = IBinder.DeathRecipient { destroy() }
    private var panelOff = false
    private var wake: PowerManager.WakeLock? = null
    private var virtualWake: PowerManager.WakeLock? = null
    private var desktopBefore: String? = null
    private var freeformBefore: String? = null
    private var settingsChanged = false
    private var launcher = ""
    private var mode = ""
    private var input: Any? = null
    private var inject: java.lang.reflect.Method? = null
    private var requestedRefreshRate = 60
    private var refreshRateRequestSupported = false
    private var dexRequested = false
    private var dexActive = false
    private val details = mutableListOf<String>()

    @Synchronized override fun attachOwner(owner: IBinder) {
        if (this.owner == owner) return
        owner.linkToDeath(died, 0)
        try { this.owner?.unlinkToDeath(died, 0) } catch (_: Exception) {}
        this.owner = owner
    }

    @Synchronized override fun createDisplay(width: Int, height: Int, density: Int, surface: Surface, mode: String, owner: IBinder,
        fps: Int, samsungDex: Boolean, highRefresh: Boolean): Int {
        require(width in 240..8192 && height in 240..8192 && density in 120..640)
        require(fps in 15..240)
        require(mode == "desktop")
        check(surface.isValid)
        val nativeSamsung = samsungDex && Build.MANUFACTURER.equals("samsung", true)
        check((nativeSamsung && NativeDexMirror.activeDisplay(shell) != null) ||
            !(shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "请先解锁主机，再创建虚拟屏" }
        // A prepared local receiver must survive the switch from primary capture to its DeX mirror.
        val preparedLocal = localDex
        val preparedStandalone = standaloneDex
        localDex = null
        standaloneDex = null
        val preparedDetail = dexRouteDetail
        try { closeDisplay() } finally { localDex = preparedLocal; standaloneDex = preparedStandalone; dexRouteDetail = preparedDetail }
        try {
            this.mode = mode
            details.clear()
            if (samsungDex && dexRouteDetail.isNotBlank()) details.add(dexRouteDetail)
            dexRequested = samsungDex
            dexActive = false
            requestedRefreshRate = if (highRefresh) fps else 60
            if (nativeSamsung) {
                var nativeDisplay = NativeDexMirror.activeDisplay(shell)
                if (nativeDisplay == null && NativeDexMirror.hasExternalConnection(shell)) {
                    try {
                        nativeDexRequest(1)
                        for (attempt in 0 until 30) {
                            nativeDisplay = NativeDexMirror.activeDisplay(shell)
                            if (nativeDisplay != null) break
                            Thread.sleep(100)
                        }
                        if (nativeDisplay == null) { nativeDexRequest(2); details.add("系统尚未进入 DeX，继续尝试第二屏桌面") }
                    } catch (e: Exception) { details.add("系统 DeX 进入请求未完成：${(e.cause ?: e).message}") }
                }
                if (nativeDisplay != null) {
                    try {
                        nativeDexMirror = NativeDexMirror.create(nativeDisplay, width, height, surface)
                        attachOwner(owner)
                        if (nativeDisplay.displayId == Display.DEFAULT_DISPLAY)
                            wake = (shell.getSystemService(Context.POWER_SERVICE) as PowerManager)
                                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Sunshine:TabletDexAwake").apply { acquire() }
                        dexActive = true
                        launcher = if (nativeDisplay.displayId == Display.DEFAULT_DISPLAY) "三星原生 DeX（平板主屏）" else "三星原生 DeX"
                        refreshRateRequestSupported = false
                        details.add(if (standaloneDex != null) "使用平板主屏原生 DeX，并非独立第二屏；退出恢复本次切换"
                            else if (nativeDisplay.displayId == Display.DEFAULT_DISPLAY) "串流已开启的平板主屏 DeX；退出保留原有模式"
                            else if (localDex != null) "正在串流无 HDMI 的本机原生 DeX；退出将关闭本次本机接收端"
                            else "正在串流系统 DeX 第二屏；退出串流不会关闭原有 DeX 会话")
                        return nativeDisplay.displayId
                    } catch (e: Exception) {
                        try { nativeDexMirror?.close() } catch (_: Exception) {}
                        nativeDexMirror = null
                        details.add("系统 DeX 采集未连接：${(e.cause ?: e).message}")
                    }
                }
            }
            if (mode == "desktop") {
                desktopBefore = setting("force_desktop_mode_on_external_displays")
                freeformBefore = setting("enable_freeform_support")
                settingsChanged = true
                putSetting("force_desktop_mode_on_external_displays", "1")
                putSetting("enable_freeform_support", "1")
            }
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            // System-defined flags are reflected, so older releases omit unsupported fields.
            for (name in listOf("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", "VIRTUAL_DISPLAY_FLAG_TRUSTED", "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")) {
                flags = flags or flag(name)
            }
            if (mode == "desktop") flags = flags or flag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS")
            display = createSurfaceDisplay(width, height, density, surface, flags)
                ?: error("系统未创建虚拟屏")
            attachOwner(owner)
            wake = (shell.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Sunshine:VirtualScreen").apply { acquire() }
            virtualWake = (shell.createDisplayContext(display!!.display).getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Sunshine:VirtualDisplayAwake").apply { acquire() }
            val id = display!!.display.displayId
            check(startDesktop(id)) { "无法启动虚拟屏桌面" }
            if (requestedRefreshRate > 60 && !refreshRateRequestSupported)
                details.add("此系统未提供虚拟屏刷新率请求接口，保留系统显示模式")
            return id
        } catch (e: Throwable) {
            closeDisplay()
            throw IllegalStateException((e.cause ?: e).message ?: "虚拟屏创建失败", e)
        }
    }

    private fun flag(name: String): Int = try { DisplayManager::class.java.getDeclaredField(name).getInt(null) } catch (_: Exception) { 0 }

    private fun createSurfaceDisplay(width: Int, height: Int, density: Int, surface: Surface, flags: Int): VirtualDisplay? {
        val manager = shell.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        refreshRateRequestSupported = false
        // Android 14+ exposes a requested refresh rate. Older adapters may only
        // advertise a fixed 60 Hz mode; changing encoder FPS cannot change that.
        try {
            val configType = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val builderType = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = builderType.getConstructor(String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance("Sunshine Virtual Screen", width, height, density)
            builderType.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
            builderType.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            builderType.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType).invoke(builder, requestedRefreshRate.toFloat())
            val config = builderType.getMethod("build").invoke(builder)
            val result = manager.javaClass.getMethod("createVirtualDisplay", configType)
                .invoke(manager, config) as? VirtualDisplay
            if (result != null) { refreshRateRequestSupported = true; return result }
        } catch (_: NoSuchMethodException) {
            // Preserve the normal desktop on Android versions without this API.
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) { details.add("高刷新率请求未应用：${(e.cause ?: e).message}") }
        return manager.createVirtualDisplay("Sunshine Virtual Screen", width, height, density, surface, flags, null, handler)
    }

    @Synchronized override fun blankMainScreen(owner: IBinder): Boolean {
        check(!(shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "请先解锁主机" }
        closeDisplay()
        try {
            mode = "screen_off"
            launcher = "原主屏画面"
            attachOwner(owner)
            wake = (shell.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Sunshine:MainScreenStreaming").apply { acquire() }
            panelOff = true
            setPanelPower(0)
            return true
        } catch (e: Exception) {
            closeDisplay()
            throw IllegalStateException((e.cause ?: e).message, e)
        }
    }

    @Synchronized override fun setMainPanelOff(off: Boolean): Boolean {
        check(mode == "desktop" && (display != null || nativeDexMirror != null)) { "请先创建独立第二屏" }
        check(owner?.isBinderAlive == true) { "串流宿主已退出" }
        if (off) check(!(shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "请先解锁主机" }
        val primaryDex = nativeDexMirror?.takeIf { it.source.displayId == Display.DEFAULT_DISPLAY }
        if (off && primaryDex != null && !(shell.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            val power = shell.getSystemService(Context.POWER_SERVICE) as PowerManager
            try {
                PowerManager::class.java.getMethod("wakeUp", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                    .invoke(power, SystemClock.uptimeMillis(), 2, "Sunshine:DesktopStream")
            } catch (_: Exception) { command("input", "keyevent", "KEYCODE_WAKEUP") }
        }
        // Preserve the virtual display, owner watch and wake resources. Putting
        // display 0 to sleep would let OEM policies demote active stream workers.
        setPanelPower(if (off) 0 else 2)
        panelOff = off
        if (off && primaryDex != null) handler.postDelayed({
            synchronized(this) {
                if (panelOff && nativeDexMirror === primaryDex && owner?.isBinderAlive == true)
                    try { setPanelPower(0) } catch (e: Exception) { android.util.Log.e("SunshineDesktopControls", "Cannot reapply panel blanking", e) }
            }
        }, 250)
        return true
    }

    // A Shizuku UserService has no registered Android application process. The
    // platform shell commands use the supported external-provider/activity path.
    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        try {
            check(process.waitFor(3, TimeUnit.SECONDS)) { "系统操作超时" }
            val output = process.inputStream.bufferedReader().readText().take(8192)
            check(process.exitValue() == 0 && !output.contains("SecurityException")) { output }
            return output.trim()
        } finally { process.destroy() }
    }
    private fun setting(key: String): String? = command("settings", "get", "global", key).takeUnless { it == "null" }
    private fun nativeDexRequest(state: Int) {
        command("am", "broadcast", "--user", "0", "-a", "com.samsung.android.desktopmode.action.DESKTOP_MODE_UPDATE_REQUEST",
            "--ei", "com.samsung.android.desktopmode.extra.DESKTOP_MODE_STATE", state.toString(),
            "--ei", "com.samsung.android.desktopmode.extra.DESKTOP_MODE_SOURCE", "3")
    }
    private fun putSetting(key: String, value: String?) {
        if (value == null) command("settings", "delete", "global", key)
        else command("settings", "put", "global", key, value)
    }

    private fun startDesktop(id: Int): Boolean {
        val samsung = Build.MANUFACTURER.equals("samsung", true)
        if (dexRequested) details.add(if (samsung) "当前没有可采集的原生 DeX，已使用 Sunshine 兼容桌面"
            else "此主机不是三星设备，已使用普通第二屏桌面")
        val component = ComponentName("com.alkaidlab.sunshine", "com.alkaidlab.sunshine.ui.VirtualDesktopActivity")
        launcher = "Sunshine 桌面"
        return start(component, id, false, home = true) && awaitForeground(component, id)
    }

    private fun start(component: ComponentName, id: Int, freeform: Boolean, home: Boolean = false, page: String = "apps"): Boolean = try {
        val args = mutableListOf("am", "start", "--display", id.toString(), "--windowingMode", if (freeform) "5" else "1", "-f", if (home) "0x18000000" else "0x10200000",
            "-a", Intent.ACTION_MAIN, "-c", if (home) Intent.CATEGORY_SECONDARY_HOME else Intent.CATEGORY_LAUNCHER)
        if (home) args.addAll(listOf("--activityType", "2"))
        if (component.className == "com.alkaidlab.sunshine.ui.VirtualDesktopActivity") args.addAll(listOf("--es", "desktop_page", page,
            "--ei", "desktop_display_id", id.toString()))
        args.addAll(listOf("-n", component.flattenToString()))
        val result = command(*args.toTypedArray())
        !result.contains("Error:") && !result.contains("Error type")
    } catch (_: Exception) { false }

    private fun awaitForeground(component: ComponentName, displayId: Int): Boolean {
        repeat(15) {
            @Suppress("DEPRECATION")
            val tasks = (shell.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(100)
            val first = tasks.firstOrNull { task -> runCatching { task.javaClass.getField("displayId").getInt(task) == displayId }.getOrDefault(false) }
            if (first?.topActivity == component) return true
            Thread.sleep(100)
        }
        return false
    }

    @Synchronized override fun launchApp(component: String): Boolean {
        val value = ComponentName.unflattenFromString(component) ?: return false
        val id = inputDisplayId() ?: return false
        // Ordinary second displays do not reliably support OEM freeform. Launch fullscreen
        // first so an unsupported windowing mode cannot strand the user on an empty wallpaper.
        if (!start(value, id, nativeDexMirror != null) && !start(value, id, false)) return false
        // am start may succeed while an OEM redirects the activity to another display.
        repeat(12) {
            @Suppress("DEPRECATION")
            val tasks = (shell.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(100)
            if (tasks.any { task ->
                task.topActivity?.packageName == value.packageName && runCatching { task.javaClass.getField("displayId").getInt(task) == id }.getOrDefault(false)
            }) return true
            Thread.sleep(100)
        }
        return false
    }

    @Synchronized override fun desktopTasks(): String {
        val id = inputDisplayId() ?: return "[]"
        return try {
            @Suppress("DEPRECATION")
            val tasks = (shell.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(100)
            val result = org.json.JSONArray()
            val homes = listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).flatMap { category ->
                shell.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                    .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            }.toSet()
            for (task in tasks.filterNotNull()) {
                val type = task.javaClass
                if (type.getField("displayId").getInt(task) != id) continue
                val component = type.getField("topActivity").get(task) as? ComponentName ?: continue
                if (component.packageName == "com.alkaidlab.sunshine") continue
                if (component in homes) continue
                result.put(JSONObject().put("id", type.getField("taskId").getInt(task)).put("component", component.flattenToString()))
            }
            result.toString()
        } catch (_: Exception) { "[]" }
    }

    @Synchronized override fun desktopAction(action: String, taskId: Int): Boolean {
        val id = inputDisplayId() ?: return false
        return when (action) {
            "back" -> injectKey(KeyEvent.KEYCODE_BACK, true, 0) && injectKey(KeyEvent.KEYCODE_BACK, false, 0)
            "home" -> if (nativeDexMirror != null) injectKey(KeyEvent.KEYCODE_HOME, true, 0) && injectKey(KeyEvent.KEYCODE_HOME, false, 0)
                // A floating OEM task may stay above HOME. The owned app chooser
                // remains usable above it without closing or minimizing user tasks.
                else desktopAction("apps", -1)
            "apps" -> {
                val apps = shell.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.packageName != "com.alkaidlab.sunshine" }
                    .distinctBy { it.activityInfo.packageName to it.activityInfo.name }.sortedBy { it.loadLabel(shell.packageManager).toString() }
                controls?.choose("全部应用", apps.map { it.loadLabel(shell.packageManager).toString() to
                    "launch:${ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString()}" })
                controls != null
            }
            "tasks" -> {
                val tasks = org.json.JSONArray(desktopTasks())
                controls?.choose("最近任务", (0 until tasks.length()).map { index ->
                    val task = tasks.getJSONObject(index)
                    val component = ComponentName.unflattenFromString(task.getString("component"))!!
                    val label = runCatching { shell.packageManager.getApplicationLabel(shell.packageManager.getApplicationInfo(component.packageName, 0)).toString() }
                        .getOrDefault(component.packageName)
                    label to "focus:${task.getInt("id") }"
                })
                controls != null
            }
            "quick" -> {
                controls?.choose("快捷控制", listOf("全部应用" to "apps", "最近任务" to "tasks", "网络设置" to "settings_wifi",
                    "声音设置" to "settings_sound", "显示设置" to "settings_display", "回到主屏查看通知" to "exit"))
                controls != null
            }
            "settings_wifi", "settings_sound", "settings_display" -> {
                val setting = when (action) {
                    "settings_wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                    "settings_sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
                    else -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                }
                Intent(setting).resolveActivity(shell.packageManager)?.let { launchApp(it.flattenToString()) } ?: false
            }
            "focus" -> {
                val tasks = org.json.JSONArray(desktopTasks())
                if ((0 until tasks.length()).none { tasks.getJSONObject(it).optInt("id", -1) == taskId }) false
                else try { !command("am", "task", "focus", taskId.toString()).contains("Error") } catch (_: Exception) { false }
            }
            else -> when {
                action.startsWith("focus:") -> action.removePrefix("focus:").toIntOrNull()?.let { desktopAction("focus", it) } ?: false
                action.startsWith("launch:") -> launchApp(action.removePrefix("launch:"))
                else -> false
            }
        }
    }

    @Synchronized override fun showDesktopControls(callback: ISunshineDesktopCallback): Boolean {
        // The shell service can remain bound between streams. A primary screen
        // (including screen-off streaming) must never acquire desktop controls.
        if (mode != "desktop" || (display == null && nativeDexMirror == null) || owner?.isBinderAlive != true) {
            controls?.close(); controls = null
            return false
        }
        val id = inputDisplayId() ?: return false
        controls?.close(); controls = null
        val target = (shell.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(id) ?: return false
        val overlay = DesktopControlOverlay(shell.createDisplayContext(target), action = { action ->
            try { callback.reportStatus("Action received: $action") } catch (_: Exception) {}
            if (action == "exit") try { callback.requestExit() } catch (_: Exception) {}
            else handler.post {
                val applied = desktopAction(action, -1)
                try { callback.reportStatus("Action completed: $action = $applied") } catch (_: Exception) {}
            }
        }, report = { status -> try { callback.reportStatus(status) } catch (_: Exception) {} })
        return try {
            overlay.show()
            var visible = false
            for (attempt in 0 until 4) {
                if (overlay.isVisibleOn(id)) { visible = true; break }
                Thread.sleep(100)
            }
            check(visible) { "控制条被系统隐藏，未在目标屏幕显示" }
            if (standaloneDex != null && nativeDexMirror?.source?.displayId == Display.DEFAULT_DISPLAY) desktopAction("home", -1)
            controls = overlay; true
        } catch (e: Exception) {
            overlay.close(); details.add("控制条不可用：${e.message}；可从主机通知退出桌面"); false
        }
    }

    private fun setPanelPower(value: Int) {
        val type = Class.forName("android.view.SurfaceControl")
        val tokens = try {
            val ids = type.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            check(ids.isNotEmpty())
            val mainDisplay = (shell.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val unique = Display::class.java.getMethod("getUniqueId").invoke(mainDisplay) as String
            val internal = unique.removePrefix("local:").toLongOrNull()?.takeIf { it in ids } ?: ids.first()
            listOf(type.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, internal) as IBinder)
        } catch (_: Exception) {
            listOf(type.getMethod("getInternalDisplayToken").invoke(null) as IBinder)
        }
        check(tokens.isNotEmpty()) { "没有找到本机屏幕" }
        val method = type.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        tokens.forEach { method.invoke(null, it, value) }
    }

    @Synchronized override fun closeDisplay() {
        var restoreError: Exception? = null
        mode = ""
        controls?.close(); controls = null
        if (panelOff) try { setPanelPower(2); panelOff = false } catch (e: Exception) { restoreError = e }
        try { nativeDexMirror?.close() } catch (e: Exception) { restoreError = e } finally { nativeDexMirror = null }
        try { localDex?.close() } catch (e: Exception) { restoreError = e } finally { localDex = null }
        try { standaloneDex?.close() } catch (e: Exception) { restoreError = e } finally { standaloneDex = null }
        try { virtualWake?.release() } catch (_: Exception) {}; virtualWake = null
        try { wake?.release() } catch (_: Exception) {}; wake = null
        try { display?.release() } catch (_: Exception) {}; display = null
        // Keep the host death watch while idle, including across display changes.
        if (settingsChanged) {
            putSetting("force_desktop_mode_on_external_displays", desktopBefore)
            putSetting("enable_freeform_support", freeformBefore)
            settingsChanged = false
        }
        dexActive = false
        launcher = ""
        details.clear()
        dexRouteDetail = ""
        if (restoreError != null) throw IllegalStateException("显示恢复失败：${restoreError.message}", restoreError)
    }

    private fun inputDisplayId(): Int? = if (mode == "screen_off" && panelOff) Display.DEFAULT_DISPLAY
        else nativeDexMirror?.source?.displayId ?: display?.display?.displayId

    private fun send(event: InputEvent): Boolean { return try {
        val id = inputDisplayId() ?: return false
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, id)
        if (input == null) {
            val manager = shell.getSystemService(Context.INPUT_SERVICE)
            input = manager
            inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }
        inject!!.invoke(input, event, 0) == true
    } catch (_: Exception) { false } }
    @Synchronized override fun injectMotion(event: MotionEvent): Boolean = try { send(event) } finally { event.recycle() }
    @Synchronized override fun injectKey(keyCode: Int, down: Boolean, modifiers: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return send(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }
    @Synchronized override fun snapshot(): String {
        val nativeDisplay = nativeDexMirror?.source ?: NativeDexMirror.activeDisplay(shell)
        val nativeMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        nativeDisplay?.getRealMetrics(nativeMetrics)
        return JSONObject().put("display_id", inputDisplayId() ?: -1)
        .put("protocol_version", 8).put("build_code", com.alkaidlab.sunshine.BuildConfig.VERSION_CODE)
        .put("local_dex_pending", (localDex != null || standaloneDex != null) && nativeDexMirror == null)
        .put("local_dex_ready", (localDex != null || standaloneDex != null) && nativeDisplay != null)
        .put("dex_route_detail", dexRouteDetail).put("desktop_controls", controls != null)
        .put("native_dex_width", nativeMetrics.widthPixels).put("native_dex_height", nativeMetrics.heightPixels)
        .put("local_dex_stage", localDex?.stage ?: "idle").put("local_dex_error", localDex?.failure?.message ?: "")
        .put("launcher", launcher).put("mode", mode).put("panel_off", panelOff)
        .put("requested_refresh_rate", requestedRefreshRate).put("actual_refresh_rate", (nativeDexMirror?.source ?: display?.display)?.refreshRate ?: 0f)
        .put("system_dex_mirror", nativeDexMirror != null)
        .put("refresh_rate_request_supported", refreshRateRequestSupported).put("dex_requested", dexRequested)
        .put("dex_active", dexActive).put("detail", details.joinToString("；")).toString()
    }
    override fun destroy() { try { closeDisplay() } finally { thread.quitSafely(); System.exit(0) } }

    @Synchronized override fun prepareLocalDex(owner: IBinder): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", true) || NativeDexMirror.activeDisplay(shell) != null ||
            NativeDexMirror.hasExternalConnection(shell)) return false
        if (localDex != null || standaloneDex != null) return true
        val route = SamsungDexPolicy.route(true, false, false, SamsungLocalDex.supported(shell), SamsungStandaloneDex.supported())
        if (route == SamsungDexPolicy.Route.STANDALONE) {
            val receiver = SamsungStandaloneDex(shell)
            try {
                receiver.start(); attachOwner(owner); standaloneDex = receiver
                dexRouteDetail = "正在启动平板主屏 DeX；退出时恢复本次切换"
                return true
            } catch (e: Exception) { receiver.close(); throw e }
        }
        if (route == SamsungDexPolicy.Route.COMPATIBLE) {
            dexRouteDetail = "当前固件没有可用的无外屏原生 DeX 入口，使用带应用与导航的兼容桌面"
            return false
        }
        val manager = shell.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val wifi = DisplayManager::class.java.getMethod("getWifiDisplayStatus").invoke(manager)
        check(wifi.javaClass.getMethod("getActiveDisplayState").invoke(wifi) == 0) { "请先结束现有无线投屏" }
        val receiver = SamsungLocalDex(shell)
        try {
            receiver.start()
            attachOwner(owner)
            localDex = receiver
            command("am", "broadcast", "--user", "0", "-a", "com.samsung.android.desktopmode.action.DESKTOP_MODE_UPDATE_REQUEST",
                "--ei", "com.samsung.android.desktopmode.extra.DESKTOP_MODE_STATE", "1",
                "--ei", "com.samsung.android.desktopmode.extra.DESKTOP_MODE_SOURCE", "0")
            // Samsung's service owns its non-exported discovery activity; the
            // system request above is the supported entry, never direct start.
            dexRouteDetail = "请在三星 DeX 列表选择 Sunshine Local DeX，再确认立即开始；首次使用还可能需要接受三星的 DeX 使用说明"
            return true
        } catch (error: Exception) {
            receiver.close(); localDex = null
            throw error
        }
    }
}
