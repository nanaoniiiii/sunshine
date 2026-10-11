package com.alkaidlab.sunshine.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    DASHBOARD("仪表盘", Icons.Default.Dashboard),
    PAIRING("配对设备", Icons.Default.VpnKey),
    APPS("应用", Icons.Default.Apps),
    SETTINGS("设置", Icons.Default.Tune),
    LOGS("日志", Icons.Default.Terminal)
}

class MainActivity : ComponentActivity() {

    private val viewModel: SunshineViewModel by viewModels()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SunshineService.start(this, result.data!!)
            viewModel.checkSystemPermissions()
            if (intent?.getBooleanExtra("auto_restart", false) == true) {
                moveTaskToBack(true)
            }
        }
    }

    private val generalPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.checkSystemPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val autoData = intent?.getParcelableExtra<Intent>(SunshineService.EXTRA_RESULT_DATA)
        if (autoData != null) {
            SunshineService.start(this, autoData)
        } else {
            SunshineService.start(this)
        }

        if (com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance().getMediaProjection() == null && autoData == null) {
            requestMediaProjection()
        } else if (intent?.getBooleanExtra("auto_restart", false) == true) {
            moveTaskToBack(true)
        }

        setContent {
            SunshineTheme {
                MainContent(
                    viewModel = viewModel,
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
        val autoData = intent?.getParcelableExtra<Intent>(SunshineService.EXTRA_RESULT_DATA)
        if (autoData != null) {
            SunshineService.start(this, autoData)
        }
        if (com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance().getMediaProjection() == null && autoData == null) {
            requestMediaProjection()
        } else if (intent?.getBooleanExtra("auto_restart", false) == true) {
            moveTaskToBack(true)
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
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(1001)
                }
            }
        } catch (_: Exception) {}
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
    onRequestBatteryOptimization: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.DASHBOARD) }
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = DarkBg,
                tonalElevation = 8.dp
            ) {
                NavigationTab.values().forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, fontSize = 11.sp) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DarkBg,
                            selectedTextColor = PrimaryCyan,
                            indicatorColor = PrimaryCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBg)
        ) {
            when (selectedTab) {
                NavigationTab.DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    onRequestMediaProjection = onRequestMediaProjection,
                    onRequestShizuku = onRequestShizuku,
                    onRequestBatteryOptimization = onRequestBatteryOptimization
                )
                NavigationTab.PAIRING -> PairingScreen(viewModel = viewModel)
                NavigationTab.APPS -> AppsScreen(viewModel = viewModel)
                NavigationTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                NavigationTab.LOGS -> LogsScreen(viewModel = viewModel)
            }
        }
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
                    OutlinedTextField(
                        value = pinDialogInput,
                        onValueChange = { if (it.length <= 4) pinDialogInput = it },
                        placeholder = { Text("4 位 PIN 码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinDialogInput.isNotBlank()) {
                            viewModel.submitPin(pinDialogInput)
                            pinDialogInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("确认配对", color = DarkBg, fontWeight = FontWeight.Bold)
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
