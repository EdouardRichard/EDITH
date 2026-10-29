# D3 — Agent Loop 与上下文治理设计草案（承接 0.25 / D121）
**草案，未批准；不是 spec。**

- **日期**：2026-10-19
- **作者**：EDITH 设计子代理（只读调研 + 草案）
- **基线**：`main` HEAD `1e0cc41`；0.24 已由业主版本级验收闭环（`STATUS.md:3-5,18`）；0.25 承接 `D121` 工具面重构。
- **输入**：预研报告 `docs/pre-research/2026-10-19-edith-agent-evolution-pre-research.md` §3.1/§3.5/§5.3/§5.4/§8.4/§8.5/附录 C；EDITH 现有代码；X-OmniClaw 与 e2e 源码（本机只读，未运行、未在 EDITH 设备上验证）。
- **范围**：Agent Loop 保护、多 tool call 执行、上下文压缩、overflow 恢复、图片记账、Metrics、JVM 测试清单、待裁决问题。
- **不在范围**：工具 schema / 工具面明细（D2）、DeviceControl 设备内核与通道实现（D1）、确认闸门的具体风险分级、会话 UI。
- **证据边界**：本文区分「代码事实」（本机源码，带路径+行号）、「参考实现事实」（外部仓库源码，带路径+行号）、「建议/推断/未测」。所有阈值、K 值、字符/字节上限均为**建议值，未在 EDITH 上标定**；没有任何设备实测或模型实测读数。
- **纪律**：本文不修改产品代码，不修改 `DECISIONS.md` / `CONTRACTS.md` / `ACCEPTANCE.md` / `STATUS.md`。任何落地必须先走 EDITH 规格 → 计划 → 实施 → 验收流程。正文为可读性使用“文件名 + 行号”，完整路径见 §12 参考索引。

---

## 0. 结论摘要

1. **Loop 保护从“一个 payload 字符串守卫”升级为“调用身份 + 观察状态”双层**：4 类 detector 在**执行前**判定，warning 以合成 tool result 返回模型（不执行该次调用），critical 直接终止；连续相同错误 3 次终止；运行态提示按请求注入、不写 `ConversationStore`；文本计划续跑 nudge 最多 3 次；工具级超时与回合 deadline 分离；步数与观察预算分离；取消必须传播到工具执行层。
2. **多 tool call 的边界是“通道”而不是“读/写”两个字**：a11y 单服务 + 单 `currentSnapshot`、shell 单通道，因此同通道读仍串行；只有 D1 声明 `parallelSafe` 的不同通道只读调用可并行（上限 3），写全部串行；结果**按调用顺序**回填；`ProgressGuard` 改看观察状态指纹（generation + structural fingerprint），不再比较 payload 字符串；过期读不构成进展。
3. **上下文仍遵守 D81 统一窗口预算，不引入 per-category token 预算**；新增一刀 ⓪「类型感知省略」插在 D97 的①之前：旧 UI 树→header、旧图片→占位摘要、超长工具结果头尾软修剪、最后一条工具结果豁免；①增加“最近 N 轮/N 条 assistant + 本轮工具轮固定”锚。**这些是对 D81“不截断”和 D97“固定三刀”的显式扩展，必须单列裁决**（见 §8 R1/R2）。
4. **overflow 恢复分本地与服务端两条检测**：本地 `ConfigurationRejected` 与 provider `CONTEXT_OVERFLOW` 都触发「确定性压缩一次 → 硬截断一次 → 可解释失败」，最多 2 次恢复；**不使用 LLM 摘要救当前请求**（D100）；`ContextPlanner` 是生产未接线的死代码，建议删除而不是继续保留。
5. **图片：K=3、长边 768、按 28px tile 估算成本、成本并入同一统一预算；`ToolOutput` 携带 `ImageRef`，历史只存引用不存字节；artifact 缺失降级为可见文字占位，不让整个请求失败。** 但现行 `ContextBudgeter` 拒绝任何非文本输入、`MediaSource` 只有音频、适配器没有图片编码，且生产 `ConversationStore` 目前不记录任何工具观测——图片进历史之前必须先补这三处（§5.6）。
6. **Metrics 只记计数/耗时/id**：`AgentRun` 增加 `metrics`，provider usage 从 `Terminal.usage` 累加（现在被网关丢弃），新增独立 `P3_METRIC` 行，不改 `P3_TURN`/`P3_TOOL` 的既有形状（eval 解析器依赖它们，`TraceExtractor.kt:3-5`）。
7. **5 条待裁决**：R1 D81 是否允许子回合截断；R2 D97 是否允许插入 ⓪ 与锚；R3 图片 K/成本/记录粒度；R4 观察预算与 D-P3-10/D110；R5 overflow 恢复与 D100。

---

## 1. Loop 保护

### 1.1 现状（代码事实）

| 事实 | 位置 |
|---|---|
| 单次 `AgentLoop.run` 串行执行工具：`for (call in reply.toolCalls)`，每个结果回填 `ModelHistoryItem.ToolOutput` | `AgentLoop.kt:401-502` |
| `ProgressGuard(limit=3)` 只比较**连续 payload 字符串** | `ProgressGuard.kt:14-37`；`AgentLoop.kt:493` |
| `maxSteps=24` 是挂死保护，不是预算；`spentSteps` 按描述符 `countsTowardStepLimit` 计 | `AgentLoop.kt:188-199,406`；`Actions.kt:38-50` |
| 终止判据：无 tool call 且文本非空 → `COMPLETED`；空回合/`INCOMPLETE` 重试最多 3 次 | `AgentLoop.kt:348-387,547-550` |
| 取消只在循环顶、模型返回后、每个工具后检查；工具执行本身没有取消/超时 | `AgentLoop.kt:304,344,498`；`ToolRegistry.kt:84-98` |
| `ActionExecutor` 已有 `cancel()` 端口，但 `AgentLoop` 不调用；插件 teardown 直接 `actions.cancel()` | `Actions.kt:72-76`；`SmartAssistantPlugin.kt:3260-3273` |
| 工具结果只有 `ok + payload:String`，没有观察状态、图片、耗时、超时 | `ToolProtocol.kt:22-33` |
| `notify_user` 与 `load_skill` 用自增计数让 payload 不同，专门规避 `ProgressGuard` 误判 | `NotifyUser.kt:68-74,94`；`LoadSkillTool.kt:26-27,59` |

**结论**：现有守卫能防“同 payload 三次”，但防不住“同调用不同 payload / 轮询无进展 / 两工具交替 / 变体错误”，也没有执行前拦截。参考实现 e2e 的四类 guard 与 X-OmniClaw 的四类 detector 均把“调用身份”和“结果无进展”分开，EDITH 需要同样的分层。

### 1.2 共同状态机

**建议**：`AgentLoop` 内新增一个 per-run `LoopDetector`，与 `ProgressGuard` 并存（职责不同，见 §2.4）。

```kotlin
// 草案形状；不是最终契约
data class CallRecord(
    val index: Int,
    val tool: String,
    val argsHash: String,          // 稳定序列化后 SHA-256，不记原文
    val effect: Effect,            // READ / WRITE / TERMINAL，来自 D2 元数据
    val executed: Boolean,
    val ok: Boolean,
    val errorIdentity: String?,    // tool + 稳定码，或 detail 的哈希
    val observation: ObservationState?, // generation + 结构指纹 + 前台 + revision
    val startedNanos: Long,
    val durationNanos: Long,
)
```

检测点与动作：

| 时点 | 动作 |
|---|---|
| 每个 tool call **执行前** | 计算 `(tool,argsHash)`；查 generic repeat / poll-no-progress / ping-pong / global；warning → **不执行**该调用，追加合成 `ToolOutput`（`ok=false`，稳定码 `LOOP_WARNING:<detector>`）；critical → 终止 |
| 每个 tool call **执行后** | 记录结果、观察状态、错误身份；喂 `ProgressGuard`（只喂有观察状态的**写**结果；读不喂，见 §2.4） |
| 每个模型请求**前** | 注入运行态提示（§1.5）；global 警告阈值只注入一次 notice |
| 每轮结束 | 更新连续错误计数；成功写重置错误/无进展计数（观察成功不重置，见 §1.4） |

**warning 的具体语义**：不是“注入一条聊天提示”，而是**替该次调用返回一个 tool result**。这样不额外消耗一次模型往返，模型在同一工具轮里看到“别再这样”，且该文本不进入会话存储（工具轮本来就不写 `ConversationStore`，`SmartAssistantPlugin.kt:2081-2092`）。**critical 的语义**：不再执行任何后续调用，`finish(AgentOutcome.NO_PROGRESS, safeCode = LOOP_*)`，由插件映射失败提示。

**去重**：每个 detector 用 `warningKey`（detector + 签名 + 上次观察指纹）去重；同一 warning 在一个 run 内不重复注入；调用签名或观察状态变化后允许新 warning。参考 X-OmniClaw 的 `reported_warnings`（`loop_detector.py:99,136`）与 e2e 的 `noticedGuardReason`（`tool-loop.ts:599-600`）。

**阈值建议（未标定）**：

| detector | 身份 | 计数窗口 | warning | critical | 终止码 |
|---|---|---|---:|---:|---|
| `generic_repeat` | `(tool,argsHash)` 连续相同 | 尾部连续**尝试** | 3 | 5 | `LOOP_REPEAT` |
| `known_poll_no_progress` | `(tool,argsHash)` + 观察结构指纹连续不变 | 尾部连续**尝试**；只对 D2 声明 `observation=true` 的调用 | 3 | 5 | `LOOP_POLL` |
| `ping_pong` | 两个不同签名 A/B 交替 | 尾部交替周期（按**尝试**计） | 2 个完整周期 | 3 个完整周期且无进展证据 | `LOOP_PINGPONG` |
| `global_circuit_breaker` | 全 run “无进展结果”累计 | 全 run，不重置；只统计**已执行**结果 | 8（仅注入一次） | 12 | `LOOP_GLOBAL` |

**为什么不是 X-OmniClaw 的 10/20/30**：EDITH `maxSteps=24`，30 永远到不了，10/20 也已接近一半步数；e2e 的 3/5、2/3 更贴近“早停省钱”。12 作为全局上限留出约一半步数给模型改道。**这些值必须在实现前用 EDITH 任务语料标定；未标定前只能作为可配置默认值。**

### 1.3 四类 detector 细节

