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
    private val shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val thread = HandlerThread("Sunshine-Virtual-Display").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
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

    @Synchronized override fun createDisplay(width: Int, height: Int, density: Int, surface: Surface, mode: String, owner: IBinder,
        fps: Int, samsungDex: Boolean, highRefresh: Boolean): Int {
        require(width in 240..8192 && height in 240..8192 && density in 120..640)
        require(fps in 15..240)
        require(mode == "desktop")
        check(surface.isValid)
        check(!(shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "请先解锁主机，再创建虚拟屏" }
        closeDisplay()
        try {
            this.mode = mode
            details.clear()
            dexRequested = samsungDex
            dexActive = false
            requestedRefreshRate = if (highRefresh) fps else 60
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
            this.owner = owner
            owner.linkToDeath(died, 0)
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
            this.owner = owner
            owner.linkToDeath(died, 0)
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
    private fun putSetting(key: String, value: String?) {
        if (value == null) command("settings", "delete", "global", key)
        else command("settings", "put", "global", key, value)
    }

    private fun startDesktop(id: Int): Boolean {
        val samsung = Build.MANUFACTURER.equals("samsung", true)
        if (dexRequested && samsung && startSamsungDesktop(id)) return true
        if (dexRequested) details.add(if (samsung) "当前系统未提供可用的第二屏 DeX，已使用 Sunshine 桌面"
            else "此主机不是三星设备，已使用普通第二屏桌面")
        if (mode == "desktop") {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
            val candidates = shell.packageManager.queryIntentActivities(home, 0)
            val system = candidates.firstOrNull { !samsung && it.activityInfo.packageName != "com.alkaidlab.sunshine" }
            if (system != null) {
                val component = ComponentName(system.activityInfo.packageName, system.activityInfo.name)
                if (start(component, id, false)) { launcher = "系统第二屏桌面"; return true }
            }
        }
        val component = ComponentName("com.alkaidlab.sunshine", "com.alkaidlab.sunshine.ui.VirtualDesktopActivity")
        launcher = "Sunshine 桌面"
        return start(component, id, false)
    }

    private fun startSamsungDesktop(id: Int): Boolean {
        val secondary = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
        val system = shell.packageManager.queryIntentActivities(secondary, 0).firstOrNull {
            val info = it.activityInfo
            info.exported && (info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) &&
                (info.packageName.startsWith("com.sec.android.") || info.packageName.startsWith("com.samsung.android."))
        }
        if (system != null && start(ComponentName(system.activityInfo.packageName, system.activityInfo.name), id, false) && isDexConfigured()) {
            dexActive = true; launcher = "三星 DeX 第二屏"; return true
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage("com.sec.android.app.desktoplauncher")
        val legacy = shell.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        if (!legacy.exported || !start(ComponentName(legacy.packageName, legacy.name), id, false)) return false
        // A successful am start alone does not establish that legacy DeX entered
        // desktop mode. Inspect this display's configuration before reporting it.
        if (!isDexConfigured()) return false
        dexActive = true; launcher = "三星 DeX"; return true
    }

    private fun isDexConfigured(): Boolean {
        // A bare display context inherits the shell process configuration. Read
        // the actual task configurations on the owned display instead.
        return try {
            val taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            val tasks = Class.forName("android.app.IActivityTaskManager")
                .getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
                .invoke(taskManager, display!!.display.displayId) as List<*>
            tasks.any { task ->
                val configuration = task!!.javaClass.getField("configuration").get(task)
                configuration.javaClass.getField("semDesktopModeEnabled").getInt(configuration) == 1
            }
        } catch (_: Exception) { false }
    }

    private fun start(component: ComponentName, id: Int, freeform: Boolean): Boolean = try {
        val args = mutableListOf("am", "start", "--display", id.toString(), "--windowingMode", if (freeform) "5" else "1", "-f", "0x18000000")
        args.addAll(listOf("-n", component.flattenToString()))
        val result = command(*args.toTypedArray())
        !result.contains("Error:") && !result.contains("Error type")
    } catch (_: Exception) { false }

    @Synchronized override fun launchApp(component: String): Boolean {
        val value = ComponentName.unflattenFromString(component) ?: return false
        val id = if (mode == "screen_off" && panelOff) Display.DEFAULT_DISPLAY else display?.display?.displayId ?: return false
        return start(value, id, mode == "desktop")
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
        if (panelOff) try { setPanelPower(2); panelOff = false } catch (e: Exception) { restoreError = e }
        try { virtualWake?.release() } catch (_: Exception) {}; virtualWake = null
        try { wake?.release() } catch (_: Exception) {}; wake = null
        try { display?.release() } catch (_: Exception) {}; display = null
        try { owner?.unlinkToDeath(died, 0) } catch (_: Exception) {}; owner = null
        if (settingsChanged) {
            putSetting("force_desktop_mode_on_external_displays", desktopBefore)
            putSetting("enable_freeform_support", freeformBefore)
            settingsChanged = false
        }
        if (restoreError != null) throw IllegalStateException("主屏恢复失败：${restoreError.message}", restoreError)
    }

    private fun send(event: InputEvent): Boolean { return try {
        val id = if (mode == "screen_off" && panelOff) Display.DEFAULT_DISPLAY else display?.display?.displayId ?: return false
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
    @Synchronized override fun snapshot(): String = JSONObject().put("display_id", if (mode == "screen_off" && panelOff) 0 else display?.display?.displayId ?: -1)
        .put("protocol_version", 3).put("build_code", com.alkaidlab.sunshine.BuildConfig.VERSION_CODE)
        .put("launcher", launcher).put("mode", mode).put("panel_off", panelOff)
        .put("requested_refresh_rate", requestedRefreshRate).put("actual_refresh_rate", display?.display?.refreshRate ?: 0f)
        .put("refresh_rate_request_supported", refreshRateRequestSupported).put("dex_requested", dexRequested)
        .put("dex_active", dexActive).put("detail", details.joinToString("；")).toString()
    override fun destroy() { try { closeDisplay() } finally { thread.quitSafely(); System.exit(0) } }
}
