# v2026.10.17.4 优化与验证

版本代码 20261033，正式发布。此次审查覆盖 GL 帧分发、帧额度、输入几何、多端 UDP 批次发送和应用内部管理接口。没有另建交接文档。

## 具体改动

1. **控制接口与外网分离**：Sunshine 自身 127.0.0.1 管理接口显式使用 `Proxy.NO_PROXY`。更新下载仍使用正常系统网络设置。修复系统 HTTP 代理不可达时设置、状态和应用列表读取失败；新增实际 loopback HTTP 回归测试，在不可用的 HTTP ProxySelector 下仍能读取响应。
2. **GL 线程通知合批**：Binder 完成通知进入 FIFO mailbox，共享一次待执行 GL 任务；逐条保留原始通知顺序。不能按 PTS 合并并提前执行后到的编码通知，否则“发送完成先到、较早帧编码后到”的竞态会错误释放尚在发送的帧额度。增加该顺序回归及多生产线程测试。此次没有修改跨进程回调协议，不宣称减少 Binder 事务数。
3. **帧唤醒与额度算法**：已有更早帧唤醒时直接复用；只有需要提前唤醒或取消时才移除 Handler 回调。扫描最早客户端截止时间时避免临时列表；额度仅清理有序前缀中的过期项，编码跳帧清理用迭代器原位删除，保留已编码待发送帧的额度。
4. **多端发送线程互斥**：FIFO 发送门只保护一次非阻塞 sendmmsg，单次最多 16 个数据报。遇到 EAGAIN／ENOBUFS 后的 100 微秒重试等待在发送门外执行，让其他客户端能进入；保留 16ms 重试期限和部分成功后的前缀续发规则。新增线程测试让一端等待重试，另一端须在其等待结束前成功发送，并校验续发偏移。
5. **输入参数缓存**：在显示添加、改变、移除和源显示切换时使缓存失效；连续移动事件复用尺寸与旋转，复用 DisplayMetrics。切换到同一个 Display 0 的不同模式也强制刷新。该缓存仍由 InputBroker 原有同步监视器保护。
6. **录屏失效处理**：没有有效 MediaProjection 时拒绝启动新应用串流，记录需要重新允许录屏；避免在无采集源状态下完成连接后等待视频超时。

相关接口说明：[Android Handler](https://developer.android.com/reference/android/os/Handler)、[DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[sendmmsg](https://man7.org/linux/man-pages/man2/sendmmsg.2.html)。优化结论以本项目代码和以下验证为依据。

## 构建与测试

- 133 项 Release JVM 单元测试通过，0 failures / 0 errors。含并发通知、保序、唤醒提前与复用、输入缓存失效、实际 HTTP 直连，以及现有编码／输入／桌面／更新回归。
- 两种 ABI 原生库重新编译；arm64-v8a、armeabi-v7a 各 11 个测试可执行文件均在 SM-G986U1 上实际运行通过，共 22 项。覆盖真实 IPv4／IPv6 UDP loopback、重试释放发送门、FEC、音频、队列、画布与编码策略。
- Release lint 0 errors，仍有 37 条工具与既有代码警告。三种 APK 构建并通过签名校验；包内所有 native 库哈希与构建输入一致。

## 实机结果与边界

最终 arm64 APK 已安装 SM-G986U1，系统版本 v2026.10.17.4 / 20261033。安装后的 base.apk SHA-256 与交付文件一致：`88f742c7708fe2b7f99eb5d7cd5711875b507f594868f90e02aa740c5e5bed89`。

SM-G986U1 Android 13 → Windows Moonlight，2560×1440／60fps、H.264、主屏静态界面，连续 30 秒有效采样：

| 项目 | 结果 |
| --- | --- |
| 编码 FPS 窗口中位数 | 59.9511 |
| 新增编码帧 | 1800 |
| 新增队列丢帧／发送失败／帧额度超时 | 0 / 0 / 0 |
| 帧额度峰值 | 2 |
| 进度通知／GL 处理批次 | 3600 / 3009 |
| 复用已有帧唤醒 | 1815 次 |
| 温控状态／引擎 cpuset | 1 / top-app |

3600 条通知合批为 3009 次 GL 处理，约减少 16.4% 的任务投递。它衡量通知处理任务数量，不是 CPU 占用率或端到端延迟改善百分比。Windows 实际接收画面可见；候选中通过 Moonlight 点击设置并在源端和接收端核对页面更新，源端设置读取在代理修复后正常。最终输入复核时检测到用户同时操作，停止继续注入点击，保留串流窗口。

旧版基线启动时 MediaProjection 已失效，连接随后视频超时，未形成有效对照，因此不发布前后 FPS、CPU 或延迟提升百分比。最初多端／第二屏／测试画面配置的组合命令被自动审批拒绝，仅返回 `blocked by policy`，命令未执行；该组合场景未计为本轮实测通过。

本轮未完整复测多端高动态、平板原生 DeX 和长时息屏。原有固件刷新率、温控和网络带宽边界仍适用；不能由单端静态 60fps 推定这些场景稳定 120fps。

复核到上轮临时测试代理遗留 `global_http_proxy_host=127.0.0.1`、`global_http_proxy_port=17890`。核对仅为本次测试端口后清除有效代理，现 host 为空、port 为 0；本机设置读取恢复。没有在产品中添加测试代理或凭据。

原始记录保留在本地 `work/samsung-desktop/architecture-*` 和 `work/virtual-stream-network/architecture-final-primary/`，不公开设备抓取、截图或配对信息。
