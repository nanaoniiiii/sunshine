# 无 HDMI 原生 DeX：拔线冷启动验证

> 后续已实现本机 DeX for PC 接收端，并在无 HDMI 下启动原生 DeX；见 [集成实测](headless-dex-integrated-2026-10-15.md)。以下保留两条早期虚拟屏路径的失败记录。

2026-10-15，Samsung SM-G986U1 / Android 13，Shizuku/Stellar 的 shell 权限。

当前结论：**本机的普通虚拟屏、三星 Desktop 标记虚拟屏两条冷启动路径均未启用原生 DeX**。生产代码仍复用已运行的系统 DeX，尚无经过验证的无 HDMI 原生 DeX 启动实现。这些结果不等于穷尽所有可能的系统适配方案。

本轮读取设备系统实现与运行时能力：

- `CoreRune.SAFE_DEBUG=false`、`CoreRune.MD_DEX_EMULATOR=false`、`DesktopModeFeature.DEBUG=false`。
- 普通 VirtualDisplay 固定为 type 5，DeX 硬件管理器接收外部显示类型 2、1000、1001。
- DeX 强制外接显示调试入口在此固件中不可用；仅设置桌面启动器或创建名为 Desktop 的显示不足以证明原生 DeX 已启动。
- 当前 HDMI 外接显示 ID 42，2560×1440；原生 DeX `enabled=4,type=102`。未变更系统设置。

已编译并推送 `work/DexHeadlessProbe.java` 至 `/data/local/tmp/dex-headless-probe.dex`。只读能力检查完成；带 `attempt desktop` 参数在 HDMI 仍连接时正确拒绝操作（退出码 2），确认不会在用户拔线前中断现有 DeX。

用户手动拔线并通知后，先检查没有外接连接、DeX 是否已退出；若仍处于原有会话，不将继续显示视为无 HDMI 冷启动成功。符合冷启动条件后可分别执行：

```powershell
adb -s 192.168.31.150:34671 shell 'CLASSPATH=/data/local/tmp/dex-headless-probe.dex app_process / DexHeadlessProbe attempt'
adb -s 192.168.31.150:34671 shell 'CLASSPATH=/data/local/tmp/dex-headless-probe.dex app_process / DexHeadlessProbe attempt desktop'
```

探针分别检查普通/三星 Desktop 标记的虚拟屏，发送 DeX 进入广播并测试系统 Binder 的进入请求，记录真实桌面模式、显示列表和 Surface 帧数。只回收探针创建的显示，不发送全局 DeX 退出请求。成功条件还需确认系统原生桌面、实际画面和输入，不能仅以虚拟屏创建成功为依据。

本地日志：`work/dex-headless-capabilities.log`、`work/dex-headless-hdmi-guard.log`。

## 用户拔线后的实测结果

用户明确回复「已断开」后执行。初始只剩主屏 Display 0，系统返回 `enabled=2,type=0`、`mDesktopDisplayId=-1`、`mDualModeEnabled=false`、`mIsExternalDisplayConnected=false`，因此本轮确为无外接显示、无既有活动 DeX 会话的冷启动测试。

| 检查 | 普通可信虚拟屏 | 三星 Desktop 标记虚拟屏 |
| --- | --- | --- |
| 创建结果 | Display 49，flags=3081 | Display 2，flags=19465 |
| DeX 进入广播 | 返回 0 | 返回 0 |
| 广播后模式 | enabled=2,type=0 | enabled=2,type=0 |
| Binder 进入请求后模式 | enabled=2,type=0 | enabled=2,type=0 |
| 接收 Surface 帧数 | 1 | 0 |
| 原生 DeX 启用 | false | false |

普通虚拟屏的一帧仅证明 Surface 收到缓冲，不能证明桌面已启动或持续渲染。Desktop 标记路径即使返回 Display ID 2，也没有启用原生 DeX，不能以显示 ID 判断成功。由于原生桌面没有启动，本轮不继续把输入投递到回退桌面作为 DeX 验收。

两个探针均输出 `PROBE_COMPLETE`，按 finally 路径释放自己的显示、ImageReader 和捕获线程。探针未修改系统调试开关、网络设置或应用配置，未将未成功的路径集成到生产代码。

随后核对系统状态时无线 ADB 离线；旧地址 `192.168.31.150:34671` 被拒绝。用户提供新地址 `192.168.31.150:42249` 后恢复连接，独立核对确认：只剩 Display 0、`enabled=2,type=0`、`mDesktopDisplayId=-1`、`mDualModeEnabled=false`、`mIsExternalDisplayConnected=false`、`mUserRequest=NO_REQUEST`。临时显示已释放，没有待处理的 DeX 启动请求。系统 uptime 约 199510 秒，未发生整机重启；无线调试端口变化原因未确定。

本轮证据：

- `work/dex-headless-after-unplug.log`
- `work/dex-headless-normal-result.log`
- `work/dex-headless-desktop-result.log`
- `work/dex-headless-final-state.log`

当前可交付的是明确的可行性边界与可重复测试工具；用户要求的「不接 HDMI，在串流第二屏时自动进入原生 DeX」尚未实现。
