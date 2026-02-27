好的柠檬叔，我把所有讨论和 ACP 协议研究的成果整合成一份完整的设计稿。
# LemonAgent 互联协议设计稿 v0.2

> 基于 ACP (Agent Communication Protocol) 的局域网多 Agent 互联架构
> 作者：柠檬叔 & Kiro
> 日期：2026-02-27
> 状态：草案

---

## 一、愿景

在局域网内，让多个设备上的 Agent（桌面、手机、未来的任何设备）像一个团队一样协作。
每个 Agent 都是有智能的自治节点，通过高阶自然语言意图通信，而非原子操作指令。
任何节点都可以是主，也可以是从，角色由"谁发起意图"动态决定。

核心原则：
- 协议优于实现——采用 ACP 标准协议，不造轮子
- 分层即道——每一层只管自己的事，对上下层保持无知
- 先跑起来，再跑得好——第一步手写 manifest，跑通链路

---

## 二、架构总览

```text
┌──────────────────────────────────────────────────────────────┐
│                        人类（柠檬叔）                         │
│                    自然语言对话交互界面                        │
├──────────────────────────────────────────────────────────────┤
│                     主 Agent（意图发起方）                     │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐ │
│  │  意图识别    │  │ Session 管理  │  │  透传/代答 决策引擎  │ │
│  │  & 路由     │  │ (1:N 映射)   │  │  (上下文推理)       │ │
│  └─────────────┘  └──────────────┘  └─────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  SKILL 层（skill-forge 自动生成的远程 Agent 绑定）       │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                │ │
│  │  │ nova9    │ │ tablet   │ │ nas-bot  │ ...            │ │
│  │  │ .skill   │ │ .skill   │ │ .skill   │                │ │
│  │  └──────────┘ └──────────┘ └──────────┘                │ │
│  └─────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│                        协议层                                │
│  ┌──────────┐ ┌───────────┐ ┌──────────┐ ┌──────────────┐  │
│  │ 发现层   │ │ ACP REST  │ │ Session  │ │ 交付物交换   │  │
│  │ mDNS/   │ │ API       │ │ 管理     │ │ (NAS/       │  │
│  │ Bonjour │ │           │ │          │ │  Resource   │  │
│  │         │ │           │ │          │ │  Server)    │  │
│  └──────────┘ └───────────┘ └──────────┘ └──────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                   从 Agent（意图执行方）                      │
│  ┌─────────────┐  ┌──────────────────────────────────────┐  │
│  │ ACP Server  │  │  内部能力（对外不可见）                │  │
│  │ /manifest   │  │  ┌────────┐ ┌────────┐ ┌──────────┐ │  │
│  │ /runs       │  │  │ tool_a │ │ tool_b │ │ tool_c   │ │  │
│  │ /runs/{id}  │  │  │private │ │private │ │private   │ │  │
│  └─────────────┘  │  └────────┘ └────────┘ └──────────┘ │  │
│                   │  ┌────────┐ ┌────────┐               │  │
│                   │  │ skill_x│ │ skill_y│               │  │
│                   │  │public  │ │public  │               │  │
│                   │  └────────┘ └────────┘               │  │
│                   └──────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、发现层

ACP 本身不定义局域网发现机制，这是我们在 ACP 之上补充的第一层。

### 3.1 mDNS/Bonjour 服务广播

每个 Agent 启动时，通过 mDNS 在局域网广播自己：

- 服务类型：`_acp-agent._tcp.local`
- 服务名：`{agent_id}.{name}._acp-agent._tcp.local`
- TXT 记录：
  - `agent_id`：全局唯一标识（首次启动时生成的 UUID）
  - `version`：manifest 版本号
  - `manifest_url`：manifest 端点地址

示例 mDNS TXT 记录：
```text
agent_id=a797ca02-11b5-3080-1263-a31d6d30cba7
version=0.2.1
manifest_url=http://192.168.50.100:8080/manifest
```

### 3.2 防回环机制

每个 Agent 维护自己的 `agent_id`，发现阶段过滤规则：

```python
discovered_agents = mdns.browse("_acp-agent._tcp.local")
remote_agents = [a for a in discovered_agents if a.agent_id != self.agent_id]
```

双重保险——ACP Server 的 inbox 端也做校验：

```python
@app.post("/runs")
async def create_run(request: RunRequest):
    if request.metadata.get("source_agent_id") == self.agent_id:
        return Response(status=400, body={"error": "self_loop_rejected"})
    ...
