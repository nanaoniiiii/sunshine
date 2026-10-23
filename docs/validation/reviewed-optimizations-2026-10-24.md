# 优化候选版升级与实机验证

初次候选为 `v2026.10.24.1` / `20261041`，功能源码提交为 `fbc6b14396f6be06cda6cc12a2cb19f86e0cad5e`。继续检查发现锁屏状态判断误拦，修正版为 `v2026.10.24.2` / `20261042`；最终补齐桌面退出目标处理的版本为 `v2026.10.24.3` / `20261043`。设备为三星 S20+ `SM-G9860` / Android 13，当前主用户；接收端为 Windows Moonlight。各版本的事实分别记录，此记录补充[开发分支审查](../reviews/developer-optimizations-2026-10-24.md)中的发布 APK 验证，不用函数级微基准代替完整应用检查。

## v2026.10.24.1 升级、授权与配对

此前安装命令曾被执行工具以 `blocked by policy` 拒绝。用户明确要求“开始验证”后，正常重试 `adb install --user 0 -r` 成功，未清数据、卸载或重新配对。安装后包管理器确认版本为 `20261041`；设备上实际安装的 base APK SHA-256 为 `ee8a2c9ce15762e026e9675a68dab69c5e2fa2709015dee1b8a261013b116c67`，与已验证的 arm64 候选一致。

| 项目 | 当前结果 |
| --- | --- |
| 升级前后配置 | 仅 `version` 字段改变，其他字段一致。 |
| 配对列表 | 前后完全一致，保留一台测试客户端。 |
| 主用户授权 | Shizuku API、录音、通知均保留授权。 |
| 零客户端输入适配器 | `ready=true`、`running=true`、`systemNavigation=true`。 |
| 使用原配对连接 | 电脑 Moonlight 实际收到 Sunshine 主屏画面，无需再次配对或录屏确认。 |
| 诊断解析 | `/api/diagnostics` 正常返回原生视频会话、编码尺寸、帧率、音频与队列数据。 |

## HTTP 状态处理

对正在连接的测试客户端执行一次保持原名称的保存，返回成功；保存前后配对列表完全一致。没有删除客户端或修改证书。

另发起一个只传入部分请求体的配对重命名请求，保持其未完成时读取客户端列表。列表在约 `0.015 s` 返回，证明实机上慢请求体没有占住状态锁。随后补齐故意无效的 JSON，请求返回 HTTP 500；错误响应可由 JSON 解析器正常解析，`status=false`。这项测试验证 HTTP 服务的锁范围和错误编码，不代表 Java 锁能协调原生引擎的跨进程文件写入。

## 实际画面与运行样本

已经观察电脑接收端的实际渲染，Sunshine 首页、串流卡片、设置导航与锁屏错误提示显示完整。连接请求为 `2560×1440 / 60fps`，主屏处于竖屏并启用自动旋转，实际编码为 `1440×2560`，不能把请求尺寸当作这时的编码尺寸。

主屏样本实际编码约 `59.7–60.4fps`，电脑覆盖层也显示约 `60fps`。两次记录之间编码帧数从 `8426` 增长到 `9526`，视频队列丢帧、发送错误、无效缓冲均为 `0`，提交超时及 Surface 失败也为 `0`。静止画面的源帧较少，重复帧开启；这些读数不证明原画面每秒刷新 60 次，也不作为本轮优化带来 FPS 提升或端到端延迟降低的证据。

音频采集处于运行状态，40ms 有界队列正常返回。累计溢出丢弃出现过 30ms，两个取样点之间没有继续增加；未把这一状态查询当作听感或音画同步验收。崩溃缓冲样本中未发现 Sunshine 的进程崩溃记录。

## 锁屏与桌面创建边界

此前依据 `Keyguard showing=true` 将样本记为真实锁屏，这一判断不成立，现予更正。旧候选的 DeX 请求确实在约 `0.485 s` 返回 `status=false` 并显示解锁提示，且没有卡住服务；但 `showing` 本身不能证明用户应用需要身份验证才能访问，因此不能将该结果记为安全锁屏前置检查通过。

用户指出手机一直显示 Sunshine。随即检查实际截图与系统信任状态：Sunshine 首页处于前台，`mKeyguardOccluded=true`、`trusted=1`、`deviceLocked=0`，同时 `Keyguard showing=true`。普通触屏点击和滚动可以正常打开 Sunshine 设置。这揭示了原有 `isKeyguardLocked` 检查把受信任、可直接进入应用的状态误判为需要解锁。

这一差别与 [Android 官方 KeyguardManager 说明](https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked())一致：`isKeyguardLocked` 包括被 Activity 遮挡、受信任或只需滑动即可解除的锁屏 UI；`isDeviceLocked` 则检查当前用户的应用是否因需要身份验证而不可访问。

