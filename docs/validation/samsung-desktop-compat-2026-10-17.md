# 三星桌面适配与防止空桌面困住用户

日期：2026-10-17。此记录随实机验证更新；尚未完成的项目不计为通过。

> 当前交付为 v2026.10.17.3，源码与 APK 已发布；本页保留各轮候选的历史现场与验证边界。最终入口、应用内更新和 119 项 JVM 检查见 [最新交接](../HANDOFF-2026-10-17.md)。末尾 v2026.10.17.1 的“未提交／未发布”描述仅指当时状态。用户接受当前性能边界后，本轮按现有串流实现交付。

## 适配边界

统一设置入口根据实际能力选择已有原生 DeX、手机本机 DeX for PC 接收端、平板 Standalone 或兼容桌面，不使用型号白名单。系统没有原生入口时明确说明，不把普通虚拟屏报告为 DeX。Standalone 使用平板主屏，并非独立第二屏。

- SM-G986U1：系统支持 DeX for PC，Standalone=false。
- SM-T970：有原生 DeX 系统桌面和 Standalone 能力，没有 DeX for PC 包。
- 平板原生 Standalone 的独立启动探针成功；早期探针期间宿主进程重启、串流中断。候选已增加密度、最小屏宽、UI 模式等配置变化声明和三星 keepalive 元数据，最终完整串流仍需另测。

## 空桌面与导航

原活动按普通标准任务启动，在三星第二屏上被系统 SecondaryDisplayLauncher 盖住，接收端只看到壁纸。旧的 MAIN / HOME 请求会被 Android 主 HOME 路由到 Display 0；改为 SECONDARY_HOME、明确 activityType=HOME，取消 singleTask 的跨显示复用，并在创建后验证目标显示任务。兼容桌面提供搜索、应用列表、当前显示的任务、快捷设置和错误反馈，普通显示上的应用优先全屏启动，并核对任务确实位于目标显示。

常驻控制条在其他应用上保留返回、桌面、任务、快捷控制和退出。候选要求控制条成功附着才报告桌面创建成功；失败恢复主屏。另有桌面按钮、主机通知操作和 Ctrl+Alt+Esc 退出。两个前台服务共用通知，均保留退出操作，避免引擎更新覆盖按钮。

系统通知栏是否存在由固件决定，兼容桌面的快捷控制不能等同于完整系统通知中心，也不读取其他应用的通知。原生 DeX 的任务栏和通知栏由三星系统提供。

## 已定位并复现的故障

S20+ 首次候选控制条创建失败，宿主正确拒绝进入并恢复主屏。独立 app_process 探针保留的 snapshot 指向 Typeface.create 内部空指针；TypeFace.DEFAULT 实为 null。该服务未经 Zygote 的字体预加载，补调系统 loadPreinstalledSystemFontMap 后同探针 OVERLAY=true，清理自己的显示成功。修复仅在默认字体尚未初始化时执行，并保留真实异常日志。超时或关闭后的延迟 UI 回调也会移除窗口，避免残留。

## 最终候选验证状态

本轮 `v2026.10.17.1`（versionCode 20261030），107 项 JVM 测试、Release lint 和三种 APK 构建通过。此轮没有修改原生库。最终以本地 build-checks-final.log 和对应 APK SHA256 区分同版本号的多次候选。

1. SM-T970 最终兼容桌面：实际目标显示截图可见完整应用网格、搜索、底部导航和常驻控制；系统设置成功在目标显示打开。最近任务面板显示设置并能 focus 原任务，错误的系统 SecondaryDisplayLauncher 不再作为可切换应用。HOME 下浮窗可能继续遮住启动器，因此普通桌面的“桌面 / 应用”改为在应用上方打开自有应用选择器。
2. SM-T970 原生 DeX：Standalone 启动约 4.5 秒，系统 Display 0、2800×1752、报告约 120Hz。宿主 PID 与 Windows 串流会话不变；实际截图可见三星任务栏、应用抽屉、最近任务、通知区域和桌面图标。此路径使用平板主屏，不是独立第二屏。
3. 自有最近任务和快捷控制在原生 DeX 上可见，网络设置启动后产生实际窗口，`focus:<task>` 日志确认成功。点击常驻“退出桌面”后约 2 秒恢复平板普通主屏，两路会话和宿主进程均保留。
4. Windows 2560×1440 / 60fps H.264 与 OPPO 1440×3168 / 120fps HEVC 双端实际同连。首次发现新客户端 reloadConfig 错误地把 Display 0 的平板 DeX 当普通主屏，自动旋转将竖屏编码画布变成横屏并拉伸。修复后接收端保持请求的竖屏画布，横向 DeX 等比放入，截图验证通过。黑边是横向桌面放进竖向接收画布的结果，不是画面缺失。
5. 双端物理主面板关闭 45 秒：15/30/45s 的 SurfaceFlinger 采样均 Off，Android 逻辑显示均 ON，DeX 和两路串流持续，结束恢复 On。PC / OPPO 编码 FPS 窗口中位数约 47fps；分别新增队列丢帧 3 / 47、最终 UDP 发送失败 109 / 221，额度超时均 0。温控全程 3，引擎 cpuset 从 foreground 经 moderate 到 abnormal。动态壁纸、温度和高码率负载仍有性能问题，不能记作稳定高刷或零丢包通过，也未证明这些变化全部由息屏引起。
6. S20+ 原生 DeX 回归：无 HDMI，本机接收端确认后创建 Display 2、1920×1080 / 60Hz，OPPO 真正收到 DeX 任务栏与控制条；点击控制条退出后恢复主屏，保留接收端会话。平板路径没有替代手机 PC 路径。

