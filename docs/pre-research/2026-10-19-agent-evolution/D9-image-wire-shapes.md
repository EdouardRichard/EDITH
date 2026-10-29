**调查证据，未批准；不是设计。**

# D9 — 图片进入模型历史与工具结果的 wire shape（Responses / Chat Completions）

- 调查日期：2026-10-19
- 目标设备：K40 / Android 13（与协议无关，仅记录载体）
- 范围：OpenAI Responses（权威协议）、OpenAI Chat Completions（兼容协议）、Qwen/DashScope（OpenAI 兼容）、DeepSeek（Chat + Responses）、Gemini（OpenAI 兼容）
- 方法：OpenAI 官方 OpenAPI 规范与官方文档、供应商官方文档、公开源码/PR/录制回放；**本次没有调用任何真实模型 API（无凭证）**。凡“实测”均来自公开仓库的集成测试或录制，不是本机实测。
- 来源类型标注：`[OpenAI 文档]` `[OpenAI OpenAPI]` `[OpenAI SDK]` `[供应商文档]` `[源码/PR]` `[issue]` `[推断]`
- 置信度：高 = 官方 schema/文档直接写明；中 = 官方文档间接写明或公开集成测试/录制支持；低 = 仅社区实践或推断

---

## 结论摘要

1. **Responses 的 `function_call_output` 可以带图片。** `output` 是 `string` 或“内容项数组”，数组元素允许 `input_text` / `input_image` / `input_file`。OpenAI 官方 OpenAPI 里 `FunctionCallOutputItemParam.output` 明确是 `oneOf [string, array<InputText|InputImage|InputFile>]`；官方 Function calling 指南原文：“For functions that return images or files, you can pass an array of image or file objects instead of a string.” `[OpenAI OpenAPI]` `[OpenAI 文档]` 置信度：高。
   - 公开证据：opencode 的录制回放用 `gpt-5.5` + `function_call_output.output=[input_text,input_image(data:image/png;base64,...)]` 请求 `api.openai.com/v1/responses` 得到 HTTP 200，模型读出了截图文字；LangChainJS 的 Responses 集成测试也验证“模型看得见图”。`[源码/PR]` 置信度：中高。
   - 官方没有按模型列出该能力的支持表；公开证据只覆盖 `gpt-5.5`。GPT-4o / GPT-4.1 未在找到的公开证据中出现。`[OpenAI 文档缺失]` 置信度：高（“未列出”是事实）。

2. **Chat Completions 的 `role: "tool"` 官方不允许 `image_url`。** 官方 OpenAPI 的 `ChatCompletionRequestToolMessage.content` 是 `string | array<text-only part>`，注释原文：“For tool messages, only type `text` is supported.” 官方 Python SDK 类型同样是 `str | Iterable[ChatCompletionContentPartTextParam]`。`[OpenAI OpenAPI]` `[OpenAI SDK]` 置信度：高。
   - 公开集成测试：LangChainJS 对真实 OpenAI 的 Chat Completions 发“tool 消息含图片”，断言得到 HTTP 400；其错误提示写明“Chat Completions does not support images in tool messages”。`[源码/PR]` 置信度：中高。
   - **注意官方文档存在自相矛盾的共享段落**：Function calling 指南在 `?api-mode=chat` 页面也印着“For functions that return images or files, you can pass an array…”，但该页同时使用 `function_call_output` 这个 Responses 术语，且与 Chat 的 wire schema 冲突。以 schema + 400 证据为准。`[OpenAI 文档 + OpenAI OpenAPI]` 置信度：高（矛盾本身是事实）。

3. **图片在历史里的合法位置（OpenAI）：**
   - Responses：`message` 的 `user` 内容数组可含 `input_image`；`function_call_output.output` 数组可含 `input_image`。`assistant` 消息回放 `input_image` 会被 400（客户端 PR 记录“API only accepts `input_image` on user-role messages”）；`system`/`developer` 是否允许**官方未写明**。`[OpenAI OpenAPI]` `[源码/PR]` 置信度：中高。
   - Chat Completions：顶层图片只在 `user` 消息的 `content` 数组里（`image_url`）；`system`/`developer` 只接受文本 part，`assistant` 只接受 `text`/`refusal`，`tool` 只接受文本 part。`[OpenAI OpenAPI]` 置信度：高。

