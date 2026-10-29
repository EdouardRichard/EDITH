# EDITH 公共契约索引（0.21 基线 + 0.24 官方交互对齐 + 0.3 Phase 1 + 0.3 Phase 2 + 0.3 Phase 2.1 + 0.3 Phase 2.2 + 0.3 Phase 3 + 0.4 脱离唤醒/上下文输入/模型内置）

- **锚点**：EDITH 0.21 基线；0.24 官方交互对齐增量（D157–D173，2026-10-18/19）；0.3 Phase 1 提示词/预算/缓存增量（2026-10-20，加法式）；0.3 Phase 2 感知/设备状态/遥测增量（2026-10-20 批准，2026-10-21 JVM 实现，加法式）；0.3 Phase 2.1 自动视觉/截图压缩增量（2026-10-21 批准并实现，加法式，无新公共类型）；0.3 Phase 2.2 工具结果契约增量（2026-10-21 批准并实现；模型可见形状的**有意破坏性变更**，公共 Kotlin API 为加法式）；0.3 Phase 3 主动辅助与结果残留增量（2026-10-21 实现并记录；加法式，无新 wire/依赖，见 §2.5）；0.4 脱离眼镜唤醒/上下文输入/模型内置增量（2026-10-26 批准，实现中；加法式、无 CXR wire 变更，见 §2.8）。
- **公共 API 文档版本**：**`1`**（0.24/0.3/0.4 没有升级 `wireVersion`；新增命令/类型/字段/默认方法均为加法式）。
- **范围**：只列**当前**对外承诺；历史增量、逐 checkpoint 变更已删除（git 历史保留）。
- **消费者**：`phone-app`、`glasses-app`、`plugin-smart-helper`、`plugin-diagnostic`、`plugin-interview`（壳）、`model-runtime`、`agent-runtime`、`host-runtime`。
- **兼容性政策**：优先加法式（带默认值）；破坏性变更必须先获业主批准、记入 `DECISIONS.md`，并在本文件写明当前结果与回退成本。
- **实现状态**见 `ACCEPTANCE.md`；决策理由见 `DECISIONS.md`。

---

## 1. `core-contracts`（9 个文件）

| 文件 | 类型 | 用途 |
|---|---|---|
| `AssistantRoute.kt` | `AssistantRoute` | 助手路由（唤醒/主动辅助）的共享枚举；`fromWire` 对未知值返回 null |
| `AudioTimeline.kt` | `InputEpoch`、`PcmEncoding`、`VerifiedPcm`、`SampleRange`、`GapReason` | 音频时间线与 PCM 证据；只有带 `evidenceId` 的 `VerifiedPcm` 才是已验证格式 |
| `ConversationContracts.kt` | `ToolPhase`（`START`/`END`）、`SpeechResult`（`FINISHED`/`FAILED`） | 0.24 跨插件/运行时共享的枚举；放在 core-contracts 以防插件端口与 wire 各自漂移 |
| `Errors.kt` | `ContractError` 及 `ConfigurationRejected`/`CapabilityMissing`/`SessionUnavailable`/`ResourceConflict`/`Cancelled`/`Expired`/`Transport` | 公共错误分类；调用方按类型处理，不解析字符串 |
| `Events.kt` | `TranscriptRevision`、`SpeakerLabel`、`SpeakerAssessment`、`AcousticLabel`、`ConfirmationResult`、`HostEvent` 及 `SessionStateEvent`/`TranscriptEvent`/`SpeakerEvent`/`AudioEvent`/`UserAction`/`WakeDetected`/`AcousticEvent`/`ContextChanged`/`PinChanged`/`SpeechFinished` | 宿主事件模型；所有事件带 `EventEnvelope` |
| `Ids.kt` | `SessionId`、`Generation`、`EventId`、`TurnId`、`EventEnvelope` | 身份与代际；旧 session/generation 的事件必须被丢弃 |
| `Output.kt` | `OutputPriority`、`Assistance`、`SpeechRequest`、`OutputStatus` | 提示/语音输出契约；`Assistance` 带 session/generation/priority/TTL/route/`emitId` |
| `RuntimeEvents.kt` | `DiscontinuityEvent`、`ServiceFaultEvent`、`OutputStateEvent` | 运行时故障/不连续/输出状态事件；`OutputStateEvent` 带 per-emit `emitId` |
| `Session.kt` | `SessionState`、`AudioFormat`（`Pcm`）、`AudioSegment` | 会话状态与音频段；`Pcm` 只声明格式，不证明链路 |

**稳定性**：这些类型是跨层契约；改名/删字段属于破坏性变更。

**0.24 加法字段**：

- `Assistance.emitId: String? = null`：**一次 emit** 的身份（每次 `submit` 一个），不是 turn/hint 身份；`null` 是旧调用/主动提示的 legacy 情形。
- `OutputStateEvent.emitId: String? = null`、`SpeechFinished.emitId: String? = null`：把同一个 emit 身份带回插件；`null` 时消费者保留旧的 `hintId` 比较（D172）。
- `PinChanged(envelope, route)`：眼镜 `PIN` 的宿主→插件事件；只置 `pinned=true`（单向），不属于 `ConversationDisplay`（方向相反）。
- `SpeechFinished(envelope, hintId, result, emitId = null)`：手机内部语音结束；插件据此决定是否开下一轮；**没有 wire 命令**。

**0.4 加法字段（D203，2026-10-26）**：

- `UserAction.Action.Typed`：追加在 `Help` 之后（`Pause, Resume, Stop, Help, Typed`），给手机输入框的文字消息；`DiagnosticTextPlugin` 用 `if`，`smart-helper` 的 `when` 是唯一穷尽分支。
- `UserAction.text: String? = null`：默认值、最后一个字段，仅对 `Typed` 有意义；`UserAction` **不序列化、不上线缆**，文本是用户内容，日志/证据不得记录。旧位置参数构造保持编译。

---

## 2. `plugin-api`（24 个文件）

| 文件 | 类型 / 接口 | 用途 |
|---|---|---|
| `Actions.kt` | `RiskLevel`、`ActionOutcome`、`ActionDescriptor`、`ActionResult`、`ActionExecutor` | 工具声明与执行；`ActionDescriptor` 带风险、确认、scope、`countsTowardStepLimit`；0.3 Phase 2.2 加法 `screenObservation: Boolean = false`（只有 `device`/`open`/`shell`/`dial` 为 true）与 `ActionResult.execution`/`observation`（结构化执行事实与调用后观察）；仍带 `fingerprint`/`stateChanging`/`images` 与 **0.3 Phase 2 final-fix 加法 `nonPersistent`**（该结果正文含感知内容，只进当前请求，不进存储 transcript） |
| `ToolResults.kt` | `ToolOutcome`、`ToolVision`、`ToolExecution`、`ToolObservation` | 0.3 Phase 2.2 的结果契约类型：六态 `outcome`、执行事实三态、观察十字段；纯数据、无 JSON 依赖；`plugin-api` 不依赖序列化库，envelope 由 `agent-runtime` 投影 |
| `AudioSegments.kt` | `AudioSegmentHandle`、`AudioSegmentSource` | 宿主拥有的音频段获取；无音频时 `fetch` 返回 null |
| `AudioServices.kt` | `AudioSubscription`、`AsrSession`、`SpeakerWindow`、`SpeakerProfileRef`、`SpeakerService` | 采音订阅、ASR 会话、声纹窗口/服务 |
| `Capabilities.kt` | `Capability` | 能力枚举：Audio/Asr/Speaker/Model/Storage/TextOutput/SpeechOutput/Actions/Context/WakeWord/Confirmation/SystemOne/Embedding/StorageAccess/ConversationDisplay/DeviceState/Perception |
| `Configuration.kt` | `ConfigSchema`、`PluginConfiguration` | 插件配置 schema 与值 |
| `ConfiguredPlugin.kt` | `ConfiguredPlugin` | 插件配置入口 |
| `Confirmation.kt` | `ConfirmationChannel` | 宿主确认通道；插件闸门是权威 |
| `ContextSignals.kt` | `ContextSnapshot`、`ContextSignals` | 场景/环境信号 |
| `ConversationDisplay.kt` | `ConversationDisplay` | 会话显示（可选能力；缺失不得阻断回合）；0.24 方法全部 default no-op |
| `DeviceEnvironment.kt` | `DeviceProfile`、`DeviceState`、`DeviceStateProvider` | 0.3 静态设备画像 + 每回合动态状态；缺失字段保持 null 并从提示词省略，不猜默认值 |
| `EmbeddingServices.kt` | `EmbeddingService`、`EmbedResult`（`Success`/`Error`） | 语义向量服务 |
| `HostServices.kt` | `CancellationHandle`、`TaskScope`、`SecretHandle`、`OutputSink`、`OutputSubmission`、`HostServices`、`EmptyHostServices` | 宿主服务容器；`require(capability)` 取能力，缺能力抛 `CapabilityMissing` |
| `InferenceEngines.kt` | `LayaInferenceEngine`、`EmbeddingInferenceEngine` | 本地推理引擎接口 |
| `ModelServices.kt` | `InputModality`、`OutputModality`、`VerificationState`、`CapabilityVerification`、`ModelProfile`、`InputPart`、`MessageRole`、`TextMessage`、`ToolSpec`、`ModelHistoryItem`、`ModelRequest`、`ModelUsage`、`ModelClient` | 模型档案、请求、历史、工具形状、客户端；0.3 加法 `ModelRequest.maxOutputTokens` 与 `ModelUsage.cachedInputTokens`/`cacheMissInputTokens`（未上报保持 null，不补零） |
| `Perception.kt` | `PerceptionSource`、`PerceptionFilter`、`NotificationItem`、`PerceptionElement`、`PerceptionCoverage`、`PerceptionSnapshot`、`PerceptionScreenshotResult`（`Image`/`Disabled`/`Unavailable`）、`PerceptionProvider`（含 final-fix C1 的 `refresh()`） | 0.3 Phase 2 宿主所有的每回合感知快照 + 只读端口；`snapshot` 只读缓存、`refresh` 是显式真实重读；缺失事实保持 null/空并由九个 coverage token 说明来源状态，不猜默认值；截图只返回 artifact 引用，不内联字节 |
| `PluginDescriptor.kt` | `PluginDescriptor` | 插件 id/名称/版本/能力声明 |
| `PluginLifecycle.kt` | `DeactivationReason`、`PluginLifecycleState`、`PluginLifecycle`、`PluginContext` | 插件生命周期与上下文 |
| `RuntimePorts.kt` | `MonotonicClock`、`DeadlineScheduler`、`InputPort`、`HintDevice` | 时钟/调度/输入/提示设备的宿主端口 |
| `SpeechPlayer.kt` | `SpeechPlayback`、`SpeechPlayer` | 语音播放端口 |
| `StorageServices.kt` | `StorageHandle`、`StorageAccess` | 插件私有加密命名空间 + 用户 SKILL 目录访问 |
| `SystemOneServices.kt` | `SystemOneService`、`SystemOneQuestionType`、`SystemOneCriterion`、`SystemOneQuestion`、`SystemOneAnswer`、`SystemOneResult`、`SystemOneStatus`、`ModelInfo`、`FailureReason` | System-One（Laya）决策模型契约：单一 `ask(state, question)`；`latencyMs` 入契约（D61） |
| `TypedHostServices.kt` | `HostServices.speech()/audioSegments()/model()/audio()/asr()/speaker()/storage()/textOutput()/actions()/context()/wakeWord()/confirmation()/systemOne()/embedding()/storageAccess()/conversationDisplay()/deviceState()/perception()` | 类型化取能力；缺能力的行为由各方法 KDoc 定 |
| `WakeWords.kt` | `WakeWordRegistry` | 唤醒词注册 |

**要点**：

- `HostServices.require(capability)` 缺能力抛 `CapabilityMissing`；可选能力（如 `ConversationDisplay`）由调用方 `runCatching` 处理。
- `audioSegments()` 走 host-owned 全局源，不要求插件声明 `Capability.Audio`（P5 C2 修复）。
- `ModelRequest` 是纯线缆形状；`ContextPart` 与三处 `context` 已删除（D80）。
- `SystemOneService` 只有 `ask`；`choice`/`score`/`noul` 是 `SystemOneQuestionType` 的数据，不是方法（D61）。
- **0.3 Phase 1 加法**：`ModelRequest.maxOutputTokens: Int? = null`（逐回合输出上限；null 时适配器用 `ModelProfile.maxOutputTokens`）；`ModelUsage.cachedInputTokens`/`cacheMissInputTokens`（provider 未上报保持 null，不补零）；`DeviceEnvironment.kt` 的 `DeviceProfile`/`DeviceState`/`DeviceStateProvider`、`Capability.DeviceState` 与 `HostServices.deviceState()`（设备字段缺失保持 null 并从提示词省略）。旧宿主/旧调用点不实现新成员也可编译。
- **0.3 Phase 2 加法**：`Capability.Perception` 与 `HostServices.perception()`（可选能力；宿主无感知层时不广告，调用方按 `runCatching` 处理）；`DeviceState` 新增 `media: MediaState?`、`panelVisible: Boolean?`、`glassesConnected: Boolean?`、`glassesWearing: Boolean?` 四个字段（缺失保持 null）；`MediaState` 同时承载 `DeviceState.media` 与 `PerceptionSnapshot.media`；`PerceptionSnapshot`/`PerceptionElement`/`PerceptionCoverage`/`NotificationItem`/`PerceptionProvider` 见 §2.2。旧宿主不实现 `perception()` 也可编译。

