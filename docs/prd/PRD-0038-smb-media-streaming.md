#!/usr/bin/env markdown
# PRD-0038：NAS SMB 媒体“串流式”播放（MP3 内置播放器 + MP4 系统默认播放器，支持 seek）

日期：2026-02-20  
定位：在既有 `nas_smb/` VFS 挂载（PRD-0033）基础上，为 SMB 目录下的 **大体积二进制媒体文件**提供“可缓冲、可随机访问、可 seek”的播放体验：  
- `.mp3`：在 App 内置音乐播放器中直接播放（复用 PRD-0029 的播放链路）  
- `.mp4`：通过 `Intent` 调用系统默认播放器播放，并且 **必须支持拖动进度条 seek**（柠檬叔已确认）

> 术语说明：本 PRD 的“串流”指 **不做全量下载再打开**，而是按需读取远端文件片段（Range/随机读）+ 本地缓冲；并在播放过程中可随意 seek。

## Vision

用户在 Files 页签浏览 `nas_smb/<mount_name>/...` 时：

1) 点开 `.mp3`：立刻开始播放（先缓冲后出声），mini play bar 正常工作；可随意拖动进度条 seek  
2) 点开 `.mp4`（5G/10G 电影）：系统默认播放器打开后可立即播放，并支持进度条 seek  
3) 网络抖动/断线时：有明确的错误口径与可恢复策略（重试/提示/回到列表），不会“假死”

## Background / 现状与问题

- PRD-0033 已定义：`nas_smb/` 是 App 内部 VFS 挂载（非系统级 mount），核心面向 LIST/READ/WRITE 等文件工具能力。
- 但媒体播放属于 **大文件二进制 + 高频随机读** 场景：  
  - `READ` 文本接口不适用（会失败或非常慢）  
  - 外部播放器的 seek 往往表现为对 FD 的 `lseek/read`（随机读）  
- Android 非 root 环境下，外部播放器通常需要通过 `content://` URI 读取数据；单纯 `smb://` URI 兼容性差，且多数系统播放器不支持。

## Goals

- G1：在 `nas_smb/**` 子树内对 `.mp3/.mp4` 提供“点即播”的体验
- G2：`.mp4` 外部播放器 **必须支持 seek**（核心目标）
- G3：统一一套“SMB 随机读 + 缓冲”底座，供 MP3（内置）与 MP4（外部）复用
- G4：不泄露 SMB secrets：不在 URI / 日志 / errorMessage 中暴露用户名、密码、host、share 等敏感信息

## Non-Goals（本期不做）

- 不做：转码/码率自适应/字幕下载/倍速/投屏/DLNA
- 不做：把 SMB 变成“系统级可见的文件提供者”（不做 SAF DocumentProvider；不做系统文件管理器可浏览的 SMB）
- 不做：离线全量缓存/镜像同步（不把整个电影同步到本地再播放）
- 不做：串流场景的 SMB 写入（本 PRD 的媒体 Provider 仅暴露只读能力）
- 不做：非 SMB 来源（WebDAV/FTP/NFS 等）

## 用户故事（User Stories）

- US-0038-01：我在 `nas_smb/` 的某个目录里点一首 `.mp3`，它像本地音乐一样播放，可拖进度。
- US-0038-02：我点一个 10G 的 `.mp4` 电影，系统播放器打开后几秒内开始播放，并可随意快进快退。
- US-0038-03：我在播放中断网/切 Wi-Fi，能看到明确提示，并可重试继续。

## 方案选择（核心技术路线）

### 关键约束：minSdk=24

要实现“外部播放器 + seek”，本质需要一个 **可随机访问的 `content://` 数据源**。

推荐分层策略：

- Android API >= 26：使用 `StorageManager.openProxyFileDescriptor()` + `ProxyFileDescriptorCallback`  
  - 能以“虚拟文件”的方式提供 **按 offset 随机读**，天然满足外部播放器 seek  
  - 是本 PRD 的主路径（Nova 9 等现代设备覆盖）
- Android API 24/25：不具备上述能力  
  - 本 PRD 允许降级：提示“系统版本过低，外部播放器 seek 串流不支持”，可选择“后台下载完成后再打开”或“复制到本地后播放”

### 组件概览

1) `SmbRandomAccessReader`
   - 输入：`mountName + remotePath`
   - 输出：`readAt(offset, length) -> ByteArray` + `length()`（文件大小）
   - 连接管理：同一 mount 的多个会话共享底层 `Connection/Session`；每个活跃串流会话持有独立的 SMB 文件句柄（支持随机读）；并具备超时与断线重连策略

