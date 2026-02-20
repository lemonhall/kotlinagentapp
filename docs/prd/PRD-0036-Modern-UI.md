## Android App UI 现代化改造指令书

### 项目背景

这是一个 Kotlin Android 项目（`kotlin-agent-app`），功能是 AI Agent 客户端，包含聊天、文件管理、Web 浏览、终端、录音等功能。当前 UI 使用的是 Android Studio 默认模板生成的 Material 2 主题和配色（紫色+青色），视觉效果非常"模板化"。目标是将 UI 升级到 Material 3，提升整体视觉质感。

### 当前技术栈

- Kotlin 1.9.0，AGP 8.6.0
- Compose BOM 2023.08.00，Compose Compiler 1.5.0
- 混合架构：主框架用 XML（ViewBinding + Navigation Fragment），部分页面用 Jetpack Compose
- 主题：`Theme.MaterialComponents.DayNight.DarkActionBar`（Material 2）
- 配色：Android Studio 默认的 purple_500 / teal_200
- minSdk = 24，targetSdk = 35，compileSdk = 35
- 无 Google Play Services（目标设备是华为手机，Nova 9，Android 11 / API 30）
- `settings.gradle.kts` 中通过 `includeBuild()` 引入了两个本地 composite build 子项目：
  - `external/openagentic-sdk-kotlin`
  - `external/agent-browser-kotlin`

### 改造任务

请严格按以下顺序执行，每完成一步确保项目能编译通过。

---

#### 任务 1：升级 Kotlin、AGP 和 Compose 依赖

##### 1.1 修改主项目 `gradle/libs.versions.toml`

```toml
[versions]
kotlin = "2.0.21"         # 从 1.9.0 升级
agp = "8.7.3"             # 配合 Kotlin 2.0 升级
composeBom = "2026.02.00" # 从 2023.08.00 升级
# 删除 composeCompiler 这一行，Kotlin 2.0+ 不再需要单独指定
```

在 `[plugins]` 部分新增：

```toml
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

##### 1.2 修改主项目 `app/build.gradle.kts`

在 `plugins` 块中添加：

```kotlin
alias(libs.plugins.compose.compiler)
```

删除整个 `composeOptions { ... }` 块。

##### 1.3 同步升级两个 composite build 子项目

主项目和两个子项目的 Kotlin 版本必须统一。Gradle composite build 中，如果主项目和 included build 使用不同的 Kotlin 版本，会导致编译期元数据不兼容或 IR 后端冲突。

对 `external/openagentic-sdk-kotlin` 和 `external/agent-browser-kotlin` 分别执行：

1. 找到其 `build.gradle.kts`（或 `build.gradle`）和 `libs.versions.toml`（如果有），将 Kotlin 版本改为 `2.0.21`。
2. 如果子项目也使用了 Jetpack Compose，同样需要：
   - 删除旧的 `composeOptions { kotlinCompilerExtensionVersion = "..." }` 配置
   - 添加 `org.jetbrains.kotlin.plugin.compose` 插件（版本与 Kotlin 一致，即 `2.0.21`）
   - Compose BOM 版本升级到 `2024.12.01`
3. 如果子项目是纯 Kotlin 库（不涉及 Compose），只需要升级 Kotlin 版本即可。
4. 子项目中如果使用了 `kotlinx-serialization`，对应的插件 `org.jetbrains.kotlin.plugin.serialization` 版本也需要跟随 Kotlin 版本升级到 `2.0.21`。

##### 1.4 验证

在主项目根目录执行 `./gradlew assembleDebug`，确保主项目和所有子项目都能编译通过。如果出现编译错误，优先修复，不要跳过。

---

#### 任务 2：主题从 Material 2 迁移到 Material 3

修改 `app/src/main/res/values/themes.xml`，完整替换为：

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.Kotlinagentapp" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/md_primary</item>
        <item name="colorOnPrimary">@color/md_on_primary</item>
        <item name="colorPrimaryContainer">@color/md_primary_container</item>
        <item name="colorOnPrimaryContainer">@color/md_on_primary_container</item>
        <item name="colorSecondary">@color/md_secondary</item>
        <item name="colorOnSecondary">@color/md_on_secondary</item>
        <item name="colorSecondaryContainer">@color/md_secondary_container</item>
        <item name="colorOnSecondaryContainer">@color/md_on_secondary_container</item>
        <item name="colorTertiary">@color/md_tertiary</item>
        <item name="colorOnTertiary">@color/md_on_tertiary</item>
        <item name="colorSurface">@color/md_surface</item>
        <item name="colorOnSurface">@color/md_on_surface</item>
        <item name="colorSurfaceVariant">@color/md_surface_variant</item>
        <item name="colorOnSurfaceVariant">@color/md_on_surface_variant</item>
        <item name="colorError">@color/md_error</item>
        <item name="colorOnError">@color/md_on_error</item>
        <item name="colorOutline">@color/md_outline</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
    </style>
</resources>
```