### 2.1 `ConversationDisplay`（0.24 官方会话增量）

原四个方法（`userMessage`/`assistantMessage`/`working`/`idle`）保持不变。0.24 新增方法全部是 **default no-op**：旧宿主继续编译且行为不变，没有显示端口也不得阻断回合。

| 方法 | 语义 | 身份 / 约束 |
|---|---|---|
| `state(route, turnId, status, sequence)` | 官方 status 电平；`status` 是 wire int 0–7 | 同一 turn 内新 `sequence` 覆盖旧；**status 0 是 `showNoneView`，不是 idle** |
| `showIdle(route, sequence)` | 官方 `showIdleStatus`：隐藏 Hi/思考/“…”、结束当前条目、保留面板与内容 | 不是 `idle()`（busy 电平）；与 `STATE` 共享 `sequence` 域 |
| `userPartial(route, turnId, revision, text)` | 佩戴者 ASR 逐字增量 | revision 单调；final 仍走 `userFinal`/`SAID` |
| `userFinal(route, turnId, entryId, text)` | 佩戴者的**最终**话语 | 约定 `entryId = asr-${turnId}`；final 替换同一行，`SAID` 也带同一 id |
| `assistantPartial(route, turnId, hintId, revision, text)` | 助手答案逐字增量 | 条目 id `say-${hintId}`；revision 在 `(route, hintId)` 内单调 |
| `assistantFinal(route, turnId, hintId, text)` | 助手**最终**答案的手机记录 | 条目 id `say-${hintId}`；**不再发 wire**（最终答案已作为 `SHOW` 到眼镜） |
| `assistantNotice(route, text)` | 不是本轮答案的助手陈述（如显式记忆确认） | 自己的 `say-<uuid>` 条目；**不得**消费/最终化 pending `say-${hintId}` |
| `toolStatus(route, turnId, phase, label)` | 工具行 | wire 上 `phase` 为小写 `start`/`end`；`label` 是内部标签，未设 400 上限 |
| `panelOpen(route)` / `closePanel(route)` | 显示面板（不改状态）/ 关闭面板并丢弃显示缓存 | `closePanel` 只丢弃 pending partial 关联，手机记录保留 |

**逐 emit 身份（D172）**：回答 final 的 `hintId` 命名**一个会话条目**（`say-${hintId}`），`emitId` 命名**一次提交**。同一 turn 的后续 emit（收尾答案、fallback、记忆确认）可以复用 `hintId`，但各有自己的 `emitId`；`OutputStateEvent`/`SpeechFinished` 用 `emitId` 区分“被替换的旧 emit”和“当前答案”。`emitId` 是手机内部字段，**不在 wire 上**；`null` 表示旧生产者/主动提示，消费者退回 `hintId` 比较。

**记录先于发送（record-first）**：`ConversationWireDisplay` 先把陈述写入手机 `ConversationLog`，再发官方命令。发送被拒只损失眼镜上的显示，不丢失手机记录。最后一条 `SAID`/`SHOW` 到达后，迟到的 `ASR`/`ANSWER` revision 被丢弃；`ANSWER` revision 在 `(route, hintId)` 内按 `revision` 单调过滤；每个 route 一个串行监视器，保证 record/pending/send 原子；final 之后的路由 busy 状态不会把已 final 的条目标成未完成。完成/丢弃的条目 id 每 route 有界保存（8 个）。

**`ConversationWire`**（`phone-app` bridge 内部）：每个官方命令一个方法 `state/idle/asr/answer/tool/panelOpen/close`，返回 `Boolean` 表示链路是否接受；生产注入 `CxrHintLink`，无官方链路的组合用 `SilentConversationWire`（全部 false）。`ConversationEntry.turnId` 是可空的回合关联；`ConversationLog` 以 `entryId` 替换同一条目，绝不解析 id 猜回合。

**`PinChanged` / `SpeechFinished`**：见 §1。`PinChanged` 只置 true；`SpeechFinished` 是手机内部事件，结果只有 `FINISHED`/`FAILED`，`STARTED` 不转发。两者都不产生 wire 命令。

### 2.2 0.3 Phase 2 感知与遥测增量

**`Perception`（宿主所有、插件只读）**：`PerceptionProvider` 的六个方法是 `beginTurn()`（打开本回合） / `snapshot(include, filter, limit)`（**只读本回合缓存**） / `refresh()`（**真实重读**，新 `snapshotId`，不重置回合；final-fix C1） / `afterAction()`（**仅动作后**重读，新 `snapshotId`） / `screenshot()` / `endTurn()`。`refresh()` 的默认实现退回 `snapshot(SCREEN, ALL, null)`（只保证旧 provider/测试类编译），Android 宿主覆写为与 `beginTurn` 相同的顺序：`observe(ALL)` → 新 snapshot id → `beginSnapshot` → `assign` → build/cache。`device.observe`（显式观察是真实读取）与 `STALE_REF` 的“新快照”都走 `refresh()`；缓存的 `snapshot()` 不能用于这两处，否则手动切屏后会把旧屏当新屏、并把旧 ref 重新盖章后通过 TTL/前台校验。`PerceptionSnapshot` 的每个缺失事实保持 `null`/空并由 `PerceptionCoverage` 的九个 token（`screen`/`notifications`/`media`/`calendar`/`acoustic`/`clipboard`/`vision`/`glasses`/`device`）说明来源状态；`truncated` 表示该快照被边界截断。通知读取区分 `available=false`（producer 抛错）与空列表：前者 coverage 为 `unavailable(provider)`，未授权仍为 `unavailable(no_access)`（final-fix I3）。`Capability.Perception` 是**可选能力**：宿主未注册时 `HostServices.perception()` 抛 `CapabilityMissing`，插件按 `runCatching { services.perception() }.getOrNull()` 处理——缺感知层只是“没有感知块”，永不让回合失败。`screenshot()` 返回 `Image`/`Disabled`/`Unavailable(reason)`，`Image` 只带 artifact 引用（`artifactId`/`mime`/宽高），不内联字节。

**`PERCEPTION` 动态尾部规则**：插件渲染的感知块由 `AgentLoop` 折成**一个不受信任的 `PERCEPTION` 段，紧邻 `USER_INPUT` 之前**；它只存在于本请求的 conversation flow，**永不写入 history、`ConversationStore` 或工具结果**，也永不进 system 槽。`RequestLayout.dynamicTail` 的请求作用域来源是 `PERCEPTION`/`MEMORY_EVIDENCE`/`SKILL_BODY`/`RUNTIME_STATE`/`RETRY`，且只包含 `USER_INPUT` 之前的段。同一次运行内与上一请求逐字节相同的块（SHA-256）不再重复注入；provider 为 null/空白/抛错时不产生段。续听的 ambient 请求用 `full=false`：高容量源渲染 `not_requested`，设备/前台/媒体/coverage 仍渲染。

**工具结果的感知内容边界（final-fix C2 + C2 re-review）**：**`device` 的每个内容型动作结果**（`observe`/`act`/`wait`/`status`/`screenshot`，含 `STALE_REF` 的新快照与未挂 perception provider 的旧投影路径）都设置 `ActionResult.nonPersistent=true`；参数校验拒绝的 detail 只有稳定 code、不含内容，不在此列。`AgentLoop` 仍把完整 detail 喂给**本次运行内**的模型请求，但在 `finish` 生成 `AgentRun.transcript` 时只把**本运行**（`runHistoryStart` 之后）的对应 `ToolOutput` 正文替换为安全标记 `(perception content not persisted)` 并清空 `images`：屏幕/通知/剪贴板/日历/媒体/设备事实与 artifact 引用都不会进入 `ConversationStore`/`AgentRun.transcript`/落盘 transcript，模型的结果配对与调用 id 仍保留。按 call id 的脱敏只作用于本运行前缀之后：call id 只要求非空，本运行可以复用更早回合的 id；前缀里的旧 `ToolOutput` 必须原样保留，否则 `recordStoreTurn` 的前缀检查会失败并丢掉本回合的工具轮（C2 re-review）。`nonPersistent` 是加法默认 `false`，不改变其他工具结果。

**边界 policy（不是模型常量）**：`PerceptionPolicy` 默认 `elementLimit=60`、`notificationLimit=3`、`refTtlMillis=20_000`、`calendarTitleLimit=5`，`includeClipboard`/`includeVision` 默认开；快照的 `limit` 被 policy 上限夹住，`device` 工具的 ref 门按 `refTtlMillis` 检查 TTL。默认值可配置，不是契约常量。

**视觉闸门与 coverage 优先级**：宿主 `PerceptionCoverage.vision` 在视觉闸门开且 policy 要视觉时为 `ok`，闸门关为 `disabled`；渲染侧在所选 profile 不声明 `IMAGE` 输入时只把 `ok` 降级为 `unavailable(no_image_profile)`，`disabled` 保持（宿主自己的关闭是更强的事实）。`device` 工具的 `screenshot` 在闸门关时直接拒绝 `VISION_DISABLED`，不读通道、不产生 artifact。

**`open` 的验证字段**：`open` 的成功与 `STATE_UNCHANGED` 失败都带同一组字段——`resolvedPackage`、`foreground`（`package`/`activity`）、`rootPackage`、`verified`。包启动要求 2 s 内等到前台包等于请求包，且 `foreground.package` 与 a11y `rootPackage` 都等于它；URL/settings 启动没有目标包，要求观察到的前台包非空且等于根包。`am start` 的 `Status: ok` 只是前置条件，不是 verified。

**`P3_CACHE`（count-only，每个带 usage 的模型请求一行）**：由 `ModelClientGateway` 在每次模型请求（含工具轮、确定性溢出重发与**异步摘要请求**）后发出，仅当 provider 上报 usage；usage 为 null 时省略。字段固定顺序 `cached`/`miss`（provider 上报值，未上报为 `-`，不补零）、`inputTokens`/`outputTokens`、`systemChars`（实际发送帧的 `systemText.length`；摘要请求无装配帧时为摘要指令长度）、`dynamicChars`（同一发送帧 `RequestLayout.dynamicTail` 的文本长度和；摘要请求为 0）。它是每请求读数，不是 run 合计；`AgentLoop` 不再发 run-total 重复行。**摘要请求是模型请求，因此有 `P3_CACHE`；但它不经过装配帧/`AgentLoop.onRequestBuilt`，所以不发 `P3_PROMPT`**（final-fix I4）。

**`P3_PROMPT`（count/hash-only，每个装配后请求一行）**：插件发出，字段 `version`、`hash`、`deviceHash`、`catalogHash`、`route`、`staticChars`、`dynamicChars`；在 `AgentLoop` 折叠 `RUNTIME_STATE`/`PERCEPTION` 之后、gateway 发送（或拒绝）之前发出（proactive 路由直接由装配处发出）。`dynamicChars` 计入 memory evidence、拉取的 `SKILL_BODY`、`RETRY` 与 `PERCEPTION`，**排除** loop 自有的 `RUNTIME_STATE`；gateway 的溢出重发复用同一装配请求，只产生 `P3_CACHE`。该行不含提示词正文、设备值、SKILL 名或用户文本。**异步摘要请求没有装配帧，不产生 `P3_PROMPT`**——它不是“每个模型请求”的字段，避免被读成漏行。

**`P3_PERCEPTION`（count-only，每个渲染块一行）**：`snap=<8 字符前缀>`、`elements`/`notifications`/`calendar` 计数、`images`（**该感知块携带的图片数；Phase 2 的感知块不携带图片，恒为 0**）、`screenChars`（渲染块长度）、`truncated`、`coverage=<九 token>`；从不携带标题、正文、包名、活动名、日历标题或剪贴板值。截图/工具图片由 `P3_METRIC images=` 计数，不由 `P3_PERCEPTION` 计数（final-fix I7）。

### 2.3 0.3 Phase 2.1 自动视觉与截图压缩（2026-10-21）

**范围与触发**：自动视觉复用既有 `PerceptionProvider.screenshot()` → `ImageRef` → `ActionResult.images` → `ToolOutput.images` 路径，不新增图片通道。

