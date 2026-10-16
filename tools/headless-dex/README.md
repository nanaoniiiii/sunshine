# 无 HDMI 原生 DeX 实验原型

> 后续已通过本机 DeX for PC 接收端实现并集成应用，生产代码位于 `app/src/main/java/com/alkaidlab/sunshine/service/headless/`。见 [集成实测记录](../../docs/validation/headless-dex-integrated-2026-10-15.md)。此目录中的 `run.py` 仍测试早期直接 Binder 路径，下文“未成功”仅指该路径。

`validate_runtime.py` 用于已运行的真实串流采样，可选 `--sleep` 关闭主屏并在 finally 中恢复。它保留显示状态、应用帧时间和 Sunshine 诊断，区分源显示刷新率与编码 FPS。见 [高刷与息屏验证](../../docs/validation/dex-refresh-screenoff-2026-10-15.md)。

这是一个自主编写的 shell / Shizuku 用户服务级原型，尝试使用**本机回环无线显示接收端**触发三星原生无线 DeX。它不把普通虚拟桌面作为成功结果，不修改系统特性文件、不修改 HDMI/sysfs，也不伪造测试通过。

**当前设备结果：未成功。** Samsung SM-G986U1 / Android 13 / One UI 5.1 的 shell UID 2000 在真实 Binder 调用处被拒绝：

```text
java.lang.SecurityException:
Permission required to connect to a wifi display:
Neither user 2000 nor current process has android.permission.CONFIGURE_WIFI_DISPLAY.
```

此拒绝发生在无线显示服务接收请求之前，所以手机上还没有进入 RTSP 握手。协议测试通过仅代表原型能与自建的本机测试端交互，不证明三星厂商引擎已兼容或原生 DeX 已激活。

## 实现

- `HeadlessDex.java`：检测当前用户身份、Standalone 能力、现有 DeX 与外接显示，构造三星 `SemWifiDisplayConfig` 的本机 AP 连接（无线 DeX 模式 2），实际调用 `IDisplayManager.connectWifiDisplayWithConfig()`。
- `LocalWfdSink.java`：最小 WFD RTSP 控制接收端，实现 OPTIONS、能力协商、SET_PARAMETER、SETUP、PLAY 和 UDP RTP/RTCP 消费；仅连接/绑定 `127.0.0.1`。没有视频解码功能，不宣称收到包等于有正确画面。
- `LocalWfdSinkTest.java`：碎片化消息、长度边界、重复/非法头、完整模拟 M1–M7 控制序列、Session/端口协商、RTP/RTCP 收包与资源关闭测试。
- `run.py`：构建、测试、部署和结构化报告。默认只诊断；只有显式 `--attempt` 才请求创建会话。

原型拒绝覆盖已有 DeX、外接显示或无线投屏会话。请求实际被接受后，最多观察约 20 秒并在 finally 中关闭本原型请求的会话及 socket；这是短时实验工具，不是保持桌面的后台产品。

## 运行

需要 JDK 17+、Python 3、ADB、Android SDK platform 34 与 build-tools 34.0.0。源码位于仓库 `tools/headless-dex`，输出位于 `work/headless-dex`。

只测试协议，不连接手机：

```powershell
python tools/headless-dex/run.py --test-only
```

读取设备能力并构建 DEX，不请求进入 DeX：

```powershell
python tools/headless-dex/run.py --serial 192.168.31.150:42249
```

真实尝试（没有 HDMI、现有 DeX 或投屏连接时）：

```powershell
python tools/headless-dex/run.py --serial 192.168.31.150:42249 --attempt
```

无线调试地址会变化，运行前用实际地址替换。退出码：0 为诊断完成或观察到原生 DeX 进入状态；3 为拒绝/失败；4 为会话清理失败。即使观察到状态成功，也仍需独立验证捕获画面与触控。

Shizuku 用户服务以 ADB 权限启动时，与本次 UID 2000 的限制相同。将代码放进 APK 或改用 Shizuku 代理不会自动获得这个签名权限。

## 本轮实测

- JVM 协议测试通过。
- Android DEX 构建并推送成功。
- 设备诊断：`wifi_permission=-1`、`standalone_supported=false`、`desktop_debug=false`。
- 设备实际尝试：`stage=connect_wifi_display_binder`、`result=blocked_by_system_permission`，退出码 3。
- 请求未被接受；没有创建无线显示，不需要断开其他用户会话。
- 系统 DeX 保持关闭，实验结束后独立核对显示与系统设置。

没有发现可用的厂商委托入口：本机 DeXonPC 主服务未导出，发现服务要求三星自定义签名权限。未向这些服务发送伪造的硬件/紧急状态广播。只读提取的厂商 APK 与反编译材料留在本地 work 目录，不包含在原型交付包内。

## 后续适配边界

要继续在本机实际验证回环 WFD，需要运行主体具备 `CONFIGURE_WIFI_DISPLAY`，或找到三星允许第三方合法调用的连接入口。当前原型没有突破该限制。不得据此宣称 Root 后一定成功：厂商 WFD 引擎的回环连接、协议扩展以及 DeX 画面仍需继续验证。

Standalone 是另一条路径：本机系统没有启用该特性。这个原型没有安装 Root 模块或修改系统分区；用户原先要求的独立第二屏与 Standalone 占用主屏也不是同一目标。

控制消息设计参考 AOSP 的 WFD 交互流程；代码为本项目自行实现：

- [AOSP WifiDisplaySink](https://android.googlesource.com/platform/frameworks/av/+/8060060/media/libstagefright/wifi-display/sink/WifiDisplaySink.cpp)
- [AOSP WifiDisplaySource](https://android.googlesource.com/platform/frameworks/av/+/54408d8/media/libstagefright/wifi-display/source/WifiDisplaySource.cpp)

三星反射接口来自本设备系统框架的只读核对。该工具只针对当前三星实验环境，不提供跨厂商兼容保证。