2) `SmbPageCache`（缓冲）
   - 以 `pageSizeBytes` 为页（默认 **256KiB**），LRU 逐出
   - 支持 read-ahead（顺序播放时预读后续页）
   - 默认最大缓存：**64MiB**（可配置调整）
   - 存储：优先内存；可选落盘到 `cacheDir`（后续 ECN 决定）

3) `SmbMediaTicket`（一次性票据）
   - App 内生成短期 token（例如 UUID），映射到 `mountName + remotePath + mime + size`
   - Provider 在首次 `openFile()` 时记录 `Binder.getCallingUid()` 并绑定到该 token；后续对同一 token 的访问必须来自相同 UID
   - token 存储在 App 私有内存/短期持久化（避免进程被杀后立刻失效可选）
   - 任何 URI 中 **不得**包含 SMB host/share/remotePath 明文

4) `SmbMediaContentProvider`（桥接给系统播放器）
   - Authority：`com.lsl.kotlin_agent_app.smbmedia`
   - 仅提供 `openFile()`（读）能力，不提供目录枚举（不变相做 SAF）
   - `android:exported="false"` + `android:grantUriPermissions="true"`；仅通过 `FLAG_GRANT_READ_URI_PERMISSION` 临时授权给目标播放器
   - API >= 26：在 `openFile()` 内使用 `StorageManager.openProxyFileDescriptor()` 返回 seekable FD（Proxy FD）

## Data Flow（MP4 外部播放器）

1) 用户在 Files 页签点击 `nas_smb/**/movie.mp4`
2) App 生成 `SmbMediaTicket(token, mountName, remotePath, mime=video/mp4, size, expiresAt)`
3) App 构造 `content://.../v1/<token>/movie.mp4`
4) App `Intent(ACTION_VIEW)` 打开系统默认播放器，并 `FLAG_GRANT_READ_URI_PERMISSION`
5) 外部播放器拿到 `ParcelFileDescriptor` 后，对 FD 执行 `lseek/read`（随机读）
6) Provider 通过 `ProxyFileDescriptorCallback.onRead(offset, size, data)` 被回调，并返回“实际读取字节数”（`int`）
7) `onRead` 内调用 `SmbRandomAccessReader` + `SmbPageCache` 返回数据片段

## Data Flow（MP3 内置播放器）

Locked：内置音乐播放器同样通过 `content://.../v1/<token>/song.mp3` 播放（复用同一套随机读 + 缓冲底座，避免两套 I/O）。

- ExoPlayer 可从 `content://` 读取；seek 走同一套随机读能力  
- metadata 读取优先依赖 Media3 自身的解析能力；若不足，可补充使用 `MediaMetadataRetriever.setDataSource(context, uri)`

## Requirements（Req IDs）

### A. 入口与交互（Files）

- REQ-0038-001：在 `nas_smb/**` 子树内，点击 `.mp3` 使用内置播放器播放（不下载到 `musics/`）。
- REQ-0038-002：在 `nas_smb/**` 子树内，点击 `.mp4` 通过 `Intent(ACTION_VIEW)` 调起系统默认播放器，MIME 使用 `video/mp4`。
- REQ-0038-003：必须显式 `FLAG_GRANT_READ_URI_PERMISSION` 授权给目标播放器；且授权范围仅限本次打开的 URI。
- REQ-0038-004：媒体 Provider 必须只读：拒绝任何写入/`rw`/`wt` 打开模式。

### B. seekable 串流（核心）

- REQ-0038-010：`.mp4` 必须支持 seek：外部播放器拖动进度条应生效（不允许“只能从头播”）。
- REQ-0038-011：API >= 26：Provider 在 `openFile()` 中返回 seekable Proxy FD；外部播放器对 FD 的 `lseek/read` 必须能触发 `ProxyFileDescriptorCallback.onRead(offset, size, data)` 并得到正确数据。
- REQ-0038-011A：`ProxyFileDescriptorCallback.onGetSize()` 必须返回 SMB 远端文件的精确字节数（通过 `SmbRandomAccessReader.length()` 获取），否则视为不满足 seekable 要求。
- REQ-0038-012：必须实现可配置缓冲（至少：页大小、最大缓存字节数），默认：`pageSize=256KiB`、`maxCacheSize=64MiB`。
- REQ-0038-013：断线/超时必须返回稳定错误码，并在 UI 有可解释提示；至少包含：  
  - `Timeout` / `HostUnreachable` / `AuthFailed` / `PermissionDenied`  
  - `ShareNotFound` / `FileNotFound` / `ConnectionReset` / `BufferUnderrun` / `Unknown`