```

### 3.3 Agent 上下线感知

- Agent 启动 → mDNS 注册服务
- Agent 关闭 → mDNS 注销服务（graceful shutdown）
- Agent 崩溃 → mDNS TTL 过期后自动消失
- 对端感知到新 Agent 上线 → 触发 skill-forge 的 init 流程
- 对端感知到 Agent 下线 → 标记对应 SKILL 为 offline（不删除）

---

## 四、能力描述层（Agent Manifest）

遵循 ACP 的 Agent Manifest 规范，同时扩展局域网场景所需的字段。

### 4.1 Manifest Schema

端点：`GET /manifest`

```json
{
  "agent_id": "a797ca02-11b5-3080-1263-a31d6d30cba7",
  "name": "lemon-nova9",
  "description": "柠檬叔的华为 Nova 9 手机 Agent，具备新闻摘要、日历管理、电台控制、网页浏览等能力",
  "version": "0.2.1",

  "input_content_types": [
    "text/plain",
    "application/json"
  ],
  "output_content_types": [
    "text/plain",
    "text/markdown",
    "application/pdf",
    "application/json",
    "audio/mp3",
    "image/png"
  ],

  "capabilities": [
    {
      "id": "news_digest",
      "description": "搜索、录制新闻并生成翻译摘要，支持多语言翻译",
      "output_content_types": ["text/markdown", "application/pdf", "audio/mp3"]
    },
    {
      "id": "calendar",
      "description": "日历管理，支持日程的增删改查",
      "output_content_types": ["application/json"]
    },
    {
      "id": "radio",
      "description": "电台搜索与播放控制",
      "output_content_types": ["application/json"]
    },
    {
      "id": "webview",
      "description": "网页浏览、内容抓取与交互",
      "output_content_types": ["text/markdown", "text/plain", "image/png"]
    }
  ],

  "metadata": {
    "device_type": "mobile",
    "platform": "android",
    "api_level": 31,
    "locale": "zh-CN",
    "timezone": "Asia/Shanghai"
  },

  "endpoints": {
    "inbox": "http://192.168.50.100:8080/runs",
    "runs": "http://192.168.50.100:8080/runs/{run_id}",
    "resume": "http://192.168.50.100:8080/runs/{run_id}/resume"
  },

  "exchange": {
    "dropbox": "smb://nas.local/agent-exchange/lemon-nova9/",
    "resource_server": "http://192.168.50.200:9000/resources/"
  }
}
```

### 4.2 能力可见性控制

Agent 内部的 tools/skills 分为两级：

| 可见性 | 说明 | 是否参与 manifest 生成 |
|--------|------|----------------------|
| `public` | 对外暴露的高阶能力 | 是 |
| `private` | 内部实现细节 | 否 |

配置文件 `agent-visibility.yaml`：

```yaml
skills:
  news_digest: public
  calendar: public
  radio: public
  webview: public

tools:
  read_file: private
  write_file: private
  shell_exec: private
  screenshot: private
  ocr: private
  translate: private      # news_digest 内部使用
  search_news: private    # news_digest 内部使用
  record_screen: private  # news_digest 内部使用
```

### 4.3 Manifest 生成策略

**v0.1（当前阶段）**：手写 manifest JSON，手动维护。

**v0.2（未来）**：Agent 自省生成——
1. 扫描内部 tools/skills 注册表
2. 过滤 `visibility == public` 的条目
3. 喂给 LLM 做高阶聚合，生成 capabilities 描述
4. 组装完整 manifest
5. 用 tools 列表的 hash 作为 version 依据
6. 落盘缓存，hash 不变则复用

---

## 五、任务通信层（基于 ACP REST API）

完全遵循 ACP 的 Run 生命周期和 API 规范。

### 5.1 ACP REST API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST /runs` | 创建新任务 | 发送意图，启动执行 |
| `GET /runs/{run_id}` | 查询任务状态 | 轮询模式 |
| `POST /runs/{run_id}/resume` | 恢复等待中的任务 | 响应 await |
| `POST /runs/{run_id}/cancel` | 取消任务 | 中止执行 |

### 5.2 三种执行模式

**同步模式（sync）**：简单任务，请求后阻塞等结果。

