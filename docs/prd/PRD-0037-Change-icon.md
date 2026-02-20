### 替换 App 图标

#### 1. 保存 SVG 源文件

将以下 SVG 保存为 `app/src/main/res/drawable/ic_launcher_foreground.svg`（仅作为源文件留档）：

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#1A1A2E"/>
      <stop offset="100%" stop-color="#16213E"/>
    </linearGradient>
    <linearGradient id="lemon" x1="0.3" y1="0" x2="0.7" y2="1">
      <stop offset="0%" stop-color="#FFF44F"/>
      <stop offset="100%" stop-color="#F0D000"/>
    </linearGradient>
  </defs>
  <rect width="512" height="512" rx="108" ry="108" fill="url(#bg)"/>
  <circle cx="256" cy="240" r="80" fill="none" stroke="#FFF44F" stroke-width="1.5" opacity="0.12"/>
  <circle cx="256" cy="240" r="120" fill="none" stroke="#FFF44F" stroke-width="1.5" opacity="0.08"/>
  <circle cx="256" cy="240" r="160" fill="none" stroke="#FFF44F" stroke-width="1" opacity="0.05"/>
  <path d="M196 240 Q256 148 316 240 Q256 332 196 240Z" fill="url(#lemon)"/>
  <rect x="244" y="216" width="24" height="36" rx="12" ry="12" fill="#1A1A2E"/>
  <path d="M236 244 Q236 268 256 268 Q276 268 276 244" fill="none" stroke="#1A1A2E" stroke-width="4" stroke-linecap="round"/>
  <line x1="256" y1="268" x2="256" y2="280" stroke="#1A1A2E" stroke-width="4" stroke-linecap="round"/>
  <line x1="168" y1="220" x2="168" y2="260" stroke="#FFF44F" stroke-width="3" stroke-linecap="round" opacity="0.6"/>
  <line x1="152" y1="228" x2="152" y2="252" stroke="#FFF44F" stroke-width="3" stroke-linecap="round" opacity="0.4"/>
  <line x1="136" y1="234" x2="136" y2="246" stroke="#FFF44F" stroke-width="3" stroke-linecap="round" opacity="0.25"/>
  <line x1="344" y1="220" x2="344" y2="260" stroke="#FFF44F" stroke-width="3" stroke-linecap="round" opacity="0.6"/>
  <line x1="360" y1="228" x2="360" y2="252" stroke="#FFF44F" stroke-width="3" stroke-linecap="round" opacity="0.4"/>
  <line x1="376" y1="234" x2="376" y2="246" stroke="#FFF44F" stroke-width="3" stroke-linecap="round" opacity="0.25"/>
  <text x="120" y="148" font-family="system-ui, sans-serif" font-size="16" fill="#FFF44F" opacity="0.35">Hello</text>
  <text x="330" y="148" font-family="system-ui, sans-serif" font-size="15" fill="#FFF44F" opacity="0.3">こんにちは</text>
  <text x="108" y="360" font-family="system-ui, sans-serif" font-size="15" fill="#FFF44F" opacity="0.3">Bonjour</text>
  <text x="320" y="360" font-family="system-ui, sans-serif" font-size="15" fill="#FFF44F" opacity="0.35">你好</text>
  <text x="200" y="400" font-family="system-ui, sans-serif" font-size="14" fill="#FFF44F" opacity="0.2">Hola</text>
  <text x="240" y="120" font-family="system-ui, sans-serif" font-size="13" fill="#FFF44F" opacity="0.2">안녕</text>
  <text x="256" y="448" font-family="system-ui, sans-serif" font-weight="700" font-size="32" fill="#FFF44F" text-anchor="middle" letter-spacing="6" opacity="0.85">AGENT</text>
</svg>
```

#### 2. 生成 PNG 图标文件

Android 自适应图标需要 PNG 位图。用项目根目录下的脚本或任何可用工具，将上面的 SVG 导出为以下尺寸的 PNG，覆盖对应目录中的 `ic_launcher.png` 和 `ic_launcher_round.png`：

| 目录 | 尺寸 |
|---|---|
| `app/src/main/res/mipmap-mdpi/` | 48×48 |
| `app/src/main/res/mipmap-hdpi/` | 72×72 |
| `app/src/main/res/mipmap-xhdpi/` | 96×96 |
| `app/src/main/res/mipmap-xxhdpi/` | 144×144 |
| `app/src/main/res/mipmap-xxxhdpi/` | 192×192 |

`ic_launcher.png` 和 `ic_launcher_round.png` 使用同一张图即可（圆角已经在 SVG 里了）。

#### 3. 更新自适应图标配置

替换 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
```

同样替换 `ic_launcher_round.xml` 为相同内容。

在 `app/src/main/res/values/colors.xml` 中添加（如果不存在）：

```xml
<color name="ic_launcher_background">#1A1A2E</color>
```

#### 4. 生成前景层 PNG

对于自适应图标的前景层，需要额外导出一版 512×512 的 PNG（保留透明背景，去掉 SVG 中的 `<rect>` 背景），保存为各 mipmap 目录下的 `ic_launcher_foreground.png`：

| 目录 | 尺寸 |
|---|---|
| `app/src/main/res/mipmap-mdpi/` | 108×108 |
| `app/src/main/res/mipmap-hdpi/` | 162×162 |
| `app/src/main/res/mipmap-xhdpi/` | 216×216 |
| `app/src/main/res/mipmap-xxhdpi/` | 324×324 |
| `app/src/main/res/mipmap-xxxhdpi/` | 432×432 |

#### 5. 确认 AndroidManifest.xml

确保 `AndroidManifest.xml` 中 `<application>` 标签的图标属性指向：

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

这通常已经是默认值，确认一下即可。

#### 注意

- Codex 环境中如果没有 SVG 转 PNG 的工具（如 `rsvg-convert`、`cairosvg`、`sharp`），可以用 Python + `cairosvg` 库来转换，或者直接告诉我无法转换，我来提供预渲染的 PNG
- SVG 中的 `<text>` 元素在转 PNG 时需要系统有对应字体（中文、日文、韩文），如果缺字体导致文字缺失，可以接受，不影响主体图标识别度
- 不要修改任何业务代码，只替换图标资源文件