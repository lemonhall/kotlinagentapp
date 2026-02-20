# v40 Plan：Radio 离线转录（TranscriptTaskManager + CloudAsrClient + `radio transcript`）

## Goal

对 v39 录制产物提供"离线转录"的后台慢任务闭环：

- 为录制会话创建 `transcripts/`，任务/进度以文件形式可见
- 云端 ASR 把每个 `chunk_NNN.ogg` 转为 `chunk_NNN.transcript.json`
- CLI：`radio transcript start|status|list|cancel`
- UI：长按录制会话 → 上下文菜单 → "开始转录" / "强制重新转录"

## 录制会话与转录任务的目录关系

v39 录制产物与 v40 转录任务、v41 翻译产物的完整嵌套结构：

```
workspace/radio_recordings/
  rec_20260219_140000_a1b2c3/           # v39 录制会话
    _meta.json                           # 录制元信息（state/stationId/...）
    _STATUS.md
    chunk_001.ogg                        # 10min 音频切片
    chunk_002.ogg
    ...
    transcripts/                         # v40 转录任务根目录
      _tasks.index.json                  # 该 session 下所有转录任务索引
      tx_abc_ja/                         # v40 转录任务（source_lang=ja）
        _task.json                       # 任务状态/进度
        _STATUS.md
        chunk_001.transcript.json        # v40 转录产物
        chunk_001.translation.json       # v41 翻译产物（若指定了 target_lang）
        chunk_002.transcript.json
        chunk_002.translation.json
      tx_abc_ja2zh/                      # v41 多语言对任务（ja→zh）
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
      tx_abc_ja2en/                      # v41 多语言对任务（ja→en）
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
```

关键规则：

- `transcripts/` 位于录制会话目录内部（`rec_*/transcripts/`）
- 每个转录任务有独立的 `tx_*/` 子目录
- `_tasks.index.json` 位于 `transcripts/` 根，索引该 session 下所有任务
- transcript/translation 文件与源 chunk 同名（只是后缀不同），便于定位对应音频

## PRD Trace

- PRD-0034：REQ-0034-050 / REQ-0034-051 / REQ-0034-052 / REQ-0034-053

## Scope

做（v40）：

- `TranscriptTaskManager`（串行处理 chunk，避免 API 并发爆炸）
- WorkManager 集成：可恢复、可取消
- `_task.json` + `_tasks.index.json` + `_STATUS.md` 落盘与持续更新
- 云端 ASR 抽象层：
  - `CloudAsrClient` 接口（输入 audio file + 语言，输出 segments）
  - `AliyunQwenAsrClient` 作为第一个实现（路线 B：DashScope 异步调用）
  - 上传 `.ogg` chunk 至 DashScope 临时存储获取 `oss://` URL
  - 提交异步转写任务 → 轮询结果
  - 解析异步调用返回的 `transcripts[].sentences[]` 为 segments
  - 错误码归一：AsrNetworkError / AsrRemoteError / InvalidArgs / …
- CLI：`radio transcript ...`（受控输入：sessionId 或录制目录）
- UI：长按录制会话 → 上下文菜单 → "开始转录" / "强制重新转录"
- `.env` 配置文件：默认写好 base_url，用户只需填写 API Key

不做（v40）：

- 不做翻译（v41）
- 不做"实时"转录（v44）
- 不做对 `state=recording`（仍在录制中）的 session 发起转录（明确拒绝，错误码 `SessionStillRecording`）

## 配置文件（`.env`）

ASR 凭据不通过 Settings 配置，而是通过 App 内工作区的 `workspace/radio_recordings/.env` 文件配置（首次启动会自动创建模板文件，且**不会覆盖**用户已填写的真实内容）。用户只需填写 `DASHSCOPE_API_KEY`：

```env
# ===== 阿里云百炼 ASR 配置 =====
# API Key（必填）
# 获取方式：https://help.aliyun.com/zh/model-studio/get-api-key
DASHSCOPE_API_KEY=

# DashScope API Base URL（默认值，一般不需要修改）
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/api/v1

# ASR 模型名称（默认值，录音文件转写专用模型）
ASR_MODEL=qwen3-asr-flash-filetrans
```

代码运行时读取 `workspace/radio_recordings/.env` 加载配置（Debug 构建可回退到 `BuildConfig` 默认值，但不依赖 Settings）。

## ASR 提供商抽象