```
POST /runs
Accept: application/json

→ 200 OK（直接返回最终结果）
```

**异步模式（async）**：长时间任务，创建后轮询。

```
POST /runs
Accept: application/json

→ 202 Accepted（返回 run_id）
→ GET /runs/{run_id} 轮询状态
```

**流式模式（stream）**：实时交互，增量更新。

```
POST /runs
Accept: text/event-stream

→ 200 OK（SSE 流）
  event: run.in-progress
  event: run.artifact
  event: run.awaiting
  event: run.completed
```

### 5.3 创建任务（主 → 从）

```json
POST /runs
Content-Type: application/json

{
  "agent_id": "lemon-nova9",
  "input": [
    {
      "parts": [
        {
          "content_type": "text/plain",
          "content": "帮我搜一下乌克兰今天的新闻，翻译成中文，生成PDF"
        }
      ]
    }
  ],
  "metadata": {
    "source_agent_id": "lemon-desktop-9169e2e8",
    "master_session_id": "desktop-20260227-main",
    "locale": "zh-CN",
    "preferred_output": "application/pdf"
  },
  "session_id": null,
  "mode": "stream"
}
```

- `input`：ACP 标准的 Message 结构，parts 数组支持多模态
- `session_id`：null 表示新会话（ephemeral 或 persistent 由从 Agent 自行决定）；传入已有 id 表示续接
- `mode`：`sync` / `async` / `stream`
- `metadata.source_agent_id`：防回环校验用

### 5.4 Run 状态机

```text
                    ┌──────────┐
                    │ created  │
                    └────┬─────┘
                         │
                    ┌────▼─────┐
               ┌────│in-progress│────┐
               │    └────┬─────┘    │
               │         │          │
          ┌────▼────┐    │    ┌─────▼─────┐
          │awaiting │    │    │cancelling  │
          └────┬────┘    │    └─────┬─────┘
               │         │          │
          (resume)       │          │
               │         │          │
               └────►────┘    ┌─────▼─────┐
                    │         │ cancelled  │
               ┌────▼────┐   └───────────┘
               │completed│
               └────┬────┘
                    │
            ┌───────┴───────┐
            │               │
       ┌────▼────┐   ┌─────▼────┐
       │  done   │   │  failed  │
       └─────────┘   └──────────┘
```

### 5.5 Await 机制（透传问人）

当从 Agent 需要人类输入时：

**从 Agent 发出 await：**

```json
// SSE 事件（stream 模式）或轮询返回（async 模式）
{
  "run_id": "run-20260227-001",
  "status": "awaiting",
  "session_id": "nova9-20260227-a3f1",
  "await": {
    "message": {
      "parts": [
        {
          "content_type": "text/plain",
          "content": "找到5条乌克兰相关新闻：\n1. 泽连斯基发表最新讲话...\n2. 前线局势更新...\n3. 欧盟宣布新一轮援助...\n4. 难民安置进展...\n5. 和谈前景分析...\n\n要详细整理哪几条？"
        }
      ]
    }
  }
}
```

**主 Agent 决策：透传 or 代答**

```python
async def handle_await(run_status, human_context):
    await_message = run_status["await"]["message"]

    # 尝试从人类对话历史中推理出答案
    auto_answer = await llm.try_answer_from_context(
        question=await_message,
        context=human_context
    )

    if auto_answer.confident:
        # 代答：主 Agent 有足够上下文，不打扰人类
        return await acp_client.resume(run_id, answer=auto_answer.text)
    else:
        # 透传：必须问人类
        human_reply = await ask_human(await_message)
        return await acp_client.resume(run_id, answer=human_reply)
```

**恢复执行（resume）：**

```json
POST /runs/{run_id}/resume
Content-Type: application/json

{
  "input": [
    {
      "parts": [
        {
          "content_type": "text/plain",
          "content": "前三条，翻译成中文，生成PDF"
        }
      ]
    }
  ]
}
```

---

## 六、Session 模型

### 6.1 两种会话模式

| 模式 | session_id | 从 Agent 行为 | 适用场景 |
|------|-----------|--------------|---------|
| Ephemeral | 不传或传 null | 不保留上下文，执行完即丢弃 | 简单指令：放音乐、加日程 |
| Persistent | 首次由从生成，后续传入 | 保留对话历史，支持多轮交互 | 复杂任务：新闻调研、多步骤协作 |