- `device.observe`：仅当 `include` 含 `SCREEN`（默认 observe include 即 `SCREEN`）时，在 `refresh()`/`snapshot()` 之后附带一张当前屏幕图。
- `device.act`：动作派发后附带动作后屏幕图；`scroll_to_text` 只在最终结果附一张，不逐步截图；stale-ref 在 `refresh()` 后附当前屏幕图，已派发后（含被拒）在 `afterAction()` 后附，`STALE_REF` 语义不变；参数校验拒绝（`INVALID_ARGUMENTS`）不截图。
- `open`：仅 `verified=true` 的成功结果附一张图；ambiguous/not-found/`STATE_UNCHANGED`/already-foreground 不附。
- `device.status`、`device.wait` 不自动附图；显式 `device.screenshot` 仍是独立工具（同样走压缩）。
- 自动截图失败只降级视觉，不改变 observe/act/open 的结果与 `changed` 语义。

**`vision` 标记**：自动截图结果在工具 `data.vision` 写 `ok` / `disabled` / `unavailable(reason)`；未走自动视觉路径的调用（`status`/`wait`、不含 `screen` 的 observe、未 verified 的 open）不带该字段；`disabled`/`unavailable` 表示已走自动视觉路径但未附图。`reason` 是稳定原因码（如 `no_image_profile`、`SCREENSHOT_FAILED`；组合层对未落盘 `pending` 占位返回 `SCREENSHOT_PENDING`）。它与显式 `device.screenshot` 的 `VISION_DISABLED` 拒绝码不同。**0.3 Phase 2.2 起该标记移到 `observation.vision` + `observation.visionReason`（见 §2.4）；`data.vision` 只保留给 Phase 2.1 的历史读数/旧测试夹具，不再写进当前 envelope 的 `data`。**

**门控与 profile 来源**：自动截图要求用户 `visionEnabled` 开 **且** 当前回合 profile 的 `inputs` 含 `IMAGE` **且** 该 profile 当前 revision 的最新 `IMAGE_REQUEST` 校验为 `Verified`。校验记录的选择规则与 `CapabilityGate` 相同：只看 `profileRevision == profile.revision` 且 `operation == "IMAGE_REQUEST"` 的记录，取 `testedAtEpochMillis` 最大者，同时间取列表靠后者；记录属于旧 revision、状态为 `Unverified`/`Failed` 或不存在，一律不附图（fail closed）。只声明 `IMAGE` 却未通过 image probe 的 profile 若附图，首个自动图会被 `CapabilityGate` 以 `CapabilityMissing` 终止整回合（final whole-branch fix I2）。协议支持维度（`protocolId`/`support.inputs`）仍由 `CapabilityGate` 在请求时判定，auto gate 不重复检查（residual）。profile 解析与回合配置同源：`AndroidProfileRepository.selections()[smartPluginId]` 优先；selection 缺失/不可读时回退 `smartWiring.selectedProfile()`；解析结果 null 或抛错一律不附图（fail closed）。每次 capture 重新读取，不冻结装配时的选择。显式 `device.screenshot` 不在本次自动门控内：它仍只受用户视觉开关约束；profile 无 `IMAGE` 时仍可能被 `CapabilityGate` 拒绝——这是已知 Phase 2 残留，本次只做 auto gate，不顺手修。

**压缩**：所有截图路径共用一次编码：按原生比例缩放，`scale = min(1, 720/w, 1600/h)`，不放大、不裁切，宽 ≤ 720 且长边 ≤ 1600，最小 1 px；JPEG quality **80**，mime `image/jpeg`；`ImageRef` 的 width/height 是编码后的尺寸。使用 Android 平台 `Bitmap.createScaledBitmap`/`Bitmap.compress`，**不新增 Gradle 依赖**；`ImageBudget.UNITS_PER_IMAGE=1024` 与 `TurnTrimmer.IMAGE_KEEP=3` 不变。

**持久化（C2）**：自动图只进当轮模型请求；`DeviceTool` 的内容型结果与带图的 `open` 结果 `nonPersistent=true`，`AgentRun.transcript`/`ConversationStore` 不含 `ImageRef` 与感知正文；模型在本次运行内仍能看到完整 detail。

**遥测口径**：自动附图计入 `P3_METRIC images=`（运行工件，含当轮所有工具图片）；`P3_PERCEPTION images=` 仍是感知块的硬编码字段，Phase 2/2.1 的感知块不携带图片，恒为 0，不能把 0 读成“本轮无图”。`P3_PERCEPTION` coverage 里的 `vision=ok` 表示视觉闸门/通道为 ok，不等于该感知块携带图片。

### 2.4 0.3 Phase 2.2 工具结果契约（2026-10-21）

**范围**：所有模型可见工具结果统一为 `{execution, observation|null, data}`；`device`/`open`/`shell`/`dial` 是屏幕工具，`notify_user`/`load_skill` 是纯工具。**业主 2026-10-21 批准（D186）**。枚举/类型在 `plugin-api` 的 `ToolResults.kt`，envelope 序列化在 `agent-runtime` 的 `ToolResultEnvelope`；`plugin-api` 不新增 JSON 依赖。

**Envelope 与分层**：`ToolResult.payload` 就是唯一的模型面 envelope（由 `execution`/`observation`/`data` 派生，不单独存储）；OpenAI Chat Completions 与 DeepSeek Responses 适配器原样消费该 payload，不重新拼模板，也不再教模型旧的 `"Failed: "`/`"Refused: "` 前缀。成功、拒绝、失败、超时、uncertain、`TOOL_THREW`、部分执行、取消都使用同一形状；`data` 只放工具特有 JSON（解析失败时是 JSON 字符串），**不复述** observation/execution 字段。`open list`/未知动作不是屏幕操作，仍带执行事实但不附图。

**扁平编码（与 spec 示例的差别）**：spec §4 的 `foreground: {package, activity}` 与内联 `vision: "unavailable(reason)"` 只是示意；**committed 编码是扁平的**——`foregroundPackage`/`foregroundActivity`、`vision`（`ok|disabled|unavailable|not_requested`）+ `visionReason`。十个 observation 字段始终存在：8 个可空（`snapshotId`、`capturedAtEpochMillis`、`foregroundPackage`、`foregroundActivity`、`rootPackage`、`coverage`、`screen`、`visionReason`）+ `truncated`（`Boolean=false`）与 `vision`（`ToolVision=NotRequested`）非空；三个顶层键顺序稳定；`data` 变化不改变该形状。

**`ToolExecution`（执行事实）**：`outcome` 六态 `done|refused|failed|timeout|uncertain|cancelled`；`code` 稳定机器码；`message` 稳定原因（不含用户内容）；`recoverable`/`uncertain`/`partial` 非空布尔；`accepted`/`applied`/`verified`/`changed` 三态 `true|false|null`，**`null` 只表示未知，禁止用 `false` 冒充未知**。参数/策略拒绝：`accepted=false`、`applied=false`、`verified=null`（动作未派发）；uncertain/超时未确认：`applied=null`、`changed=null`、`uncertain=true`（可能仍在执行）；`TOOL_THREW`：`outcome=uncertain`，不声称无副作用；成功也必须有明确的 `applied`/`verified`/`changed`，不靠 detail 文本。**不给模型“下一步建议”**，没有 `next=retry/reobserve` 之类提示。

**`ToolObservation`（调用后观察）**：`snapshotId`、`capturedAtEpochMillis`、`foregroundPackage`、`foregroundActivity`、`rootPackage`、`coverage`（九 token 沿用 Phase 2 语义）、`screen`（compact text）、`truncated`、`vision`、`visionReason`。屏幕工具成功/失败/超时/uncertain **都带 observation**；工具拿不到内容时 observation 仍存在但 `screen=null`/`vision=unavailable(reason)`，或显式 `observation=null` + reason（不能静默）。纯工具 `observation=null`。loop 生成的失败观察（policy/确认拒绝、confirmed timeout、uncertain timeout、`TOOL_THREW`）由注入的 `ToolObservationSource.observeWithImages(250)` 补；无源 ⇒ `unavailable(no_source)`，源返回 null/超时/抛出 ⇒ `unavailable(observation_timeout)`（provider 抛出由源报 `provider_threw`），屏幕工具**不静默返回 null**；`status`/`wait` 成功只有文本观察 + `vision=not_requested`，失败尝试图；参数拒绝只给观察文本 + vision 原因；`open list`/未知动作无图；一次 capture 最多并入一张失败图。Phase 3 的生产 `PerceptionObservationSource` 改为真实 `refresh()` + 截图（单线程、250 ms 截止、超时 `observation_timeout`、抛出 `provider_threw`、关闭后不再起任务），见 §2.5。**250 ms 是建议预算**：设备实测值未记录（Q3=最简 未专门测），故“250 ms 合规”**NotMeasured**；loop 级 throw/timeout 图片 JVM 已证，设备 **NotMeasured**（shared WAKE path，Q3=最简 接受），观察文本/vision 原因仍必需。

**图片与 C2**：失败/超时/uncertain 的屏幕工具在 vision 可用时附图；`status`/`wait` 仅失败图；纯参数拒绝不强制图；`open list`/未知动作不附图。图片仍走 `ToolResult.images`/`ImageRef`，`observation.vision` 只说明图片是否可用，不内联 bytes；失败图同样计入 `ImageBudget`/`IMAGE_KEEP=3`。observation 存在即 `nonPersistent`：完整内容只进当轮模型请求，`AgentRun.transcript`/`ConversationStore` 得到安全 marker 且不含图片；loop 补 observation 的副本也必须撤掉持久化资格；任何图片都不落 store/transcript（`mustRedact` 不依赖工具 flag）。`data` 含内容时仍按工具标记；纯执行事实可持久化。日志/证据只记 code/计数/布尔，正文/图/artifact id 不落。

**`P3_METRIC images=` 定义**：**distinct images produced by this run's tool calls, including images a terminal run never carried**；按 artifact id 去重，只从 append-time 工具输出计数；从上一轮 history 携带进来的图片**不计**。`P3_PERCEPTION images=` 仍是感知块硬编码 0，不能把 0 读成本轮无图。

**data 去重与 TurnTrimmer**：生产组合（`HostCompositionRoot` 总注入 perception）下 `device`/`open`/`shell`/`dial` 的 `data` 不再重复 screen/foreground/coverage/snap/vision/execution；`data.changed=false` 不再在 `applied/changed` 未知时出现。`TurnTrimmer` 从 `observation.screen` 取 UI 树；⓪ 淘汰旧观察时只把 `observation.screen` 换成 header，保留 `execution`、`data` 与 observation 其他字段（真实 `ToolResultEnvelope.encode` payload 测试）。**legacy 例外**：`DeviceTool` 在 `perception == null`（仅测试/非生产组合）时保留旧 `data.screen` 投影树；那里没有 observation，删掉会丢失屏幕而不是去重，且不伪造 observation。生产不落该形状。

**未变/残留**：无新依赖；`ImageBudget.UNITS_PER_IMAGE=1024`/`IMAGE_KEEP=3` 不变；本节是 Phase 2.2 的契约，Phase 3 已在其上实现并记录（§2.5），Phase 4 未启动；0.21/0.25/0.26 判词不变。**显式 `device.screenshot` 的 profile 门控仍是已知 Phase 2 残留**：它只受用户视觉开关约束，profile 无 `IMAGE` 时仍可能被 `CapabilityGate` 拒绝（本次只做结果契约，不改该门）。debug 探针（T6/OpenTarget）必须读 `ActionResult.observation`/`execution`（`ProbeResultProjection`），不能把 `detail` 当 envelope。

### 2.5 0.3 Phase 3 主动辅助与结果残留（2026-10-21，D188）

**范围**：HINT_TURN / FOLLOWUP_LISTENING / FOLLOWUP_TURN；三档 prompt route；完整感知尾部；loop 失败观察图；count-only 结果遥测；debug seam 边界。**不新增供应商、wire 命令、权限或依赖；`plugin-api` 公共类型不变（新增的 `ToolObservationCapture`/`observeWithImages` 在 `agent-runtime`，`PromptRoute` 在插件内）；** `wireVersion` 仍为 1；P3-13 两条路由独立。

**Prompt 三档**：`PromptRoute` = `WAKE` | `PROACTIVE_HINT` | `PROACTIVE_FOLLOWUP`；资源仍由 `PromptCatalog` 的 `route/*.md` 提供（`route/wake.md`、`route/proactive-followup.md` 等），会话激活时三档一起钉住；`P3_PROMPT route=` 使用这三档名，WAKE 的日志/行为默认不变。`PROACTIVE_HINT` 只带 `propose_hint` 终态工具（`maxSteps=1`、无设备动作、`data` 即提示文本，≤60 code point）；`PROACTIVE_FOLLOWUP` 用正常工具面/确认/完整感知，答案后按 `onAnswerDone` → `openContinuation` 支持持续多轮（不是一次性提示）。

