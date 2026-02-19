# v37 Plan：Radio 播放可靠性（减少 `Source error` + 自动跳过坏台 + 状态机稳定）

日期：2026-02-19

## Goal

当用户提出“来点越南/埃及/国内新闻 radio”这类模糊需求时：

1) Agent 能稳定选台并播放（自动 `sync` + `explore` + `play`）。
2) 遇到大量 broken stream（`Source error`）时，能**快速跳过坏台**并落到可播电台（而不是 pause/resume 空转）。
3) `radio status` 在“加载/缓冲”窗口内也尽量**不误报 idle**，避免触发错误的补救动作。

## Background（现状与痛点）

### 现状

- 电台库来自 Radio Browser，`hidebroken=true` 仍会返回不少不可用电台；这是现实约束。
- 播放器基于 Media3/ExoPlayer，直播流准备/缓冲是异步过程。
- `radio-cli` 的“电台发现流程”已约定：`sync → fav/last_played → explore(Grep 索引) → play → status 验证 → 写记忆`。

### 痛点

1) **`Source error` 比例高**：很多电台 URL 失效、被限制、TLS/解析失败、重定向/playlist 问题、流格式不兼容等。
2) **状态误判**：刚 `play` 后立刻 `status` 可能显示 idle/paused（加载尚未 ready），导致 agent 乱做 `resume/pause`。
3) **缺少“坏台处置”闭环**：即使错误可解释，仍需要把“换下一个”固化为默认策略（少问、少折腾、快速落地可播放）。

### 补充（线上资料+本项目现状）：几个“系统性批量杀手”

> 这几条往往会造成“看起来一大片台都挂了”，但其实是播放链路的系统性缺口导致一批台被同时打死。

1) **Cleartext HTTP（`http://`）在 targetSdk=28+ 默认不允许**  
   Radio Browser 里 `url_resolved` 仍有大量 `http://` 流；如果 App 未显式允许 cleartext，会在 Android 9+ 设备上批量失败（通常表现为 `Source error`，root cause 可能是 cleartext not permitted）。  
   - 本项目当前 `app/src/main/AndroidManifest.xml` 未配置 `android:usesCleartextTraffic`，也未配置 `networkSecurityConfig`（需要补齐口径与实现）。
2) **HLS 需要单独的 Media3 模块依赖**  
   许多电台实际是 HLS（`.m3u8`）；在 Media3/ExoPlayer 的模块化依赖下，若未引入 HLS 对应模块，`.m3u8` 会批量不可播（表面同样是 `Source error`）。  
   - 本项目当前依赖只有 `androidx.media3:media3-exoplayer` + `...:media3-session`（未看到 HLS 模块依赖），需要确认并补齐。
3) **“`.m3u` 不等于 `.m3u8`”**  
   `.m3u` 在电台生态里经常只是“文本列表（若干候选 URL）”，不是 HLS playlist。ExoPlayer 通常不会把这种 `.m3u` 当作“需要先解析的 playlist”，而会当成媒体本体去加载，导致失败。  
   - v37 里做 playlist/redirect resolver 的方向正确，但优先级应与 Cleartext/HLS 并列为“先堵系统性缺口”。
4) **ICY/SHOUTcast 状态行与兼容性**  
   少量站点会返回 `ICY 200 OK` 这类非标准 HTTP 状态行；不同 HTTP stack（HttpURLConnection vs OkHttp）对该场景的兼容性不同，可能出现“同一 URL 在某些实现可播/某些实现不可播”。

## PRD Trace（追溯）

- PRD-0030：REQ-0030-012（失败可解释、不得崩溃）
- PRD-0030：REQ-0030-032（播放失败必须可解释，且不影响浏览）
- PRD-0030：Non-Goals 明示 broken stream 必然存在，必须“可解释、可跳过、可刷新”
- PRD-0029（music player）：播放运行时由 Service/Controller 承载，状态需可被 UI/CLI 稳定读取

## 已完成（截至 2026-02-19）

这部分已在主干落地（无需重复做）：