1. **generic_repeat**
   - 状态：尾部连续相同 `(tool,argsHash)` 的**调用尝试**数（含被 warning 跳过、未执行的调用）；否则模型可以靠“被跳过”无限拖住，永远到不了 critical。对带观察状态的写调用，若指纹变化则重置（有进展不算重复）；无观察状态的调用按身份计。
   - 与 `ProgressGuard` 的重叠：带观察的写调用连续 3 次同指纹时，guard 会先终止（`NO_PROGRESS`），generic 的 warning/critical 不会到达；这是有意的“最强证据优先”。generic 主要覆盖 guard 看不见的模式（无观察工具、读轮询、ping-pong、global）。见 §2.4 的分工段。
   - 执行前：若计数 ≥3，下一次同签名调用不执行，返回 `LOOP_WARNING:GENERIC_REPEAT`；若计数 ≥5，终止 `LOOP_REPEAT`。
   - 参考：e2e period=1 `repeatWarn=3/repeatStop=5`（`loop-guards.ts:44-52,79-98`）；X-OmniClaw `WARNING_THRESHOLD=10`（`loop_detector.py:14-17`），阈值偏高，不照搬。
2. **known_poll_no_progress**
   - 只对“观察类”调用生效（D2 元数据 `observation=true` 或 `effect=READ` 且 `idempotent=true`）。**不在 runtime 按工具名硬编码**（D110 的同一原则，`Actions.kt:38-50`）。
   - 状态：同签名 + 观察指纹不变的连续**调用尝试**次数；指纹变化即重置。
   - warning/critical 同上；若调用没有观察状态（例如 `load_skill`），退化为 generic_repeat，不单独判 poll。
   - 参考 X-OmniClaw 的 `known_poll_no_progress` 同时看 args 与 result（`loop_detector.py:120-147,242-258`）；e2e 用 `checkFailureStreak` 处理“连续失败但输入不同”（`loop-guards.ts:210-227`）。
3. **ping_pong**
   - 状态：尾部 `A,B,A,B…` 的完整周期数；A/B 以 `(tool,argsHash)` 为身份，按调用尝试计（含 warning 跳过）。warning 只看交替；critical 还要求“两个签名在整段交替里观察指纹均未变化”（无进展证据）。
   - 阈值：2 周期 warning、3 周期 critical。参考 e2e `cycleWarn=2/cycleStop=3`（`loop-guards.ts:45-52`）。
   - 若 A/B 任一带来真实观察变化，则不判 critical（模型可能确实在推进）。
4. **global_circuit_breaker**
   - 状态：整个 run 的“无进展结果”累计。定义：一次写调用后观察指纹与调用前相同，或一次观察调用返回与前一次相同的指纹，且前台/窗口未变 → 记 1。
   - 8 时注入一次“你已连续多次没有推进”的 notice（不跳过调用）；12 终止 `LOOP_GLOBAL`。
   - 不重置，作为“整轮浪费上限”；与 `maxSteps` 是两道不同的闸。
   - 参考 X-OmniClaw 的 `global_circuit_breaker`（`loop_detector.py:101-118`，阈值 30）与 e2e 的 turn 预算（`tool-loop.ts:379-384`）。

### 1.4 连续错误追踪

- **相同错误 3 次终止**：错误身份 = `tool + ":" + stableCode`。`stableCode` 取 `Refused: CODE` / `Failed: CODE` 的首个 token，或 `ACTION_TIMEOUT` 等稳定码；无法取得稳定码时用 detail 的 SHA-256 前 16 位（D117 允许哈希，不记正文）。连续计数达到 3 → `finish(FAILED, "REPEATED_ERROR")`（或 `NO_PROGRESS`，取现有失败语义更近者；建议 `FAILED`，因为这是执行失败而非无进展）。
- **不同错误连续失败**：warning 3、stop 5（e2e `failureWarn=3/failureStop=5`，`loop-guards.ts:45-52,215-227`）；相同错误命中更强规则时以 3 为准。
- **重置规则**：一次成功的**写**调用重置错误计数；一次成功的**观察**不重置（否则“read_screen 成功 → tap 失败”可以无限交替）。参考 X-OmniClaw 是任意 success 清空（`agent_logic.py:546-547`），EDITH 建议收紧。
- **不计入错误 streak 的结果**：`CONFIRMATION_DENIED` / `CONFIRMATION_TIMEOUT` 是用户决定，不是执行错误；返回 `CONFIRMATION_*` 时注入“不要自动重试，等用户下一步”的提示，不计失败 streak。`REFUSED_BY_POLICY` 是确定性拒绝，计入（模型应换路）。
- **错误分类提示**：对 `NODE_STALE` / `NODE_NOT_FOUND` / `CHANNEL_UNAVAILABLE` / `ACTION_TIMEOUT` 等稳定码，返回前注入一次短提示（重新观察 / 换通道 / 先确认是否已生效），同一码一次。参考 X-OmniClaw 的 snapshot/ref 错误视觉兜底提示（`agent_logic.py:536-545`）。
- **不记录正文**：错误身份、提示、计数可进日志；detail 正文不进（D117）。
- **与 detector 的分工**：detector 的 warning/skip **不计入**错误 streak（它不是执行错误）；global 只统计已执行结果，warning 跳过不产生观察，不增加 global 计数。

### 1.5 运行态提示（非持久）

- **内容建议**（≤300 字符，纯状态，不含用户正文）：当前前台 package/窗口 id、最近一次观察的 `generation/revision`、是否有“已执行写但未验证”的动作、是否有“已输入未发送”、本轮已用步数/观察数。
- **注入路径**：新增 `SegmentOrigin.RUNTIME_STATE`（untrusted，非会话流）；`AgentRequest.frameFor` 的签名扩展为 `(transcript, TurnRuntimeState) -> RequestFrame`（或在 `AgentRequest` 增加 `runtimeState: (() -> TurnRuntimeState?)?`，由 frame builder 读取）。插件 `frameBuilderFor` 在 memory 之前、`USER_INPUT` 之前追加该段；`ModelClientGateway.frameToTranscript` 像 `RETRY` 一样把它折进请求 `input` 之前，**不**作为独立 user 历史项（否则会形成 trailing lone USER group，被 `ContextBudgeter` 拒绝，`ContextBudgeter.kt:50-53`；网关对 trailing group 的拒绝见 `ModelClientGateway.kt:316-321`）。
- **不持久**：不写 `ConversationStore`；只活在本次请求。每请求最多一条；与上次注入内容哈希相同则跳过（省 token）。
- **TurnTrimmer 处理**：`RUNTIME_STATE` 不是 turn start，`isEvictable=false`（与 `USER_INPUT` 同类保护，`TurnTrimmer.kt:161-170`），但仍计入预算；它只有几百字节，不会成为超预算主因。
- **测试**：注入后请求仍通过 `ContextBudgeter` 形状校验；store 大小不变；相同提示连续两次只出现一次。

### 1.6 文本计划续跑 nudge

- **触发条件（全部满足）**：
  1. 模型本回合**没有 tool call**，文本非空白；
  2. 本 run 至少有一次**成功执行**的 tool（`tools_used` 非空；参考 X-OmniClaw `agent_logic.py:595-600` 的 `tools_used` 条件）；
  3. 文本长度 ≤1200 字符，命中“计划词”（接下来/下一步/我将/稍后/先…再…/I will/Next 等），且未命中“完成词”（已完成/无法继续/没有权限/总结如下 等）；参考 `utils.py:7-19,67-74`；
  4. `nudges < 3`；
  5. `AgentRequest.allowPlanNudge = true`（默认 `false`，生产 WAKE 路由设 true，PROACTIVE 设 false；纯问答没有工具时天然不触发）；
  6. 未到 deadline / `maxSteps` / `maxObservations`。
- **动作**：追加 `[assistant text, USER nudge]`（复用 `appendRetry` 的模式，`AgentLoop.kt:547-550`），继续循环；nudge 文本要求“先观察再操作，不要只用承诺文字结束”。该 USER 文本在 frame builder 中按位置识别为 `RETRY`（`SmartAssistantPlugin.kt:2221-2235`），折进 input，不写 store。
- **上限与收尾**：最多 3 次；达到上限后把文本当作最终答复（`COMPLETED`），metrics 记 `planNudgeCap=1`。如果产品要求更严格，可在规格阶段改为 `INSUFFICIENT/PLAN_NOT_EXECUTED`；本草案不把它列为裁决，因为两种都诚实。
- **不触发**：无工具的纯问答、`notify_user` 已经给出的明确答复、文本为空、文本像完成/失败说明、PROACTIVE 路由、上一次 nudge 后文本完全相同（避免原地打转）。
- **为什么不照搬 6 次**：EDITH 的回合更短（默认 24 步）且模型往返昂贵；3 次足够给模型两次改道机会。参考 X-OmniClaw `MAX_TEXT_ONLY_CONTINUATION_NUDGES=6`（`utils.py:28`）。

### 1.7 工具级超时

- **与回合 deadline 分开（D49）**：deadline 约束整个回合的机器时间；工具超时约束单次调用；两者取更早者。等待确认的人时仍从 deadline 顺延，与工具超时无关（`AgentLoop.kt:24-25,298-301,444-453`）。
- **默认值建议（未标定）**：普通工具 30s；长工具（安装/卸载/大文件/媒体/截图落盘）120s；`load_skill` 5s；`notify_user` 5s。D2 在描述符上提供 `timeoutMillis`（字段名由 D2 定；本草案不定义 schema）。
- **执行**：`ToolRegistry.execute` 当前是同步调用（`ToolRegistry.kt:84-98`），需要一个带 `Future.get(timeout)` 的执行包装或 D1 的异步 op。超时上限取 `min(toolTimeout, turnDeadline - now)`。
- **超时后的语义**：
  - 调用 `executor.cancel()`（端口已存在，`Actions.kt:72-76`），等待 2s 宽限；
  - 已取消 → `ToolResult.error(callId, "ACTION_TIMEOUT")`，模型看到“可能已生效/可能未生效，先观察再决定”；计入错误 streak；
  - 2s 内未确认取消 → `ACTION_TIMEOUT_UNCERTAIN`，**终止**本 run（或至少终止本批剩余写调用），并把通道标记为“脏”；下一次观察必须重新建立 generation。绝不能在上一个写可能仍在执行时继续下一个写。
- **不重试**：工具超时不自动重试；是否重试由模型依据观察决定。
- **Metrics**：每次调用记录 `durationNanos`、`timedOut`；不记参数。