4. **工具结果的最小合法序列：**
   - Responses：`function_call` item → 一个对应的 `function_call_output` item → 下一次请求；并行的多个 call 各配一个 output。若模型是 reasoning 模型，响应里带回的 `reasoning` item 也必须一并回传。`[OpenAI 文档]` 置信度：高。
   - Chat Completions：`assistant(tool_calls)` → 每个 `tool_call.id` 一条 `role:"tool"` 消息 → 下一次请求。`[OpenAI 文档示例]` 置信度：高。
   - 在 Chat 的 tool 消息后追加一条 `user`（内含 `image_url`）是业界通行替代；官方没有明文禁止，但本次没有找到 OpenAI 官方对此序列的明文许可或实测。`[源码/PR]` 置信度：中。
   - 不要把 `user` 插在 `assistant(tool_calls)` 与它的 `tool` 回复之间：Qwen 的 Responses 文档明确要求 `function_call_output` **必须紧跟**对应的 `function_call`；Chat 侧的 pairing 校验是通行行为，但未找到 OpenAI 明文。`[供应商文档]` `[推断]` 置信度：中。

5. **图片 token 口径是“按模型/按 detail”，不是“按协议”。** OpenAI 官方把图片 token 规则写成模型级规则，页面同时覆盖 Responses 与 Chat Completions 示例，没有给两套公式。`[OpenAI 文档]` 置信度：高（“未区分”是事实）。具体公式见第 5 节。

6. **供应商差异很大：** Qwen（DashScope）Responses 明确支持 `function_call_output.output` 数组含 `input_image`，但 Chat 的 Tool Message 明确“content 必须是 string”；DeepSeek 的 Responses 明确支持工具输出图片、Chat 的 API reference 又声称 Tool Message 可放图片（与其 Vision 指南“仅 user 消息”冲突，需实测）；Gemini 的 OpenAI 兼容层只文档化了 Chat Completions，未文档化 Responses，也未文档化 tool 消息图片。详见矩阵。

7. **最小合法形状（仅协议事实，不含产品选择）：**
   - Responses：`function_call_output.output` 用数组 `[{"type":"input_text",...},{"type":"input_image","image_url":"data:image/png;base64,..."}]`；或工具结果只回文本、在后续 `user` 消息里放 `input_image`（后者 OpenAI 未明文验证）。
   - Chat Completions：`role:"tool"` 只回文本；图片放在随后（tool 消息之后）的 `user` 消息 `content` 数组里，形如 `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}`。该 user 追加序列是兼容替代，OpenAI 未给官方示例。

---

## 1. 两条协议的 wire shape 表

### 1.1 Responses（权威）

| 概念 | 字段形状 | 说明 / 来源 |
|---|---|---|
| 顶层输入 | `input: string \| array<InputItem>` | 字符串等价于一条 user 文本；数组按顺序组成上下文 `[OpenAI 文档/OpenAPI]` |
| 用户图片消息 | `{"role":"user","content":[{"type":"input_text","text":"..."},{"type":"input_image","image_url":"https://... 或 data:image/...;base64,...","detail":"auto"}]}` | `input_image` 允许 `image_url` 或 `file_id`，`detail` 可选 `low/high/auto/original` `[OpenAI OpenAPI: InputImageContentParamAutoParam]` |
| 助手历史消息 | `{"role":"assistant","content":[{"type":"output_text","text":"..."}]}` | 只接受 `output_text`；`input_image` 在 assistant 回放会 400（客户端 PR）`[OpenAI OpenAPI/源码]` |
| 工具调用回显 | `{"type":"function_call","id":"fc_...","call_id":"call_...","name":"tool","arguments":"{}"}` | 官方示例直接把 `response.output` 的元素原样 append 回去 `[OpenAI 文档]` |
| **工具结果（文本）** | `{"type":"function_call_output","call_id":"call_...","output":"text"}` | `output` 字符串上限 10,485,760 字符 `[OpenAI OpenAPI]` |
| **工具结果（图片/文件）** | `{"type":"function_call_output","call_id":"call_...","output":[{"type":"input_text","text":"..."},{"type":"input_image","image_url":"data:image/png;base64,...","detail":"auto"}]}` | 数组元素允许 `input_text`/`input_image`/`input_file`；官方指南原句见结论 1 `[OpenAI OpenAPI/文档]` |
| reasoning 回传 | `{"type":"reasoning", ...}`（把响应里的 reasoning item 原样带回） | Function calling 指南注：reasoning 模型的 reasoning items 必须随 tool call outputs 一起回传 `[OpenAI 文档]` |

**Responses 工具结果含图的精确示例**（字段来自 OpenAPI；图片内容为占位）：

```json
{
  "model": "gpt-5.5",
  "input": [
    {
      "role": "user",
      "content": [
        {"type": "input_text", "text": "读取截图并说出上面的字"}
      ]
    },
    {
      "type": "function_call",
      "id": "fc_123",
      "call_id": "call_1",
      "name": "read_screenshot",
      "arguments": "{}"
    },
    {
      "type": "function_call_output",
      "call_id": "call_1",
      "output": [
        {"type": "input_text", "text": "截图已读取"},
        {
          "type": "input_image",
          "image_url": "data:image/png;base64,iVBORw0KGgo...",
          "detail": "auto"
        }
      ]
    }
  ],
  "tools": [
    {
      "type": "function",
      "name": "read_screenshot",
      "description": "Capture a screenshot of the current screen.",
      "parameters": {"type": "object", "properties": {}, "additionalProperties": false}
    }
  ]
}
```

