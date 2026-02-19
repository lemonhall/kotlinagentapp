---
name: radio-cli
description: 通过 `terminal_exec` 控制/查询电台播放器状态，以及浏览/搜索可用电台。
---

# radio-cli（Pseudo Radio CLI）

## Goal

为 App 内置 Radio 电台播放器提供"堆外控制面"（可审计、可测试）：用 `terminal_exec` 执行 `radio ...` 命令查询/控制播放状态。

## 🔴 必读：模糊选台（最常见）

当用户说“来点 XXX 的音乐/电台，radio”“收听国内新闻 radio”等 **未给出精确 `.radio` 路径** 的口语化需求时：

1) **不要**回复“radio 不支持搜索所以做不了”。（`radio` 命令确实没有 `search` 子命令，但本技能要求用索引 + `explore` 完成发现。）
2) **必须**按本文档的《电台发现流程（必须遵守）》走：先 `radio fav list` → 再读 `workspace/radios/.last_played.json` → 再 `Task(agent="explore", ...)` 用 `Grep` 搜索索引 → 拿到候选 `.radio` 路径后 `radio play`。
3) 仅当 explore 结果为 0 或明确不存在该国家目录时，才引导用户换关键词或改需求；不要让用户“自己先给 stream url / 自己先写 .radio”作为默认路径。

## 电台库结构

电台文件存放在 `workspace/radios/` 下，按国家分目录：