### 1.8 步数与观察预算分离

- **保留 `maxSteps` 作为挂死保护**（`AgentLoop.kt:188-199`），不改成任务预算（spec D-P3-10 的旧口径仍然有效）。
- **新增观察计数 `spentObservations`**：只对 D2 声明 `observation=true` 的调用计数，不论它是否 `countsTowardStepLimit`；`read_screen`/`device(observe)` 建议声明 `countsTowardStepLimit=false`（D110 的声明式豁免，`Actions.kt:38-50`），从而不吃 24 步；但吃观察预算。
- **`maxObservations` 建议 24**：达到即 `finish(NO_PROGRESS, "OBSERVATION_BUDGET")`；warning 阈值不单设，poll detector 会更早触发。观察预算只是防“无限刷屏”，不是任务预算。
- **`load_skill`**：仍是唯一生产 `countsTowardStepLimit=false` 的现有工具（`D110`）；它不是观察，不占 `spentObservations`。
- **`notify_user`**：计步（它是真实动作），但 L0 免确认（`NotifyUser.kt:66-68`）。
- **被 detector 跳过的调用**：记录 `AgentStep`；是否 `spentSteps++` 仍按描述符 `countsTowardStepLimit` 决定（豁免的观察调用不涨步数，但涨 `spentObservations`），`executed=false`；避免“跳过不计数”被用来绕过挂死保护。
- **指标**：`AgentRun` 增加 `spentObservations`；`steps.size` 仍表示“模型发起了多少次工具调用”（D110 的既有含义，`AgentLoop.kt:128-138`），避免 eval 口径漂移。

### 1.9 取消传播

- **现状**：`AgentLoop.cancel()` 只置位；模型请求由 `ModelClientGateway` 的 `stop()`/`handle.cancel()` 处理（`ModelClientGateway.kt:143-145,161-188`）；工具执行阻塞时循环读不到标志；插件 teardown 另外直接 `actions.cancel()`（`SmartAssistantPlugin.kt:3260-3273`）。
- **建议**：
  1. `AgentLoop` 持有 `onCancel: (() -> Unit)?`（或 `ToolRegistry.cancel()`），在观察到取消时调用；`ToolRegistry.cancel()` 遍历 executor 的 `cancel()`（`ExecutionRouter.kt:61-63` 已有聚合）。
  2. 每个工具调用带一个 `CancellationToken`（D1 提供）；DeviceControl 在每个子操作前检查；a11y 的 `performAction` 是快操作，shell 的 `sh -c` 可能不可中断 → 只能 best-effort。
  3. 取消后**不追加**未完成调用的 tool output；已完成调用的结果可以留在内存 transcript，但 run 返回 `CANCELLED`，插件不写 store。
  4. 宽限期（2s）与 `ACTION_TIMEOUT_UNCERTAIN` 同一处理；不把“取消”报成“工具失败”。
- **测试**：模型请求中取消、工具 sleep 中取消、工具刚返回时取消、并发取消幂等。

### 1.10 终止码与用户可见语义

| 场景 | `AgentOutcome` | `safeCode` | 备注 |
|---|---|---|---|
| 四类 detector critical | `NO_PROGRESS` | `LOOP_REPEAT` / `LOOP_POLL` / `LOOP_PINGPONG` / `LOOP_GLOBAL` | 插件按失败提示上屏；不把模型承诺当答案 |
| 相同错误 3 次 | `FAILED` | `REPEATED_ERROR` | 保留最后一次稳定码 |
| 工具超时（可继续） | 继续 | `ACTION_TIMEOUT`（tool result） | 模型决定下一步 |
| 工具超时未确认 | `FAILED` | `ACTION_TIMEOUT_UNCERTAIN` | 通道需重新观察 |
| 观察预算耗尽 | `NO_PROGRESS` | `OBSERVATION_BUDGET` | 防刷屏 |
| 上下文彻底放不下 | `FAILED` | `CONTEXT_OVERFLOW` | 见 §4 |
| 模型只在承诺不执行 | `COMPLETED`（当前建议） | — | metrics `planNudgeCap`；规格阶段可改 |

---

## 2. 多 tool call 执行

### 2.1 现状

- 解码器/网关已支持一回合多个 tool call（`reply.toolCalls` 是列表，`AgentLoop.kt:393-401`），但循环**串行**执行，结果按顺序回填。
- a11y 后端是**单服务 + 单 `currentSnapshot`**：`read_screen` 设置快照，`tap/long_press/input_text` 消费它；`scroll` 使快照失效（`AccessibilityExecutionBackend.kt:157-158,174-180,230-239,258-265`）。两个并发的 a11y 读会互相覆盖 `currentSnapshot`，因此**同通道必须串行**。
- shell 通道是单特权进程/单通道（`PrivilegedShell`，`ShizukuExecutionBackend`），同样串行。
- `ExecutionRouter` 只按工具名路由，不提供并发/代际语义（`ExecutionRouter.kt:43-63`）。

### 2.2 分组与并行边界

**原则：并行单位是“通道 + 只读”，不是“读/写”两个字。**

| 组合 | 执行方式 |
|---|---|
| 同通道两个读（a11y+a11y / shell+shell） | **串行**（共享快照/单通道） |
| 不同通道的读（a11y + shell + skill/memory） | 可并行，上限 3；前提是 D1/D2 把该通道标为 `parallelSafe` |
| 任意写 | **全局串行**；写之前先执行完同批中位于它之前的读；写之后同批的读在写完成后执行 |
| 需要确认的调用 | 独占一批（不与任何调用并行）；确认等待会顺延 deadline（D49） |
| terminal scope 调用 | 独占并终止本批；成功后结束 run（现有 `SCOPE_TERMINAL` 逻辑，`AgentLoop.kt:495-497`） |
| 未知工具 / 参数非法 | 不执行，结果按调用位次回填，继续 |
| 写超时未确认 | 终止本批，后续调用回填 `SKIPPED_AFTER_UNCERTAIN`，不执行 |

批次切分：把一次模型回复的 `toolCalls` 按“写/确认/terminal”切成若干段；每段内只读调用按通道去重后并行（最多 3），段与段之间按调用顺序串行。

### 2.3 结果回填

- 用与 `toolCalls` 等长的数组收集结果，**始终按调用索引回填**到 `history`（`ModelHistoryItem.ToolOutput`），不按完成顺序；每个 `tool_call` 都必须有对应 `tool_output`，否则 provider 的下一次请求会拒绝（参考 0.21 规格 §6.2 的工具往返要求）。
- 并行的读：等整段完成后一次性按索引追加；串行的写：每个写完成即追加。
- 中途取消：已完成的读结果可保留在内存，但 run 返回 `CANCELLED` 后不再发下一次模型请求；未完成的不追加。
- 工具状态回调 `onToolStatus` 仍按每次调用 START/END 发（`AgentLoop.kt:410,487-489`），显示层不需要知道并行细节。

### 2.4 `ProgressGuard` 改看观察状态指纹

- **现状问题**：payload 字符串既可能因计数后缀而不同（`notify_user`/`load_skill` 的规避，`NotifyUser.kt:72-74`；`LoadSkillTool.kt:26-27`），也可能因同一屏幕的不同序列化而不同；反之，不同动作可能产生同一 payload。它测的是“文本像不像”，不是“设备状态有没有变”。
- **建议**：`ToolResult` 增加 `observation: ObservationState?`（由 D1 提供）：

  ```kotlin
  data class ObservationState(
      val generation: Long,          // D1 写代际；写成功 +1
      val structuralFingerprint: String, // 结构指纹哈希；只比较，不打印
      val foregroundPackage: String?,    // id，不是正文
      val revision: Long,
  )
  ```

- `ProgressGuard.observeAfterAction(state)` 比较**结构指纹**；相同 → 连续计数 +1；不同 → 重置。generation 只用于丢弃过期读，不单独算进展（写后屏幕没变仍是 no-progress）。
- **喂入时机**：只喂**写**后的观察；读不喂 guard（读的轮询归 poll detector）。同一批多个写串行执行，每个写后喂一次；并行读不会喂 guard，所以一个含 3 个读的批次不会凑满 3 次相同指纹。
- **无观察状态的工具**（`load_skill`、`notify_user`）不再喂 guard；它们由 generic_repeat / 错误 streak 管。`notify_user`/`load_skill` 的自增计数可以保留（兼容既有测试），但不再是 guard 的正确性依赖；若删除，需同步改测试。
- **过期读**：`state.generation < lastWriteGeneration` 的读结果标记 `stale=true`，不喂 guard，不作为下一步动作依据；必要时自动重读一次（有界）。
- **与四类 detector 的分工**：guard 是“连续 3 次同观察状态”的快速终止；detector 是“调用身份 + 多种模式”的早停/警告。两者都保留，guard 命中仍返回 `NO_PROGRESS`（与今天一致），detector 命中返回 `LOOP_*`。**重叠优先级**：带观察的写调用连续 3 次同指纹 → guard 的 3 次先于 generic 的 5 次；warning 主要对 guard 看不见的模式生效（无观察工具、读轮询、ping-pong、global）。若规格要求“warning 一定先于终止可见”，需要把 guard 阈值提到 5 或对该签名停止喂 guard；本草案默认不改 guard 的 3 次，以保持现有快速停止行为。

### 2.5 与 DeviceControl generation 的配合

- D1 是 generation/代际的权威：每个写操作成功使 generation +1；每个读携带它观察到的 generation；D1 负责让跨代读可见地标 stale。
- D3 只消费：`ToolResult.observation`、`stale` 标志；**不自己生成指纹，不比较 payload**。
- 动作后自动观察：每个写工具的 result 应带 `applied` + 新的 `observation`（D1/D2 的 envelope），这样 loop 不需要额外发一次 `read_screen`；这是参考实现里最大的往返节省（预研 §1.5、§8.6）。若 D1 暂未实现自动观察，loop 保持现有“模型自己再读一次”的行为，不假装有 observation。
- 生成代际的边界：一次 tool batch 开始前记录 `startGeneration`；batch 结束后只有 `observation.generation > startGeneration` 的写才算“可能有效”。这与 `applied` 的判定不重复：`applied` 是工具自己的语义，generation 是并发安全。

---

## 3. 上下文压缩

### 3.1 预算单位与现状