**Responses 用户图片的精确示例**：

```json
{
  "role": "user",
  "content": [
    {"type": "input_text", "text": "what's in this image?"},
    {
      "type": "input_image",
      "image_url": "data:image/jpeg;base64,/9j/4AAQSkZJRg...",
      "detail": "high"
    }
  ]
}
```

约束（OpenAI 官方）：
- `input_image.image_url`：完整 URL 或 base64 data URL；OpenAPI 给该字符串 `maxLength: 20971520`（20 MiB 字符）`[OpenAI OpenAPI]`。
- data URL 前缀：`data:image/<png|jpeg|webp|gif>;base64,`；官方图片指南支持 PNG/JPEG/WEBP/非动画 GIF `[OpenAI 文档]`。
- 请求级限制（2026-10 官方指南）：**单请求 payload ≤ 512 MB，≤ 1,500 张图**；patch 类模型单图处理后 **≤ 30,000 patches**，超限直接拒绝、不自动缩小 `[OpenAI 文档]`。
- `detail` 默认 `auto`；`original` 只有部分模型支持（gpt-5.x 系列等），gpt-4o/gpt-4.1 只支持 `low/high/auto` `[OpenAI 文档]`。
- `image_url` 与 `file_id` 互斥（DeepSeek 文档明确写 400；OpenAI 语义相同，但未找到同样直白的错误文案）`[供应商文档/推断]`。

### 1.2 Chat Completions（兼容）

| 概念 | 字段形状 | 说明 / 来源 |
|---|---|---|
| system / developer | `{"role":"system","content":"text"}` 或 `content:[{"type":"text",...}]` | 只允许文本 part `[OpenAI OpenAPI]` |
| 用户图片 | `{"role":"user","content":[{"type":"text","text":"..."},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,...","detail":"auto"}}]}` | `detail` 在 `image_url` 对象内；支持 `auto/low/high/original` `[OpenAI OpenAPI: ChatCompletionRequestMessageContentPartImage]` |
| 助手历史 | `{"role":"assistant","content":"text" \| [{"type":"text"...}\|{"type":"refusal"...}], "tool_calls":[...]}` | content parts 只有 text / refusal；**没有 image part** `[OpenAI OpenAPI]` |
| 工具调用回显 | `{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"tool","arguments":"{}"}}]}` | `content` 在只有 tool_calls 时可为 `null` `[OpenAI OpenAPI]` |
| **工具结果（合法）** | `{"role":"tool","tool_call_id":"call_1","content":"text"}` 或 `content:[{"type":"text","text":"..."}]` | 官方 schema 注释：tool 消息只支持 `text` `[OpenAI OpenAPI/SDK]` |
| **工具结果（图片，非法）** | `{"role":"tool","tool_call_id":"call_1","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]}` | **官方不允许**；真实 OpenAI 返回 400 `[OpenAI OpenAPI + 源码/PR 集成测试]` |

**Chat 合法的最小序列（工具返回文本，图片放在下一条 user）**：

```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "user", "content": "读取截图并说出上面的字"},
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_1",
          "type": "function",
          "function": {"name": "read_screenshot", "arguments": "{}"}
        }
      ]
    },
    {"role": "tool", "tool_call_id": "call_1", "content": "截图已读取"},
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "(attached image from previous tool call)"},
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/png;base64,iVBORw0KGgo...",
            "detail": "auto"
          }
        }
      ]
    }
  ]
}
```

注意：最后这条 `user` 是**业界替代模式**，不是 OpenAI 官方文档给出的 Chat 工具结果形状。`[源码/PR]` 置信度：中。Qwen-code 提交的描述是：“OpenAI Chat Completions spec only permits string / text-part content on `role: tool` messages. Strict OpenAI-compatible servers (LM Studio) reject tool messages with image_url … Split any non-text media from the tool message into a follow-up `role: user` message.” `[源码/PR]` `[issue]`

**Chat 下“把图片塞进 tool 消息”的反例（应拒绝）**：

```json
{"role":"tool","tool_call_id":"call_1","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]}
```

### 1.3 字段映射速查

