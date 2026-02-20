#!/usr/bin/env markdown
# v45 Plan：NAS SMB 媒体串流播放（MP3 内置 + MP4 系统默认，seekable）

日期：2026-02-20  
PRD：`docs/prd/PRD-0038-smb-media-streaming.md`

## Goal

交付 `nas_smb/**` 子树下 `.mp3/.mp4` 的“按需随机读 + 缓冲 + 可 seek”播放闭环：

- `.mp3`：内置播放器直接播放 `content://`（不落盘全量下载）
- `.mp4`：系统默认播放器打开 `content://`，并且可 seek（API >= 26 主路径）

## PRD Trace

- 入口与交互：REQ-0038-001 / 002 / 003 / 004
- seekable 串流：REQ-0038-010 / 011 / 011A / 012 / 013
- 保活：REQ-0038-014 / 014A
- 安全：REQ-0038-020 / 021 / 022 / 023
- 兼容：REQ-0038-030 / 031

## Scope

### In（v45）

- 新增 `SmbMediaContentProvider`（只读）+ token/uid 校验 + Proxy FD（API>=26）+ `onGetSize` 精确实现  
- 新增可复用底座：`SmbMediaTicketStore`（滑动过期）+ `SmbPageCache`（LRU + 末页不足 pageSize 正确）  
- 外部视频串流保活：前台服务（60s 延迟停服）  
- Files 点击行为接入：`nas_smb/**` 下 `.mp3/.mp4`  
- 内置播放器支持 `content://` 播放（复用 PRD-0029 的播放与前台服务口径）

### Out（明确不做）

- 非 `.mp4` 的视频格式（`.mkv/.avi/.mov`）  
- 写入能力（Provider 仅只读）  
- 离线全量缓存/镜像同步  
- SAF DocumentProvider / 系统级“让其它 App 像文件管理器一样浏览 SMB”

## Acceptance（硬口径）

1) 在 `nas_smb/**` 下点击 `.mp3`：内置播放器开始播放，且可 seek。  
2) 在 `nas_smb/**` 下点击 `.mp4`：系统默认播放器可播放且可 seek（API>=26）。  
3) 安全：`content://` URI 不包含 SMB host/share/remotePath 明文；token 滑动过期；首次访问绑定 callingUid。  
4) 只读：Provider 拒绝任何写入模式。  
5) 保活：播放 MP4 时前台通知存在；最后一个会话 `onRelease` 后延迟 60s 无新会话自动停止。  
6) 并发：同时播放一首 MP3（内置）和一个 MP4（外部），两者均可正常播放与 seek。  
7) Verify（项目根目录执行）：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。

## Files（预计改动清单）

> 实现过程中如发现需要增删文件，必须同步更新本计划。

- 新增：`app/src/main/java/com/lsl/kotlin_agent_app/smb_media/*`（ticket/cache/provider/uri/mime/error）
- 修改：`app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`（点击 `nas_smb/**` 下 mp3/mp4）
- 修改：`app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlayerController.kt`（支持 `content://` 播放入口）
- 修改：`app/src/main/AndroidManifest.xml`（注册 ContentProvider + 前台服务）
- 新增：`app/src/test/java/com/lsl/kotlin_agent_app/smb_media/*`（ticket/cache/安全口径单测）
- （可选）新增：`app/src/androidTest/java/**`（Proxy FD/FD 随机读集成测试）

## Steps（Strict：TDD）

1) **RED：`SmbPageCache` 行为测试**
   - LRU 逐出、命中、read-ahead 触发（最小口径）
   - 末页不足 `pageSize` 的读取不越界、不补零
   - 运行：`.\gradlew.bat :app:testDebugUnitTest`（预期失败：缺实现）

2) **GREEN：实现 `SmbPageCache`**
   - 最小实现通过测试；保持 API 简洁（YAGNI）

3) **RED：`SmbMediaTicketStore` 测试**
   - token 生成、首次 openFile 绑定 callingUid、后续不匹配拒绝
   - 滑动过期：最后一次访问后 30min 回收（使用可注入的时钟/时间源推进时间，不真等 30min）
   - URI 不含敏感字段（host/share/path 不应出现在字符串化结果）

4) **GREEN：实现 `SmbMediaTicketStore` + `SmbMediaUri`**

5) **RED：错误码映射与兜底测试**
   - 至少覆盖：`Timeout/HostUnreachable/AuthFailed/PermissionDenied/ShareNotFound/FileNotFound/ConnectionReset/BufferUnderrun/Unknown`

6) **GREEN：实现错误码模型（仅用于 UI/日志口径；不得泄露 secrets）**

7) **RED：`SmbRandomAccessReader` 行为测试**
   - 契约：`size()` 返回精确字节数；`readAt(offset,size)` 返回正确数据
   - 越界：`offset >= size` 返回空；`offset+size` 超界返回截断后的实际数据
   - 异常：连接/读失败映射为稳定错误（用于后续错误码口径）

8) **GREEN：实现 `SmbRandomAccessReader`**
   - 先以接口 + Fake 实现跑通单测；真实 SMBJ 连接/句柄管理可在后续用真机/（可选）instrumentation 验证补齐

9) **接入：`SmbMediaContentProvider`（API>=26 Proxy FD）**
   - `openFile()` 只读 + token/uid 校验
   - `ProxyFileDescriptorCallback.onGetSize()` 返回精确 size
   - `onRead()` 返回实际读取字节数（`int`）并走 `SmbRandomAccessReader` + `SmbPageCache`
   - API 24/25：明确降级（抛出可解释异常，由 UI 提示）

10) **保活：前台服务**
   - 开始外部 `.mp4` 串流时启动；最后一个会话 release 后延迟 60s 无新会话停止

11) **RED/GREEN：内置播放器支持 `content://` URI 输入**
   - 11a：`MusicPlayerController` 新增/调整入口支持传入 `content://`（不依赖本地文件路径）；并补最小单测覆盖“不会因 file-only 假设崩溃”

12) **UI：Files 点击行为接入**
   - `nas_smb/**` 下：
     - `.mp4`：生成 token → `Intent(ACTION_VIEW)` + grant read uri permission → 启动前台服务
     - `.mp3`：生成 token → 调用内置播放器播放 `content://`
   - 保持 `musics/` 的既有行为不受影响

13) **Verify**
   - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
   - （可选）`.\gradlew.bat :app:installDebug` 真机冒烟：Nova 9 上 `.mp4` seek + 并发播放 + 通知生命周期

## Risks & Mitigations

- 风险：Proxy FD 在 Robolectric 下不可测  
  - 缓解：单元测试覆盖 ticket/cache/安全/错误码；Proxy FD 行为用真机/（可选）instrumentation 兜底
- 风险：国产 ROM 对 unexported provider 的 URI grant 行为差异  
  - 缓解：按 PRD-0038 的 REQ-0038-023 fallback（exported=true + token+uid 校验）并在 Nova 9 验收
