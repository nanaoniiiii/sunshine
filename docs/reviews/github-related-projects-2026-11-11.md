# Sunshine Android 相近 GitHub 项目调研

截至北京时间 2026-11-11，与本项目整体最接近的是 **android-display-mirror、Flux Streaming、nightmare-space/sunshine_android**；它们涉及 Android 设备作为 Moonlight 主机。**DroidLink** 的 Android 游戏串流目标也很接近，但使用 WebRTC。独立桌面、多显示输入、全局按键映射和重力传输，则分别有值得借鉴的组件项目。

优先阅读顺序是 **Mirror → scrcpy → Extend → SensorServer／SensaGram → Key Mapper**。DeX 新系统适配再看 Dextop、DX Manager、LocalDex；不能把主要面向 Android 16／One UI 8 的实现直接当成 S20+／Android 13 的替代品。

本文覆盖 50 个外部仓库：Android 主机与相似实现 10 个，虚拟屏与桌面 13 个，投屏控制实现 8 个，桌面与按键 5 个，传感器与姿态 4 个，协议与权限基础 10 个。自己的 `nanaoniiiii/sunshine` 不计入外部项目数量。

## 检索范围与比较基线

本次进行了 13 组 GitHub 全站公开仓库检索，并结合网页搜索、项目引用和依赖关系追踪。GitHub 查询返回 294 条记录，去重得到 262 个候选；再筛选并核验 50 个仓库的元数据和项目说明，查看 11 个仓库的文件树，抽查 10 份关键实现或许可文件。检索词包括 Android Sunshine、GameStream host、Shizuku streaming、DeX virtual display、Android WebRTC remote control、gamepad launcher、sensor streaming 和相关精确短语。

这是公开可检索范围的交叉搜索，不是对 GitHub 全部代码的穷尽证明。教程、同名天气应用、纯软件列表、只把 Android 当接收端的项目，以及依赖 Linux 容器的 Android 方案，都按实际角色区分。下面的相似度与阅读优先级是对本项目需求的判断，不表示已做移植或合并。

比较基线为本项目本地 `f92585f`，对应 `v2026.10.31.2`：Android 串流主机、Moonlight 协议、MediaCodec／音频内录、Shizuku 输入、独立第二屏、三星原生 DeX 兼容路径、全局手柄适配、桌面与开机即连。本轮没有安装或运行第三方 APK，也不将其 README 中的低延迟、120fps 或广泛兼容声明作为本机实测结果。

优先级定义：**优先**表示值得下一轮精读；**专项**表示适合某个模块；**参考**表示历史、原型、资源或平台条件较远。所有仓库的 Stars、默认分支提交日期、最新 Release 和许可证快照在文末索引中。

## Android 作为主机的同类项目