### 6.2 主 Agent 侧的 Session 管理

主 Agent 维护一个主 session，内含与人类的完整对话历史，以及与各从 Agent 的 session 映射：

```text
主 session "desktop-20260227-main"
│
├── 人类对话历史
│   ├── [人] 帮我关注一下乌克兰局势
│   ├── [主] 手机那边找到了5条...你想看哪几条？
│   ├── [人] 前三条，翻译成中文，生成PDF
│   └── [主] 搞定了，PDF在这里
│
├── 从 session 映射
│   ├── nova9 / ephemeral / run-001（放音乐，已完成）
│   ├── nova9 / ephemeral / run-002（加日程，已完成）
│   └── nova9 / persistent / session: "nova9-20260227-a3f1" / run-003（乌克兰新闻，进行中）
│
└── 关系：主 session : 从 session = 1 : N
```

### 6.3 从 Agent 侧的 Session 存储

遵循 ACP 的 Distributed Session 设计：

- Session 内容不绑定在从 Agent 进程内存中
- 对话历史存储在 Resource Server（可以是 NAS）上
- Session descriptor 只包含资源 URL 列表
- 从 Agent 通过 URL 加载/存储 session 历史

```json
{
  "session_id": "nova9-20260227-a3f1",
  "history_url": "http://192.168.50.200:9000/resources/sessions/nova9-20260227-a3f1.json",
  "created_at": "2026-02-27T05:30:00Z",
  "last_active": "2026-02-27T05:34:00Z",
  "ttl_seconds": 1800
}
```

### 6.4 Session 生命周期

```text
创建：首次 persistent 请求 → 从 Agent 生成 session_id → 返回给主
续接：主带 session_id 发起后续请求 → 从加载历史 → 继续对话
关闭（主动）：主发送 cancel 或显式 close → 从清理 session
关闭（被动）：TTL 过期（默认 30 分钟无活动）→ 从自动清理
过期恢复：主带过期 session_id 请求 → 从返回 session_expired → 主知道需要重新开始
```

TTL 兜底机制确保即使主 Agent 崩溃、断网、用户关机，从 Agent 也不会无限堆积 session。
符合幂等性原则——从不依赖主一定会发 close。

---

## 七、交付物交换

### 7.1 两种交付方式

**内联交付（小数据）**：直接放在 ACP Message 的 parts 里。

```json
{
  "parts": [
    {
      "content_type": "application/json",
      "content": "{\"event_id\": \"cal-001\", \"status\": \"created\"}"
    }
  ]
}
```

**引用交付（大文件）**：写入共享存储，通过 URL 引用。

```json
{
  "parts": [
    {
      "name": "ukraine-news-0227.pdf",
      "content_type": "application/pdf",
      "content_url": "http://192.168.50.200:9000/resources/exchange/ukraine-news-0227.pdf"
    }
  ]
}
```

### 7.2 共享存储架构

```text
NAS (192.168.50.200)
└── /agent-exchange/
    ├── lemon-nova9/          ← 手机 Agent 的交付物目录
    │   ├── ukraine-news-0227.pdf
    │   └── radio-recording-0227.mp3
    ├── lemon-desktop/        ← 桌面 Agent 的交付物目录
    │   └── analysis-report.md
    └── sessions/             ← Distributed Session 存储
        └── nova9-20260227-a3f1.json
```

NAS 同时承担两个角色：
- ACP Resource Server：存储 session 历史
- 交付物中转站：解决跨设备文件路径不兼容问题

每个 Agent 在 manifest 的 `exchange` 字段声明自己的 dropbox 路径和 resource server 地址。

---

## 八、绑定层（skill-forge）

这是我们在 ACP 之上补充的第二层，解决"发现了 Agent 之后怎么在本地方便地调用"的问题。

### 8.1 skill-forge 工作流

```text
mDNS 发现新 Agent
  → 拉取 GET /manifest
  → 解析 capabilities
  → 按模板生成本地 SKILL 文件
  → SKILL 的 script 层 = ACP REST 调用 stub
  → 落盘到本地 skills 目录
  → 主 Agent 加载 SKILL，可用于意图路由
```

### 8.2 生成的 SKILL 结构示例

