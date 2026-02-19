---
name: radio-cli
description: 通过 `terminal_exec` 控制/查询电台播放器状态，以及浏览/搜索可用电台。
---

# radio-cli（Pseudo Radio CLI）

## Goal

为 App 内置 Radio 电台播放器提供"堆外控制面"（可审计、可测试）：用 `terminal_exec` 执行 `radio ...` 命令查询/控制播放状态。

## 电台库结构

电台文件存放在 `workspace/radios/` 下，按国家分目录：

```
workspace/radios/
  .countries.index.json   ← 国家列表索引
  .countries.meta.json    ← 国家元数据
  {CC}__{CountryName}/    ← 国家目录，如 AU__Australia/、CN__China/
    .stations.index.json  ← 该国电台索引（用于搜索）
    .stations.meta.json   ← 该国电台缓存元数据（TTL）
    {StationName}__{hash}.radio  ← 电台文件
    _STATUS.md
```

## Commands（v35）

### Help

使用工具 `terminal_exec` 执行：

- `radio --help`
- `radio help`
- `radio play --help`

期望：
- `exit_code=0`

### 状态

使用工具 `terminal_exec` 执行：

- `radio status`

期望：
- `exit_code=0`
- `result.state` 为 `idle|playing|paused|stopped|error`
- 播放电台时 `result.station.path` 为 `workspace/radios/**.radio`

### 播放控制（仅允许 radios/ 子树）

使用工具 `terminal_exec` 执行（示例）：

- `radio play --in workspace/radios/CN__China/中国之声__a1b2c3d4e5.radio`
- `radio pause`
- `radio resume`
- `radio stop`

期望：
- `exit_code=0`
- `radio play` 成功后：`radio status` 的 `result.state=playing`

## 常见用语映射

用户描述电台时经常使用口语化表达，必须正确映射到国家代码：

| 用户说法 | 国家代码 | 目录名 |
|---|---|---|
| "国内" / "中国" / "大陆" / "内地" | CN | CN__China |
| "香港" / "港台" | HK | HK__Hong Kong |
| "台湾" | TW | TW__Taiwan, Republic Of China |
| "澳门" | MO | MO__Macao |
| "日本" | JP | JP__Japan |
| "美国" | US | US__The United States Of America |

用户说"国内"时，**一律**指 CN__China，不要混淆为 HK/MO/TW。

## 电台发现流程（必须遵守）

当用户请求播放某个电台但未给出精确 `.radio` 文件路径时，**必须使用 `explore` 子 agent** 完成电台发现。主 agent 禁止自行读取或扫描 `workspace/radios/` 下的任何文件。

### Step 1：调用 explore

使用 `Task(agent="explore", prompt="...")` 发出搜索指令，模板：

> **直接**用 `Read` 读取 `workspace/radios/.countries.index.json`（不要用 Glob/Grep 搜索这个文件，路径是确定的），
> 从 JSON 中找到 code 为 "{CC}" 的条目，取其 dir 字段作为国家目录名；
> 然后对 `workspace/radios/{dir}/.stations.index.json` 用 `Grep` 搜索（regex）：
> - 优先匹配 `"name": "..."` 含 "{用户关键词}" 的行
> - 同时可匹配 tags 含 `"news"` 等新闻属性
> - 必须设置 `after_context>=8`，以便从 after_context 中拿到同一条目里的 `"path": "..."` 行
> 返回最多 10 条结果，每条包含电台名称（name）和完整的 `.radio` 文件相对路径（path）。

示例——用户说"收听国内的新闻 radio"：

> **直接**用 `Read` 读取 `workspace/radios/.countries.index.json`（路径确定，不要搜索），
> 找到 code 为 "CN" 的条目，取其 dir 字段；
> 然后对 `workspace/radios/CN__China/.stations.index.json` 用 `Grep` 搜索 `"name": ".*(新闻|资讯|综合|中国之声|CNR|央广|CRI).*"`，
> 并设置 `after_context>=8`，从 after_context 中提取 `"path": "..."`；
> 若 name 匹配不足，可补充一次 Grep：搜索 tags 行 `"tags": .*news.*`；
> 返回最多 10 条结果，每条包含电台名称（name）和 `.radio` 文件相对路径（path）。

### Step 2：处理 explore 返回结果

- 返回 1 条结果：直接播放。
- 返回多条结果：列出候选项（序号 + 电台名称）让用户选择。
- 返回 0 条结果：告知用户未找到匹配电台，建议换个关键词。
- explore 报告国家不存在：告知用户"当前电台库中没有该国家的电台"。
- explore 返回错误或超时：告知用户"电台搜索失败"，不要回退到主 agent 自行扫描。

### Step 3：播放

拿到精确的 `.radio` 文件路径后，执行 `radio play --in <path>`。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `radio play` 只允许 `workspace/radios/**.radio`；越界应返回 `exit_code!=0` 且 `error_code` 可解释（如 `NotInRadiosDir/NotRadioFile/NotFound`）。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接报告错误并停止。
- **主 agent 禁止**使用 `Read`、`Glob`、`List`、`Grep` 等工具直接访问 `workspace/radios/` 目录树。所有电台发现必须委派给 `explore` 子 agent。
- `explore` 子 agent 内部可以自由使用 `Read`、`Glob`、`Grep` 等工具完成搜索，这是它的职责。
- SKILL 已经描述了完整的命令格式和用法，不需要额外调用 `radio --help` 来确认。直接按流程操作即可。