| 项目 | 与本项目相近的部分 | 值得借鉴与采用条件 | 优先级 |
| --- | --- | --- | --- |
| [jqssun/android-display-mirror](https://github.com/jqssun/android-display-mirror) | Android 内置 Sunshine，向 Moonlight 输出，使用 MediaProjection、MediaCodec、音频采集和 Shizuku 远程输入。另含 AirPlay／DisplayLink。 | 最接近完整主线；优先比较 JNI、采集、音频、输入和虚拟显示生命周期。其视频不一定需要 Shizuku，输入需要；不能把权限条件混为一项。 | 优先 |
| [Flux-Sniffer-Mods/Flux-Streaming](https://github.com/Flux-Sniffer-Mods/Flux-Streaming) | 手机作为 Moonlight 主机，支持配对、音频、输入、独立显示和内置授权运行时。 | 有实际 Kotlin 协议与会话代码，值得研究模块化；但其视频打包器明确没有 FEC，不能整体替换当前原生 Sunshine 核心。是很新的小型项目。 | 优先 |
| [nightmare-space/sunshine_android](https://github.com/nightmare-space/sunshine_android) | 明确以 Android 运行 Sunshine 服务端为目标，Flutter 界面加 Android／C++ 桥接。 | 历史移植对照，适合看平台适配与封装；根目录没有明确的总项目许可证，依赖自带许可不能代替整体判断。 | 专项 |
| [Aihoward/DroidLink](https://github.com/Aihoward/DroidLink) | Android 对 Android 游戏串流，MediaProjection、播放音频采集、控制器回传和诊断。 | WebRTC 与输入 DataChannel 很值得比较；使用 Firebase 信令及 TURN 配置，不是 Moonlight 协议的即插即用替代。 | 优先 |
| [aggun04/SunDroid](https://github.com/aggun04/SunDroid) | Android 硬件 H.264 编码到 Windows，鼠标／键盘控制及游戏键位映射。 | 小型原型便于阅读；自定义传输和 PC 查看端，不应因为名称就认定兼容 Moonlight。README 的帧率和延迟数字未在本轮验证，根级许可不明确。 | 参考 |
| [bk138/droidVNC-NG](https://github.com/bk138/droidVNC-NG) | Android 免 Root 远程桌面主机，Accessibility 输入、Home／Back 操作、自启及浏览器查看。 | 值得看授权引导、服务状态和系统导航；采用 VNC，产品目标和游戏串流编码／输入时延不同。 | 专项 |
| [dkrivoruchko/ScreenStream](https://github.com/dkrivoruchko/ScreenStream) | Android 屏幕与音频向浏览器／RTSP 接收端输出。 | 可研究 MediaProjection 生命周期及多传输产品设计；Local MJPEG、Global WebRTC、RTSP 的功能和发行渠道不同，不能当成同一种流。 | 专项 |
| [rustdesk/rustdesk](https://github.com/rustdesk/rustdesk) | 跨平台远程桌面，包含 Android 相关实现。 | 适合比较连接管理、会话状态、远程协助交互；不是 Moonlight 主机实现，整体依赖与 AGPL 许可需单独考虑。 | 专项 |
| [wandou12581/moonshare](https://github.com/wandou12581/moonshare) | Android 采集和 H.264 编码，自定义 TCP／UDP 到 Windows。 | 小型配对、分片和查看端参考；作者明确为实验项目且只读，没有输入回传，不兼容 Moonlight 协议。 | 参考 |
| [Flux-Sniffer-Mods/Flux-Keyboard](https://github.com/Flux-Sniffer-Mods/Flux-Keyboard) | 键盘、游戏模式与手机串流主机的一体化入口。 | 可看模块状态、快捷启动和手柄／键盘协作。与 Flux Streaming 同一项目家族，不应计为两套独立验证过的串流核心。 | 专项 |

## 虚拟屏和 Android 桌面

| 项目 | 与本项目相近的部分 | 值得借鉴与采用条件 | 优先级 |
| --- | --- | --- | --- |
| [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | Android 采集、硬件编码、音频、输入、息屏和虚拟显示。 | 兼容性、显示变化和清理恢复的重要对照；通常需要 ADB 授权，不是 Moonlight 协议，也不等于一项永久系统权限。 | 优先 |
| [jqssun/android-display-extend](https://github.com/jqssun/android-display-extend) | 管理物理／虚拟显示，按显示绑定输入设备、IME、触控板、Home／Back 和内置屏息屏。 | 适合研究多显示输入路由和桌面管理；它管理显示，Mirror 负责创建及串流，两者职责分开。完整能力需要 Shizuku。 | 优先 |
| [NarYuki/Dextop](https://github.com/NarYuki/Dextop) | 手机上的虚拟桌面、窗口、触控／鼠标／键盘、能力探测和恢复记录。 | 可研究能力分级与多后端。README 主要支持 One UI 8，旧 DeX 受限；当前旧主屏 120Hz 控制器已停用，不能直接作为 S20+ 高刷解法。 | 专项 |
| [maze-mei/DX-Manager](https://github.com/maze-mei/DX-Manager) | 电脑通过 ADB／scrcpy 管理 DeX 和多个独立应用窗口，包含恢复与多设备设置。 | 值得看显示清理、会话保护和测试组织。较新 One UI 是主要验证环境；实验 120fps 需要主屏保持开启，不代表任意 DeX 源达到 120Hz。 | 专项 |
| [sam1am/localdex](https://github.com/sam1am/localdex) | 在手机创建隐藏第二屏并在本机显示 DeX，使用本机 ADB 和 scrcpy。 | 原理清楚，适合阅读本机显示、输入与任务策略；要求 One UI 8 或更高，根级许可证未明确。 | 参考 |
| [zanderp/inner-desk](https://github.com/zanderp/inner-desk) | 手机本机运行 Samsung／Pixel 桌面，触控板、显示设置和特权守护进程。 | 可参考本机桌面控制和权限状态；当前范围为 Pixel Android 16／Samsung One UI 8，采用 AGPL。 | 专项 |
| [mekhontsev/magicdesk](https://github.com/mekhontsev/magicdesk) | Android 多工作区、窗口、Termux／Linux 图形应用和特权服务。 | 工作区及窗口组织有参考价值；APK 要 Android 14+，受管桌面要 Android 15+，比当前 Android 13 主机条件更高。 | 专项 |
| [PhiLong-Ho/DexDroid](https://github.com/PhiLong-Ho/DexDroid) | 外接屏桌面、独立旋转、手机触控板和应用设置记忆。 | 可看外屏交互；需要真实视频输出连接及 Shizuku，验证设备有限，根级许可不明确。 | 参考 |
| [HakaI963/headless-android](https://github.com/HakaI963/headless-android) | Shizuku 创建真实虚拟显示、按显示启动应用、输入和像素自测。 | 小型显示引擎值得精读；预览是约 2fps 的 JPEG，不是游戏级视频流。应用克隆／重签名是额外功能，不是我们串流所必需。 | 专项 |
| [lejw0925/android-virtual-screen](https://github.com/lejw0925/android-virtual-screen) | 独立显示、显示范围内输入、Root／Shizuku／Stellar 后端及控制权管理。 | 可参考控制租约和不误操作主屏的边界；偏 AI／MCP 操作，许可为 PolyForm Noncommercial，不能按普通宽松开源库直接纳入。 | 专项 |
| [farmerbb/SecondScreen](https://github.com/farmerbb/SecondScreen) | 分辨率、DPI、方向和外屏配置档。 | 与全屏尺寸和恢复需求相关，但项目明确偏 AOSP／Google ROM，改分辨率需要 Root 或 ADB 权限；维护较久未更新。 | 专项 |
| [MagicianGuo/Android-SettingTools](https://github.com/MagicianGuo/Android-SettingTools) | Shizuku 修改系统设置、分辨率和 DPI，README 说明刷新率的版本限制。 | 历史设置工具对照；不能把刷新率设置值当实际源帧率，也不能假定其实现支持所有固件。 | 参考 |
| [pk1313/flipmirror](https://github.com/pk1313/flipmirror) | Shizuku 调整显示比例，使 Android 投屏更适合 16:9 接收端。 | 与全屏比例功能相近；属于显示优化工具，不是串流服务器。作者的全品牌宣传不作为本项目的兼容性保证。 | 参考 |

## 投屏控制实现和 scrcpy 客户端

| 项目 | 功能对应 | 值得借鉴与采用条件 | 优先级 |
| --- | --- | --- | --- |
| [mingzhixian/Easycontrol](https://github.com/mingzhixian/Easycontrol) | Android 控制 Android，音频、多设备、分辨率自适应、旋转、小窗／全屏。 | 适合对照控制端交互和适配；基于改造后的 scrcpy，不是 Sunshine／Moonlight 协议。README 区分公开源码和官方激活安装包。 | 专项 |
| [daitj/Easycontrol](https://github.com/daitj/Easycontrol) | Easycontrol 的分支，独立包名和 ADB 控制。 | 可比较构建和本地化管理；设备验证范围有限，修改显示和休眠设置需要恢复策略。 | 专项 |
| [shiyunjin/EasyControlNext](https://github.com/shiyunjin/EasyControlNext) | 手机到手机控制，拆分 app 和 app_process 服务端。 | 服务部署、音视频和版本适配可参考；仍需要自己的接收端，不能直接代替 Moonlight。 | 专项 |
| [bonorca/PadKite](https://github.com/bonorca/PadKite) | Android 对 Android 控制，按屏幕尺寸适配，连接丢失后的恢复。 | 适合研究恢复守护和网络中断后的清理；其音频需要 Android 12+，ADB／授权设置仍是前提。 | 专项 |
| [barry-ran/QtScrcpy](https://github.com/barry-ran/QtScrcpy) | PC 控制多台 Android、分组和自定义游戏键位脚本。 | 输入映射和桌面控制端参考；不是 Android 侧独立 Moonlight 主机。 | 专项 |
| [viarotel-org/escrcpy](https://github.com/viarotel-org/escrcpy) | scrcpy 图形管理、多设备、旋转和控制栏。 | 可看产品交互与多设备状态；主要是电脑前端，不能把其封装功能当成 Android 底层新能力。 | 专项 |
| [yume-chan/ya-webadb](https://github.com/yume-chan/ya-webadb) | Tango，浏览器／TypeScript 的 ADB 和 scrcpy 生态。 | 适合未来网页接收与浏览器控制端；不提供 Moonlight 服务端，也不消除 ADB 信任。 | 专项 |
| [NetrisTV/ws-scrcpy](https://github.com/NetrisTV/ws-scrcpy) | WebSocket 浏览器客户端、scrcpy 画面与多点输入。 | 可研究浏览器回传通道；服务端／ADB 部署与当前独立 APK 主机架构不同。 | 参考 |

## 桌面界面和全局按键

| 项目 | 功能对应 | 值得借鉴与采用条件 | 优先级 |
| --- | --- | --- | --- |
| [keymapperorg/KeyMapper](https://github.com/keymapperorg/KeyMapper) | 手柄、键盘、遥控器按键映射、组合操作、手势和应用动作。 | 全局按键状态、冲突和自定义绑定最值得看；系统动作仍需相应权限，不能保证任何游戏都不拦截输入。 | 优先 |
| [axel358/smartdock](https://github.com/axel358/smartdock) | Android 桌面／平板布局、Dock 和桌面式启动入口。 | 可参考应用列表、桌面状态和任务交互；它不是三星系统 DeX，也不是视频传输层。 | 专项 |
| [farmerbb/Taskbar](https://github.com/farmerbb/Taskbar) | 开始菜单、最近应用、Freeform 和 Android 桌面模式。 | 桌面任务模型与启动逻辑参考；维护快照较旧，外接桌面仍依赖设备输出与系统设置。 | 专项 |
| [mrYouki/YoukiDex-Android-Desktop](https://github.com/mrYouki/YoukiDex-Android-Desktop) | 基于 Smart Dock 的完整桌面界面、系统覆盖层和外观定制。 | 作者已经宣布停止开发，GitHub 已归档。可参考历史界面实现，不作为持续维护依赖。 | 参考 |
| [likeich/console-launcher](https://github.com/likeich/console-launcher) | 游戏主机式界面、手柄导航、应用／游戏资料和主题。 | 可以参考交互与主题结构；当前仓库主要是主题、市场和资源，未提供完整应用源码工程，不能直接移植整个启动器。 | 参考 |

## 控制端重力和传感器数据

| 项目 | 功能对应 | 值得借鉴与采用条件 | 优先级 |
| --- | --- | --- | --- |
| [UmerCodez/SensorServer](https://github.com/UmerCodez/SensorServer) | 将 Android 传感器经 WebSocket 输出，包含类型、时间戳和向量值。 | 与当前“只同步控制端重力供自动横竖屏使用”的需求最直接对应。适合采样、生命周期和消息格式；它发送数据，不等于替换被控端 Sensor HAL。 | 优先 |
| [UmerCodez/SensaGram](https://github.com/UmerCodez/SensaGram) | Android 传感器经 UDP 发到指定接收端，可动态选择传感器。 | 适合轻量重力发送器和更新频率控制；接入串流还需配对、会话归属、乱序／断线处理及自动旋转开关。 | 优先 |
| [yaqwsx/SensorStreamer](https://github.com/yaqwsx/SensorStreamer) | 任意 Android API 支持的传感器，经 TCP 以 JSON／二进制传输。 | 数据协议简单，MIT 许可；维护快照停留在 2022 年，适合作为格式参考。 | 参考 |
| [QuityRolled/moonboard](https://github.com/QuityRolled/moonboard) | Moonlight Android 的实验性 Cardboard VR 客户端，包含头部追踪。 | 可看传感器与接收画面生命周期；头部追踪服务本地 VR 渲染，不等于向 Android 主机转发重力。 | 参考 |

对重力同步的建议：让控制端明确发送重力数据，主机只在自动旋转开启、来源属于当前控制会话时消费。方向稳定后更新对应显示并同步采集／编码；关闭开关保持方向，断流恢复原设置。不能依据视频宽高反推控制端姿态，也不应把 Moonlight 的手柄运动通道直接说成通用手机传感器共享。这个方案是对本项目的设计建议，不是上述仓库已经共同实现的功能。

## 协议和权限基础项目

| 项目 | 在本项目中的角色 | 值得借鉴与采用条件 | 优先级 |
| --- | --- | --- | --- |
| [SunshineStream/Sunshine](https://github.com/SunshineStream/Sunshine) | Sunshine 上游协议主机。 | 配对、RTSP、RTP、FEC、编码和网络调度的基础。原 `LizardByte/Sunshine` 查询本轮被 GitHub 解析为此规范名称；不因此自动更新本项目固定的上游版本。 | 优先 |
| [moonlight-stream/moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) | GameStream 客户端核心和输入协议定义。 | 判断哪些输入／传感器消息确实存在的重要来源；它不是 Android 串流主机应用。 | 优先 |
| [moonlight-stream/moonlight-android](https://github.com/moonlight-stream/moonlight-android) | 官方 Android 接收端。 | 控制端传感器采样、解码、输入与配置变化的基线；接收端功能不能算成 Android 主机功能。 | 优先 |
| [qiin2333/moonlight-vplus](https://github.com/qiin2333/moonlight-vplus) | 增强型 Android Moonlight 接收端。 | 自定义分辨率、输入和扩展能力协商；部分能力要求配套 Foundation 主机，不能假定我们当前主机已兼容全部扩展。 | 专项 |
| [ClassicOldSong/moonlight-android](https://github.com/ClassicOldSong/moonlight-android) | Artemis Android，增强型接收端。 | 适合办公室交互、自定义按钮和虚拟显示配套；仍是接收端。 | 专项 |
| [ClassicOldSong/Apollo](https://github.com/ClassicOldSong/Apollo) | Sunshine 系 PC 主机，按客户端参数创建虚拟显示。 | 会话与显示生命周期参考；其内置虚拟显示方案当前主要是 Windows，不能把 SudoVDA 移植为 Android 显示服务。 | 专项 |
| [AlkaidLab/foundation-sunshine](https://github.com/AlkaidLab/foundation-sunshine) | 面向 Windows 优化的 Sunshine 分支。 | 编码、诊断与客户端扩展协商参考；不是 Android APK，也不是我们仓库的同一实现。 | 专项 |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | 当前免 Root 特权调用基础。 | UserService、Binder 死亡和权限恢复的直接参考；ADB 模式不等于永久系统服务身份。 | 优先 |
| [RikkaApps/Sui](https://github.com/RikkaApps/Sui) | 通过 Root／Magisk 提供特权 API 的相关方案。 | 可作可选 Root 后端参考，不能作为无 Root 主线的前置条件。 | 参考 |
| [remote-android/redroid-doc](https://github.com/remote-android/redroid-doc) | 云端／容器 Android 和云游戏基础设施文档。 | 若未来做云安卓再看；不在真实手机上复用其已有系统与传感器，不是当前设备端 APK 的替代品。组件许可分别核对。 | 参考 |

## 源码抽查中需要保留的区别

1. **Mirror 有 Android 适配实现。** 已看到 Java 主机生命周期与 C++ JNI 桥，不只是 README 引用了 Sunshine。入口：[SunshineHost.java](https://github.com/jqssun/android-display-mirror/blob/934198667d2359ca69c1ead118157d7d8a539c7d/app/src/main/java/io/github/jqssun/displaymirror/sunshine/SunshineHost.java#L24)、[sunshine.cpp](https://github.com/jqssun/android-display-mirror/blob/934198667d2359ca69c1ead118157d7d8a539c7d/app/src/main/cpp/sunshine.cpp#L56)。
2. **Flux Streaming 的协议完整度与我们不同。** 当前视频打包器明确不生成 FEC，不能因兼容 Moonlight 就认定与完整 Sunshine 的丢包恢复相同。[StreamProtocol.kt](https://github.com/Flux-Sniffer-Mods/Flux-Streaming/blob/70f2a707d63803662009e531e7dc9d0a7c0fcb2d/app/src/main/java/io/github/fluxsniffermods/streaming/StreamProtocol.kt#L63)
3. **Dextop 的主屏高刷描述与当前旧入口不同。** `InternalRefreshRateController.applyIfEnabled()` 当前没有应用主屏锁定，支持判断返回 false；注释说明设置转向虚拟显示的请求刷新率。需继续核对新后端，不把旧入口算成现成解锁。[InternalRefreshRateController.kt](https://github.com/NarYuki/Dextop/blob/3767eb279b202e8f9833dc17ac4faa8326d4a83d/android/app/src/main/kotlin/moe/n4tsu/dextop/InternalRefreshRateController.kt#L13)
4. **DroidLink 分开处理控制与摇杆数据。** 实现中控制通道使用有序传输，轴数据使用无序且不重传的 DataChannel。这种按数据性质分开处理的思路适合比较手柄／重力通道。[WebRTCManager.kt](https://github.com/Aihoward/DroidLink/blob/85350c85d1d80bf2eac81b1674e7126383a6ce2b/app/src/main/java/com/droidlink/app/WebRTCManager.kt#L212)
5. **android-virtual-screen 有明确的非商用许可。** GitHub 只将其识别为 Other／NOASSERTION，实际顶层文件为 PolyForm Noncommercial 1.0.0，不能在表格中写成 MIT 或 Apache。[LICENSE](https://github.com/lejw0925/android-virtual-screen/blob/c20c591e0b6bb6dd573a921c9db7775fe740a27d/LICENSE#L1)
6. **Console Launcher 和 Headless Android 的角色不同。** 前者当前仓库主要承载主题与资源；后者真实创建显示，但 README 预览约 2fps。都不能仅凭“桌面／虚拟屏”名称判断为完整低延迟串流方案。[Console Launcher](https://github.com/likeich/console-launcher)、[Headless Android](https://github.com/HakaI963/headless-android)

本轮结论是精读和对照顺序，不是代码合并决定。对于缺少根级许可证、非商用、AGPL 或多依赖项目，移植前应按实际使用文件和依赖许可单独核对；表中许可识别不代替逐文件审查。

## 维护和版本快照

Stars 为本轮 GitHub API 读取值，不表示质量或性能。最近提交使用默认分支提交时间，转换为北京时间；最新 Release 来自 GitHub 的 latestRelease 字段，可能落后于默认分支说明。无 Latest Release 不表示项目从未在其他渠道发行。下面的链接固定到本轮读取的提交，便于后续复查变化。

| 仓库与固定提交 | Stars | 默认分支最近提交 | 最新 Release | 顶层许可识别 | 归档 |
| --- | ---: | --- | --- | --- | --- |
| [jqssun/android-display-mirror · 93419866](https://github.com/jqssun/android-display-mirror/tree/934198667d2359ca69c1ead118157d7d8a539c7d) | 239 | 2026-10-30 | [v0.0.36](https://github.com/jqssun/android-display-mirror/releases/tag/v0.0.36) | GPL-3.0 | 否 |
| [Flux-Sniffer-Mods/Flux-Streaming · 70f2a707](https://github.com/Flux-Sniffer-Mods/Flux-Streaming/tree/70f2a707d63803662009e531e7dc9d0a7c0fcb2d) | 0 | 2026-11-04 | 无 Latest Release | GPL-3.0 | 否 |
| [nightmare-space/sunshine_android · 7e39e83f](https://github.com/nightmare-space/sunshine_android/tree/7e39e83f4db7dfd86e15b5ba2bb24115b1d0aa45) | 15 | 2025-03-23 | 无 Latest Release | 未检出 | 否 |
| [Aihoward/DroidLink · 85350c85](https://github.com/Aihoward/DroidLink/tree/85350c85d1d80bf2eac81b1674e7126383a6ce2b) | 12 | 2026-08-25 | [v3.7](https://github.com/Aihoward/DroidLink/releases/tag/v3.7) | MIT | 否 |
| [aggun04/SunDroid · 4f1f6cc3](https://github.com/aggun04/SunDroid/tree/4f1f6cc3c5b2fd46f7a344a66909bc765b209da4) | 0 | 2026-11-09 | [v1.0.0](https://github.com/aggun04/SunDroid/releases/tag/v1.0.0) | 未检出 | 否 |
| [bk138/droidVNC-NG · 3e5f27e2](https://github.com/bk138/droidVNC-NG/tree/3e5f27e2961e2dce80d89f12e4a5156736e554a1) | 2436 | 2026-11-10 | [v2.22.2](https://github.com/bk138/droidVNC-NG/releases/tag/v2.22.2) | GPL-2.0 | 否 |
| [dkrivoruchko/ScreenStream · d9a535c3](https://github.com/dkrivoruchko/ScreenStream/tree/d9a535c39566d3fc406e70ebca01e75ad45395ad) | 2577 | 2026-11-10 | [4.4.2](https://github.com/dkrivoruchko/ScreenStream/releases/tag/4.4.2) | MIT | 否 |
| [rustdesk/rustdesk · 95cca3f6](https://github.com/rustdesk/rustdesk/tree/95cca3f661f635b3cc85c04935fe8d96d8f432ef) | 128917 | 2026-11-09 | [1.5.1](https://github.com/rustdesk/rustdesk/releases/tag/1.5.1) | AGPL-3.0 | 否 |
| [wandou12581/moonshare · 867a7eeb](https://github.com/wandou12581/moonshare/tree/867a7eeb9f679aa9becb21b0b850902026eefcda) | 0 | 2026-11-02 | [MoonView-1.0.20](https://github.com/wandou12581/moonshare/releases/tag/MoonView-1.0.20) | MIT | 否 |
| [Flux-Sniffer-Mods/Flux-Keyboard · 9394cad7](https://github.com/Flux-Sniffer-Mods/Flux-Keyboard/tree/9394cad7f3d660d08781d7520af7725baa17a48f) | 6 | 2026-10-20 | [flux/v2.0.0](https://github.com/Flux-Sniffer-Mods/Flux-Keyboard/releases/tag/flux/v2.0.0) | GPL-3.0 | 否 |
| [Genymobile/scrcpy · a60891ae](https://github.com/Genymobile/scrcpy/tree/a60891aea193d92e7e5c3942700eca63f9d19a5f) | 154508 | 2026-10-08 | [v5.0.1](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1) | Apache-2.0 | 否 |
| [jqssun/android-display-extend · 4598db3f](https://github.com/jqssun/android-display-extend/tree/4598db3f710a7d7081c4386c5353d909f573ed10) | 217 | 2026-10-20 | [v0.0.27](https://github.com/jqssun/android-display-extend/releases/tag/v0.0.27) | GPL-3.0 | 否 |
| [NarYuki/Dextop · 3767eb27](https://github.com/NarYuki/Dextop/tree/3767eb279b202e8f9833dc17ac4faa8326d4a83d) | 1140 | 2026-11-09 | [v1.7.0](https://github.com/NarYuki/Dextop/releases/tag/v1.7.0) | GPL-3.0 | 否 |
| [maze-mei/DX-Manager · f391baae](https://github.com/maze-mei/DX-Manager/tree/f391baae391b5aca39d68905645af3982fb777cc) | 104 | 2026-10-25 | [v2.5.0](https://github.com/maze-mei/DX-Manager/releases/tag/v2.5.0) | MIT | 否 |
| [sam1am/localdex · 5af90ed4](https://github.com/sam1am/localdex/tree/5af90ed4a5645c52af019abe16d6f3849074184e) | 64 | 2026-08-15 | [v0.0.1](https://github.com/sam1am/localdex/releases/tag/v0.0.1) | 未检出 | 否 |
| [zanderp/inner-desk · cc41ff19](https://github.com/zanderp/inner-desk/tree/cc41ff19d58664493c83dc677b0fc0f8deb3e0dc) | 27 | 2026-08-18 | [v0.8.2](https://github.com/zanderp/inner-desk/releases/tag/v0.8.2) | AGPL-3.0 | 否 |
| [mekhontsev/magicdesk · 8c9e40b3](https://github.com/mekhontsev/magicdesk/tree/8c9e40b3757d36b431dc1e7efbe7216131932e21) | 215 | 2026-11-09 | [v2.2.8](https://github.com/mekhontsev/magicdesk/releases/tag/v2.2.8) | GPL-3.0 | 否 |
| [PhiLong-Ho/DexDroid · 5fdd6420](https://github.com/PhiLong-Ho/DexDroid/tree/5fdd642001e04a663f58cc6e68d288080299b36b) | 4 | 2026-10-28 | [v0.17.0](https://github.com/PhiLong-Ho/DexDroid/releases/tag/v0.17.0) | 未检出 | 否 |
| [HakaI963/headless-android · 20646ebf](https://github.com/HakaI963/headless-android/tree/20646ebff1cf19c86c27e001408028dd5dca6d13) | 4 | 2026-10-20 | [v2.0.0](https://github.com/HakaI963/headless-android/releases/tag/v2.0.0) | Apache-2.0 | 否 |
| [lejw0925/android-virtual-screen · c20c591e](https://github.com/lejw0925/android-virtual-screen/tree/c20c591e0b6bb6dd573a921c9db7775fe740a27d) | 37 | 2026-10-30 | [v0.2.0](https://github.com/lejw0925/android-virtual-screen/releases/tag/v0.2.0) | PolyForm Noncommercial 1.0.0 | 否 |
| [farmerbb/SecondScreen · 29949c4c](https://github.com/farmerbb/SecondScreen/tree/29949c4c1ab7ab97e83d45998a99756d260f3a1f) | 522 | 2024-09-13 | [186](https://github.com/farmerbb/SecondScreen/releases/tag/186) | Apache-2.0 | 否 |
| [MagicianGuo/Android-SettingTools · fe153a9b](https://github.com/MagicianGuo/Android-SettingTools/tree/fe153a9b94c98df374b54c55bba3db26ce8908f7) | 17 | 2024-04-01 | 无 Latest Release | 未检出 | 否 |
| [pk1313/flipmirror · 04bbd23c](https://github.com/pk1313/flipmirror/tree/04bbd23c6b660245ca0b9697b86195d212e77b17) | 2 | 2026-10-17 | [v1.0.2](https://github.com/pk1313/flipmirror/releases/tag/v1.0.2) | MIT | 否 |
| [mingzhixian/Easycontrol · 0f864a74](https://github.com/mingzhixian/Easycontrol/tree/0f864a744108912b284d5c2a02035ab7f90fda2f) | 772 | 2024-04-24 | [10507](https://github.com/mingzhixian/Easycontrol/releases/tag/10507) | GPL-3.0 | 否 |
| [daitj/Easycontrol · 101b487d](https://github.com/daitj/Easycontrol/tree/101b487df68462fb1000bb48f0fda30544ad728c) | 201 | 2026-02-27 | [v1.0.4](https://github.com/daitj/Easycontrol/releases/tag/v1.0.4) | GPL-3.0 | 否 |
| [shiyunjin/EasyControlNext · aeda9901](https://github.com/shiyunjin/EasyControlNext/tree/aeda9901ebf53350491f5454a41269e791918662) | 96 | 2026-03-30 | [v1.1.1](https://github.com/shiyunjin/EasyControlNext/releases/tag/v1.1.1) | GPL-3.0 | 否 |
| [bonorca/PadKite · f02f290e](https://github.com/bonorca/PadKite/tree/f02f290e114b96823f01c933b832310087addd97) | 1 | 2026-10-29 | [v1.7.2](https://github.com/bonorca/PadKite/releases/tag/v1.7.2) | GPL-3.0 | 否 |
| [barry-ran/QtScrcpy · b018bec8](https://github.com/barry-ran/QtScrcpy/tree/b018bec82cc32113cfd13e4ca7e0f198d71c69ef) | 32805 | 2026-10-15 | [v4.2.1](https://github.com/barry-ran/QtScrcpy/releases/tag/v4.2.1) | Apache-2.0 | 否 |
| [viarotel-org/escrcpy · 3a73e0d4](https://github.com/viarotel-org/escrcpy/tree/3a73e0d4de58a4674dddd61fc43956b4882c8bec) | 12341 | 2026-10-26 | [v3.3.1](https://github.com/viarotel-org/escrcpy/releases/tag/v3.3.1) | Apache-2.0 | 否 |
| [yume-chan/ya-webadb · d22e9dc3](https://github.com/yume-chan/ya-webadb/tree/d22e9dc3c9d53178f4cf88cd757253f7ce780b10) | 3284 | 2026-11-06 | [v2.6.4](https://github.com/yume-chan/ya-webadb/releases/tag/v2.6.4) | MIT | 否 |
| [NetrisTV/ws-scrcpy · cd6cea6e](https://github.com/NetrisTV/ws-scrcpy/tree/cd6cea6ebde17af6685c2b325183fc24bef9d52b) | 2611 | 2026-09-30 | [v0.8.1](https://github.com/NetrisTV/ws-scrcpy/releases/tag/v0.8.1) | MIT | 否 |
| [keymapperorg/KeyMapper · 5e01aad0](https://github.com/keymapperorg/KeyMapper/tree/5e01aad089f12439c9ac76a1520f2ff710e83de8) | 2816 | 2026-11-09 | [v4.7.0](https://github.com/keymapperorg/KeyMapper/releases/tag/v4.7.0) | GPL-3.0 | 否 |
| [axel358/smartdock · 517604a7](https://github.com/axel358/smartdock/tree/517604a72745a1c440068b89b3f42c8248abab36) | 1468 | 2026-05-22 | [1.15.0](https://github.com/axel358/smartdock/releases/tag/1.15.0) | GPL-3.0 | 否 |
| [farmerbb/Taskbar · e2a00b9a](https://github.com/farmerbb/Taskbar/tree/e2a00b9a3f3adda069059abb9aebcb8b4e18954f) | 1299 | 2024-11-21 | [207](https://github.com/farmerbb/Taskbar/releases/tag/207) | Apache-2.0 | 否 |
| [mrYouki/YoukiDex-Android-Desktop · f8041027](https://github.com/mrYouki/YoukiDex-Android-Desktop/tree/f80410273e5d27ac4ce5810e21efdaa30f69a9cb) | 605 | 2026-09-15 | [3.5](https://github.com/mrYouki/YoukiDex-Android-Desktop/releases/tag/3.5) | GPL-3.0 | 已归档 |
| [likeich/console-launcher · d32609db](https://github.com/likeich/console-launcher/tree/d32609db1ea8fea84e90e32f9f2d9a17172a7907) | 330 | 2026-11-05 | [v2.0.0-beta.6.12](https://github.com/likeich/console-launcher/releases/tag/v2.0.0-beta.6.12) | 未检出 | 否 |
| [UmerCodez/SensorServer · 5ae40178](https://github.com/UmerCodez/SensorServer/tree/5ae401780d99debcabb8dc259256c2652dada0a6) | 450 | 2026-05-24 | [v7.2.1](https://github.com/UmerCodez/SensorServer/releases/tag/v7.2.1) | GPL-3.0 | 否 |
| [UmerCodez/SensaGram · d438730e](https://github.com/UmerCodez/SensaGram/tree/d438730e3af10bb6eb4b3c9a5e10a3a3b51b5f6e) | 77 | 2025-12-23 | [v1.5.2](https://github.com/UmerCodez/SensaGram/releases/tag/v1.5.2) | GPL-3.0 | 否 |
| [yaqwsx/SensorStreamer · e58e9be4](https://github.com/yaqwsx/SensorStreamer/tree/e58e9be40c3df221e364049ec951ae7464c0792c) | 60 | 2022-10-09 | 无 Latest Release | MIT | 否 |
| [QuityRolled/moonboard · 376408f4](https://github.com/QuityRolled/moonboard/tree/376408f4d81e9c4093fab5a8f07e1d2119f178f9) | 2 | 2026-10-04 | 无 Latest Release | GPL-3.0 | 否 |
| [SunshineStream/Sunshine · ec2676b6](https://github.com/SunshineStream/Sunshine/tree/ec2676b60bb8220a1807fc761bc2157e5e6dba63) | 43237 | 2026-11-11 | [v2026.914.233613](https://github.com/SunshineStream/Sunshine/releases/tag/v2026.914.233613) | GPL-3.0 | 否 |
| [moonlight-stream/moonlight-common-c · e80a786a](https://github.com/moonlight-stream/moonlight-common-c/tree/e80a786afedeaccff63e839e68c8f3d8a057c13d) | 585 | 2026-11-03 | 无 Latest Release | GPL-3.0 | 否 |
| [moonlight-stream/moonlight-android · b48494cb](https://github.com/moonlight-stream/moonlight-android/tree/b48494cb96bff23d8886c4775cc4f39a1075495d) | 7462 | 2026-09-13 | [v12.2](https://github.com/moonlight-stream/moonlight-android/releases/tag/v12.2) | GPL-3.0 | 否 |
| [qiin2333/moonlight-vplus · 4532ae6d](https://github.com/qiin2333/moonlight-vplus/tree/4532ae6d514b9d315690b83a7eb0df0cf1376995) | 4430 | 2026-11-11 | [v12.14.22](https://github.com/qiin2333/moonlight-vplus/releases/tag/v12.14.22) | GPL-3.0 | 否 |
| [ClassicOldSong/moonlight-android · c5cf27f4](https://github.com/ClassicOldSong/moonlight-android/tree/c5cf27f4dc822db0e863c4691e7a70c74bea977a) | 4187 | 2026-09-09 | [v20.2.6](https://github.com/ClassicOldSong/moonlight-android/releases/tag/v20.2.6) | GPL-3.0 | 否 |
| [ClassicOldSong/Apollo · adc5c5a0](https://github.com/ClassicOldSong/Apollo/tree/adc5c5a0bd80831ce495434bb16aee2cd4175fb8) | 11501 | 2026-05-21 | [v0.4.6](https://github.com/ClassicOldSong/Apollo/releases/tag/v0.4.6) | GPL-3.0 | 否 |
| [AlkaidLab/foundation-sunshine · 6d41627e](https://github.com/AlkaidLab/foundation-sunshine/tree/6d41627e52686b572926ab871291513bf63170e6) | 7886 | 2026-11-11 | [v2026.1031.121458.杂鱼](https://github.com/AlkaidLab/foundation-sunshine/releases/tag/v2026.1031.121458.%E6%9D%82%E9%B1%BC) | GPL-3.0 | 否 |
| [RikkaApps/Shizuku · b844bc49](https://github.com/RikkaApps/Shizuku/tree/b844bc491f1790c72328e1a8e5b2349f8978f0ea) | 33351 | 2025-06-18 | [v13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0) | Apache-2.0 | 否 |
| [RikkaApps/Sui · 2f5fd2a0](https://github.com/RikkaApps/Sui/tree/2f5fd2a04bc061eb2a8431cc3ede9066954f5a7c) | 4362 | 2023-09-30 | [v13.5.1](https://github.com/RikkaApps/Sui/releases/tag/v13.5.1) | GPL-3.0 | 否 |
| [remote-android/redroid-doc · eba9a489](https://github.com/remote-android/redroid-doc/tree/eba9a489990f3f558b7115f25fdc782d610ee8ad) | 7124 | 2026-05-17 | 无 Latest Release | 文档与组件分别核对 | 否 |
