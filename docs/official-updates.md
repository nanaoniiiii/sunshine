# 官网安装包与发布时自动同步

官网入口：

- [arm64-v8a](https://nanaoni.tech/downloads/latest/arm64-v8a.apk)
- [armeabi-v7a](https://nanaoni.tech/downloads/latest/armeabi-v7a.apk)
- [universal](https://nanaoni.tech/downloads/latest/universal.apk)
- [GitHub Release](https://github.com/nanaoniiiii/sunshine/releases/latest)

当前版本以官网已推送的更新清单为准。官网按发布时推送的副本提供安装包，访客访问更新清单或下载文件时不再查询 GitHub，也不临时从 GitHub 回源下载。

## 发布流程

以包含三个最终 ABI APK 的公开 GitHub Release 作为同步触发点。先在草稿中上传全部 APK 和校验文件，再公开发布；普通源码提交保留现有安装包。`.github/workflows/official-release-sync.yml` 会自动运行 `tools/push_official_release.py`：

1. 从该公开 Release 取得三个附件，检查固定仓库、文件名、大小与 GitHub 提供的 SHA-256。
2. 全部检查通过后，把同一批文件上传官网签名发布接口。
3. 官网逐份验证实际字节和摘要，三份副本均完成后才更新版本清单，避免把不完整版本推送给客户端。
4. 上传失败会重试；需要补跑时，可在 GitHub Actions 手动运行同一工作流，填写公开版本号。重复执行使用相同摘要，旧版本不会覆盖较新的官网版本。

工作流也处理 Release 撤回或删除；官网回到仍保留的前一公开副本。动态存储保留最近三组版本，历史原始文件仍在 GitHub。公开的预发布版本也可同步，客户端会标注预览版。

同步使用专用的 `OFFICIAL_RELEASE_SYNC_SECRET`，仅允许向官网发布安装包和清单；Worker 端对应 `RELEASE_SYNC_SECRET`。凭据放在 GitHub Secrets 与 Cloudflare Worker Secrets，不需要把个人 Cloudflare OAuth 放入 GitHub。源码、配置示例、日志均不包含密钥值。

## 官网服务

`website/worker.mjs` 是入口；`update-service.mjs` 负责读取和下载，`release-publish.mjs` 负责验证签名上传。现有 Cloudflare Worker 继续使用原域名及静态页面，RELEASES KV 保存发布时上传的副本与清单。

- `/updates/latest.json`：读取已推送清单，存储尚未初始化时使用打包静态基线。响应包含 `X-Sunshine-Update-Mode: push`。
- `/downloads/latest/<ABI>.apk`：跳转到已发布的版本固定官网 URL，不缓存 latest 重定向。
- 版本固定文件：读取官网静态基线、已推送副本或缓存，不在访客请求中访问 GitHub。已清理的历史版本可跳转到本仓库的历史附件。
- 提供强 ETag、If-Match、If-Range、HEAD 和 `206 Content-Range` 断点续传；未知文件返回 404，不把 SPA 首页当成 APK。
- 每个 APK 接收上限为 25 MiB。签名包含方法、路径、时间戳和内容摘要；服务器验证实际字节，拒绝篡改、过期请求和同版本号不同内容。
- 只有签名发布接口写入存储，访客接口只读。元数据边缘传播可能有短暂延迟，工作流依据服务器校验完全部副本后、经签名认证的发布接口回执判断同步完成。

`tools/sync_official_release.py` 保留为维护工具，用于后台代码部署或准备新的静态基线。它沿用现有 Wrangler 登录，通过精确版本上传与部署维护已有 Worker；日常发布 APK 已由 GitHub Actions 自动推送，不需要人工再部署 Worker。

## Android 应用内更新

支持官网更新的应用优先读取官网清单并选择对应 ABI 的版本固定下载地址。客户端官网检查失败时仍保留 GitHub API 和发布订阅源备用路径；这是客户端降级，与官网服务运行时查询 GitHub 不同。

从 v2026.10.31.2 开始，下载由应用自身的前台下载服务管理，最多四路请求精确字节范围；同一版本、ABI 和摘要的下载保留分段断点，暂停或进程中断后可以继续。慢速或失败时仅在官网固定版本地址与本项目 GitHub 原始附件之间切换，不使用第三方代理镜像。下载窗口显示实际字节、平均速度和使用的来源。没有 Range 支持时降级为完整文件下载，较旧版本的 DownloadManager 任务仍能读取完成状态。

文件拼接后核对大小和 SHA-256；安装前再次核对 APK 包名、版本号和签名。安装仍使用正常的系统授权与确认流程。下载服务与采集主机处于同一进程，避免 Android 13 上新建仅 dataSync 的兄弟进程导致录屏失效。已开启“开机即连”时，应用更新后恢复服务与录屏。

## 下载速度与实测边界

发布同步成功不等于所有网络的大文件传输都快。本次在 S20+ 原网络复现了缓存命中仍仅约 1–3 KB/s 的持续传输；同手机经电脑已有出口，约 45 秒完整收到同一 7.1 MB 文件。部署推送模式后，手机原网络的慢速仍存在。详见 [持续下载与发布同步验收](validation/official-download-throughput-2026-10-22.md)。

19 项官网服务测试通过，实际 workerd 完成整包摘要与 206 分段检查；[实际 GitHub Actions 同步运行](https://github.com/nanaoniiiii/sunshine/actions/runs/39631463367) 成功。网页布局未改动，本轮未将部署元数据当作页面视觉验收。
