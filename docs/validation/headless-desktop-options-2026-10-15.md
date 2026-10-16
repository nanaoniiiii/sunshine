# 无 HDMI 桌面模式：方案检索与设备验证

> 后续已自行实现本机 DeX for PC 接收端，无需另装 Windows DeX 软件即可在本机启动原生 DeX；见 [集成实测](headless-dex-integrated-2026-10-15.md)。以下为实现前的方案检索记录。

2026-10-15。目标设备：Samsung SM-G986U1，Android 13 / One UI 5.1（系统属性 `ro.build.version.oneui=50100`）。

## 可行路线

| 路线 | 是否为原生 DeX | 额外条件 | 对当前目标的结论 |
| --- | --- | --- | --- |
| 无线 DeX | 是 | 支持 Miracast 的接收端 | 三星官方列出 S20 系列支持；不需要 HDMI，但仍需接收端建立连接 |
| DeX for PC | 是 | Windows 10/11 电脑、DeX for PC 软件及 USB 数据线，或支持的无线连接 | 三星官方列出 S20 系列支持；手机仍安装 `com.sec.android.app.dexonpc`。电脑未发现已安装的 DeX 客户端。本轮未安装、未连接验证 |
| Sunshine 独立第二屏 + Android 自由窗口 | 否 | 现有 Shizuku/Stellar 权限 | 已在当前设备无 HDMI 状态下验证独立显示、自由窗口与多帧采集 |
| DeX Standalone Magisk/KernelSU 模块 | 是，运行在主屏的独立模式 | Root 与匹配的 One UI 固件 | 作者提供 One UI 8 以前的方案，但会改变主屏模式，不直接满足主屏与独立 DeX 第二屏并存；本轮没有安装 |
| LocalDex / InnerDesk / DX Manager 的纯虚拟屏方案 | 取决于新系统支持 | 通常需要 One UI 8+ | 不能根据它们的宣传推断 One UI 5.1 可用；本轮追加验证 DX Manager 的具体 overlay 命令，未激活 DeX |

无线 DeX 或 DeX for PC 建立系统 DeX 后，现有 `NativeDexMirror` 的设计可以复用活动 DeX Display；这是基于本地实现的接入推断，尚未完成这两种入口与 Sunshine 的串流实测。

## 一手来源

