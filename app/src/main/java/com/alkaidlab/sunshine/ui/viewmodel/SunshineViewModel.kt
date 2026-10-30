package com.alkaidlab.sunshine.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alkaidlab.sunshine.api.SunshineApiClient
import com.alkaidlab.sunshine.data.*
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.alkaidlab.sunshine.service.SunshineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

class SunshineViewModel(application: Application) : AndroidViewModel(application) {

    private val api = SunshineApiClient.service
    private val runtime = SunshineRuntimeManager.getInstance()

    // Server & Network Status
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _serviceIp = MutableStateFlow("127.0.0.1")
    val serviceIp: StateFlow<String> = _serviceIp.asStateFlow()

    private val _serviceVpnIp = MutableStateFlow<String?>(null)
    val serviceVpnIp: StateFlow<String?> = _serviceVpnIp.asStateFlow()

    private val _serviceIpv6 = MutableStateFlow<String?>(null)
    val serviceIpv6: StateFlow<String?> = _serviceIpv6.asStateFlow()

    // Real Data Models
    private val _config = MutableStateFlow<ConfigResponse?>(null)
    val config: StateFlow<ConfigResponse?> = _config.asStateFlow()
    private val _settingsDraft = MutableStateFlow<SettingsDraft?>(null)
    val settingsDraft = _settingsDraft.asStateFlow()
    private val _configSaving = MutableStateFlow(false)
    val configSaving = _configSaving.asStateFlow()
    private val _settingsSaveError = MutableStateFlow<String?>(null)
    val settingsSaveError = _settingsSaveError.asStateFlow()
    private val settingsAutosave = SettingsAutosave()
    private val settingsChanges = Channel<Unit>(Channel.CONFLATED)
    private val configMutex = Mutex()

    fun editSetting(key: String, value: String) {
        applySettingsSnapshot(settingsAutosave.edit(key, value))
        _settingsSaveError.value = null
        if (key !in SettingsAutosave.numericKeys) settingsChanges.trySend(Unit)
    }

    fun finishSettingEdit(key: String) {
        settingsAutosave.finishEdit(key)
        settingsChanges.trySend(Unit)
    }

    fun retrySettingsSave() {
        settingsChanges.trySend(Unit)
    }

    private fun applySettingsSnapshot(snapshot: SettingsAutosave.Snapshot) {
        _config.value = snapshot.confirmed
        _settingsDraft.value = snapshot.draft
        if (!settingsAutosave.hasUnsavedEdits()) _settingsSaveError.value = null
    }

    private fun startSettingsAutosave() {
        viewModelScope.launch {
            while (isActive) {
                settingsChanges.receive()
                persistSettings()
            }
        }
    }

    private suspend fun persistSettings(): Boolean = configMutex.withLock {
        val batch = settingsAutosave.nextBatch() ?: return@withLock true
        _configSaving.value = true
        try {
            val response = api.saveConfig(batch.values)
            if (!response.isSuccessful || response.body()?.status != "true") {
                _settingsSaveError.value = "自动保存失败，当前输入已保留。请重试。"
                return@withLock false
            }
            val confirmed = api.getConfig().takeIf { it.isSuccessful }?.body()
            if (confirmed == null) {
                _settingsSaveError.value = "设置已提交，暂时无法确认保存结果。请重试。"
                return@withLock false
            }
            applySettingsSnapshot(settingsAutosave.acknowledged(batch, confirmed))
            _settingsSaveError.value = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SunshineLogManager.log("CONFIG_SAVE_ERR", "Cannot save settings: ${error.message}")
            _settingsSaveError.value = "自动保存失败，当前输入已保留。请确认服务运行后重试。"
            false
        } finally {
            _configSaving.value = false
        }
    }

    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    val apps: StateFlow<List<AppItem>> = _apps.asStateFlow()

