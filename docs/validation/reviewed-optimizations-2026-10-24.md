# 优化候选版升级与实机验证

初次候选为 `v2026.10.24.1` / `20261041`，功能源码提交为 `fbc6b14396f6be06cda6cc12a2cb19f86e0cad5e`。继续检查发现锁屏状态判断误拦，修正版为 `v2026.10.24.2` / `20261042`。设备为三星 S20+ `SM-G9860` / Android 13，当前主用户；接收端为 Windows Moonlight。两个版本的事实分别记录，此记录补充[开发分支审查](../reviews/developer-optimizations-2026-10-24.md)中的发布 APK 验证，不用函数级微基准代替完整应用检查。

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

执行工具拒绝了随后准备连接及启动电脑 Moonlight 的复合命令，仅返回 `blocked by policy`，未改写重试启动串流。已请求用户完成电脑连接，等待继续修正版的 DeX 创建、普通虚拟桌面、默认息屏和接收端输入检查。旧候选的实际串流读数不转记为修正版结果。候选尚未公开发布；之前正式版的[三种显示模式验证](fresh-install-permissions-2026-10-23.md)也不代替修正版创建验收。

拒绝来源的表述另作更正：当前会话及本地配置均为 `approval_policy=never / sandbox_mode=danger-full-access`；本地常规命令规则只有允许项。日志能定位到 `codex_core::tools::router` 记录执行前的 `CreateProcess … Rejected … blocked by policy`，没有具体审核理由或对应的 Auto-review 决策记录。按照 [OpenAI 官方 Auto-review 说明](https://learn.chatgpt.com/docs/sandboxing/auto-review)，`never` 不触发其交互式审批审核。因此不能把这次错误确定归因为该 Auto-review 功能、用户缺少授权，或 Moonlight 本身被禁用；具体命中哪一步及哪条策略仍未知。

设备截图、原始日志、配对列表和配置快照仅保留在本地忽略目录 `work/review-validation/`。
