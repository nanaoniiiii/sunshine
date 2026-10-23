package com.alkaidlab.sunshine.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.drawable.toBitmap
import com.alkaidlab.sunshine.controller.ControllerAdapter
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.alkaidlab.sunshine.ui.desktop.DesktopArtwork
import com.alkaidlab.sunshine.ui.desktop.DesktopArtworkCache
import com.alkaidlab.sunshine.ui.desktop.DesktopBackground
import com.alkaidlab.sunshine.ui.desktop.desktopFocus
import com.alkaidlab.sunshine.ui.theme.SunshineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Temporary HOME for the owned ordinary secondary display; OEM DeX HOME is untouched. */
class VirtualDesktopActivity : ComponentActivity() {
    private val trustedKeyguard = com.alkaidlab.sunshine.service.TrustedKeyguardDismissal()
    companion object { @Volatile var foregroundDisplayId = -1; private set }
    override fun onResume() { super.onResume(); trustedKeyguard.onForeground(this); @Suppress("DEPRECATION") foregroundDisplayId = windowManager.defaultDisplay.displayId }
    override fun onPause() { foregroundDisplayId = -1; super.onPause() }
    private var desktopWindowFocused by mutableStateOf(false)
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); desktopWindowFocused = hasFocus; if (hasFocus) trustedKeyguard.onForeground(this) }
    private var page by mutableStateOf("apps")
    private var navigationRevision by mutableIntStateOf(0)
    private fun onRequestedDisplay(intent: Intent): Boolean {
        val expected = intent.getIntExtra("desktop_display_id", -1)
        @Suppress("DEPRECATION") val actual = windowManager.defaultDisplay.displayId
        if (actual == 0 || (expected >= 0 && expected != actual)) { finish(); return false }
        return true
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!onRequestedDisplay(intent)) return
        setIntent(intent); page = intent.getStringExtra("desktop_page") ?: "apps"; navigationRevision++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!onRequestedDisplay(intent)) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        page = intent.getStringExtra("desktop_page") ?: "apps"
        setContent { SunshineTheme(darkTheme = true) { Desktop() } }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable private fun Desktop() {
        val runtime = SunshineRuntimeManager.getInstance()
        val state by runtime.virtualScreenState.collectAsState()
        val adapter by ControllerAdapter.status.collectAsState()
        var joinedSession by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val inputModeManager = LocalInputModeManager.current
        val snacks = remember { SnackbarHostState() }
        var apps by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
        var tasks by remember { mutableStateOf<List<Pair<Int, ComponentName>>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var search by remember { mutableStateOf(false) }
        var refresh by remember { mutableIntStateOf(0) }
        var selected by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var clock by remember { mutableStateOf("") }
        val rail = androidx.compose.foundation.lazy.rememberLazyListState()
        val focus = remember { mutableMapOf<String, FocusRequester>() }
        val playFocus = remember { FocusRequester() }
        val moreFocus = remember { FocusRequester() }
        val exitFocus = remember { FocusRequester() }
        val quickFocus = remember { List(5) { FocusRequester() } }
        val taskFocus = remember { mutableMapOf<Int, FocusRequester>() }
        val taskGrid = rememberLazyGridState()
        var taskColumns by remember { mutableIntStateOf(1) }
        val topFocus = remember { List(5) { FocusRequester() } }
        val searchFieldFocus = remember { FocusRequester() }
        var focusRequest by remember { mutableIntStateOf(0) }
        fun requestFocus(target: () -> FocusRequester?) {
            val generation = ++focusRequest
            scope.launch {
                repeat(4) {
                    withFrameNanos { }
                    if (generation != focusRequest) return@launch
                    val requester = target()
                    if (requester != null) {
                        inputModeManager.requestInputMode(InputMode.Keyboard)
                        if (runCatching { requester.requestFocus() }.isSuccess) return@launch
                    }
                }
            }
        }
        var topIndex by remember { mutableIntStateOf(0) }
        var zone by remember { mutableStateOf("rail") }
        var frame by remember { mutableStateOf(Rect.Zero) }
        val frameX by animateFloatAsState(frame.left, tween(180), label = "focus x")
        val frameY by animateFloatAsState(frame.top, tween(180), label = "focus y")
        val frameWidth by animateFloatAsState(frame.width, tween(180), label = "focus width")
        val frameHeight by animateFloatAsState(frame.height, tween(180), label = "focus height")
        fun Modifier.tracked(group: String) = desktopFocus(group, { zone = it }, { frame = it })
        var taskSelected by remember { mutableIntStateOf(0) }
        var quickSelected by remember { mutableIntStateOf(0) }
        val filtered = remember(apps, query) { apps.filter { it.loadLabel(packageManager).toString().contains(query, true) || it.activityInfo.packageName.contains(query, true) } }
        val current = filtered.getOrNull(selected.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)))
        val currentKey = current?.let { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        var artwork by remember { mutableStateOf<DesktopArtwork?>(null) }
        val color by animateColorAsState(artwork?.palette?.color?.let { Color(it) } ?: Color(0xff1b3355), label = "desktop palette")
        fun exit() { scope.launch {
            val result = runtime.leaveSunshineDesktop()
            if (result.applied) finish() else snacks.showSnackbar(result.message)
        } }
        fun launch(component: ComponentName) { scope.launch {
            if (!runtime.launchVirtualApp(component.flattenToString())) snacks.showSnackbar("应用未能在此屏幕打开，请重试或从最近任务切换")
        } }
        fun settings(action: String) {
            val component = Intent(action).resolveActivity(packageManager)
            if (component != null) launch(component) else scope.launch { snacks.showSnackbar("此设备没有对应的设置入口") }
        }
        fun select(index: Int, moveFocus: Boolean = true) {
            selected = index.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
            if (filtered.isEmpty()) {
                if (moveFocus) requestFocus { exitFocus }
                return
            }
            scope.launch {
                rail.animateScrollToItem(selected); withFrameNanos { }
                if (moveFocus) requestFocus { filtered.getOrNull(selected)?.let { focus["${it.activityInfo.packageName}/${it.activityInfo.name}"] } }
            }
        }
        fun showTop(index: Int) {
            zone = "tabs"
            topIndex = (index + 5) % 5
            when (topIndex) {
                0 -> { page = "apps"; search = false }
                1 -> { page = "tasks"; search = false }
                2 -> { page = "quick"; search = false }
                3 -> { page = "apps"; search = true }
                4 -> { page = "apps"; search = false }
            }
        }
        fun focusTop(index: Int) {
            showTop(index)
            val target = topIndex
            requestFocus { topFocus[target] }
        }
        fun focusTask(index: Int) {
            if (tasks.isEmpty()) return
            taskSelected = index.coerceIn(0, tasks.lastIndex)
            scope.launch {
                taskGrid.animateScrollToItem(taskSelected)
                withFrameNanos { }
                requestFocus { tasks.getOrNull(taskSelected)?.let { taskFocus[it.first] } }
            }
        }
        fun parentMenu(delta: Int) {
            if (zone == "tabs") focusTop(topIndex + delta)
            else if (zone == "actions" && page == "apps") select(selected + delta, false)
            else {
                val parent = when { zone == "search" || search -> 3; page == "tasks" -> 1; page == "quick" -> 2; else -> 0 }
                focusTop(parent + delta)
            }
        }
        fun heroTitle(): String = when (page) {
            "apps" -> when {
                zone == "tabs" && topIndex == 3 || zone == "search" -> "搜索应用"
                zone == "tabs" && topIndex == 4 -> "系统设置"
                else -> current?.loadLabel(packageManager)?.toString() ?: "Sunshine 桌面"
            }
            "tasks" -> tasks.getOrNull(taskSelected)?.second?.let { component ->
                runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(component.packageName, 0)).toString() }.getOrDefault("最近任务")
            } ?: "最近任务"
            else -> listOf("网络设置", "显示设置", "声音设置", "系统设置", "刷新应用与任务").getOrNull(quickSelected) ?: "快捷控制"
        }
        LaunchedEffect(state.active, state.busy) {
            if (state.active) joinedSession = true
            if (joinedSession && !state.active && !state.busy) finishAndRemoveTask()
        }
        LaunchedEffect(Unit) { while (true) { clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()); delay(15000) } }
        LaunchedEffect(refresh) {
            loading = true
            apps = withContext(Dispatchers.IO) {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != packageName && it.activityInfo.enabled && it.activityInfo.exported }
                    .distinctBy { it.activityInfo.packageName to it.activityInfo.name }.sortedBy { it.loadLabel(packageManager).toString() }
            }
            loading = false
        }
        LaunchedEffect(query) { selected = 0; rail.scrollToItem(0) }
        LaunchedEffect(loading, page, desktopWindowFocused) {
            if (desktopWindowFocused && !loading && page == "apps" && !search && zone != "tabs") { withFrameNanos { }; withFrameNanos { }; requestFocus { currentKey?.let { focus[it] } } }
        }
        LaunchedEffect(navigationRevision) {
            if (navigationRevision > 0) {
                // HOME / recents can arrive while focus belonged to another
                // page. Restore a live target instead of retaining its bounds.
                frame = Rect.Zero
                zone = "tabs"
                topIndex = when (page) { "tasks" -> 1; "quick" -> 2; else -> 0 }
                refresh++
                requestFocus { topFocus[topIndex] }
            }
        }
        LaunchedEffect(currentKey, refresh) {
            artwork = current?.let { app -> withContext(Dispatchers.Default) { runCatching { DesktopArtworkCache.get(currentKey!!) { app.loadIcon(packageManager) } }.getOrNull() } }
        }
        LaunchedEffect(page, refresh) {
            if (page == "tasks") {
                val result = org.json.JSONArray(runtime.virtualDesktopTasks())
                tasks = (0 until result.length()).mapNotNull { index -> result.getJSONObject(index).let { item ->
                    ComponentName.unflattenFromString(item.optString("component"))?.let { item.getInt("id") to it }
                } }
            }
        }
        BackHandler { query = ""; search = false; focusTop(0) }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff040912)).onPreviewKeyEvent { key ->
            if (key.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
            else when (key.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> { parentMenu(-1); true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { parentMenu(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val delta = if (key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    when {
                        zone == "tabs" -> { focusTop(topIndex + delta); true }
                        zone == "rail" -> { select(selected + delta); true }
                        zone == "tasks" -> { focusTask(taskSelected + delta); true }
                        else -> false
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> when (zone) {
                    "rail" -> { focusTop(0); true }
                    "search" -> { focusTop(3); true }
                    "tasks" -> { if (taskSelected < taskColumns) focusTop(1) else focusTask(taskSelected - taskColumns); true }
                    "quick" -> { if (quickSelected == 0) focusTop(2) else requestFocus { quickFocus[quickSelected - 1] }; true }
                    "footer" -> { when(page) { "apps" -> requestFocus { playFocus }; "tasks" -> if (tasks.isEmpty()) focusTop(1) else focusTask(taskSelected); else -> requestFocus { quickFocus.last() } }; true }
                    "actions" -> { select(selected); true }
                    else -> false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> when (zone) {
                    "tabs" -> when {
                        page == "apps" && topIndex == 3 && search -> { requestFocus { searchFieldFocus }; true }
                        page == "apps" -> { select(selected); true }
                        page == "quick" -> { requestFocus { quickFocus[quickSelected] }; true }
                        tasks.isNotEmpty() -> { focusTask(taskSelected); true }
                        else -> { requestFocus { exitFocus }; true }
                    }
                    "rail" -> { requestFocus { playFocus }; true }
                    "search" -> { select(selected); true }
                    "actions" -> { requestFocus { exitFocus }; true }
                    "tasks" -> { if (taskSelected + taskColumns < tasks.size) focusTask(taskSelected + taskColumns) else requestFocus { exitFocus }; true }
                    "quick" -> { if (quickSelected < 4) requestFocus { quickFocus[quickSelected + 1] } else requestFocus { exitFocus }; true }
                    else -> false
                }
                else -> false
            }
        }) {
            val compact = maxWidth < 600.dp
            val columns = maxOf(1, ((maxWidth - (if (compact) 48.dp else 88.dp) + 12.dp) / 212.dp).toInt())
            SideEffect { taskColumns = columns }
            val margin = if (compact) 24.dp else 44.dp
            val headerColor = if (color.luminance() > .45f) Color(0xff111724) else Color.White
            DesktopBackground(artwork, color)
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = margin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 20.dp)) {
                    listOf("apps" to "应用", "tasks" to "最近任务", "quick" to "控制").forEachIndexed { index, (tab, title) ->
                        DesktopButton(onClick = { focusTop(index); if (tab == "tasks") refresh++ }, modifier = Modifier.focusRequester(topFocus[index]).onFocusChanged { if (it.isFocused) showTop(index) }.tracked("tabs"), contentColor = if (page == tab) headerColor else headerColor.copy(alpha = .72f), contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 10.dp, vertical = 5.dp)) {
                            Text(title, fontSize = if (compact) 18.sp else 22.sp, fontWeight = if (page == tab) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    DesktopButton(onClick = { showTop(3); scope.launch { withFrameNanos { }; requestFocus { searchFieldFocus } } }, modifier = Modifier.size(44.dp).focusRequester(topFocus[3]).onFocusChanged { if (it.isFocused) showTop(3) }.tracked("tabs"), contentPadding = PaddingValues(0.dp)) { DesktopHeaderIcon(Icons.Default.Search, "搜索应用") }
                    DesktopButton(onClick = { focusTop(4); settings(android.provider.Settings.ACTION_SETTINGS) }, modifier = Modifier.size(44.dp).focusRequester(topFocus[4]).onFocusChanged { if (it.isFocused) showTop(4) }.tracked("tabs"), contentPadding = PaddingValues(0.dp)) { DesktopHeaderIcon(Icons.Default.Settings, "系统设置") }
                    Text(clock, fontSize = if (compact) 18.sp else 24.sp, color = Color.White,
                        style = TextStyle(shadow = Shadow(Color(0xd0111724), Offset(1f, 1f), 4f)))
                }
                if (search && page == "apps") OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = margin).focusRequester(searchFieldFocus).tracked("search").background(Color.Black.copy(alpha = .48f), RoundedCornerShape(12.dp)), singleLine = true, label = { Text("搜索应用") })
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (page == "apps") {
                    LazyRow(state = rail, contentPadding = PaddingValues(horizontal = margin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().height(if (compact) 146.dp else 194.dp)) {
                        itemsIndexed(filtered, key = { _, app -> "${app.activityInfo.packageName}/${app.activityInfo.name}" }) { index, app ->
                            val key = "${app.activityInfo.packageName}/${app.activityInfo.name}"
                            val requester = remember(key) { FocusRequester().also { focus[key] = it } }
                            val tile by animateDpAsState(if (selected == index) { if (compact) 120.dp else 164.dp } else { if (compact) 80.dp else 104.dp }, label = "application tile")
                            var icon by remember(key) { mutableStateOf<DesktopArtwork?>(null) }
                            LaunchedEffect(key) { icon = withContext(Dispatchers.Default) { runCatching { DesktopArtworkCache.get(key) { app.loadIcon(packageManager) } }.getOrNull() } }
                            Box(Modifier.size(tile).focusRequester(requester).tracked("rail").onFocusChanged { if (it.isFocused) selected = index }
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "打开 ${app.loadLabel(packageManager)}") { selected = index; launch(ComponentName(app.activityInfo.packageName, app.activityInfo.name)) }, contentAlignment = Alignment.Center) {
                                icon?.let { Image(it.image, app.loadLabel(packageManager).toString(), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                        }
                    }
                    if (filtered.isEmpty() && !loading) Text(if (query.isBlank()) "未读取到应用，可以刷新或打开系统设置" else "没有匹配的应用", Modifier.padding(horizontal = margin), color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.padding(horizontal = margin).fillMaxWidth(if (compact) 1f else .75f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("SUNSHINE", fontSize = 12.sp, letterSpacing = 4.sp, color = Color.White.copy(alpha = .84f))
                        Text(heroTitle(), fontSize = if (compact) 30.sp else 42.sp, lineHeight = if (compact) 38.sp else 50.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("你的应用，随时开始", fontSize = if (compact) 14.sp else 17.sp, color = Color.White.copy(alpha = .86f))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            DesktopButton(onClick = { current?.let { launch(ComponentName(it.activityInfo.packageName, it.activityInfo.name)) } }, enabled = current != null,
                                modifier = Modifier.width(if (compact) 150.dp else 230.dp).height(52.dp).focusRequester(playFocus).tracked("actions"),
                                containerColor = Color.White, contentColor = Color(0xff111724)) { Text("打开应用", fontSize = 17.sp) }
                            DesktopButton(onClick = { focusTop(2) }, Modifier.size(64.dp, 52.dp).focusRequester(moreFocus).tracked("actions"), borderColor = Color.White.copy(alpha = .5f)) { Icon(Icons.Default.MoreHoriz, "更多操作", tint = Color.White) }
                        }
                    }
                    Spacer(Modifier.height(if (compact) 18.dp else 30.dp))
                } else if (page == "tasks") {
                    Text(heroTitle(), Modifier.padding(horizontal = margin), color = headerColor, fontSize = 26.sp)
                    Text("此屏幕的最近任务", Modifier.padding(horizontal = margin), color = headerColor.copy(alpha = .8f), fontSize = 14.sp)
                    if (tasks.isEmpty()) Text("还没有其他应用任务", Modifier.padding(horizontal = margin), color = headerColor.copy(alpha = .86f))
                    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f).padding(horizontal = margin), state = taskGrid, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        gridItemsIndexed(tasks, key = { _, item -> item.first }) { index, (id, component) ->
                            val label = remember(component) { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(component.packageName, 0)).toString() }.getOrDefault(component.packageName) }
                            val icon = remember(component) { runCatching { packageManager.getApplicationInfo(component.packageName, 0).loadIcon(packageManager) }.getOrNull() }
                            val requester = remember(id) { FocusRequester().also { taskFocus[id] = it } }
                            Box(Modifier.height(150.dp).fillMaxWidth().focusRequester(requester).onFocusChanged { if (it.isFocused) taskSelected = index }.tracked("tasks").border(1.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { taskSelected = index; scope.launch { if (!runtime.virtualDesktopAction("focus", id)) snacks.showSnackbar("任务已结束或无法切换，请刷新") } }) {
                                icon?.let { Image(it.toBitmap(240, 240).asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .86f)))))
                                Text(label, Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .62f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(heroTitle(), fontSize = 28.sp, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .58f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                        Text(adapter.detail, color = Color.White.copy(alpha = .86f), modifier = Modifier.background(Color.Black.copy(alpha = .48f), RoundedCornerShape(8.dp)).padding(8.dp))
                        for ((index, pair) in listOf("网络设置" to android.provider.Settings.ACTION_WIFI_SETTINGS, "显示设置" to android.provider.Settings.ACTION_DISPLAY_SETTINGS, "声音设置" to android.provider.Settings.ACTION_SOUND_SETTINGS, "系统设置" to android.provider.Settings.ACTION_SETTINGS).withIndex()) {
                            val (label, action) = pair
                            DesktopButton(onClick = { quickSelected = index; settings(action) }, Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(quickFocus[index]).onFocusChanged { if (it.isFocused) quickSelected = index }.tracked("quick"), containerColor = Color.Black.copy(alpha = .56f), borderColor = Color.White.copy(alpha = .38f)) { Text(label, color = Color.White) }
                        }
                        DesktopButton(onClick = { refresh++ }, Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(quickFocus[4]).onFocusChanged { if (it.isFocused) quickSelected = 4 }.tracked("quick"), containerColor = Color.Black.copy(alpha = .56f), borderColor = Color.White.copy(alpha = .38f)) { Text("刷新应用与任务", color = Color.White) }
                        Text("L3 切换鼠标 · R3 游戏原生\n游戏中：Start＋B 返回 · Start＋X 最近任务\n长按 Start＋Select 恢复导航并回主页", color = Color.White.copy(alpha = .9f), modifier = Modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp)).padding(9.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = margin), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (compact) "A 确定 · B 返回 · L3 鼠标" else "A 确定     B 返回     方向键 / 左摇杆 选择     L3 鼠标     R3 游戏原生", Modifier.weight(1f), fontSize = 12.sp, color = Color.White.copy(alpha = .9f))
                    DesktopButton(onClick = { exit() }, enabled = !state.busy, modifier = Modifier.focusRequester(exitFocus).tracked("footer")) { Text("退出桌面", color = Color.White) }
                }
            }
            if (frame != Rect.Zero) Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Color(0xc0111724), Offset(frameX - 3.dp.toPx(), frameY - 3.dp.toPx()),
                    Size(frameWidth + 6.dp.toPx(), frameHeight + 6.dp.toPx()), CornerRadius(16.dp.toPx()), style = Stroke(4.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = .94f), Offset(frameX - 3.dp.toPx(), frameY - 3.dp.toPx()),
                    Size(frameWidth + 6.dp.toPx(), frameHeight + 6.dp.toPx()), CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            SnackbarHost(snacks, Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
        }
    }
}

/** Glyph outline stays legible over both the artwork and its colour extension. */
@Composable
private fun DesktopHeaderIcon(vector: ImageVector, description: String) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(vector, null, Modifier.size(28.dp), tint = Color(0xc0111724))
        Icon(vector, description, Modifier.size(24.dp), tint = Color.White)
    }
}

/** Only the shared animated outline indicates controller focus; no additional focus tint. */
@Composable
private fun DesktopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = Color.White,
    borderColor: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    Row(modifier.clip(shape).background(containerColor).border(1.dp, borderColor, shape)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(contentPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = if (enabled) 1f else .4f)) { content() }
    }
}
