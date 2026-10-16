# 应用内更新与控制窗验证 2026-10-17

v2026.10.17.3 / 20261032：119 项 JVM 测试、Release lint、三种 APK 构建通过。新增更新回归覆盖版本排序、禁止回退、草稿与缺失 APK、ABI 选择、仓库地址限制、摘要格式、Atom 备用路径、禁止 XML 外部实体。

S20+ 与 OPPO 实际连接：原主屏无控制窗，第二屏 Display 158 有实际绘制的控制窗，退出后消失，主屏息屏模式无窗，最终面板与配置恢复。公开交付不包含设备原始抓取。

S20+ 已验证应用内更新对话框、无新版本结果、6 小时持久网络任务。当前网络直连 GitHub 握手被关闭；借用电脑出口后，匿名 API 配额为 0，实际使用官方发布 feed 检查成功。测试结束已恢复原 `http_proxy=null`，删除临时 ADB reverse，未在应用中内置代理或账号凭据。

## 实际发布附件安装结果

- 最终发布：[v2026.10.17.3](https://github.com/nanaoniiiii/sunshine/releases/tag/v2026.10.17.3)，预发布；代码提交 `1266166440f98a7c21706c1f304522e21ca48b4a`。v2026.10.17.2 遇到系统“打开方式”选择器，已转回草稿，不作为交付版本。
- 在 S20+ 使用相同最终功能源码、仅版本号降低为 v2026.10.17.2 / 20261031 的私有引导包。由软件更新发现真实发布的 v2026.10.17.3，出现 ID 2042 更新通知；直接在应用内下载 arm64-v8a APK。
- 界面显示“下载完成，安装包校验通过”；核对手机上的下载文件 SHA-256，与 GitHub 附件 digest 和本地交付副本相同。
- 首次安装权限在系统设置中开启 Sunshine 后返回。最终实现使用 INSTALL_PACKAGE 并选择系统安装器，实际前台组件为 `com.google.android.packageinstaller/com.android.packageinstaller.PackageInstallerActivity`，直接显示“要更新此应用吗？”，没有启动浏览器或文件查看器。
- 经过正常系统安装确认与 Play 保护扫描，系统提示“此应用看起来很安全”，继续后显示“已安装应用”。未关闭 Play 保护或绕过其扫描。
- 安装后 PackageManager：versionName `v2026.10.17.3`，versionCode `20261032`。已安装 base.apk 的 SHA-256 为 `039de41fe4be42c8acdbe9a9de84e1dcd79e8975f1769a5225694bbbdc0bb6ce`，与发布 APK 完全一致。点击系统“打开”后 Sunshine 正常启动。
- 最终版本再连接 OPPO：配对保留；创建第二屏后控制窗实际可见，断开最后一路会话后桌面恢复、控制窗删除。测试模式配置恢复为原值，结束时无活动会话。

原始证据在本地 `work/samsung-desktop/`：`in-app-update-install-result.json`、`updater-direct-system-installer.png`、`updater-install-success.png`、`overlay-last-disconnect.json`、`final-build-verification.json`，不随 Git 发布。平板仍离线，本轮更新安装结果仅针对 S20+；之前平板桌面验证见另一记录。