- REQ-0038-014：播放会话期间必须保活串流进程：开始外部视频串流时启动前台服务（通知文案例如“正在串流媒体”）。
- REQ-0038-014：播放会话期间必须保活串流进程：开始外部视频串流时启动前台服务（通知文案例如“正在串流媒体”）。MP3 内置播放的保活由 PRD-0029 的音乐播放前台服务负责，不额外启动本串流保活服务。
- REQ-0038-014A：前台服务停止判定：最后一个串流会话的 `ProxyFileDescriptorCallback.onRelease()` 被回调后，延迟 `60s` 若无新会话启动则停止（避免播放器短暂关闭/重开 FD 时抖动）。

### C. 安全（Secrets 与 URI）

- REQ-0038-020：任何日志/异常/结果不得包含 SMB 密码；用户名与 host 至少打码。
- REQ-0038-021：`content://` URI 中不得出现 SMB host/share/remotePath 明文；必须通过 token 映射。
- REQ-0038-022：token 必须有过期时间与回收机制，语义为“最后一次读取后”的滑动过期：  
  - 串流读请求持续发生时 token 不过期；  
  - 结束播放/关闭播放器后，若连续 `30min` 无任何读取则回收；  
  - 回收后再次打开必须重新签发 token。
- REQ-0038-023：如出现 ROM 对 `exported=false` + URI grant 的兼容问题，允许将 Provider 调整为 `exported=true`；但必须同时启用 `token + grantedUid` 双重校验（仅允许被调起的目标播放器 UID 读取）。

### D. 版本兼容

- REQ-0038-030：API >= 26 走 seekable 串流主路径（Proxy FD）。
- REQ-0038-031：API 24/25 必须有明确降级策略与提示文案（不 silent fail）。

## Acceptance（硬口径）

1) 在 **华为 Nova 9**（Android 11/12 级别）上：
   - 从 `nas_smb/` 点击任意 `.mp4`，系统默认播放器可播放且可 seek；
   - 播放过程中多次 seek（快进/快退）均可稳定生效；
   - `android:exported="false"` + URI grant 组合可用；若 ROM 兼容性不通过，则切到 `exported="true"` + `token + grantedUid` 校验后重新验收通过；
   - 串流开始时有前台通知（REQ-0038-014），串流结束后自动消失；
2) 从 `nas_smb/` 点击任意 `.mp3`，内置播放器可播放且可 seek；mini play bar 状态正确；
3) 断网/断开 NAS 后：
   - 播放失败有可解释提示；
   - 返回 Files 列表不崩溃；
4) Verify（最少，在项目根目录执行）：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：token 过期/权限门禁/缓存命中/错误码映射）。
5) 并发场景：同时播放一首 MP3（内置）和一个 MP4（外部），两者均可正常播放与 seek，互不干扰。

## Testing Strategy（建议）

- Unit（Robolectric/JVM）：
  - `SmbMediaTicketStore`（token 生成、过期、回收）
  - `SmbPageCache`（LRU、read-ahead、并发读）
  - 边界：seek 到文件末尾附近时，“最后一页不足 pageSize”能正确处理（不越界、不补零）
  - `ErrorCode` 映射（SMBJ 异常 -> 稳定错误码）
- Instrumentation（必要时补充）：
  - 通过 `ContentResolver.openFileDescriptor()` 获取 FD，并验证随机读（seek）可返回正确长度/数据（API >= 26）

## Risks

- 外部播放器差异：不同 ROM/播放器对 `content://` 的读取模式不同（read size、并发、seek 频率）；缓存与连接管理必须稳健。
- 进程生存期：外部播放器播放大文件期间，App 进程被系统回收的概率高（尤其部分国产 ROM）；本 PRD 已把“前台服务保活”提升为 P0 要求（REQ-0038-014）。
- SMB 性能：Wi-Fi 抖动/弱信号下随机读会放大延迟；需要 read-ahead + 合理 pageSize 组合。
- API 24/25 降级：seekable 外部串流能力无法完全覆盖，需明确产品口径。

## Locked Decisions（已确认）

1) 本期视频格式：只做 `.mp4`；实现上使用“后缀 -> MIME”映射表，便于后续扩展 `.mkv/.avi/.mov`。  
2) 缓冲默认值：`pageSize=256KiB`、`maxCacheSize=64MiB`（可配置调整）。  
3) token 过期语义：滑动过期（最后一次读取后 30min 回收）。  
