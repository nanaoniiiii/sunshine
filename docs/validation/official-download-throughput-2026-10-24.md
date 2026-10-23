# v2026.10.24.3 官网副本与完整下载核验

记录日期：2026-10-24（北京时间）。本次发布目标为 [v2026.10.24.3](https://github.com/nanaoniiiii/sunshine/releases/tag/v2026.10.24.3)，功能源码提交 `beeacf669fcf7fd344ae20d65467c0bbe9f83feb`。

## 发布与副本

[官网同步工作流](https://github.com/nanaoniiiii/sunshine/actions/runs/40012633746) 已成功，三个 APK 已复制到官网。公开更新清单 `https://nanaoni.tech/updates/latest.json` 的版本为 `v2026.10.24.3`，三项附件的名称、大小、SHA-256 digest 与本地验证包及 GitHub Release 一致，下载地址均指向官网的版本固定 URL。

手机响应为 HTTP 200，`Content-Length: 7321630`、`X-Sunshine-Download-Source: published-copy`。本次实际读取的是官网发布副本，不需要访客触发 GitHub 查询或文件中转。

## 完整下载样本

电脑使用 `curl --noproxy '*'`；手机为当前 USB 连接的 S20+ `SM-G9860` / Android 13，使用系统自带 `/system/bin/curl --noproxy '*'`。手机请求直接从手机发出，没有经电脑转发；未更改手机代理、DNS、VPN或网络设置。三个下载均为实际完整 APK，按收到的全部字节计算摘要。

| 客户端 | 文件 | 收到字节 | 总时间 | 校验 |
| --- | --- | ---: | ---: | --- |
| 电脑 | arm64-v8a | 7,321,630 | 3.589 秒 | SHA-256 一致 |
| 电脑 | armeabi-v7a | 6,356,178 | 3.004 秒 | SHA-256 一致 |
| 电脑 | universal | 10,739,574 | 4.828 秒 | SHA-256 一致 |
| S20+ 系统 curl | arm64-v8a | 7,321,630 | 4.057 秒 | SHA-256 一致 |

手机首包时间为 `1.576 秒`，总平均约 `1.80 MB/s`（十进制）。以上手机样本确认收到整个文件，包含开始后的持续传输；没有将首包成功或 HTTP 200 单独当成下载完成。

arm64 文件 SHA-256 为 `17ff3edb86f359568dd851c22670a3e26eac261b9719c7ecc0c14fea1178fb1b`。其他两个文件的摘要见[最终安装包与实机记录](reviewed-optimizations-2026-10-24.md)。

## 边界

[2026-10-22 的慢速样本](official-download-throughput-2026-10-22.md)仍保留：当时手机原网络的持续速率仅约 1–3 KB/s，推送官网副本没有解决那条访问线路。当前设备、时间、目标版本及网络样本与当时不能视为完全相同的受控对照；本轮只更新发布副本，没有部署新的下载传输实现，因此不能把速度差异都归因于某项网站代码修复。

本次手机测试客户端是系统 curl，未将结果推广到所有浏览器、运营商、地区或手机。另一个 Python urllib 匿名探针返回 HTTP 403；未改写其身份或站点防护来重试。最终完整文件核验由电脑 curl 和手机系统 curl 分别完成。

手机测试 APK 与响应头临时文件均已删除。原始结果和线上清单保留在本地忽略目录 `work/releases/v2026.10.24.3/`，不上传设备配置或抓取日志。