- **统一预算（D81）**：`limit = min(inputBudgetTokens, usableWindowTokens - maxOutputTokens - safetyTokens)`；`ContextBudgeter` 用 UTF-8 字节 + 每项 32 的记账（`ContextBudgeter.kt:54-73,86`）；`TurnTrimmer` 用同一算式（`TurnTrimmer.kt:180-185`）。注意：这是**保守上界**——每个 token 至少占 1 个 UTF-8 字节（CJK 3 字节/字更保守），所以“字节数 ≥ token 数”，把字节数与 token 上限比较不会低估；它不是“字节预算”。
- **现状裁剪**：`TurnTrimmer` ①旧整回合（`TurnTrimmer.kt:126-138`）→ ②最不相关记忆（`:140-150`）→ ③只报告 `compressionOwed`（`:156`），③的异步摘要在回合尾（`ContextCompressor.kt:58-63`，D100：压缩不救当前请求）。
- **关键缺陷**：`TurnTrimmer` 的①把“当前回合的 utterance + 本轮工具轮”也视为一个 turn group；超预算时它可以把本轮工具轮整个删掉（因为 `isTurnStart` 只认 `TRANSCRIPT + USER`，当前 utterance 由 `toSegment` 映射为 `TRANSCRIPT/USER`，`SmartAssistantPlugin.kt:3331-3337`）。这样继续请求的模型只看到 `USER_INPUT`，看不到刚执行的工具结果，容易原地重试。**必须固定本轮工具轮。**
- `ContextBudgeter` 的整组淘汰也会删最后一组（`ContextBudgeter.kt:64-73`），同样需要“最后一组含工具项时不删”的规则，否则网关裁完、预算器又裁一次。
- 现有 `ConversationStore` 生产只写 user/assistant（`SmartAssistantPlugin.kt:2085-2086`），**不写任何工具轮/观察**；跨回合的旧树/旧图规则目前没有对象（§3.5）。

### 3.2 类型感知省略顺序（建议）

在**同一个统一 limit** 内，按以下顺序执行；每一步只在仍超预算时执行，且一次成型（oldest-first、确定、幂等）：

```
⓪ 类型感知省略（新增；D81 扩展 + D97 顺序变化）
  ⓪a 旧 UI 树 → header + 标记（保留最新一次完整树）
  ⓪b 旧图片 → 文字摘要（元数据占位；保留最新 K=3 张）
  ⓪c 超长工具结果 → 头/尾软修剪（最后一条工具结果在正常裁剪中豁免；overflow 硬模式除外）
① 旧整回合（D97 原①；新增“本轮固定 + 最近 N 轮/N 条 assistant 锚”）
② 最不相关记忆（D97 原②）
③ 压缩（D97 原③；仍只报告 compressionOwed，异步摘要不救当前请求）
```

| 规则 | 触发 | 动作 | 可见标记 | 豁免 | 元数据来源（不解析文本） |
|---|---|---|---|---|---|
| ⓪a 旧 UI 树 | 仍超预算，且存在非最新完整树 | 只保留 header（前台/窗口/包名/节点数，≤256 字节） | `[旧 UI 树已压缩；仅保留最近一次完整快照]` | 最新完整树 | D2 result envelope 的 `kind=UI_TREE` + `header`；frame `Segment.toolMeta` |
| ⓪b 旧图片 | 仍超预算，且历史图片 >K | 去掉 `ImageRef`，替换为文字摘要 | `[截图已省略：第 N 次观察，前台=…，尺寸=w×h]` | 最新 K=3 张；当前轮最新观察 | `ImageRef` 的 dims/ref + 观察元数据；**不调用 VLM 生成 caption** |
| ⓪c 超长工具结果 | 仍超预算，结果 > `min(16KB, limit/4)` 且不是最后一条 | 头 1.5KB + 尾 1.5KB + `[省略 N 字节]` | 头尾 + 省略计数 | **最后一条工具结果在正常裁剪中不截断**；overflow 硬模式除外 | envelope 的 `output` 字段；不要对 JSON 外壳做字符串截断 |
| ① 旧整回合 | 仍超预算 | 从最旧整回合开始删 | 无（现状） | 当前轮（utterance + 工具轮）固定；最近 N=2 轮、N=2 条 assistant 锚 | `TRANSCRIPT/USER` + `TOOL_ROUND` |
| ② 记忆 | 仍超预算 | 从最不相关端整条删 | 无（现状） | — | `MEMORY_EVIDENCE` |
| ③ 压缩 | ①真的删过且仍欠 | 报告 `compressionOwed`，回合尾异步摘要 | 摘要边界 | — | D100 |

**元数据缺失即跳过**：任何 ⓪ 规则要求 D2 的结构化元数据（`kind/header/images/output`）；如果某段来自旧调用或旧 store 数据、没有 `Segment.toolMeta`，该规则**跳过**，不靠正则/文本猜测，也不对 JSON 外壳做半截截断。这样旧数据的最坏结果是“没被压缩、继续走整回合淘汰”，而不是“被错误分类”。

**确定性与缓存**：⓪ 只在超预算时做一次批处理；每个对象压缩后保持稳定（旧树 header、图片文字摘要、头尾结果都不会再变），避免每轮重写前缀导致 provider prompt cache 全失效。是否值得为“保住旧回合”而牺牲 cache，需要在规格阶段用真实 provider 的 cache 计费验证；本草案选择“先保内容”。

**为什么最后一条工具结果豁免**：刚执行完的结果是模型下一步决策的直接依据；截断它会让模型基于残缺输出行动。参考 X-OmniClaw 明确跳过最后一条 tool 的字符截断（`context_manager.py:173-174,199-201`），e2e 的 overflow 才会硬截断（`tool-loop.ts:757-797`）。

**为什么 ⓪ 不触发 ③**：D100 的压缩门是 `droppedTurns > 0 && compressionOwed`（`DECISIONS.md:78`，`SmartAssistantPlugin.kt:2087-2091`）；⓪是规则压缩，不是“整回合被删”，不应让 LLM 去总结没被删的内容。`compressionOwed` 仍只由①/②决定。

### 3.3 本轮固定与锚

- **当前轮固定**：frame builder 需要给“本轮 utterance + 本轮工具轮”打 `pinned=true`（新增 `Segment.pinned: Boolean = false`，或在 `SegmentOrigin` 之外再记一个 `turnId`）；`TurnTrimmer.①` 不删 pinned 段。`ContextBudgeter` 看不到 `Segment`，因此用结构规则近似：**最后一组含 tool item 时，不删最后一组**；仍超则拒绝 → 走 overflow 恢复（§4）。这条规则同时修复“网关裁完、预算器再裁掉工具轮”的双重裁剪。
- **最近 N 轮/N 条 assistant 锚**：`N=2`（建议值，参考 X-OmniClaw 5 轮/3 条 assistant，`context_manager.py:17,22`，EDITH 窗口更小，取更小值）。①从最旧开始删，但**删到锚为止**；锚内不删。若锚 + ⓪ 仍放不下，进入②/③；正常裁剪不破锚。
- **overflow 硬模式可破锚**：见 §4.3；正常 pre-send 裁剪不破锚，避免“为了塞下而把最近上下文也删了”的不可解释行为。
- **N 的代价**：小窗口（如 4k）下，如果最后 2 轮本身就超预算，正常裁剪会走到“拒绝 → overflow 恢复”，而不是静默删掉最近轮。这是有意的：最近轮是模型正确性的最低保障。

### 3.4 D81 / D97 扩展矩阵

| 变更 | 性质 | 与 D81 的关系 | 与 D97 的关系 |
|---|---|---|---|
| ⓪a 旧 UI 树→header | 子回合**截断** | **显式扩展**：D81 写“不截断” | **顺序变化**：插在①前 |
| ⓪b 旧图片→文字摘要 | 子回合**移除**（保留元数据摘要） | 显式扩展（内容被省略） | 顺序变化 |
| ⓪c 长工具结果头尾 | 子回合**截断** | 显式扩展（D81 禁止截断） | 顺序变化 |
| 最后一条工具结果豁免（正常裁剪；overflow 硬模式除外） | 逐项豁免 | 显式扩展（D81 的单位是整回合/整条记忆） | ①细化 |
| 最近 N 轮/N 条 assistant 锚 | ①的保留下限 | 不改变统一预算 | **①行为变化** |
| 本轮工具轮固定 | ①的排除项 | 不改变统一预算 | **①行为变化** |
| 图片成本并入统一预算 | 记账单位扩展 | 扩展（不新增类别预算） | 不影响顺序 |
| ③压缩门 | 不变 | 不变 | 不变（D100） |
| per-category token 预算 | **不引入** | 遵守 D81 | — |

**不是 D81 扩展的部分**：`ContextBudgeter` 的单一 `minOf(...)` limit、`TurnTrimmer` 的字节+32 记账、`USER_INPUT` 不裁、③异步摘要不救当前请求——这些保持原样。

### 3.5 记录粒度：跨回合的旧树/旧图目前没有数据

- 生产 `recordTurnAndCompress` 只 `appendUser` + `appendAssistant`（`SmartAssistantPlugin.kt:2085-2086`）；`ConversationStore.appendToolCall/appendToolOutput` 存在但生产无调用（grep 结果只出现在测试）。所以：
  - “当前 run 内”的旧树/旧图/长工具结果规则有对象（多步 run 的 frame 里包含工具轮）；
  - “跨回合”的旧树/旧图规则**没有对象**，除非会话记录开始写观测。
- **建议（需批准）**：每回合记录**至多一个、有界**的观测：
  - `[user, assistant tool opener, ToolCall(observe), ToolOutput(header + ImageRef ≤K), assistant reply]`；只保留该回合**最后一次**观察；旧回合的观测由 ⓪ 压缩；超过 K 的图片只留 ref 元数据/占位。
  - 这样 `ContextBudgeter` 的组形状仍合法（assistant text 开 tool run，tool call/output 成对，最后 assistant 收尾；`ContextBudgeter.kt:25-53`），`ContextCompressor` 的整回合淘汰自然带走工具轮。
  - 若业主只想要“当前 run 的图片历史”，则跨回合规则可以不做；但 owner 前提“图片进历史、最近 K 张”通常意味着跨回合，必须明确（R3）。
- **D117**：store 是内存态，不落盘；日志只记数量/尺寸/id。图片字节只在 artifact store，store 只存 ref+metadata。

### 3.6 与异步摘要（③）的关系