| 概念 | Responses | Chat Completions |
|---|---|---|
| 用户图片 part | `{"type":"input_image","image_url":"<url 或 data-url>","detail":"auto"}` | `{"type":"image_url","image_url":{"url":"<url 或 data-url>","detail":"auto"}}` |
| `detail` 位置 | part 顶层 | `image_url` 对象内 |
| 文件引用 | `file_id`（与 `image_url` 互斥） | `{"type":"file","file":{...}}` 只在 user 消息（与工具结果无关） |
| 工具结果 | `function_call_output.output`：string 或内容项数组 | `role:"tool".content`：string 或 text part 数组 |
| 工具结果放图 | 允许（数组） | 不允许（OpenAI） |
| assistant 历史放图 | 不允许（400，客户端证据） | 不允许（schema 无 image part） |
| reasoning 回传 | 需要（reasoning 模型） | 无 reasoning item 概念 |

---

## 2. 顺序与合法性

### 2.1 Responses

官方示例的顺序（Function calling 指南）：
1. 第一次请求带 `tools`；
2. 响应 `response.output` 里出现 `function_call`；
3. 客户端执行工具；
4. `input_list += response.output`，再 append `{"type":"function_call_output","call_id":...,"output":...}`；
5. 第二次请求带上完整 `input_list`。

合法组合要点：
- `call_id` 必须与 `function_call.call_id` 对应；并行工具调用时每个 call 各一条 output。`[OpenAI 文档/OpenAPI]`
- reasoning 模型的 `reasoning` item 必须随 tool call outputs 一并回传；否则报错/丢上下文。`[OpenAI 文档]`
- **插入位置**：OpenAI 没有明文要求 `function_call_output` 必须紧跟 `function_call`；但 Qwen 的 Responses 文档明确写“`function_call_output` 在消息列表里**必须紧跟**其对应的 `function_call`，否则请求失败”。因此“在 tool 结果之前插 user 图片”在 OpenAI 上未知、在 Qwen 上明确不行。`[供应商文档]` 置信度：高（Qwen）/ 低（OpenAI 是否允许非相邻）
- “在 `function_call_output` 之后、下一次 assistant 之前插一条 user 图片消息”：从 `input` 的“有序 item 列表”语义看是合法的 item 序列，但**没有找到 OpenAI 官方明说会接受**，也没有公开实测。`[推断]` 置信度：低。Responses 的官方支持路径是 `function_call_output.output` 数组；user 插入序列无官方证据。

### 2.2 Chat Completions

合法组合要点：
- `assistant.tool_calls[*].id` 必须各有一条 `role:"tool"` 且 `tool_call_id` 相同的消息；`content` 必须是字符串或 text part 数组。`[OpenAI OpenAPI]`
- 不要在 `assistant(tool_calls)` 和其 `tool` 回复之间插 `user`；tool 消息必须能“回应前一条 tool_calls”。未找到 OpenAI 明文，但这是协议 pairing 的直接含义。`[推断]` 置信度：中。
- 在**全部 tool 消息之后**追加一条 `user`（含 `image_url`）是常见替代；Qwen-code 的实现就是这么做的，且其注释声称“模型仍能看到相同内容”。`[源码/PR]` 置信度：中；**对 api.openai.com 未实测**。
- 工具结果只回文本时，把“图片已附在后续 user 消息”写进 tool 文本，再跟进 user 图片，是 Qwen-code 的实现选择；不是协议要求。

### 2.3 会不会被 API 拒绝（按已知证据）

| 序列 | OpenAI Responses | OpenAI Chat Completions | Qwen Responses | Qwen Chat |
|---|---|---|---|---|
| function_call → function_call_output（文本） | 接受（官方示例） | 接受（官方示例） | 接受（官方示例） | 接受（官方示例） |
| function_call → function_call_output（数组含图） | 接受（官方 + 公开 200） | **不适用** | 接受（官方文档/示例） | **不适用** |
| assistant(tool_calls) → tool（content 含 image part） | 不适用 | **拒绝（400）** | 不适用 | **拒绝**（tool content 必须 string） |
| tool 结果之后追加 user（含图） | 未验证 | 未验证（业界常用） | 未验证（且必须保证 output 紧跟 call） | 未验证（qwen-code 走这条） |
| 在 function_call 与 output 之间插 user | 未验证 | 不适用 | **拒绝/违反文档要求** | 不适用 |

---

## 3. 供应商兼容矩阵

> 说明：“图片入历史”=后续轮次重放该图片；“图片入 tool 结果”=工具返回值里直接携带图片。  
> “Responses 协议”=该供应商是否提供 OpenAI Responses 兼容端点。