如果存在 `res/values-night/themes.xml`，也做同样的 parent 替换为 `Theme.Material3.DayNight.NoActionBar`，配色值暂时与 light 相同，后续再调暗色方案。

---

#### 任务 3：替换配色方案

修改 `app/src/main/res/values/colors.xml`，完整替换为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- M3 Light scheme - 绿色科技感 -->
    <color name="md_primary">#FF1B6B4A</color>
    <color name="md_on_primary">#FFFFFFFF</color>
    <color name="md_primary_container">#FFA7F5C8</color>
    <color name="md_on_primary_container">#FF002113</color>
    <color name="md_secondary">#FF4E6355</color>
    <color name="md_on_secondary">#FFFFFFFF</color>
    <color name="md_secondary_container">#FFD0E8D6</color>
    <color name="md_on_secondary_container">#FF0B1F14</color>
    <color name="md_tertiary">#FF3C6472</color>
    <color name="md_on_tertiary">#FFFFFFFF</color>
    <color name="md_surface">#FFF6FBF3</color>
    <color name="md_on_surface">#FF171D19</color>
    <color name="md_surface_variant">#FFDCE5DB</color>
    <color name="md_on_surface_variant">#FF414942</color>
    <color name="md_error">#FFBA1A1A</color>
    <color name="md_on_error">#FFFFFFFF</color>
    <color name="md_outline">#FF717971</color>

    <!-- 旧色值保留，防止其他地方硬引用报错 -->
    <color name="purple_200">#FFBB86FC</color>
    <color name="purple_500">#FF6200EE</color>
    <color name="purple_700">#FF3700B3</color>
    <color name="teal_200">#FF03DAC5</color>
    <color name="teal_700">#FF018786</color>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
</resources>
```

---

#### 任务 4：修复 NoActionBar 导致的 ActionBar 缺失问题

切换到 `NoActionBar` 后，`AppCompatActivity.setSupportActionBar()` 和 `setupActionBarWithNavController()` 需要一个 `Toolbar`。

##### 4.1 修改 `app/src/main/res/layout/activity_main.xml`

在根布局最顶部（`BottomNavigationView` 和 `NavHostFragment` 之上）添加一个 `MaterialToolbar`：

```xml
<com.google.android.material.appbar.MaterialToolbar
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="?attr/actionBarSize"
    android:background="?attr/colorSurface"
    android:elevation="0dp"
    app:titleTextColor="?attr/colorOnSurface" />
```

确保 `NavHostFragment` 在 Toolbar 下方：
- 如果根布局是 `ConstraintLayout`，给 NavHostFragment 加 `app:layout_constraintTop_toBottomOf="@id/toolbar"`
- 如果根布局是 `LinearLayout`（vertical），Toolbar 放在最前面即可

##### 4.2 修改 `MainActivity.kt`

在 `setContentView(binding.root)` 之后、`setupActionBarWithNavController` 之前，添加：

```kotlin
setSupportActionBar(binding.toolbar)
```

##### 4.3 检查其他 Activity

全局搜索 `supportActionBar` 和 `actionBar`，确认没有其他 Activity 依赖系统 ActionBar。`RecorderActivity` 继承的是 `ComponentActivity`，它自己有 `binding.toolbar`，应该不受影响，但仍需确认。

---

#### 任务 5：Compose 侧主题对齐

找到项目中 Compose 的 Theme 定义文件（通常在 `ui/theme/` 目录下，文件名可能是 `Theme.kt`、`Color.kt`、`Type.kt`）。

##### 5.1 修改 `Color.kt`（或对应的颜色定义文件）

新增 M3 色值定义：

```kotlin
import androidx.compose.ui.graphics.Color

