# 开发分支优化审查与筛选

审查基线为 `62513886a4f7d5d175ba2719d14438c93b4c61ff`。初始快照覆盖 `auto/**` 分支的 31 个开放 PR、3 个已关闭且被替代的旧 PR、74 个尚未进入基线的非合并提交；合并前发现新增 #39，并完成增量审查。最终范围为截至 #39 的 32 个开放 PR、3 个旧 PR、76 个非合并提交。以提交历史追溯来源，以当前 PR 的净改动判断是否值得合并。

标准是实际收益、已有实现的保持和合并必要性。此次完整合并 8 个经修正的 PR，另部分采纳 #36；其余 23 个开放 PR 已检查，但本轮不合入。3 个旧版本不重复合并。完整采纳者保留原分支提交历史，#36 只采纳必要修复，其原子替换方案仍留在原 PR。

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

## 逐项决定

下表链接对应审查时的 PR，判定针对本轮快照；后续作者更新需重新审查。

| PR | 判断 | 决定 |
| --- | --- | --- |
| [#5](https://github.com/nanaoniiiii/sunshine/pull/5) | PCM 两段批量复制有可重复局部收益；Gson 可复用。帧率表仅每秒少量分配。 | 合入，修正收益描述并补充立体声队列等价测试。 |
| [#6](https://github.com/nanaoniiiii/sunshine/pull/6) | 两个私有 IP 检测方法已无调用，保留它们容易误用过期逻辑。 | 合入清理，不声称提高运行速度或缩小 R8 后 APK。 |
| [#7](https://github.com/nanaoniiiii/sunshine/pull/7) | 三项依赖无源代码使用；OkHttp 4.12.1 实际源码增加失信证书链阻断。 | 合入，保留 Kotlin／Compose 工具链及服务器证书。 |
| [#8](https://github.com/nanaoniiiii/sunshine/pull/8) | 配置解析主要是搬移；未统一其他配置解析入口，没有运行收益。 | 暂缓，本轮不为减少单文件行数引入新类。 |
| [#9](https://github.com/nanaoniiiii/sunshine/pull/9) | host 已是 volatile，节流字段已有独立同步；改为会话锁会增加与启停路径的竞争。反射小函数只去重。 | 不合入这一锁范围变更。 |
| [#10](https://github.com/nanaoniiiii/sunshine/pull/10) | 去除 COW 追加／淘汰的整数组复制，分配实测下降。原提案仍有锁外发布乱序窗口。 | 修正发布顺序后合入。 |
| [#11](https://github.com/nanaoniiiii/sunshine/pull/11) | 小型、单一职责 PIN 状态封装可测试；日志确实回显 PIN。 | 合入，追加异常消息脱敏和回归测试。 |
| [#12](https://github.com/nanaoniiiii/sunshine/pull/12) | 几何同步搬到持有多项状态 getter 的类，算法和工作量不变。 | 暂缓。 |
| [#13](https://github.com/nanaoniiiii/sunshine/pull/13) | 对端探测搬移与解析测试有维护价值，没有减少网络探测或计算量。 | 暂缓，另行维护性重构时评估。 |
| [#14](https://github.com/nanaoniiiii/sunshine/pull/14) | 遥测线程算法不变，新增大量 Host getter／setter，状态仍在原管理器。 | 暂缓，不作为性能优化合入。 |
| [#15](https://github.com/nanaoniiiii/sunshine/pull/15) | 显示后端增加 Host 转接，shell 调用与等待时间均不变。 | 暂缓，保留刚验证的显示恢复路径。 |
| [#16](https://github.com/nanaoniiiii/sunshine/pull/16) | 按差异覆盖证书／私钥会改变既有信任状态，分两次截断写入也不是一项事务。 | 不合入，证书轮换应独立设计迁移和失败恢复。 |
| [#17](https://github.com/nanaoniiiii/sunshine/pull/17) | 测试框架有助于验证 Android API；原编解码器示例可能因空列表而不做断言。 | 仅采用修正后的测试基建与有效回归，不进入生产依赖。 |
| [#18](https://github.com/nanaoniiiii/sunshine/pull/18) | 批量版本升级没有逐项证明当前问题的收益，扩大兼容性验证面。 | 暂缓，不与小范围优化混合。 |
| [#19](https://github.com/nanaoniiiii/sunshine/pull/19) | AGP／Gradle 升级不是当前优化必需项，未证明产品运行收益。 | 暂缓。 |
| [#20](https://github.com/nanaoniiiii/sunshine/pull/20) | Kotlin 2 与 Compose 编译器迁移扩大构建及 UI 回归面。 | 暂缓，继续使用已验证的 Kotlin 1.9.22。 |
| [#22](https://github.com/nanaoniiiii/sunshine/pull/22) | 删除一个从未注入的滚轮 MotionEvent 构建／回收过程，真正的带轴事件保留。 | 合入；不把对象池复用夸成每次必然少一次堆分配。 |
| [#24](https://github.com/nanaoniiiii/sunshine/pull/24) | 退出与面板控制搬到 Host 包装，未提供运行收益。 | 暂缓。 |
| [#25](https://github.com/nanaoniiiii/sunshine/pull/25) | 前置条件拆成多层数据／Host；测试缝并不能替代首次授权与系统录屏实测。 | 暂缓。 |
| [#26](https://github.com/nanaoniiiii/sunshine/pull/26) | DeX 等待循环和 180 秒确认时限均未改变。 | 暂缓，不改已验证的确认编排。 |
| [#27](https://github.com/nanaoniiiii/sunshine/pull/27) | 快照 `009850ac` 的 block-body 函数缺少 return，实际编译失败。 | 不合入。 |
| [#29](https://github.com/nanaoniiiii/sunshine/pull/29) | 地址分类规则未优化，并引入 Host／Accumulator／新返回类型。 | 暂缓；只通过 #6 删除原本无用的旧方法。 |
| [#30](https://github.com/nanaoniiiii/sunshine/pull/30) | 闲置恢复算法不变，新增 Host 与日志转接。 | 暂缓。 |
| [#31](https://github.com/nanaoniiiii/sunshine/pull/31) | 普通退出改用 closeQuietly，吞掉关闭错误，可能错误报告成功。 | 不合入这一行为回退。 |
| [#32](https://github.com/nanaoniiiii/sunshine/pull/32) | 物理采集恢复搬移，增加多项可变状态委托，没有运行收益。 | 暂缓。 |
| [#33](https://github.com/nanaoniiiii/sunshine/pull/33) | 相比旧版保留了 DisplayFailureMessage，但仍只是错误恢复的搬移。 | 暂缓，现有错误语义保留。 |
| [#34](https://github.com/nanaoniiiii/sunshine/pull/34) | 全屏会话编排加 Host 转接，系统交互与等待没有减少。 | 暂缓。 |
| [#35](https://github.com/nanaoniiiii/sunshine/pull/35) | 与 #5 重复实现音频批量复制。 | 不重复合并，收益由 #5 覆盖。 |
| [#36](https://github.com/nanaoniiiii/sunshine/pull/36) | HTTP 状态写入串行化、错误 JSON 转义有明确正确性收益；锁内读取请求体会阻塞其他查询。 | 部分采纳并缩小锁范围；原子替换暂不采纳。 |
| [#37](https://github.com/nanaoniiiii/sunshine/pull/37) | 固定 Locale.ROOT 会改变部分语言的数字显示，不是性能优化。 | 暂缓，无当前需求则保留系统地区格式。 |
| [#38](https://github.com/nanaoniiiii/sunshine/pull/38) | 快照 `057ce288` 仅换图标依赖，实际出现 Dashboard、Gamepad、ScreenShare 等大量 unresolved reference。 | 不合入，保留 extended；同一 import 包名不能证明来自 core。 |
| [#39](https://github.com/nanaoniiiii/sunshine/pull/39) | 增量确认 reconcile 只被自身测试引用；生产路径使用 SettingsAutosave.rebase，已有真实自动保存测试覆盖编辑与轮询。 | 合入纯清理，并删除三个只测试死代码的用例；不宣称有运行时加速。 |

已关闭的 #21／#23／#28 分别被 #31／#32／#33 替代，不重复引入过期版本。

## 验证与实现保持

增量 #39 合入前为 221 项测试通过，0 失败、0 错误；#39 删除三个仅验证死代码的用例后，最终套件为 218 项。新增覆盖混合音频环绕／溢出／整包读取与队列参考模型的等价性，日志并发追加／清空与发布一致性，PIN 各条错误路径的脱敏，配对 API 并发更新不丢失、慢请求体不阻塞读取及有效 JSON 错误。

最终 `testReleaseUnitTest lintRelease assembleRelease` 全部通过：218 项测试、0 失败、0 错误；Lint 0 错误、48 条警告（含现有 Compose 自定义检查版本不匹配的边界）。三个候选 APK 均验证签名与上一正式版一致、包名／版本／ABI 正确，包内原生模块与构建产物一致；Robolectric 不在生产运行依赖中。

| 候选 APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 7320946 | `ee8a2c9ce15762e026e9675a68dab69c5e2fa2709015dee1b8a261013b116c67` |
| armeabi-v7a | 6355494 | `62d67e8b988976d8983a264c4511440b79c7596b889c0a3bfd05cc8c01267e60` |
| universal | 10738890 | `cd48f6a3bc9e772c955dfd04ce9087ede092faaaa534eb7e7538920f32661070` |

覆盖安装命令起初被执行工具拒绝，仅返回 `blocked by policy`，具体策略来源未知。用户明确要求“开始验证”后正常重试安装成功，已核对设备实际 APK 的散列与候选一致。配置除版本号外保持一致，配对记录和授权保留，原有 Moonlight 配对可直接收到画面，诊断解析与 HTTP 状态接口检查通过。继续检查更正了此前将 `Keyguard showing=true` 等同于真实锁屏的结论，并发现既有锁屏检查误拦受信任设备；修正版 `v2026.10.24.2` 已改用实际设备认证状态，221 项测试通过并已安装。修正版的桌面创建、息屏及输入实测仍待连接后继续。完整事实与边界见[候选实机验证记录](../validation/reviewed-optimizations-2026-10-24.md)，候选暂不公开替换官网 `v2026.10.23.3` 正式安装包。

保留虚拟屏、DeX、Shizuku 自动准备、手柄映射、编码器／队列／网络协议的现有实现；原生库和 UI 图标方案不变。这里的“保持”表示未改这些路径且通过对应可执行检查，不等于所有设备、游戏、异常断电场景均已实测。没有承诺绝对无回归。

依赖检查参考：[OkHttp 4.12.1 发布源码](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.1/okhttp-4.12.1-sources.jar)、[Robolectric 官方 JDK 17 配置](https://robolectric.org/getting-started/#running-with-java-17-and-higher)。
