package com.alkaidlab.sunshine.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.alkaidlab.sunshine.ui.components.PinField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkaidlab.sunshine.service.SunshineService
import com.alkaidlab.sunshine.ui.screens.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel
import rikka.shizuku.Shizuku

enum class NavigationTab(val title: String, val icon: ImageVector) {
    DASHBOARD("首页", Icons.Default.Dashboard),
    PAIRING("设备", Icons.Default.VpnKey),
    APPS("应用", Icons.Default.Apps),
    SETTINGS("设置", Icons.Default.Tune)
}

class MainActivity : ComponentActivity() {

    private val viewModel: SunshineViewModel by viewModels()
    private var projectionRequestPending = false
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 1001) {
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance().initShizukuInput()
            }
            viewModel.checkSystemPermissions()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        projectionRequestPending = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SunshineService.start(this, result.data!!)
            viewModel.checkSystemPermissions()
        }
    }

    private val generalPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.checkSystemPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Request basic permissions
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(android.Manifest.permission.RECORD_AUDIO)
        generalPermissionsLauncher.launch(permissions.toTypedArray())

        ensureAppOpsPermissions()
        SunshineService.start(this)
        if (com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance().getMediaProjection() == null) {
            requestMediaProjection()
        }

        setContent {
            val systemDark = isSystemInDarkTheme()
            val themePreferences = remember { getSharedPreferences("appearance", Context.MODE_PRIVATE) }
            var themeOverride by rememberSaveable { mutableStateOf<Boolean?>(
                when (themePreferences.getString("theme", "system")) { "dark" -> true; "light" -> false; else -> null }) }
            val darkTheme = themeOverride ?: systemDark
            SunshineTheme(darkTheme = darkTheme) {
                MainContent(
                    viewModel = viewModel,
                    darkTheme = darkTheme,
                    onToggleTheme = { themeOverride = !darkTheme; themePreferences.edit().putString("theme", if (!darkTheme) "dark" else "light").apply() },
                    onFollowSystemTheme = { themeOverride = null; themePreferences.edit().putString("theme", "system").apply() },
                    onRequestMediaProjection = { requestMediaProjection() },
                    onRequestShizuku = { requestShizukuPermission() },
                    onRequestBatteryOptimization = { requestIgnoreBatteryOptimization() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reopening the UI must not recreate capture or restart an active engine.
        SunshineService.start(this)
        if (com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance().getMediaProjection() == null) {
            requestMediaProjection()
        }
    }

    override fun onResume() {
        super.onResume()
        ensureAppOpsPermissions()
        viewModel.checkSystemPermissions()
        viewModel.fetchAll()
    }

    private fun ensureAppOpsPermissions() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val cmd = "appops set $packageName PROJECT_MEDIA allow && appops set $packageName SYSTEM_ALERT_WINDOW allow && dumpsys deviceidle whitelist +$packageName && appops set $packageName RUN_IN_BACKGROUND allow"
                val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? java.lang.Process
                p?.waitFor()
            }
        } catch (_: Throwable) {}
    }

    private fun requestMediaProjection() {
        if (projectionRequestPending) return
        projectionRequestPending = true
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(1001)
                } else {
                    com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance().initShizukuInput()
                    viewModel.checkSystemPermissions()
                }
            } else {
                android.widget.Toast.makeText(this, "Shizuku 服务尚未连接，请先在 Shizuku 中启动服务", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }
}

@Composable
fun MainContent(
    viewModel: SunshineViewModel,
    onRequestMediaProjection: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    darkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onFollowSystemTheme: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.DASHBOARD) }
    var settingsPage by rememberSaveable { mutableStateOf("settings") }
    BackHandler(enabled = selectedTab == NavigationTab.SETTINGS && settingsPage != "settings") { settingsPage = "settings" }
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pageStates = rememberSaveableStateHolder()
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, bottomBar = {
            if (!wide) NavigationBar(containerColor = CardBg, tonalElevation = 0.dp) {
                NavigationTab.values().forEach { tab ->
                    NavigationBarItem(selected = selectedTab == tab, onClick = { selectedTab = tab; settingsPage = "settings" },
                        icon = { Icon(tab.icon, null) }, label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = PrimaryCyan, indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary))
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail(containerColor = CardBg) {
                    Spacer(Modifier.height(24.dp))
                    NavigationTab.values().forEach { tab ->
                        NavigationRailItem(selected = selectedTab == tab, onClick = { selectedTab = tab; settingsPage = "settings" },
                            icon = { Icon(tab.icon, null) }, label = { Text(tab.title) },
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                        pageStates.SaveableStateProvider(if (selectedTab == NavigationTab.SETTINGS) settingsPage else selectedTab.name) {
                            when (selectedTab) {
                                NavigationTab.DASHBOARD -> DashboardScreen(viewModel, onRequestMediaProjection,
                                    onRequestShizuku, onRequestBatteryOptimization, onOpenPairing = { selectedTab = NavigationTab.PAIRING }, onToggleTheme = onToggleTheme, darkTheme = darkTheme)
                                NavigationTab.PAIRING -> PairingScreen(viewModel)
                                NavigationTab.APPS -> AppsScreen(viewModel)
                                NavigationTab.SETTINGS -> when (settingsPage) {
                                    "logs" -> LogsScreen(viewModel, onBack = { settingsPage = "settings" })
                                    "faq" -> FaqScreen(onBack = { settingsPage = "settings" })
                                    else -> SettingsScreen(viewModel, onOpenLogs = { settingsPage = "logs" },
                                        onOpenFaq = { settingsPage = "faq" }, onFollowSystemTheme = onFollowSystemTheme)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val encoderError by viewModel.encoderError.collectAsState()
    if (encoderError != null) {
        AlertDialog(onDismissRequest = { viewModel.dismissEncoderError() },
            title = { Text("编码器启动或运行失败") },
            text = { Text(encoderError.orEmpty()) },
            confirmButton = { TextButton(onClick = { viewModel.dismissEncoderError(); selectedTab = NavigationTab.SETTINGS }) { Text("切换编码器") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissEncoderError() }) { Text("关闭") } })
    }

    val pinRequested by viewModel.pinRequested.collectAsState()
    var pinDialogInput by remember { mutableStateOf("") }

    if (pinRequested != null) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissPinRequest()
                pinDialogInput = ""
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = PrimaryCyan)
                    Text("Moonlight 配对请求", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "检测到 Moonlight 客户端正在发起配对连接。请输入客户端屏幕上显示的 4 位数字配对码：",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    PinField(pinDialogInput, { pinDialogInput = it }, Modifier.fillMaxWidth()) {
                        viewModel.submitPin(pinDialogInput)
                        pinDialogInput = ""
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinDialogInput.length == 4) {
                            viewModel.submitPin(pinDialogInput)
                            pinDialogInput = ""
                        }
                    },
                    enabled = pinDialogInput.length == 4
                ) {
                    Text("确认配对")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissPinRequest()
                    pinDialogInput = ""
                }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardBg
        )
    }
}
