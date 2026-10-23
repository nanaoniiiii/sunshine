package com.alkaidlab.sunshine.controller

import android.content.*
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import android.widget.Toast
import com.alkaidlab.sunshine.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import kotlin.math.abs

data class ControllerDevice(val id: String, val name: String, val kind: String, val enabled: Boolean, val grabbed: Boolean)
data class ControllerStatus(val running: Boolean = false, val ready: Boolean = false, val detail: String = "随 Sunshine 服务启动，无需串流连接",
    val devices: List<ControllerDevice> = emptyList(), val modes: Map<String, ControllerMode> = emptyMap(), val lastInput: String = "",
    val physicalReports: Long = 0, val kernelToMapperMs: Long? = null, val maxKernelToMapperMs: Long? = null,
    val systemNavigation: Boolean = false, val frequentAppsSource: String = "等待输入服务",
    val mouseInjection: String = "尚未测试",
    val focusedControl: String = "", val navigationResult: String = "")
data class ControllerLive(val id: String, val name: String, val mode: ControllerMode, val sample: ControllerSample)
data class ControllerTrace(val time: Long, val device: String, val input: String, val operation: String, val mode: ControllerMode?)

/** App-owned adapter lifetime, never bound to streamStarted/streamStopped. All reducer state lives on one handler. */
object ControllerAdapter {
    private lateinit var context: Context
    private val thread = HandlerThread("Sunshine-Controller").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        handler.post { if (wanted) report("适配器操作失败：${error.message}") }
    })
    private var service: IControllerService? = null
    private var binding = false
    private var bindingAt = 0L
    @Volatile private var wanted = false
    private var epoch = 0
    private val owner = Binder()
    private val reducers = linkedMapOf<String, ControllerReducer>()
    private val acquired = mutableSetOf<String>()
    private val virtualIds = mutableMapOf<String, Int>()
    private val radial = mutableMapOf<String, RadialGesture>()
    private var wheelOwner: String? = null
    private var wheelItems = emptyList<RadialItem>()
    private var frequentItems = emptyList<RadialItem>()
    private var wheelVisible = false
    private var wheelRevision = 0
    private var wheelKind = RadialKind.ACTIONS
    private var reportCount = 0L
    private var maxReportDelay = 0L
    private var nextReportPublish = 0L
    private var overrides = emptyMap<String, ControllerAction>()
    private val _status = MutableStateFlow(ControllerStatus())
    val status: StateFlow<ControllerStatus> = _status
    private val _live = MutableStateFlow<List<ControllerLive>>(emptyList())
    val live: StateFlow<List<ControllerLive>> = _live
    private val _trace = MutableStateFlow<List<ControllerTrace>>(emptyList())
    val trace: StateFlow<List<ControllerTrace>> = _trace
    private var nextLivePublish = 0L
    private var modeToast: Toast? = null
    private val _captured = MutableStateFlow<String?>(null)
    val captured: StateFlow<String?> = _captured
    private val _settingsRevision = MutableStateFlow(0)
    val settingsRevision: StateFlow<Int> = _settingsRevision
    @Volatile private var captureUntil = 0L
    private var currentDisplay = -1
    private var mouseX = 0f; private var mouseY = 0f
    private val heldMouse = mutableMapOf<String, Int>()
    private var mouseDownAt = 0L
    private var mouseLastResult = ""
    private var gestureStart = 0L; private var gestureX = 0f; private var gestureY = 0f
    private var gestureOwner: String? = null
    private var size = Point(1, 1)
    private var nextMetrics = 0L
    @Volatile private var remoteEpoch = 0
    private val remoteKeys = RemoteKeyRouter(KeyEvent.getModifierMetaStateMask())
    private var args: rikka.shizuku.Shizuku.UserServiceArgs? = null
    private var connection: ServiceConnection? = null
    private fun prefs() = context.getSharedPreferences("controller_adapter", Context.MODE_PRIVATE)

    fun initialize(value: Context) {
        if (!::context.isInitialized) synchronized(this) { if (!::context.isInitialized) context = value.applicationContext }
    }
    fun enabled(): Boolean = prefs().getBoolean("enabled", true)
    fun bindings(): Map<String, ControllerAction> = prefs().all.mapNotNull { (key, value) ->
        if (key.startsWith("binding_")) runCatching { key.removePrefix("binding_") to ControllerAction.valueOf(value as String) }.getOrNull() else null
    }.toMap()
    fun setEnabled(value: Boolean) {
        prefs().edit().putBoolean("enabled", value).apply(); _settingsRevision.value++
        handler.post { if (wanted) { disconnect(); if (value) connect() } }
    }
    fun bind(token: String, action: ControllerAction) {
        prefs().edit().putString("binding_$token", action.name).apply(); reloadBindings()
    }
    fun resetBindings() {
        val editor = prefs().edit(); prefs().all.keys.filter { it.startsWith("binding_") }.forEach { editor.remove(it) }; editor.apply(); reloadBindings()
    }
    private fun reloadBindings() { _settingsRevision.value++; handler.post { overrides = bindings() } }
    fun deviceEnabled(device: ControllerDevice, value: Boolean) {
        val set = prefs().getStringSet("excluded", emptySet()).orEmpty().toMutableSet()
        if (value) set.remove(device.name) else set.add(device.name)
        prefs().edit().putStringSet("excluded", set).apply()
        handler.post { callService(Unit) { it.exclude(JSONArray(set.toList()).toString()) } }
    }
    fun capture() { _captured.value = null; handler.post { reducers.keys.toList().forEach { setMode(it, ControllerMode.NAVIGATION) }; captureUntil = SystemClock.uptimeMillis() + 15000; cancelGesture(); cancelWheel() } }
    fun stopCapture() { handler.post { captureUntil = 0 } }
    fun clearTrace() { handler.post { _trace.value = emptyList() } }
    fun refreshLive() { handler.post { publishLive(true) } }
    fun navigationChanged(connected: Boolean) { handler.post {
        _status.value = _status.value.copy(systemNavigation = connected)
        if (!connected) cancelWheel()
        refreshMouseCursor()
    } }
    fun navigationInterrupted() { handler.post { cancelWheel(); refreshMouseCursor() } }
    fun navigationOutcome(control: String, result: String) { handler.post { _status.value = _status.value.copy(focusedControl = control, navigationResult = result) } }
    private fun deviceName(id: String): String = _status.value.devices.find { it.id == id }?.name
        ?: "Moonlight 手柄 ${(id.substringAfter(':').toIntOrNull() ?: 0) + 1}"
    private fun publishLive(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (_live.subscriptionCount.value == 0 || !force && now < nextLivePublish) return
        nextLivePublish = now + 50
        _live.value = reducers.map { (id, reducer) -> ControllerLive(id, deviceName(id), reducer.mode, reducer.sample) }
    }
    private fun trace(device: String, input: String, operation: String) {
        _trace.value = (_trace.value + ControllerTrace(SystemClock.uptimeMillis(), deviceName(device), input, operation, reducers[device]?.mode)).takeLast(60)
    }
    private fun showMode(mode: ControllerMode) { main.post {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(SunshineRuntimeManager.getInstance().sourceDisplayId)
        val displayContext = if (display != null) context.createDisplayContext(display) else context
        modeToast?.cancel()
        modeToast = Toast.makeText(displayContext, "Sunshine · ${mode.title}模式", Toast.LENGTH_SHORT).also { it.show() }
    } }
    fun mode(mode: ControllerMode) { handler.post { reducers.keys.toList().forEach { setMode(it, mode) } } }
    fun start(value: Context) {
        initialize(value)
        handler.post {
            if (wanted) return@post
            wanted = true; overrides = bindings()
            _status.value = _status.value.copy(running = true, systemNavigation = ControllerNavigationService.connected.value)
            connect(); handler.post(tick)
        }
    }
    fun stop() { handler.post {
        wanted = false
        ++remoteEpoch
        captureUntil = 0
        // Preserve a start() already queued by a rapid service restart.
        // Old physical callbacks use epoch; remote callbacks use remoteEpoch.
        handler.removeCallbacks(tick)
        disconnect()
        _status.value = ControllerStatus()
    } }

    private fun connect() {
        if (!wanted || !enabled() || service != null || binding) return
        if (!PrivilegedShell.available()) { _status.value = _status.value.copy(ready = false, detail = "等待 Shizuku 授权，服务运行后自动重试"); return }
        binding = true; bindingAt = SystemClock.uptimeMillis()
        val generation = ++epoch
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val serviceArgs = rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(context.packageName, ControllerUserService::class.java.name))
            .daemon(false).processNameSuffix("controller").tag("sunshine-controller").version((version xor (version ushr 32)).toInt())
        val listener = object : IControllerListener.Stub() {
            override fun state(device: String, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, eventTimeMs: Long) {
                handler.post {
                    if (generation == epoch && device in acquired) {
                        val now = SystemClock.uptimeMillis()
                        val delay = now - eventTimeMs
                        ++reportCount
                        if (delay in 0..5000) {
                            maxReportDelay = maxOf(maxReportDelay, delay)
                            if (now >= nextReportPublish || buttons != reducers[device]?.sample?.buttons) {
                                nextReportPublish = now + 1000
                                _status.value = _status.value.copy(physicalReports = reportCount, kernelToMapperMs = delay, maxKernelToMapperMs = maxReportDelay)
                            }
                        }
                        sample(device, ControllerSample(buttons, lx, ly, rx, ry, lt, rt))
                    }
                }
            }
            override fun key(device: String, code: Int, down: Boolean) { handler.post {
                if (generation == epoch && device in acquired && down && reducers[device]?.mode != ControllerMode.NATIVE)
                    token(device, if (code == 116 || code == 142) "POWER" else "LINUX:$code")
            } }
            override fun devices(json: String) { handler.post {
                if (generation != epoch) return@post
                val list = JSONArray(json)
                val found = (0 until list.length()).map { list.getJSONObject(it) }.map {
                    ControllerDevice(it.getString("id"), it.getString("name"), it.getString("kind"), !it.optBoolean("excluded"), it.optBoolean("grabbed"))
                }
                _status.value = _status.value.copy(devices = found)
                found.filter { it.enabled }.forEach {
                    reducers.getOrPut(it.id) { ControllerReducer() }
                    if (it.id !in acquired) {
                        if (callService(false) { remote -> remote.grab(it.id, true) }) acquired.add(it.id)
                        else report("无法独占 ${it.name}，已保留系统输入；请检查其他映射器是否占用")
                    }
                }
                publishModes()
            } }
            override fun removed(device: String) { handler.post { if (generation == epoch) remove(device) } }
            override fun error(message: String) { handler.post { if (generation == epoch) report(message) } }
        }
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { handler.post {
                if (!wanted || generation != epoch) return@post
                try {
                    service = IControllerService.Stub.asInterface(binder)
                    binder.linkToDeath({ handler.post { if (generation == epoch) disconnect() } }, 0)
                    service!!.exclude(JSONArray(prefs().getStringSet("excluded", emptySet()).orEmpty().toList()).toString())
                    service!!.start(listener, owner)
                    val navigationReady = service!!.enableNavigation(true)
                    val remoteService = service!!
                    scope.launch {
                        val data = runCatching { org.json.JSONObject(remoteService.frequentApps(8)) }.getOrNull()
                        val entries = data?.optJSONArray("apps") ?: JSONArray()
                        val items = (0 until entries.length()).mapNotNull { index ->
                            ComponentName.unflattenFromString(entries.getString(index))?.let { component ->
                                runCatching { RadialItem(component.flattenToString(), context.packageManager.getActivityInfo(component, 0).loadLabel(context.packageManager).toString(), component.flattenToString()) }.getOrNull()
                            }
                        }
                        handler.post { if (generation == epoch) { frequentItems = items; _status.value = _status.value.copy(frequentAppsSource = data?.optString("source") ?: "常用应用读取失败") } }
                    }
                    binding = false
                    _status.value = _status.value.copy(ready = true, detail = if (navigationReady)
                        "适配器就绪 · 未连接串流时控制手机主屏"
                    else "适配器就绪 · 系统控件导航未启用，请在实验室检查无障碍设置")
                    // An OEM may retain a dead accessibility binding after an
                    // app restart. Remove only our owned entry once, allowing
                    // the settings observer to process it before re-enabling.
                    if (navigationReady) handler.postDelayed({
                        if (generation == epoch && wanted && service === remoteService && !ControllerNavigationService.connected.value) scope.launch {
                            runCatching { remoteService.enableNavigation(false) }
                            handler.postDelayed({
                                if (generation == epoch && wanted && service === remoteService) scope.launch {
                                    val restored = runCatching { remoteService.enableNavigation(true) }.getOrDefault(false)
                                    handler.postDelayed({
                                        if (generation == epoch && wanted && (!restored || !ControllerNavigationService.connected.value))
                                            report("物理输入已就绪；系统控件导航尚未连接，请从实验室检查无障碍设置")
                                    }, 1500)
                                }
                            }, 500)
                        }
                    }, 1500)
                } catch (error: Exception) { disconnect(); report("适配器启动失败：${error.message}") }
            } }
            override fun onServiceDisconnected(name: ComponentName) { handler.post { if (generation == epoch) disconnect() } }
        }
        args = serviceArgs; connection = newConnection
        try { rikka.shizuku.Shizuku.bindUserService(serviceArgs, newConnection) } catch (error: Exception) { disconnect(); report("Shizuku 绑定失败：${error.message}") }
    }
    private fun disconnect() {
        ++epoch; cancelGesture(); cancelWheel()
        remoteKeys.clear()
        heldMouse.keys.toList().forEach(::releaseMouse)
        ControllerNavigationService.hideMouseCursor()
        // destroy() performs stop/ownership cleanup before exiting. Calling
        // both would run all secure-settings commands twice on this handler.
        try { service?.destroy() } catch (_: Exception) {}
        val oldArgs = args; val oldConnection = connection
        if (oldArgs != null && oldConnection != null) try { rikka.shizuku.Shizuku.unbindUserService(oldArgs, oldConnection, true) } catch (_: Exception) {}
        service = null; args = null; connection = null; binding = false
        acquired.clear(); reducers.clear(); virtualIds.clear(); radial.clear()
        _live.value = emptyList()
        reportCount = 0; maxReportDelay = 0; nextReportPublish = 0
        _status.value = _status.value.copy(ready = false, devices = emptyList(), modes = emptyMap(), detail = if (enabled()) "等待输入服务" else "适配器已关闭")
    }
    private fun report(message: String) { _status.value = _status.value.copy(detail = message); SunshineLogManager.log("CONTROLLER", message) }
    private fun <T> callService(fallback: T, remote: IControllerService? = service, operation: (IControllerService) -> T): T {
        if (remote == null) return fallback
        return try { operation(remote) } catch (error: Exception) {
            // A Binder can die between a connection check and any transaction.
            // Never let that race escape a Handler or a root IO coroutine.
            handler.post {
                if (service === remote) {
                    if (error is RemoteException || !remote.asBinder().isBinderAlive) disconnect()
                    report("输入服务调用失败：${error.message}")
                }
            }
            fallback
        }
    }
    private fun publishModes() { _status.value = _status.value.copy(modes = reducers.mapValues { it.value.mode }); publishLive(true) }
    private fun remove(device: String) {
        if (wheelOwner == device) cancelWheel()
        radial.remove(device)
        if (gestureOwner == device) cancelGesture()
        releaseMouse(device)
        reducers.remove(device); acquired.remove(device)
        virtualIds.remove(device)?.let { try { service?.releaseGamepad(it) } catch (_: Exception) {} }
        refreshMouseCursor()
        publishModes()
    }

    /** Native engine only transports state; reducer and injection stay in the service-owning app process. */
    fun remoteState(id: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        if (id !in 0..31) return
        val generation = remoteEpoch
        handler.post { if (generation == remoteEpoch && wanted && enabled()) sample("remote:$id", ControllerSample(buttons, lx, ly, rx, ry, lt, rt)) }
    }
    fun remoteRemoved(id: Int) { val generation = remoteEpoch; handler.post { if (generation == remoteEpoch) remove("remote:$id") } }
    fun releaseRemoteControllers() { ++remoteEpoch; remoteKeys.clear(); handler.post { reducers.keys.filter { it.startsWith("remote:") }.toList().forEach(::remove) } }
    fun remoteKey(key: Int, down: Boolean, modifiers: Int): Boolean {
        val available = wanted && ::context.isInitialized && enabled()
        val default = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> "UP"; KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"; KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"; KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BUTTON_MODE -> "HOME"
            KeyEvent.KEYCODE_MENU -> "START"; KeyEvent.KEYCODE_POWER -> "POWER"
            KeyEvent.KEYCODE_APP_SWITCH -> "X"; KeyEvent.KEYCODE_NOTIFICATION -> "Y"
            else -> null
        }
        val capturing = available && SystemClock.uptimeMillis() < captureUntil
        val custom = available && prefs().contains("binding_KEY:$key")
        // Shift/Ctrl/Alt/Meta shortcuts belong to the original keyboard path.
        // A key's up follows its down even when the modifier is released first.
        if (!remoteKeys.route(key, down, modifiers, available && (default != null || custom || capturing))) return false
        val generation = remoteEpoch
        handler.post { if (generation == remoteEpoch && wanted && enabled() && down)
            token("remote:keys", if (default == null || custom) "KEY:$key" else default) }
        return true
    }
    private fun sample(device: String, value: ControllerSample) {
        val reducer = reducers[device] ?: ControllerReducer().also { created ->
            reducers[device] = created
            publishModes()
        }
        val previous = reducer.sample
        val mouseMode = reducer.mode == ControllerMode.MOUSE
        val tokens = reducer.update(value, SystemClock.uptimeMillis())
        val ring = radial.getOrPut(device) { RadialGesture() }
        val wasRadial = ring.capturesSticks
        updateRadial(device, reducer, ring, SystemClock.uptimeMillis())
        val consumesRadial = wasRadial || ring.capturesSticks || wheelOwner != null
        val changed = previous.buttons xor value.buttons
        ControllerButtons.tokens.forEach { (mask, name) ->
            if (changed and mask != 0) {
                val down = value.buttons and mask != 0
                val operation = when {
                    mouseMode && name in listOf("A", "B") -> "鼠标${if (name == "A") "左" else "右"}键${if (down) "按下" else "松开"}"
                    reducer.mode == ControllerMode.NATIVE && name !in listOf("R3", "HOME") && value.buttons and 16 == 0 -> "游戏原生按键${if (down) "按下" else "松开"}"
                    else -> if (down) "按下" else "松开"
                }
                trace(device, name, operation)
            }
        }
        val leftBefore = ControllerReducer.direction(previous.lx, previous.ly)
        val leftNow = ControllerReducer.direction(value.lx, value.ly)
        if (leftBefore != leftNow) trace(device, "左摇杆", leftNow ?: "回中")
        val rightBefore = ControllerReducer.direction(previous.rx, previous.ry)
        val rightNow = ControllerReducer.direction(value.rx, value.ry)
        if (rightBefore != rightNow) trace(device, "右摇杆", rightNow ?: "回中")
        for ((label, before, after) in listOf(Triple("LT", previous.lt, value.lt), Triple("RT", previous.rt, value.rt))) {
            if (before < .35f && after >= .60f) trace(device, label, "按下")
            else if (before >= .35f && after < .35f) trace(device, label, "松开")
        }
        if (mouseMode && captureUntil <= SystemClock.uptimeMillis() && !consumesRadial) {
            mouseButtons(device, (if (reducer.activeButtons and 4096 != 0) MotionEvent.BUTTON_PRIMARY else 0) or
                (if (reducer.activeButtons and 8192 != 0) MotionEvent.BUTTON_SECONDARY else 0))
        }
        tokens.filterNot { mouseMode && it in listOf("A", "B") || consumesRadial && it in listOf("UP", "DOWN", "LEFT", "RIGHT", "A", "X", "Y") }.forEach { token(device, it) }
        if (reducer.mode == ControllerMode.NATIVE) writeNative(device, reducer.nativeSample())
        publishLive()
        wakeTick()
    }
    private fun writeNative(device: String, value: ControllerSample) {
        val id = virtualIds.getOrPut(device) {
            device.removePrefix("remote:").toIntOrNull() ?: (32..63).firstOrNull { it !in virtualIds.values } ?: return
        }
        val ok = try { service?.gamepad(id, value.buttons, value.lx, value.ly, value.rx, value.ry, value.lt, value.rt) == true } catch (_: Exception) { false }
        if (!ok) {
            setMode(device, ControllerMode.NAVIGATION)
            report("系统拒绝创建游戏手柄，已退回导航模式")
            trace(device, "游戏原生模式", "系统未能创建游戏输入设备，已恢复导航")
        }
    }
    private fun setMode(device: String, mode: ControllerMode) {
        val reducer = reducers[device] ?: return
        if (reducer.mode == mode && device !in virtualIds && device !in heldMouse) return
        cancelGesture(); cancelWheel(); ControllerNavigationService.hideFocus()
        releaseMouse(device)
        if (_status.value.devices.any { it.id == device && it.kind == "remote" } && mode == ControllerMode.NATIVE) return
        if (mode != ControllerMode.NATIVE) virtualIds.remove(device)?.let { id -> callService(Unit) { it.releaseGamepad(id) } }
        if (!device.startsWith("remote:")) {
            if (!callService(false) { it.grab(device, true) }) { report("模式未切换：设备输入接管失败"); return }
            acquired.add(device)
        }
        reducer.setMode(mode); publishModes()
        refreshMouseCursor()
        trace(device, "模式切换", mode.title)
        report("${mode.title}模式 · 同时长按 Start＋Select 约一秒恢复导航")
        if (mode == ControllerMode.NATIVE) writeNative(device, ControllerSample())
        if (reducer.mode == mode) showMode(mode)
    }
    private fun token(device: String, token: String) {
        val now = SystemClock.uptimeMillis()
        _status.value = _status.value.copy(lastInput = token)
        if (now < captureUntil) { _captured.value = token; captureUntil = 0; return }
        val reducer = reducers[device]
        if (wheelOwner != null && token == "B") { cancelWheel(); return }
        if (token == "ESCAPE") { trace(device, token, "恢复系统映射并请求主页"); setMode(device, ControllerMode.NAVIGATION); action(ControllerAction.HOME); return }
        if (token == "SYS_NAVIGATION") { setMode(device, ControllerMode.NAVIGATION); return }
        val reserved = when (token) { "SYS_HOME" -> ControllerAction.HOME; "SYS_BACK" -> ControllerAction.BACK
            "SYS_RECENTS" -> ControllerAction.RECENTS; "SYS_NOTIFICATIONS" -> ControllerAction.NOTIFICATIONS; else -> null }
        if (reserved != null) { trace(device, token, "请求${reserved.title}"); if (reserved == ControllerAction.HOME) setMode(device, ControllerMode.NAVIGATION); action(reserved); return }
        trace(device, token, "触发：${ControllerButtons.action(token, overrides).title}")
        when (val mapped = ControllerButtons.action(token, overrides)) {
            ControllerAction.MOUSE -> setMode(device, if (reducer?.mode == ControllerMode.MOUSE) ControllerMode.NAVIGATION else ControllerMode.MOUSE)
            ControllerAction.NATIVE -> setMode(device, ControllerMode.NATIVE)
            ControllerAction.NAVIGATION -> setMode(device, ControllerMode.NAVIGATION)
            ControllerAction.CONFIRM -> if (reducer?.mode == ControllerMode.MOUSE) click() else action(mapped)
            else -> { if (mapped == ControllerAction.HOME && reducer != null) setMode(device, ControllerMode.NAVIGATION); action(mapped) }
        }
    }
    private fun action(action: ControllerAction) {
        if (action == ControllerAction.HOME || action == ControllerAction.BACK) cancelWheel()
        if (action == ControllerAction.HOME) reducers.keys.toList().forEach { setMode(it, ControllerMode.NAVIGATION) }
        val runtime = SunshineRuntimeManager.getInstance()
        val state = runtime.virtualScreenState.value
        val code = when (action) {
            ControllerAction.CONFIRM -> KeyEvent.KEYCODE_DPAD_CENTER; ControllerAction.BACK -> KeyEvent.KEYCODE_BACK
            ControllerAction.HOME -> KeyEvent.KEYCODE_HOME; ControllerAction.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            ControllerAction.MENU -> KeyEvent.KEYCODE_MENU; ControllerAction.UP -> KeyEvent.KEYCODE_DPAD_UP
            ControllerAction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN; ControllerAction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT; ControllerAction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            ControllerAction.PREVIOUS_MENU -> KeyEvent.KEYCODE_BUTTON_L1; ControllerAction.NEXT_MENU -> KeyEvent.KEYCODE_BUTTON_R1
            else -> null
        }
        if (code != null) {
            if (state.active && state.mode == "desktop" && !state.systemDexMirror && action in listOf(ControllerAction.HOME, ControllerAction.RECENTS)) {
                scope.launch { runtime.virtualDesktopAction(if (action == ControllerAction.HOME) "home" else "tasks") }
            } else {
                val fallback = { runtime.handleKeyboardEvent(code, true, 0); runtime.handleKeyboardEvent(code, false, 0) }
                val navigation = when (action) {
                    ControllerAction.CONFIRM -> "confirm"; ControllerAction.UP -> "up"; ControllerAction.DOWN -> "down"
                    ControllerAction.LEFT -> "left"; ControllerAction.RIGHT -> "right"
                    ControllerAction.PREVIOUS_MENU -> "previous_menu"; ControllerAction.NEXT_MENU -> "next_menu"; else -> null
                }
                val global = when (action) {
                    ControllerAction.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    ControllerAction.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    ControllerAction.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS; else -> null
                }
                if (navigation != null) ControllerNavigationService.navigate(navigation, runtime.sourceDisplayId, fallback)
                else if (global != null) ControllerNavigationService.global(global, runtime.sourceDisplayId, fallback)
                else fallback()
            }
        } else if (action == ControllerAction.POWER) {
            ControllerNavigationService.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                runtime.sourceDisplayId) {
                report(if (runtime.sourceDisplayId != 0) "请退出第二屏桌面，在主屏打开系统电源菜单"
                    else "系统电源菜单需要已连接的无障碍控件导航服务")
            }
        } else if (action == ControllerAction.NOTIFICATIONS) {
            val applied = try { service?.systemAction("notifications", runtime.sourceDisplayId) == true } catch (_: Exception) { false }
            if (!applied && state.active && !state.systemDexMirror) scope.launch { runtime.virtualDesktopAction("quick") }
            else if (!applied) report("当前系统未开放${action.title}，可使用主页键离开应用")
        }
    }
    private fun send(event: MotionEvent, actionButton: Int = 0) {
        val action = event.actionMasked
        val buttons = event.buttonState
        val result = try { service?.motion(event, currentDisplay, actionButton) == true } catch (_: Exception) { false }
        finally { event.recycle() }
        val description = if (result) "已注入至显示屏 $currentDisplay" else "系统拒绝鼠标事件，显示屏 $currentDisplay"
        val buttonEvent = action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE
        if (description != mouseLastResult || action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || buttonEvent) {
            mouseLastResult = description
            _status.value = _status.value.copy(mouseInjection = description)
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || buttonEvent)
                trace("mouse", "鼠标事件", "action=$action button=$actionButton state=$buttons · $description")
        }
    }
    private fun mouse(action: Int, buttons: Int = heldMouse.values.fold(0) { total, value -> total or value }, actionButton: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { x = mouseX; y = mouseY; pressure = if (buttons == 0) 0f else 1f }
        send(MotionEvent.obtain(if (mouseDownAt == 0L) now else mouseDownAt, now, action, 1, arrayOf(properties), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0), actionButton)
        refreshMouseCursor()
    }
    private fun refreshMouseCursor() {
        if (wheelOwner == null && reducers.values.any { it.mode == ControllerMode.MOUSE } && currentDisplay >= 0)
            ControllerNavigationService.showMouseCursor(currentDisplay, mouseX, mouseY)
        else ControllerNavigationService.hideMouseCursor()
    }
    private fun mouseButtons(device: String, buttons: Int) {
        val before = heldMouse.values.fold(0) { total, value -> total or value }
        if (buttons == 0) heldMouse.remove(device) else heldMouse[device] = buttons
        val after = heldMouse.values.fold(0) { total, value -> total or value }
        transitionMouseButtons(before, after)
    }
    private fun transitionMouseButtons(before: Int, after: Int) {
        if (before == 0 && after != 0) mouseDownAt = SystemClock.uptimeMillis()
        MouseButtonTransition.steps(before, after).forEach { step ->
            val action = when (step.action) {
                MouseStepAction.DOWN -> MotionEvent.ACTION_DOWN
                MouseStepAction.MOVE -> MotionEvent.ACTION_MOVE
                MouseStepAction.UP -> MotionEvent.ACTION_UP
                MouseStepAction.HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE
                MouseStepAction.BUTTON_PRESS -> MotionEvent.ACTION_BUTTON_PRESS
                MouseStepAction.BUTTON_RELEASE -> MotionEvent.ACTION_BUTTON_RELEASE
            }
            mouse(action, step.buttons, step.actionButton)
        }
        if (after == 0) mouseDownAt = 0
    }
    private fun releaseMouse(device: String) {
        if (device !in heldMouse) return
        val before = heldMouse.values.fold(0) { total, value -> total or value }
        heldMouse.remove(device)
        val after = heldMouse.values.fold(0) { total, value -> total or value }
        // Match normal release semantics even when changing modes or removing
        // the final device, so mouse consumers receive BUTTON_RELEASE + UP.
        transitionMouseButtons(before, after)
    }
    private fun click() {
        val before = heldMouse.values.fold(0) { total, value -> total or value }
        if (before and MotionEvent.BUTTON_PRIMARY != 0) return
        transitionMouseButtons(before, before or MotionEvent.BUTTON_PRIMARY)
        transitionMouseButtons(before or MotionEvent.BUTTON_PRIMARY, before)
    }
    private fun touch(action: Int) {
        send(MotionEvent.obtain(gestureStart, SystemClock.uptimeMillis(), action, gestureX, gestureY, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })
    }
    private fun cancelGesture() { if (gestureOwner != null) { touch(MotionEvent.ACTION_CANCEL); gestureOwner = null } }
    private fun cancelWheel() {
        val wasOpen = wheelOwner != null
        ++wheelRevision
        radial.values.forEach { it.cancel() }
        if (wheelOwner != null) ControllerNavigationService.hideWheel()
        wheelOwner = null; wheelVisible = false; wheelItems = emptyList()
        if (wasOpen) refreshMouseCursor()
    }
    private fun updateRadial(device: String, reducer: ControllerReducer, ring: RadialGesture, now: Long) {
        val allowed = reducer.mode != ControllerMode.NATIVE && captureUntil <= now && (wheelOwner == null || wheelOwner == device)
        val count = if (wheelOwner == device) wheelItems.size else if (reducer.sample.lt >= .6f) 8 else frequentItems.size
        ring.update(reducer.sample, now, allowed, count).forEach { event -> when (event) {
            is RadialEvent.Open -> {
                cancelGesture(); heldMouse.keys.toList().forEach(::releaseMouse)
                wheelOwner = device; wheelKind = event.kind; wheelVisible = false
                ControllerNavigationService.hideMouseCursor()
                wheelItems = if (event.kind == RadialKind.APPS) frequentItems else listOf(
                    ControllerAction.HOME, ControllerAction.RECENTS, ControllerAction.NOTIFICATIONS, ControllerAction.MOUSE,
                    ControllerAction.NATIVE, ControllerAction.MENU, ControllerAction.POWER, ControllerAction.BACK).map {
                    RadialItem(it.name, when (it) { ControllerAction.NATIVE -> "游戏原生"; ControllerAction.MOUSE -> "鼠标 / 导航"; else -> it.title }) }
                val revision = ++wheelRevision
                trace(device, if (event.kind == RadialKind.APPS) "RT 按下" else "LT 按下", "打开快捷圆环")
                ControllerNavigationService.showWheel(SunshineRuntimeManager.getInstance().sourceDisplayId,
                    if (event.kind == RadialKind.APPS) "常用应用" else "快捷操作", wheelItems,
                    if (event.kind == RadialKind.APPS) _status.value.frequentAppsSource else "左 / 右摇杆选择 · 松开扳机触发") { shown -> handler.post {
                        if (revision == wheelRevision) { wheelVisible = shown; if (!shown) { report("圆环需要系统控件导航服务，请在实验室检查无障碍状态"); cancelWheel() } }
                    } }
            }
            is RadialEvent.Select -> { ControllerNavigationService.selectWheel(event.index); wheelItems.getOrNull(event.index)?.let { trace(device, "圆环选择", it.label) } }
            is RadialEvent.Release -> {
                val selected = wheelItems.getOrNull(event.index).takeIf { wheelVisible }
                cancelWheel(); reducer.suspendSticks()
                if (selected != null) {
                    trace(device, "松开扳机", "触发：${selected.label}")
                    if (selected.component != null) {
                        val target = SunshineRuntimeManager.getInstance().sourceDisplayId
                        val generation = epoch
                        val remote = service
                        scope.launch {
                            val opened = callService(false, remote) { it.launchApp(selected.component, target) }
                            handler.post { if (generation == epoch && wanted && !opened) report("应用未能打开：${selected.label}") }
                        }
                    } else when (val action = ControllerAction.valueOf(selected.id)) {
                        ControllerAction.MOUSE -> setMode(device, if (reducer.mode == ControllerMode.MOUSE) ControllerMode.NAVIGATION else ControllerMode.MOUSE)
                        ControllerAction.NATIVE -> setMode(device, ControllerMode.NATIVE)
                        else -> action(action)
                    }
                }
            }
        } }
    }
    private fun wakeTick() { handler.removeCallbacks(tick); handler.post(tick) }
    private val tick = object : Runnable {
        override fun run() {
            if (!wanted) return
            try {
                val now = SystemClock.uptimeMillis()
                if (binding && now - bindingAt > 8000) { disconnect(); report("输入服务绑定超时，稍后自动重试") }
                if (service == null && !binding) connect()
                val display = SunshineRuntimeManager.getInstance().sourceDisplayId
                if (currentDisplay != display || now >= nextMetrics) {
                    val changedDisplay = currentDisplay != display
                    if (changedDisplay) { cancelGesture(); cancelWheel(); heldMouse.keys.toList().forEach(::releaseMouse); ControllerNavigationService.hideMouseCursor() }
                    currentDisplay = display; nextMetrics = now + 1000
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(display)?.getRealSize(size)
                    if (mouseX <= 0 || mouseX >= size.x || mouseY <= 0 || mouseY >= size.y) { mouseX = size.x / 2f; mouseY = size.y / 2f }
                    if (changedDisplay) refreshMouseCursor()
                }
                reducers.toMap().forEach { (device, reducer) ->
                    val ring = radial.getOrPut(device) { RadialGesture() }
                    updateRadial(device, reducer, ring, now)
                    reducer.tick(now).filter { it == "ESCAPE" || !ring.capturesSticks && !reducer.sticksSuspended }.forEach { token(device, it) }
                    if (ring.capturesSticks || reducer.sticksSuspended) return@forEach
                    if (captureUntil > now) return@forEach
                    val sample = reducer.sample
                    if (reducer.mode == ControllerMode.MOUSE && maxOf(abs(sample.lx), abs(sample.ly)) > .18f) {
                        mouseX = (mouseX + sample.lx * size.x * .013f).coerceIn(0f, size.x - 1f)
                        mouseY = (mouseY + sample.ly * size.y * .013f).coerceIn(0f, size.y - 1f)
                        mouse(if (heldMouse.isEmpty()) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_MOVE)
                    }
                    if (reducer.mode != ControllerMode.NATIVE && maxOf(abs(sample.rx), abs(sample.ry)) > .25f && (gestureOwner == null || gestureOwner == device)) {
                        if (gestureOwner == null) { gestureOwner = device; gestureStart = now; gestureX = size.x * .5f; gestureY = size.y * .5f; touch(MotionEvent.ACTION_DOWN) }
                        gestureX = (gestureX + sample.rx * size.x * .018f).coerceIn(size.x * .12f, size.x * .88f)
                        gestureY = (gestureY + sample.ry * size.y * .018f).coerceIn(size.y * .12f, size.y * .88f)
                        touch(MotionEvent.ACTION_MOVE)
                        if (now - gestureStart > 280) { touch(MotionEvent.ACTION_UP); gestureOwner = null }
                    } else if (gestureOwner == device) { touch(MotionEvent.ACTION_UP); gestureOwner = null }
                }
            } catch (error: Exception) { report("输入服务暂不可用：${error.message}"); disconnect() }
            if (wanted) handler.postDelayed(this, if (reducers.any { (id, reducer) -> radial[id]?.capturesSticks != true && reducer.needsTick() } || radial.values.any { it.pending }) 16 else 1000)
        }
    }
}
