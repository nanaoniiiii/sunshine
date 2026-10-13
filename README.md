# Sunshine for Android

把 Android 手机或平板作为串流主机，在 Moonlight 上接收画面与声音，并远程操作。

[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/nanaoniiiii/sunshine/releases)
[![Release](https://img.shields.io/github/v/release/nanaoniiiii/sunshine)](https://github.com/nanaoniiiii/sunshine/releases/latest)
[![Website](https://img.shields.io/badge/官网-nanaoni.tech-335BCC)](https://nanaoni.tech)
[![Shizuku](https://img.shields.io/badge/远程输入-Shizuku-6750A4)](https://github.com/RikkaApps/Shizuku)

**[访问官网](https://nanaoni.tech) · [下载安装包](https://github.com/nanaoniiiii/sunshine/releases/latest) · [常见问题](docs/faq.md) · [反馈问题](https://github.com/nanaoniiiii/sunshine/issues)**

作者：[七鬼 · nanaoniiiii](https://github.com/nanaoniiiii)。官网中的交互设备为模拟演示；下面的 Android 页面与 Moonlight 接收画面来自实际设备截图。

## 实机与官网

<table>
  <tr><th>Sunshine 主机 · OPPO Android 11</th><th>常见问题 · 设置 → 维护</th><th>官网 · nanaoni.tech</th></tr>
  <tr>
    <td><img src="docs/images/sunshine-device.png" alt="OPPO 真机上的 Sunshine 主页面，服务运行中" width="280"></td>
    <td><img src="docs/images/faq-device.png" alt="真机上的常见问题页面" width="280"></td>
    <td><a href="https://nanaoni.tech"><img src="docs/images/website.png" alt="Sunshine 官网首屏实景" width="280"></a></td>
  </tr>
</table>

<details>
<summary>查看 Moonlight 接收画面与关于栏</summary>

<table>
  <tr><th>Moonlight 客户端 · 三星 Android 13</th><th>项目介绍、作者与链接</th></tr>
  <tr>
    <td><img src="docs/images/moonlight-device.png" alt="三星 Moonlight 客户端接收 1440×3168 竖屏像素测试图，v2026.10.14.1 实测" width="320"></td>
    <td><img src="docs/images/about-device.png" alt="设置中的关于栏，显示版本、作者、官网和仓库入口" width="320"></td>
  </tr>
</table>

Moonlight 图来自 v2026.10.14.1 的竖屏串流验证，设备、配置与限制见[验证记录](docs/validation/v2026.10.14.1.md)。主页、常见问题及关于栏图来自 v2026.10.14.2。

</details>

## 项目能力

- **硬件编码**：通过 Android AMediaCodec 提供 H.264 / HEVC 编码，设置中可选择系统默认或指定编码器。可用分辨率、帧率与编码能力取决于设备。
- **显示与全屏**：默认使用主机当前显示尺寸采集；可在主页按某一路连接的请求分辨率切换全屏，最后一路退出后恢复保存的显示设置。不同宽高比的连接共用主机画布。
- **远程输入**：通过 Shizuku 代理触控、鼠标和键盘操作，免 Root 使用仍受系统与厂商权限限制。
- **音频内录**：Android 10+ AudioPlaybackCapture 与 Opus 推流；来源应用是否允许捕获会影响声音输出。
- **设备与应用**：Moonlight PIN 配对、连接状态、桌面和应用入口管理。
- **服务恢复与诊断**：独立引擎进程、最后一路退出后的延迟恢复、有限长度音视频队列、每连接诊断及运行日志。
- **应用内帮助**：“设置 → 维护 → 常见问题”包含全屏、横屏、Shizuku、配对和品牌差异；“关于”提供官网、作者和仓库入口。帮助无需联网即可阅读，外部链接需要联网。

## 快速开始

1. 准备 Android 10 或以上手机／平板，以及能运行 Moonlight 的电脑、手机或其他客户端，两端接入同一可互通的局域网。
2. 在 [Releases](https://github.com/nanaoniiiii/sunshine/releases/latest) 下载 APK：多数现代手机选 `arm64-v8a`；32 位 ARM 选 `armeabi-v7a`；不确定时选 `universal`。若通用包以 ZIP 提供，请先解压其中的 APK 再安装。
3. 安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku/releases)，按[官方手册](https://shizuku.rikka.app/zh-hans/guide/setup/)完成无线调试或电脑 USB 启动。免 Root 重启手机后需重新启动，配对通常无需重做。
4. 打开 Sunshine，在主页授予 Shizuku 及提示的录屏、录音、通知权限，确认服务运行，记下主机 IP。
5. 在 Moonlight 添加该 IP，点击主机，在 Sunshine 弹窗或“设备”页输入 **Moonlight 显示的 4 位 PIN**，然后选择桌面或应用开始串流。

系统无线调试配对用于启动 Shizuku，Moonlight PIN 配对用于连接 Sunshine，是两个独立流程。

## 常见问题

- **无法全屏、竖屏只有中央小块**：核对客户端实际请求宽高、屏幕方向、交换宽高、刘海区域和视频缩放选项，修改分辨率后重新连接。Sunshine 主页“全屏”按钮只能调整主机画布。[详细步骤](docs/faq.md#moonlight-画面不全屏或竖屏变成小块)
- **横屏异常**：尝试 Sunshine“自动旋转”与系统方向开关；找不到系统选项时退出全部串流以恢复。部分桌面本身不支持横屏。[详细步骤](docs/faq.md#横屏异常方向锁定或退出后恢复)
- **Shizuku 已启动但权限不足、授权后掉权**：核对应用授权、后台限制及厂商开关，必要时从官方 GitHub 或 Google Play 更新或尝试兼容版本。[品牌设置与排查](docs/faq.md)

“填充”可能裁切，“拉伸”可能变形；同时连接不同比例的客户端时可能留边。更新 Shizuku 不能保证解决全部厂商限制。具体设置名称以本机系统版本为准。

## 从源码构建

应用使用 Kotlin、Jetpack Compose / Material 3 与原生 Android 串流引擎。需要 JDK 17、Android SDK 34；最低 SDK 29。仓库包含两种 ARM ABI 的原生库。

```powershell
git clone https://github.com/nanaoniiiii/sunshine.git
cd sunshine
./gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

macOS／Linux 使用 `./gradlew`。APK 位于 `app/build/outputs/apk/release/`。当前发布构建启用 R8 和资源压缩，并沿用项目现有签名配置；其他环境自行构建的签名可能不同。

需要重新构建原生引擎时，请先阅读[原生构建说明](native/README.md)。`native/sources.lock.json` 固定上游和依赖版本；`native/patches/android-host.patch` 保存 Android 主机修改。

## 工程记录

- [v2026.10.14.2：帮助、关于与仓库更新](docs/validation/v2026.10.14.2.md)
- [v2026.10.14.1：默认竖屏、按连接全屏与退出恢复](docs/validation/v2026.10.14.1.md)
- [后端优化与能力边界](docs/backend-optimization.md)
- [原生视频控制](docs/native-video-controls.md)
- [网络反馈](docs/network-feedback.md)
- [队列、编码器与远程唤醒](docs/queue-encoder-controls.md)

单元测试覆盖会话恢复、设置校验、采集几何及全屏恢复等逻辑。音视频、输入、OEM 权限和长期稳定性仍需要设备验证；实验性反馈自适应与 FEC 默认关闭。

## 开源来源与致谢

- [Sunshine](https://github.com/LizardByte/Sunshine)：GameStream 服务端。
- [kumu-ze/connect-screen.com](https://github.com/kumu-ze/connect-screen.com)：Android 原生移植来源；其 GPLv3 许可保存在 [native/LICENSE.upstream](native/LICENSE.upstream)。
- [Moonlight](https://github.com/moonlight-stream)：串流客户端与协议生态。
- [Shizuku](https://github.com/RikkaApps/Shizuku)：Android 权限代理。
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 与 [JmDNS](https://github.com/jmdns/jmdns)：HTTP 服务与网络发现。

各组件遵循各自的开源许可。