- ⓪/①/② 都是同步、确定性、无模型调用；③ 仍由回合尾的 `ContextCompressor.compressStore` 异步执行，只影响下一回合（D100）。
- 类型感知压缩（⓪）**不写摘要**，因此不会把“被压缩的旧树/旧图”再变成一份摘要内容；摘要只覆盖①真正丢弃的整回合。
- 若未来要“旧图片→语义摘要”，那是一次额外的 VLM 调用，属于产品决策（R3 的子问题），本草案默认不调用。

---

## 4. overflow 恢复

### 4.1 检测点

| 检测 | 位置 | 现状 | 建议 |
|---|---|---|---|
| 本地装配超限 | `ContextBudgeter.prepare` 抛 `ConfigurationRejected`（`ContextBudgeter.kt:73,79`） | 直接冒泡到插件 → `FAILED/PLUGIN_RUN_THREW` | 网关捕获，走恢复阶梯；本地拒绝没有真正发请求，不计 model call |
| provider 上下文超限 | HTTP 400/413 的 error body | `ControlledModelClient` 目前统一映射 `FAILED/HTTP_STATUS`（`ControlledModelClient.kt:116-128`），不读 body | 有界读取（≤4KB）并按错误码/关键词分类为 `CONTEXT_OVERFLOW`；**不记录 body 正文**（D117） |
| 输出截断 | `ModelEnd.INCOMPLETE` / `INCOMPLETE_TURNS` | 已有处理（`AgentLoop.kt:348-364`） | **不要**当成 overflow；不触发恢复 |
| 估算偏差 | 发送成功但 provider 仍报超限 | 当前无法识别 | 同上 provider 检测 |

### 4.2 恢复阶梯

对**同一次** `GatewayTurn`，最多 2 次恢复尝试（建议值）：

1. **确定性压缩**：对当前请求的 history/frame 跑一次“硬模式 ⓪”：
   - 旧 UI 树全部→header（包括最新树；硬模式允许破“最后一条结果豁免”，但保留该结果的 header/文本，不整项删除）；
   - 图片只留最新 1 张，其余→文字摘要；
   - 所有非最后工具结果→头尾 1.5KB；
   - 仍保留本轮工具轮和最后一条工具结果（可被压成 header，但不删除该 tool 项）。
   若字节数没有下降，直接跳到失败（参考 e2e 的 “nothing to shrink” 判断，`tool-loop.ts:509-516`），不要用同一请求再烧一次 provider 调用。
2. **硬截断**：把剩余文本项统一裁到 16,384 字节头 + 说明（e2e `OVERFLOW_TEXT_CLIP_CHARS`，`tool-loop.ts:757-797`）；去掉所有历史图片（当前轮最新图可留 1 张）；仍保留本轮工具结果。**硬截断也必须按结构化字段做**：`TOOL_ROUND` 的 JSON 外壳不裁，只裁它内部的 `output`；如果该项无法结构化解析（没有 D2 metadata、JSON 破损），整项保留或整项丢弃并记 `hardClipSkipped`，**不能把半截 JSON 当文本消息发给 provider**。再发一次。
3. **可解释失败**：返回 `ModelEnd.FAILED` / `CONTEXT_OVERFLOW`，附一个**稳定、无正文**的说明；插件映射为“上下文过大，请新开会话/缩短任务”的失败提示。不做第 3 次重试。

**每层约束**：

- 每次重试都是**新的 model call**，计入 `modelCalls`/tokens；受同一回合 deadline 约束（D49）；剩余时间不足最小窗口（建议 5s）则直接失败，避免“重试到一半 deadline”。
- **不重新执行工具**：overflow 只发生在模型请求；工具结果已经在 transcript 里，恢复只改上下文。
- **不调用 LLM 摘要**：X-OmniClaw 的 overflow 恢复是“LLM 摘要压缩最多 3 次”（`ContextManager.kt:29-33,70-108`），EDITH 不用，因为 D100 明确压缩只影响下一回合，且当前请求需要确定性、可解释、零额外成本。
- **可解释**：用户看到的失败句子必须说明“上下文太大”，不能是 `PLUGIN_RUN_THREW` 或一个 HTTP 码；日志只记 `overflow=local|provider`、attempts、字节数。

### 4.3 与 `TurnTrimmer` / `ContextBudgeter` 的关系

- **职责**：`TurnTrimmer` 在发送前按统一 limit 做 ⓪①②；`ContextBudgeter` 是最后一道形状+预算校验，正常情况下不应再淘汰任何东西。测试必须证明：**trimmer 的输出（含图片成本）经 `prepare` 后 `removedIds` 为空**；否则说明两套记账漂移（当前 `-32` 安全边际只覆盖文本）。
- **最后一组规则**：`ContextBudgeter` 在“最后一组含 tool item”时不淘汰最后一组（§3.3）；仍超 → `ConfigurationRejected` → 恢复阶梯。否则它会把网关刚保留的工具轮再删掉。
- **本地拒绝的歧义**：`ConfigurationRejected` 同时覆盖“超预算”和“形状错误/末尾 lone USER group”（`ContextBudgeter.kt:10,50-53,73,79`）；恢复前必须区分，形状错误不能靠压缩重试。建议给 `ContractError` 增加可区分 reason（如 `CONTEXT_BUDGET_EXCEEDED` vs `REQUEST_SHAPE_REJECTED`），或让预算器先做形状校验再报预算；否则会把“装配 bug”误报成“上下文太大”。
- **legacy 无 frame 路径**：没有 `TurnTrimmer` 可复用；如果该路径仍在生产，给它在网关内一个 `TranscriptCompactor` 或直接以 `CONTEXT_OVERFLOW` 失败，**不要**把未裁剪的历史静默发出去。
- **恢复的归属**：建议放在 `ModelClientGateway`（它拥有 frame、`lastTrim`、重试/计数的位置）或一个 `RecoveringModelGateway` 装饰器；不要让 loop 重发（loop 不拥有 frame，重发会重复计数/重复 nudge）。`GatewayReply` 增加 `attempts`/`usage`，loop 只累加。
- **`lastTrim` 扩展**：新增计数 `compactedTrees`、`compactedImages`、`trimmedToolResults`、`pinnedSegments`、`hardClipped`；仍是 `TrimCounts` 风格的“无文本”类型，可安全打印（`ModelClientGateway.kt:76-85`）。

### 4.4 `ContextPlanner`：死代码，建议删除

- 生产无调用：只有自身与 `ContextPlannerTest`；eval 工具明确注释“生产不构造 `ContextPlanner`”（`TraceExtractor.kt:28-31`，`Main.kt:222`）。
- 它的规则与 D97 冲突：它先“摘要化超长工具结果”（`ContextPlanner.kt:52-59`），而 D81 明确“不截断”；它按自己的分组删除（`:71-78`），与 `TurnTrimmer` 的整回合边界不是同一套。
- **建议**：在实现阶段删除 `ContextPlanner.kt` 与对应测试；不要保留“参考用”死代码，也不要把它改名成新压缩器（新规则需要 D2 元数据，和它的纯文本模型不兼容）。若团队坚持保留一个纯函数测试接缝，重写为 `ContextCompactor` 并只服务新规则；本草案不推荐。
- **不影响** eval 的 `degraded` 指标：该指标本来就一直是 `false/NotMeasured`（`TraceExtractor.kt:28-31`），删除后仍是 NotMeasured，不得改写成通过。

### 4.5 失败语义

- `CONTEXT_OVERFLOW` 是**可解释失败**，不是 `NO_PROGRESS`；用户的下一步是“新开会话/缩短任务”，不是“再试一次同样的话”。
- 本地拒绝（没发请求）与 provider 拒绝（发了请求）在日志里区分：`overflow=local|provider`；两者的重试次数、字节数都记录。
- 如果图片/超长工具结果是主因，日志记 `largestItem=kind:bytes`（kind 是枚举，bytes 是数字；不记内容/id）。

---

## 5. 图片 token 记账与历史

### 5.1 现状（代码事实）

| 事实 | 位置 |
|---|---|
| `InputPart.Image(localRef)` 契约存在 | `ModelServices.kt:56-59` |
| `CapabilityGate` 能把 IMAGE 纳入输入模态并检查 consent.image / `IMAGE_REQUEST` 验证 | `CapabilityGate.kt:16-26,34-47` |
| 但 `ContextBudgeter` 拒绝任何非 Text 输入 | `ContextBudgeter.kt:13-15` |
| `MediaSource` 只有 `resolveAudio`，没有图片解析 | `MediaSource.kt:24-26` |
| OpenAI Chat Completions 的生产编码只处理 text+audio，且**丢弃 tool calls/outputs** | `OpenAiChatCompletionsAdapter.kt:167-217`（丢弃见 `:186-193`） |
| DeepSeek Responses 适配器要求全部 Text，但支持工具往返 | `DeepSeekResponsesAdapter.kt:84-109,111-126` |
| `ModelHistoryItem.ToolOutput` 只有 `String`，历史项无法带图 | `ModelServices.kt:101-104` |
| `Terminal.usage` 已解码，但网关回包不携带 | `ModelServices.kt:143-145`；`ModelClientGateway.kt:195-213` |
| 生产 `ConversationStore` 不记录工具观测 | `SmartAssistantPlugin.kt:2085-2086` |

**结论**：图片能力目前只有“契约 + 能力闸门”，没有编码、没有预算、没有历史形状；任何“图片进历史”的设计必须先补 §5.6 的四个接缝。

### 5.2 成本估算

- **缩放**：截图/图片落 artifact 前统一缩到**长边 ≤768 px**（建议值；预研 §8.7 也建议 768），减少 tile 数与字节。
- **成本公式**（保守上界，参考 e2e）：`imageUnits = ceil(width/28) * ceil(height/28)`，`28 px` 是 tile 边长；例如 768×768 → 784，768×432 → 448。e2e 用同一公式做 pre-flight（`adapter.ts:106-121`；`sdk.ts:69-78`）。
- **单位**：把 `imageUnits` 直接加到 `ContextBudgeter`/`TurnTrimmer` 的“字节+tile”总数里，与 token 上限比较。文本字节是 token 上界，图片 tile 数也是 token 上界，两者相加仍是上界；**不新增图片类别预算，不换算成“字节”再乘系数**（乘系数会引入第二个口径）。需要明确：这个数字是**保守估计**，不是 provider 的精确计费；未实测（R3）。
- **缺失 artifact**：预算按占位文本的字节计，不加 imageUnits；编码时替换成占位（§5.4）。
- **每次请求的图片数**：≤K（见 §5.3）；当前轮最新观察始终保留。