- 播放器错误信息更可诊断：将 ExoPlayer `errorCodeName` + root cause 拼入 `error_message`（减少“只有 Source error”）。
- HTTP 数据源更稳：User-Agent、跨协议重定向、超时策略（缓解部分 30x/UA/超时类失败）。
- 状态机更贴近真实：引入 transport snapshot（`playWhenReady/playbackState/mediaId`）并将“缓冲/准备中”视为 playing-in-progress，降低 idle 误报。
- `radio-cli` 技能文档加入“播放验证 + 跳过坏台”的强制流程（以 `radio status` 判定 error 就换台，最多跳 3 个）。

## Design Options（下一步怎么做）

### 方案 A：只靠 Skill/Prompt 约束（最小改动）

- 保持 `radio play` 只做“发起播放”，由 Skill 强制 `status` 轮询并换台。
- 优点：改动最少。
- 缺点：仍会遇到“状态短窗口误判”“同一坏台反复尝试”“playlist 链接永远播不出”等问题；Agent 复杂度高。

### 方案 B（推荐）：把“可靠性”下沉到播放器/CLI（可测试、低噪音）

1) `radio play` 增加可选等待语义（例如 `--await_ms` / `--verify`）：
   - 发起播放后最多等待 N ms，直到 `playing` 或 `error`（避免立即 status 误判）。
2) 引入 **Playlist/Redirect 解析器**（`.m3u/.pls/.asx/.xspf` 等）：
   - 对明显 playlist URL（或 `Content-Type` 指示 playlist）先解析出真实流 URL，再交给 ExoPlayer。
3) `radio status` 输出增加 transport 级字段（`transport_state/play_when_ready/media_id`），便于 agent 做稳定决策。
- 优点：可靠性与状态判定从“提示词约束”变成“可复用的工程能力”；单测可覆盖。
- 缺点：需要改代码与协议（但可保持向后兼容：新增字段/新增 flag）。

### 方案 C：后台健康检查 + 坏台黑名单（体验最好，成本最高）

- 后台任务抽样探测 station（或用户请求时探测），把“近期可播性”写入缓存，探索/排序时优先推荐“近期 OK”。
- 优点：用户体验最好。
- 缺点：网络成本、被限流风险、复杂度显著上升；需要明确的隐私与频控策略。

本轮（v37）优先做 **方案 B**；方案 C 作为后续 v38+ 备选。

## Scope（v37）

做：

0) **先堵系统性缺口（高收益）**：
   - Cleartext 策略落地：明确是否允许全局 `http://`；或采用 `network_security_config` 做可控策略（并在文档里解释安全权衡）。
   - HLS 依赖补齐：确认并引入 Media3 HLS 模块，使 `.m3u8` 电台可播。
   - ICY 兼容性：明确遇到 `ICY 200 OK` 的处理路径（例如改用 OkHttp DataSource、或做更明确的错误分类与提示）。