**`ToolObservationSource` / `observeWithImages`**：

```kotlin
data class ToolObservationCapture(val observation: ToolObservation?, val images: List<ImageRef> = emptyList())
fun interface ToolObservationSource {
    fun observe(deadlineMillis: Long): ToolObservation?
    fun observeWithImages(deadlineMillis: Long): ToolObservationCapture? =
        observe(deadlineMillis)?.let { ToolObservationCapture(it) }
}
```

`AgentLoop.observedFailure` 只调用 `observeWithImages(250)`（不是 `observe`）；只有**非 `Done`** 的 `screenObservation` 工具才补；一次 capture 最多并入一张图，observation 内容仍 `nonPersistent`，`AgentRun.transcript`/`ConversationStore` 不含正文/图。生产 `PerceptionObservationSource`：单线程 daemon、`provider.refresh()` + `screenshot()`、`Future.get(250)`；超时 `unavailable(observation_timeout)`、provider 抛出 `provider_threw`、关闭后不再起任务；截图遵循现有 vision gate；source 是会话级、`deactivate` 关闭、不跨会话复用。**设备 250 ms 实际值 / loop throw-timeout 图 = `NotMeasured`（Q3=最简 接受；JVM + shared WAKE path 证明）**。

**`P3_TOOLRESULT`（每次工具调用一行，count-only）**：字段顺序固定为
`turn= call= tool= outcome= code= recoverable= accepted= applied= verified= changed= uncertain= partial= observation= vision= screenChars= images= shape=`；`outcome` 六态；tri-state 用 `t|f|-`；`shape` 是 `ToolResultEnvelope.shapeDigest(payload)` 的 8 位小写 hex（字符串→`""`、数值→`0`、布尔/null 保留、紧凑序列化后 SHA-256 前 8 hex；payload 不是 JSON/解析失败时为 `-`；本轮设备 21/21 行均为 8 hex）；`images` 是**该调用**的图片数，run 级 distinct 计数仍由 `P3_METRIC images=` 承担；不记正文/包名/标题/命令/号码/artifact id。该行在 `observedFailure` 之后、`appendToolOutput`/脱敏之前发出。

**`P3_OBSERVE`（count-only）**：`source=tool tool=<name> observeMs=<n> captureMs=<n> result=<ok|unavailable|disabled>`；`source=loop ms=<n> timeout=<bool> images=<n> reason=<stable|->`。不含正文。

**P3-13 会话独立（硬约束）**：proactive 与 wake 各有独立 route/store/turn 槽/cycle 状态/摘要/prompt plan/显示/生命周期；只复用 `AgentLoop`/`ConversationCycle`/确认/显示**机制**，不复用会话。ASR final 先由 WAKE `AwaitingTranscript` 消费；只有没有 WAKE turn waiting/running 时 proactive follow-up 才能消费。WAKE 的 final/answer/continuation 不得进入 proactive，反之亦然。JVM 路由/并发测试钉住；设备并发 WAKE+PROACTIVE `NotMeasured`（Q3=最简）。

**debug seam 边界**：只使用现有 `DebugUtterance route=transcript` / `route=wake`（`phone-app/src/debug`）；`P3_UTTERANCE` 只记 `state/route/turn/rev/chars/textSha256`；release 无 receiver。**不新增** `DEBUG_GATE`/`DEBUG_FAULT`，不做 forced gate/hint/fault。`P3_TURN route=` 只对非 WAKE 路由追加（WAKE 遥测保持逐字节）。手机 UI 只用 `edith.phone.a11yprobe`（读现值→保留→追加→验证绑定）。harness 在 gitignored `.local/`，不是生产协议。

**Q1=A0 gate 输入（known drift，不修）**：gate state 仍是四键 JSON（省略空字段、`LayaPromptBuilder` 尾部截断）；**D1** 空字段省略 vs fine-tune 四键齐全训练契约、**D2** 合成最大中文四键 ~436 tokens vs 部署 ~219 预算（尾部截断可能切 JSON）、**D3** `ModelInfo.contextWindow=512` vs 实际 256——三者都是 known drift/`NotMeasured`。Phase 3 **不修 gate 代码、不重训、不扩 state**；完整感知只进 HINT_TURN/FOLLOWUP_TURN。

**Q3=最简 accepted gaps**：loop throw/timeout 图由 JVM + shared WAKE path 证明，设备 `NotMeasured`；250 ms 设备实测值 `NotMeasured`；shared `AgentLoop`/context/display/confirmation **不为 proactive 重复设备测试**；不可达路径记 `NotMeasured` 并明确接受。**不把该接受读成通过。**

### 2.6 0.3 Phase 4A 工具面（2026-10-22，JVM-only）

**工具面**：一个 `Capability.Actions` provider；默认 23 = 17 atomic + 4 umbrella（`device`/`shell`/`open`/`dial`）+ `notify_user`/`load_skill`（2026-10-22 D196：`launch_app` 退役，24 → 23）；`propose_hint` 只在 `PROACTIVE_HINT` 回合。描述固定（App 版本 + 会话），掉线保留广告；`dial` 单 owner 是 umbrella `DialTool`，`IntentActionExecutor.dial` 不广告但保留唯一实现。打开应用/网页/设置页优先 `open`；`open_app` 保留为"已解锁且 Shizuku 不可用"的 Intent 兜底，歧义返回 `APP_AMBIGUOUS`+候选、未找到 `APP_NOT_FOUND`（均 `recoverable=true`）；`list_apps` 为本地 PackageManager 目录，无需 Shizuku。umbrella 掉线返回 `CHANNEL_UNAVAILABLE`，legacy atomic 保留 `ACTION_CHANNEL_UNAVAILABLE`；A4 不归一化。

**描述与用户文案**：`ActionDescriptor.description` 是模型面向的长描述（A1），`ActionDescriptor.title` 是短用户标签；`ToolDescriptor.title` 由 title 投影，`ToolDescriptor.description` 只给模型。`WhitelistActionPolicy` 确认摘要与 `SmartAssistantPlugin` 工具行只使用 `title`/`ToolLabelMap`，长 description 不得泄漏到用户文案。风险级仍按 spec §4.1（atomic tap/long_press/input_text L1、brightness L2、clear_data/uninstall L3、disable_app/set_permission L4）。

**PromptCatalog 0.4.1**：`RESOURCE_FOLDERS` 含 `loop`/`pinned`；`plan()` 不再自动收 `PINNED`；`pinned(key)`/`loop(key)`/`summarizer()` 只读 manifest 校验条目。A7 已把 gate 问句/选项、retry/plan-nudge/timeout、六段结构化 summarizer、diagnostic 固定句接到资源；`core/20-tools.md` 为 23 工具面（D196）。A6/A7 的 loop/pinned 资源留在 plugin `PromptCatalog`（4A 计划优先；不迁到 agent-runtime base）。

**遥测**：`P4_TOOLFACE tools=<n> duplicate=<n>` 每次会话只 emit 一次（session-id 一次性）；`P4_TOOL tool=<name> ms=<millis> outcome=<wire>` 每次实际进入工具测量时一行，count-only，不改 `P3_TOOLRESULT`/`P3_METRIC` 形状。`P4ToolMetrics` 只解析该行，按工具 p50/p95；无读数为 `NotMeasured`。取消/拒绝/跳过调用的计数边界由 4B/B9 明确，不假设 `P3_TOOLRESULT` 与 `P4_TOOL` 1:1。

**turn 边界**：atomic a11y 的 `read_screen` 快照按回合失效（`ToolSurface.beginTurn` → adapter seam → `AccessibilityExecutionBackend.beginTurn`）；跨回合不重放旧 index。A8 的架构/硬编码 guard 钉住 prompt 资源目录、base/plugin 词汇边界与 24 名单。

**验证边界**：4A 全量 JVM gate = 10 模块 / 338 suites / 3,659 tests / 0 failures / 0 errors / 2 env skips；**无设备运行**。24 工具设备广告/选择、L3/L4 确认、真实延迟矩阵、旧语料与 4B 权限模式均 `NotMeasured`/未启动；JVM 通过不等于设备通过。2026-10-22 D196 后当前工具面为 23，上述 24 读数保留为变更前历史。

### 2.7 0.3 Phase 4B 权限、确认与“不拦截”（2026-10-22，JVM + Partial device）

**策略**：`WhitelistActionPolicy` 的 `NORMAL` 模式把 `maxLevel` 当自动执行上限；`risk.ordinal > maxLevel.ordinal` → `RequireConfirmation(descriptor.title)`（不再 `Refuse`），上限先于白名单/`requiresConfirmation`；L3/L4 恒确认。`NO_INTERCEPTION` 由 live `noInterception: () -> Boolean` provider 表示，先于上限/白名单/`requiresConfirmation`，所有 L0–L4 `Allow`。插件侧 `ConfirmingActions` 在 provider 为 true 时跳过 shell precheck 与确认、直接执行；provider 每个 decide/execute 都重读，不是回合快照。`PolicyMode` 没有独立公共类型；策略/确认层以 provider 表示，宿主 `NoInterceptionGate` 在 phone-app host（plan B2/B3 优先，spec §6.2 的 agent-runtime 位置作为文档漂移记录）。

**持久化与 UI**：`SmartSettings.noInterception` 默认 false，key `noInterception`，缺省/损坏值严格读 false；`saveSmartSettings` 先关 gate 再写、写后按值设置；`HostCompositionRoot.create` 从持久值 seed。设置页开关 + 二次确认（仅 off→on）+ 持久 banner；关闭方向在设置保存后 live 生效、无需重启；App 关闭/被杀/重启不重置。B8 设备不拦截读数使用 debug-only `DebugNoInterceptionControl` 调生产 `storedSettings()`/`saveSmartSettings()`，UI 二次确认只有 JVM/编审证据。

**确认可用性与通道**：`ConfirmationChannel.available()=true` / `ConfirmationPort.available()=true` 是 additive default；presenter 聚合实际可用面；`RequireConfirmation` 且 no available surface → `CONFIRMATION_UNAVAILABLE`，不进 120 s。present 时眼镜先 `PANEL_OPEN`，再 `SHOW`+`CONFIRM_STATE`；`PANEL_SHOWN` 用稳定 `confirm-<uuid>` hint id 重发（不追加重复行），pending/TTL 保留。优先级：眼镜自动面板/键 > 通知 action > 语音精确词 > Edith 弹窗；通知 `available()` 检查权限/App 开关/channel importance，渠道 NONE → `CHANNEL_DISABLED`；voice 只结算**已呈现**问题的精确 allow/deny（`P4_CONFIRM channel=voice` 是回答通道），不是独立展示 rung；语音“急停”仍未实现（deviation）。

**遥测**：`P4_POLICY mode=normal|no_interception action=<name> risk=<Lx> decision=Allow|RequireConfirmation|Refuse` 每次 policy 决策一行；`P4_CONFIRM tool=<name> decision=allow|deny|timeout|unavailable channel=glasses|notification|voice|dialog|none` 每问题一行（成功 raised 不单独发；结算/unavailable 时发），不改旧行。`P4_CONFIRM` 可能在 `EdithP3`（host presenter）或 `EdithSmart`（loop/precheck no-channel）上；B9/设备解析必须同时读两个 tag。

**shell 分类器**：`cmd package {list,path,resolve-activity}` 等只读命令 Allow；`pm disable`/`settings put`/`am force-stop` 等 → `RequireConfirmation`；未知/不可读/包装/变量/重定向 fail-closed。复合命令沿用 0.25 的**逐段判定**：所有段 Allow 才 Allow，任一段危险/不可读即拒绝/确认；不把“任何多段都确认”作为实现。B3 的 noInterception 在 precheck 之前直接执行，因此不产生确认。

**验证边界**：4B 最终 JVM gate 11 模块 / 359 suites / 3,936 tests / 0 failures / 0 errors / 2 env skips。Phase 4 设备验收已由业主 D195 接受：P4-A `Partial`、P4-B/C/D `Verified`、P4-E `NotMeasured`；已测 L4/L3/L2 确认与眼镜 ENTER/BACK、后台/锁屏、noInterception 设置页/持久/关闭恢复、shell、代表工具/24/0；已测审计 readback、真实 急停、通知 allow/deny、notifications-off、debug no-visible、白名单 exact/错 scope；未测真实 CXR 移除、真实声学人声、延迟矩阵与旧语料。ASR 合成 TTS clean 句已验证，真实人声 `NotMeasured`。设备证据见 [closure](evidence/2026-10-22-0.3-phase-4-closure.md) / [device](evidence/2026-10-22-0.3-phase-4b-device.md) / [latency-corpus](evidence/2026-10-22-0.3-phase-4-latency-and-corpus.md)；`Partial`/`NotMeasured` 不是 pass。