首轮已结束自建 Moonlight 测试进程并移除专用 ADB 转发。首轮收尾时客户端数为 0，未遗留虚拟屏／DeX，输入适配器仍就绪；配置恢复原 `screen_off / sunshine`，除版本号外与升级前一致，全局强制桌面值为原 `1`、USB 保持亮屏值为原 `0`、主屏 override 保持 `1080×2400`。

## v2026.10.24.2 修正与后续检查

将虚拟屏创建、主屏息屏、独立桌面面板控制、退出 Sunshine 桌面和本地 DeX 准备中的认证前置条件集中为 `requireDisplayUserUnlocked`，查询 `KeyguardManager.isDeviceLocked`。已经运行的原生 DeX 保留原来的复用规则；没有关闭系统锁屏、改变安全设置或注入认证凭据。

新增三个 Robolectric 回归：受信任但 Keyguard 仍 showing 时可以继续；真正需要认证时以原操作提示拒绝；后续重新锁定时每次操作都会重新检查。最终 `testReleaseUnitTest lintRelease assembleRelease` 通过，共 `221` 项测试、0 失败、0 错误。三个 APK 的包名／版本／ABI／原生库与签名通过校验。

修正版已覆盖安装到主用户并成功打开，包管理器确认 `v2026.10.24.2 / 20261042`，仍能在同一 `deviceLocked=0 / Keyguard showing=true` 状态下触屏操作设置。实机设置确认：“虚拟屏”栏目中的“第二屏桌面模式”关闭时隐藏桌面选择，打开后出现默认 Sunshine 与设备自带 DeX 两个选项，布局和文字完整。

执行工具拒绝了随后准备连接及启动电脑 Moonlight 的复合命令，仅返回 `blocked by policy`，未改写启动串流。之后用户手动完成配对并连接，复用已打开的 Moonlight 窗口继续实测，见下一节。初次候选的读数没有转记为修正版结果；之前正式版的[三种显示模式验证](fresh-install-permissions-2026-10-23.md)也不代替修正版创建验收。

