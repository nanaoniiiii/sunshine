# 无 HDMI 原生 DeX：应用集成与实测

2026-10-15，Samsung SM-G986U1 / Android 13 / One UI 5.1，HDMI 已由用户物理断开。代码、测试包均保留本地，未提交或发布。

## 已实现

在 Shizuku 用户服务中运行自行实现的 DeX for PC 本机接收端。三星系统 DeX 列表显示 `Sunshine Local DeX`，确认“立即开始”后，由系统自己的 DeXonPC 完成认证并启动原生双屏 DeX。Sunshine 用 SurfaceControl 采集真实 DeX Display 2，并把输入映射到该显示器。

这条路径不需要 HDMI、Root 或 `CONFIGURE_WIFI_DISPLAY` 权限。它仍依赖此设备自带的 DeX for PC 系统组件、64 位 Shizuku 用户服务和有 IPv4 地址的 wlan0。发现公告使用 Wi-Fi 广播；接收端监听、IDC、WFD/RTP 使用本机回环，控制连接仅连回手机自身。厂商加密类与原生库在运行时从手机已有的系统 DeX APK 加载，不随 Sunshine 分发厂商文件。

## 使用流程

1. 启动 Sunshine 并授权 Shizuku，连接 Moonlight 的 Desktop。
2. 在实验功能中启用三星原生 DeX，打开第二屏。
3. 在三星主屏弹出的 DeX 页面选择 `Sunshine Local DeX`（已记住时会直接弹出确认），确认“立即开始”。等待期间保留主屏采集和输入，可通过 Moonlight 操作确认。
4. 系统进入原生 DeX 后，串流切到真正的独立桌面。退出第二屏或结束最后一个串流时，关闭本次本机接收端；已有外接 DeX 会话仍按原有逻辑处理。

## 实测结果

| 检查 | 结果 |
| --- | --- |
| 无 HDMI 创建原生 DeX | 成功，系统 Display 2、双屏 DeX 状态启用 |
| 实际桌面与刷新率 | 1920×1080、60Hz；当前协商能力为 1080p30/60 |
| Windows Moonlight 画面 | 1280×720、HEVC、约 60fps；实际可见 DeX 任务栏与动态测试画面 |
| 点击和拖动 | Moonlight 输入送达 Display 2 上的独立验证应用，收到 DOWN/MOVE/UP |
| 直接映射回归 | 原型阶段 68/68 个事件收到，无错误，最大坐标误差约 0.00006px |
| 持续运行 | 已跨越最初约 30 秒断开的故障点；原型连续运行超过 8 分钟，集成版持续采集正常 |
| 退出再创建第二屏 | 同一 Moonlight 会话中退出后重新创建成功，有连续动画画面 |
| 断开并重连 Moonlight | 旧会话结束、原生 DeX 连接关闭；新会话恢复主屏，再确认创建 DeX 后恢复桌面画面 |
| 打开 DeX 应用时宿主存活 | 加入三星多显示应用元数据后通过；宿主 PID 在应用启动、退出、重建和重连期间保持不变 |

关闭后系统可能保留状态为 OFF 的 Desktop 虚拟显示对象；不能以 `dumpsys display` 中仍有 Display 2 判定 DeX 仍在连接。需同时核对无线显示无活动连接、桌面模式退出与 Sunshine 状态。

## 实现中的关键修正

- WFD RTSP 每 4 秒发送带 Session 的 GET_PARAMETER，保持三星本机接收会话。
- 能力改为 1080p30/60，避免 720p 协商触发三星分辨率不支持提示。
- 主应用声明三星 `multidisplay.primarydisplay=default` 与 `keep_process_alive=true`，避免打开 DeX 应用时宿主被三星跨屏进程管理终止。
- 切换采集时保留已准备好的本机接收端；镜像和接收端跟随用户服务所有者 Binder 及串流生命周期清理。
- 控制协议帧单次写入，响应沿用请求 SID；帧中途超时终止连接，避免残帧被误读为下一帧。

## 验证与证据

`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` 全部通过，74 项测试、0 失败、0 错误。JVM 回归包括输入坐标映射，以及 RTSP 分片解析、大小限制、模拟完整握手、RTP/RTCP 接收与关闭；真实三星兼容性以手机和 Moonlight 实测为准。

最终 arm64 Release APK 已本地安装复测，无 HDMI 启动原生 DeX、动态画面和 Display 2 点击通过。SHA-256：`9F8207DA14C08B4199BA8E88E83C26F440099FE1C18332001FB8C1B957B4AEAD`。这是本地构建验证，没有创建 GitHub Release。

本机证据位于 `work/headless-dex/`：`native-input-result.json`、`integrated-native.png`、`e2e-input.log`、`e2e-recreated-diagnostics.json`、`e2e-reconnected-diagnostics.json`、`release-input.log`、`release-diagnostics.json`、`final-validation.log`。其中工作目录还包含只读提取的厂商分析材料，不作为交付物或发布内容。

## 后续高刷与息屏验证

见 [120Hz 与息屏持续渲染实测](dex-refresh-screenoff-2026-10-15.md)：90 秒连续主屏 OFF 渲染及操作通过，180 秒电源切换观察未出现黑屏；120fps 连接已测试，但本机固件的原生 DeX 源显示仍固定 60Hz。

## 仍需验证

- 1440×3168／120Hz 尚未在本机原生 DeX 路径实现；客户端请求高分辨率或 120fps 不等于 DeX 源显示器已达到该模式。
- 小时级息屏持续渲染尚未覆盖。
- Android Moonlight 接收端的多指与手势尚需实机覆盖，本轮端到端接收端为 Windows Moonlight。
- 其他三星机型、固件或移除 DeX for PC 的版本尚未验证。
- 本次重连未见黑屏，但有限次数通过不代表偶发黑屏已被完全消除。