val md_primary = Color(0xFF1B6B4A)
val md_on_primary = Color(0xFFFFFFFF)
val md_primary_container = Color(0xFFA7F5C8)
val md_on_primary_container = Color(0xFF002113)
val md_secondary = Color(0xFF4E6355)
val md_on_secondary = Color(0xFFFFFFFF)
val md_secondary_container = Color(0xFFD0E8D6)
val md_on_secondary_container = Color(0xFF0B1F14)
val md_tertiary = Color(0xFF3C6472)
val md_on_tertiary = Color(0xFFFFFFFF)
val md_surface = Color(0xFFF6FBF3)
val md_on_surface = Color(0xFF171D19)
val md_surface_variant = Color(0xFFDCE5DB)
val md_on_surface_variant = Color(0xFF414942)
val md_error = Color(0xFFBA1A1A)
val md_on_error = Color(0xFFFFFFFF)
val md_outline = Color(0xFF717971)

val LightColorScheme = lightColorScheme(
    primary = md_primary,
    onPrimary = md_on_primary,
    primaryContainer = md_primary_container,
    onPrimaryContainer = md_on_primary_container,
    secondary = md_secondary,
    onSecondary = md_on_secondary,
    secondaryContainer = md_secondary_container,
    onSecondaryContainer = md_on_secondary_container,
    tertiary = md_tertiary,
    onTertiary = md_on_tertiary,
    surface = md_surface,
    onSurface = md_on_surface,
    surfaceVariant = md_surface_variant,
    onSurfaceVariant = md_on_surface_variant,
    error = md_error,
    onError = md_on_error,
    outline = md_outline,
)

// 暗色方案先用默认值占位，后续再调
val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BD8A8),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA7F5C8),
    secondary = Color(0xFFB5CCBB),
    onSecondary = Color(0xFF203528),
    secondaryContainer = Color(0xFF374B3E),
    onSecondaryContainer = Color(0xFFD0E8D6),
    tertiary = Color(0xFFA3CDDB),
    onTertiary = Color(0xFF033541),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9BF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF8B938A),
)
```

##### 5.2 修改 `Theme.kt`（或对应的主题定义文件）

```kotlin
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun KotlinAgentAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12+ 支持动态取色
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

注意：如果项目中现有的 Composable 函数使用了不同的 Theme 函数名，保持原有函数名不变，只替换内部实现。

---

#### 任务 6：全局搜索并修复编译错误

完成以上所有修改后，执行以下检查：

1. 全局搜索 `Theme.MaterialComponents`，确认没有其他地方引用旧主题（包括 `AndroidManifest.xml` 中的 `android:theme` 引用）
2. 全局搜索 `colorPrimaryVariant` 和 `colorSecondaryVariant`——这两个是 M2 独有的属性，M3 中不存在。如果有 XML 布局或代码引用了它们，替换为对应的 M3 属性：
   - `colorPrimaryVariant` → `colorPrimaryContainer`
   - `colorSecondaryVariant` → `colorSecondaryContainer`
3. 全局搜索 `purple_500`、`purple_700`、`teal_200`、`teal_700`，如果有布局或代码直接引用这些颜色值，评估是否应该改为 `?attr/colorPrimary` 等主题属性引用
4. 全局搜索 `MaterialComponents`，确认所有 XML style 的 parent 都已迁移到 M3 对应的 style（例如 `Widget.MaterialComponents.Button` → `Widget.Material3.Button`）
5. 编译项目：`./gradlew assembleDebug`
6. 修复所有编译错误
7. 确保 app 能正常启动，底部导航能正常切换，各页面功能不受影响

---

### 约束条件

- 不要改动任何业务逻辑代码，只改 UI/主题/样式/依赖版本相关的内容
- 不要删除任何现有功能
- 两个 composite build 子项目（`external/openagentic-sdk-kotlin` 和 `external/agent-browser-kotlin`）是我自己控制的项目，可以自由修改，必须与主项目统一升级 Kotlin 版本到 `2.0.21`
- 保留 `colors.xml` 中的旧色值定义（`purple_*`、`teal_*`、`black`、`white`），只是不再在主题中引用它们
- 所有改动完成后，主项目和所有子项目必须能通过 `./gradlew assembleDebug` 编译
- 如果升级过程中遇到某个依赖库与 Kotlin 2.0 不兼容，优先尝试升级该依赖库到兼容版本；如果该依赖库没有兼容版本，在指令执行报告中说明具体是哪个库、什么版本、什么错误信息，等待人工决策