```kotlin
interface CloudAsrClient {
    /** 转录单个音频文件，返回带时间戳的 segments */
    suspend fun transcribe(
        audioFile: File,
        mimeType: String,       // "audio/ogg"
        language: String?,      // null = 自动检测
    ): AsrResult
}

data class AsrResult(
    val segments: List<AsrSegment>,
    val detectedLanguage: String?,
)

data class AsrSegment(
    val id: Int,
    val startMs: Long,          // 毫秒级时间戳（与阿里云返回对齐）
    val endMs: Long,
    val text: String,
    val emotion: String?,       // neutral/happy/sad/angry/...
)
```

v40 交付 `AliyunQwenAsrClient` 实现；后续版本可插入 `OpenAiWhisperClient`、`VolcEngineAsrClient`，无需改动 TaskManager。

## 阿里云 Qwen-ASR 异步调用实现细节（路线 B）

### 参考文档

- 录音文件识别（Qwen-ASR）API 参考：https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference
- 上传本地文件获取临时 URL：https://help.aliyun.com/zh/model-studio/get-temporary-file-url

### 调用流程（三步）

```
┌─────────────────┐     ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ 1. 上传文件      │ ──▶ │ 2. 提交异步任务   │ ──▶ │ 3. 轮询结果       │ ──▶ │ 4. 解析 segments  │
│ 获取 oss:// URL  │     │ 获取 task_id     │     │ 直到 SUCCEEDED   │     │ 写入 transcript   │
└─────────────────┘     └──────────────────┘     └──────────────────┘     └──────────────────┘
```

### 步骤 1：上传本地 OGG 文件获取临时 URL

异步调用要求 `file_url` 为公网可访问的 URL。本地 chunk 文件需先上传至 DashScope 临时存储空间。

临时 URL 有效期 48 小时，上传凭证接口限流 100 QPS。对于我们串行处理 chunk 的场景完全够用。

```kotlin
// 伪代码：上传文件获取 oss:// URL
suspend fun uploadFileAndGetOssUrl(apiKey: String, modelName: String, file: File): String {
    // 1. 获取上传凭证
    val policyResponse = httpClient.get("https://dashscope.aliyuncs.com/api/v1/uploads") {
        header("Authorization", "Bearer $apiKey")
        header("Content-Type", "application/json")
        parameter("action", "getPolicy")
        parameter("model", modelName)
    }
    val policy = policyResponse.body<UploadPolicyData>()

    // 2. 上传文件到 OSS
    val key = "${policy.uploadDir}/${file.name}"
    httpClient.submitFormWithBinaryData(policy.uploadHost, formData {
        append("OSSAccessKeyId", policy.ossAccessKeyId)
        append("Signature", policy.signature)
        append("policy", policy.policy)
        append("x-oss-object-acl", policy.xOssObjectAcl)
        append("x-oss-forbid-overwrite", policy.xOssForbidOverwrite)
        append("key", key)
        append("success_action_status", "200")
        append("file", file.readBytes(), Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
        })
    })

    // 3. 拼接 oss:// URL
    return "oss://$key"
}
```

对应的 cURL 验证命令：

```bash
# 步骤 1a：获取上传凭证
curl --location 'https://dashscope.aliyuncs.com/api/v1/uploads?action=getPolicy&model=qwen3-asr-flash-filetrans' \
  --header "Authorization: Bearer $DASHSCOPE_API_KEY" \
  --header 'Content-Type: application/json'

# 步骤 1b：上传文件到 OSS（用步骤 1a 返回的字段填充）
curl --location '{data.upload_host}' \
  --form 'OSSAccessKeyId="{data.oss_access_key_id}"' \
  --form 'Signature="{data.signature}"' \
  --form 'policy="{data.policy}"' \
  --form 'x-oss-object-acl="{data.x_oss_object_acl}"' \
  --form 'x-oss-forbid-overwrite="{data.x_oss_forbid_overwrite}"' \
  --form 'key="{data.upload_dir}/chunk_001.ogg"' \
  --form 'success_action_status="200"' \
  --form 'file=@"/path/to/chunk_001.ogg"'

# 拼接结果：oss://{data.upload_dir}/chunk_001.ogg
```

### 步骤 2：提交异步转写任务

```kotlin
// 伪代码：提交异步转写任务
suspend fun submitTranscriptionTask(apiKey: String, fileUrl: String, language: String?): String {
    val response = httpClient.post("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription") {
        header("Authorization", "Bearer $apiKey")
        header("Content-Type", "application/json")
        header("X-DashScope-Async", "enable")
        // 如果 fileUrl 是 oss:// 前缀，需要加这个 header
        header("X-DashScope-OssResourceResolve", "enable")
        setBody(buildJsonObject {
            put("model", "qwen3-asr-flash-filetrans")
            putJsonObject("input") {
                put("file_url", fileUrl)
            }
            putJsonObject("parameters") {
                put("channel_id", buildJsonArray { add(0) })
                put("enable_itn", false)
                put("enable_words", false)  // 句级时间戳即可，字级太重
                if (language != null) {
                    put("language", language)  // "ja", "zh", "en" 等
                }
            }
        })
    }
    val body = response.body<JsonObject>()
    return body["output"]!!.jsonObject["task_id"]!!.jsonPrimitive.content
}
```