### 5.3 历史保留 K 张与降级

- **K=3（建议值，未标定）**：整个请求（含当前轮）最多 3 张图；超出的旧图去掉 `ImageRef`，替换为文字摘要。
- **保留顺序**：最新在前；当前轮最新观察永远保留；同一回合多张图只保留最后 K 张。
- **降级文本**：用**已有元数据**拼，不调用 VLM：
  `[截图已省略：第 N 次观察，前台=<package id>，尺寸=WxH，时间=相对序号]`
  如果该观察有 UI 树 header，拼上 header；如果只有图，至少保留“第 N 次观察/前台/尺寸”，让模型知道曾经看过什么、需要时可以重新观察。**这就是 owner 要求的“更早的图降级成文字摘要”在本草案中的落地形态**：摘要来自已有元数据，不是 VLM caption；若要求语义摘要，见 R3。
- **触发**：只在实际超预算时做（⓪b），不是每轮都重写；一旦某张图被降级，保持稳定，避免每轮改前缀。若 K 张图本身就在预算内，不动。
- **图片何时产生**：由 D1/D2 的视觉策略决定（结构树优先；低置信/空树/SurfaceView/验证失败才走截图兜底）；D3 只负责“图片已经产生之后”的记账、保留 K 张与降级，不重新论证视觉策略。
- **与“旧 UI 树→header”的关系**：同一观察可能同时有树和图；先树后图（⓪a→⓪b），因为树 header 更便宜且信息密度高；图降级后仍保留 header 占位。
- **“旧图片→摘要”不是 caption**：本草案不生成视觉摘要；如果产品要求“更早的图变成一句语义摘要”，那是额外 VLM 调用，列入 R3 的子问题。默认实现是“元数据占位”，诚实且零成本。

### 5.4 artifact 缺失时的降级

- **音频 vs 图片的区别**：音频缺失是用户请求内容缺失，应拒绝（现有 `MediaSource.resolveAudio` 返回 null → `CapabilityMissing`，`OpenAiChatCompletionsAdapter.kt:172-176`）；图片是**观察**，缺失不应让整个请求失败。
- **规则**：
  1. 编码前解析 `ImageRef`；解析失败 → 用文字占位替代该图（`[图像不可用：ref 已过期或未落盘]`），继续请求；
  2. `ContextBudgeter` 对缺失图只计占位字节；
  3. metrics 记 `imagesMissing++`（只计数）；
  4. 模型若需要图，按提示重新观察（工具调用）；
  5. **不得**静默把“应该有图”的请求降级成纯文本而不告诉模型——占位就是告诉。
- **不把图塞进 tool result 文本**：二进制/base64 永不进入 context；artifact 只存 ref+metadata（§5.5）。

### 5.5 `ConversationStore` 持久化引用而非字节

- **当前**：`ConversationStore` 是内存态（`ConversationStore.kt:49` 的 `mutableListOf`；激活时清空，`SmartAssistantPlugin.kt:870-873`），没有跨进程持久化。本草案**不新增会话落盘**。
- **硬约束（无论现在还是将来）**：store 里只允许存 `ImageRef(ref, width, height, byteSize, sha256?)` 与树 header，**不允许存图片字节/base64/文件路径**。原因：内存、隐私（D117）、序列化体积、artifact TTL。
- **artifact store**：由宿主/DeviceControl 管理（缓存目录 + TTL + 容量上限）；`MediaSource` 增加 `resolveImage(ref): ResolvedImage?`（bytes/mime/width/height）。artifact 的路径不写日志、不进 store。
- **若未来要持久化会话**：序列化 `ModelHistoryItem` 时只写 ref+metadata；加载时若 artifact 已过期，按 §5.4 占位。**不得**为了“历史图还在”而把字节写进会话文件。

### 5.6 协议/契约影响（落地前必须补）

1. `ModelHistoryItem.ToolOutput` 增加 `images: List<ImageRef> = emptyList()`（默认空，旧调用不变）；`ConversationStore.appendToolOutput` 增加同参数；`Segment.toolMeta` 携带同一信息，frame renderer 恢复。
2. `ToolResult` 增加 `images` 与 `observation`（§2.4）；D2 的 result envelope 提供 `kind=UI_TREE|SCREENSHOT|TEXT`、`header`、`images`；**loop 不解析文本**。
3. `ContextBudgeter` 接受 `InputPart.Image` 与历史 `ImageRef`，通过注入的 `MediaEstimator` 计成本；缺失 estimator 时仍按现状抛 `CapabilityMissing`（不发明比例）。
4. `MediaSource.resolveImage` + `ResolvedImage`；`OpenAiChatCompletionsAdapter` / `DeepSeekResponsesAdapter` 实现图片编码。**注意**：工具结果里的图片在 OpenAI Responses/Chat Completions 里不能放进 `function_call_output`/`tool` 消息本身，通常要在其后追加一条带 `input_image`/`image_url` 的 user 消息；具体形状**未实测**，实现前必须用受控探针验证（不能靠推断上线）。
5. 生产 OpenAI 编码器当前丢弃 tool calls/outputs（`:186-193`）——这与 0.21 规格 §6.2 的“必须支持工具往返”（`2026-10-17-edith-0.21-baseline-architecture-design.md:241-246`）不一致；在任何“工具结果进上下文”的验收前必须修复，否则该协议的上下文规则无法验证。本草案只记 drift，不改代码。
6. 只允许配置 `inputs` 含 IMAGE 且 `IMAGE_REQUEST` 验证通过的档案（`CapabilityGate.kt:34-47`）；没有 IMAGE 档案时设备工具不产图，观察只走树。不要静默降级成纯文本。
7. **下一回合压缩目标的估算**：`ContextCompressor.compressStore` 用 `estimatedText()` + `estimateTokens` 量 store（`TokenBudget.kt:96-111`）；如果 store 开始记录图片，`estimatedText()` 至少要计入 `ImageRef` 元数据，或让压缩器也走 imageUnits，否则下一回合的压缩目标会低估。该项与 D100 的压缩门无关，只影响“下一回合是否该压缩”的数值。

---

## 6. Metrics

### 6.1 数据形状（只计数/耗时/id，无正文）

```kotlin
// 草案形状
data class AgentMetrics(
    val modelCalls: Int,              // 实际发出的请求数（含 overflow 重试）
    val inputTokens: Long?,           // provider usage 累加；未报告 = null，不估造
    val outputTokens: Long?,
    val modelNanos: Long,
    val toolCalls: Int,
    val toolNanos: Long,
    val observationCalls: Int,
    val observationNanos: Long,
    val loopWarnings: Int,
    val loopStops: Int,
    val repeatedErrors: Int,
    val planNudges: Int,
    val overflowRetries: Int,         // local + provider 分开也可
    val imagesAttached: Int,
    val imagesDropped: Int,
    val imagesMissing: Int,
)
```

- `AgentRun` 增加 `metrics`；`AgentStep` 增加 `durationNanos`、`timedOut: Boolean`、`channel: String?`、`effect: Effect?`（只枚举/id）。
- **token 累加来源**：`ModelStreamEvent.Terminal.usage`（`ModelServices.kt:143-145`），网关现在在 `reply()` 里丢掉（`ModelClientGateway.kt:195-213`）；需要 `GatewayReply.usage` 并逐次累加。`Terminal` 已由两个解码器填 usage（`ChatCompletionsDecoder.kt:47-58`，`ResponsesDecoder.kt:251-260`）。
- **不要造数**：provider 未报告 usage 时记 `null`，不要用字节估算冒充“实测 token”；如需估算，单独字段 `estimatedInputUnits`（来自预算器，明确标注是估算）。
- **“每轮”**：`AgentRun.metrics` 是整 run 汇总；每步耗时在 `AgentStep`；`P3_METRIC` 一行汇总即可。若要做每轮细账，放 debug-only 日志（D116），不新增生产正文日志。

### 6.2 采集点

| 指标 | 采集位置 |
|---|---|
| model 耗时/调用数 | `AgentLoop` 包 `gateway.turn` 的单调时钟；`GatewayReply.attempts` 含重试 |
| tool 耗时 | `AgentLoop` 包每次 `registry.execute`（只算执行，不含确认等待；D49 人时另计） |
| observation 耗时 | 同上，按 D2 的 `observation=true` 归类 |
| token | `GatewayReply.usage` 逐次累加 |
| trim | `ModelClientGateway.lastTrim`（扩展计数） |
| loop warning/stop | detector 计数器 |
| 图片 | 编码前解析计数（attached/dropped/missing） |

### 6.3 日志

- **新增独立行**，不改既有形状：
  `P3_METRIC turn=<id> modelCalls=.. modelMs=.. toolCalls=.. toolMs=.. obsCalls=.. obsMs=.. inTokens=.. outTokens=.. warnings=.. stops=.. nudges=.. overflow=.. images=attached/dropped/missing`
- 现有 `P3_TURN end ... steps=` 与 `P3_TOOL` 是 eval 解析器的输入（`TraceExtractor.kt:3-5`）；`P3_TURN` 的 `steps=(\d+)` 后面追加字段是安全的，但**不要改** `P3_TOOL` 的 `detail=` 形状（`:5`）。为避免任何风险，新字段全部放 `P3_METRIC`。
- `turnId` 可以记（已有）；不记用户文本、工具参数、错误 detail、图片 ref/路径。错误身份只记稳定码/哈希前缀。

### 6.4 隐私

- 所有 metrics/log 只允许：计数、枚举、耗时、id、哈希前缀、字节数（D117）。
- 图片只记数量/尺寸/字节数；不记 ref（ref 可能含路径）；不记 base64。
- 错误 detail 不进日志；如必须区分，记 `errorHash=<16 hex>`。

---

## 7. JVM 测试清单（每条规则一个可测场景；不依赖设备）

> 用 fake `ModelGateway` / fake `ActionExecutor` / fake `MonotonicClock` / fake `AccessibilityUi`；并行测试用 latch/barrier，不用 sleep 竞态。所有场景都在 JVM 跑。