### 2.8 0.4 脱离唤醒、上下文输入与模型内置（2026-10-27 文档收口；不是版本发布）

**范围与状态**：脱离眼镜（D199）、手机麦唤醒（D200）、手机显示设备（D201）、有效主动辅助（D202）、文字消息（D203）、听写（D204）、四组模型内置（D205）、版本 `0.4.0-preview`（D206）、验收口径（D207）。本增量**全部为加法式**，`wireVersion` 仍为 **1**，不改 CXR 命令/`Caps`/回调层级/眼镜端；公共 API 文档版本仍为 **1**。**实现已提交**（文字/听写 host/UI wiring `f7389627` + fix1 `0f2e4bda` + fix2 `da6032ff`；尾音闸门/re-arm `c3b4272a`；composer `fd490b97`/`851bd478`；KWS epoch reset `b2d03c06`）。最终 K40 矩阵 Task 13e 与 KWS 5/5 复验已完成（`c594fbe1`/`59f41190`）；release APK/干净安装/空间不足、keyguard、真实人声/召回率、mic busy/初始化失败、APK 字节可复现、原生开关视口、精确 ASR fault code、KWS 多轮/长稳仍 `NotMeasured`。不得把 JVM 或 debug 读数写成 release/版本通过。

**2.8.1 文字输入（D203；已实现并有 K40 最终读数）**

- **事件契约**：`core-contracts` 的 `UserAction.Action` 追加 `Typed`（顺序 `Pause, Resume, Stop, Help, Typed`）；`UserAction.text: String? = null` 为默认值、最后一个字段，只对 `Typed` 有意义，旧位置参数构造不变。`UserAction` **不序列化、不上线缆**；唯一穷尽 `when` 在 `smart-helper` 插件（编译器强制），其他插件用 `if`。`text` 是用户内容，不得写入日志/证据。
- **组合根 API**：`HostCompositionRoot.requestTypedMessage(text: String): TypedSendResult`（`@Synchronized`，仿 `requestHelp`）；`enum class TypedSendResult { Accepted, Blank, NoSession, Busy, Stale }`。`trim()` 一次；空白最先拒绝（`Blank`，不产生事件）；`closed` 或 `host.currentIdentity() == null` → `NoSession`；`SmartAssistantPlugin.canAcceptTypedMessage()` 预检为 false → `Busy`；`HostRuntime.onEvent` 拒绝（身份在读/提交之间变化）→ `Stale`；宿主接受事件 → `Accepted`。`Accepted` 只表示宿主接收事件，**不表示插件已开回合或模型已答复**。
- **回合语义**：进入 **WAKE 路线**，与已接受唤醒 final 走同一 `runTurn`/`runTurnBody` 下游；只产生一个 WAKE 回合；**不触发 PROACTIVE**、不触发 Laya gate、不产生额外 WAKE 回合。事件/回合 id 均为 `typed-<uuid>`；用户行 `asr-<turnId>`；助手答案走既有输出/确认/TTS。插件只重置 **WAKE** 的 cycle（`ConversationCycle.reset(AssistantRoute.WAKE)`），**不**复制 Help 的双路由 `reset()`/`cancelPendingHintPanel`。
- **不进入唤醒门槛**：不调用 `isUsableUtterance`（唤醒词/短文本可被发送）；**不写** `lastFinalTranscript`；`openTurn(..., startsCooldown=false)`，不启动 3 s wake cooldown；**不打开** 10 s 持续对话窗口。
- **忙碌/拒绝**：仅当 `queuedUtterance == null` 且（`turn == null` 或 `!turn.closed && turn.phase == Idle && turn.answerNotified`）接受；`AwaitingTranscript`/`Running`/Idle 未结算/已有排队句 → 拒绝，不取消进行中的回合。拒绝通过返回值/UI 文案表达，不写 sticky `SessionUiState.errorCode`。
- **遥测**：`P4_TYPED state=accepted turn=typed-<uuid> chars=<n>`；拒绝为 `P4_TYPED state=refused reason=blank|no_session|busy|stale turn=... chars=<n>`。**不记正文、不记 textSha256**。accepted 行**没有** `reason` 字段（`reason` 是拒绝词表）。
- **UI**：输入行在 `AssistantTab` 的 WAKE 视图、聊天 `Surface` 下方、`LazyColumn` 外，始终展示（不受脱离开关影响）；PROACTIVE 不显示。会话非 `Ready`/`Listening`、空白或发送中禁用/拒绝并显示中文原因；成功清空、失败保留草稿；草稿上限 2000 字符。**麦克风按钮已启用**（`MIC_DICTATION_ENABLED = true`），听写 final 只填输入框、不自动发送。UI 结果枚举：`Accepted`/`Blank`/`NoSession`/`Busy`/`Stale` 分别对应“已发送”/内容为空/请先开始会话/助手正在处理/会话已变化。

**2.8.2 脱离模式（D199/D200/D201/D202；已实现并有 K40 最终矩阵）**

- **设置**：`SmartSettings.glassesDetached: Boolean = false`（与 `proactiveEnabled` 同在一个加密 JSON blob；缺键/损坏取安全默认 **false**）；**host-only**，不进入 `pluginValues()`/插件配置 schema。唯一 writer 为 `DetachGate`；组合根组装时读取一次，`enabled: StateFlow<Boolean>` 暴露给 UI 与 host 内部。
- **保存语义**：写持久化前，若会话处于**任何非 Idle 状态**（`Authorizing`/`Connecting`/`BuildingScene`/`Ready`/`Listening`/`Paused`/`Interrupted`/`Reconnecting`/`Error`），先 `stop()`（不自动重启）；`DetachSetResult(saved, stoppedSession, error)` 可见；错误码 `SETTINGS_STORE_UNAVAILABLE`/`SESSION_STOP_FAILED`/`SETTINGS_UNREADABLE`/`SETTINGS_SAVE_FAILED`；保存成功后 live gate 立即更新。默认关，保存后**下个会话**生效；运行中修改不热切换音频源/显示目标。`stopSession()` 成功即报 `stoppedSession=true`；随后服务 stop 返回 false/抛错仍拒绝保存并报 `SESSION_STOP_FAILED`（`9db5fcb4`）。
- **detach 保存的 stop-before-save（Task 9 修复）**：停止宿主会话后还必须结束承载的 `SessionService` 及其前台通知。**目标契约**：直接 `Context.stopService`；返回 false（没有真正停到服务）或抛错 → `SESSION_STOP_FAILED`，**不持久化模式**；`SessionService.onDestroy` 先取消状态收集器，再 `stopForeground(REMOVE)` 并直接 `NotificationManager.cancel(NOTIFICATION)`。fix2 `39b36852` 已提交；直接 stopService 的拒绝路径只有 JVM 覆盖，通知取消为设备证据。
- **音频**：脱离模式不 `ensureCxrLink()`/不建立 CXR；手机 `AudioRecord` 16 kHz/mono/16-bit LE，几何与 CXR 帧一致；同一 `PcmFramer`→`AudioIngressLoop`→KWS/`SpeechPipeline`；`P4_AUDIO state=armed source=phone_mic transport=false loop=true lockscreen_hold=true ...`；`P3_CXR` 不产生。启动失败/权限缺失/占用分别为 `MIC_START_FAILED`/`MIC_PERMISSION_MISSING`/`MIC_UNAVAILABLE`，格式不符 `MIC_FORMAT_UNSUPPORTED`。前台服务类型含 `microphone`；采音/推理期间持有 `PARTIAL_WAKE_LOCK`（tag `edith:detached-wake`），pause/stop/失败路径必须释放。**不承诺自动亮屏/锁屏 UI**；真实锁屏（keyguard）行为与长稳 `NotMeasured`。
- **显示**：host `liveOutput` 在脱离模式返回 `PhoneHintDevice`，`Capability.TextOutput` 由它使能（同一 capability，不新增枚举）；**ack-only**：不写 `ConversationLog`（插件 `publishAssistantFinal` 已写 `say-<hintId>`），`clear` 不清历史；`isPanelVisible=true`（host 的 `SmartWiring` lambda 提供）；不发 `PANEL_OPEN`/`PANEL_SHOWN`，不伪造 wire；眼镜模式仍 `hintLink.liveOutput()`/原 `isPanelVisible`，语义不变。
- **D201 ack 不变量（已修正）**：生产顺序为 `sink.submit` → `PhoneHintDevice.show` → 插件 `publishAssistantFinal`，因此 `show` **无条件、同步、恰好一次**回调 `OutputStatus.Displayed`；它**不检查**答案行是否已存在（按字面检查会拒绝每个回合的首个答案）。答案行存在是**设计不变量**（插件在 submit 后立即写），不是运行时检查；`AnswerEmitIdentityIntegrationTest` 钉住答案与 memory ack 不互相覆盖。spec §4.3 / R-0.4-J5 已按此改写（`96e66a3d`）；若业主坚持字面 gating，需要插件排序/异步 ack 的独立任务。
- **严格本地链路拆除（`4c1def3e`）**：脱离模式切换时 `CxrHintLink.detach()` 取消 clock renewal/PING timeout、清 session/mapping/panel/display，**发送层**拒绝所有后续 outbound（含 timer/vendor callback 竞态）；组合根关闭并遗忘 vendor connection（transport teardown，不是 app-level CXR 命令）；stale wear/STOP/PIN/CONFIRM/PONG 不进入 host。`P4_DETACH_LINK state=neutralized session=cleared connection=closed|none|close_failed` / `state=reattached`；`P3_CXR glasses_event=wearing=<bool> ignored=detached`。
- **有效主动辅助（D202）**：`effectiveProactiveArmed = storedProactiveEnabled && !glassesDetached`；host 感知事件闸门、`proactive.armed` 上下文键、插件 `armedNow`/`SmartProactive.admit` 均读有效值；**不写回** `proactiveEnabled`，关闭脱离/重启后恢复用户原值。
- **遥测**：`P4_DETACH state=on|off effective_proactive=<bool> audio=phone_mic|cxr`（**不含** `lockscreen_hold`）；`P4_AUDIO state=armed|disarmed source=phone_mic|cxr transport=<bool> loop=<bool> lockscreen_hold=<bool> reason=...`；只记状态/布尔/计数，不记内容。
- **D4 严格本地测量窗口（Task 13e 口径）**：从 `P4_DETACH state=on`（以及 `P4_DETACH_LINK state=neutralized`）之后开始，到脱离会话结束；窗口内实际发送的 `EdithHint: outbound:` 行必须为 **0**，且无 PING/SYNC 时钟续期。**排除**：保存前正常 stop 路径可能发出的 `BUSY=false`/`CLOSE`（发生在 neutralize 之前），以及发送层拒绝诊断 `outbound suppressed: detached mode`（表示未发送，不是 wire 流量）。**Task 13e 已按此窗口在最终二进制验证 60 s 零 outbound**；窗口从 neutralize 起算、不是从点击瞬间起算。

**2.8.3 内置模型与首启 provisioning（D205；debug 首启已验证，release pending）**

