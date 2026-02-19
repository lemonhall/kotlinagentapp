# GrepTool 对齐 ripgrep 移植报告

> 基于 ripgrep 15.1.0 源码分析，目标是将 rg 的核心能力移植到纯 Kotlin (Android) 的 GrepTool 实现中。
>
> 日期：2026-02-19

---

## 一、ripgrep 架构概览

ripgrep 由 10 个独立 crate 组成：

| crate | 职责 |
|-------|------|
| `globset` | glob 模式 → regex 编译器，支持 `RegexSet` 同时匹配多个 glob |
| `ignore` | 目录遍历 + `.gitignore` / `.ignore` / `.rgignore` 解析 + 文件类型系统 |
| `matcher` | `Matcher` trait 抽象，定义搜索引擎接口 |
| `regex` | 基于 Rust regex 的 Matcher 实现，包含字面量优化 |
| `searcher` | 搜索引擎核心：缓冲区管理、二进制检测、行定位、上下文处理 |
| `printer` | 输出格式化：文本 / JSON / 统计摘要 |
| `pcre2` | PCRE2 正则后端（可选） |
| `cli` | 命令行 I/O 工具函数 |
| `core` | 主程序胶水层，参数解析和调度 |
| `grep` | 门面 crate，re-export 上述模块 |

当前 GrepTool 相当于把 `ignore`（遍历）+ `searcher`（搜索）+ `printer`（输出）三个 crate 的功能压缩在一个类中。

---

## 二、需要移植/对齐的功能点

### P0：改动小、收益大，建议立即实施

#### 2.1 字面量快速路径

**ripgrep 行为：** regex crate 内部会从正则中提取字面量，纯字面量查询完全绕过 regex 引擎，使用 `memchr` / Aho-Corasick / Teddy SIMD 进行快速扫描。

**当前 GrepTool：** 所有查询统一走 `Regex.containsMatchIn(line)`，无论是否为纯字面量。

**移植方案：**

- 检测 query 是否包含正则元字符（`.*+?^${}()|[]\`）
- 纯字面量时使用 `String.contains()` 替代 `Regex`，JVM 上性能差距约 10 倍
- 进阶：对 `foo|bar|baz` 形式的纯字面量交替，提取字面量列表逐个 `contains` 检查，或引入 Aho-Corasick 库（如 `org.ahocorasick:ahocorasick`）

**预估改动量：** ~20 行

---

#### 2.2 二进制文件检测

**ripgrep 行为：** `BinaryDetection` 枚举，三种策略：

| 策略 | 行为 |
|------|------|
| `None` | 不检测 |
| `Quit(u8)` | 发现指定字节（默认 `0x00`）立即停止搜索该文件（默认策略） |
| `Convert(u8)` | 发现指定字节替换为行终止符 |

实际实现：
- 流式搜索（`ReadByLine`）：每次填充缓冲区时检测
- 内存搜索（`SliceByLine` / `MultiLine`）：只检测前 8KB（`DEFAULT_BUFFER_CAPACITY`），之后仅在匹配行中检测

**当前 GrepTool：** 无二进制检测，仅有 2MB 文件大小上限。

**移植方案：**

- 读取文件字节后，检查前 8192 字节是否包含 `0x00`
- 如果包含，跳过该文件
- 可选：增加 `binary_detection` 参数让 agent 控制行为（`skip` / `none`）

**预估改动量：** ~5-10 行

---

#### 2.3 隐藏文件默认行为

**ripgrep 行为：** 默认跳过隐藏文件和目录（以 `.` 开头），需要 `--hidden` 显式开启。

**当前 GrepTool：** `include_hidden` 默认值为 `true`（`!= false` 的判断逻辑），即默认包含隐藏文件。

**移植方案：**

- 将 `include_hidden` 的默认值改为 `false`（即 `== true` 才包含隐藏文件）
- 这样 agent 不传参数时行为与 rg 一致

**预估改动量：** 1 行

---

### P1：中等改动量，核心功能对齐

#### 2.4 整文件搜索替代逐行搜索

**ripgrep 行为：** BurntSushi 的核心设计原则——"Thou Shalt Not Search Line By Line"。`SliceByLine` 策略在整个 buffer 上调用 matcher 的 `find` 方法找到匹配位置，然后才回头定位行边界。对于大文件中匹配稀少的场景（最常见），避免了对每一行调用 regex。

**当前 GrepTool：** `text.split('\n')` 拆成行数组，然后 `for (idx0 in lines.indices)` 逐行调用 `rx.containsMatchIn(line)`。

**移植方案：**

- 先在整个文件文本上调用 `rx.findAll(text)` 获取所有匹配
- 如果无匹配，直接跳过该文件（避免 split 开销）
- 预计算行偏移表（一次遍历文本，记录每个 `\n` 的位置）
- 对每个匹配，用二分查找定位行号和行边界
- 上下文行通过行偏移表直接索引

**预估改动量：** ~40-50 行

---

#### 2.5 `.gitignore` 支持

**ripgrep 行为（`ignore/src/gitignore.rs`）：**

`GitignoreBuilder.add_line()` 的完整解析逻辑：

1. 去除行尾空白（但 `\ ` 转义的空格保留）
2. 跳过空行
3. 跳过 `#` 开头的注释行（`\#` 转义除外）
4. `!` 前缀 → 白名单（否定规则），`\!` 转义除外
5. 前导 `/` → 锚定到 `.gitignore` 所在目录，strip 掉 `/`
6. 尾部 `/` → 只匹配目录，strip 掉 `/`
7. 如果模式不含 `/`（strip 后）→ 自动添加 `**/` 前缀（匹配任意深度）
8. 如果模式以 `/**` 结尾 → 追加 `/*`（匹配目录下所有内容）
9. 编译为 glob（`literal_separator=true`，即 `*` 不匹配 `/`）
10. 支持 UTF-8 BOM 处理（第一行 trim BOM）

