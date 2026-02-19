# Radio Browser 电台播放可靠性深度研究（Media3/ExoPlayer）

日期：2026-02-19

## Executive Summary

Radio Browser 的 `hidebroken=true` 与 `url_resolved` 能过滤/预解析一部分坏台与跳转，但在 Android/Media3 播放链路里仍存在几个会造成“批量 Source error”的系统性因素：Cleartext HTTP 默认禁用、HLS（`.m3u8`）模块缺失、ICY/SHOUTcast 兼容性差异、以及“`.m3u` 并非 HLS”的 playlist 预解析缺口。[1][2][3][4]  
在工程上，把这些因素做成可诊断的错误分类 + 播放前解析/降级/重试（而不是靠 Agent 反复 pause/resume），通常能显著提升“选台一次就能播”的命中率。[3][4][5]

## Key Findings

- **Cleartext HTTP 是 Android 9+（targetSdk≥28）最常见的批量失败源**：未显式允许 `http://` 时，系统会直接阻止 cleartext，表现为连接失败/`Source error`，并在 logcat 中出现 “Cleartext HTTP traffic … not permitted”。[1][6]  
- **HLS（`.m3u8`）需要单独引入 Media3 HLS 模块**：仅依赖 `media3-exoplayer` 不等于能播 HLS；官方文档明确要求添加 HLS 模块依赖。[2]  
- **“`.m3u` playlist” 与 “`.m3u8`（HLS）”不是一回事**：ExoPlayer 不支持把通用 `.m3u` 当“待解析 playlist”来处理（它通常会当作媒体本体去加载），因此需要播放前自己解析/挑选候选 URL。[7]  
- **`url_resolved` 不是银弹**：Radio Browser 文档说明 `url_resolved` 会尽量解析重定向与 playlist，但仍可能返回 `http://`、HLS、或被 CDN/区域策略影响的地址；应用侧仍需要兼容与诊断。[5]  
- **ICY/SHOUTcast 兼容性需要显式策略**：Media3 Troubleshooting 明确列出 `ICY 200 OK` 这类“非标准状态行”会导致解析失败，并给出使用 OkHttp/替换网络栈等处理思路。[4]

## Detailed Analysis

### 1) Radio Browser 的数据现实：过滤并不等于“稳定可播”

Radio Browser 的 `hidebroken=true` 能隐藏一部分已标记 broken 的 station，但“是否 broken”是基于社区/探测的近似值，且电台生态里 URL 变化频繁。更关键的是，即使 station 存活，应用侧仍可能因为 **协议策略（cleartext）**、**缺少解码/协议模块（HLS）**、**非标准 HTTP 行为（ICY）** 或 **playlist 形态** 而失败。[1][2][4][5]  
结论：`hidebroken=true` 的价值在于减少噪音，但“播放可靠性”仍需要应用侧工程化兜底。

### 2) Cleartext HTTP：最容易造成“批量挂”的默认策略

AndroidManifest 的 `android:usesCleartextTraffic` 在 **targetSdk≥28** 时默认是 `false`（禁止 cleartext）；如果不显式放开，任何 `http://` 流（包括 `https → http` 的最后一跳重定向）都会失败。[6]  
Android 官方同时提供 `network_security_config` 来做更精细的控制（例如仅对某些域名允许 cleartext，或在 debug/release 用不同策略）。[1]

建议落地策略（从简单到可控）：

1) **简单粗暴（高命中率）**：`android:usesCleartextTraffic="true"`  
   - 优点：马上救活大量 `http://` 电台。  
   - 风险：降低传输安全性（可被中间人劫持/篡改）。[1]
2) **更可控（推荐）**：`network_security_config`  
   - 适合希望“默认禁止 cleartext，但 radio 场景允许”的工程口径；也适合 debug-only 放开、release 更谨慎的策略。[1]

工程上还建议把“cleartext 被禁止”做成**可诊断的错误分类**（对用户提示“该台为 HTTP，系统默认禁止明文，已自动换台/请开启兼容模式”），而不是只冒出 `Source error`。

### 3) HLS：`.m3u8` 很常见，但模块依赖经常漏

大量电台采用 HLS（`.m3u8`），尤其是 CDN/多码率场景。Media3 文档明确：要播放 HLS，必须添加对应的 HLS 模块依赖。[2]  

工程建议：

- **把“缺少 HLS 模块”纳入故障诊断**：如果遇到 `.m3u8` 且当前构建未包含 HLS 模块，应明确提示“缺少 HLS 支持”，而不是泛化成 `Source error`。
- **增加一个“已知 HLS 样例台”的冒烟清单**：每次发布前手测 1～2 个 `.m3u8` 台，避免回归。