| # | 规则 | 场景 | 期望 |
|---:|---|---|---|
| 1 | generic repeat warning | 无观察工具（如 `load_skill`）连续 3 次同 tool+args **尝试**后，第 4 次同调用 | 第 4 次不执行，返回 `LOOP_WARNING:GENERIC_REPEAT`；executor 调用次数不增 |
| 2 | generic repeat stop | 无观察工具连续 5 次同调用**尝试**后，第 6 次同调用 | 第 6 次不执行并终止 `NO_PROGRESS/LOOP_REPEAT` |
| 3 | poll no progress | 同 observation 调用 3 次同指纹；第 4 次 | warning；指纹变化后计数重置 |
| 4 | ping-pong | A/B/A/B（2 周期）→ 3 周期且无进展 | 先 warning 后 `LOOP_PINGPONG`；有进展时不 stop |
| 5 | global breaker | 12 次无进展动作（不同调用也算） | `NO_PROGRESS/LOOP_GLOBAL`；第 8 次只注入一次 notice |
| 6 | warning 不持久 | 触发 warning 后检查 store | store 大小/内容不变；下一请求能看到 warning tool result |
| 7 | 相同错误 3 次 | 同 tool+同稳定码连续 3 次 | `FAILED/REPEATED_ERROR`；detail 不进日志 |
| 8 | 不同错误失败 streak | 3 个不同码失败 → warning；5 个 → stop | warning/stop 各一次；成功写重置，观察成功不重置 |
| 9 | 确认拒绝不计错误 | `CONFIRMATION_DENIED` 连续 3 次 | 不触发 `REPEATED_ERROR`；注入“不要自动重试”提示 |
| 10 | 运行态提示 | 有 pending verify + 前台 id | 请求含 `RUNTIME_STATE`；`ContextBudgeter` 通过；store 不变；相同提示去重 |
| 11 | 计划 nudge | 计划文本 + 已成功用工具 | 追加 `[assistant, user nudge]`；≤3 次；第 4 次不触发 |
| 12 | nudge 不触发 | 纯问答（无工具）/完成文本/PROACTIVE | 零 nudge；`COMPLETED` |
| 13 | 工具超时 | fake executor 睡过 timeout | `ACTION_TIMEOUT`；`cancel()` 被调用；run 继续 |
| 14 | 超时不确定 | fake executor 忽略 cancel 超过宽限 | `ACTION_TIMEOUT_UNCERTAIN`；后续写不执行 |
| 15 | 步数/观察分离 | 10 次 observation + 5 次写 | `spentSteps=5`、`spentObservations=10`；`steps.size=15` |
| 16 | 观察预算 | 第 24 次 observation | `NO_PROGRESS/OBSERVATION_BUDGET` |
| 17 | 取消传播 | 工具阻塞时 `cancel()` | executor 收到 cancel；`CANCELLED`；无迟到 tool output |
| 18 | 并行读（不同通道） | 两个 fake 通道各阻塞，latch 证明重叠 | 两个并发开始；结果按调用顺序回填 |
| 19 | 同通道读串行 | 两个 a11y fake 读 | 第二个在第一个结束后开始 |
| 20 | 写串行 | 3 个写 + 中间读 | 写不重叠；读在正确的写之后 |
| 21 | guard 指纹 | 相同 payload 但观察指纹变化 | 不 trip；相反 payload 相同指纹 3 次 → trip |
| 22 | guard 批次 | 一个含 3 个并行读的批次 | guard 不观察读，不 trip |
| 23 | generation stale | 读的 generation 落后于写 | 标记 stale，不喂 guard，不当作进展 |
| 24 | 旧树→header | 两棵树 + 一个最新 | 只最新完整；旧树 header+标记；幂等 |
| 25 | 旧图→文字摘要 | 4 张图，K=3 | 最旧 1 张变文字摘要；成本不含其 tile；最新保留 |
| 26 | 图缺失 | artifact resolver 返回 null | 占位；请求成功；`imagesMissing=1`；预算只计占位 |
| 27 | 图片成本 | 768×432 | `imageUnits=448`；与文本框合计后 budgeter 口径一致 |
| 28 | 长工具结果 | 20KB 非最后 / 20KB 最后 | 非最后头尾+省略；最后原样 |
| 29 | 最后结果豁免+overflow | 最后结果超预算 | 正常 ⓪ 不动；overflow 硬截断才裁 |
| 30 | 锚 | 5 轮历史、N=2 | ①不删最近 2 轮/2 条 assistant；锚阻止时走②/③/拒绝 |
| 31 | 本轮固定 | 超预算的两轮 frame，当前轮有工具轮 | ①不删当前轮；模型仍看到最后 tool result |
| 32 | 预算器不二次淘汰 | trimmer 输出交给 `prepare` | `removedIds` 为空（含图片成本） |
| 33 | 本地 overflow | `ConfigurationRejected` | 压缩一次→成功；第二次→硬截断；第三次→`CONTEXT_OVERFLOW` |
| 34 | provider overflow | 400 body 分类 | `CONTEXT_OVERFLOW`；body 不进日志；重试计 modelCalls |
| 35 | 恢复不重跑工具 | overflow 重试 | executor 调用次数不变 |
| 36 | 恢复上限/时间 | 连续 overflow | 最多 2 次；deadline 不足时直接失败 |
| 37 | ContextPlanner 删除 | grep/编译 | 无生产引用；eval 仍 NotMeasured（不新增通过） |
| 38 | metrics 计数 | 一次含 1 次重试/2 工具/1 nudge 的 run | modelCalls/toolCalls/nudges/usage 正确；`null` usage 不造假 |
| 39 | metrics 隐私 | 用 canary 文本跑一次 | 所有 log/metrics 字符串不含 canary |
| 40 | store 只存 ref | 记录含图工具轮 | store 字节数不随图片字节增长；序列化只有 ref+metadata |

---

## 8. 待裁决问题（5 条）

### R1 — D81：是否允许“子回合类型感知省略 + 头尾软修剪”？

- **冲突点**：D81 明确“超限按整回合/整条记忆省略，**不截断**”（`DECISIONS.md:73`）；本草案的 ⓪a/⓪b/⓪c 会改写单个工具结果/树/图片，属于截断/移除。
- **建议**：批准为**显式扩展**：只在统一窗口预算内、只在超限时、只对“旧/非最新对象”执行（最新完整树、最新 K 图、最后一条工具结果分别在各自规则里豁免），且带可见标记；不新增 per-category 预算。若拒绝，则旧树/旧图/长工具结果只能继续随整回合一起丢，无法满足 owner 前提 1 的“图片进历史 + 旧图降级”。
- **需要业主明确的**：⓪c 的阈值（建议 `min(16KB, limit/4)`）与头尾大小（1.5KB/1.5KB）是否接受为**可配置默认值**（未标定）。

### R2 — D97：是否允许在①前插入 ⓪，并给①加锚/本轮固定？

- **冲突点**：D97 规定固定三刀顺序①旧整回合→②记忆→③压缩（`DECISIONS.md:75`）；本草案在①前插入 ⓪，并在①内增加“最近 N=2 轮/N=2 条 assistant + 本轮工具轮固定”。
- **建议**：批准；否则“最后一条工具结果豁免”无法实现——现行①会把当前轮工具轮整个删掉（`TurnTrimmer.kt:126-138` + `SmartAssistantPlugin.kt:3331-3337`），继续请求的模型看不到刚执行的结果。
- **需要业主明确的**：N=2/2 是否可接受；正常裁剪遇到“锚+⓪仍放不下”时，选择**拒绝→overflow 恢复**（本草案）还是**允许破锚**。本草案建议正常裁剪不破锚，仅 overflow 硬模式可破锚（§4.3）。

### R3 — 图片：K、成本公式、记录粒度、旧图摘要方式

- **冲突点**：D81 的统一预算/不截断（图片降级是移除，但保留元数据摘要）；现行 `ContextBudgeter` 拒绝非文本（`ContextBudgeter.kt:13-15`）、`MediaSource` 无图片解析（`MediaSource.kt:24-26`）、生产编码器无图片；这些是实现缺口，不是决策冲突，但会影响“统一预算”的口径。
- **建议**：
  1. K=3；长边 768；成本 `ceil(w/28)*ceil(h/28)` 直接并入总量；
  2. `ToolOutput` 携带 `ImageRef`，store 只存 ref+metadata；
  3. 每回合记录至多一个**有界观测**（树 header + ≤K 图），否则跨回合旧图规则没有对象（§3.5）；
  4. 旧图降级用元数据占位，不调用 VLM 生成 caption；如要语义摘要，需另批 VLM 调用。
- **需要业主明确的**：K=3 与 28px tile 是否接受为**保守估算默认值**（未实测）；是否要求旧图必须生成语义摘要（成本/延迟另算）。

### R4 — 观察预算与 D-P3-10 / D110

- **冲突点**：`AgentLoop` KDoc 记录 spec D-P3-10 “forbids token/step budgets”（`AgentLoop.kt:188-189`）；D110 规定步数豁免只能走描述符声明，生产唯一 `false` 是 `load_skill`（`DECISIONS.md:113`）。本草案新增 `maxObservations=24`，并建议观察工具 `countsTowardStepLimit=false`。
- **与 D81/D97 的关系**：观察预算不是上下文预算，不改变统一窗口预算与三刀顺序，故与 D81/D97 **无直接冲突**；它若被理解成“任务预算”，才与 D-P3-10 的旧口径冲突。需要在规格里明确它的“挂死/防刷屏保护”定位。
- **建议**：明确 `maxObservations` 是**挂死/防刷屏保护**，不是任务预算；仍走描述符声明（D110），不按工具名硬编码；与 `maxSteps`、回合 deadline、工具超时是四道不同的闸。
- **需要业主明确的**：24 是否可接受（未标定）；是否允许观察工具步数豁免（否则观察会吃掉一半 24 步，工具面只剩 5–6 个时更不合理）。

### R5 — overflow 恢复与 D100/D81/D97

- **冲突点**：D81 不截断；D97 的③压缩只影响下一回合（D100，`ContextCompressor.kt:58-63`）；本草案要在**当前请求**上做“确定性压缩 + 硬截断 + 重发”，最多 2 次。
- **建议**：批准为**独立的 overflow 恢复路径**，不是 D97 的③：不调用 LLM 摘要、不重跑工具、只在本地/provider 明确报超限时触发、最多 2 次、失败给 `CONTEXT_OVERFLOW`。硬截断只在“第二次”发生，并带长度说明。
- **需要业主明确的**：是否允许硬模式**破锚**（删最近 N 轮中除当前轮外的旧轮）；是否接受“最多 2 次”的模型调用成本；是否同意删除 `ContextPlanner`（死代码，不涉及用户可见行为）。