对应的 cURL：

```bash
curl --location --request POST 'https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription' \
  --header "Authorization: Bearer $DASHSCOPE_API_KEY" \
  --header "Content-Type: application/json" \
  --header "X-DashScope-Async: enable" \
  --header "X-DashScope-OssResourceResolve: enable" \
  --data '{
    "model": "qwen3-asr-flash-filetrans",
    "input": {
        "file_url": "oss://dashscope-instant/xxx/2026-02-20/xxx/chunk_001.ogg"
    },
    "parameters": {
        "channel_id": [0],
        "language": "ja",
        "enable_itn": false,
        "enable_words": false
    }
  }'
```

返回示例：

```json
{
  "request_id": "92e3decd-0c69-47a8-************",
  "output": {
    "task_id": "8fab76d0-0eed-4d20-************",
    "task_status": "PENDING"
  }
}
```

### 步骤 3：轮询任务结果

```kotlin
// 伪代码：轮询直到完成
suspend fun pollTranscriptionResult(apiKey: String, taskId: String): AsrResult {
    while (true) {
        val response = httpClient.get("https://dashscope.aliyuncs.com/api/v1/tasks/$taskId") {
            header("Authorization", "Bearer $apiKey")
            header("X-DashScope-Async", "enable")
            header("Content-Type", "application/json")
        }
        val body = response.body<JsonObject>()
        val output = body["output"]!!.jsonObject
        val status = output["task_status"]!!.jsonPrimitive.content

        when (status) {
            "SUCCEEDED" -> {
                val result = output["result"]!!.jsonObject
                return parseAsrResult(result)
            }
            "FAILED" -> {
                val code = output["code"]?.jsonPrimitive?.content ?: "UNKNOWN"
                val message = output["message"]?.jsonPrimitive?.content ?: ""
                throw AsrRemoteError(code, message)
            }
            "PENDING", "RUNNING" -> {
                delay(3000)  // 3 秒轮询间隔
            }
            else -> throw AsrRemoteError("UNKNOWN", "Unknown task status: $status")
        }
    }
}
```

对应的 cURL：

```bash
curl --location --request GET 'https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}' \
  --header "Authorization: Bearer $DASHSCOPE_API_KEY" \
  --header "X-DashScope-Async: enable" \
  --header "Content-Type: application/json"
```

SUCCEEDED 时返回示例：

```json
{
  "request_id": "xxx",
  "output": {
    "task_id": "xxx",
    "task_status": "SUCCEEDED",
    "submit_time": "2026-02-20 14:19:31.150",
    "scheduled_time": "2026-02-20 14:19:31.233",
    "end_time": "2026-02-20 14:20:05.678",
    "result": {
      "file_url": "oss://dashscope-instant/xxx/chunk_001.ogg",
      "audio_info": {
        "format": "ogg",
        "sample_rate": 48000
      },
      "transcripts": [
        {
          "channel_id": 0,
          "text": "こんにちは、今日のニュースをお伝えします。",
          "sentences": [
            {
              "sentence_id": 0,
              "begin_time": 0,
              "end_time": 3200,
              "language": "ja",
              "emotion": "neutral",
              "text": "こんにちは、今日のニュースをお伝えします。"
            },
            {
              "sentence_id": 1,
              "begin_time": 3500,
              "end_time": 8100,
              "language": "ja",
              "emotion": "neutral",
              "text": "まず最初のトピックは..."
            }
          ]
        }
      ]
    },
    "task_metrics": { "TOTAL": 1, "SUCCEEDED": 1, "FAILED": 0 }
  },
  "usage": { "seconds": 600 }
}
```

### 结果解析映射

```kotlin
fun parseAsrResult(result: JsonObject): AsrResult {
    val transcripts = result["transcripts"]!!.jsonArray
    val firstChannel = transcripts[0].jsonObject
    val sentences = firstChannel["sentences"]!!.jsonArray

    val segments = sentences.mapIndexed { index, sentence ->
        val s = sentence.jsonObject
        AsrSegment(
            id = s["sentence_id"]?.jsonPrimitive?.int ?: index,
            startMs = s["begin_time"]!!.jsonPrimitive.long,
            endMs = s["end_time"]!!.jsonPrimitive.long,
            text = s["text"]!!.jsonPrimitive.content,
            emotion = s["emotion"]?.jsonPrimitive?.contentOrNull,
        )
    }

    val detectedLanguage = sentences.firstOrNull()
        ?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull

    return AsrResult(segments = segments, detectedLanguage = detectedLanguage)
}
```