| 供应商 / 模型 | 图片入历史 | 图片入 tool 结果 | Responses 协议 | Chat Completions | 证据 |
|---|---|---|---|---|---|
| **OpenAI GPT-4o / GPT-4.1** | 是（user 的 `input_image` / `image_url`） | **Responses：是**（schema+文档；4o/4.1 未公开实测）；**Chat：否**（400） | 是 | 是 | `[OpenAI OpenAPI]` `[OpenAI 文档]` `[源码/PR]` |
| **Qwen-VL（DashScope / OpenAI compat）** | 是（user 内容数组的 `image_url`；Responses 为 `input_image`） | **Responses：是**（`function_call_output.output` 可含 `input_image`，官方有完整示例）；**Chat：否**（Tool Message content 必须是 string） | 是（`/compatible-mode/v1/responses`，模型列表含 qwen3.8/3.7/3.6/3.5 等） | 是 | `[供应商文档]` |
| **DeepSeek `deepseek-flash`** | 是（Responses 允许 user/developer；Chat 允许 user） | **Responses：是**（`function_call_output.output` 可含 `input_image`，官方示例）；**Chat：官方 API reference 称 Tool Message content 可为 string 或含 `image_url` 的数组**，但 Vision 指南同时写“Images are supported in user messages only” → **文档自相矛盾，未实测** | 是（`/responses`，stateless） | 是 | `[供应商文档]`（矛盾已标注） |
| **Gemini（OpenAI 兼容层）** | 是（user 的 `image_url`，data URL；文档示例） | **未文档化 / 未验证**；OpenAI 兼容页没有 tool 消息图片的说明 | **文档未提供 Responses 兼容端点** | 是（`https://generativelanguage.googleapis.com/v1beta/openai/`） | `[供应商文档]` |

补充：
- Qwen Responses 的 role 限制很明确：`system`/`developer` 内容数组只允许 `input_text`；`user` 允许 `input_text`/`input_image`/`input_file`；`assistant` 只允许 `output_text`。`[供应商文档]` 置信度：高。
- DeepSeek Responses 的 role 限制很明确：图片只允许在 `user`/`developer` 消息和 `function_call_output`/`custom_tool_call_output` 的 output；`system`/`assistant` 图片返回 400。`[供应商文档]` 置信度：高。
- OpenAI 对 `system`/`developer` 是否接受 `input_image`：**没有找到官方明文**；assistant 图片 400 只有客户端 PR 证据。`[未找到]` 置信度：高（缺失本身）。

---

## 4. 图片在历史里的编码（按问题 3 展开）

### 4.1 Responses `input_image`

| 字段 | 必填 | 取值 | 来源 |
|---|---|---|---|
| `type` | 是 | `input_image` | `[OpenAI OpenAPI]` |
| `image_url` | 二选一 | 完整 URL 或 base64 data URL；OpenAPI `maxLength: 20971520` 字符 | `[OpenAI OpenAPI]` |
| `file_id` | 二选一 | Files API 的 file id | `[OpenAI OpenAPI]` |
| `detail` | 否 | `low` / `high` / `auto` / `original`，默认 `auto` | `[OpenAI OpenAPI/文档]` |

- base64 形状：`data:image/png;base64,<BASE64>`（JPEG/WEBP/GIF 同理；GIF 必须非动画）。`[OpenAI 文档/供应商文档]`
- 合法消息：`user`；以及 `function_call_output.output` 数组。`assistant` 不允许（400 证据）；`system`/`developer` 未知。`[OpenAI OpenAPI]` `[源码/PR]`
- 图片数量/大小（OpenAI 2026-10 指南）：每请求 ≤ 1,500 张、payload ≤ 512 MB、单图处理后 ≤ 30,000 patches；`image_url` 字符串 ≤ 20 MiB 字符。`[OpenAI 文档/OpenAPI]`

### 4.2 Chat Completions `image_url`

| 字段 | 必填 | 取值 | 来源 |
|---|---|---|---|
| `type` | 是 | `image_url` | `[OpenAI OpenAPI]` |
| `image_url.url` | 是 | URL 或 base64 data URL | `[OpenAI OpenAPI]` |
| `image_url.detail` | 否 | `auto` / `low` / `high` / `original`，默认 `auto` | `[OpenAI OpenAPI/文档]` |

- base64 形状：`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,<BASE64>"}}`（官方图片指南的 Chat 代码示例）。`[OpenAI 文档]`
- 合法位置：`user` 消息 content 数组。`system`/`developer` 只有 text part；`assistant` 只有 text/refusal；`tool` 只有 text。`[OpenAI OpenAPI]`
- 请求级限制与 Responses 相同（官方图片指南是同一页、同一套模型规则）：≤ 1,500 张、≤ 512 MB、≤ 30,000 patches（patch 模型）。`[OpenAI 文档]`

### 4.3 供应商差异（编码层）