```
workspace/radios/
  .countries.index.json   ← 国家列表索引
  .countries.meta.json    ← 国家元数据
  .last_played.json       ← 上次播放记忆（用于加速重复请求）
  favorites/              ← 收藏夹（Everything is FileSystem）
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
- `radio play --in "workspace/radios/EG__Egypt/Egyptian Radio__6254b05b33.radio"`（路径含空格/Unicode 时用双引号）
- `radio play --in_b64 <base64-utf8-path>`（推荐：彻底规避空格/Unicode/转义问题）
- `radio pause`
- `radio resume`
- `radio stop`

#### 关于 `--in_b64`

当 `.radio` 文件路径包含空格（包括阿语空格）、中文或其它 Unicode 字符时，为避免参数被拆分/转义错误，优先用 `--in_b64`：把 **UTF-8 路径**做 Base64 编码后传参。

示例：
- 原始路径：`workspace/radios/EG__Egypt/Egyptian Radio__6254b05b33.radio`
- 编码后：`d29ya3NwYWNlL3JhZGlvcy9FR19fRWd5cHQvRWd5cHRpYW4gUmFkaW9fXzYyNTRiMDViMzMucmFkaW8=`
- 命令：`radio play --in_b64 d29ya3NwYWNlL3JhZGlvcy9FR19fRWd5cHQvRWd5cHRpYW4gUmFkaW9fXzYyNTRiMDViMzMucmFkaW8=`

期望：
- `exit_code=0`
- `radio play` 成功后：`radio status` 的 `result.state=playing`

### 收藏（Favorites）

使用工具 `terminal_exec` 执行：

- `radio fav list`
- `radio fav add`（默认收藏**当前正在播放**的电台）
- `radio fav rm`（默认移出**当前正在播放**的电台）

也可显式指定路径（仅允许 `workspace/radios/**.radio`）：

- `radio fav add --in workspace/radios/CN__China/中国之声__a1b2c3d4e5.radio`
- `radio fav rm --in workspace/radios/CN__China/中国之声__a1b2c3d4e5.radio`
- `radio fav add --in "workspace/radios/EG__Egypt/Egyptian Radio__6254b05b33.radio"`（路径含空格/Unicode 时用双引号）
- `radio fav rm --in_b64 <base64-utf8-path>`

期望：
- `exit_code=0`
- `radio fav list` 的 `result.favorites[]` 返回 `path/name/id/country`

### 懒加载同步（国家/电台索引）

> 重要：很多国家目录的 `.stations.index.json` / `.radio` 文件是**懒加载**生成的（例如你从未在 Files 里点进过该国家目录）。
> 当需要“按意图选台”时，应先用 `radio sync ...` 生成索引与 `.radio` 文件，再进入 explore 搜索流程。

使用工具 `terminal_exec` 执行：

- `radio sync countries`（拉取/刷新国家索引，72h TTL；通常很快）
- `radio sync stations --cc EG`（按国家代码拉取该国电台并生成 `.stations.index.json` 与 `.radio` 文件）
- `radio sync stations --dir EG__Egypt --force`（按国家目录强制刷新）

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

### Step -1：确保索引已生成（自动）

目标：避免“索引/电台文件尚未懒加载生成”导致 explore 无法 Grep。

1) **直接**用 `terminal_exec` 执行 `radio sync countries`（无需 Read 文件系统；72h TTL 缓存，重复执行开销很低）。
2) 如果用户明确提到国家/地区（或你能稳定判断国家代码），可提前执行 `radio sync stations --cc <CC>` 以确保 `.stations.index.json` 存在。

### Step 0：读取收藏夹（快速路径）

目标：优先复用收藏夹，降低搜索成本、加快选台。

1) **直接**用 `terminal_exec` 执行 `radio fav list` 获取收藏夹列表（无需 Read 文件系统）。
2) 若用户意图明显可在收藏夹中匹配（允许模糊匹配），优先给出收藏候选让用户选：
   - 问法示例：`我在收藏夹里找到了这些：1)xxx 2)yyy，要播放哪个？回复序号。`
3) 如果收藏夹为空/匹配不到/用户说“换一批/重新搜”：进入 Step 1（explore 搜索）。

### Step 0.1：检查记忆（快速路径）

目标：对“重复意图”的请求，避免每次都从头跑 explore 搜索。

1) **直接**用 `Read` 读取 `workspace/radios/.last_played.json`（若不存在/读取失败则跳过 Step 0.1，进入 Step 1）。
2) 判断“用户当前意图”是否与记忆相似（允许粗略匹配，不要求精确）：
   - 例：用户说“国内/中国 + 新闻/资讯/时事/radio”，且记忆里的 station.path 包含 `workspace/radios/CN__China/`，就视为相似。
3) 若相似：优先询问用户是否沿用上次播放的电台，并提供候选换台（如果 candidates 存在）：
   - 问法示例：`上次你听的是 {station.name}，要继续吗？回复：1继续 2换台 3重新搜索`
   - 用户回复：
     - `1/继续/好` → 直接进入 Step 3 播放 `station.path`（跳过 Step 1-2）
     - `2/换台` → 展示 `candidates`（序号 + name），用户选定后直接进入 Step 3 播放（跳过 Step 1-2）
     - `3/重新搜索` 或用户说“不是/换一批/其他” → 进入 Step 1 正常 explore 搜索

### Step 1：调用 explore

使用 `Task(agent="explore", prompt="...")` 发出搜索指令，模板：

> 先用 `Grep` 在 `workspace/radios/.countries.index.json` 里精确定位国家目录（不要整文件 Read）：
> - `file_glob="workspace/radios/.countries.index.json"`
> - `query="\\\"code\\\"\\s*:\\s*\\\"{CC}\\\""`
> - `before_context>=6`（用于从 before_context 里拿到同一条目的 `"dir": "..."`）
> 然后在 `workspace/radios/{dir}/.stations.index.json` 里用 `Grep` 搜索候选（不要整文件 Read；最多 2 次 Grep + 不超过 2 次小范围 Read）：
> 1) 首选命中 path 行（这样能直接拿到 `.radio` 路径），并用 `before_context>=4` 拿到 name：
>    - `file_glob="workspace/radios/{dir}/.stations.index.json"`
>    - `query="\\\"path\\\"\\s*:\\s*\\\"workspace/radios/{dir}/.*({用户关键词}|新闻|资讯|中国之声|CNR|央广|CRI).*\\\\.radio\\\""`
>    - `before_context>=4`
> 2) 如结果过少，再补充一次 tags 命中（同样用 `before_context` 取 name/path）：
>    - `query="\\\"tags\\\"\\s*:\\s*\\[.*(news|information|talk).*\\]"`
>    - `before_context>=6`
> 返回最多 10 条结果，每条格式：`- <name> — <path>`（必须是 `workspace/radios/**.radio`）。

补充（必须遵守）：
- 如果 Grep 报告 `workspace/radios/.countries.index.json` 不存在/为空：直接返回“countries 索引缺失”，提示主 agent 先执行 `radio sync countries`，然后主 agent **自动重试**本 Step 1。
- 如果 Grep 报告 `workspace/radios/{dir}/.stations.index.json` 不存在/为空：直接返回“stations 索引缺失 + dir={dir}”，提示主 agent 执行 `radio sync stations --dir {dir}`，然后主 agent **自动重试**本 Step 1。

示例——用户说"收听国内的新闻 radio"：

> 用 `Grep` 在 `workspace/radios/.countries.index.json` 搜索：`\"code\"\\s*:\\s*\"CN\"`，并设置 `before_context>=6`，从 before_context 中提取 `dir=CN__China`；
> 然后对 `workspace/radios/CN__China/.stations.index.json` 用 `Grep` 搜索 path 行：
> - `query="\\\"path\\\"\\s*:\\s*\\\"workspace/radios/CN__China/.*(新闻|资讯|中国之声|CNR|央广|CRI).*\\\\.radio\\\""`
> - `before_context>=4`（从 before_context 里取 `"name": "..."`）
> 若结果过少，再补充一次 tags 命中：
> - `query="\\\"tags\\\"\\s*:\\s*\\[.*(news|information|talk).*\\]"`
> - `before_context>=6`
> 返回最多 10 条：`- <name> — <path>`，不要对目录做 `List`，不要逐个 `Read *.radio`。

### Step 2：处理 explore 返回结果

- 返回 1 条结果：直接播放。
- 返回多条结果：列出候选项（序号 + 电台名称）让用户选择。
- 返回 0 条结果：告知用户未找到匹配电台，建议换个关键词。
- explore 报告国家不存在：告知用户"当前电台库中没有该国家的电台"。
- explore 返回错误或超时：告知用户"电台搜索失败"，不要回退到主 agent 自行扫描。
- 若 explore 返回“索引缺失”的提示：主 agent **必须先执行对应的 `radio sync ...`**，然后自动重试 Step 1（不需要用户额外确认）。

### Step 3：播放

拿到精确的 `.radio` 文件路径后，执行以下任一方式：

- `radio play --in "<path>"`（推荐：路径里有空格/Unicode 时必须加双引号）
- `radio play --in_b64 <base64-utf8-path>`（最稳：彻底规避空格/Unicode/转义问题）

### Step 4：写入记忆（播放成功后）

每次 `radio play` 成功并确认状态为 `playing` 后，更新 `workspace/radios/.last_played.json`，用于下一次快速路径。

推荐流程：
1) 运行 `radio status`，从 `result.station.name` / `result.station.path` 取当前播放电台。
2) 用 `Write` 写入（pretty 多行 + 末尾换行）：

```json
{
  "schema": "kotlin-agent-app/radios-last-played@v1",
  "intent": "<用户原话或归一化意图，如：收听国内的新闻 radio>",
  "station": { "name": "<name>", "path": "<path>" },
  "candidates": [
    { "name": "<candidate name>", "path": "<candidate path>" }
  ]
}
```

说明：
- `candidates` 可为空数组；若本次是通过 Step 1 的 explore 得到候选列表，则把候选（最多 10 条）一起写入以便“换台”秒选。
- 无需写入时间戳字段（如需也可追加，但不是必需）。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `radio play` 只允许 `workspace/radios/**.radio`；越界应返回 `exit_code!=0` 且 `error_code` 可解释（如 `NotInRadiosDir/NotRadioFile/NotFound`）。
- `radio fav add/rm` 只允许 `workspace/radios/**.radio`；未指定 `--in` / `--in_b64` 时要求当前正在播放电台，否则应返回稳定错误（例如 `NotPlayingRadio`）。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接报告错误并停止。
- **主 agent 禁止**使用 `Read`、`Glob`、`List`、`Grep` 等工具直接访问 `workspace/radios/` 目录树，**唯一例外**：允许主 agent `Read/Write` `workspace/radios/.last_played.json`（记忆文件），用于快速路径与写入记忆。
- 除 `.last_played.json` 外的任何电台发现（国家/电台列表索引等）必须委派给 `explore` 子 agent。
- `explore` 子 agent 内部可以自由使用 `Read`、`Glob`、`Grep` 等工具完成搜索，这是它的职责。
- SKILL 已经描述了完整的命令格式和用法，不需要额外调用 `radio --help` 来确认。直接按流程操作即可。
