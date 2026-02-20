# v45 Index：NAS SMB 媒体串流播放（MP3 内置 + MP4 系统默认，seekable）

日期：2026-02-20

## Vision（引用）

- PRD：`docs/prd/PRD-0038-smb-media-streaming.md`
- 方法论引用：`tashan-development-loop`（PRD ↔ plan ↔ tests ↔ code 可追溯）、`test-driven-development`（严格 TDD）
- 本轮目标（v45）：在 `nas_smb/**` 子树内，实现 `.mp3/.mp4` 的“按需随机读 + 缓冲 + 可 seek”播放：  
  - MP3：复用 PRD-0029 的内置播放器，但数据源改为 `content://`（不落盘全量下载）  
  - MP4：通过 `Intent(ACTION_VIEW)` 调系统默认播放器，且必须可 seek（API >= 26 主路径）

## Plans

- `docs/plan/v45-smb-media-streaming.md`

## Traceability（Req → Plan → Verification）

| Req ID | Plan | Verification（命令/证据） |
|---|---|---|
| REQ-0038-001/002/003/004 入口与只读 | v45-smb-media-streaming | `.\gradlew.bat :app:testDebugUnitTest`；手工：Files 点击 `nas_smb/**.mp3/.mp4` 行为正确 |
| REQ-0038-010/011/011A seekable 串流 | v45-smb-media-streaming | 手工：Nova 9 系统播放器可播放且可 seek；（可选）instrumentation：FD 随机读正确 |
| REQ-0038-012 缓冲默认值 | v45-smb-media-streaming | `.\gradlew.bat :app:testDebugUnitTest`（cache LRU + 末页边界） |
| REQ-0038-013 错误码口径 | v45-smb-media-streaming | `.\gradlew.bat :app:testDebugUnitTest`（映射与兜底码） |
| REQ-0038-014/014A 前台服务保活 | v45-smb-media-streaming | 手工：播放 MP4 时前台通知存在；结束后 60s 无会话自动停止 |
| REQ-0038-020/021/022/023 安全与兼容 | v45-smb-media-streaming | `.\gradlew.bat :app:testDebugUnitTest`（URI 不含 secrets + token 滑动过期（FakeClock）+ UID 绑定） |
| REQ-0038-030/031 版本兼容 | v45-smb-media-streaming | 手工：API 26+ 走 Proxy FD；API 24/25 给出明确降级提示 |

## Milestones

- M1（底座，阻塞后续）：`SmbMediaTicketStore` + `SmbPageCache` + `SmbRandomAccessReader`（接口/实现）+ ContentProvider（API>=26 Proxy FD）最小闭环（只读 + token/uid + onGetSize）
- M2（UI 接入，依赖 M1）：Files 点击行为接入（`nas_smb/**` 下 `.mp3/.mp4`）+ 内置播放器 `content://` 播放支持
- M3（保活/错误，依赖 M1；可与 M2 并行）：外部视频串流前台服务保活（REQ-0038-014/014A）+ 断线/错误口径与提示
- M4（真机验收，依赖 M2 + M3）：Nova 9 手工验收记录（可播放/可 seek/并发 MP3+MP4/通知生命周期）

## Review（Evidence）

- （执行 v45 后回填）Unit tests：`.\gradlew.bat :app:testDebugUnitTest` ✅（exit code=0）
- （执行 v45 后回填）Nova 9：系统播放器 seek ✅；并发播放 ✅；前台通知 ✅