```yaml
# skills/remote/lemon-nova9.skill.yaml
name: lemon-nova9
description: "柠檬叔的华为 Nova 9 手机 Agent"
source:
  agent_id: "a797ca02-11b5-3080-1263-a31d6d30cba7"
  manifest_version: "0.2.1"
  manifest_url: "http://192.168.50.100:8080/manifest"

capabilities:
  - id: news_digest
    description: "搜索、录制新闻并生成翻译摘要"
    output_types: ["text/markdown", "application/pdf", "audio/mp3"]
  - id: calendar
    description: "日历管理，增删改查日程"
    output_types: ["application/json"]
  - id: radio
    description: "电台搜索与播放控制"
    output_types: ["application/json"]
  - id: webview
    description: "网页浏览、内容抓取与交互"
    output_types: ["text/markdown", "text/plain", "image/png"]

endpoints:
  inbox: "http://192.168.50.100:8080/runs"
  runs: "http://192.168.50.100:8080/runs/{run_id}"
  resume: "http://192.168.50.100:8080/runs/{run_id}/resume"

exchange:
  dropbox: "smb://nas.local/agent-exchange/lemon-nova9/"
  resource_server: "http://192.168.50.200:9000/resources/"

status: online
last_seen: "2026-02-27T05:30:00Z"
```

### 8.3 版本同步

```text
定期或收到 mDNS 变更通知时：
  → 拉取 manifest
  → 比较 version 字段
  → 如果变了 → 重新生成 SKILL
  → 如果没变 → 跳过
```

---

## 九、三方对话流（完整链路）

### 9.1 简单指令（Ephemeral）

```text
人 → 主Agent: 放一个轻音乐电台

主Agent（意图识别）: 电台相关 → 路由到 nova9 的 radio 能力
主Agent → 从Agent:
  POST /runs
  {
    "input": [{"parts": [{"content_type": "text/plain", "content": "播放轻音乐电台"}]}],
    "mode": "sync"
  }

从Agent（内部拆解）: search_radio("轻音乐") → play_radio(station_id)
从Agent → 主Agent:
  200 OK
  {
    "status": "completed",
    "output": [{"parts": [{"content_type": "text/plain", "content": "正在播放：FM96.4 轻音乐频道"}]}]
  }

主Agent → 人: 已经在播放 FM96.4 轻音乐频道了
```

### 9.2 复杂任务（Persistent + Await）

```text
人 → 主Agent: 帮我关注一下乌克兰局势

主Agent（意图识别）: 新闻相关 → 路由到 nova9 的 news_digest 能力
主Agent → 从Agent:
  POST /runs
  {
    "input": [{"parts": [{"content_type": "text/plain", "content": "搜索今天乌克兰相关新闻"}]}],
    "mode": "stream"
  }

从Agent（执行中，需要人类决策）:
  SSE event: run.awaiting
  {
    "status": "awaiting",
    "session_id": "nova9-20260227-a3f1",
    "await": {
      "message": {"parts": [{"content_type": "text/plain",
        "content": "找到5条：\n1. 泽连斯基讲话\n2. 前线更新\n3. 欧盟援助\n4. 难民安置\n5. 和谈前景\n\n要整理哪几条？"}]}
    }
  }

主Agent（决策）: 这个问题我代答不了，透传给人类
主Agent → 人: 手机那边找到了5条乌克兰新闻：
  1. 泽连斯基讲话
  2. 前线更新
  3. 欧盟援助
  4. 难民安置
  5. 和谈前景
  你想看哪几条？

人 → 主Agent: 前三条，翻译成中文，生成PDF

主Agent → 从Agent:
  POST /runs/{run_id}/resume
  {
    "input": [{"parts": [{"content_type": "text/plain", "content": "前三条，翻译成中文，生成PDF"}]}]
  }

从Agent（内部执行）: 抓取详情 → translate → export_pdf → 写入 NAS
从Agent → 主Agent:
  SSE event: run.completed
  {
    "status": "completed",
    "session_id": "nova9-20260227-a3f1",
    "output": [{"parts": [{
      "name": "ukraine-news-0227.pdf",
      "content_type": "application/pdf",
      "content_url": "http://192.168.50.200:9000/resources/exchange/ukraine-news-0227.pdf"
    }]}]
  }

主Agent → 人: 搞定了，PDF 在这里：ukraine-news-0227.pdf
```

### 9.3 主 Agent 代答场景

