package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.BuildConfig
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*

private const val WEBSITE = "https://nanaoni.tech"
private const val REPOSITORY = "https://github.com/nanaoniiiii/sunshine"
private const val SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases"
private const val SHIZUKU_GUIDE = "https://shizuku.rikka.app/zh-hans/guide/setup/"
private const val SHIZUKU_PLAY = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"

@Composable
fun AboutSection() {
    SectionCard("关于", Icons.Default.Info) {
        Text("Sunshine for Android", style = MaterialTheme.typography.titleLarge)
        Text(BuildConfig.VERSION_NAME, color = PrimaryCyan, style = MaterialTheme.typography.bodyMedium)
        Text("把 Android 手机或平板作为串流主机，在另一块屏幕上接收画面与声音，并通过 Shizuku 实现远程输入。支持 H.264 / HEVC 硬件编码、设备配对、应用管理与连接诊断。可使用 Moonlight，也可开发兼容串流协议的自定义接收端。",
            color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text("作者：七鬼 · nanaoniiiii", style = MaterialTheme.typography.bodyLarge)
        HelpLink("项目官网 · nanaoni.tech", WEBSITE)
        HelpLink("GitHub · nanaoniiiii/sunshine", REPOSITORY)
        HelpLink("作者 GitHub", "https://github.com/nanaoniiiii")
    }
}

@Composable
fun FaqScreen(onBack: () -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf<String?>("fullscreen") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("返回设置")
            }
            PageHeader("常见问题", "全屏、横屏、配对与 Shizuku 授权")
        }
        item { FaqTopic("Moonlight 画面不全屏或竖屏变成小块", expanded == "fullscreen", { expanded = if (expanded == "fullscreen") null else "fullscreen" }) {
            HelpText("1. 先核对 Sunshine 主页中该连接的请求分辨率。竖屏内容应请求竖屏宽高（例如 1440×3168），横屏应请求横屏宽高；仅旋转客户端页面不会重新协商视频尺寸。修改 Moonlight 分辨率后退出并重新连接。")
            HelpText("2. 检查客户端的自定义分辨率、交换宽高／自动反转分辨率、屏幕方向／跟随设备旋转、填充刘海区域、视频缩放模式。各 Moonlight 分支的名称与位置可能不同。")
            HelpText("3. 手机横竖屏全屏切换：开启 Sunshine“固定画布旋转兼容”，Moonlight 使用与主机匹配的竖屏分辨率（例如 1440×3168），关闭自由旋转／横竖屏自由切换，开启填充刘海区域。将接收端固定为竖屏；阿西西版可开启“竖屏显示模式”，或在串流菜单点“旋转屏幕”切到竖屏。主机视频进入横屏后，随画面转动接收手机即可，触控会同步映射。")
            HelpText("4. Sunshine 默认按主机显示尺寸采集。主页连接旁的“全屏”按钮会按该连接请求的尺寸调整主机画布；它不能替客户端修改显示选项。同一主机画布只能匹配一个比例，其他比例的连接可能留边。")
            HelpText("“填充”可能裁掉边缘，“拉伸至全屏”可能使画面变形；保留完整画面时允许黑边。提高码率不能解决宽高比例不匹配。")
        } }
        item { FaqTopic("横屏异常、方向锁定或退出后恢复", expanded == "rotation", { expanded = if (expanded == "rotation") null else "rotation" }) {
            HelpText("手机接收端先按上方步骤开启固定画布旋转兼容；电脑接收端可关闭此模式，开启自动旋转，让视频画布直接交换宽高。")
            HelpText("先在 Sunshine“设置 → 画面与流畅度”尝试修改“自动旋转”，设置会自动保存。再检查手机系统中的自动旋转／方向锁定；部分系统另有“允许系统旋转”或类似开关，可尝试切换。选项是否存在取决于系统版本。")
            HelpText("若找不到对应系统选项，或全屏后方向不正常，先退出所有 Moonlight 串流连接，让 Sunshine 恢复全屏前保存的显示尺寸与旋转设置，再重新连接。只退出其中一路时，其他连接仍在使用主机画布，通常不会恢复。")
            HelpText("某些桌面或应用本身只支持竖屏（例如部分 OPPO 桌面），强制横屏仍可能居中留白或图标错位。可回到竖屏，或进入支持横屏的应用；全屏按钮不能为这些应用补出横屏布局。")
        } }
        item { FaqTopic("下载、启动 Shizuku 与授权 Sunshine", expanded == "shizuku", { expanded = if (expanded == "shizuku") null else "shizuku" }) {
            HelpLink("Shizuku GitHub 下载与历史版本", SHIZUKU_RELEASES)
            HelpLink("Shizuku Google Play", SHIZUKU_PLAY)
            HelpLink("Shizuku 官方启动手册", SHIZUKU_GUIDE)
            HelpText("Android 11 及以上：手机连接 Wi-Fi，开启开发者选项、USB 调试与无线调试。在 Shizuku 点击配对，进入系统“无线调试 → 使用配对码配对设备”，保持配对窗口打开，在 Shizuku 通知中输入该配对码，完成后回到 Shizuku 点击启动。")
            HelpText("Android 10 及以下通常需要电脑通过 USB 启动：开启 USB 调试、连接电脑并允许调试授权，然后按 Shizuku 当前版本显示的启动命令操作。本项目要求 Android 10 及以上。")
            HelpText("确认 Shizuku 显示服务运行后，回到 Sunshine 主页申请 Shizuku 权限，并在授权弹窗中允许；也可在 Shizuku 的授权应用管理中检查 Sunshine 是否已允许。Shizuku 正在运行不代表 Sunshine 已获得授权。")
            HelpText("免 Root 模式重启手机后要重新启动 Shizuku，配对通常不用重做。删除配对记录、撤销调试授权或恢复设置后，可能需要重新配对。")
        } }
        item { FaqTopic("开发者选项：通用设置", expanded == "general", { expanded = if (expanded == "general") null else "general" }) {
            HelpEntry("开发者选项", "开启总开关。入口常见于关于手机连续点击版本号，具体以机型为准。")
            HelpEntry("USB 调试", "开启，并允许自己电脑的调试授权。")
            HelpEntry("无线调试", "Android 11 及以上用手机本机启动时开启；配对时连接 Wi-Fi。")
            HelpEntry("停用／禁止 ADB 授权超时", "如果有，建议开启，减少授权过期。")
            HelpEntry("默认 USB 配置", "插拔线后服务停止时，尝试“无数据传输／仅充电”。")
            HelpEntry("后台运行", "允许 Shizuku 和 Sunshine 后台运行；按需开启自启动、取消电池限制，避免清理工具关闭开发者选项或终止服务。")
        } }
        item { FaqTopic("各手机系统额外需要注意的设置", expanded == "brands", { expanded = if (expanded == "brands") null else "brands" }) {
            HelpText("同品牌不同版本的名称、入口和可用开关可能不同。先做通用设置，再按实际问题检查以下项目。")
            HelpEntry("小米 / Redmi / POCO · MIUI / HyperOS", "MIUI 检查“USB 调试（安全设置）”，它与普通 USB 调试是两个开关；HyperOS 若仍有此项也优先检查。已启动但执行操作提示权限不足时尤其要核对。旧 MIUI 配对通知异常，可尝试把通知样式改为“Android／原生样式”。")
            HelpEntry("OPPO / 一加 · ColorOS", "ADB 权限受限时，检查并关闭开发者选项中的“权限监控”（如果提供）。若中文界面没有该项，部分版本可尝试通过系统设置或语音助手进入语言设置，临时将本机语言改为英文，再搜索“Permission monitoring”，操作后恢复原语言。这是部分版本的排查办法，不能保证显示该项；仍找不到时按当前系统与 Shizuku 版本说明排查。")
            HelpEntry("魅族 · Flyme", "权限受限时，检查并关闭“Flyme 支付保护”（如果存在）；它可能拦截部分 ADB 操作。")
            HelpEntry("华为 · EMUI", "检查“仅充电模式下允许 ADB 调试”，避免拔掉数据线或切换为仅充电后服务停止。")
            HelpEntry("三星 · One UI", "USB 调试灰色或无法开启时，检查“设置 → 安全和隐私 → 自动拦截器（Auto Blocker）”，按需关闭对应阻拦。它可能禁用 USB 调试，也可能阻止安装下载的 APK。")
            HelpEntry("vivo / iQOO · OriginOS / Funtouch OS", "先按通用设置配置，没有适用于所有版本的统一额外开关。切到系统设置后搜不到配对服务时，检查 Shizuku 后台与网络限制；不要直接照搬 ColorOS 的“关闭权限监控”教程。")
            HelpEntry("Pixel / 原生 Android 等", "先按通用设置配置，重点排查无线调试发现、配对、授权及后台运行。")
        } }
        item { FaqTopic("授权后立即掉权、服务停止或搜不到配对", expanded == "permission", { expanded = if (expanded == "permission") null else "permission" }) {
            HelpText("先区分：Shizuku 服务是否停止、Sunshine 是否未被授权、还是服务已运行但系统拦截某项操作。分别检查 Shizuku 状态、授权应用列表及对应厂商设置；录屏、录音和通知权限还需按 Sunshine 提示单独授予。")
            HelpText("配对时一直搜索：允许 Shizuku 后台运行和本地网络访问，保持 Wi-Fi 与无线调试开启，保持系统配对码窗口可见，必要时使用分屏。配对端口与无线调试连接端口不同；配对码过期后重新生成。")
            HelpText("授权后立刻失效：检查后台限制、开发者选项是否被清理工具关闭、ADB 授权超时和厂商额外开关。重启 Shizuku 后重新检查 Sunshine 授权。仍失败可从官方 GitHub 或 Google Play 更新 Shizuku；若新版与本机系统不兼容，可按发布说明尝试官方其他版本，并重新启动与授权。")
            HelpText("换版本不保证解决所有厂商限制。记录手机型号、Android／系统版本、Shizuku 版本和 Sunshine 运行日志，便于定位。")
        } }
        item { FaqTopic("Sunshine 与 Moonlight 怎么配对", expanded == "pairing", { expanded = if (expanded == "pairing") null else "pairing" }) {
            HelpText("1. 主机与客户端连接同一可互通的局域网，启动 Sunshine，在主页确认服务运行并记下主机 IP。访客 Wi-Fi／AP 隔离可能阻止两端互访。")
            HelpText("2. 在 Moonlight 添加该 IP 并点击主机。Moonlight 显示 4 位 PIN 后，在 Sunshine 的配对弹窗或“配对”页输入这 4 位 PIN，确认配对，再选择桌面或应用开始串流。")
            HelpText("3. 不要把系统无线调试的配对码填到 Sunshine。无线调试配对用于启动 Shizuku；Moonlight 的 4 位 PIN 用于连接 Sunshine，是两个独立流程。")
            HelpText("配对失败时先检查 IP、两端网络、主机服务和待处理配对请求；必要时在客户端删除旧主机记录后重新配对。免 Root 重启后先启动 Shizuku，已有 Moonlight 配对通常无需重做。")
        } }
        item { SectionCard("参考与反馈", Icons.Default.Link) {
            HelpLink("Shizuku 官方手册", SHIZUKU_GUIDE)
            HelpLink("项目问题反馈 · GitHub Issues", "$REPOSITORY/issues")
            Text("厂商设置以本机版本为准。文档更新：2026-10-14。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun FaqTopic(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SectionCard {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
            Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起 $title" else "展开 $title", modifier = Modifier.size(24.dp))
        }
        if (expanded) content()
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun HelpEntry(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        HelpText(description)
    }
}

@Composable
private fun HelpLink(title: String, url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(0.dp)) {
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.OpenInNew, contentDescription = "在浏览器打开", modifier = Modifier.size(20.dp))
    }
}