- **构建输入**：`tools/scripts/p2/model-bundles.json` 是唯一可提交清单（role/path/bytes/sha256/source/license；ASR 复用 `engine-manifest.json`）；模型二进制**不进 git**；默认目录 `<repo>/.local/0.4-models`，`-PbundledModelsDir=<dir>` 覆盖；每个文件必须存在且 bytes/SHA-256 匹配，缺失/不一致**构建失败**；KWS 是完整构建必需项。构建期还从 21 个条目重算 canonical `bundleId = SHA-256(按 path 排序的 "group/path:sha256" 行，\n 连接)`，声明值不匹配即失败；许可证/声明集固定为 **15 份**（10 份既有 + 5 份 ASR/VAD/speaker），缺一/多一均失败（`ee3b43d5`）。
- **开发/CI 开关**：`-PwithoutBundledModels=true` 跳过生成、省略 `assets/models/**`、设置 `BuildConfig.BUNDLED_MODELS=false`；**release variant 遇到该开关构建失败**；release/debug 默认都执行完整校验。
- **APK 资产**：`assets/models/asr/{engine-manifest.json + 6 artifact}`、`assets/models/laya/{6 运行时文件}`、`assets/models/embeddinggemma/{graph, sentencepiece.model}`、`assets/models/licenses/*`（15 份文本）、`assets/models/models-manifest.json`；KWS 保持 `assets/kws/`（5 运行时文件 + `keywords.txt`）。`models-manifest.json` 携带 `bundleId` 与每文件 SHA；**`bundleId` 在运行时仍是 opaque 的内容身份，不是公共 key**——消费者不得解析/假设其构成；构建已保证它由 21 个条目的 path+SHA 派生，APK 内容变化即变化。
- **materialize**：App 启动后、会话可用前运行 `BundledModelProvisioner`；`ProvisioningState = Preparing | Ready(bundleId) | Failed(reason)`。ASR 经 `EngineModelStore` 的 active → recover → asset import（`ENGINE_IMPORTED_ASSET`）路径导入；Laya 6 运行时文件与 EmbeddingGemma 2 文件 temp+rename+size/SHA 校验后写入现有运行目录并写 **receipt（`bundleId` + `manifestDigest` 两行）**；receipt 与目标尺寸匹配时跳过（二次启动 `ENGINE_REUSED`），APK 变化（含仅文件摘要变化）触发一次重新 materialize。**旧版只有 bundleId 的单行 receipt 视为 stale（迁移由 JVM 测试覆盖）**，升级后首启重新 copy 一次并写新格式；失败不留下半个 active bundle；空间不足在首写前返回 `MODELS_NO_SPACE`；本版不做旧 bundle GC。
- **门禁**：`dispatch("edith.START")` 在 `beforeStart` **之前**检查模型 readiness；非 Ready 返回 `MODELS_PREPARING`/`MODELS_UNAVAILABLE`；FAILED 可重试；已有可用模型不因后台校验失败阻断。运行时读取路径不变（ASR `EngineModelStore.active()`，Laya/Embedding 原目录），不 mmap assets、不整包重哈希（spec §6.4 允许既有校验）。
- **许可证**：`assets/models/licenses/` 随 APK，固定 15 份（含 5 份 ASR/VAD/speaker 模型卡/3D-Speaker 文本）；ASR/VAD/speaker 现有文本、Laya Apache-2.0 + NOTICE 引用、KWS Apache-2.0 + 来源/revision、EmbeddingGemma Gemma Terms §3.2 + Prohibited Use Policy；`tools/scripts/p2/NOTICE.md` 列出四组来源/SHA/license；`distributionReview` 仍为 **Incomplete**，不是法律审计结论。
- **遥测**：`P4_MODELS state=preparing|ready|failed stage=asr|laya|embedding|space bytes=<n> bundle=<id> reason=...`；空间不足失败固定 `state=failed stage=space reason=MODELS_NO_SPACE`；不打印模型路径/内容。
- **证据边界**：K40 debug APK **1,111,007,965 B**、SHA-256 `8b4973eb…46c6d8c`；首启 `preparing → ENGINE_IMPORTED_ASSET → ready stage=all`（launch→ready 13.79 s，provisioning 12.81 s）；二次启动 ready 0.453 s（`ENGINE_REUSED`）；设备 14/14 SHA 匹配。**该 Task 4 构建只有 10 份许可证文本**；`ee3b43d5` 后为 15 份 + 派生 id + manifestDigest，构建审计 `ASSET_AUDIT_RESULT=PASS`（15/15 + 15/15 + 6/6），但**最终构建的设备首启/旧 receipt 迁移未重跑**。**release provisioning、干净安装首启、空间不足/中断恢复、APK 字节可复现均为 `NotMeasured`**；不得把 debug 读数写成 release 读数。证据：[bundled-models-device](evidence/2026-10-26-0.4-bundled-models-device.md)。

**2.8.4 听写（D204；已实现并有 K40 最终矩阵读数）**

- **`SpeechPipeline` seam（12a，`c6bfb7d4`，已提交）**：
  - `fun redirectEmit(sink: ((HostEvent) -> Unit)?)`：安装后**后续** `HostEvent`（`Transcript`/`Speaker`/`ServiceFault`/`SessionState`/`Discontinuity`）都发给 `sink`；`null` 恢复构造注入的 emit；在**发射时**读取，因此只影响调用之后的事件；不重定向 `onFrameSignal`/`onReset`；未安装时与旧路径同一调用点/线程/顺序。
  - `fun finishCurrentTurn(): Boolean`：有活动回合时走与 VAD 端点相同的收尾（`asr.finish().copy(final = true)` 经 `TurnRevision.revise`，保留 `startNanos`/最后帧 `endNanos`，revision+1，请求 speaker window，重置 ASR/VAD，清回合），同步发一个 final；无活动回合/已 close/已由端点或 ASR 收尾 → 不重复发 final（无回合时返回 false 且不发事件）；并发调用只 final 一次。
  - `fun rearm()`（`c3b4272a`）：清除 terminal `paused`、撤销故障遗留回合/挂起请求、清 deferred/pre-roll、重置 ASR/VAD/speaker；只在 `paused` 时生效（healthy/closed 为 no-op）；同一 session 继续拒收（原 fenced-PCM 测试保留），另一个 session/generation 的首帧或新 audio epoch arm 才恢复。
- **`DictationController`（12b-i，`ae2cad59`，已提交）**：`DictationPipeline { redirect(sink); finishCurrentTurn(): Boolean }`；`DictationState = Idle | Listening | NoSpeech | Error(code)`；`start()/stop()/cancel()/close()`；默认 **15 s** deadline。**final-only**：只接受最终 transcript，非空 final 通过 `onFinal(text)` 交给 UI 填入输入框（**不自动发送**）；**partial transcript 丢弃，final 也不转发给插件/`HostRuntime`**；非 transcript 事件可按 `forward` 交给宿主。stop/cancel/timeout 会先 `finishCurrentTurn()` 封口并在 redirect 仍安装时丢弃该 final，防止迟到文本落到唤醒路线；`close()` 永久解绑并令 `start()` 返回 `Unavailable`；控制器自身不记任何文本/长度/hash。
- **host/UI wiring（12b-ii，`f7389627` + fix1 `0f2e4bda` + fix2 `da6032ff`，已提交）**：`MIC_DICTATION_ENABLED = true`；host 拥有 final 消费；单 lease；眼镜模式用帧路由暂停/重定向（不是 restart CXR transport）；发送/取消/超时/拒绝 UI 与终态清除已接线。
- **单一采音 lease**：听写与唤醒共用 `RECORD_AUDIO` 与同一 ASR/pipeline；host 必须保证同一时刻只有一个采音所有者（唤醒或听写），在 terminal state 停止手机采集、恢复唤醒帧路由并释放 lease；眼镜模式下用帧路由暂停/重定向（停止把 CXR 帧喂 wake/pipeline，改喂手机麦帧），**不** `disarmAudioEpoch`/重启 CXR transport/改官方握手。
- **转写路由闸门（`c3b4272a`）**：听写终态（final/timeout/cancel/error）在 cleanup 恢复唤醒环之前 `closeTranscriptRoute(reason)`；**只有显式输入**才重开：KWS wake、typed、`Help`、新会话/新 audio epoch。听写之后到达的尾音/环境语音被计数丢弃，**不会**成为 WAKE 回合或续接队列项；这是“听写文本只进 UI”的**刻意代价**，不是屏蔽音频、不是自动发送。下一次显式输入立即可继续。眼镜模式的 ambient/proactive 转写同样按此规则丢弃；若产品要求“耳语后不唤醒也能续聊”，需另立产品裁决。
- **遥测**：`P4_DICTATION state=started|refused|final|stopped|timeout|cancel|error chars=<n> reason=<...|none>`，**无 `turn`**，不记正文/hash；`P3_TRANSCRIPT_ROUTE state=closed|open|drop reason=<dictation_end|dictation_final|session|help|typed|wake>`（drop 行可带 `partials=<n> chars=<n>`）；`P3_PIPELINE state=fault reason=<code>` / `state=paused` / `state=rearmed reason=session`。
- **状态**：听写 end-to-end 已实现并有 K40 D8/D10 最终读数；尾音/busy 路径由 `c3b4272a` 设备复验；切页签草稿/陈旧状态由 `851bd478` 修复，Task 13e 设备复验（草稿保留、立即 typed accepted、NoSpeech 清除）。设备判词见 `ACCEPTANCE` §2.16 D8/D10。

**2.8.5 版本、兼容性与残余**

- **版本**：两端 `versionName = "0.4.0-preview"`、`versionCode = 4`（14a，`a8ecc067`）；README/README.zh-CN/NOTICE 已更新。不 push/tag/merge/release。
- **兼容性**：0.4 契约变化全部加法式；`wireVersion` 仍 **1**；公共 API 文档版本仍 **1**；CXR 线缆/`Caps`/回调层级未改；手机显示设备复用 `Capability.TextOutput`，不新增能力枚举；`SmartSettings.glassesDetached` 缺键 false，旧 blob 安全升级。
- **修正记录**：spec §4.3（D201 ack 不变量）、§4.4/§7.2（`lockscreen_hold` 在 `P4_AUDIO`）、§5.2/§7.2（accepted `P4_TYPED` 无 `reason`）已在 `96e66a3d`/docs 收口修正；§5.3/§7.2 的听写遥测已改为实际词表（无 `turn`）。
- **最终矩阵/发布边界**：Task 13e 已完成（APK `8fd5af06…ef09ff75`）：D3/D4（neutralization 后 60 s 零 outbound）/D7/D8/D9/D10 Verified；D5 Verified（2026-10-27 业主真人验收；合成历史 5/5/0/3 见证据）；D11 权限拒绝 Verified、live revoke NM；KWS epoch reset `b2d03c06` 在 APK `77858647…1A78C2BA` 上有利素材 5/5、同 APK 冷启动脱离 0/3。最终 JVM gate（HEAD `59f41190`）：18 个产品/工具模块 **439 XML / 4,655 tests / 0F / 0E / 2S**（探针 15/152 按 D156 排除），模型审计 15/15 + 15/15 + 6/6。D1/D2 保持 `Partial`（release/干净安装/空间/字节可复现 NM）；release APK/release provisioning、干净安装、空间不足、keyguard、真实人声/召回率、mic busy/初始化失败、APK 字节可复现、原生开关视口、精确 ASR fault code、KWS 多轮/长稳均未收口。**0.4 不是版本级通过；`NotMeasured`/`Partial` 不是 pass。** 不 push/tag/merge。
- **唤醒流 epoch reset（0.4 最终修复）**：`AudioIngressLoop.start(epoch)` 在换 epoch/音频源时调用 `wake?.reset()`，重新错相 8 条原生 KWS 流；同一 epoch 的重复 start 仍为 no-op。JVM `AudioIngressLoopTest.startingAFreshEpochResetsTheWakeStreams` 钉住。设备：有利素材 `wake13g.wav` 5/5（新进程；pre-fix 冷启动亦曾命中），但同一 APK 冷启动脱离对同一素材 0/3（`wake-zh` 1 次亦 0）；两读数并列，只证明 reset 代码正确、设备效果不稳定，不证明切换后可靠唤醒；D5 标 `Partial`（中文合成不稳定），长稳/多轮切换仍 `NotMeasured`。证据：[KWS epoch reset](evidence/2026-10-27-0.4-kws-epoch-reset-device.md)。

---

## 3. 宿主能力与生命周期

- **会话状态机**：`SessionState` 的值为 `Idle` / `Authorizing` / `Connecting` / `BuildingScene` / `Ready` / `Listening` / `Paused` / `Interrupted` / `Reconnecting` / `Error`。`HostRuntime` 通过 `observeState(listener)` 通知状态变化；`SessionStateEvent` 是 `HostEvent` 类型（生产由音频管线等生产者发出），**不是** `HostRuntime` 的状态通知通道。旧 generation 的事件被丢弃；不存在 `Starting`/`Running`/`Stopping` 这三个状态。
- **插件注册**：`PluginRegistry`；编译期注册（D12）；`plugin-interview` 保留注册但选择器不展示（D57）。
- **资源作用域**：`ResourceScope`；取消/停止时释放订阅、计时器、任务。
- **输出仲裁**：`OutputArbiter`：优先级、过期、revision、队列上限 8、显示中替换；`OutputStatus` 回到插件；`emitId` 原样回到 `OutputStateEvent`/`SpeechFinished`。
- **确认**：`ConfirmationChannel`；宿主展示、插件结算；超时由插件闸门负责（D47/D48）。

能力声明与提供者：

| 能力 | 提供者（生产） |
|---|---|
| Model | `HostCompositionRoot` 的受门控 client |
| Storage | 每插件加密命名空间 |
| StorageAccess | 用户 SKILL 目录 |
| Actions | `ExecutionRouter`（Intent/Accessibility/Shizuku/Launch/System） |
| Asr | CXR 音频链 + 已验引擎包 |
| WakeWord | 唤醒注册表 |
| SystemOne | `SystemOneServiceImpl` + Laya |
| Embedding | `EmbeddingServiceImpl` |
| SpeechOutput | `AndroidSpeechPlayer` |
| TextOutput | 眼镜提示链；脱离模式由手机 `PhoneHintDevice` 使能（同一 `Capability.TextOutput`，§2.8.2） |
| Confirmation | 手机通知 + 主屏对话框 |
| Context | 场景信号 |
| DeviceState | `AndroidDeviceStateProvider`（`HostCompositionRoot` 注册）：静态 `DeviceProfile` + 每回合 `DeviceState`；缺失字段 null 并从提示词省略 |
| Perception | `AndroidPerceptionProvider`（`HostCompositionRoot` 注册）：每回合快照、动作后重读与截图 artifact；宿主不注册时插件按可选能力处理 |
| ConversationDisplay | 会话页显示（生产 `ConversationWireDisplay`，官方 wire = `CxrHintLink`） |
| Audio | 交付路径**不广告**（无 `AudioSubscription`；转写经 `AsrSession`） |
| Speaker | 本版不注册声纹，实际不广告 |

