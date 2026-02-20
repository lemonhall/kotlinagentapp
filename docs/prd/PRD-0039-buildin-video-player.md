# PRD-0039：内置视频播放器（SMB MP4）

## 背景

当前 Files 页签点击 `.agents/nas_smb` 下的 mp4，会通过 `ACTION_VIEW` 把 `content://<authority>.smbmedia/...` 交给外部播放器。部分 OEM 播放器（如华为）在读取元数据阶段存在苛刻超时/兼容性问题，导致用户无法播放。

## 目标

- 在 App 内提供一个内置视频播放器，作为 SMB mp4 的默认打开方式，绕过外部播放器兼容性问题。
- 保留“其他应用打开”的兜底入口（可重新选择外部播放器）。
- 保持 SMB 串流的权限模型（Ticket/UID 绑定）不被破坏。

## 非目标（首版不做）

- DRM、投屏、画中画、字幕、播放列表、记忆进度。

## 交互与入口

- Files 页签：
  - `.agents/nas_smb/**.mp4`：打开内置播放器。
  - `.agents/**.mp4`（本地 workspace 内视频）：同样打开内置播放器（避免 `openFile()` 走文本编辑器逻辑）。
- 播放页：
  - 顶部：返回；右侧“其他应用”按钮。
  - 内容：Media3 `PlayerView` 控制条（播放/暂停/进度/全屏等默认能力）。
  - 异常：显示错误文本 + “重试”按钮。

## 技术方案

- 新增 `VideoPlayerActivity`（Compose + `PlayerView` via `AndroidView`），使用 Media3 `ExoPlayer` 播放单个 `Uri`。
- 新增 `VideoPlayerViewModel` 持有 `ExoPlayer`，在 `onCleared()` 中释放，`onStop()` 暂停。
- SMB mp4 点击时：
  - 由 `SmbMediaActions.createNasSmbMp4Content()` 签发 ticket 并生成内部 `content://...smbmedia/...` URI。
  - 启动 `VideoPlayerActivity` 播放该 URI。
- “其他应用”：
  - 若来源是 SMB mp4：重新签发 ticket 并调用 `SmbMediaActions.openNasSmbMp4External()`（避免 ticket 被内置播放器先绑定到本 App UID，导致外部 App uid mismatch）。
  - 若来源是本地 `.agents/**.mp4`：用 `FileProvider` 生成 `content://...fileprovider/...` 后通过 chooser 打开。

## 验收标准

- 同一 SMB mp4：
  - 点击后进入内置播放器，正常播放或明确提示错误可重试。
  - 返回后播放器资源释放/暂停，不产生前台服务泄漏。
- “其他应用”可用：每次都弹 chooser，允许重选播放器。
- 单测 `:app:testDebugUnitTest` 通过；Debug 能正常安装到真机。

