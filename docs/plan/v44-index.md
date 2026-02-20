# v44 Index：Recorder（通用录音机）

## Vision

- 让用户在 App 内（Files 页签）用「设备麦克风」录音，并在录制完成后复用既有 v40/v41/v42 pipeline：转录、翻译、双语播放。

## PRD

- `docs/prd/PRD-0035-Recorder.md`

## Plans

- `docs/plan/v44-recorder.md`

## Traceability（Req → Plan → Verification）

| Req ID | Plan | Verification（命令/证据） |
|---|---|---|
| REQ-0035-010 入口与导航 | v44-recorder | `.\gradlew.bat :app:testDebugUnitTest`；手工：Files 的 `recordings/` 🎙 图标 + 长按菜单 |
| REQ-0035-020 录音 | v44-recorder | `.\gradlew.bat :app:testDebugUnitTest`；手工：录音页可开始/暂停/继续/停止，生成 `workspace/recordings/rec_*/chunk_001.ogg` + `_meta.json` |
| REQ-0035-030 文件管理 | v44-recorder | `.\gradlew.bat :app:testDebugUnitTest`；手工：长按 session 目录弹菜单（播放/转录/翻译/双语播放/重命名/删除） |
| REQ-0035-040 录音设置 | v44-recorder | `.\gradlew.bat :app:testDebugUnitTest`；手工：停止后保存页可改名 + 勾选自动转录翻译并触发 |
| REQ-0035-050 后台录制 | v44-recorder | 手工：录制中切后台不中断（FGS 通知可返回录音页） |

## Milestones

- M1（P0/P1）：录音入口 + 录音 UI + session 落盘 + 复用转录/翻译/双语播放 + 保存页（改名/自动 pipeline）
- M2（P2）：Foreground Service 后台录制 + 通知栏跳回 + WakeLock

## Open Issues / Diffs

- （本轮交付后回填）若与 PRD 口径有差异，在此列出并进入 v45。