匹配优先级：
- 后定义的规则优先于先定义的
- 子目录的 `.gitignore` 优先于父目录的
- 白名单（`!`）可以覆盖忽略规则

**当前 GrepTool：** 完全不支持任何 ignore 文件。

**移植方案：**

核心数据结构：
```
GitignoreGlob:
  - original: String          // 原始行文本
  - pattern: Regex            // 编译后的正则
  - isWhitelist: Boolean      // ! 前缀
  - isOnlyDir: Boolean        // 尾部 /
  - isAnchored: Boolean       // 含 / 或前导 /

GitignoreParser:
  - root: Path                // .gitignore 所在目录
  - globs: List<GitignoreGlob>
  - addFile(path: Path)       // 读取并逐行解析
  - addLine(line: String)     // 解析单行
  - matched(relativePath: String, isDir: Boolean): MatchResult  // None / Ignore / Whitelist
```

匹配逻辑：从后往前遍历 globs，返回第一个匹配的结果。

glob → regex 编译时需要注意 `literal_separator=true`：
- `*` → `[^/]*`（不匹配路径分隔符）
- `**` → `.*`（匹配任意路径）
- `?` → `[^/]`

**预估改动量：** ~150 行

---

#### 2.6 遍历时的层级 gitignore 栈

**ripgrep 行为（`ignore/src/dir.rs` + `ignore/src/walk.rs`）：**

目录遍历器维护一个忽略规则栈：
1. 进入目录时，检查是否存在 `.gitignore` / `.ignore` / `.rgignore`
2. 如果存在，解析并压入栈
3. 匹配文件时，从栈顶（最深目录）往栈底查找
4. 离开目录时弹出栈

还支持的忽略源（按优先级从高到低）：
1. 命令行 `--ignore-file` 指定的文件
2. 当前目录的 `.rgignore`
3. 当前目录的 `.ignore`
4. 当前目录的 `.gitignore`
5. 父目录的上述文件（递归向上）
6. `.git/info/exclude`
7. 全局 gitignore（`core.excludesFile` 配置）

**当前 GrepTool：** 使用 `ctx.fileSystem.listRecursively()` 遍历，无任何忽略规则集成。

**移植方案：**

- 替换 `listRecursively()` 为自定义的递归遍历函数
- 遍历时维护一个 `Stack<GitignoreParser>`
- 每进入一个目录：检查 `.gitignore` 是否存在 → 解析 → 压栈
- 每个文件/目录：从栈顶到栈底依次匹配，第一个命中的决定是否忽略
- 离开目录时弹栈
- Android 场景下可以只支持 `.gitignore`，不需要 `.rgignore` / `.ignore` / 全局 gitignore

**预估改动量：** ~80-100 行

---

#### 2.7 输出格式增强：submatches

**ripgrep 行为（`printer/src/jsont.rs`）：**

`match` 消息中包含 `submatches` 数组，精确标记行内每个匹配的位置：

```json
{
  "type": "match",
  "data": {
    "path": {"text": "src/main.kt"},
    "lines": {"text": "    val result = processQuery(input)\n"},
    "line_number": 42,
    "absolute_offset": 1337,
    "submatches": [
      {"match": {"text": "processQuery"}, "start": 18, "end": 30}
    ]
  }
}
```

字段说明：
- `match` — 匹配到的文本
- `start` / `end` — 行内字节偏移，半开区间 `[start, end)`

文本编码处理（`Data` 类型）：
- 合法 UTF-8 → `{"text": "字符串"}`
- 非法 UTF-8 → `{"bytes": "base64编码"}`

