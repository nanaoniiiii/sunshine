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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun editSetting(key: String, value: String) {
        if (!_configSaving.value) _settingsDraft.update { it?.withValue(key, value) }
    }

    fun discardSettingsDraft() {
        if (!_configSaving.value) _config.value?.let { _settingsDraft.value = SettingsDraft.from(it) }
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

    suspend fun fetchConfig(): ConfigResponse? {
        try {
            val resp = api.getConfig()
            if (resp.isSuccessful) {
                resp.body()?.let { current ->
                    val previous = _config.value
                    _settingsDraft.update { SettingsDraft.reconcile(it, previous, current) }
                    _config.value = current
                    return current
                }
            }
        } catch (e: Exception) { SunshineLogManager.log("CONFIG_READ_ERR", "Cannot load settings: ${e.message}") }
        return null
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

    fun saveConfig(map: Map<String, String>) {
        if (!_configSaving.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.saveConfig(map)
                if (resp.isSuccessful) {
                    _statusMessage.value = resp.body()?.message ?: "配置保存成功"
                    val confirmed = fetchConfig()
                    // Inputs are disabled during save. Reconcile to the confirmed server values.
                    confirmed?.let { _settingsDraft.value = SettingsDraft.from(it) }
                } else {
                    _statusMessage.value = "保存失败: ${resp.errorBody()?.string() ?: resp.message()}"
                }
            } catch (e: Exception) {
                _statusMessage.value = "保存异常: ${e.message}"
            } finally {
                _configSaving.value = false
            }
        }
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
        viewModelScope.launch(Dispatchers.IO) {
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