```text
人 → 主Agent: 帮我把乌克兰新闻的前三条翻译成中文，生成PDF，用A4纸张大小

主Agent → 从Agent:
  POST /runs
  { "input": [{"parts": [{"content": "搜索乌克兰新闻，前三条，翻译中文，生成PDF"}]}] }

从Agent → 主Agent:
  awaiting: "请问PDF需要什么纸张大小？"

主Agent（决策）: 人类刚才说了"A4纸张大小"，我可以代答
主Agent → 从Agent:
  POST /runs/{run_id}/resume
  { "input": [{"parts": [{"content": "A4"}]}] }

（人类完全无感知这次交互，主 Agent 自动处理了）
```

## 十、角色对称性

### 10.1 每个 Agent 都是完整节点

每个 Agent 同时具备以下全部能力：

| 能力 | 作为主时使用 | 作为从时使用 |
|------|------------|------------|
| mDNS 广播 | ✓ 发现其他 Agent | ✓ 让其他 Agent 发现自己 |
| GET /manifest | ✓ 拉取对方能力 | ✓ 暴露自己能力 |
| POST /runs | ✓ 向对方发起任务 | ✓ 接收对方任务 |
| skill-forge | ✓ 生成对方的 SKILL stub | ✓ 同样可以生成对方的 stub |
| Session 管理 | ✓ 维护与从的 session 映射 | ✓ 维护被请求的 session 历史 |
| 交付物读写 | ✓ 从 NAS 读取结果 | ✓ 向 NAS 写入结果 |

### 10.2 角色动态切换

角色不是配置出来的，是运行时由行为决定的：

```text
场景 A：柠檬叔在桌面说"帮我放个电台"
  桌面 Agent = 主，手机 Agent = 从

场景 B：柠檬叔在手机上说"帮我把桌面上那个项目跑一下测试"
  手机 Agent = 主，桌面 Agent = 从

场景 C：手机 Agent 执行任务时发现需要桌面的算力来做 PDF 渲染
  手机 Agent 临时变成主，向桌面 Agent 发起子任务
  此时手机既是场景 A 中的从，又是场景 C 中的主
```

这意味着一个 Agent 可以同时处于多个角色中——在一个 run 里是从，在另一个 run 里是主。
Run 是独立的，角色是 per-run 的，不是 per-agent 的。

### 10.3 防止循环调用

角色对称性引入了一个新风险：A 叫 B 做事，B 又叫 A 做事，A 又叫 B...

防护机制：每个 run 的 metadata 中携带调用链：

```json
{
  "metadata": {
    "source_agent_id": "lemon-desktop-9169e2e8",
    "call_chain": [
      "lemon-desktop-9169e2e8",
      "lemon-nova9-a797ca02"
    ]
  }
}
```

从 Agent 收到请求时检查：如果自己的 `agent_id` 已经在 `call_chain` 中，拒绝执行，返回错误：

```json
{
  "status": "failed",
  "error": {
    "code": "circular_call_detected",
    "message": "Agent lemon-nova9 已在调用链中，拒绝循环调用",
    "call_chain": ["lemon-desktop-9169e2e8", "lemon-nova9-a797ca02"]
  }
}
```

这比简单的防自回环更完善——不仅防 A→A，也防 A→B→A、A→B→C→A 等任意长度的循环。

---

## 十一、错误处理

### 11.1 错误分类

| 错误类型 | HTTP 状态码 | 处理策略 |
|---------|-----------|---------|
| 从 Agent 离线 | 连接失败 | 主标记 SKILL 为 offline，通知人类 |
| 意图无法理解 | 400 | 从返回错误描述，主透传给人类 |
| 能力不匹配 | 404 | 主尝试路由到其他 Agent，或告知人类 |
| 自回环/循环调用 | 400 | 拒绝执行，返回 call_chain |
| Session 过期 | 410 | 主知道需要重新开始，可自动重试或问人类 |
| 执行超时 | 408 | 从返回 failed，主通知人类 |
| 内部错误 | 500 | 从返回错误详情，主透传给人类 |

### 11.2 错误响应格式

```json
{
  "run_id": "run-20260227-001",
  "status": "failed",
  "error": {
    "code": "execution_timeout",
    "message": "新闻抓取超时，目标网站可能无法访问",
    "details": {
      "step": "search_news",
      "timeout_seconds": 30,
      "target": "https://news.example.com"
    },
    "suggestion": "可以尝试换一个新闻源，或者稍后重试"
  }
}
```