1) **Playlist/Redirect 解析**（播放前，核心）：
   - 目标：把“指向 playlist/跳转页”的 URL 解析成“真实可播流 URL”，显著降低 `Source error` 比例。
   - 支持类型（优先级由高到低）：
      - `.pls`（INI 格式）
      - `.m3u` / `.m3u8`（只做“候选 URL 抽取”，不做完整 HLS playlist 改写；Media3/ExoPlayer 在引入 HLS 模块后支持 HLS）
      - `.asx` / `.xspf`（如果实现成本可控；否则先写入 v38）
      - `Content-Type` 指示 playlist（即便 URL 无后缀）
      - HTML/Text 兜底：v37 禁用（误判风险高，留给 v38+ 专项）
   - 输入：`streamUrl`（来自 `.radio` 文件的 `streamUrl`）
   - 输出：`ResolvedStream`（`finalUrl` + `candidates[]` + `resolutionTrace` + `classification`）
   - 解析策略（必须约束，防止滥扫/滥抓）：
     - 只允许 `http`/`https`
     - 最多跟随 5 次重定向（含跨协议/跨 host），超出直接失败可解释
     - 单次下载上限：256KB（只够解析 playlist，不允许拉全文件）
     - 单次解析超时：4s（可配置）
     - 统一 UA：复用播放器 UA（避免“目录可访问但播放器被 403”）
   - 解析规则（简化版，可直接变成实现）：
     - 先做一次 `GET`（Range 不可靠时也可全量但限制字节数），拿到：`status_code`、`final_url`、`content_type`、`body_preview`
     - 若 `status_code` 非 2xx：失败（归类 HTTP_4XX/5XX）
     - 若 `content_type` 或 `final_url` 后缀明显是 playlist：
        - `.pls`：提取 `FileN=`，只保留前 10 条有效 URL
        - `.m3u/.m3u8`：
          - 判定为 HLS：body 含 `#EXTM3U` 且存在 `#EXT-X-` 标签（例如 `#EXT-X-STREAM-INF`），则视为 HLS playlist，直接把原始 URL（或 `final_url`）交给 ExoPlayer（不做候选抽取）
          - 否则：按 Shoutcast 风格的简单 URL 列表处理，忽略注释行（`#` 开头），提取每行 URL；只保留前 10 条有效 URL
     - 否则（看起来是直接流）：直接返回 `final_url`
     - 候选过滤（必须）：
       - 去重
       - 丢弃非 http(s) / 明显非法 URL
       - 允许 `.m3u8`、`.aac`、`.mp3`、`.ogg` 等常见流后缀（不做强约束，只做 sanity）
   - 播放时的尝试策略：
     - 依次尝试 `candidates[]`（或只尝试解析出来的 `finalUrl`）
     - 每个候选最多等待 `--await_ms` 窗口（见下一条），失败立即换下一个
2) **`radio play` 可选 verify/await**：
   - `radio play ... --await_ms 4000`：等待进入 playing 或 error；超时则返回“still_buffering/unknown”但不得误报 idle
3) **`radio status` 增强**：
   - `result.transport`: `{ playback_state, play_when_ready, is_playing, media_id }`
   - `result.state` 的推导规则明确：缓冲/准备中 ≈ playing-in-progress
4) **坏台跳过策略固化（低问询）**：
   - Skill：保留“最多跳 3 个”上限（防止死循环）
   - CLI：提供稳定错误码/字段，让 Skill 不需要靠字符串 contains

不做（v37）：

- 后台批量健康检查/排名系统（留给 v38+）
- 自动改写/覆盖原 `.radio` 文件（只在必要时写入临时 resolved 文件，且路径透明）
- 对所有流格式的全量兼容（优先覆盖最常见 playlist 与 redirect）

## Acceptance（硬口径）

0) **系统性缺口修复（必须先验）**：
   - 至少能解释并验证：对 `http://` 电台，若未允许 cleartext，必须在 `error_message`/分类字段中明确指出原因；若允许 cleartext，则这类电台不应再“批量失败”。
   - 至少能解释并验证：对 `.m3u8` 电台，若缺少 HLS 模块，必须在 `error_message`/分类字段中明确指出原因；引入 HLS 后应能显著提升可播率（以手测样例验证即可）。
1) **状态正确性**：
   - 用户执行 `radio play ...` 后，在 0~4 秒内调用 `radio status`，不得长期误报 `idle`（除非确实未设置任何 media item）。
   - 缓冲中应表现为 `state=playing`（或新增 `state=buffering` 也可，但必须稳定且 Skill 有口径）。
2) **错误可解释**：
   - `radio status` 的 `error_message` 必须包含可诊断信息（至少含 `ERROR_CODE_*` 或 HTTP/解析/SSL 等关键词之一），而不是只有 `Source error`。
3) **坏台可跳过**：
   - Skill 在候选列表 >= 2 时，遇到 `state=error` 能自动换台，最多连续跳过 3 个后停止并提示用户换关键词/换国家。
4) **Playlist 解析可用**：
   - 对至少 1 份 `.pls` 与 1 份 `.m3u` 样例（测试 fixture）解析出 >=1 个候选 URL，并能把第一个候选交给 transport.play（单测可验证调用参数）。
5) **验证命令**：
   - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
   - 真机手测（至少 1 台）：选择一个已知“playlist 链接/重定向链接”的电台，能成功解析并播放（记录站点与现象即可，不要求每次都成功）。

## Tests（建议）