| 供应商 | 图片字段 | base64 前缀 | 额外允许 | 限制 |
|---|---|---|---|---|
| OpenAI Responses | `input_image.image_url` | `data:image/...;base64,` | `file_id` | image_url 字符串 ≤ 20 MiB 字符；请求 ≤ 512 MB / 1,500 图 |
| OpenAI Chat | `image_url.image_url.url` | 同上 | 无 file_id（user 另有 file part） | 同上 |
| Qwen Responses | `input_image.image_url`（**必须是字符串**，不支持嵌套 `{"url":...}`） | `data:image/png;base64,...` | `input_file`（user，PDF/图片，仅部分模型） | 单图 ≤ 20 MB（Qwen3.5+/Qwen3-VL 系列），请求体 ≤ 64 MB；DashScope 老域名 16 MiB |
| Qwen Chat | `image_url.image_url.url` | 同上 | 无 | 单图 ≤ 20 MB（同上），请求体 ≤ 64 MB |
| DeepSeek Responses/Chat | `input_image.image_url` / `image_url.image_url.url` | `data:image/jpeg;base64,...` | `file_id`（Files API） | inline ≤ 32 MiB；请求体 ≤ 48 MiB；≤ 600 图 |
| Gemini OpenAI 兼容 | `image_url.image_url.url` | `data:image/jpeg;base64,...` | 无文档化的 file_id | 未在本页找到字节上限；官方另有 media resolution/token counting 文档，本次未取 |

---

## 5. 图片 token / 成本口径

### 5.1 OpenAI（官方 2026-10 指南）

适用前提：同一条规则按“模型 + detail”计算；官方指南同时覆盖 Responses 与 Chat Completions 示例，**没有给两套公式**。`[OpenAI 文档]` 置信度：高。

**A. tile 类模型（gpt-4o、gpt-4.1、gpt-5、gpt-5.1 等）**

| 模型 | base tokens | 每 tile tokens |
|---|---:|---:|
| gpt-4o, gpt-4.1 | 85 | 170 |
| gpt-5.1, gpt-5（deprecated） | 70 | 140 |
| gpt-4o-mini | 2833 | 5667 |
| o1, o1-pro, o3（deprecated） | 75 | 150 |

计算（`detail: low` / `high` / `auto`）：
- `low`：只收 base，和尺寸无关。
- `high` / `auto`：
  1. 等比缩到 ≤ 2048×2048（不放大小图）；
  2. 若最短边 > 768，把最短边缩到 768，另一边向下取整；
  3. 用 512×512 的 tile 覆盖：`tiles = ceil(w/512) * ceil(h/512)`；
  4. `tokens = base + tile_tokens * tiles`。

**B. patch 类模型（gpt-5.2/5.4/5.5/5.6/6.x 等）**

1. `patch_count = ceil(width/32) * ceil(height/32)`；
2. 若模型/detail 有 patch budget，等比缩小到 budget 内（保留比例、整数像素、不放大）；
3. `tokens = ceil(patch_count * model_multiplier)`。

| 模型 | multiplier |
|---|---:|
| gpt-6-astra, gpt-5.6-sol/terra/luna, gpt-5.5, gpt-5.4/5.4-mini/5.4-nano, gpt-5.2, gpt-5-mini（deprecated） | 1.2 |
| gpt-5-nano（deprecated） | 1.5 |
| gpt-4.1-mini | 1.62 |
| gpt-4.1-nano（2025-04-14） | 2.46 |
| o4-mini | 1.72 |

- **硬限制**：处理后 `patch_count > 30,000` 直接拒绝；不会自动缩到 30k 以内。
- `detail` 默认 `auto`（Responses 与 Chat 都是）。
- 官方示例：1024×1024 @ gpt-6-astra high = 32×32=1024 patches → `ceil(1024×1.2)=1229` tokens。

**C. 两协议是否一致**：官方用同一套模型规则、同一个 calculator；未发现 Responses/Chat 两套 token 口径。`[OpenAI 文档]` 置信度：高（仅限“文档未区分”）。
**未验证**：同一张图片放在 `function_call_output.output` 里是否与放在 user 消息里计费完全相同——官方没有单独说明。`[未找到]`

### 5.2 Qwen-VL（DashScope，官方文档）

官方在 Chat API reference 的 `total_pixels` 说明中给出换算：
- Qwen3.8 / 3.7 / 3.6 / 3.5、Qwen3-VL 系列：**1 image token = 32×32 像素**（例如 `819,200,000 px ≈ 800,000 image tokens`）。
- Qwen2.5-VL 开源系列、QVQ 系列：**1 image token = 28×28 像素**（例如 `51,380,224 px ≈ 65,536 image tokens`）。