### 3.1 CXR scene / 入口契约（0.24 S5）

- **生产入口**：`glasses-app` 的 `GlassesActivity` 是 CXR `appStart` 目标和 launcher 入口（manifest `exported=true`，`Theme.Edith.CxrScene`）。有悬浮窗权限时它是**透明、非交互、保持 RESUMED 的 CXR scene**：1×1 透明 content、透明 window、`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`；它启动 `GlassesOverlayService`，自己**不承载面板**。`Settings.canDrawOverlays == false` 时先显示最小权限页，授权返回后切到同一 scene host；该切换本身是设备 `NotMeasured`。
- **何时结束**：只在 CXR scene/session 结束时 `finish()`。生产方法是 `GlassesOverlayService.onCxrDisconnected()`（桥自己的断连回调）和 `onDestroy()` 调用 `GlassesActivity.finishSceneHost()`；普通面板关闭**不**结束 scene。Activity 被 launcher 重新打开时仍是同一入口。
- **overlay 所有者**：`GlassesOverlayService` 是 `specialUse` 前台服务，拥有 `TYPE_APPLICATION_OVERLAY` 480×380 面板、屏幕唤醒锁、CXR 监听和 `UI_READY`/`PANEL_SHOWN`/`UI_CLOSED` 的发送。`UI_READY` **按已接受的 session 身份**发送（不是每服务一次）；`PANEL_SHOWN` 在 overlay 首次布局后发送；`UI_CLOSED` 只在**可见→隐藏**时发送，隐藏状态收到 `CLOSE` 只清缓存、不重复发。`CLOSE` 按 route 或 `ALL` 关闭并清眼镜缓存；面板关闭后服务和 CXR 会话保留。CXR 断连时服务隐藏面板、释放唤醒锁、清缓存并 `stopSelf()`。
- **debug 入口**：`GlassesPanelHostActivity`（`exported=false`，旧的不透明 `Theme.Material.NoActionBar`）只承接 instrumentation 的 `EXTRA_HOST_PANEL`。该 extra 只在 `BuildConfig.DEBUG` 下生效；CXR `appStart` 永不命名这个组件，因此生产 scene 永远不是它。
- **手机侧 transport 类型**：`GlassesEvents` 只命名 EDITH 消费的五个 `IGlassesEventListener` 回调（`appResumed`/`appPaused`/`wearingChanged`/`screenOn`/`screenOff`）；listener factory 在 session 创建时、`connect` 之前注册（官方 1.1.2 顺序）。`GlassAppStarter.start(activityName, onResult)` 唯一对应 `ExternalAppClient.appStart(String, IGlassAppCbk)`，同步返回“是否问到 SDK”，异步回调给出 scene 自己的答案。`CxrHintLink.kt` 中的 `UiState` 是手机侧事件：`Ready`（服务可接收）或 `Closed(route)`（该 route 面板隐藏，服务仍活）。
- **眼镜端模型（模块内公开，不是跨端公共 API）**：`PanelModel` 的值包括 `route`、`entries`、`liveEntryId`、`contentRevision`、`status: OfficialStatus?`、`idle`、`officialIdle`、`pinned`、`listeningVisible`、`asrLoadingVisible`、`hiVisible`、`thinkingVisible`/`thinkingTurnId`、`toolLabel`/`toolTurnId`/`toolPhase`、`tips`、`finalAnswerEntryIds`。`idle` 与 `officialIdle` 不同：前者是“没有进行中的事”，后者只表示收到官方 `IDLE`（`finish_last` 只由它触发）。`PanelPort` 是 `show/hide/render/wakeScreen/sendPanelShown/sendUiClosed`。`OrbState { IDLE, LISTENING, THINKING, ANSWERING }`；`OrbRequest`（internal）是 `Status(OfficialStatus)`/`OfficialIdle`/`Legacy(OrbState)`，由 `status` 优先、`officialIdle` 次之、legacy 最后决定。
- **`OfficialStatus`**：`EMPTY=0`、`LISTENING=1`、`ASR=2`、`ANSWERING=3`、`ASR_END=4`、`AUDIO_FINISHED=5`、`TOOL=7`。wire codec 解码 0–7；**6 是官方 JSUI 卡片，超出 EDITH 范围**：眼镜消费该 `STATE`、消耗其 sequence 并记 `state status=6 ignored`，但不改变面板电平；手机不生产 6。

---

## 4. 线缆协议

### 4.1 CXR 自定义命令（眼镜 ↔ 手机）

- 传输：`Caps` 字符串，经 `rk_custom_client` 发、`rk_custom_key` 收（`HintWireCodec` KDoc）。
- 版本：`wireVersion = 1`。0.24 **不升级版本号**，只做加法式命令/字段；旧解码器对未知命令返回 `null`（安全失败）。
- **0.4 不变**：未新增/修改任何 CXR 命令、`Caps` 字段顺序或回调层级；文字消息、听写与脱离模式均不上线缆（D198/D203/D204）。
- 旧命令保留：`HELLO`、`PING`、`PONG`、`SYNC`、`SHOW`、`CLEAR`、`ACK`、`SAID`、`BUSY`、`STOP`。`PING`/`PONG`/`SYNC` 沿用 `wireVersion, command, syncId, …` 的旧形状（没有 session 前缀）；`HELLO` 及所有带 session 的命令以 `wireVersion, command, sessionId, generation` 为公共前缀。
- 0.24 新增命令（全部带 `wireVersion, command, sessionId, generation` 前缀）：

| 命令 | 方向 | 其余字段（顺序） | 语义 |
|---|---|---|---|
| `STATE` | 手机→眼镜 | `route, turnId, status, sequence` | 官方 status 电平；`status` 0–7；同一 turn 内新 `sequence` 覆盖旧 |
| `IDLE` | 手机→眼镜 | `route, sequence` | 官方 `showIdleStatus`；隐藏 Hi/思考/“…”并结束当前条目，保留面板/内容；与 `STATE` 共享 sequence 域 |
| `ASR` | 手机→眼镜 | `route, turnId, revision, text` | 佩戴者行逐字增量；final 由 `SAID` 承载；`text` 编码取末尾 400 code point |
| `ANSWER` | 手机→眼镜 | `route, turnId, hintId, revision, text` | 答案行逐字增量；final 由 `SHOW` 承载；`text` 编码取末尾 400 code point |
| `TOOL` | 手机→眼镜 | `route, turnId, phase, label` | 工具行；`phase` 为小写 `start`/`end` |
| `CLOSE` | 手机→眼镜 | `route` | 关闭该 route（或 `ALL`=所有路由）并清眼镜缓存；`ALL` 是 wire 字面量，不是 `AssistantRoute` |
| `PIN` | 眼镜→手机 | `route` | 佩戴者已钉住；**单向，没有 boolean**；手机置 `pinned=true` |
| `PANEL_OPEN` | 手机→眼镜 | `route` | 显示面板，不改变状态；已可见时内容不重置 |
| `PANEL_SHOWN` | 眼镜→手机 | `route` | 面板已在本 route 可见，手机可开 listening 计时 |
| `UI_READY` | 眼镜→手机 | *（无 route）* | 面板服务存活、可接收本 session 命令；按已接受 session 身份发送，不是每服务一次 |
| `UI_CLOSED` | 眼镜→手机 | `route` | 该 route 面板已隐藏，服务仍存活；只在可见→隐藏时发送 |

- **`STOP`**（0.23/D134 的加法命令）仍是**眼镜→手机**，字段顺序 `wireVersion, command, sessionId, generation`；它是事件而非显示请求，**没有** sequence/ACK，重复发送幂等；手机必须校验 session+generation 后才取消回合/主动请求。
- **BACK/EXIT 口径（D173）**：未 pin / pin+非 ANSWERING → 眼镜隐藏面板并发 **`UI_CLOSED`**（不额外发 `STOP`），手机 `onClose` 取消 turn/计时、清 pin；pin+ANSWERING → 只发 `STOP`，面板保留。规格 §5.3 的“STOP + UI_CLOSED”以实现为准；`UI_CLOSED` 已承担 EXIT 的取消语义。
- **文本上限**：`SHOW`/`SAID` 的正文 ≤ **400 code point**；超限在编码侧拒收（`TEXT_TOO_LONG`），不截断。`ASR`/`ANSWER` 是显示增量，编码取**末尾** 400 code point（不拆代理对）；解码端不再以长度拒收。`MAX_MESSAGE_CHARS = 65_536` 是入站消息的硬上限。`TOOL.label` 是内部工具标签，没有独立 400 上限。
- **兼容性**：旧命令的现有形状不变；0.24 新命令在旧眼镜 APK 上会 `undecodable`/null。因此**提示路径的验证必须两端同一次构建**（D63）。`route` 字段：对 `SHOW`/`SAID` 这类旧命令，字段缺失读作 `WAKE`（历史形状）；`BUSY` 和 0.24 新命令要求 route 存在且可识别，缺少/未知一律拒收。0.24 命令不因旧字段的默认值而把 malformed 消息猜成 wake。

### 4.2 提示线缆

- `HintWireCodec.WIRE_VERSION = 1`；`MAX_TEXT_CODEPOINTS = 400`。
- `HintPolicy.DEFAULT_MAX_CODE_POINTS`、`HintWireCodec.MAX_TEXT_CODEPOINTS`、`ReadableHintAssembler.MAX_CODEPOINTS` 三处相等并由测试钉住，不能各自漂移（D63）。
- 加法式字段（如 `route`）旧解码器忽略；未知 `command` 返回 null。0.24 新命令也遵守“未知即 null”，不抛异常。
- `STATE`/`IDLE` 共用 sequence 域；`STATE`/`IDLE` 的 sequence 只在新 session 身份时重置，不因面板关闭而重置，因此排队跨隐藏窗口的旧命令仍会输给新命令。

### 4.3 音频上行

- PCM：16 kHz / mono / 16 bit / little-endian（D35，源参数；`VerifiedPcm` 才表示已实测）。
- **0.4 脱离模式**：不建立 CXR 音频上行；手机麦走本地 `PcmFramer`/`AudioIngressLoop`（16 kHz/mono/16-bit 同几何），不是新的 wire 上行，也不改上述 CXR 分片/背压语义。
- `AudioUplinkCodec`/`AudioUplinkSession`/`BoundedAudioUplinkSender`：分片、背压、有界发送。
- 壳层回复上限（R-9）是**另一条**通道：每条流 8 MiB 内存上限 + 可分辨 carrier 码（D101）。

---

## 5. 破坏性变更的当前结果

