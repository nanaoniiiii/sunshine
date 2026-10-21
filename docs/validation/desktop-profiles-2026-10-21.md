# 虚拟屏默认息屏、第二屏桌面与 DeX 分辨率

记录日期：2026-10-21。最终交付版本 `v2026.10.21.2`，versionCode `20261036`，沿用项目签名。已安装至 SM-G986U1 / Android 13，并在用户后续授权后公开发布 GitHub。原生 DeX 竖屏缺键未修复，用户已要求停止补充按钮方案；最终源码与 APK 不含该方案。

## 最终行为

- 首页统一入口为“开启虚拟屏”。默认配置 `virtual_screen_mode=screen_off`：只关闭手机物理面板，继续采集和操作原主屏，不启动 Sunshine 桌面或 DeX，不改主屏逻辑尺寸。
- 设置 → 虚拟屏 → “第二屏桌面模式”默认关闭。只有打开开关，才展开“全屏 Sunshine 桌面（默认）”与“设备自带桌面（DeX 等）”两个单选项；关闭时收起。选项在下次开启虚拟屏时应用，不即时切换正在运行的显示。
- 两个桌面选项由 `virtual_screen_desktop=sunshine|system` 保存；选择 profile 本身不会开启第二屏模式。实验性功能页不再重复提供 DeX 模式开关。旧客户端明确提交的 DeX 布尔值仍可映射 profile，未配置模式时采用主屏息屏默认值；已有明确保存的 desktop 选择继续保留。
- Sunshine 临时作为本次普通第二屏的主页，应用全屏启动，返回到栈底和 HOME 回到 Sunshine。在桌面按返回不会退出，不改主屏默认启动器。
- “退出桌面”释放临时主页守护，把采集与输入切回手机主屏主页，保留原虚拟屏资源，按当前请求尺寸维持全屏并关闭物理面板。主机 UI 同时退到后台，避免覆盖手机主页。结束整个虚拟屏或最后一端断开才释放资源并恢复显示设置。
- 原生 DeX 保留三星主页和任务栏；其他厂商尝试系统次级主页，无能力时明确报错。平板可以自行启用 DeX 后直接串流主屏，应用不再自动启动 Standalone DeX。
- 删除原 Sunshine 常驻悬浮控制窗。最终版本未添加竖屏导航补充窗或按钮。
- 创建独立第二屏或进入主屏息屏串流时，临时将 Android `force_desktop_mode_on_external_displays` 置 0，保存旧值；完整退出、失败或宿主死亡清理时恢复。不再强制开启全局自由窗口支持。

## 最终候选实机验证

Windows Moonlight 连接 S20+；测试内容为桌面和系统设置，不作高熵性能压力结论。

| 场景 | 结果 |
| --- | --- |
| 设置默认值与层级 | 实机截图显示“第二屏桌面模式”关闭，两个 profile 收起；打开后展开默认 Sunshine 与设备自带桌面单选项，保存为 desktop；关闭后保存为 screen_off |
| 默认开启虚拟屏 | 从首页点击“开启虚拟屏”，状态为 screen_off、Display 0、launcher=原主屏画面；实际源尺寸仍为 2400×1080，原 wm override=1080×2400 不变；电脑继续显示手机 UI |
| 默认息屏输入 | 在电脑 Moonlight 点击手机系统主页键，实际回到手机原生主页；SurfaceFlinger 核对物理面板 Off，运行中开发者强制桌面值为 0 |
| 普通第二屏 | 显式打开第二屏桌面模式后，创建 2560×1440 Sunshine 桌面，Display 69，无悬浮控制窗 |
| 退出 Sunshine 桌面 | 通过实际桌面按钮触发后，电脑实际显示手机原生主页，主机 UI 已退到后台；采集与输入 Display 0，retainedDisplayId=69，连接 ID 与 2560×1440 编码尺寸不变，物理面板 Off |
| 恢复 | 结束虚拟屏并断开本次测试连接后，sessions 为空，物理面板 On，主屏恢复原 1080×2400 override，强制桌面值恢复测试前的 1；设置留下 screen_off / sunshine |

最终安装的 arm64 base.apk 与交付副本的 SHA-256 一致：`adc565932d4b75189b37c62ae586de1874d3f14b37e4634855410f846a8aaa7f`。数据与截图证据保存在被 Git 忽略的 `work/desktop-20261021/final-validation/`；v2-before/v2-after 对应最终退出行为，default-panel/default-second-desktop 对应最终默认值与设置层级。电脑画面通过实际 Moonlight 窗口检查，未将 API 成功或 socket 发送计为收图证明。

## DeX 分辨率与竖屏任务栏边界

前序候选已在电脑 Moonlight 验证原生外部 DeX 的逻辑桌面、采集和编码尺寸分别匹配 2560×1440、1440×3168。修复先对指定外部显示应用逻辑尺寸，等待 DisplayManager 实读匹配后再创建镜像；保存旧覆盖，按显示 ID 与 uniqueId 确认后恢复。失败或系统忽略请求时回滚并报错，不把固定 1920×1080 源的缩放标成分辨率已应用。物理 WFD 协商模式仍为 1920×1080@60Hz，不宣称修改物理模式或实现 120Hz。

本轮另确认 S20+ 的 DeXSystemUI 3.0.01.30 在竖屏时主动隐藏部分导航按钮，源屏截图中即已隐藏；原生应用列表和最近任务功能仍可被调用。用户不接受补充按钮方案，故该方案已完全撤回，原生任务栏保持原状，缺键未修复。未修改三星系统包、注入系统进程或伪装横屏配置。这一项不能写入修复通过清单。

前序电脑首连还发现编码器旋转更换 Surface 后，旧 Surface 身份检查导致采集初始化被跳过；现按当前会话与开始时间获取有效 Surface，并在采集尚未启动时补排初始化。本轮多次电脑连接实际出图，仍不据有限次数声称所有重连问题消除。

## 构建与交付

- `:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease` 通过；152 项 JVM 测试，0 失败、错误或跳过；lint 无错误、38 项警告。
- arm64-v8a、armeabi-v7a、universal 三个 APK 均通过签名检查；每个 ABI 的 libsunshine.so 与仓库文件逐字节一致。原生实现没有修改，本轮未重建原生库。
- 本轮最终包验证设备为 S20+；其他品牌次级桌面能力没有本轮实机通过证据。没有继续平板无 HDMI DeX 开发，也没有继续竖屏原生任务栏修复。
- 附件副本位于 `work/releases/v2026.10.21.2/`。同版本号曾有已撤回按钮方案的临时测试构建，最终以以下 hash 为准；手机已替换为最终包。
- 用户后续明确批准上传当前版本，现已[公开发布 GitHub](https://github.com/nanaoniiiii/sunshine/releases/tag/v2026.10.21.2)。官网已配置发布时自动复制安装包，持续下载慢的实测与边界见[下载验收](official-download-throughput-2026-10-22.md)。

| APK | SHA-256 |
| --- | --- |
| arm64-v8a | adc565932d4b75189b37c62ae586de1874d3f14b37e4634855410f846a8aaa7f |
| armeabi-v7a | ed2c02a0e1b689a49d07c3fb072e9a1e8fe3d17fa66ad5446d0aee2f6bb22f42 |
| universal | ff3d3c8aaaf25fb65217d0f3734f3b249a96739bbe9670cdac8e997175934649 |
