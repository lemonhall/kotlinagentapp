# 华为 Nova 9（EMUI/鸿蒙）后台/锁屏音乐播放可行性与工程对策（Deep Research）

## Executive Summary

在 Android 生态里，要让音频在“切后台/锁屏”后仍稳定播放，最可控的工程路径是：基于 Media3（ExoPlayer）+ MediaSession( Service )，并在播放期间运行 **Foreground Service（类型 `mediaPlayback`）** 与媒体播放通知；同时在 Android 13+ 处理通知权限、在 Android 15+ 遵守音频焦点的新限制。[1][2][3][4]

华为（含 Nova 9）属于后台限制偏激进的 OEM 之一，即使采用前台服务，仍可能在某些电量/后台策略组合下被系统终止。风险无法“理论上归零”，但可以在文档阶段把它变成“可验证、可排障、可追溯”的工程任务：把实现栈与权限/声明写死；提供华为后台限制的用户侧设置指引；并将 Nova 9 的手动验收纳入 DoD（失败必须留痕/进入下一轮）。[5][6]

## Key Findings

- **前台服务 + MediaSession 是后台播放的基础设施**：Media3 的 `MediaSessionService` 设计目标就是让播放在 UI 生命周期之外继续进行，并通过通知提供控制入口。[1][2]
- **Android 15（面向 targetSdk=35）对音频焦点更严格**：应用若不在前台或未运行前台服务，请求音频焦点可能被拒绝；因此“播放时运行前台服务”不再只是体验优化，而是兼容性要求。[3]
- **Android 14+ 强制前台服务类型与更细粒度权限**：媒体播放属于 `mediaPlayback` 类型的前台服务，需正确声明前台服务类型与相关权限（平台级约束）。[4]
- **华为后台限制是主要不确定性来源**：华为官方支持文档会指向“允许应用后台运行/应用启动管理/电量优化”等设置；第三方的 DontKillMyApp 也把华为列为限制较强的阵营。[5][6]
- **通知权限的影响比“会不会活”更偏向“可控/可解释”**：Android 13+ 若未授予通知权限，系统可能不在通知抽屉展示前台服务通知；但媒体会话相关通知在部分场景可豁免此变化。工程上仍应把“通知权限拒绝/ROM 差异”当作需处理的现实：保持 App 内控播可用，并在 `musics/` 提供可解释提示与排障入口。[4][7]

## Detailed Analysis

### 1) Android 端“稳定后台播放”的最小正确形态

建议采用的最小形态（对 v29 文档的约束口径）：

1. **Player**：Media3 ExoPlayer（或可替换实现）
2. **Session**：Media3 `MediaSession` 绑定播放器，提供系统级控制入口（耳机按键/蓝牙/锁屏控件等，受设备支持差异影响）[2]
3. **Service**：Media3 `MediaSessionService`（或 `MediaLibraryService`），把播放生命周期托管给 Service，而不是 Fragment/Activity[1][2]
4. **Foreground**：播放期间由 Service 进入前台（media playback notification + `foregroundServiceType="mediaPlayback"`），避免后台被随意回收[1][4]

关键点：Media3 文档明确提到“如果媒体会在后台播放，就应该在 Service 里托管，并用通知提供控制”。这不是“最佳实践”，而是和 OEM 行为、系统限制共同作用下的必需品。[1][2]

### 2) targetSdk=35（Android 15）下的音频焦点与后台限制

Android 15 的行为变化之一是：当应用不在前台、且没有运行前台服务时，请求音频焦点可能被拒绝（以减少后台应用抢占音频）。这会直接表现为“锁屏/切后台后续播失败、或者下一首开始失败”。[3]

因此，v29 文档阶段就应把“播放时必须运行前台服务（mediaPlayback）”作为硬约束，并把音频焦点的申请时机与状态机写进 DoD/测试计划（哪怕单测只覆盖状态机，真机验收覆盖系统交互）。[3][4]

### 3) 前台服务声明、通知权限与可解释降级

Android 14 起，对前台服务类型（FGS type）有更严格的声明要求；媒体播放应使用 `mediaPlayback` 类型（并在 manifest 与 service 声明中对齐）。[4]

Android 13 起引入运行时通知权限 `POST_NOTIFICATIONS`：如果用户拒绝通知权限，系统可能不在通知抽屉展示前台服务通知；但媒体会话相关通知在部分场景可豁免此变化。[4][7]

即便如此，“不崩溃”只是底线；工程上应做到“可解释”，因为 ROM/OEM 仍可能带来差异。这会带来两个常见问题：

- 用户无法在锁屏/通知栏快速暂停/恢复
- 当 OEM/系统限制触发回收时，缺少“可见证据”与排障入口

因此，在 `musics/` 视图里提示用户授权通知（并允许用户拒绝但知晓后果），同时保证 mini play bar 可控播，并提供“后台播放排障”入口，是更稳妥的工程策略。[4][7]

