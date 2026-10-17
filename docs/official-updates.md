# 官网安装包与应用内更新

官网最新安装包入口：

- [arm64-v8a](https://nanaoni.tech/downloads/latest/arm64-v8a.apk)
- [armeabi-v7a](https://nanaoni.tech/downloads/latest/armeabi-v7a.apk)
- [universal](https://nanaoni.tech/downloads/latest/universal.apk)
- [历史版本：GitHub Releases](https://github.com/nanaoniiiii/sunshine/releases)

“最新”指有完整三个 ABI 安装包的最新公开版本，包括已公开的预发布版；应用更新窗口会标注预览版。源码提交、草稿和未公开候选不会触发更新。当前发布版本为 v2026.10.17.5 正式版；官网同步同版本的三个 ABI 安装包。

## 应用行为

支持此功能的新版先读取 `https://nanaoni.tech/updates/latest.json`，按设备 ABI 选择版本固定的官网 APK 地址；官网检查失败再尝试 GitHub API 和公开发布订阅源。有效官网清单显示已是最新时不会为了检查旧版本而继续请求 GitHub。更新检查使用独立 IO 协程，下载由 DownloadManager 管理，不占用串流引擎线程。

启动时与后台定期检查，发现更新后通知。Android 后台调度不保证即时通知，设置中可手动检查。在应用内下载、显示进度并校验大小、SHA-256、包名、版本和签名，然后直接调用系统安装器。仍需正常允许安装来源并确认系统安装；不支持静默安装。仅配置官网不能改变已经安装的旧更新器，需先安装一次支持官网更新的新包。

## 官网服务

`website/worker.mjs` 是入口，`update-service.mjs` 实现更新服务。首页静态资源沿用原托管；只对 `/updates/*` 和 `/downloads/*` 运行更新逻辑，不改 DNS 或域名绑定。

- 打包保留当前公开版本的三个 APK 和基线清单，即使 GitHub 暂时不可用，仍能提供这组包。
- 收到请求时，服务器最多每五分钟查询一次 GitHub 公开 Release 列表，忽略草稿、未发布、不完整或摘要无效的版本。有效新版本无需等待手机连通 GitHub即可被发现。
- 新发布包首次被请求时，由服务器从本仓库获取，校验大小与 SHA-256 后缓存；客户端下载始终使用官网域名。首次镜像仍依赖服务器到 GitHub 的连通性，缓存不是永久存储。
- GitHub 暂时不可用时保留最近有效清单，必要时使用打包基线；健康 API 撤回版本时不继续将其推送为最新。
- 最新地址不缓存重定向；APK 地址含版本，避免清单和二进制更新交错。旧版本地址跳转本仓库 GitHub，不在官网提供历史包列表。
- 提供 ETag、字节范围和 `206 Content-Range` 续传。无法续传会导致 Android DownloadManager 的 1008 错误；服务用流式分段避免为了一个 Range 在内存里复制整包。
- 只接受项目约定的版本、ABI 与 APK 名称。未知更新路径返回 404，不返回 SPA 首页。

## 每次公开发布后的同步

先完成 GitHub Release（含三个 ABI APK 的最终附件），再准备官网静态输出，运行：

```powershell
python tools/sync_official_release.py --deployment-dir <现有Cloudflare部署目录> --apk-dir <本次公开发布包目录> --deploy
```

脚本使用 gh 和 Wrangler 各自现有登录，默认自动选最新完整公开版本，不能上传草稿候选；缺少本地包时从对应 GitHub Release 下载。所有包校验通过后才写清单。只清除上次清单明确列出的旧官网 APK，GitHub 历史包保留。先 `versions upload`，再对返回的精确版本执行 `versions deploy`，核对 100% 流量，并记录旧部署供回滚。没有 `--deploy` 时仅准备本地内容。

官网会自动发现新 Release；上述同步仍应纳入每次正式发布，以把当前包变成持久静态基线，而不是只依赖边缘缓存。不需要将个人 OAuth 凭据放入 GitHub Secrets，也没有新增计划任务。

## 验证范围

145 项 Release JVM 测试通过，含六项新增官网更新策略测试；15 项官网服务测试覆盖发布筛选、ABI、SHA-256、撤回、离线基线、缓存、历史链接、Range、ETag、HEAD 与错误路径。Release 构建通过，lint 无错误。实际 workerd 本地服务完成整包摘要与 `206` 分段响应检查。

生产官网入口已部署。以下完整安装链路在 v2026.10.17.5 发布前，使用当时公开的 v2026.10.17.3 作为真实更新目标验证。OPPO 使用本次源码的低版本私有引导包，实际通过官网发现公开 v2026.10.17.3；下载地址在生产 Worker 日志中确认为本域名的版本固定 APK 路径。最初遇到 1008，修复 Range、ETag 和 If-Match 后继续测试。手机直连速度慢且多次暂停，临时经电脑出口完成同一下载；finally 已恢复三项代理设置为 null 并移除测试 ADB reverse，随后单独复核一致。

应用显示“下载完成，安装包校验通过”，下载文件 SHA-256 与公开附件及本地副本一致。应用直接打开 OPPO 系统包安装器，经过正常安装来源授权和安装确认后显示“安装完成”。PackageManager 确认 versionName v2026.10.17.3 / versionCode 20261032，已安装 base.apk SHA-256 为 `039de41fe4be42c8acdbe9a9de84e1dcd79e8975f1769a5225694bbbdc0bb6ce`。全流程没有打开浏览器。验证后 OPPO 恢复安装支持官网更新的 v2026.10.17.5，配对与应用数据保留。

另一台 S20 当前网络仍有官网连接超时。电脑默认 HTTP 客户端被 Cloudflare 返回 1010，未通过修改浏览器身份或降低站点安全配置绕过。官网下载不等于所有网络都已加速；本轮完成的是官网下载、校验与系统安装链路验收。网站下载文案和链接已同步到 Sites 第 8 版与现有 Worker；当前缺少 browser-harness 和指定 vision skill，未声称完成官网页面视觉回归。

原始记录保留在本地 work/samsung-desktop 和 work/website，不上传手机抓取或凭据。该文档是更新功能说明，不是新的项目交接文档。