**当前 GrepTool：** 只输出整行文本 `text`，无行内匹配位置信息。

**移植方案：**

- 在匹配行上调用 `rx.findAll(line)` 获取所有 submatch
- 每个 submatch 输出 `match`（匹配文本）、`start`（起始偏移）、`end`（结束偏移，半开区间）
- Android 上基本都是 UTF-8，`Data` 类型的 `text`/`bytes` 双模式可以简化为纯字符串

**预估改动量：** ~15 行

---

#### 2.8 输出格式增强：统计信息

**ripgrep 行为：**

每个文件搜索结束时输出 `end` 消息，包含 `stats`：

```json
{
  "stats": {
    "elapsed": {"secs": 0, "nanos": 36296, "human": "0.0000s"},
    "searches": 1,
    "searches_with_match": 1,
    "bytes_searched": 367,
    "bytes_printed": 1151,
    "matched_lines": 2,
    "matches": 2
  }
}
```

**当前 GrepTool：** 只有 `total_matches` 和 `truncated`。

**移植方案：**

在最终输出中增加 `stats` 对象：

```json
{
  "stats": {
    "files_searched": 150,
    "files_with_matches": 3,
    "files_skipped_binary": 5,
    "files_skipped_gitignore": 200,
    "total_matches": 7,
    "matched_lines": 7,
    "bytes_searched": 524288,
    "elapsed_ms": 42
  }
}
```

这些统计信息对 agent 判断搜索结果的质量和覆盖范围很有价值。

**预估改动量：** ~20 行

---

#### 2.9 输出格式增强：absolute_offset

**ripgrep 行为：** 每个 match/context 消息都包含 `absolute_offset`，表示该行在文件中的绝对字节偏移。

**当前 GrepTool：** 无此字段。

**移植方案：**

- 如果实施了 2.4（整文件搜索 + 行偏移表），`absolute_offset` 可以直接从行偏移表中获取，几乎零成本
- 对 agent 做精确文件编辑时非常有用

**预估改动量：** ~5 行（依赖 2.4 的行偏移表）

---

### P2：锦上添花，按需实施

#### 2.10 文件类型系统

**ripgrep 行为（`ignore/src/default_types.rs`）：**

内置 150+ 种文件类型映射，格式为 `(别名列表, glob 模式列表)`，部分示例：

| 类型名 | glob 模式 |
|--------|----------|
| `kotlin` | `*.kt`, `*.kts` |
| `java` | `*.java` |
| `py`, `python` | `*.py`, `*.pyi` |
| `js` | `*.js`, `*.jsx`, `*.vue`, `*.cjs`, `*.mjs` |
| `ts`, `typescript` | `*.ts`, `*.tsx`, `*.cts`, `*.mts` |
| `html` | `*.htm`, `*.html`, `*.xhtml` |
| `css` | `*.css`, `*.scss`, `*.less` |
| `json` | `*.json`, `*.jsonl` |
| `xml` | `*.xml`, `*.xsl`, `*.xslt`, `*.svg`, ... |
| `yaml` | `*.yaml`, `*.yml` |
| `toml` | `*.toml` |
| `markdown`, `md` | `*.md`, `*.markdown`, `*.mdown`, `*.mkdn` |
| `c` | `*.c`, `*.h`, `*.cats` |
| `cpp` | `*.cpp`, `*.cxx`, `*.cc`, `*.hpp`, `*.hxx`, `*.hh`, ... |
| `rust` | `*.rs` |
| `go` | `*.go` |
| `swift` | `*.swift` |
| `ruby`, `rb` | `*.rb`, `*.rbw`, `Gemfile`, `Rakefile`, ... |
| `shell`, `sh` | `*.sh`, `*.bash`, `*.zsh`, `*.fish`, ... |
| `sql` | `*.sql`, `*.psql` |
| `protobuf` | `*.proto` |
| `gradle` | `*.gradle`, `*.gradle.kts` |
| `cmake` | `CMakeLists.txt`, `*.cmake` |
| `docker` | `Dockerfile*`, `*.dockerfile` |

**当前 GrepTool：** 只有 `file_glob` 参数。

**移植方案：**

- 新增 `file_type` 参数（如 `"kotlin"`, `"java"` 等）
- 内置一个 `Map<String, List<String>>` 类型数据库
- 当 `file_type` 存在时，自动转换为对应的 glob 集合
- 可以和 `file_glob` 共存（取交集）
- 对 agent 来说，`file_type: "kotlin"` 比 `file_glob: "**/*.kt"` 更语义化

**预估改动量：** ~100 行（主要是数据定义）

---

#### 2.11 glob → regex 的 `literal_separator` 支持

**ripgrep 行为（`globset` crate）：**

