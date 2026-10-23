# 开发分支优化逐项审查与采纳记录

审查基线为 `62513886a4f7d5d175ba2719d14438c93b4c61ff`。初始快照覆盖 `auto/**` 分支的 31 个开放 PR、3 个已关闭且被替代的旧 PR、74 个尚未进入基线的非合并提交；合并前发现新增 #39，并完成增量审查。最终范围为截至 #39 的 32 个开放 PR、3 个旧 PR、76 个非合并提交。以提交历史追溯来源，以当前 PR 的净改动判断是否值得合并。

标准是实际收益、已有实现的保持和合并必要性。此次完整合并 8 个经修正的 PR，另部分采纳 #36；其余 23 个开放 PR 已检查，但本轮不合入。3 个旧版本不重复合并。完整采纳者保留原分支提交历史，#36 只采纳必要修复，其原子替换方案仍留在原 PR。

本文作为仓库中的长期审查记录，逐项列出 PR 原提案、作用、采用范围和修改原因；PR 链接用于追溯讨论，合并提交用于追溯最终实现。这里的开放／关闭数量对应审查快照，不表示阅读时的实时 PR 状态。后续作者更新需要重新审查，不能把本次结论直接套用到新提交。#1–#4 已在基线中，不计入本轮 32 个开放 PR。

## 实测收益及边界

在本机 JDK 17.0.19 / Kotlin 1.9.22 和 S20+ `SM-G9860` / Android 13 ART 分别比较基线与候选代码。预热后交替先后顺序，取 7 轮中位数；音频每轮 30,000 次（JVM）／10,000 次（ART），日志每轮 5,000 次／3,000 次。音频使用 3,840-float 容量、960-float 的立体声写入和读取，输入与输出语义一致。日志保留 500 条，包括时间格式化、历史快照和 StateFlow 发布，排除 Android logcat IPC。

| 局部操作 | JVM 基线 → 候选 | S20+ ART 基线 → 候选 |
| --- | --- | --- |
| 音频 960-float 写入＋读取 | 2.735 → 0.459 微秒 | 7.460 → 3.613 微秒 |
| 音频每次分配 | 3,856 → 3,856 字节 | 约 3.9 KB → 约 3.9 KB |
| 日志每条耗时 | 1.664 → 1.044 微秒 | 34.259 → 30.368 微秒 |
| 日志每条分配 | 8,367 → 4,327 字节 | 10,628 → 6,565 字节 |

ART 音频局部耗时降低约 52%，日志分配降低约 38%。音频并未减少读取结果数组的分配；日志仍需要复制历史快照，因此整体 log 操作仍有 O(n) 工作量。这里没有证明串流 FPS 提高或端到端延迟下降，也不把桌面 JVM 的约 6 倍局部加速当作手机上的加速比例。ART 通过独立 `app_process` 微基准执行相同函数，不能替代发布 APK 在完整音视频负载下的性能测试。

原始结果和生成的基准工具保留在本地忽略目录 `work/review-optimizations-20261023/`，不包含配对资料。真机临时基准文件运行后删除。

## 采纳后补充的修正

- 日志 deque 的缓冲区修改和 StateFlow 发布使用同一把锁，防止旧快照在 clear 或新记录之后重新发布。
- PIN 成功、格式错误及引擎异常三条日志路径均避免输出输入值；异常只记录类型，避免异常消息再次回显 PIN。输入校验、提交与请求清除规则保持原有语义。
- 配对文件只采用 HTTP 请求之间的读改写串行化和有效 JSON 错误响应。请求体读取移到状态锁外，慢客户端不能阻塞客户端列表查询。
- 未采用 PR #36 的 `AtomicFile` 替换。原生引擎同样写入此文件，Java 局部锁不是跨进程事务；本轮保留既有文件契约，不宣称解决进程崩溃或跨进程全部写入一致性。Windows Robolectric 的 rename 行为也不作为 Android 实机文件系统故障的证据。
- Robolectric 仅作为测试依赖，用无生产启动流程的 Application。移除未预置编解码器、可能零断言通过的示例，改为日志并发和真实 API 路由回归。JDK 17 配置按官方要求添加模块开放；仅对测试用 Bouncy Castle 多版本 JAR 跳过无必要的 Jetifier 转换，避免 Java 25 多版本字节码导致测试构建失败。
- `SourceCadenceTracker` 的候选帧率表只在约一秒的采样窗口边界使用，原 PR 的“每帧分配”表述已改正。