来源：`https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions`（`total_pixels` 的 Value range）；同一套视觉模型文档页也给出 `image_tokens` 计费字段。`[供应商文档]` 置信度：高（换算率是官方原文）。
- 另有 `min_pixels` / `max_pixels` 缩放参数：Qwen3.5+/Qwen3-VL 图像默认 `max_pixels=2,621,440`，最大 `16,777,216`；`vl_high_resolution_images=true` 时固定上限 16,777,216 px（Qwen2.5-VL 为 12,845,056）。`[供应商文档]`
- 未在官方 prose 中找到“向上取整/按 patch 对齐”的精确公式；官方提供的是 token 计算器与像素/token 表，不是一行闭式公式。`[未找到]` 置信度：高（缺失本身）。

### 5.3 DeepSeek（`deepseek-flash`，官方文档）

- 每张图先自动缩放：总像素低于约 544×544 的放大小图；更大的图等比缩到总像素约 **1300×1300**。
- 因此 **每张图上限 1,024 tokens**；2000×2000 和 5000×5000 缩放后同价。
- `detail: low` 先缩到 512×512；`high/original/auto` 保留原图（缩放到上述预算）。`[供应商文档]` 置信度：高。
- 限制：inline/URL 单图 ≤ 32 MiB，请求体 ≤ 48 MiB，≤ 600 图；Files API `file_id` 单图 ≤ 64 MiB。`[供应商文档]`

### 5.4 Gemini

本次只确认了 OpenAI 兼容层的图片输入方式（data URL），没有取 Gemini 官方 token 公式；不写推断。`[未找到]`

---

## 6. 必须用真实 provider 实测的清单

> 排序按风险高低。每项都记录：请求体（脱敏）、HTTP 状态、原始错误文本、`usage.input_tokens`、是否真的读出图中文字（不能只看 200）。

1. **OpenAI GPT-4o / GPT-4.1 × Responses × `function_call_output` 数组含 `input_image`**
   - 目的：官方 schema/指南没有模型限制，但公开 200 录制只覆盖 gpt-5.5；确认 4o/4.1 是否接受。
2. **OpenAI Chat Completions × `role:"tool"` 内含 `image_url`**
   - 预期 400；记录准确错误码/字段路径，确认不是被静默忽略。
3. **OpenAI Chat Completions × tool 文本 → 追加 user 图片消息**
   - 目的：验证业界替代序列对 `api.openai.com` 是否 200，模型是否真看得见图。
4. **OpenAI Responses × `assistant` / `system` / `developer` 回放 `input_image`**
   - 预期 assistant 400；system/developer 未知；记录角色级差异。
5. **OpenAI Responses × 工具结果图片 + reasoning 模型**
   - 验证 `reasoning` item 必须回传、且 `function_call_output` 图片与 reasoning 的顺序合法。
6. **Qwen Responses × `function_call_output.output=[input_text,input_image]`**
   - 用 base64 data URL 与公网 URL 各一次；多图一次；确认 `image_url` 必须是字符串、嵌套对象 400。
7. **Qwen Responses × 在 `function_call_output` 之后追加 user 图片消息**
   - 验证“必须紧跟”是只约束 call→output，还是也禁止 output 后插 user。
8. **Qwen Chat × `role:"tool".content` 含 `image_url`**
   - 预期被拒（文档要求 string）；记录错误。
9. **DeepSeek Chat × `role:"tool".content` 数组含 image part**
   - 文档冲突：API reference 说可以，Vision 指南说“仅 user 消息”；必须实测。
10. **DeepSeek Responses × `function_call_output` 图片**
    - 验证 base64、`file_id`、多图、`detail`；并验证 `system`/`assistant` 图片确实 400。
11. **Gemini OpenAI 兼容 × tool 消息图片 / 多轮历史图片**
    - 文档没有写；确认是 400、被忽略，还是可用。
12. **Responses vs Chat 同模型同图 token 对照**
    - 同一张图分别走两条协议，比较 `usage.input_tokens`（以及供应商返回的 `image_tokens`），验证“同模型同价”的假设。
13. **大图与移动端超时**
    - K40 上构造接近限制的 base64（OpenAI 20 MiB 字符 / Qwen 20 MB / DeepSeek 32 MiB），测请求时延、内存、超时与失败码；不要只用小图测通就宣布支持。

---

## 7. 未找到官方证据 / 明确未验证

以下各项**不得**在后续设计/验收中被当作官方事实：