---

## 9. 未测、推断与风险（不得读成通过）

- **全部阈值未在 EDITH 标定**：3/5、2/3、12、8、N=2、K=3、30s/120s、2 次恢复都是建议值；没有 EDITH 任务语料上的成功率/成本读数。参考值来自 e2e（`loop-guards.ts:44-52`）与 X-OmniClaw（`loop_detector.py:14-17`），不能直接当 EDITH 结论。
- **图片成本是保守上界，不是实测计费**：28px tile 是 model-agnostic 的粗略上界；不同 provider 的 vision 计费不同（OpenAI 512px tile 等）。未做任何 provider 账单对照。
- **图片 wire shape 未实测**：工具结果后的图片在 Chat Completions/Responses 里的合法形状没有在 EDITH 上验证；实现前必须做受控探针（P5 风格），不能只靠文档推断。
- **性能未测**：并行读、超时、压缩的 p50/p95 都没有读数；本草案不宣称任何延迟目标。
- **任务成功率未测**：类型感知压缩/旧图降级对模型正确性的影响未知；需要固定语料 A/B。
- **已知 drift（本草案不改）**：
  1. 生产 OpenAI Chat Completions 的 `BudgetedRequest` 编码丢弃 tool calls/outputs（`OpenAiChatCompletionsAdapter.kt:186-193`），与基线规格 §6.2 的“必须支持工具往返”不一致（`2026-10-17-edith-0.21-baseline-architecture-design.md:241-246`）。
  2. `ContextBudgeter` 拒绝非文本（`:13-15`），而 `CapabilityGate` 已能接受 IMAGE（`:16-26`）——能力闸门比预算器超前。
  3. `ModelClientGateway` 丢弃 `Terminal.usage`（`:195-213`），metrics 目前没有真实 token 来源。
  4. 生产 `ConversationStore` 不记录工具轮/观测（`SmartAssistantPlugin.kt:2085-2086`），跨回合旧树/旧图规则没有对象。
  5. `TokenBudget` 仍以固定 4096 分类比例驱动 `compressStore` 的下一回合压缩目标（`TokenBudget.kt:17-63`；`SmartAssistantPlugin.kt:2091`）；它不在本草案的 pre-send 统一预算里，但它是“per-category”既有实现，规格阶段必须确认它与 D81 的关系，不要新增第二套分类预算。
  6. `ContextPlanner` 生产未接线（`TraceExtractor.kt:28-31`、`Main.kt:222`）。
- **未做设备操作**：本文没有连接 K40/眼镜，没有跑 `a11yprobe`，没有新的设备证据。

---

## 10. 对 D1 / D2 的接口要求（不重复它们的 schema）

> 本节只列 D3 依赖的**语义**；字段名、工具名、参数形状由 D1/D2 定。

| 依赖 | D3 需要什么 | 用途 |
|---|---|---|
| D1 | `ObservationState(generation, structuralFingerprint, foreground, revision)` | `ProgressGuard`、stale 判定、运行态提示 |
| D1 | 每操作取消令牌 + 每操作超时/宽限 | §1.7/§1.9 |
| D1 | 写后自动观察（`applied` + 新 observation） | 减少模型往返；guard 的输入 |
| D1 | 通道 id + `parallelSafe` 声明 | §2.2 的并行边界 |
| D1 | 截图 artifact（ref/width/height/bytes/sha）与 `resolveImage` | §5 |
| D2 | `effect=READ/WRITE/TERMINAL`、`observation=true/false`、`timeoutMillis`、`scope=terminal`、`countsTowardStepLimit` | detector 分类、超时、步数/观察分离 |
| D2 | result envelope：`ok/code/applied/observation/images/kind/header` | 压缩与 guard，禁止 loop 解析正文 |
| D2 | 稳定错误码（`NODE_STALE`/`NODE_NOT_FOUND`/`CHANNEL_UNAVAILABLE`/`ACTION_TIMEOUT`…） | 错误追踪与提示 |
| D1/D2 | 最后一个动作的语义（typed / sent / pending verify） | 运行态提示 §1.5 的“已输入未发送/写后未验证” |

**D3 不需要的**：具体工具名、参数 schema、a11y/Shizuku 通道实现、确认闸门的风险等级定义。这些分别属于 D1/D2 和既有 `WhitelistActionPolicy`。工具面按 owner 前提收敛到 5–6 个（D121 落地后），D3 不引入 on-demand 路由；detector 的分类只依赖 D2 的元数据，不按工具名硬编码。确认闸门的范围（只覆盖数据不可逆与花钱、外呼不问）由既有策略/D2 决定；D3 只处理确认等待的人时（D49）。

---

## 11. 落地影响面（不是实施计划）

| 文件 | 预期变化 |
|---|---|
| `agent-runtime/.../AgentLoop.kt` | detector、观察预算、超时包装、并行批次、nudge、metrics、cancel 钩子 |
| `agent-runtime/.../ProgressGuard.kt` | 从 payload 字符串改为 `ObservationState` 指纹 |
| `agent-runtime/.../ToolProtocol.kt` | `ToolResult.images/observation`、`ToolDescriptor.timeout/effect`（具体字段与 D2 对齐） |
| `agent-runtime/.../TurnTrimmer.kt` | ⓪、锚、本轮固定、图片成本、扩展 `TrimCounts` |
| `agent-runtime/.../ContextPlanner.kt` | **删除**（连同测试）；不复用 |
| `agent-runtime/.../ConversationStore.kt` | `appendToolOutput(images)`；可选的有界观测记录 |
| `agent-runtime/.../RequestFrame.kt` / `Segment.kt` | `RUNTIME_STATE`、`toolMeta`、`pinned` |
| `agent-runtime/.../ModelGateway.kt` / `ModelClientGateway.kt` | `usage/attempts`、overflow 恢复、图片映射、`RUNTIME_STATE` 折进 input、trim 计数 |
| `model-runtime/.../ContextBudgeter.kt` | 接受图片 + `MediaEstimator`；最后一组含工具项时不淘汰 |
| `model-runtime/.../MediaSource.kt` | `resolveImage`/`ResolvedImage` |
| `model-runtime/.../OpenAiChatCompletionsAdapter.kt` / `DeepSeekResponsesAdapter.kt` | 图片编码；OpenAI 工具往返 drift 修复（独立缺陷） |
| `model-runtime/.../ControlledModelClient.kt` | provider overflow 分类（有界读 body，不记正文） |
| `plugin-smart-helper/.../SmartAssistantPlugin.kt` | 观测记录、runtime state、`P3_METRIC`、nudge 开关、步数/图片记录 |
| `common/contracts/plugin-api/.../ModelServices.kt` | `ToolOutput.images`、`ModelUsage` 透传 |
| `common/contracts/plugin-api/.../Actions.kt` | 超时/effect/observation 元数据（与 D2 对齐） |
| 测试 | §7 的 40 个场景；所有 fake 无 Android 依赖 |

---

## 12. 参考索引

**EDITH 代码**

- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/AgentLoop.kt:15,24-25,188-199,258-266,298-307,344,348-387,393-502,525-550`
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ProgressGuard.kt:14-37`
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/TurnTrimmer.kt:30-36,113-170,180-185`
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ModelClientGateway.kt:84-105,108-125,143-213,236-242,264-322`
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ConversationStore.kt:41,49,70-90,108-110,127-145,176-215,294-315,346-348`
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/RequestFrame.kt:12-53`；`Segment.kt:9-30`
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ContextPlanner.kt:41-80`；`ToolProtocol.kt:22-33`；`ToolRegistry.kt:84-98`
- `phone-app/assistant/model-runtime/src/main/kotlin/com/sheen/rokid/model/ContextBudgeter.kt:13-15,25-53,54-86`
- `phone-app/assistant/model-runtime/src/main/kotlin/com/sheen/rokid/model/OpenAiChatCompletionsAdapter.kt:167-217`；`DeepSeekResponsesAdapter.kt:84-126`；`MediaSource.kt:24-26`；`CapabilityGate.kt:16-47`
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/a11y/AccessibilityExecutionBackend.kt:157-158,174-180,230-239,258-265`
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/ExecutionRouter.kt:43-63`
- `phone-app/plugins/plugin-smart-helper/src/main/kotlin/com/sheen/rokid/phone/plugin/smarthelper/SmartAssistantPlugin.kt:870-873,1907-1937,1972-1976,2081-2092,2135-2218,2221-2235,2280-2290,3260-3273,3331-3365`
- `phone-app/plugins/plugin-smart-helper/src/main/kotlin/com/sheen/rokid/phone/plugin/smarthelper/output/NotifyUser.kt:66-74,94`；`skill/LoadSkillTool.kt:26-27,59`
- `phone-app/plugins/plugin-smart-helper/src/main/kotlin/com/sheen/rokid/phone/plugin/smarthelper/context/ContextCompressor.kt:58-63`；`TokenBudget.kt:17-63`
- `common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/Actions.kt:20-51,70-76`；`ModelServices.kt:56-59,101-104,134-145`
- `tools/eval/p3-eval-trigger/src/main/kotlin/edith/probe/eval/TraceExtractor.kt:3-5,28-31`；`Main.kt:222`
- `docs/project/DECISIONS.md:64,73,75,113,131`；`docs/superpowers/specs/2026-10-17-edith-0.21-baseline-architecture-design.md:241-258`；`docs/project/STATUS.md:3-5`

**参考实现**

- e2e：`packages/e2e/src/agent/loop-guards.ts:44-52,79-98,210-227`；`tool-loop.ts:48-63,379-409,503-521,553-648,757-797`；`transcript-compaction.ts:85-125,160-226`；`observation-budget.ts:17-73`；`settle-policy.ts:38-59`；`model/adapter.ts:102-121`；`model/sdk.ts:69-78`
- X-OmniClaw：`app/src/main/python/loop_detector.py:14-19,101-199,242-319`；`agent_logic.py:30,443-469,527-547,592-623,663-670`；`context_manager.py:14-28,129-164,167-240,268-307`；`utils.py:7-28,67-74`；`app/src/main/java/com/shijing/xomniclaw/agent/context/ContextManager.kt:29-33,70-108`；`MessageCompactor.kt:38-58`；`ContextWindowGuard.kt:21-26`
- 预研：`docs/pre-research/2026-10-19-edith-agent-evolution-pre-research.md` §3.1、§3.5、§5.3、§5.4、§8.4、§8.5、附录 C