### 4) 华为 Nova 9：后台/电量策略导致的终止风险与对策

华为设备常见的问题不是“技术上不能播”，而是“在某些电量/后台策略组合下会被系统杀”。官方支持渠道会引导用户检查后台运行、应用启动管理、耗电管理等设置。[5]

第三方汇总（DontKillMyApp）也指出华为/荣耀系对后台进程管理更激进，建议用户把应用加入允许自启动/后台运行白名单、并在多任务界面锁定应用等。[6]

工程侧能做的（可落入 v29 文档 DoD）：

- 播放期间维持 `mediaPlayback` 前台服务 + 媒体通知（降低被杀概率）[1][4]
- 提供“后台播放排障”入口：把华为的关键开关路径写成可执行 checklist（用户自助）[5]
- 把 Nova 9 作为 v29 的强制手动验收机型：锁屏/后台持续播放 + 通知控播可用；失败必须留痕并进入下一轮（必要时 ECN）

这套组合不能保证 100% 不被杀，但把不确定性变成“可复现、可引导、可追溯”。[5][6]

### 5) 文档阶段如何“消灭/收敛风险”（建议写入 PRD/Plan）

将风险从“可能不行”收敛为“有硬约束、有验收、有排障、有证据链”：

- **硬约束**：采用 Media3 + MediaSessionService + FGS `mediaPlayback`；播放时必须处于前台服务（或前台 app）状态；请求音频焦点遵守 Android 15 限制。[1][3][4]
- **权限/声明清单**：在 v29 计划里列出 manifest/service 的必需项（FGS type、通知权限、相关 permission），并把“未授权通知”的降级策略写清。[4][7]
- **用户侧排障入口**：把华为 Nova 9 常见后台限制设置整理成 checklist（静态文档即可），并放进 `musics/` 视图里，让用户能自助配置。[5][6]
- **设备验收**：将 Nova 9 的锁屏/后台持续播放验收写进 DoD，失败必须留痕（并进入 v30 或 ECN）。

## Areas of Consensus

- 背景/锁屏音频播放要稳定，需要把播放生命周期移出 UI，使用 Service + MediaSession，并在播放时使用前台服务与通知。[1][2][4]
- OEM（尤其华为系）的后台管理可能导致前台服务也被更积极地限制；最现实的工程策略是提供明确的用户侧设置引导，并用真机验收来固化“在目标设备上可用”。[5][6]

## Areas of Debate

- **通知权限拒绝时的系统行为差异**：不同系统版本/ROM 对“前台服务通知能否展示、以及对存活的影响”可能存在差异；工程上应以“可解释提示 + 真机验收”兜底。[7]
- **OEM 终止策略是否可完全规避**：DontKillMyApp 的经验表明很难通过纯代码保证 100% 不被杀；可行的是降低概率 + 提供排障与证据链。[6]

## Sources

[1] Android Developers — Media playback in the background（Background playback with MediaSessionService）。（官方开发者文档，高可信）

[2] AndroidX Media3 API Reference — `MediaSessionService`。（官方 API 文档，高可信）

[3] Android Developers — Audio focus changes（Android 15，音频焦点限制）。 （官方开发者文档，高可信）

[4] Android Developers — Foreground services overview（FGS 类型/权限/限制）。 （官方开发者文档，高可信）

[5] 华为消费者业务支持 — “后台应用管理”（允许后台运行/启动管理等）。 （厂商官方支持文档，中高可信）

[6] dontkillmyapp.com — Huawei（后台限制经验汇总）。 （社区汇总，中可信；可用于风险评估与排障线索）

[7] Android Developers — Notification permission（Android 13+ `POST_NOTIFICATIONS`）。 （官方开发者文档，高可信）

### Source Links（for reproducibility）

> 为避免在仓库正文里散落链接，这里把 URL 统一放进代码块。

```text
[1] https://developer.android.com/media/media3/session/background-playback
[2] https://developer.android.com/reference/androidx/media3/session/MediaSessionService
[3] https://developer.android.com/about/versions/15/behavior-changes-15#audio-focus
[4] https://developer.android.com/develop/background-work/services/fgs
[5] https://consumer.huawei.com/en/support/content/en-us15877019/
[6] https://dontkillmyapp.com/huawei
[7] https://developer.android.com/develop/ui/views/notifications/notification-permission
```

## Gaps and Further Research

- **Nova 9 的具体系统版本差异**（EMUI vs 鸿蒙、版本号差异）会影响后台策略细节；需要在你的真机上记录系统版本与验收结果，形成可追溯证据。
- 若 Nova 9 在特定场景仍被杀，需要进一步收集：是否在省电模式、是否有“应用启动管理”限制、通知权限是否关闭、以及服务是否在播放期间确实处于前台。