1. OpenAI 官方对 `input_image` 可出现的 **所有 role** 的明文清单（尤其 `system`/`developer`；assistant 只有第三方客户端 PR 的 400 记录）。
2. OpenAI 官方对 **哪些模型**支持 `function_call_output` 图片的模型清单；公开实测仅 gpt-5.5。
3. OpenAI 官方对 **`function_call_output` 是否必须紧跟 `function_call`** 的明文；Qwen 明确要求紧跟，OpenAI 未写。
4. OpenAI 官方对 **tool 结果之后插入 user 图片消息** 的接受性；只有业界替代实现，没有官方示例或本机实测。
5. OpenAI 官方对 **图片在 tool 输出中与 user 消息中 token 计费相同** 的说明。
6. OpenAI 官方 **单张图片字节上限**：当前指南给的是请求级 512 MB；OpenAPI 给 `image_url` 字符串 20 MiB 字符；Azure 旧文档写 20 MB/图。三者口径不完全一致，以真实 provider 报错为准。
7. Chat Completions 的 `image_url.url` **字符串长度上限**：OpenAPI 未给 maxLength。
8. Gemini OpenAI 兼容层的 **tool 消息图片**、**多轮历史图片重放**、**是否提供 Responses 端点**（文档只列 Chat Completions/Images/Embeddings 等；未列 Responses）。
9. Qwen Chat 对“tool 消息图片”是否只有“必须 string”这一条限制；官方没有单独写“images not supported in tool messages”。
10. DeepSeek **Chat tool 消息图片**的最终行为：API reference 与 Vision 指南互相矛盾。
11. Qwen 图片 token 的**精确取整/patch 对齐公式**：官方只给 1 token/32×32（或 28×28）与计算器，没有闭式公式。
12. Gemini 图片 token 公式：本次未取；不写推断。

---

## 8. 来源清单（按类型）

### OpenAI 官方
- Images and vision 指南：https://developers.openai.com/api/docs/guides/images-vision （`platform.openai.com/docs/guides/images-vision` 会跳转到这里）
- Function calling 指南：https://developers.openai.com/api/docs/guides/function-calling?api-mode=responses 与 `?api-mode=chat`
- 官方 OpenAPI 规范：https://github.com/openai/openai-openapi/blob/master/openapi.yaml
  - `FunctionCallOutputItemParam`（`output` oneOf string / array<InputText|InputImage|InputFile>）
  - `InputImageContentParamAutoParam`（`type`/`image_url`/`file_id`/`detail`）
  - `ChatCompletionRequestToolMessage`（content 仅 string 或 text part；注释“only type text is supported”）
  - `ChatCompletionRequestMessageContentPartImage`（`image_url.url` + `image_url.detail`）
  - `ChatCompletionRequestAssistantMessage`（content parts 仅 text/refusal）
- 官方 Python SDK 类型：
  - https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_tool_message_param.py
  - https://github.com/openai/openai-python/blob/main/src/openai/types/responses/response_input_image_param.py

### 供应商官方
- Qwen/DashScope：
  - Responses API 参考：https://help.aliyun.com/en/model-studio/qwen-api-via-openai-responses
  - Responses 兼容说明：https://help.aliyun.com/en/model-studio/compatibility-with-openai-responses-api
  - Chat API 参考：https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions
  - 视觉理解/限制：https://www.alibabacloud.com/help/en/model-studio/vision
- DeepSeek：
  - Chat API 参考：https://api-docs.deepseek.com/api/create-chat-completion
  - Responses API 参考：https://api-docs.deepseek.com/api/create-response/
  - Responses 指南（Image Input / Restrictions）：https://api-docs.deepseek.com/guides/responses_api
  - Vision（格式/限制/token）：https://api-docs.deepseek.com/guides/vision
- Gemini：
  - OpenAI 兼容性：https://ai.google.dev/gemini-api/docs/openai

### 其他官方（Microsoft / 非 OpenAI 平台）
- Azure OpenAI “Vision-enabled chat model concepts”（20 MB/图、10 图/次等旧口径）：https://learn.microsoft.com/en-us/azure/ai-services/openai/concepts/gpt-with-vision

### 源码 / PR / issue（补充证据，不是官方文档）
- Responses `function_call_output` 图片的真实 200 录制（gpt-5.5）：https://github.com/anomalyco/opencode/pull/28754
- Chat tool 图片被 OpenAI 400 的集成测试与 Responses 图片可见测试：https://github.com/langchain-ai/langchainjs/pull/11690
- Chat tool 媒体拆到后续 user 消息的实现：https://github.com/QwenLM/qwen-code/commit/4f8d5e02b55cf2cf21ad3755dee5587bfac8cfd3
- strict OpenAI-compatible server（LM Studio）拒绝 tool 图片的 issue：https://github.com/QwenLM/qwen-code/issues/3616
- assistant 回放 `input_image` 被 400 的客户端修复：https://github.com/NousResearch/hermes-agent/pull/97077
- OpenAI .NET SDK 将 `function_call_output.output` 视为 string 的兼容问题：https://github.com/microsoft/agent-framework/pull/5705
- llama.cpp 增加 Responses tool output 图片支持：https://github.com/ggml-org/llama.cpp/pull/27958