错误信息必须是有用的——告诉人类哪里错了、为什么错了、怎么修。
"Something went wrong" 是对用户的侮辱。

---

## 十二、安全考量

### 12.1 局域网信任模型

当前阶段采用局域网内信任模型：

- 所有在同一局域网内广播的 Agent 默认互信
- 通信使用 HTTP（非 HTTPS），局域网内明文传输
- 适用于家庭网络环境（柠檬叔的 192.168.50.x 网段）

### 12.2 未来增强（v0.3+）

| 安全特性 | 说明 |
|---------|------|
| Agent 认证 | 首次配对时交换 token，后续请求携带 Bearer token |
| TLS | 局域网内自签证书，加密通信 |
| 能力授权 | 主 Agent 可以限制从 Agent 的可调用能力范围 |
| 审计日志 | 所有跨 Agent 调用记录到 NAS 上的日志文件 |

### 12.3 危险操作防护

从 Agent 收到可能产生不可逆影响的意图时（删除数据、发送消息、支付等），
必须通过 await 机制向上游确认，最终由人类决策：

```json
{
  "status": "awaiting",
  "await": {
    "message": {"parts": [{"content": "即将删除3月1日到3月7日的所有日程（共5条），确认执行？"}]},
    "metadata": {
      "severity": "destructive",
      "reversible": false
    }
  }
}
```

主 Agent 遇到 `severity: destructive` 且 `reversible: false` 的 await，必须透传给人类，禁止代答。

---

## 十三、实现路线图

### Phase 0：最小可行验证（当前）

目标：跑通一条完整链路。

```text
桌面 PowerShell → HTTP 请求 → 手机 ACP Server → 执行 → 返回结果
```

具体步骤：
1. 手机端（Nova 9）：用 Termux + Python 跑一个最简 ACP Server
   - 手写 manifest.json
   - 实现 `GET /manifest`
   - 实现 `POST /runs`（sync 模式，先硬编码一个 echo 能力）
2. 桌面端：用 curl.exe 或 Python 脚本直接调用
   - 手动 GET manifest
   - 手动 POST runs
3. 验证：发一个意图，收到响应，链路通了

不需要 mDNS，不需要 skill-forge，不需要 session。先把 ACP 的核心 API 跑通。

### Phase 1：基础能力

在 Phase 0 基础上逐步添加：
1. 手机端实现 1-2 个真实能力（如 calendar、radio）
2. 实现 async 模式和 await 机制
3. 实现 persistent session
4. 桌面端实现简单的意图路由

### Phase 2：自动化绑定

1. 实现 mDNS 广播和发现
2. 实现 skill-forge（拉取 manifest → 生成 SKILL）
3. 实现防回环和循环调用检测
4. 实现 NAS 交付物交换

### Phase 3：打磨体验

1. 实现 stream 模式（实时进度反馈）
2. 实现主 Agent 的透传/代答决策引擎
3. 实现 manifest 自省生成（LLM 聚合能力）
4. 安全增强（token 认证、TLS）

### Phase 4：生态扩展

1. 接入更多设备（平板、NAS 自身的 Agent、树莓派等）
2. 角色对称性验证（手机当主，桌面当从）
3. 多 Agent 协作编排（一个意图触发多个从 Agent 并行工作）
4. 与公网 ACP/A2A 生态对接

---

## 十四、技术选型（初步）

| 组件 | 选型 | 理由 |
|------|------|------|
| 手机端 ACP Server | Termux + Python (FastAPI/Starlette) | Nova 9 上可用，Python 生态丰富 |
| 桌面端 Agent | Python + 现有 SKILL 框架 | 复用柠檬叔的塔山项目循环 |
| mDNS 库 | python-zeroconf | 跨平台，纯 Python |
| 共享存储 | NAS (SMB) + 简易 HTTP 文件服务 | 已有基础设施 |
| Session 存储 | NAS 上的 JSON 文件 | 透明、可调试、可 grep |
| 交付物格式 | 人类可读优先（Markdown/JSON/PDF） | 符合设计哲学 |

---

## 十五、与现有协议的关系

