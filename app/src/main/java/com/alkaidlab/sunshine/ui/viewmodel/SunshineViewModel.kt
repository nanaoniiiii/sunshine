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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    val apps: StateFlow<List<AppItem>> = _apps.asStateFlow()

    private val _clients = MutableStateFlow<List<ClientItem>>(emptyList())
    val clients: StateFlow<List<ClientItem>> = _clients.asStateFlow()

    val runtimeSessions: StateFlow<List<RuntimeSession>> = runtime.activeSessionsFlow

    val perf: StateFlow<PerfResponse> = runtime.perfFlow

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

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
            while (isActive) {
                checkSystemPermissions()
                if (_isServiceRunning.value) {
                    if (_config.value == null) {
                        fetchConfig()
                    }
                    fetchRuntimeData()
                }
                delay(2000)
            }
        }
    }

    fun fetchAll() {
        viewModelScope.launch(Dispatchers.IO) {
            fetchConfig()
            fetchApps()
            fetchClients()
            fetchRuntimeData()
            fetchLogs()
        }
    }

    suspend fun fetchConfig() {
        try {
            val resp = api.getConfig()
            if (resp.isSuccessful) {
                _config.value = resp.body()
            }
        } catch (_: Exception) {}
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

    private suspend fun fetchRuntimeData() {
        // Active sessions and performance metrics are bound reactively in real time
        // via runtime.activeSessionsFlow and runtime.perfFlow.
        // We also query the REST API endpoint to keep remote clients synchronized:
        try {
            api.getRuntimeSessions()
            api.getPerfCurrent()
        } catch (_: Exception) {}
    }

    fun fetchLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.getLogs()
                if (resp.isSuccessful) {
                    _logs.value = resp.body()?.logs ?: emptyList()
                } else {
                    _logs.value = SunshineLogManager.getRecentLogs()
                }
            } catch (_: Exception) {
                _logs.value = SunshineLogManager.getRecentLogs()
            }
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.saveConfig(map)
                if (resp.isSuccessful) {
                    _statusMessage.value = "配置保存成功"
                    fetchConfig()
                } else {
                    _statusMessage.value = "保存失败: ${resp.message()}"
                }
            } catch (e: Exception) {
                _statusMessage.value = "保存异常: ${e.message}"
            }
        }
    }

    fun addApp(appItem: AppItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.saveApp(appItem)
                if (resp.isSuccessful) {
                    _statusMessage.value = "应用添加成功"
                    fetchApps()
                }
            } catch (e: Exception) {
                _statusMessage.value = "添加应用失败: ${e.message}"
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
                api.restartServer()
                _statusMessage.value = "服务正在重启..."
            } catch (_: Exception) {}
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