### 4) Playlist：`.pls/.m3u` 的解析与候选筛选

Radio 生态里的 playlist 常见两类：

- **HLS playlist（`.m3u8`）**：交给 HLS 模块处理即可（前提：依赖齐全）。[2]
- **通用 playlist（`.m3u` / `.pls`）**：往往是“若干流 URL 的文本列表”。ExoPlayer 不把这种 `.m3u` 当 playlist 解析，因此需要应用侧先解析出候选 URL，再逐个尝试。[7]

可落地的“小妙招”（偏工程策略）：

- **判别**：同时依据 `Content-Type`（如 `audio/x-mpegurl`/`application/vnd.apple.mpegurl`/`text/plain` 等）、URL 后缀、以及 body 首行/注释特征（`#EXTM3U`、`[playlist]` 等）。[4][5]
- **解析**：  
  - `.m3u`：忽略 `#` 注释行，收集每行 URL。  
  - `.pls`：INI 风格解析 `File1=...`、`File2=...`。  
- **筛选与限流**：去重、只保留 `http(s)`、限制候选数量（例如前 10 个）、限制抓取 body 大小与超时，避免误把网页当 playlist 抓太多。

### 5) ICY/SHOUTcast：非标准状态行与网络栈差异

部分 SHOUTcast/ICY 服务器会使用 `ICY 200 OK` 作为状态行。Media3 Troubleshooting 指出，这会导致“Unexpected status line: ICY 200 OK”这类错误，并给出通过 OkHttp/替换网络实现来规避的方向。[4]  

建议：

- 采集真实失败样本（logcat + URL），区分是 **cleartext**、**HLS**、**ICY**、还是 **404/403/SSL**。  
- 若 ICY 是高频根因：考虑引入 `media3-datasource-okhttp` 并切换到 OkHttp DataSource（或在特定错误上做 fallback）。[4]

## Areas of Consensus

- Radio Browser 数据“够用但不保证稳定可播”，应用侧需要“可解释 + 快速跳过坏台”的工程策略。[5]  
- Cleartext HTTP 与 HLS 依赖缺失都可能造成“批量 Source error”，应优先排查与修复。[2][6]  
- 通用 `.m3u` playlist 需要应用侧预解析；把 `.m3u` 等同于 `.m3u8` 会踩坑。[7]

## Areas of Debate

- **允许 cleartext 的范围**：全局允许（最省事） vs 使用 `network_security_config` 精细控制（更安全但实现/维护更复杂）。[1][6]  
- **处理 ICY 的方式**：改用 OkHttp DataSource（依赖增加、但可能更兼容） vs 维持现状并仅做错误分类/换台（成本更低）。[4]

## Sources

[1] Android Developers — Network security configuration（官方文档；权威）: https://developer.android.com/privacy-and-security/security-config  
[2] Android Developers — Build an HLS playback app（官方文档；权威）: https://developer.android.com/media/media3/exoplayer/hls  
[3] Android Developers — Media3 Troubleshooting（官方文档；权威）: https://developer.android.com/media/media3/exoplayer/troubleshooting  
[4] Android Developers — Media3 Troubleshooting: “Unexpected status line: ICY 200 OK” 段落（官方文档；权威）: https://developer.android.com/media/media3/exoplayer/troubleshooting#unexpected-status-line-icy-200-ok  
[5] Radio Browser API 文档 — station 字段（`url`/`url_resolved` 等）（官方项目文档；高可信）: https://api.radio-browser.info/#Stations  
[6] Android Developers — `<application android:usesCleartextTraffic>`（官方参考；权威）: https://developer.android.com/guide/topics/manifest/application-element#usesCleartextTraffic  
[7] ExoPlayer Issue #2885（通过 Lightrun 汇总的 maintainer 结论：不支持通用 `.m3u` playlists；二手来源，需与实际样本验证）: https://lightrun.com/answers/google-exoplayer-support-m3u-playlists

## Gaps and Further Research

- 需要从真实失败样本中统计根因分布（cleartext / HLS 缺失 / ICY / 403 / SSL / 超时等），以便排序投入产出最高的修复项。[3]  
- 需要验证：引入 HLS 模块与 cleartext 策略后，“Source error 率”在你常用的国家/关键词集合上能下降多少（建议做 20～50 个站点的对照实验）。  
- 需要确认：当前项目的网络栈在遇到 ICY/重定向/Content-Type 异常时的实际行为（同一 URL 在浏览器/ffplay/其他开源 App 的对比），以决定是否要做 OkHttp fallback。

