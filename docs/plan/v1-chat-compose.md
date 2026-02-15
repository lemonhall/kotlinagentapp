# v1 Plan：Chat Tab（Compose in Fragment）最短闭环

## Goal

交付一个可运行、可测试的 Chat 页面骨架：Compose UI + ViewModel 状态管理 + mock/echo 回复 + tool 轨迹占位，为后续接 SDK 打基础。

## PRD Trace

- REQ-0001-001（消息发送与展示）
- REQ-0001-002（工具调用轨迹占位）
- REQ-0001-003（基础错误与重试入口，MVP 先占位）

## Scope

做：
- 将 BottomNav 的 Home 页替换为 Chat（Compose UI）
- 用 `ChatViewModel` 管理消息列表与输入发送
- assistant 回复先使用同步/异步 echo（不接真实 SDK）
- tool 轨迹先用 mock 数据结构与 UI 占位

不做：
- 不接 `openagentic-sdk-kotlin`
- 不做消息持久化（Room/本地文件）与会话列表
- 不做 WebView

## Acceptance（硬口径）

1. `sendUserMessage("hi")` 后，`ChatViewModel` 的消息列表最终包含 2 条：user + assistant（echo）。
2. UI（Compose）包含：消息列表区域、输入框、发送按钮；发送空文本不会追加消息。
3. 存在 Robolectric 测试：启动 `MainActivity` 后选择 Chat Tab，`ChatFragment` 视图创建成功。

## Files（预计改动）

- `app/build.gradle.kts`（启用 Compose、添加依赖、测试选项）
- `gradle/libs.versions.toml`（新增 Compose / test 依赖版本）
- `app/src/main/java/.../ui/chat/*`（新增 Chat 相关）
- `app/src/main/res/navigation/mobile_navigation.xml`（Home → ChatFragment）
- `app/src/main/res/menu/bottom_nav_menu.xml`（标题更新）
- `app/src/main/res/values/strings.xml`（tab 标题更新）
- `app/src/test/java/...`（ChatViewModel 单测 + Robolectric E2E 替代测）

## Steps（Strict）

### 1) TDD Red：写失败测试并跑到红

- 单测：`ChatViewModelTest`（消息追加 + echo 回复）
- Robolectric：`MainActivityChatNavigationTest`（Activity 启动 → ChatFragment 视图存在）
- 命令：`.\gradlew.bat :app:testDebugUnitTest`（预期红）

### 2) TDD Green：实现最小代码跑到绿

- 实现 `ChatViewModel`（StateFlow）
- 实现 `ChatFragment`（ComposeView + Material）
- 更新导航图与底部菜单标题
- 命令：`.\gradlew.bat :app:testDebugUnitTest`（预期绿）

### 3) Refactor：保持绿的前提下整理结构

- 提炼 UI 组件：`ChatScreen` / `MessageBubble`
- 适度收敛依赖与命名（不做大改动）

### 4) Build Gate

- 命令：`.\gradlew.bat :app:assembleDebug`（预期通过）

## Risks

- Compose 版本与 Kotlin/AGP 兼容性：如遇冲突，优先调整 Compose BOM / compiler extension，保证构建稳定。