- 单元测试：
  - playlist 解析器：输入 m3u/pls 样例文本 → 输出候选 URL 列表（含边界：空/注释/相对 URL/非法 URL）
  - `radio play --await_ms`：使用 fake transport 模拟 buffering→ready→playing 的状态迁移，断言状态推导稳定
- 集成/Robolectric：
  - `radio status` 返回的 `result.transport.*` 字段存在且稳定
  - （可选）当播放 `http://` URL 时，若策略禁止 cleartext，应能拿到可诊断的错误分类（避免“只有 Source error”）
  - （可选）当播放 `.m3u8` 且未引入 HLS 模块时，应能拿到可诊断的错误分类（避免“只有 Source error”）

## Steps（Strict / TDD）

0) Scope 0 先落地（独立 / 低风险 / 高收益）：
   - `network_security_config.xml`：允许 cleartext（见 Decisions），并在文档里说明安全权衡。
   - 引入 Media3 HLS 模块：使 `.m3u8` 台可播。
   - ICY 兼容性确认：采集至少 1 个失败样本，判断是否命中 `ICY 200 OK` 类问题与后续策略（见 Decisions）。
1) 真机快速冒烟（先验收益）：
   - 用 2~3 个此前“必挂”的 `http://` 台与 2~3 个 `.m3u8` 台，确认系统性缺口已堵。
   - 记录：是否仍 `Source error`、root cause（至少含 errorCodeName 或关键字）。
2) Analysis：确定 `state` 口径（本轮不新增枚举；继续“buffering 视为 playing-in-progress”，见 Decisions）。
3) TDD Red：为 playlist 解析器写测试（m3u/pls/redirect/garbage）。
4) TDD Green：实现解析器 + 最小网络抓取（限制大小、超时、重定向策略、User-Agent 复用）。
5) TDD Red：为 `radio play --await_ms` 写测试（buffering/ready/error/timeout）。
6) TDD Green：实现 `radio play` 等待/验证逻辑（不阻塞 UI；在 terminal_exec 中可同步等待）。
7) 更新 `radio-cli` 技能：把“verify + skip”与新的结构化字段对齐（减少字符串 contains）。
8) Verify：`.\gradlew.bat :app:testDebugUnitTest`。
9) 真机冒烟：至少验证 1 次“播放后立即 status”不误报 idle；记录现象。

## Risks

- 中国大陆网络环境：DNS/代理/证书链问题会让某些电台持续失败；策略只能“更可解释 + 更快换台”，无法完全消除。
- playlist 解析误判：过于激进会导致把网页链接误当流；解析需保守（只接受 http(s) URL、限制域名/后缀/Content-Type）。
- 等待窗口：`--await_ms` 过长会拖慢 agent 体验；过短会误判“仍在缓冲”。建议默认 2~4 秒，可配置。
 - 安全权衡：允许 cleartext 会降低传输安全性（易被中间人劫持/篡改）。需要在文档里明确用途边界（“只用于公开音频流”）与可选策略（debug-only / 仅 radio 功能域 / 全局）。

## Decisions（已拍板）

1) `state` 不新增 `buffering` 枚举：继续把“准备中/缓冲中”映射为 `playing`（playing-in-progress 口径）。
2) 不写入临时 resolved `.radio` 文件：playlist 解析结果以“内存候选 URL 列表”形式直接交给 transport.play。
3) “坏台跳过”上限固定为 3：避免过度智能化导致候选数量少时过早放弃。
4) Cleartext 策略使用 `network_security_config.xml`（不使用全局 `android:usesCleartextTraffic="true"`）：
   - 允许 cleartext 的范围以 “可审计、可未来收紧” 为原则；实现上可先对所有域名放开（radio 生态域名难枚举），但必须通过 config 文件承载。
5) 直接引入 Media3 HLS 模块：提升 `.m3u8` 可播率，包体增量可接受。
6) ICY 兼容性：先采样确认是否高频命中；若是，再评估是否引入 OkHttp DataSource 或做特定 fallback。
7) HTML/Text 兜底：v37 直接禁用；如后续统计确实存在一批“指向 HTML 页”的电台，再作为 v38+ 专项评估（例如标记为 web_only 跳过，或引入 headless 抓取）。

## Open Questions（保留）

（暂无）
