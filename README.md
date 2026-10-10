# Sunshine for Android 📱⚡

<div align="center">

![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029--34)-green?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack-Compose-blue?logo=jetpackcompose&logoColor=white)
![Hardware Encoder](https://img.shields.io/badge/Encoder-AMediaCodec%20(Qualcomm%2FMediaTek)-orange)
![Performance](https://img.shields.io/badge/Streaming-175%20FPS%20Ultra--Low%20Latency-brightgreen)
![Privilege](https://img.shields.io/badge/Input-Shizuku%20(Root--Free)-teal)

**运行在 Android 设备上的高性能 Sunshine 串流服务端，支持 Moonlight 客户端进行超低延迟、高帧率远程桌面与游戏控制。**

</div>

---

## 🌟 核心特性 (Key Features)

### 1. 🚀 硬件级视频加速与超高帧率 (Hardware Video Encoding)
* 深度集成 **AMediaCodec (Qualcomm / MediaTek)** 硬件编码器，原生支持 **HEVC (H.265)** 与 **H.264** 硬件推流。
* 实测支持高达 **175 FPS**、10~50 Mbps 动态码率串流，编码耗时低至 **2~3 ms**。
* 自动协商颜色空间（Rec. 709 / Limited Range），保障画面色彩饱满真实。

### 2. 🔄 零点击自愈无缝重连 (Zero-Click Rebirth Architecture)
* 彻底解决原生 C++ 单例在客户端退出后难以重复初始化（Socket 挂起、死锁）的痛点。
* 会话断开时触发 OS 级进程涅槃与平滑自愈机制（利用 Shizuku 特权 Shell 与 AlarmManager 双保险），在 **1~2 秒内自动重生**，无需在手机端手动重启应用，支持任意次数的断开与立即重连。

### 3. 🎯 像素级自适应触控与手势映射 (Adaptive SunshineMouse Engine)
* **自动比例计算**：动态检测全面屏（如三星 20:9、19.5:9 等）物理像素尺寸，自动计算 Letterboxing / Pillarboxing 黑边偏移。
* **横竖屏自适应**：无论手机竖屏还是横屏，自动重映射坐标视口。
* **手势与拖拽优化**：针对 Moonlight 触控协议重写 `bufferedMove` 缓冲区与 Pointer 追踪，精准支持单指点按、连续长按拖拽、多指缩放与手势操作。

### 4. 🛡️ Shizuku 免 Root 特权注入 (Root-Free Privileged Injection)
* 集成 **Shizuku API**，免 Root 获取系统级 `injectInputEvent` 权限，支持高精度触摸、鼠标移动和虚拟键盘按键事件。
* 自动授予免弹窗录屏特权（`PROJECT_MEDIA allow`）以及系统省电优化白名单（`deviceidle whitelist`），彻底杜绝系统后台杀进程或冻结（如三星 One UI `FreecessController`）。

### 5. 🔊 AudioPlaybackCapture 音频内录
* 原生调用 Android 10+ 媒体内录通道（`AudioPlaybackCaptureConfiguration`），以 48 kHz 立体声 PCM 实时录制媒体音频并进行低延迟 Opus 编码推流。

### 6. 🎨 现代化 Material 3 Jetpack Compose UI
* **仪表盘 (Dashboard)**：一览当前服务运行状态、局域网 IP、推流端口、硬件编码器、权限状态以及实时 FPS / 码率监控。
* **设备配对 (Pairing)**：客户端请求 PIN 码时自动弹出交互窗口，亦可在配对管理界面输入 PIN 码一键配对。
* **应用管理 (Apps)**：配置和管理客户端可见的推流桌面与应用快捷方式。
* **日志面板 (Logs)**：内置实时日志滚动查看器，方便排查连接与推流状态。

---

## 📲 快速开始 (Quick Start)

### 准备环境
1. 运行 Android 10 (API 29) 及以上系统的 Android 手机 / 平板（推荐骁龙处理平台）。
2. （强烈建议）安装并激活 [Shizuku](https://shizuku.rikka.app/)，用于免 Root 触控注入与全自动自愈重连。

### 安装与运行
1. 前往本仓库 [Releases](../../releases) 页面下载最新的 `Sunshine-Android.apk`。
2. 安装后打开应用，授予基础通知与录音权限。
3. 若已启动 Shizuku，应用将自动完成免 Root 特权激活，仪表盘中的系统权限将全部点亮为绿色勾选状态。
4. 在仪表盘确认**服务运行中**（显示手机局域网 IP 与 47989 端口）。

### 客户端连接 (Moonlight)
1. 在同一局域网下的 PC、笔记本或另一台手机上启动 **Moonlight Game Streaming**。
2. 添加手机的局域网 IP 地址（例如 `192.168.31.150`）。
3. 首次连接时，在手机端弹出的输入框中输入 Moonlight 屏幕上的 4 位 PIN 码完成配对。
4. 点击 **Desktop** 应用，即可开启超低延迟高帧率串流！

---

## 🛠️ 编译与构建 (Build from Source)

### 要求
* JDK 17
* Android SDK (Compile SDK 34, Min SDK 29)
* NDK 27.2+ (`27.2.12479018`)

### 构建命令
```bash
# 克隆仓库
git clone https://github.com/nanaoniiiii/sunshine.git
cd sunshine

# 编译 Debug APK
./gradlew assembleDebug

# 生成的 APK 位于: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📜 开源协议与致谢 (License & Acknowledgements)

本项目参考并集成以下优秀的开源项目：
* [Sunshine](https://github.com/LizardByte/Sunshine) - 自托管 GameStream 服务端
* [Moonlight](https://github.com/moonlight-stream) - GameStream 客户端协议实现
* [Shizuku](https://github.com/RikkaApps/Shizuku) - Android 免 Root 权限代理
* [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) - 轻量级嵌入式 HTTP 服务
* [JmDNS](https://github.com/jmdns/jmdns) - mDNS 零配置网络发现