### OGG 格式支持

文档未明确列出支持的音频格式白名单，但从返回结构的 `audio_info.format` 字段来看，它会自动检测格式。Analysis 阶段需用一个短 ogg 文件做一次 curl 验证，确认 `audio/ogg; codecs=opus` 能被正确识别。10 分钟时长在异步模式下完全没问题。

## UI 触发入口

### 长按录制会话 → 上下文菜单

在 **Files** 页签中：

- 进入目录：`workspace/radio_recordings/`
- 长按某个 `rec_*` 录制会话目录
- 弹出上下文菜单（BottomSheet 或 PopupMenu），包含：
  - "开始转录"：等同于 `radio transcript start --session <sessionId>`
  - 如果该 session 已有转录结果，菜单项变为"重新转录"，点击后弹确认对话框："已有转录结果，是否覆盖？"
  - 确认后强制重新转录（覆盖已有的 `chunk_NNN.transcript.json`，重置 `_task.json` 进度）
- 对 `state=recording` 的 session，菜单中"开始转录"置灰，tooltip 提示"请先停止录制"

### 转录进度展示

- 转录进行中时，`workspace/radio_recordings/` 下的 `rec_*` 目录行显示进度指示（如 "转录中 3/12"）
- 转录完成后，卡片上显示"已转录"标记

## Acceptance（硬 DoD）

- 前置校验：`radio transcript start --session <sessionId>` 必须校验 `_meta.json` 存在且 `state` 为 `completed`（或 `cancelled`/`failed` 但已有 chunks）；对 `state=recording` 的 session 必须返回 `error_code=SessionStillRecording`。
- 任务可见性：启动任务后必须产生 `{task_id}/_task.json` 和 `{task_id}/_STATUS.md`，`_task.json` 中体现 `totalChunks/transcribedChunks/failedChunks` 进度。
- 单 chunk 原子性：每完成一个 chunk 的转录，必须先写 `chunk_NNN.transcript.json` 再更新 `_task.json`（避免"进度已走但文件缺失"）。
- 可恢复：WorkManager 中断后再次启动时，扫描 `{task_id}/` 目录下已存在的 `chunk_NNN.transcript.json`，跳过已完成的 chunk；`_task.json` 的 `transcribedChunks` 计数基于实际文件存在性重算，不依赖内存状态。
- 取消语义：`radio transcript cancel --task <taskId>` 后，`_task.json` 的 `state=cancelled`，已完成的 chunk transcript 文件保留不删。
- UI 触发：长按录制会话可触发转录，已有结果时可强制覆盖重新转录。
- 配置文件：`.env.example` 存在且包含 `DASHSCOPE_API_KEY` / `DASHSCOPE_BASE_URL` / `ASR_MODEL` 三个字段。
- CLI help：`radio transcript --help` / `radio help transcript` 退出码为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## Files（规划）

- 配置：
  - `.env.example`（模板文件）
  - `app/src/main/java/com/lsl/kotlin_agent_app/config/EnvConfig.kt`（读取 `.env`）
