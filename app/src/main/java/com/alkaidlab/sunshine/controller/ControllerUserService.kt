package com.alkaidlab.sunshine.controller

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.app.usage.UsageStatsManager
import android.os.*
import android.view.InputEvent
import android.view.MotionEvent
import com.alkaidlab.sunshine.service.IControllerListener
import com.alkaidlab.sunshine.service.IControllerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent of capture/display services: remains active with zero Moonlight clients. */
class ControllerUserService(context: Context) : IControllerService.Stub() {
    constructor() : this(systemContext())
    companion object {
        private fun systemContext(): Context {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("currentActivityThread").invoke(null) ?: type.getMethod("systemMain").invoke(null)
            return type.getMethod("getSystemContext").invoke(thread) as Context
        }
        private val buttons = mapOf(304 to 4096, 305 to 8192, 307 to 16384, 308 to 32768,
            310 to 256, 311 to 512, 314 to 32, 315 to 16, 316 to 1024, 317 to 64, 318 to 128,
            544 to 1, 545 to 2, 546 to 4, 547 to 8,
            103 to 1, 108 to 2, 105 to 4, 106 to 8, 28 to 4096, 352 to 4096, 158 to 8192,
            102 to 1024, 172 to 1024, 139 to 16)
    }
    private val shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val lock = Any()
    private val thread = HandlerThread("Sunshine-Controller-IO").apply { start() }
    private val handler = Handler(thread.looper)
    private var listener: IControllerListener? = null
    private var owner: IBinder? = null
    private val death = IBinder.DeathRecipient { destroy() }
    private var running = false
    private var nextScan = 0L
    private var lastList = ""
    private var wakeFd = -1
    private var watchFd = -1
    private var excluded = emptySet<String>()
    private val pads = mutableMapOf<Int, Int>()
    private var loadError: String? = null
    private val navigationComponent = ComponentName("com.alkaidlab.sunshine", "com.alkaidlab.sunshine.controller.ControllerNavigationService").flattenToString()
    private val navigationSettings = ControllerNavigationSettings(navigationComponent,
        { ComponentName.unflattenFromString(it)?.flattenToString() == navigationComponent }, ::readSecure, ::writeSecure)
    private data class Device(val fd: Int, val name: String, val kind: String,
        val ranges: Map<Int, IntArray>, var buttons: Int = 0, var keys: Int = 0,
        var hat: Int = 0, val values: MutableMap<Int, Float> = mutableMapOf(), var grabbed: Boolean = false) {
        val layout = ControllerAxisLayout.detect(ranges.keys)
        val monitoredAxes = setOf(0, 1, layout.rightX, layout.rightY, layout.lt, layout.rt).filter { it in ranges }
        init {
            monitoredAxes.forEach { code ->
                val range = ranges.getValue(code)
                if (range[2] > range[1]) values[code] = ((range[0] - range[1]).toFloat() / (range[2] - range[1])).coerceIn(0f, 1f)
            }
        }
        fun axisActive() = monitoredAxes.any { code ->
            val value = values[code] ?: return@any false
            if (code == layout.lt || code == layout.rt) value > .12f else kotlin.math.abs(value - .5f) > .12f
        }
    }
    private val devices = linkedMapOf<String, Device>()
    init {
        try {
            val app = context.packageManager.getApplicationInfo("com.alkaidlab.sunshine", 0)
            System.load(File(app.nativeLibraryDir, "libsunshine_controller.so").absolutePath)
        } catch (error: Throwable) { loadError = "输入模块加载失败：${error.message}" }
    }
    override fun start(listener: IControllerListener, owner: IBinder) = synchronized(lock) {
        // Propagate startup failure through Binder. An error callback alone
        // would still let the host publish ready=true with no input reader.
        check(loadError == null) { loadError.orEmpty() }
        this.listener = listener
        if (this.owner != owner) {
            owner.linkToDeath(death, 0)
            try { this.owner?.unlinkToDeath(death, 0) } catch (_: Exception) {}
            this.owner = owner
        }
        if (!running) {
            if (wakeFd < 0) wakeFd = ControllerNative.createWake()
            if (watchFd < 0) watchFd = ControllerNative.watchInputs()
            check(wakeFd >= 0) { "输入事件等待器创建失败" }
            running = true; handler.post(poll)
        }
    }
    private fun remove(path: String) {
        devices.remove(path)?.let { ControllerNative.grab(it.fd, false); ControllerNative.close(it.fd) }
        listener?.removed(path)
    }
    private fun scan() {
        val found = ControllerNative.devices().map { it.split('|') }.filter { it.size == 3 }
        val paths = found.map { it[0] }.toSet()
        devices.keys.toList().filter { it !in paths || devices[it]?.name in excluded }.forEach(::remove)
        found.filter { it[0] !in devices && it[1] !in excluded }.forEach { parts ->
            val fd = ControllerNative.open(parts[0])
            if (fd >= 0) {
                // Some kernels return a zeroed absinfo for unadvertised axes.
                // Only usable ranges identify the layout; otherwise every pad
                // appears to have Samsung's GAS/BRAKE trigger axes.
                val ranges = (0..23).associateWith { ControllerNative.axis(fd, it) }
                    .filterValues { it.size == 4 && it[2] > it[1] }
                devices[parts[0]] = Device(fd, parts[1], parts[2], ranges)
            } else listener?.error("无法读取 ${parts[1]}（$fd）")
        }
        val list = JSONArray().apply { found.forEach { parts -> put(JSONObject().put("id", parts[0]).put("name", parts[1])
            .put("kind", parts[2]).put("excluded", parts[1] in excluded).put("grabbed", devices[parts[0]]?.grabbed == true)) } }.toString()
        if (list != lastList) { lastList = list; listener?.devices(list) }
    }
    private val poll = object : Runnable {
        override fun run() {
            val waiting = synchronized(lock) {
                if (!running) return
                val activeAxis = devices.values.any { it.axisActive() }
                devices.values.map { it.fd }.toIntArray() to if (nextScan == 0L) 0 else if (activeAxis) 40 else 5000
            }
            val reason = ControllerNative.awaitInput(waiting.first, wakeFd, watchFd, waiting.second)
            synchronized(lock) {
                if (!running) return
                try {
                    val now = SystemClock.uptimeMillis()
                    // A timeout returns 2; a wake/inotify event returns 1|2.
                    // Active-axis reconciliation must not reopen every input
                    // node on each 40 ms timeout.
                    if ((reason and 3) == 3 || now >= nextScan) { nextScan = now + 5000; scan() }
                    devices.toMap().forEach { (path, device) ->
                        val events = ControllerNative.read(device.fd)
                        var dead = false
                        var emitted = false
                        for (index in events.indices step 3) {
                            val type = events[index]; val code = events[index + 1]; val value = events[index + 2]
                            if (type < 0 || (type == 0 && code == 3)) { dead = true; break }
                            if (type == 1 && value != 2) {
                                val mask = buttons[code]
                                if (mask != null) device.keys = if (value != 0) device.keys or mask else device.keys and mask.inv()
                                else listener?.key(path, code, value != 0)
                            } else if (type == 3) {
                                val range = device.ranges[code]
                                if (code == 16 || code == 17) {
                                    device.hat = if (code == 16) (device.hat and 12.inv()) or (if (value < 0) 4 else if (value > 0) 8 else 0)
                                        else (device.hat and 3.inv()) or (if (value < 0) 1 else if (value > 0) 2 else 0)
                                } else if (range != null && range[2] > range[1]) {
                                    device.values[code] = ((value - range[1]).toFloat() / (range[2] - range[1])).coerceIn(0f, 1f)
                                }
                            } else if (type == 0 && code == 0) {
                                device.buttons = device.keys or device.hat
                                reportState(path, device, value.toLong())
                                emitted = true
                            }
                        }
                        // Bluetooth drivers can update the current axis state
                        // while the evdev reader misses a release packet. Read
                        // kernel state only while axes are active, so circles,
                        // swipes and mouse motion always end at neutral.
                        var corrected = false
                        if (!dead && (emitted || waiting.second == 40)) device.monitoredAxes.forEach { code ->
                            val range = device.ranges.getValue(code)
                            val current = ControllerNative.axis(device.fd, code)
                            if (current.size == 4 && range[2] > range[1]) {
                                val normalized = ((current[0] - range[1]).toFloat() / (range[2] - range[1])).coerceIn(0f, 1f)
                                if (kotlin.math.abs((device.values[code] ?: normalized) - normalized) > .01f) {
                                    device.values[code] = normalized; corrected = true
                                }
                            }
                        }
                        if (corrected) reportState(path, device, now)
                        if (dead) remove(path)
                    }
                } catch (error: Exception) {
                    // A dead host must never leave an exclusive kernel grab behind.
                    // Exit the UserService as well: leaving a live Binder here
                    // prevents the host from detecting that its reader stopped.
                    android.util.Log.e("SunshineController", "Controller reader stopped", error)
                    destroy(); return
                }
                if (running) handler.post(this)
            }
        }
    }
    private fun reportState(path: String, device: Device, timestamp: Long) {
        fun stick(axis: Int) = if (axis < 0) 0f else (device.values[axis] ?: .5f) * 2f - 1f
        val layout = device.layout
        listener?.state(path, device.buttons, stick(0), stick(1), stick(layout.rightX), stick(layout.rightY),
            if (layout.lt >= 0) device.values[layout.lt] ?: 0f else 0f,
            if (layout.rt >= 0) device.values[layout.rt] ?: 0f else 0f,
            timestamp)
    }
    override fun grab(device: String, enabled: Boolean): Boolean = synchronized(lock) {
        val input = devices[device] ?: return@synchronized false
        if (input.grabbed == enabled) return@synchronized true
        ControllerNative.grab(input.fd, enabled).also { if (it) { input.grabbed = enabled; nextScan = 0; ControllerNative.wake(wakeFd) } }
    }
    override fun exclude(json: String) = synchronized(lock) {
        val array = JSONArray(json)
        excluded = (0 until array.length()).map { array.getString(it) }.toSet()
        nextScan = 0
        if (wakeFd >= 0) ControllerNative.wake(wakeFd)
    }
    override fun gamepad(id: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float): Boolean = synchronized(lock) {
        if (!running || loadError != null || id !in 0..63) return@synchronized false
        val fd = pads[id] ?: ControllerNative.createGamepad(id).also { if (it >= 0) pads[id] = it }
        fd >= 0 && ControllerNative.writeGamepad(fd, buttons, lx, ly, rx, ry, lt, rt)
    }
    override fun releaseGamepad(id: Int) = synchronized(lock) { pads.remove(id)?.let { ControllerNative.destroyGamepad(it) }; Unit }
    override fun motion(event: MotionEvent, displayId: Int, actionButton: Int): Boolean = try {
        if (actionButton != 0) MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(event, actionButton)
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val manager = shell.getSystemService(Context.INPUT_SERVICE)
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType).invoke(manager, event, 0) == true
    } catch (error: Exception) {
        android.util.Log.w("SunshineController", "Motion injection failed: display=$displayId action=${event.actionMasked}", error)
        false
    } finally { event.recycle() }
    override fun systemAction(action: String, displayId: Int): Boolean { return try {
        val type = Class.forName("android.os.ServiceManager")
        val binder = type.getMethod("getService", String::class.java).invoke(null, "statusbar") as IBinder
        val status = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        when (action) {
            "notifications" -> {
                if (displayId != 0) return false // Do not open a hidden primary-screen shade from a secondary display.
                status.javaClass.getMethod("expandNotificationsPanel").invoke(status)
            }
            "power" -> status.javaClass.getMethod("showGlobalActions").invoke(status)
            else -> return false
        }
        true
    } catch (_: Exception) { false } }
    override fun enableNavigation(enabled: Boolean): Boolean = synchronized(lock) {
        // A delayed host recovery must not re-enable our accessibility entry
        // after stop/destroy has already released this service instance.
        if (enabled && !running) return@synchronized false
        val identity = Binder.clearCallingIdentity()
        try {
            navigationSettings.setEnabled(enabled)
            true
        } catch (error: Exception) {
            android.util.Log.e("SunshineController", "enableNavigation failed", error)
            try { listener?.error("系统控件导航${if (enabled) "启用" else "恢复"}失败：${error.message}") } catch (_: Exception) {}
            false
        }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun readSecure(key: String): String? = settingsCommand("get", key).trim().takeUnless { it == "null" }
    private fun writeSecure(key: String, value: String) {
        // Use Android's shell settings client, as the display adapter does.
        // A fresh shell client avoids inheriting the application's Binder
        // attribution through the UserService settings resolver.
        settingsCommand("put", key, value)
        check(readSecure(key) == value) { "系统设置 $key 写入后核对失败" }
    }
    private fun settingsCommand(operation: String, key: String, value: String? = null): String {
        val command = mutableListOf("settings", "--user", "current", operation, "secure", key)
        if (value != null) command.add(value)
        val result = runCommand(command, 2000)
        check(result.first == 0 && !result.second.contains("Exception") && !result.second.contains("Error:")) {
            "系统设置 $key 操作失败：${result.second.trim().take(240)}"
        }
        return result.second
    }
    private fun runCommand(command: List<String>, timeoutMs: Long): Pair<Int, String> {
        // Redirect to a regular file rather than reading a pipe before waitFor:
        // readText() can otherwise wait for EOF forever, making its later
        // timeout ineffective. A file also cannot fill a subprocess pipe and
        // deadlock waitFor. Keep the returned diagnostic output bounded.
        val output = File.createTempFile("sunshine-controller-", ".log", File("/data/local/tmp"))
        var process: java.lang.Process? = null
        try {
            val launched = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
            process = launched
            check(launched.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) { "系统命令操作超时" }
            val bytes = ByteArray(16 * 1024)
            val count = output.inputStream().use { input ->
                var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    total += count
                }
                // Never silently truncate a service list and then write it
                // back: fail safely if OEM output exceeds this bound.
                check(input.read() < 0) { "系统命令输出过长" }
                total
            }
            return launched.exitValue() to if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else ""
        } finally {
            process?.let { launched ->
                if (launched.isAlive) launched.destroyForcibly() else launched.destroy()
            }
            output.delete()
        }
    }
    override fun frequentApps(count: Int): String {
        val identity = Binder.clearCallingIdentity()
        val apps = JSONArray()
        var detail = "Android 使用记录 · 最近 7 天"
        try {
            val usage = shell.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usage.queryAndAggregateUsageStats(now - 7L * 86400000, now).values
                .sortedWith(compareByDescending<android.app.usage.UsageStats> { it.totalTimeInForeground }.thenByDescending { it.lastTimeUsed })
                .filter { it.packageName != "com.alkaidlab.sunshine" }
                .mapNotNull { shell.packageManager.getLaunchIntentForPackage(it.packageName)?.component }
                .distinct().take(count.coerceIn(1, 8)).forEach { apps.put(it.flattenToString()) }
        } catch (error: Exception) { detail = "使用记录不可用：${error.javaClass.simpleName}" }
        finally { Binder.restoreCallingIdentity(identity) }
        return JSONObject().put("source", detail).put("apps", apps)
            .put("samsung_widget", false).put("samsung_detail", if (Build.MANUFACTURER.equals("samsung", true)) "DeepSky 小组件接口受系统关联与签名权限保护" else "非三星设备").toString()
    }
    override fun launchApp(component: String, displayId: Int): Boolean {
        if (!synchronized(lock) { running }) return false
        val identity = Binder.clearCallingIdentity()
        return try {
        val target = ComponentName.unflattenFromString(component)
        check(target != null)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = shell.packageManager.resolveActivity(intent, 0)?.activityInfo
        check(activity != null && activity.enabled && activity.exported)
        val result = runCommand(listOf("am", "start", "--user", "current", "--display", displayId.toString(),
            "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-n", target.flattenToString()), 3000)
        result.first == 0 && !result.second.contains("Error:") && !result.second.contains("Exception") &&
            !result.second.contains("Permission Denial")
    } catch (_: Exception) { false } finally { Binder.restoreCallingIdentity(identity) } }
    private fun stopLocked() {
        running = false
        if (wakeFd >= 0) ControllerNative.wake(wakeFd)
        handler.removeCallbacksAndMessages(null)
        devices.values.forEach {
            try { ControllerNative.grab(it.fd, false) } finally { ControllerNative.close(it.fd) }
        }
        devices.clear()
        pads.values.forEach { ControllerNative.destroyGamepad(it) }; pads.clear()
        // Release physical devices before potentially slow OEM settings writes.
        enableNavigation(false)
        listener = null; lastList = ""; nextScan = 0
    }
    override fun stop() = synchronized(lock) { stopLocked() }
    override fun destroy() {
        synchronized(lock) { stopLocked(); try { owner?.unlinkToDeath(death, 0) } catch (_: Exception) {}; owner = null }
        // Shizuku expects the dedicated UserService process to exit. Merely
        // quitting its worker looper leaves a cached Binder with no input reader.
        handler.post {
            if (wakeFd >= 0) ControllerNative.close(wakeFd)
            if (watchFd >= 0) ControllerNative.close(watchFd)
            System.exit(0)
        }
    }
}
