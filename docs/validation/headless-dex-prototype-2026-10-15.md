# 自编写无 HDMI DeX 原型：实现与实测

> 后续进展：直接 Binder 路径的阻碍仍成立，但已通过本机 DeX for PC 接收端实现无 HDMI 原生 DeX 并集成应用。见 [后续集成实测](headless-dex-integrated-2026-10-15.md)。下文保留早期失败实验记录。

2026-10-15，用户授权「你试试自己写一个」后实现。源码位于 `tools/headless-dex/`。

## 结果

已编写、构建和在 Samsung SM-G986U1 / Android 13 上运行。**未实现无 HDMI 启动原生 DeX**。本次取得新的明确阻碍：UID 2000 的实际 Binder 调用在进入无线显示控制器前被 `CONFIGURE_WIFI_DISPLAY` 权限检查拒绝。

这与之前「创建普通/desktop 标记虚拟屏并发送 DeX 广播」的实验不同。本轮尝试由本机回环 WFD 接收端触发真正的三星无线 DeX。

## 实现范围

- `HeadlessDex.java`：读取能力与现有会话，拒绝覆盖现有外接/DeX/投屏，构造 `SemWifiDisplayConfig.Builder.setApConnection(127.0.0.1,7236,...)`、`MODE_WIRELESS_DEX=2`，通过 `IDisplayManager.connectWifiDisplayWithConfig()` 发出真实请求。
- `LocalWfdSink.java`：回环 RTSP/WFD 接收端及本机 UDP RTP/RTCP 消费。支持最小能力协商与 SETUP/PLAY；有消息大小限制、相邻 RTP/RTCP 端口分配、会话关闭。没有视频解码器。
- `LocalWfdSinkTest.java`：仅与自行构建的本机模拟源测试协议，不替代厂商 WFD 测试。
- `run.py`：可重复测试、Android DEX 构建、部署与 JSON 报告。默认诊断，`--attempt` 才请求会话。

## 验证

- 桌面 JVM 测试通过：碎片化 RTSP、非法/过大/重复消息头、能力查询、完整模拟 M1–M7 控制序列、Session/传输端口、RTP/RTCP 接收及资源关闭。
- Java/Android DEX 构建通过，已推送手机并执行。
- 能力：`uid=2000`、`wifi_permission=-1`、`standalone_supported=false`、`desktop_debug=false`。
- 真实请求退出码 3，报告：

```json
{
  "result": "blocked_by_system_permission",
  "stage": "connect_wifi_display_binder",
  "error_type": "java.lang.SecurityException",
  "error": "Permission required to connect to a wifi display: Neither user 2000 nor current process has android.permission.CONFIGURE_WIFI_DISPLAY."
}
```

调用被拒绝，没有在本机进入 RTSP 阶段。因此不能宣称原型已与三星 WFD 引擎握手、已有 DeX 画面或输入可用，也不能推断 Root 后必然成功。

## 厂商应用委托入口核对

只读提取手机现有 DeXonPC APK 并检查清单与相关接收器：

- `DOPService`、`KMSService` 未导出。
- `DOPDiscoveryService` 虽导出，但受 `ACCESS_DOP_DISCOVERY_SERVICE` 自定义签名权限保护。
- `DOPUsbStateReceiver` 使用 USB gadget 状态及真实连接流程，不能由已知入口直接指定本机回环 DeX 会话。

没有发送伪造的硬件/紧急模式广播，没有安装或修改厂商应用。这是对已检查入口的结论，不宣称穷尽所有厂商组件。提取的厂商 APK 与反编译内容仅存放本地 work，不包含于交付 ZIP。

## 结束状态与交付

独立只读核对仍仅有 Display 0、`enabled=2,type=0`，Standalone=false。系统设置维持 `overlay_display_devices=null`、`enable_freeform_support=1`、`force_desktop_mode_on_external_displays=0`。没有改变当前 Sunshine 生产实现、系统特性文件或 Root 状态。

运行与设计限制见 `tools/headless-dex/README.md`。实测 JSON、协议测试日志与最终状态位于 `work/headless-dex/`。继续进行真实 WFD 验证，需要可用的系统授权主体或三星允许的连接入口；将相同代码放进普通 APK/Shizuku shell 用户服务不会增加权限。