拒绝来源的表述另作更正：当前会话及本地配置均为 `approval_policy=never / sandbox_mode=danger-full-access`；本地常规命令规则只有允许项。日志能定位到 `codex_core::tools::router` 记录执行前的 `CreateProcess … Rejected … blocked by policy`，没有具体审核理由或对应的 Auto-review 决策记录。按照 [OpenAI 官方 Auto-review 说明](https://learn.chatgpt.com/docs/sandboxing/auto-review)，`never` 不触发其交互式审批审核。因此不能把这次错误确定归因为该 Auto-review 功能、用户缺少授权，或 Moonlight 本身被禁用；具体命中哪一步及哪条策略仍未知。

## 最终 v2026.10.24.3 的修正和验收

接收端连接后，`v2026.10.24.2` 已能创建原生 DeX 和普通虚拟桌面，但实测退出 Sunshine 桌面时，保留虚拟屏与息屏正确，主屏仍可能露出“Smart Lock 已解锁手机”的锁屏 UI。新增 `TrustedKeyguardDismissal`，在主机和虚拟桌面 Activity 恢复或重新获得焦点时，仅在 `isDeviceLocked=false` 且 Keyguard 仍 showing 的情况下调用正常的 `requestDismissKeyguard`。它不修改锁屏设置；已经需要身份验证时不发起该请求，重复焦点回调只保留一个待处理请求。

新增四项 Android 框架回归覆盖可信状态正常完成、认证锁定时不请求、无锁屏时不请求、待处理时不重复请求；连同前一轮三项锁定检查共增加七项。最终 `testReleaseUnitTest lintRelease assembleRelease` 全部通过：225 项测试、0 失败、0 错误，Lint 0 错误／48 条警告，仍保留现有 Compose 自定义检查版本不匹配的验证边界。

安装的最终 arm64 APK 散列与发布候选完全一致，三份 APK 的签名与上一正式版一致。最终 universal APK 中八个原生库逐字节与初次优化候选一致，初次候选的八个库此前已与 `v2026.10.23.3` 核对一致。

| 最终 APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 7321630 | `17ff3edb86f359568dd851c22670a3e26eac261b9719c7ecc0c14fea1178fb1b` |
| armeabi-v7a | 6356178 | `c19e4c32c9cc1618735549b83cf376ba5d5006bcdef697fedd1d779b484a5b94` |
| universal | 10739574 | `111707d684ae737e8dbb3f4052b4c3aea3df8f27b18bfa2ebb3dd5503b2d80a4` |

用户已打开并连接的 Windows Moonlight 实际协商 HEVC，分辨率请求为 `2560×1440 / 60fps`；通过现有窗口正常结束旧连接，升级最终版后又使用保留的配对连接成功。

| 实测项目 | 最终结果 |
| --- | --- |
| 普通 Sunshine 虚拟桌面 | Display 22，逻辑与编码均为 2560×1440；电脑实际收到应用列表和右侧图标背景。创建约 1.05 秒完成，本项数值只对应本次已授权、已连接的样本。 |
| 桌面“退出桌面”按钮 | 通过实际按钮触发，切回 Display 0；Display 22 保留，`mainPanelOff=true`，主屏全屏 override 为 1440×2560（横屏逻辑输出 2560×1440）。可访问窗口为“主屏幕”，Keyguard showing=false；电脑实际收到手机主页。 |
| 默认息屏模式 | `mode=screen_off`、Display 0、无保留独立桌面。电脑继续显示手机原主屏；SurfaceFlinger 物理面板 Off，串流显示 On。 |
| 三星原生 DeX | 经过实际三星“立即开始”确认，进入 Display 2 双屏 DeX，保留三星主页和任务栏。物理模式 1920×1080、逻辑 override 2560×1440、编码输出 2560×1440，匹配接收端请求。确认等待计入创建请求总时长，不作为冷启动性能数据。 |
| Android 默认桌面开关 | 每次虚拟屏运行时实际值为 0；完整退出恢复原值 1。 |
| 空闲服务重启 | `v2026.10.24.2` 在零客户端时约 2.55 秒内更换了实际原生引擎进程；配置和配对保留，原生服务返回成功，输入适配器与系统导航持续就绪。 |

最终版默认息屏样本实际编码约 60fps，静止 Sunshine／DeX 桌面样本约 4fps。DeX 稳定两次取样新增编码 440 帧，队列丢帧／发送错误／无效缓冲均无新增；本轮最终连接在模式切换过程中累计出现 5 次队列丢帧，不能写成全程零丢帧。稳定取样的累计音频丢弃为 60ms，全部连接收尾时为 100ms；这些有界缓冲状态不等于听感、音画同步或高负载验收。没有观察到编码器错误、提交超时或 Surface 失败，当前崩溃缓冲中没有 Sunshine 进程崩溃记录。

`v2026.10.24.2` 还完成了接收端鼠标打开 DeX 设置和滚轮滚动的实际画面检查。ADB 注入的系统方向键验证了应用列表→打开按钮→顶部栏的层级、顶部应用／最近任务／控制／搜索／设置的可达性、栏目无需确认即切换，以及控制文字的深色背景；在第二屏打开系统设置后，系统返回键退回 Sunshine，继续返回和在桌面按主页均保留 Sunshine。首次 Android 全屏说明通过正常上滑与确认关闭。最近任务空列表的画面已检查，未把它计为非空任务卡片渲染验收。

本轮没有识别到实体手柄；上述方向键检查是系统注入，不计为实体手柄或 Moonlight 手柄的复测。电脑自动注入的键盘方向键尝试未观察到桌面选中项变化，未计为远程键盘通过，也没有据此归因为产品回归。手柄映射核心未改，保留[之前实体手柄测试](controller-desktop-2026-10-23.md)的原有范围；各品牌、各游戏和全部外设没有逐一重测。

最终收尾：所有串流连接结束，虚拟屏、保留显示与本地 DeX 接收端均已清理；物理面板 On。配置恢复 `screen_off / sunshine`，除版本字段外与初始配置一致；全局强制桌面恢复 1、USB 保持亮屏恢复原 0、主屏 override 恢复原 `1080×2400`。用户手动配对后的最终列表为两条，初始配对记录仍在，没有清理新增项。零客户端时服务、输入适配器和系统导航仍就绪。所有本轮手机临时诊断文件已删除，电脑 Moonlight 主程序保留。

## 正式发布与官网

最终包已在北京时间 2026-10-24 公开为 [v2026.10.24.3](https://github.com/nanaoniiiii/sunshine/releases/tag/v2026.10.24.3)，Release 目标为功能提交 `beeacf669fcf7fd344ae20d65467c0bbe9f83feb`。[官网同步工作流](https://github.com/nanaoniiiii/sunshine/actions/runs/40012633746) 成功，公开更新清单已切到该版本，三个 APK 的线上名称、大小、摘要与本地一致。

三个官网 APK 均从电脑完整下载并通过 SHA-256；当前 S20+ 使用系统 curl 完整收到 7,321,630 字节 arm64 APK，用时约 4.057 秒，摘要同样一致。数据对应当前设备／网络样本，详见[本次发布下载核验](official-download-throughput-2026-10-24.md)。

设备截图、原始日志、配对列表和配置快照仅保留在本地忽略目录 `work/review-validation/`。