## 仍需验证的边界

- 未覆盖所有三星型号、One UI 版本，尤其移除 DeX for PC、又没有 Standalone 的手机。此类系统使用明确标识的兼容桌面，不能承诺原生 DeX。
- 最新控制和画布修复没有解决动态负载下全部性能问题；还需区分温控、厂商 cpuset、编码与网络发送压力。
- 平板 cover_close 会让主屏休眠，旧逻辑将其当外屏断开并退出；现已区分主屏 DeX 与外屏 DeX，主屏休眠尝试恢复逻辑唤醒并只关闭物理面板。该 cover 事件自动恢复分支尚未独立验收，45 秒结果来自显式面板 API，不混为同一测试。
- 不是小时级稳定性测试；应用启动由目标任务与截图核对，个别不支持外屏或多窗口的第三方应用仍可能拒绝，界面会保留退出和错误反馈。
- Android 接收端截图验证了实际网络出图；本轮菜单操作主要用目标显示 UiAutomation 点击，尚未完成全部 Moonlight 手势的逐事件端到端坐标验收。

## 权限服务与故障修正

平板最初 Shizuku 未运行，实际是 Stellar 提供兼容服务且 Sunshine 为询问。组合命令被工具拒绝后未执行；用户明确说明坏屏无法操作并要求代办，随后单独读取界面、点击授权成功，状态复核为允许。未清应用数据、密钥或修改无关应用授权。

控制条先遇到 shell 字体未初始化；再发现窗口可 addView 却因应用包与 shell UID 归属不符被 AppOps 隐藏。最终改用已授权 shell 的 INTERNAL_SYSTEM_WINDOW / SYSTEM_DIALOG 窗口，保持正确归属；创建后检查目标显示、owner、实际绘制与 isOnScreen，而非仅检查 Surface 存在。补充回归覆盖“主屏可见但第二屏不可见”“AppOps 隐藏”“尚未绘制”和多显示互相掩盖。

## 本地证据（不随 Git 发布）

- work/samsung-desktop/s20-overlay-probe.log：字体修复前 snapshot。
- work/samsung-desktop/s20-overlay-font-probe.log：默认字体为空、初始化后控制条成功。
- work/samsung-desktop/s20-capabilities-20261017.log：手机能力。
- work/samsung-desktop/tablet-capabilities.log：平板能力。
- work/samsung-desktop/build-checks-*.log：各候选检查。

- work/samsung-desktop/tablet-compatible-visible.png、tablet-settings-visible.png：修复后兼容桌面和应用窗口。
- work/samsung-desktop/tablet-native-dex-desktop.png、tablet-native-final-tasks.png：原生桌面和悬浮任务选择器。
- work/samsung-desktop/tablet-native-toolbar-exit-result.json：两路会话、宿主 PID 保留并恢复主屏。
- work/samsung-desktop/oppo-native-rotation-fixed.png：竖屏实际接收画面比例。
- work/virtual-stream-network/tablet-native-dual-panel-off-20261017/：45 秒双端面板关闭原始采样与 summary.json。
- work/samsung-desktop/s20-native-regression.json、oppo-s20-native-receiver.png、s20-native-exit-result.json：手机路径回归。

最终 arm64 APK SHA256：`6ba7c42f1e85911607e1a47dc4682ce2c47dd72d5a0cbbee70e7b0ff85b9d4ba`。签名验证通过，三种 APK 包内原生库与仓库文件逐字节一致。最新候选已安装到平板与 S20+。本轮未提交或发布；平板结束时保留原生 DeX 与一条 Windows 测试串流，主面板恢复 On，便于用户从电脑查看。