- [三星官方 DeX 使用与兼容性](https://www.samsung.com/us/support/answer/ANS10001955/)：S20 系列的无线 DeX、DeX on PC 支持及 USB 连接步骤；One UI 7+ 不支持旧 DeX for PC。
- [三星开发者：DeX 工作方式](https://developer.samsung.com/samsung-dex/how-it-works.html)：S20 系列无线 DeX 与 DeX for PC 适用范围。
- [DX Manager 用户指南](https://github.com/maze-mei/DX-Manager/blob/main/docs/USER_GUIDE_EN.md)：已验证基线 Android 16 / One UI 8.x，更早版本未确认可靠。
- [DX Manager 显示实现](https://github.com/maze-mei/DX-Manager/blob/main/DexManager.Core/Services/VirtualDisplayService.cs)：`BuildOverlaySetting()` 默认生成 `WxH/DPI,hdmi`，再写入 `overlay_display_devices`。
- [LocalDex Requirements](https://github.com/sam1am/localdex#requirements)：明确要求 One UI 8+。
- [LocalDex 技术说明](https://github.com/sam1am/localdex/blob/main/docs/TECHNICAL.md)：新系统上创建虚拟屏及强制 freeform 的实现。
- [InnerDesk](https://github.com/zanderp/inner-desk)：支持三星 One UI 8 和 Pixel Android 16。
- [Dextop Compatibility](https://github.com/NarYuki/Dextop#compatibility)：One UI 8 以前为兼容性有限且很可能不兼容。
- [DeX Standalone 模块](https://github.com/supermarsx/magisk-samsung-dex-standalone-mode)：修改 `SEC_FLOATING_FEATURE_COMMON_CONFIG_DEX_MODE`，增加 standalone；需要 Magisk/KernelSU Root。作者说明该模式直接使用手机屏幕。

## 新增设备实验：DX Manager overlay 方法

检查原 `overlay_display_devices=null` 后，在用户已断开 HDMI 的条件下，临时执行作者实现对应的命令：

```text
settings put global overlay_display_devices 1920x1080/240,hdmi
```

结果：创建 Display 8，`type=4`（OVERLAY），而非外接 HDMI 类型；DeX 保持 `enabled=2,type=0`。本机框架的 OverlayFlags 不解析 `hdmi`，仅处理 secure、own_content_only、should_show_system_decorations。这解释了此新系统方案不能直接在本机生效。

finally 路径已恢复 `overlay_display_devices=null`，再次读取只剩主屏 Display 0。没有遗留模拟屏。

## 新增设备实验：现有 Sunshine 自由窗口桌面

通过本轮调试 APK 的 `VirtualDisplayUserService` 创建普通第二屏（1920×1080、240 dpi），不开启三星 DeX 请求，调用已有 `launchApp()` 以 freeform 模式打开验证 Activity。

最终一次验证：

- 独立 Display 9，`launcher=Sunshine 桌面`、`dex_active=false`。
- 验证应用任务 `windowingMode=5`（freeform），边界 `[480,81][1440,999]`，`visible=true`。
- `semDesktopModeEnabled=0`，因此这是 Android 普通自由窗口桌面。
- ImageReader 收到 26 帧；输入接口接受 DOWN/UP 请求，随后帧数增加至 27。没有取得这次验证应用的触控接收日志，不据此宣称输入端到端验收完成。
- 已关闭自己的显示和捕获资源，并恢复创建前的系统设置。

首次探针将 `frames > 10` 作为启发式阈值时刚好收到 10 帧，因而未判通过。后续改为检查多帧采集、freeform 实际任务状态、输入接口返回及输入后的帧更新；最终探针使用明确的 SOURCE_TOUCHSCREEN。采集数量不用于推断 60/120 FPS 或长期稳定性。

## 本地材料

- `work/FreeformDesktopProbe.java`
- `work/freeform-desktop-result.log`
- `work/headless-research/dx-hdmi-overlay-result.log`
- `work/headless-research/DX-VirtualDisplayService.cs`
- `work/headless-research/LocalDex-README.md`
- `work/headless-research/LocalDex-TECHNICAL.md`

本轮仅检索、读取第三方源码并执行可恢复的探针。没有安装第三方 APK、Root 模块，也没有修改生产代码或把普通桌面标记为原生 DeX。

## ADB/Shizuku 伪装 HDMI 或启动 Standalone 的权限核对

进一步在当前设备只读核对：

- ADB 身份为 `uid=2000(shell)`、SELinux `u:r:shell:s0`。
- `/system/etc/floating_feature.xml` 与 `/vendor/etc/floating_feature.xml` 的 DeX 配置均为 `dual,wireless,dexforpc`，没有 `standalone`。
- 运行时 `DesktopModeFeature.SUPPORT_STANDALONE=false`，`DesktopModeFeature.DEBUG=false`、`CoreRune.MD_DEX_EMULATOR=false`。
- `cmd desktopmode help` 返回 `No shell command implementation.`。本机 `DesktopModeService.onShellCommand()` 只在 DEBUG=true 时提供强制内部屏模式的命令。
- 两个 floating feature 文件为 root:root、0644；`/sys/class/dp_sec/dex` 为 system:system、0660。当前 shell 没有写入权限。本轮没有尝试修改文件或 sysfs。

因此，当前 ADB 权限启动的 Shizuku 不能直接打开未启用的 Standalone，也不能靠写设置或添加 `hdmi` 后缀创建硬件 EXTERNAL DisplayDevice。在用户服务自身进程反射改变特性字段，不会改变 system_server 进程已经加载的能力开关与预检器。

Root 的 Standalone 模块通过修改系统 floating feature 增加 standalone，并在系统启动时生效；这与只发送进入广播不同。伪装真正 HDMI 设备还涉及显示适配器/驱动或系统服务，不应把获取 Root 等同于该路线已成功。

证据：`work/headless-research/standalone-capabilities.log`。这次补充检查没有修改设备配置。
