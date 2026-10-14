# 原生视频控制与 Shizuku 兼容性

当前版本使用官方 Sunshine 协议核心和本项目独立实现的 Android 编码、输入与诊断适配。
每会话的码率、关键帧、FEC 与断连控制由本项目 Android 后端执行。

## 行为

- 原生编码器遵守客户端请求的低帧率，不再把 30 FPS 强制提升到 60 FPS。`max_fps` 是上限；编码器帧率的更改在新会话生效，宿主画面调度可立即更新。
- `min_bitrate`、`max_bitrate` 范围为 1000–150000 kbps，要求最小值不大于最大值。限制可立即作用于活跃会话；始终不超过客户端协商预算，预算低于下限时尊重客户端预算。
- 每会话支持手动码率、关键帧和断连。命令在视频线程上执行；请求成功代表排队接受，需读诊断确认驱动结果。失败命令不忙循环重试，显式请求可重试。
- 诊断返回实际 H.264/HEVC、MediaCodec 名称、请求/配置 FPS、目标/已应用码率、输出 FPS/码率、帧数、关键帧、无效缓冲与驱动状态。输出停止超过两秒后，输出 FPS/码率显示为零。
- `submit_to_output_p95_ms` 是最近最多 256 个有效样本的 EGL 提交时间戳到编码器输出的 P95；不是纯硬件编码时间或端到端延迟。编码器输出码率也不包含 FEC、音频和网络开销。
- MediaCodec 输出按正确的 offset/size 读取并检查边界；HEVC 创建失败会终止该会话，不再假装用不匹配的 H.264 参数继续。Android 后端逐数据报发送并记录成功和失败，避免批量发送部分成功后重复发送数据。

## API

先读取 `GET /api/runtime/sessions` 或 `GET /api/diagnostics` 的 `video` 字段获取 `session_id`，再请求：

| 请求 | JSON 请求体 |
| --- | --- |
| `POST /api/runtime/sessions/{session_id}/bitrate` | `{"bitrate_kbps":6000}` |
| `POST /api/runtime/sessions/{session_id}/keyframe` | `{}` |
| `POST /api/runtime/sessions/{session_id}/disconnect` | `{}` |

码率越界、错误类型返回 400；失效会话返回 404；并发会话/配置变化导致原生拒绝时返回 409。会话 ID 随引擎进程和会话变化，不应缓存用于下次连接。`bitrate_status`/`idr_status` 为 MediaCodec 返回值，0 表示成功；`applied_bitrate_kbps` 表示驱动接受的目标，不保证每秒实际输出恰好达到此数值。

配置保存改为显式解析 JSON 标量，避免 R8 压缩 Release 包后匿名 Gson TypeToken 缺失类型参数。校验合并后的配置，防止单字段更新造成 min/max 不一致。

## Shizuku

旧 ShizukuProvider 用 `android.permission.INTERNET` 保护，而一些系统的 ADB shell 没有请求该权限，导致已经授权却收不到 Binder。改用 [Shizuku 官方配置](https://github.com/RikkaApps/Shizuku-API#shizuku) 的 `android.permission.INTERACT_ACROSS_USERS_FULL`，保留权限保护。Binder 丢失时清除输入绑定，重新收到后恢复；权限结果即时刷新。

这一原因可能影响多个厂商/系统版本。OPPO Android 11 已实测；不能据此保证所有系统的 Shizuku 限制都已解决。无需卸载、清空数据或尝试给 shell 强行授予 INTERNET。

## 静音视觉诊断

该 Activity 只有 Canvas 绘图和触摸日志，没有音频源或播放代码。使用前保持手机与 PC 静音，明确启动新任务，避免系统复用主界面：

```sh
adb shell am start -W --activity-multiple-task -n com.alkaidlab.sunshine/.ui.StreamTestActivity
adb shell am start -W --activity-multiple-task -n com.alkaidlab.sunshine/.ui.StreamTestActivity --ez animate false
```

第二条用于静态页面/关闭补帧验证。回归范围见 [本地验证记录](validation/v2026.10.12.2.md)。