glob 编译时有一个关键选项 `literal_separator`（默认 `false`，但 gitignore 模式下为 `true`）：

| 选项 | `*` 匹配 | `?` 匹配 | `[...]` 匹配 |
|------|----------|----------|-------------|
| `literal_separator=false` | 任意字符（含 `/`） | 任意单字符（含 `/`） | 含 `/` |
| `literal_separator=true` | 任意字符（不含 `/`） | 任意单字符（不含 `/`） | 不含 `/` |

`**` 在两种模式下都匹配任意路径（含 `/`）。

**当前 GrepTool：** 取决于 `globToRegex()` 的实现（未在提供的代码中），可能未正确处理此区分。

**移植方案：**

- 检查现有 `globToRegex()` 实现
- 确保 `file_glob` 参数使用 `literal_separator=true` 语义（`*` 不匹配 `/`）
- gitignore 解析中的 glob 也必须使用 `literal_separator=true`

**预估改动量：** 取决于现有实现

---

#### 2.12 符号链接处理

**ripgrep 行为：** 默认不跟随符号链接，需要 `--follow` / `-L` 显式开启。跟随时会检测循环引用。

**当前 GrepTool：** 行为取决于 Okio 的 `listRecursively()` 实现，未明确控制。

**移植方案：**

- 在自定义遍历函数中（2.6），检查 `metadata.symlinkTarget` 是否为 null
- 默认跳过符号链接
- 可选增加 `follow_symlinks` 参数

**预估改动量：** ~10 行

---

### 不需要移植的部分

| ripgrep 特性 | 不移植原因 |
|-------------|-----------|
| 多线程并行搜索 | 已用协程 yield，Android 上文件量不大 |
| mmap vs 增量读取 | Okio 已处理 |
| SIMD 加速（Teddy / memchr） | JVM 上无法直接使用，JIT 会做部分自动向量化 |
| PCRE2 后端 | Kotlin Regex 够用 |
| 彩色终端输出 | 输出为 JSON |
| 压缩文件搜索（`-z`） | agent 场景不需要 |
| JSON Lines 流式输出 | GrepTool 是一次性返回 `ToolOutput.Json`，非流式 |
| `begin`/`end` 生命周期消息 | 同上，非流式场景不需要 |
| `Data` 类型的 `text`/`bytes` 双模式 | Android 上基本都是 UTF-8 |
| 全局 gitignore / `.git/info/exclude` | Android 项目目录场景下不需要 |
| `.rgignore` / `.ignore` | 非 ripgrep 专用场景 |

---

## 三、建议的最终输出格式

### content 模式

```json
{
  "root": "/path/to/root",
  "query": "processQuery",
  "matches": [
    {
      "file_path": "src/main/kotlin/App.kt",
      "line_number": 42,
      "absolute_offset": 1337,
      "lines": "    val result = processQuery(input)",
      "submatches": [
        {"match": "processQuery", "start": 18, "end": 30}
      ],
      "before_context": ["    // Handle user input"],
      "after_context": ["    return result"]
    }
  ],
  "stats": {
    "files_searched": 150,
    "files_with_matches": 3,
    "files_skipped_binary": 5,
    "files_skipped_gitignore": 200,
    "total_matches": 7,
    "matched_lines": 7,
    "bytes_searched": 524288,
    "elapsed_ms": 42
  },
  "truncated": false
}
```

### files_with_matches 模式

```json
{
  "root": "/path/to/root",
  "query": "processQuery",
  "files": [
    "src/main/kotlin/App.kt",
    "src/main/kotlin/Query.kt",
    "src/test/kotlin/AppTest.kt"
  ],
  "stats": {
    "files_searched": 150,
    "files_with_matches": 3,
    "files_skipped_binary": 5,
    "files_skipped_gitignore": 200,
    "bytes_searched": 524288,
    "elapsed_ms": 42
  }
}
```

---

## 四、实施路线图

### 第一阶段（P0，预计 1 天）

- [ ] 2.1 字面量快速路径
- [ ] 2.2 二进制文件检测
- [ ] 2.3 隐藏文件默认行为修正

### 第二阶段（P1 搜索引擎，预计 1-2 天）

- [ ] 2.4 整文件搜索替代逐行搜索
- [ ] 2.7 输出增强：submatches
- [ ] 2.8 输出增强：统计信息
- [ ] 2.9 输出增强：absolute_offset

### 第三阶段（P1 忽略规则，预计 2-3 天）

- [ ] 2.5 `.gitignore` 解析器
- [ ] 2.6 遍历时层级 gitignore 栈
- [ ] 2.11 glob → regex 的 `literal_separator` 确认/修复

### 第四阶段（P2，按需）

- [ ] 2.10 文件类型系统
- [ ] 2.12 符号链接处理