| 变更 | 当前结果 | 回退成本 |
|---|---|---|
| `SystemOneService` 三动词 → 单一 `ask(state, question)`（D61） | 当前契约只有 `ask`；`SystemOneQuestionType` 区分 CHOICE/SCORE/NOUL；`latencyMs` 在 `Success` | 改回旧签名；无数据迁移 |
| `ModelRequest` 收窄；`ContextPart`/三处 `context` 删除（D80） | 当前 `ModelRequest` 为纯线缆形状；两适配器从 `RequestFrame` 渲染 | 恢复字段与适配器 `context` 分支（机械改动） |
| SKILL 全量注入 → 两层目录 + pull（D73/D105） | `SkillManager.catalog()` + `load_skill`；`injection()` 只作回退参考，无调用者 | 恢复 `instructionsFor` 全量注入（3 文件） |
| 工具面：Intent 通道排除（D83） | 验收口径 9 个工具；**代码在通道可用时还广告 6 个 Intent 工具 + 5 个 Shizuku 特权工具（4 个 L3/L4 + `set_brightness` L2）（共 11 个额外动作工具；全开时 21 个，含 `notify_user`/`load_skill`）**，见基线规格 §4.9/§10 | 下版本按 D121 重构；本版不改（D120） |
| 上传零旋钮（D53①） | 无会话级上传开关；点「开始」即唯一同意 | 恢复开关是小回退（同意对象仍在 `HostCompositionRoot`） |
| 0.24 官方会话增量（D157–D173） | 全部加法式：新命令、`ConversationDisplay` default 方法、新宿主事件、`emitId`；`wireVersion` 仍为 1；无数据迁移 | 删除新增命令/默认方法/事件即可回到 0.23 形状；已升级的两端需同构建 |
| 0.3 Phase 2 感知增量（D175–D184） | 全部加法式：`Capability.Perception`/`HostServices.perception()`、`Perception.kt`、`DeviceState` 四字段、`MediaState`；`PERCEPTION` 只作为请求尾部段，不写 store、不改历史 wire | 删除新能力/类型/段并回退 `DeviceState` 四字段（机械改动）；无数据迁移 |
| 0.3 Phase 2.1 自动视觉/压缩增量（D185） | 全部加法式：无新公共类型；observe/act/verified-open 结果增加一个 `ImageRef` 与 `data.vision`；所有截图改 JPEG q80/宽≤720/长边≤1600；`vision` 是工具 data 字段，不是 wire 字段 | 删除自动附图与 `vision` 字段、恢复旧 q85 全尺寸编码；无数据迁移 |
| 0.3 Phase 2.2 工具结果契约（D186） | **模型可见形状的有意破坏性变更**（0.3 内）：所有工具输出统一为 `{execution, observation|null, data}`；旧的工具 `detail` 字符串变成 `data`，`"Failed: "`/`"Refused: "` 前缀不再出现；`ToolObservation` 的 `vision` 取代 `data.vision`；Kotlin 公共 API 是加法式（`ActionResult.execution/observation`、`ActionDescriptor.screenObservation`、`ToolResults.kt` 新类型、`AgentRequest.observationSource`）；无 wire/权限/依赖变化 | 恢复旧的 detail 渲染与逐工具 detail 拼接（适配器/工具/测试约 20+ 文件机械回退）；无数据迁移（旧 transcript 仍可读，legacy parser 只为错误身份/审计） |
| 0.4 脱离唤醒/文本输入/内置模型（D198–D207） | 全部加法式：`UserAction.Action.Typed` + `text`（手机内部，不上线缆）、host-only `SmartSettings.glassesDetached`、`PhoneHintDevice`（复用 `Capability.TextOutput`）、`TypedSendResult`/`requestTypedMessage`、`SpeechPipeline.redirectEmit`/`finishCurrentTurn`/`DictationController`、APK 内置模型与 provisioner/`ProvisioningState`；`wireVersion` 仍 1，CXR 命令/`Caps`/回调层级不变，无数据迁移 | 删除新增枚举/字段/类型与 host 分支即可回到 0.3 形状（机械改动）；模型内置需恢复“用户自行下载 + adb push”的构建/文档路径，APK 体积与首启 provisioning 行为随之改变，无数据迁移 |

---

## 6. debug-only 测量接缝（不是公共 API）

| 类型/入口 | 位置 | 边界 |
|---|---|---|
| `MemoryMeasurementForm { PRODUCTION, LEGACY_RAW }` | `plugin-smart-helper/.../memory/` | 默认 `PRODUCTION`；`LEGACY_RAW` 只由 debug 接收器在激活前选择 |
| `MemoryEvidenceRenderer` / `ProductionMemoryEvidenceRenderer` / `LegacyRawMemoryEvidenceRenderer` | 同上 | 生产默认渲染器逐字节等于 pre-seam；legacy 只供 F4，不得接回生产 |
| `SmartAssistantPlugin.setMemoryMeasurementForm()` + `memoryMeasurementFormForTest()` | 插件 | 设置方法生产不调用；`activate()` 读一次 |
| `HostCompositionRoot.smartPluginForProbe` / `memoryStoreForProbe()` | `phone-app` | `internal`；无生产调用者 |
| `edith.DEBUG_MEMORY_FORM` / `edith.DEBUG_MEMORY_SEED` / `edith.DEBUG_F4_ANSWER` | `phone-app/src/debug` | release 源集没有；结构上由源集/manifest 分离保证（未组装 release APK 检查） |
| `edith.DEBUG_SPEECH` / `DebugSpeechToggle` | `phone-app/src/debug` | 0.23 P5 唤醒音证据；调用生产 `HostCompositionRoot.setSpeechEnabled`，只记请求/提交布尔；release 无接收器 |
| `edith.DEBUG_INJECT` / `DebugAudioInjector` | `phone-app/src/debug` | 实时 CXR ingress 注入；0.23 P5 用业主唤醒录音驱动真实 KWS；release 无接收器 |
| `EXTRA_HOST_PANEL` / `GlassesPanelHostActivity` | `glasses-app` | 仅 `BuildConfig.DEBUG`；承载旧的不透明 Activity 面板供 instrumentation；CXR `appStart` 永不命名它 |
| `onCxrDisconnectedForProbe` / `onPanelShownForProbe` / `dispatchKeyForProbe` / `senderForProbe` | `glasses-app` 服务/控制器 | 测试接缝；生产走真实 CXR 回调/按键路径，不用这些入口 |
| `P3_F4FORM` / `P3_F4MEM` / `P3_F4ANSWER` 日志 | 运行时 | 只记形态/布尔/计数/id/SHA/route；正文不进日志（D117） |
| `P5_CHIME` / `P3_CXR stop` / `P4_TICKER` 日志 | 运行时 | 只记状态/布尔/计数/偏移/id；正文不进日志 |
| `ProbeResultProjection` | `phone-app/src/debug/.../debug/` | 0.3 Phase 2.2 起 debug 探针的纯投影：`screen=observation?.screen`、`code/applied=execution?...`、候选数从 `ActionResult.detail`（已只是 data）解析；release 源集没有；不是公共 API |
| `edith.DEBUG_UTTERANCE` / `DebugUtterance`（`route=transcript` / `wake`；`help` 为既有第三档） | `phone-app/src/debug/.../debug/DebugUtterance.kt` | 0.3 Phase 3 只复用该既有 seam 注入 ASR final；`P3_UTTERANCE` 只记 state/route/turn/rev/chars/textSha256；release 无 receiver；不是公共协议。Phase 3 **不新增** `DEBUG_GATE`/`DEBUG_FAULT`，不做 forced gate/hint/fault；`P3_TURN route=` 只对非 WAKE 路由追加。Q3=最简 的共享路径不重复设备测试，未覆盖路径记 `NotMeasured`（见 §2.5） |
| `edith.DEBUG_DETACH` / `DebugDetachControl`；`edith.DEBUG_WAKE_AUDIO` / `DebugWakeAudioPlayer`；`DebugProfileCreator` | `phone-app/src/debug` | 0.4 K40 设备矩阵用：跳过设置页直接调生产 `DetachGate`/读回、向手机扬声器播放合成 WAV、选择已校验 profile；`P4_DETACH_DEBUG` 与 `P4_WAKE_AUDIO` 只记请求/布尔/计数。release 无 receiver，不是公共 API；`DebugDetachControl` 不能当 UI 验证，合成 WAV 不能当真实人声/召回率证据 |

> 这些入口用于证明"生产默认未变"与"release 无入口"，**不升级为公共 API**。

---

## 7. 契约变更流程

1. 先提变更与影响分析（消费者、回退成本、隐私/上传影响），获业主批准。
2. 在 `DECISIONS.md` 记新条目（含取代关系）；在本文件更新当前承诺；在 `ACCEPTANCE.md` 补/改验证行。
3. 破坏性变更必须给出回退成本；加法式变更优先。
4. 不在代码注释里发明新契约；代码与本文不一致时，先记 drift，再决定改哪边。
5. 0.24 S5 Task 22 已记录解析后的 wire 公共前缀、status 6 的忽略规则、`CLOSE`/`PIN` 形状与旧解码器 null 边界；后续改变这些形状必须先走本流程。

---

## 8. 0.5 纯手机模式（2026-10-29；0.5.1 更正随此节记录；不改变公共 wire/插件契约）

- **用户可见名**：0.5 预览期的「纯手机模型」自 0.5.1 起改为「纯手机模式」（`C1`）；内部标识、设置 JSON 键、日志事件名与通知 channel id `phone_only_overlay` 均不变，不做设置 blob 迁移。
- **无 wire/协议变更**：`wireVersion`、`Assistance`、CXR `HELLO/PING/SYNC/SHOW/CLEAR/PIN/CLOSE` 形状与状态 6 规则都不变；没有新的设备命令。
- **插件构造器加法**：`SmartAssistantPlugin` 新增 `phoneOnly: () -> Boolean = { false }`（默认 = 眼镜模式旧行为）；`SmartWiring` 传实时 `detachGate.enabled`。这是 app 与内置插件之间的配置，不是新的一份 wire 协议。
- **app 内部类型**：`PhoneBubbleEvent`/`PhoneBubbleFeed`、`PhoneBubbleRepository`、`BubbleStackModel`、`SpeechPlayerRouter`、`PhoneOnlyOverlayService`/`PhoneOnlyBallContent`/`PhoneOnlyBubbleContent`/`BallPlacement`/`BallGestureMachine`/`OverlayLifecycleOwner` 都只在 `phone-app` 内使用，不进 `plugin-api`，不是第三方插件 API。
- **overlay 运行状态/权限撤销与生命周期依赖（0.5.1 C12/C14/C15）**：`PhoneOnlyOverlayService.isRunning`/`startFailure` 与 `PhoneOnlyStartStopPolicy`/`PhoneOnlyOverlayRuntime` 都是 `phone-app` 进程内 app 状态，不是跨进程或插件 API。服务用 `AppOpsManager.startWatchingMode(SYSTEM_ALERT_WINDOW, packageName)` 监听运行中撤销：撤销后移除两个窗口与前台通知并 `stopSelf`；`onDestroy` 注销 watcher、`cancelDictation(BALL)`（不交付 final）并 `phoneBubble.clear()`；通知使用 app 自有单色 `res/drawable/ic_edith_notification.xml`。`phone-app` 显式保留 `androidx.lifecycle:lifecycle-runtime:2.10.0` + `androidx.savedstate:savedstate:1.4.0`；两个 ViewTree owner 在服务内仍反射安装（直接 import 不可编译，见 `DECISIONS` §21.1），安装失败以 `LIFECYCLE_OWNER` 启动失败可见。不改 wire/插件契约/设置 blob。
- **设置 blob 加法**：`SmartSettings` 新增 `ballOpacityPercent`（默认 82）、`bubbleOpacityPercent`（默认 75）、`speechConfigured`（默认 false）；旧 blob 缺字段取默认，不做迁移、不改键名；旧消息比例 `OLD_MESSAGE_ALPHA_RATIO=0.42` 固定，不是设置字段。
- **音频语义（0.5.1 更正，C5）**：`EchoMuteWindow` 从「只包脱离唤醒 ack」扩展为「所有 `AndroidSpeechPlayer` 播放都开窗」；**0.5.1 删除 120s 时间看门狗**（`watchdogNanos` 已移除，`open()` 不再计算 `now + watchdog`）。显式 `clear()` 用于手动 BALL 开始、关闭语音朗读、会话停止、长按打断；player `close()`/cancel 仍保留 150ms 混响尾（不是 clear）。丢失 TTS 结束回调时麦克风最多静音到下一次手动长按/关闭朗读/停止会话，**不会永久静音**，也不再依赖 120s 兜底。这是 app 内部半双工行为，不改 `SpeechPlayer` 的接口形状（仍是 `play(assistance, onState): CancellationHandle`）。
- **气泡契约（0.5.1，C6/C7/C10）**：最多 **2 条**（最新 + 1 条旧）；新消息到达时原旧卡立即移除，**不**补 200ms 加速淡出。最新整泡（卡片+文字+尾巴+阴影）alpha = 气泡透明度（默认 75%，30–100 可调）；旧整泡 alpha = 0.42 × 气泡透明度 × fade，`PhoneOnlyAppearance.oldMessageAlpha` 必须被 UI 实际使用；只有最新带尾巴；系统字体/颜色不变。Notice 3–4s 自动消失，不依赖 `TurnIdle`。`BubbleStackModel.MAX_VISIBLE=2` 与 `OLD_MESSAGE_ALPHA_RATIO=0.42` 是 app 内部契约，不是设置字段。
- **Agent prompt 契约（0.5.1，C8）**：`PromptCatalog.VERSION` 由 `0.4.1` 升为 **`0.5.1`**（目录新增 `phone-only/overlay.md`，资源数 18）。`PromptCatalog.plan(route, profile, phoneOnly = false)` 在 `phoneOnly=true` 时追加该 `PromptKind.STATIC` section（说明悬浮球/气泡是 EDITH 自己的界面）；眼镜模式不追加，sections 与 plan hash 形状不变。`P3_PROMPT version=` 随目录版本变为 0.5.1（手机/眼镜都是）；手机模式 `staticChars` 比眼镜多该 section。提示词不含用户正文/截图内容。
- **debug-only**：`edith.DEBUG_BUBBLE`（`DebugPhoneBubble`）只在 `src/debug`，release 源集没有；只记 scenario 名，不记消息正文；不是 UI 验证，也不是公共 API。
