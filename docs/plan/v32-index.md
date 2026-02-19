# v32 Index：Files 电台播放器（radios/ VFS + 懒加载目录 + 直播流 + Favorites）

日期：2026-02-19

## Vision（引用）

- PRD：`docs/prd/PRD-0030-radio-player.md`
- 本轮目标（v32）：在 Files 根目录新增 `radios/`（与 `musics/` 同级），把全球电台映射为“国家/地区目录 → `.radio` 文件”，支持懒加载同步与可解释刷新；点击 `.radio` 复用现有播放器运行时与 UI 播放直播流，并保证切页签/后台/锁屏不断播；提供 `favorites/` 作为纯文件系统收藏入口。

## Milestones

### M1：`radios/` 根目录 + 国家/地区目录树 + 懒加载缓存

- PRD Trace：
  - PRD-0030：REQ-0030-000 / REQ-0030-001 / REQ-0030-010 / REQ-0030-011 / REQ-0030-012 / REQ-0030-013 / REQ-0030-014
  - PRD-0030：REQ-0030-020
- DoD（硬口径）：
  - Files 根目录自动创建 `radios/` 与 `radios/favorites/`（严格小写）；
  - 进入 `radios/` 可看到国家/地区目录列表（允许弱网/失败，但必须可解释）；
  - 进入某国家目录能触发懒加载拉取 station 列表并缓存；再次进入可复用缓存（带 TTL 或手动刷新策略）；
  - station 默认按“热度/投票（votes）”排序（如数据源可用），并提供手动刷新；
  - 提供明确“刷新”入口（下拉刷新/菜单刷新/刷新文件之一），失败不崩溃；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：目录数据解析 + 缓存策略）。

### M2：`.radio` station 文件 + 直播播放 + UI 降级 + 错误可解释

- PRD Trace：
  - PRD-0030：REQ-0030-030 / REQ-0030-031 / REQ-0030-032
  - PRD-0030：REQ-0030-040 / REQ-0030-041
  - PRD-0030：REQ-0030-021 / REQ-0030-022
- DoD（硬口径）：
  - 国家目录内 station 以 `.radio` 文件呈现（JSON schema v1），可被点击播放；
  - 直播流播放复用现有前台服务 + MediaSession 路径，切页签/后台/锁屏不断播；
  - 直播流不支持 seek/duration 时 UI 必须降级（隐藏或置灰 seek，并展示“LIVE”或等价可解释状态）；
  - 支持 next/prev（同目录下一台/上一台）；队列 size=1 时合理降级（不崩溃、可解释）；
  - 收藏：任意 station 可写入到 `radios/favorites/`，并可从 favorites 播放；
  - 播放失败必须可解释（错误码/提示），且不影响继续浏览与切台；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：`.radio` 解析 + 播放状态机分支）。

## Plan Index

- `docs/plan/v32-radio-player.md`

## ECN Index

- （本轮无）

## Review（Evidence）

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest`
  - 2026-02-19：PASS（exit code=0）
- Lint：`.\gradlew.bat :app:lintDebug`
  - 2026-02-19：FAIL（仓库既有 lint errors；首个错误：`GitCommand.kt` 使用了 API33 的 `ByteArrayOutputStream.toString(StandardCharsets.UTF_8)`）
- Device smoke：待真机/模拟器执行（播放电台→锁屏/后台 5 分钟不断播→通知栏控播）
