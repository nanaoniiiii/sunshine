# 无 HDMI 原生 DeX：120Hz 与息屏持续渲染验证

2026-10-15，Samsung SM-G986U1 / Android 13 / One UI 5.1，HDMI 保持物理断开，使用已安装的本地 Release 构建。接收端为 Windows Moonlight。此次只新增验证工具和记录，没有修改产品代码、提交或发布。

## 结论

**息屏持续渲染通过本轮实测；原生 DeX 120Hz 未通过。** 原生桌面实际仍为 1920×1080、60Hz。不能把 Moonlight 配置为 120fps 或显示超过 60fps 的接收统计当作桌面每秒生成 120 个新画面。

## 息屏实测

通过 Android `KEYCODE_SLEEP` 关闭主屏，用 `cmd display get-displays` 和 `dumpsys display` 确认内置屏幕为 OFF，原生 DeX 保持 ON。DeX 上的独立验证应用持续绘制动态计数器；同时采集应用 gfxinfo 帧时间、Sunshine 诊断和串流会话。结束后在 finally 中发送 `KEYCODE_WAKEUP` 恢复主屏。

| 场景 | 结果 |
| --- | --- |
| 60fps 客户端，90 秒连续息屏 | 6 次采样全部主屏 OFF；DeX 始终活动；应用帧时间持续推进；采集 58.70–60.70fps，中位数 60.05fps；Surface 失败数 0 |
| 120fps 客户端，180 秒观察 | DeX 始终活动、动画持续更新、Surface 失败数 0；12 次采样中 11 次主屏 OFF，约 93 秒处一次 ON |
| 息屏操作 | 点击与拖动均收到 Display 2 上的 DOWN/MOVE/UP，坐标与此前端到端映射一致 |
| 亮屏恢复与电源切换 | 主屏恢复后 DeX 和同一条 Windows 串流继续出图，未出现黑屏 |

180 秒观察与后续密集采样均包含手动电源键操作。系统日志记录 `power_button`，用户已确认当时按过电源键，不能把这两轮描述为全程连续息屏，也不能据此归因远程输入会自动唤亮手机。密集采样结束附近还短暂加入一个 90fps 客户端，因此该轮不是隔离的单客户端性能测试。

这验证的是手机主屏 OFF 时 DeX 持续工作。系统整体 `mWakefulness=Awake`，保留 DeX 的运行状态；没有验证让整个 SoC 深度休眠后继续生成实时画面，也没有进行小时级稳定性测试。

## 120Hz 实测与限制位置

1. 用 Moonlight 的 120fps 连接创建第二屏，Sunshine 状态显示 `requestedRefreshRate=120`，但 `actualRefreshRate=60`、`refreshRateRequestSupported=false`。
2. 系统 Desktop 虚拟显示和 `Sunshine Local DeX` 无线显示都仅公布 60Hz 模式。应用 gfxinfo 的有效帧间隔约 16.2–16.7ms，没有出现 120Hz 所需的约 8.33ms。
3. 执行 `cmd display set-user-preferred-display-mode 1920 1080 120 2` 后，用户首选模式仍为未设置，实际刷新率仍是 60Hz。测试后已清除该首选请求。
4. 180 秒样本中，120fps 配置的 HEVC 编码输出为 106.30–119.16fps，中位数 117.54fps，采集源中位数约 60.15fps。编码器会重用同一源画面，实际传输也并非稳定达到 120fps。
5. 对照从本机固件只读提取的实现：`VirtualDisplayAdapter.VirtualDisplayDevice` 构造及 resize 时均以 `60.0f` 创建模式；`WifiDisplayAdapter` 创建无线显示设备以及尺寸变化时也固定 `60.0f`。因此仅修改 Sunshine 编码 FPS、WFD 能力或客户端参数，不能使这条固件路径产生真实 120Hz 原生桌面。

没有修改系统服务、固件文件或全局主屏分辨率。1440×3168／120Hz 原生 DeX 目标尚未实现。

## 可复查证据

本机目录 `work/headless-dex/`：

- `refresh-screenoff-summary.json`：三轮采样的汇总。
- `sleep-60/`：90 秒连续息屏的原始显示状态、应用帧时间与 JSON 诊断；`input.log` 包含点击事件。
- `sleep-120-client/`：180 秒观察记录、拖动事件、显示设备信息及电源历史。
- `sleep-input-dense/`：约 1 秒实际间隔的密集采样，以及手动电源键亮屏的日志。
- `request120-result.json`：系统 120Hz 请求前后状态。
- `work/dex-firmware/VirtualDisplayAdapter.java`、`WifiDisplayAdapter.java`：本机固件只读分析材料，不作为发布内容。

复测工具 `tools/headless-dex/validate_runtime.py`，需先连接 Moonlight、创建原生 DeX，并在 Display 2 启动独立验证应用：

```powershell
python tools/headless-dex/validate_runtime.py --adb C:\Users\31919\AppData\Local\Android\Sdk\platform-tools\adb.exe --serial 192.168.31.150:42249 --host 192.168.31.150 --output work/headless-dex/sleep-repeat --seconds 180 --sleep
```

无线调试端口需按当前设备更新。原始显示状态文件和应用帧时间都应核对，不能仅依据串流 FPS 判断息屏渲染通过。
