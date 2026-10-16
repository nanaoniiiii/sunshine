# SM-T970 无线 ADB 端口与稳定性排查

2026-10-16，通过平板 USB ADB 读取状态；网络测试明确选择 `IP:端口` transport，没有用 USB 执行成功的命令冒充无线测试。原始日志与设备标识保留在忽略目录 `work/tablet-wireless-adb/`。

## 可复核发现

- 型号 SM-T970，Android 13。检查时连接 5GHz / 5200MHz，RSSI 约 −31～−32dBm，USB 与无线连接均正常。
- `adb_wifi_enabled=0`，系统原生 TLS 无线调试已关闭；旧端口 33327 没有监听，TCP 连接测试超时。
- `service.adb.tcp.port=7120`，7120 正在监听，实际连接成功。通过该网络连接读出的设备序列号与 USB 平板一致，密钥认证通过，`ro.adb.secure=1`。
- 电脑 `adb mdns services` 仍列出旧的 `_adb-tls-connect._tcp` / 33327，也列出当前 `_adb._tcp` / 7120。发现记录不等于端口仍在监听，不能只复用旧 TLS 发现记录。
- 平板安装并运行 Stellar 1.0.16（100160）。该版本代码支持按配置切换固定 TCP 端口，并在启动成功后关闭原生无线调试，与本次设备状态吻合。这是对当前模式的解释，历史每次断线没有完整日志，不能全部归于同一原因。

版本对应源码：[切换 TCP 端口](https://github.com/roro2239/Stellar/blob/v1.0.16%28100160%29/manager/src/main/kotlin/roro/stellar/manager/startup/worker/AdbStarter.kt)，[启动后关闭原生无线调试](https://github.com/roro2239/Stellar/blob/v1.0.16%28100160%29/manager/src/main/kotlin/roro/stellar/manager/startup/worker/AdbStartWorker.kt#L110)。保留 Stellar 现有配置，不与其端口管理反复切换。

## 处理

本次连接改用当前监听的固定 TCP 端口 7120，复用电脑已经获准的 ADB 密钥。没有重新配对、删除密钥、重启 ADB server 或改动 Wi-Fi、VPN、Stellar 的设置。

新增 `tools/connect_android_adb.py`：从明确指定的 USB 设备读取 Wi-Fi 地址、实际 TLS/TCP 端口与原生无线调试开关；连接后核对设备身份，避免连接到另一台设备。原生 TLS 已关闭时不会采用残留的 TLS 发现端口。脚本只读取状态和建立连接，不打开调试服务。

```powershell
python tools/connect_android_adb.py --adb <adb.exe> --usb-serial <USB_SERIAL>
```

可选 `--check-seconds 300 --output work/adb-check.json` 执行有限时间验证。报告包含设备标识，应留在忽略目录中。

## 实测结果

| 项目 | 结果 |
| --- | --- |
| 连续检查约五分钟 | 300/300 次无线往返成功 |
| 主屏息屏 180 秒 | 13 次逐 15 秒采样均为 OFF；无线往返全部成功 |
| 息屏期间 ADB 服务 | PID 与端口不变，未发生服务重启 |
| 文件传输 | 上传 16MiB、下载 16MiB，双向 SHA256 一致 |
| 无心跳空闲 120 秒 | 息屏期间无线 shell 静默保持 120.09 秒，结束后身份核对成功，屏幕仍为 OFF |
| 主动断开后重连 | 10/10 次成功并核对身份，无需重新配对 |

息屏测试在 finally 中唤醒平板；临时传输文件已删除。以上保留 USB 用于旁路观测，尚未覆盖拔掉 USB 或重启系统后的表现。旧 TLS 的历史证书告警与本次固定 TCP 通道成功分开记录，不宣称修复了所有历史 TLS 配对问题。

连续检查的命令往返中位数 125ms、P95 172ms，最大 594ms；该数值包含本机启动 ADB 客户端、协议与远端 shell 的开销，不是网络 ping 时延。双向文件传输分别约 0.67s / 0.53s。测试结束平板主屏已恢复 ON，USB 与固定 TCP 两条连接均在线。未提交、推送或发布代码。