    data class InstalledApp(val label: String, val packageName: String)
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps = _installedApps.asStateFlow()
    private val _appsLoading = MutableStateFlow(false)
    val appsLoading = _appsLoading.asStateFlow()
    private val _installedAppsError = MutableStateFlow<String?>(null)
    val installedAppsError = _installedAppsError.asStateFlow()
    private val _appSaving = MutableStateFlow(false)
    val appSaving = _appSaving.asStateFlow()
    fun loadInstalledApps() {
        if (!_appsLoading.compareAndSet(false, true)) return
        _installedAppsError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = getApplication<Application>().packageManager
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                _installedApps.value = pm.queryIntentActivities(intent, 0).map {
                    InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName)
                }.distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            } catch (_: Exception) { _installedAppsError.value = "无法读取本机应用列表，请重试" }
            finally { _appsLoading.value = false }
        }
    }

    private val _clients = MutableStateFlow<List<ClientItem>>(emptyList())
    val clients: StateFlow<List<ClientItem>> = _clients.asStateFlow()

    val runtimeSessions: StateFlow<List<RuntimeSession>> = runtime.activeSessionsFlow
    val fullscreenBusy = runtime.fullscreenBusy
    val streamDisplayMode = runtime.streamDisplayMode
    private val _serviceStartup = MutableStateFlow(com.alkaidlab.sunshine.service.ServiceStartupSettings.read(application))
    val serviceStartup = _serviceStartup.asStateFlow()
    private val _instantConnectSaving = MutableStateFlow(false)
    val instantConnectSaving = _instantConnectSaving.asStateFlow()
    private val _instantConnectError = MutableStateFlow<String?>(null)
    val instantConnectError = _instantConnectError.asStateFlow()
    val captureStartupStatus = com.alkaidlab.sunshine.service.BootCaptureBootstrap.status

    fun setInstantConnect(enabled: Boolean) {
        if (_instantConnectSaving.value) return
        _instantConnectSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _instantConnectError.value = null
                if (enabled) com.alkaidlab.sunshine.service.BootCaptureBootstrap.configure(getApplication())
                val previous = _serviceStartup.value
                val next = previous.copy(startOnBoot = enabled, keepAlive = if (enabled) true else previous.keepAlive,
                    instantConnect = enabled)
                check(next.save(getApplication())) { "启动设置保存失败，请重试" }
                _serviceStartup.value = next
                if (enabled || SunshineService.isRunning)
                    SunshineService.start(getApplication(), automaticCapture = enabled)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _instantConnectError.value = error.message ?: "设置失败，请重试"
            } finally { _instantConnectSaving.value = false }
        }
    }

    fun setServiceStartup(startOnBoot: Boolean? = null, keepAlive: Boolean? = null) {
        if (_instantConnectSaving.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val previous = _serviceStartup.value
            val updated = previous.copy(startOnBoot = startOnBoot ?: previous.startOnBoot, keepAlive = keepAlive ?: previous.keepAlive)
            val next = if (!updated.startOnBoot || !updated.keepAlive) updated.copy(instantConnect = false) else updated
            if (next.save(getApplication())) {
                _serviceStartup.value = next
                // Deliver a fresh start command so Android records the new sticky
                // policy immediately, without stopping capture or active sessions.
                if (SunshineService.isRunning || next.startOnBoot) try {
                    SunshineService.start(getApplication())
                } catch (_: RuntimeException) { _statusMessage.value = "设置已保存，请从首页开启 Sunshine 服务" }
            } else _statusMessage.value = "启动设置保存失败，请重试"
        }
    }
    fun setStreamDisplayMode(mode: com.alkaidlab.sunshine.service.StreamDisplayMode) {
        viewModelScope.launch { _statusMessage.value = runtime.setStreamDisplayMode(mode).message }
    }
    val virtualScreenState = runtime.virtualScreenState
    fun toggleVirtualScreen() {
        viewModelScope.launch {
            _statusMessage.value = if (runtime.virtualScreenState.value.active) runtime.exitVirtualScreen().message
                else runtime.createVirtualScreen().message
        }
    }
    fun setVirtualScreenPanelOff(off: Boolean) {
        viewModelScope.launch { _statusMessage.value = runtime.setVirtualScreenPanelOff(off).message }
    }
    fun fullscreenSession(sessionId: Long) {
        viewModelScope.launch {
            _statusMessage.value = runtime.fullscreenSession(sessionId).message
        }
    }

    val perf: StateFlow<PerfResponse> = runtime.perfFlow
    private val _diagnostics = MutableStateFlow<DiagnosticsResponse?>(null)
    val diagnostics: StateFlow<DiagnosticsResponse?> = _diagnostics.asStateFlow()

    val logs: StateFlow<List<String>> = SunshineLogManager.logsFlow

    // Permission and System States
    private val _isMediaProjectionGranted = MutableStateFlow(false)
    val isMediaProjectionGranted: StateFlow<Boolean> = _isMediaProjectionGranted.asStateFlow()

    private val _isShizukuGranted = MutableStateFlow(false)
    val isShizukuGranted: StateFlow<Boolean> = _isShizukuGranted.asStateFlow()

    private val _isAudioRecordGranted = MutableStateFlow(false)
    val isAudioRecordGranted: StateFlow<Boolean> = _isAudioRecordGranted.asStateFlow()

    private val _isBatteryOptimizedIgnored = MutableStateFlow(false)
    val isBatteryOptimizedIgnored: StateFlow<Boolean> = _isBatteryOptimizedIgnored.asStateFlow()

    // Action Status Message
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val encoderError = runtime.encoderError
    fun dismissEncoderError() = runtime.dismissEncoderError()
    val pinRequested = runtime.pinRequested

    init {
        startSettingsAutosave()
        startPolling()
    }

    fun checkSystemPermissions() {
        val app = getApplication<Application>()
        _isMediaProjectionGranted.value = runtime.getMediaProjection() != null
        _isAudioRecordGranted.value = ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        try {
            _isShizukuGranted.value = Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            _isShizukuGranted.value = false
        }

        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        _isBatteryOptimizedIgnored.value = pm?.isIgnoringBatteryOptimizations(app.packageName) ?: false

        val net = SunshineService.refreshNetworkAddresses()
        _isServiceRunning.value = SunshineService.isRunning
        _serviceIp.value = net.lanIp
        _serviceVpnIp.value = net.vpnIp
        _serviceIpv6.value = net.ipv6
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                checkSystemPermissions()
                if (_isServiceRunning.value) {
                    if (_config.value == null || tick % 3 == 0) {
                        fetchConfig()
                    }
                    fetchClients()
                    if (runtime.isStreaming.value) {
                        try { _diagnostics.value = api.getDiagnostics().body() } catch (_: Exception) {}
                    } else _diagnostics.value = null
                }
                tick++
                delay(2000)
            }
        }
    }

    fun fetchAll() {
        viewModelScope.launch(Dispatchers.IO) {
            fetchConfig()
            fetchApps()
            fetchClients()
        }
    }

    suspend fun fetchConfig(): ConfigResponse? = configMutex.withLock {
        try {
            val resp = api.getConfig()
            if (resp.isSuccessful) {
                resp.body()?.let { current ->
                    withContext(Dispatchers.Main) {
                        applySettingsSnapshot(settingsAutosave.refreshed(current))
                    }
                    return@withLock current
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { SunshineLogManager.log("CONFIG_READ_ERR", "Cannot load settings: ${e.message}") }
        null
    }

    suspend fun fetchApps() {
        try {
            val resp = api.getApps()
            if (resp.isSuccessful) {
                _apps.value = resp.body()?.apps ?: emptyList()
            }
        } catch (_: Exception) {}
    }

    suspend fun fetchClients() {
        try {
            val resp = api.getClients()
            if (resp.isSuccessful) {
                _clients.value = resp.body()?.namedCerts ?: emptyList()
            }
        } catch (_: Exception) {}
    }

    fun fetchLogs() {
        // Logs are now reactively streamed via SunshineLogManager.logsFlow
    }

    fun submitPin(pin: String) {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4 || !cleanPin.all { it.isDigit() }) {
            _statusMessage.value = "PIN 码必须为 4 位纯数字"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runtime.submitPin(cleanPin)
            if (ok) {
                _statusMessage.value = "PIN 配对码已提交至引擎"
                fetchClients()
            } else {
                _statusMessage.value = "PIN 提交失败: 配对会话不存在或已超时"
            }
        }
    }

    fun dismissPinRequest() {
        runtime.dismissPinRequest()
    }

    fun addApp(appItem: AppItem, onSaved: (Boolean) -> Unit = {}) {
        if (!_appSaving.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            var saved = false
            try {
                val resp = api.saveApp(appItem)
                saved = resp.isSuccessful
                if (saved) { _statusMessage.value = "应用入口已保存"; fetchApps() }
                else _statusMessage.value = "保存应用失败，请重试"
            } catch (_: Exception) { _statusMessage.value = "无法保存应用，请确认服务运行后重试" }
            finally {
                _appSaving.value = false
                withContext(Dispatchers.Main) { onSaved(saved) }
            }
        }
    }

    fun launchApp(appItem: AppItem) {
        val cmd = appItem.cmd?.trim() ?: ""
        if (cmd.isEmpty()) {
            _statusMessage.value = "已处于全局桌面镜像模式"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runtime.launchAppByCommand(cmd)
            if (ok) {
                _statusMessage.value = "已启动应用: ${appItem.name}"
            } else {
                _statusMessage.value = "无法启动 ${appItem.name}: 未找到对应包名 ($cmd)"
            }
        }
    }

    fun deleteApp(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.deleteApp(name)
                if (resp.isSuccessful) {
                    _statusMessage.value = "应用删除成功"
                    fetchApps()
                }
            } catch (e: Exception) {
                _statusMessage.value = "删除失败: ${e.message}"
            }
        }
    }

    fun renameClient(uuid: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.renameClient(RenameClientRequest(uuid, newName))
                if (resp.isSuccessful) {
                    _statusMessage.value = "重命名成功"
                    fetchClients()
                }
            } catch (e: Exception) {
                _statusMessage.value = "重命名失败: ${e.message}"
            }
        }
    }

    fun unpairClient(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.unpairClient(UnpairClientRequest(uuid))
                if (resp.isSuccessful) {
                    _statusMessage.value = "已解除设备配对"
                    fetchClients()
                }
            } catch (e: Exception) {
                _statusMessage.value = "解绑失败: ${e.message}"
            }
        }
    }

    fun unpairAll() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.unpairAll()
                if (resp.isSuccessful) {
                    _statusMessage.value = "已解绑所有设备"
                    fetchClients()
                }
            } catch (e: Exception) {
                _statusMessage.value = "解绑失败: ${e.message}"
            }
        }
    }

    fun restartServer() {
        viewModelScope.launch {
            if (!persistSettings() || settingsAutosave.hasUnsavedEdits()) {
                _statusMessage.value = "请先修正未保存的设置或重试自动保存"
                return@launch
            }
            try {
                val response = api.restartServer()
                _statusMessage.value = response.body()?.message ?: "重启请求失败"
            } catch (_: Exception) {}
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