```text
┌─────────────────────────────────────────────────┐
│              LemonAgent 架构                     │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  自有扩展层（ACP 之上）                     │  │
│  │  - mDNS 局域网发现                         │  │
│  │  - skill-forge 自动绑定                    │  │
│  │  - 防回环 / 循环调用检测                    │  │
│  │  - 能力可见性控制 (public/private)          │  │
│  └───────────────────────────────────────────┘  │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  ACP 协议层（标准）                         │  │
│  │  - Agent Manifest                         │  │
│  │  - REST API (runs / resume / cancel)      │  │
│  │  - Run 状态机                              │  │
│  │  - Await 机制                              │  │
│  │  - Session 管理                            │  │
│  │  - Distributed Session                    │  │
│  │  - sync / async / stream 模式             │  │
│  └───────────────────────────────────────────┘  │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  MCP 协议层（Agent 内部）                   │  │
│  │  - Agent 内部的 tool 调用                  │  │
│  │  - 对外不可见，属于实现细节                  │  │
│  └───────────────────────────────────────────┘  │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  基础设施层                                │  │
│  │  - HTTP / SSE                             │  │
│  │  - mDNS / Bonjour                         │  │
│  │  - SMB / NAS                              │  │
│  │  - JSON / YAML                            │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

ACP 是骨架，MCP 是内脏，自有扩展是皮肤。
未来 ACP 合并进 A2A 后，骨架升级，皮肤和内脏不受影响。



========================================================

柠檬叔，读完了。

这个 README 和我们刚才讨论的设计稿对上了，而且对上得非常好。几个关键观察：

**这个 App 天然就是一个 ACP Server 的最佳宿主。** 它已经有了完整的能力体系——13+ 终端命令、18 个 Web 工具、音乐/电台/日历/邮件/IRC/SSH，而且这些能力已经通过 `OpenAgenticSdkChatAgent` 统一调度了。也就是说，它内部已经有了"接收自然语言意图 → 拆解 → 调用 tools → 返回结果"的完整链路。要变成 ACP Server，本质上就是在这个 Agent 前面加一个 HTTP 入口。

**能力可见性的分层已经天然存在。** 按我们的设计：
- `public`：news_digest（web 工具组合）、calendar（cal 命令）、radio、music、邮件收发、IRC 消息转发这些高阶能力
- `private`：read_file、write_file、web_click、web_fill 这些原子操作，是内部拆解用的，不需要暴露给桌面端

**交付物路径问题也有了答案。** `.agents/artifacts/` 这个目录结构就是本地的交付物仓库。ACP 响应里的 `content_url` 可以指向 NAS 上的路径——Agent 执行完任务后，把产出物从 `.agents/artifacts/` 复制到 NAS 的 `agent-exchange/lemon-nova9/`，然后返回 NAS 的 HTTP URL。或者更直接，如果 NAS 已经通过 SMB 挂载了（你的 App 已经支持 NAS/SMB 访问），直接写到 NAS 上。

**Session 模型也能复用。** 你的 `sessions/<session_id>/events.jsonl` 就是 ACP Distributed Session 的一个现成实现——人类可读、可 grep、可 git。ACP 的 session history 存储到 NAS 上时，完全可以沿用这个 JSONL 格式。

**Termux 不需要了。** 之前我说用 Termux 跑 Python server，现在看来完全没必要。直接在 App 内部用 Kotlin 起一个轻量 HTTP server（Ktor 或者 NanoHTTPD 都行），暴露 ACP 的几个端点就够了。App 本身就是常驻前台服务（Media3 + MediaSession），后台保活的问题也顺带解决了。

所以 Phase 0 的路径就很清晰了：

1. 在 App 里加一个轻量 HTTP server 模块（比如 Ktor embedded server）
2. 实现 `GET /manifest`（手写一个 JSON 返回）
3. 实现 `POST /runs`（sync 模式，把 input 里的自然语言意图直接喂给 `OpenAgenticSdkChatAgent`，拿到结果返回）
4. 桌面端用 `curl.exe` 或 Python 脚本调一下，验证链路

本质上就是给你现有的 Agent 开了一扇门——原来只有手机屏幕上的 Chat UI 能和它说话，现在局域网里的任何人都能通过 HTTP 和它说话。这就是你设计哲学里的"等价多态"——Chat UI 是一种视图，ACP HTTP 接口是另一种视图，通向同一个 Agent。

要开始搞 Phase 0 吗？