- 转录模块（新包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/*`
    - `TranscriptTaskManager.kt`（串行队列 + WorkManager 调度）
    - `TranscriptTaskStore.kt`（读写 `_task.json` / `_tasks.index.json` / `_STATUS.md`）
    - `TranscriptChunkV1.kt` / `TranscriptTaskV1.kt`（kotlinx.serialization data class）
- ASR 抽象层（建议独立子包，未来 Chat 语音输入可复用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/*`
    - `CloudAsrClient.kt`（接口）
    - `AsrResult.kt` / `AsrSegment.kt`
    - `AliyunQwenAsrClient.kt`（第一个实现：上传 + 提交 + 轮询）
    - `DashScopeFileUploader.kt`（上传文件获取 oss:// URL 的独立工具类）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `transcript` 子命令）
- UI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`（长按 `rec_*` 目录 → 开始/重试转录）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`（从 `_tasks.index.json` 装饰 subtitle 展示进度）
- Tests：
  - `app/src/test/java/...`（MockWebServer + store 读写测试 + argv 门禁 + 恢复逻辑测试 + 上传流程测试）

## `radio transcript` CLI 命令

```
radio transcript start (--session <sessionId> | --dir <recording_dir>) --source_lang ja|auto
radio transcript status --task <taskId>
radio transcript list (--session <sessionId> | --dir <recording_dir>)
radio transcript cancel --task <taskId>
```

`radio transcript list` 粒度为 session 级（列出该 session 下所有转录任务），不提供全局列表。

`radio transcript start` 的 result：

```json
{
  "task_id": "tx_20260219_1600_x1y2z3",
  "session_id": "rec_20260219_140000_a1b2c3",
  "state": "pending",
  "source_language": "ja",
  "total_chunks": 12,
  "message": "Transcript task created, 12 chunks queued"
}
```

前置校验失败时：

```json
{
  "error_code": "SessionStillRecording",
  "error_message": "Session rec_... is still recording. Stop recording first."
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法 |
| `SessionNotFound` | sessionId 对应目录或 `_meta.json` 不存在 |
| `SessionStillRecording` | session `state=recording`，拒绝转录 |
| `SessionNoChunks` | session 存在但无 chunk 文件 |
| `TaskNotFound` | taskId 对应目录或 `_task.json` 不存在 |
| `TaskAlreadyExists` | 该 session 已有相同 source_lang 的进行中任务 |
| `AsrNetworkError` | ASR API 网络不可达 |
| `AsrRemoteError` | ASR API 返回非 2xx 或任务状态 FAILED |
| `AsrParseError` | ASR 返回内容无法解析为 segments |
| `AsrUploadError` | 文件上传至 DashScope 临时存储失败 |
| `AsrTaskTimeout` | 异步任务轮询超时（建议上限 30 分钟） |

## Steps（Strict / TDD）

1) Analysis：固化 `_task.json` 最小字段与错误码集合；用一个短 ogg 文件做 curl 验证阿里云异步调用全流程（上传 → 提交 → 轮询 → 拿到 sentences）；确认 ogg/opus 格式被正确识别；创建 `.env.example`。
2) TDD Red：CLI help + argv 校验 + sessionId 解析门禁 + `SessionStillRecording` / `SessionNoChunks` 前置校验测试。
3) TDD Red：`TranscriptTaskStore` 单测 — 创建任务（`_task.json` + `_STATUS.md`）、更新进度、生成 `_tasks.index.json`、取消后状态变更。
4) TDD Red：`DashScopeFileUploader` 在 MockWebServer 下的上传凭证获取 + 文件上传 + oss:// URL 拼接单测。
5) TDD Red：`AliyunQwenAsrClient` 在 MockWebServer 下的完整流程单测（上传 → 提交 → 轮询 PENDING → RUNNING → SUCCEEDED → 解析 sentences）；含 FAILED / 网络错误 / 超时映射。
6) TDD Red：恢复逻辑单测 — 模拟 `{task_id}/` 下已有部分 `chunk_NNN.transcript.json`，验证 TaskManager 跳过已完成 chunk 并正确重算进度。
7) TDD Green：实现 `TranscriptTaskManager` 串行队列 + WorkManager glue + `CloudAsrClient` 接口 + `AliyunQwenAsrClient` + `DashScopeFileUploader` + `.env` 配置读取，跑到绿。
8) UI：实现长按录制会话上下文菜单 + 转录触发 + 强制覆盖确认对话框 + 进度展示。
9) Verify：UT 全绿；手动用一个短录音 chunk 做真机转录验证（可选但推荐）。

## Risks

- 阿里云临时存储限制：上传凭证接口限流 100 QPS，临时 URL 有效期 48 小时。串行处理 chunk 场景下不会触发限流，但需注意任务恢复时如果距上传已超 48 小时，需重新上传。
- OGG MIME type：文档未明确列出支持格式白名单，Analysis 阶段做一次手动 curl 验证。
- JSON 体积：每 chunk transcript 可能较大（10min 语音 ≈ 数百 sentences），UI/CLI 默认输出必须摘要化（`transcribedChunks/totalChunks`），完整内容走文件浏览。
- 异步任务超时：10 分钟音频的异步转写通常在 1-2 分钟内完成，但需设置轮询上限（建议 30 分钟），超时后标记为 `AsrTaskTimeout`。
- 费用：每个 chunk 按音频时长计费（每秒 25 tokens），10 分钟 ≈ 15000 tokens。测试必须用 MockWebServer；真机仅最小验证。
```

修改后的 plan 主要变更：将 ASR 实现从 OpenAI Whisper 切换为阿里云 Qwen-ASR 异步调用（路线 B），补充了完整的三步调用流程（上传→提交→轮询）及 Kotlin 伪代码和 cURL 示例，增加了 UI 长按触发转录入口，以及 `.env` 配置文件方案。