## 已合并的 8 个 PR：原提案、作用与最终采用部分

下面将包含多种改动的 PR 拆开列出。“合并”表示保留其提交来源并在审查后修正，不表示原提案的每一行或每项收益表述都原样保留。

| PR／部分 | 原 PR 提交的内容 | 作用与审查结果 | 最终采用部分 |
| --- | --- | --- | --- |
| [#5](https://github.com/nanaoniiiii/sunshine/pull/5)：PCM 缓冲 | `PcmAudioBuffer` 的逐样本取模循环改为每次读或写遇环绕时最多两段 `System.arraycopy`。 | 减少每个样本的循环和取模；ART 局部写入＋读取耗时降低约 52%，读取结果数组的分配量不变。 | 采用批量复制，追加立体声环绕、溢出和参考队列等价测试，保持已有缓冲语义。 |
| #5：帧率候选表 | `SourceCadenceTracker` 将候选帧率数组移为可复用的静态数组。 | 避免采样窗口结束时重复建立数组；该路径约每秒运行一次，不是每帧分配。 | 采用数组复用，修正原提案的收益说明。 |
| #5：诊断 JSON | `SunshineEngineClient` 查询视频会话时复用私有 `Gson` 实例。 | 减少重复创建解析器，查询内容与数据结构不变。 | 采用实例复用；未单独证明完整诊断请求或串流性能提升。 |
| [#6](https://github.com/nanaoniiiii/sunshine/pull/6)：死代码 | 删除 `SunshineService.getWifiIpAddress`、`getWifiIpv6Address` 两个无调用私有方法。 | 减少过时地址探测实现，降低维护和误用成本。 | 采用删除；实际使用的 `refreshNetworkAddresses` 保留。不声称提升 FPS 或缩小 R8 后 APK。 |
| [#7](https://github.com/nanaoniiiii/sunshine/pull/7)：未使用依赖 | 移除 `lifecycle-service:2.7.0`、`ui-tooling-preview`、`navigation-compose:2.7.7`。 | 源代码没有使用这三项直接依赖，清理构建配置。 | 采用移除；保留实际使用的 Lifecycle、Compose 和图标库。 |
| #7：HTTP 客户端 | `okhttp`、`logging-interceptor` 从 `4.12.0` 升到 `4.12.1`。 | 核对发布源码后确认增加失信证书链阻断，属于有明确内容的补丁升级。 | 采用这两项升级；Kotlin／Compose 工具链和现有服务器证书不变。 |
| [#10](https://github.com/nanaoniiiii/sunshine/pull/10)：日志缓冲 | `CopyOnWriteArrayList` 改为加锁的 `ArrayDeque`。 | 减少追加／淘汰时的整数组复制；ART 日志局部耗时降低约 11%、分配降低约 38%。原提案仍有锁外发布旧快照的窗口。 | 采用 deque，并将缓冲修改、清空和 StateFlow 发布放入同一把锁，补充并发回归。历史快照复制仍为 O(n)。 |
| [#11](https://github.com/nanaoniiiii/sunshine/pull/11)：PIN 配对 | 提取 `PinPairingController`，集中配对请求状态、校验、提交及清除。 | 单一职责状态更易验证；审查同时确认日志可能回显 PIN。 | 采用控制器，保留原校验／提交语义；成功、格式错误、引擎异常路径全部脱敏。异常仅记类型，避免消息回显 PIN，补充回归测试。 |
| [#17](https://github.com/nanaoniiiii/sunshine/pull/17)：测试基建 | 增加 `Robolectric:4.17`、Android 资源加载和 JDK 17 模块开放。 | 支持在 JVM 中验证 Android 框架交互，便于测试日志、API、锁屏状态等路径。 | 采用测试基建，使用不运行生产启动流程的 Application；补充 Maven 测试依赖仓库和测试用 Bouncy Castle Jetifier 排除项。Robolectric 不进入生产运行依赖。 |
| #17：示例测试 | 增加 `EncoderCapabilitiesTest`，枚举编码器并检查缓存身份。 | 未预置编码器时枚举断言可能一次也不执行；缓存身份检查不能证明实际编码能力。 | 移除这组示例，改为日志并发及实际 API 路由回归，不把框架模拟当作真机编解码器验收。 |
| [#22](https://github.com/nanaoniiiii/sunshine/pull/22)：滚轮事件 | 删除 `InputBroker.scroll` 中一个构造后从未注入、直接回收的 `MotionEvent`。 | 去掉无效的事件构建／回收；真正带滚轮轴数据的事件仍注入。对象池可能复用，不能断言每次必然减少一次堆分配。 | 采用删除；接收端鼠标在 DeX 设置中的实际滚动已验证。 |
| [#39](https://github.com/nanaoniiiii/sunshine/pull/39)：设置死代码 | 删除无生产调用的 `SettingsDraft.reconcile` 及其三项测试。 | 生产路径已使用 `SettingsAutosave.rebase`，删除重复且失效的维护对象。 | 采用删除，保留实际自动保存逻辑及八项相关测试。不宣称运行时加速；方法删除和测试删除分布在该 PR 的两项非合并提交中。 |

### 合并来源与审查修正提交

| PR／阶段 | 主线提交 | 内容 |
| --- | --- | --- |
| #5 | [`6ca7341`](https://github.com/nanaoniiiii/sunshine/commit/6ca7341) | 保留音频、帧率表和 Gson 优化的原提交来源。 |
| #6 | [`42df9d4`](https://github.com/nanaoniiiii/sunshine/commit/42df9d4) | 删除无调用的 IP 检测方法。 |
| #7 | [`58195e0`](https://github.com/nanaoniiiii/sunshine/commit/58195e0) | 依赖清理与 OkHttp 补丁升级。 |
| #10 | [`52152d0`](https://github.com/nanaoniiiii/sunshine/commit/52152d0) | 日志缓冲替换。 |
| #11 | [`6a70594`](https://github.com/nanaoniiiii/sunshine/commit/6a70594) | PIN 状态控制器。 |
| #17 | [`9aa51b2`](https://github.com/nanaoniiiii/sunshine/commit/9aa51b2) | Android 框架测试基建。 |
| #22 | [`7d0bfff`](https://github.com/nanaoniiiii/sunshine/commit/7d0bfff) | 无效滚轮事件清理。 |
| #39 | [`807032b`](https://github.com/nanaoniiiii/sunshine/commit/807032b) | 无调用设置方法和测试清理。 |
| 审查修正及部分 #36 | [`fbc6b14`](https://github.com/nanaoniiiii/sunshine/commit/fbc6b14396f6be06cda6cc12a2cb19f86e0cad5e) | 日志发布顺序、PIN 异常脱敏、有效回归、测试构建兼容，以及配对 API 的必要修复。 |

## 部分采纳的 PR #36

[#36](https://github.com/nanaoniiiii/sunshine/pull/36) 的审查头为 `6b6d70a3cb4be93821f6128f213bb3b60f9a66d2`。只在 `fbc6b14` 中采纳下面的必要部分，没有合并整个 PR 分支。

| 原 PR 提交的部分 | 作用与问题 | 最终采用部分 |
| --- | --- | --- |
| 为配对状态读改写增加 `stateLock` | 同一 HTTP 服务内，并发重命名、取消单个配对和取消全部配对可能覆盖彼此更新；但原方案在锁内读取请求体，慢客户端会阻塞其他查询。 | 采用该服务内的状态读改写串行化及读取快照；请求体读取／解析移到锁外，缩小临界区。实机不完整请求体保持未完成时，客户端列表仍约 0.015 秒返回。 |
| 通过 Gson 生成错误 JSON | 异常文本直接拼接可能产生无效 JSON。 | 采用 JSON 转义；故意无效请求返回的 HTTP 500 响应可正常解析。不将它描述为所有错误状态码都已重新设计。 |
| 用 `AtomicFile` 原子替换状态文件 | 试图改善单次文件写入中断的恢复；原生引擎也写同一文件，Java 局部锁无法协调跨进程事务。 | 不采用，保留既有 `readText`／`writeText` 文件契约。跨进程协调与崩溃一致性需单独设计；Windows Robolectric 的 rename 差异不是 Android 文件系统故障证明。 |

## 本轮未合入的 23 个 PR

“暂缓”表示本次没有足够收益或必要性来承担相应改动和回归范围，不表示重构永远没有维护价值。明确失败的编译结论仅对应表中的快照；作者修正后的提交需另行验证。

| PR | 原 PR 提交的部分 | 作用／风险与未采纳原因 | 本轮决定 |
| --- | --- | --- | --- |
| [#8](https://github.com/nanaoniiiii/sunshine/pull/8) | 提取 `SunshineConfParser` 并增加解析测试。 | 可以隔离配置解析；但未统一其他解析入口，也未减少工作量，主要是代码搬移。 | 暂缓，未采用。 |
| [#9](https://github.com/nanaoniiiii/sunshine/pull/9) | 将输入回调的 `@Synchronized` 改为共享会话锁，并提取任务 `displayId` 反射辅助函数。 | `host` 已是 volatile，节流字段已有独立同步；共享会话锁增加与启停路径竞争。反射提取仅去重。 | 两部分均未采用。 |
| [#12](https://github.com/nanaoniiiii/sunshine/pull/12) | 提取 `CaptureCanvasSync` 管理尺寸和画布同步。 | 算法不变，新增多项状态 getter 和 Host 耦合，没有证明运行收益。 | 暂缓，未采用。 |
| [#13](https://github.com/nanaoniiiii/sunshine/pull/13) | 提取 `NetworkPeerProbe`，集中对端 IP、`/proc` 解码和 ping，并增加解析测试。 | 有维护价值；未减少实际网络探测或计算量。 | 暂缓，可在独立维护性重构中再评估。 |
| [#14](https://github.com/nanaoniiiii/sunshine/pull/14) | 将遥测线程迁到 `StreamTelemetry`，通过 Host 读写运行状态。 | 算法不变，状态仍在原管理器，增加大量 getter／setter。 | 暂缓，未采用。 |
| [#15](https://github.com/nanaoniiiii/sunshine/pull/15) | 提取 `FullscreenDisplayController` 和显示后端工厂。 | 增加 Host 转接，shell 调用和等待时间不变。 | 暂缓，保留已验证的显示恢复实现。 |
| [#16](https://github.com/nanaoniiiii/sunshine/pull/16) | 按文件差异更新内置证书与私钥资源。 | 会改变既有服务器信任状态；证书与密钥分两次截断写入，不是成对原子迁移。 | 不采用；证书轮换需独立迁移及失败恢复设计。 |
| [#18](https://github.com/nanaoniiiii/sunshine/pull/18) | 批量升级 AndroidX、Compose、协程、Retrofit、Gson、JmDNS，版本见下表。 | 没有逐项证明对当前问题的收益，扩大 UI、网络和设备发现的兼容性验证范围。 | 本轮全部暂缓，不与局部优化混合。 |
| [#19](https://github.com/nanaoniiiii/sunshine/pull/19) | AGP `8.2.2 → 8.7.3`，Gradle `8.5 → 8.10.2`。 | 属于工具链迁移，非本次局部优化必需，未证明产品运行收益。 | 暂缓，保留原工具链。 |
| [#20](https://github.com/nanaoniiiii/sunshine/pull/20) | Kotlin `1.9.22 → 2.0.21`，接入新的 Compose 编译器 Gradle 插件。 | 扩大构建与 UI 回归范围。 | 暂缓，保留 Kotlin 1.9.22／Compose 编译器 1.5.8。 |
| [#24](https://github.com/nanaoniiiii/sunshine/pull/24) | 将退出和面板控制提取为 `VirtualScreenLifecycle`。 | 增加 Host 包装，未减少系统操作或运行工作量。 | 暂缓，未采用。 |
| [#25](https://github.com/nanaoniiiii/sunshine/pull/25) | 提取 `VirtualScreenPreflight`、前置条件数据和测试。 | 条件检查更易隔离，但引入多层数据／Host；这些测试不证明新设备授权及系统录屏可用。 | 暂缓，未采用。 |
| [#26](https://github.com/nanaoniiiii/sunshine/pull/26) | 提取 `LocalDexWaiter`，集中 DeX 等待、提示和会话存活判断。 | 等待循环及 180 秒确认时限未变。 | 暂缓，保留已验证的确认编排。 |
| [#27](https://github.com/nanaoniiiii/sunshine/pull/27) | 提取 `VirtualScreenProvisioner`，搬移桌面创建和采集绑定。 | 审查头 `009850ac6021d0d780cec59e32a2981634b63b00` 的 block-body 函数缺少 return，实际编译失败。 | 不采用该快照。 |
| [#29](https://github.com/nanaoniiiii/sunshine/pull/29) | 提取 `NetworkAddressResolver`，引入 Host／Accumulator／Snapshot，并删除旧地址方法。 | 地址分类规则不变，增加多层转接；死代码删除已由 #6 覆盖。 | 未采用；不把 #6 的重复清理计为部分采纳 #29。 |
| [#30](https://github.com/nanaoniiiii/sunshine/pull/30) | 提取 `FullscreenIdleRestorer`。 | 闲置恢复算法不变，新增 Host 和日志转接。 | 暂缓，未采用。 |
| [#31](https://github.com/nanaoniiiii/sunshine/pull/31) | 提取 `VirtualScreenClientHolder`，替代旧 #21。 | 普通退出也使用 `closeQuietly`，可能吞掉关闭失败并错误报告成功。 | 不采用这一行为回退。 |
| [#32](https://github.com/nanaoniiiii/sunshine/pull/32) | 提取 `VirtualCaptureRestorer`，替代旧 #23。 | 搬移物理采集恢复，增加多项可变状态委托，未证明运行收益。 | 暂缓，未采用。 |
| [#33](https://github.com/nanaoniiiii/sunshine/pull/33) | 提取 `VirtualScreenFailureRecovery`，替代旧 #28。 | 相比旧版保留了 `DisplayFailureMessage`，但主要仍为错误恢复代码搬移。 | 暂缓，保留现有错误语义。 |
| [#34](https://github.com/nanaoniiiii/sunshine/pull/34) | 提取 `FullscreenSessionRunner` 和编排测试。 | 增加 Host 转接，系统交互和等待没有减少。 | 暂缓，未采用。 |
| [#35](https://github.com/nanaoniiiii/sunshine/pull/35) | PCM 环形缓冲改用批量复制。 | 与 #5 重复，实现收益已覆盖。 | 不重复合并，采用部分归于 #5。 |
| [#37](https://github.com/nanaoniiiii/sunshine/pull/37) | 更新页和 Dashboard 的数字格式固定使用 `Locale.ROOT`。 | 影响部分语言的本地数字显示，不是性能优化，也无当前产品需求。 | 暂缓，保留系统地区格式。 |
| [#38](https://github.com/nanaoniiiii/sunshine/pull/38) | 将 `material-icons-extended` 换为 `material-icons-core`。 | 审查头 `057ce288a8a0ba550407aa9bb135cd065b33309f` 实际出现 Dashboard、Gamepad、ScreenShare 等 unresolved reference；相同 import 包名不证明图标属于 core。 | 不采用该快照，保留 extended。 |

### PR #18 提议的依赖版本

以下升级本轮均未采用；#7 的 OkHttp 补丁升级单独审查并已采用。

| 依赖 | 基线／最终保留版本 | #18 提议版本 |
| --- | --- | --- |
| AndroidX Core KTX | 1.12.0 | 1.13.1 |
| Lifecycle runtime-ktx／viewmodel-compose／runtime-compose | 2.7.0 | 2.8.7 |
| kotlinx-coroutines-android | 1.7.3 | 1.8.1 |
| Compose BOM | 2024.02.00 | 2024.06.00 |
| activity-compose | 1.8.2 | 1.9.3 |
| Retrofit／converter-gson | 2.9.0 | 2.11.0 |
| Gson | 2.10.1 | 2.13.2 |
| JmDNS | 3.5.8 | 3.6.4 |

## 已关闭、被替代的 3 个旧 PR

| 旧 PR | 提交的部分 | 替代版本与采用情况 |
| --- | --- | --- |
| [#21](https://github.com/nanaoniiiii/sunshine/pull/21) | 旧版 `VirtualScreenClientHolder`。 | 被 #31 替代；新旧两个版本本轮均未采用。 |
| [#23](https://github.com/nanaoniiiii/sunshine/pull/23) | 旧版 `VirtualCaptureRestorer`。 | 被 #32 替代；新旧两个版本本轮均未采用。 |
| [#28](https://github.com/nanaoniiiii/sunshine/pull/28) | 旧版 `VirtualScreenFailureRecovery`。 | 被 #33 替代；新旧两个版本本轮均未采用，不重复引入过期版本。 |

## 验收期间追加的两项修复

下面两项来自本轮实机发现，不计为另一位开发者 PR 的原提案；均已进入最终正式版 `v2026.10.24.3`。

| 修复与提交 | 原问题 | 采用的实现与作用 | 验证 |
| --- | --- | --- | --- |
| [受信任设备状态检查 `d7ee7af`](https://github.com/nanaoniiiii/sunshine/commit/d7ee7af) | 手机已受信任、Sunshine 可操作，但 Keyguard 仍 showing；旧逻辑使用 `isKeyguardLocked`，误阻止虚拟屏／DeX。 | 增加 `DisplayUnlockGuard`，相关显示操作每次检查 `isDeviceLocked`，正确区分认证锁定与仍显示的受信任锁屏 UI；保留已有原生 DeX 复用规则。 | 3 项回归覆盖受信任状态、真正需要认证及后续重新锁定。 |
| [收起受信任锁屏层 `beeacf6`](https://github.com/nanaoniiiii/sunshine/commit/beeacf669fcf7fd344ae20d65467c0bbe9f83feb) | 退出 Sunshine 桌面虽保留虚拟屏和息屏，但可能露出 Smart Lock 锁屏 UI，未真正回到手机主页。 | 增加 `TrustedKeyguardDismissal`，主机及桌面 Activity 恢复／重新获得焦点时，仅在无需身份验证且 Keyguard 仍 showing 时调用系统 `requestDismissKeyguard`；抑制重复请求，不修改锁屏设置。 | 4 项框架回归；最终真机退出后为手机“主屏幕”、Keyguard showing=false，虚拟屏和息屏串流保留。 |

两项修复新增 7 项测试，使最终套件由优化候选的 218 项增至 225 项。三种显示模式、实际编码尺寸、退出目标、面板电源及断流恢复的逐项结果见[最终实机记录](../validation/reviewed-optimizations-2026-10-24.md)。本轮没有连接实体手柄，不把系统方向键注入计为实体手柄或 Moonlight 手柄的新一轮验收。

## 验证与实现保持

增量 #39 合入前为 221 项测试通过，0 失败、0 错误；#39 删除三个仅验证死代码的用例后，最终套件为 218 项。新增覆盖混合音频环绕／溢出／整包读取与队列参考模型的等价性，日志并发追加／清空与发布一致性，PIN 各条错误路径的脱敏，配对 API 并发更新不丢失、慢请求体不阻塞读取及有效 JSON 错误。

最终 `testReleaseUnitTest lintRelease assembleRelease` 全部通过：218 项测试、0 失败、0 错误；Lint 0 错误、48 条警告（含现有 Compose 自定义检查版本不匹配的边界）。三个候选 APK 均验证签名与上一正式版一致、包名／版本／ABI 正确，包内原生模块与构建产物一致；Robolectric 不在生产运行依赖中。

以上计数和下表对应初次 `v2026.10.24.1` 优化候选。后续实机发现受信任锁屏状态误判及桌面退出目标问题，修正后的最终 `v2026.10.24.3` 共 225 项测试通过，三种显示模式均已实测；新增七项回归只覆盖这些认证状态边界，详见[最终实机记录](../validation/reviewed-optimizations-2026-10-24.md)。

| 候选 APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 7320946 | `ee8a2c9ce15762e026e9675a68dab69c5e2fa2709015dee1b8a261013b116c67` |
| armeabi-v7a | 6355494 | `62d67e8b988976d8983a264c4511440b79c7596b889c0a3bfd05cc8c01267e60` |
| universal | 10738890 | `cd48f6a3bc9e772c955dfd04ce9087ede092faaaa534eb7e7538920f32661070` |

覆盖安装命令起初被执行工具拒绝，仅返回 `blocked by policy`，具体策略来源未知。用户明确要求“开始验证”后正常重试安装成功，已核对设备实际 APK 的散列与候选一致。配置除版本号外保持一致，配对记录和授权保留，原有 Moonlight 配对可直接收到画面，诊断解析与 HTTP 状态接口检查通过。继续检查更正了此前将 `Keyguard showing=true` 等同于真实锁屏的结论，并发现既有锁屏检查误拦受信任设备；`v2026.10.24.2` 改用实际认证状态后，再补充正常收起受信任锁屏层的处理。最终 `v2026.10.24.3` 已通过三种显示模式、桌面退出、面板电源和断流恢复验证。工具拒绝、各阶段样本及尚未复测的输入边界分别记录在[实机验证记录](../validation/reviewed-optimizations-2026-10-24.md)。

保留虚拟屏、DeX、Shizuku 自动准备、手柄映射、编码器／队列／网络协议的现有实现；原生库和 UI 图标方案不变。这里的“保持”表示未改这些路径且通过对应可执行检查，不等于所有设备、游戏、异常断电场景均已实测。没有承诺绝对无回归。

依赖检查参考：[OkHttp 4.12.1 发布源码](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.1/okhttp-4.12.1-sources.jar)、[Robolectric 官方 JDK 17 配置](https://robolectric.org/getting-started/#running-with-java-17-and-higher)。
