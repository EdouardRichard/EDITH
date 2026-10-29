# EDITH Agent 演进预研报告 —— 参考 e2e / X-OmniClaw / Mobile-Agent-v3.5

- **日期**：2026-10-19
- **状态**：**预研报告（参考用，未批准）**。本文件不是设计规格、不是实施计划，**不构成任何产品/契约/验收基线**；不得据此直接改产品代码、不得据此改写 `DECISIONS.md` / `CONTRACTS.md` / `ACCEPTANCE.md`。任何落地必须先走 EDITH 既有流程（规格 → 计划 → 实施 → 验收）。
- **用途**：供后续接手 EDITH 的 Agent（含人类与 AI）在做 **Agent 能力演进 / 工具调用 / Agent Loop / 上下文管理 / 手机操控** 时，快速掌握三个外部参考实现的真实做法、可借鉴点与反模式，减少重复调研。
- **读者**：后续 EDITH 开发者、评审者与自动化 Agent。
- **范围**：三个外部参考实现（`e2e`、`X-OmniClaw`、`Mobile-Agent-v3.5`）+ EDITH 当前基线 + 演进建议。其他同类项目不在本报告范围。
- **方法**：三个参考仓库均为**源码级阅读**（本机未安装依赖、未运行、未真机验证）；EDITH 侧为代码阅读 + 引用既有设备实测证据；Android API 级能力用本机 `android-35` SDK 的 `data/api-versions.xml` 与 `javap android.jar` 核对（目标设备 K40 为 API 33）。
- **与其他文档的关系**：本报告是 EDITH Agent 演进主题的唯一预研参考；原 2026-10-18 分析草案中的有效内容已并入本报告，不再保留独立文件。
- **证据边界**：全文区分「**代码事实**」「**设备实测**」「**文档级**」「**推断/未测**」。未测项集中在 §11，任何 `NotMeasured` 不得读成通过；参考仓库的 README/注释声明若未在代码中对应，按"文档级"标注。
- **落盘状态**：本文件为 2026-10-19 新增预研报告；未提交、未 push、未 tag，是否入库由业主决定。本次未写产品代码、未改决策/契约/验收、未对 K40/眼镜执行设备操作。

---

## 0. 怎么用这份报告

1. 只想知道"该往哪走"：读 §1 结论摘要 + §7 差距矩阵 + §9 路线图。
2. 要设计工具面（工具数量、schema、返回结构）：读 §8.2、§8.3、附录 B。
3. 要改 Agent Loop（循环安全、失败恢复、续跑）：读 §8.4。
4. 要改上下文管理（压缩、预算、历史）：读 §8.5、附录 C。
5. 要做设备通道（a11y/Shizuku/截图/输入）：读 §8.7。
6. 要做并发/延迟优化：读 §8.6。
7. 要排计划/定验收：读 §9、§10、§11。
8. 要核对某个事实的出处：读 §12 参考索引，回源到具体文件/行。

**使用纪律**：本报告中的外部实现结论不等于 EDITH 的选型结论；任何"照搬"都必须先做 EDITH 侧的规格与实测（尤其设备行为）。

---

## 1. 结论摘要

1. **三个参考实现的定位不同**：
   - `e2e`：**端到端测试框架**，移动端引擎建立在 `agent-device` 之上（Android = ADB + snapshot helper）；它最值钱的是 **"观察-动作-验证"的语义模型**（语义树、稳定 ref、首屏全量+后续 diff、动作后自动观察、settle、输出预算、replay），不是 driver。
   - `X-OmniClaw`：**端侧（手机内）多模态 Agent**，a11y 树为主、root 可选增强、双轨视觉兜底；工具面是"统一 `device` 工具 + 按需注入"；Agent 主循环/上下文治理在 Python（Chaquopy），工程上最接近 EDITH 未来形态。
   - `Mobile-Agent-v3.5`：**PC + ADB 的模型/框架仓库**，主路径是"截图 → 模型输出 `<tool_call>` 文本 → ADB 执行"的单 Agent 循环，另有 AndroidWorld 评测下的 Manager/Executor/Reflector/Notetaker 多 Agent；上下文是"窗口化截图 + 动作摘要 / InfoPool 切片"，无 token 预算。
2. **EDITH 现状的四个核心问题**（相对上述参考）：
   - **工具面碎且裸**：21 个动作工具、a11y 返回原生序列化文本（无 role/ref/bounds/actions）、动作按 index 寻址、动作后不自动观察、结果只有 `[applied=...]` 字符串；
   - **Agent Loop 保护弱**：只有 `ProgressGuard`（连续 3 次相同 payload）与 `maxSteps`；无重复动作分类、无连续错误追踪、无"文本计划未执行"续跑、无运行态提示；
   - **上下文无"工具结果感知"**：`TurnTrimmer` 按整回合/记忆裁剪，但没有"只保留最近一次完整快照、最后一条工具结果不截断、UI 树/工具输出分类压缩、overflow 恢复"这类规则；
   - **应用目录在 MIUI 上不可见**，直接导致 0.21 的 M2 0/10 与 AC-13/16 设备失败；外部实现（X-OmniClaw 用 a11y/Screenshot、v3.5 用 `pm list packages`、e2e 用 agent-device）都证明**绕开 App UID 的包可见层**是可行方向。
3. **演进主线（建议）**：
   - 新建 **DeviceControl 内核**：内部统一 a11y + Shizuku shell 两条通道，对外只暴露语义化工具；
   - 工具面改为 **"1 个统一 `device` 工具（观察 + 动作枚举 + 截图 + 等待 + 状态）+ 少量高层工具（open_app/settings/shell/file 等）"**，按能力动态广告；
   - 所有动作返回 **结构化 envelope + 自动观察的 diff + 稳定 ref/revision + accepted/applied/verified 三级语义 + 可恢复错误码**；
   - Agent Loop 增加 **4 类 loop detector + 连续错误追踪 + 运行态提示 + 文本计划续跑 nudge + 工具级超时**；
   - 上下文增加 **工具结果感知压缩 + 最近快照保留 + 最后工具结果豁免 + overflow 恢复 + bootstrap/skills 预算**；
   - 执行层支持 **读并行/写串行 + batch/job**，并用 **a11y in-process 优先、持久 shell、事件等待、缓存** 压延迟；
   - 设备通道补齐 **a11y 配置能力（canPerformGestures/canTakeScreenshot/多窗口 flag）、截图三级 fallback、输入三级 fallback、shell `open_app`、多窗口/IME/事件**。
4. **不要照搬的**：e2e 的测试 runner/多平台抽象/secret 体系；X-OmniClaw 的 Python/Chaquopy 桥、通用快照格式化器里的 per-app 硬编码、app UID 的 shell 兜底；v3.5 的 PC+ADB 部署、截图-only、固定 sleep、每步 2–4 次 VLM 调用、无 loop detector/无 token 预算、ADB Keyboard 依赖。
5. **最大单点收益**：**动作结果自动带观察**。EDITH 现在每个动作后模型往往要再调一次 `read_screen`，模型往返是数量级最大的开销；参考实现中 e2e（`screen-update`/`settle`）与 X-OmniClaw（动作后 settle + snapshot）都把它做成了默认行为。

---

## 2. 研究范围与方法

### 2.1 参考实现

| 参考 | 路径 | 版本/许可 | 形态 | 本报告关注点 |
|---|---|---|---|---|
| e2e | `D:\Project_new\e2e`（外部仓库，不进 EDITH 工作树） | `packages/mobile` 0.12.3、依赖 `agent-device` 0.21.28；Apache-2.0 | Web/移动端 E2E 测试框架 + Agent 测试 | 移动端引擎契约、观察/动作/断言模型、Agent 循环、上下文预算、replay |
| X-OmniClaw | `D:\Project_new\X-OmniClaw` | `main` `8614c93`；Apache-2.0 | 手机内多模态 Agent（Kotlin + Chaquopy Python） | 设备控制、统一工具面、tool 路由、Agent Loop、上下文治理、Skills/Memory |
| Mobile-Agent-v3.5 | `D:\Project_new\MobileAgent\Mobile-Agent-v3.5` | `main` `11cea57`；Apache-2.0 | PC + ADB 的模型/框架仓库（Python） | 单/多 Agent 循环、`<tool_call>` 协议、InfoPool、窗口化历史、App resolver、AndroidWorld 评测 |

### 2.2 EDITH 侧材料

- 代码：`phone-app/assistant/agent-runtime/`、`phone-app/app/.../actions/`、`common/contracts/plugin-api/`、`phone-app/app/.../app/HostCompositionRoot.kt`、`phone-app/assistant/model-runtime/`。
- 既有证据：`docs/project/evidence/2026-10-16-a11y-capability-survey.md`、`2026-10-16-shizuku-shell-capability-survey.md`、`2026-10-17-shizuku-reply-ceiling.md`、`2026-10-17-phase-7-f4-measurement.md`；`ACCEPTANCE.md`、`DECISIONS.md`、`STATUS.md`。
- 设备：红米 K40 `2696813b`（Android 13 / API 33 / MIUI）、Rokid 眼镜（Android 12）。本报告不新增设备操作。

### 2.3 证据分级

| 级别 | 含义 | 本报告用法 |
|---|---|---|
| 代码事实 | 直接读源码/配置/构建文件得到 | 直接陈述并给出路径 |
| 设备实测 | EDITH 既有证据文件中留下的读数 | 引用数字 + 证据文件 |
| 文档级 | 仅 README/注释/官方文档声明 | 标"文档级"或"README 声明" |
| 推断/未测 | 未在 EDITH 设备或参考仓库运行环境中验证 | 集中在 §11，不写成结论 |

---

## 3. EDITH 当前基线（Agent 相关）

> 数据时点：2026-10-19，`main` HEAD `3386fc4`（0.24 S1–S5 已提交；0.24 版本级业主验收尚未给出）。本报告不改变该状态。

### 3.1 Agent Loop（`agent-runtime`）

| 事实 | 代码位置/说明 |
|---|---|
| 单次 `AgentLoop.run`：组装请求 → 模型 → 有工具调用则执行并追加工具回合 → 直到模型结束 | `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/AgentLoop.kt` |
| 工具调用**串行**执行（`for` 循环），每个结果作为 `ToolOutput` 字符串回填 | 同上 |
| `ToolResult(id, ok, payload: String)`；`payload` 是纯字符串 | `ToolProtocol.kt` |
| `ProgressGuard(limit=3)`：连续 3 次相同 payload → `NO_PROGRESS` | `ProgressGuard.kt` |
| `maxSteps` 默认 24（挂死保护，不是预算）；`countsTowardStepLimit` 已存在（`load_skill` 免计步） | `AgentLoop.kt` / `ToolProtocol.kt` |
| 工具策略：`WhitelistActionPolicy` —— L0/L1 直接放行、L2 依赖白名单否则确认、L3/L4 永远确认、超过 ceiling 直接拒绝 | `WhitelistActionPolicy.kt` |
| 工具声明：`ActionDescriptor(name/title/parametersJson/risk/requiresConfirmation/idempotent/reversible/scope/auditTargetArgument/countsTowardStepLimit)` | `common/contracts/plugin-api/.../Actions.kt` |
| 审计只记录声明为 `auditTargetArgument` 的标识，不记录原始参数 | `Actions.kt` KDoc、`AgentLoop.kt` |
| 多 tool call：模型解码器支持列表，但循环串行；`ProgressGuard` 以 payload 字符串比较 | `AgentLoop.kt` |

### 3.2 工具面与执行通道

`HostCompositionRoot` 的 `ExecutionRouter` 合并 5 个后端：

| 后端 | 工具 | 通道可用性 |
|---|---|---|
| `IntentExecutionBackend` | `open_app` / `open_url` / `open_settings` / `set_alarm` / `compose_note` / `dial` | 恒 true（App 内 `startActivity`，后台可能被系统拒绝） |
| `ShizukuExecutionBackend` | `clear_data` / `uninstall` / `disable_app` / `set_permission` / `set_brightness` | Shizuku ready |
| `AccessibilityExecutionBackend` | `read_screen` / `tap` / `long_press` / `scroll` / `input_text` | 无障碍服务连接 |
| `LaunchAppExecutor` | `launch_app` / `list_apps` | Shizuku ready |
| `SystemActionExecutor` | `system_action`（back/home/recents/notifications） | Shizuku ready |

- 全开时：19 个动作工具 + `notify_user` + `load_skill` = **21 个**；0.21 验收口径记录 9 个（doc/code drift，`D120` 不修、`D121` 下版本重构）。
- `AppliedSemanticsPostProcessor` 只在 detail 前加 `[applied=true|false]`。
- **核心缺口**：a11y 返回是原生序列化文本（`[i] "text" clickable ...`），无 role/ref/bounds/actions；动作按 index 寻址；动作后快照作废、模型需再调 `read_screen`；无手势/坐标/窗口/IME/事件/截图/等待；shell 通道只用了 4 条状态读 + 5 个特权写 + launch/system，**没有用上 ADB shell 的读屏、输入、截屏、dumpsys、文件、content、通知等能力**。

### 3.3 a11y 通道现状（生产 vs 实测能力）

> 术语：本文的"生产 a11y 服务"指交付 App 内的 `EdithAccessibilityService`（`com.sheen.rokid.phone`）；"探针"指 `edith.phone.a11yprobe`（`tools/probes/phone-accessibility-probe/`，`D95` 规定的设备侧验收仪器）。**探针开着不等于生产服务可用**；K40 当前 `enabled_accessibility_services` 是探针服务，生产 `connected()` 为 false（`STATUS.md` §3）。启用生产服务必须"先读现值 → 保留/追加 → 写后验证绑定"。

- 生产：`EdithAccessibilityService`（`com.sheen.rokid.phone`），配置 `edith_accessibility_config.xml`：`flagDefault`、`eventTypes=typeWindowStateChanged|typeWindowContentChanged`；**无** `canPerformGestures`、**无** `canTakeScreenshot`、**无** `flagRetrieveInteractiveWindows/flagReportViewIds/includeNotImportant`；`onAccessibilityEvent` 空实现。
- 快照：BFS 400 节点，只序列化 `text/clickable/editable/scrollable/checked/hint/truncated`；动作成功判据是 `screenFingerprint` 变化；`input_text` ≤100 字符、不支持换行、依赖内部 `currentSnapshot`。
- 实测能力（S1，`2026-10-16-a11y-capability-survey.md`）：跨应用整树可读（Settings 136 节点/14 层；253 节点/56 层）、按 id 点击、坐标手势、`ACTION_SET_TEXT`（不需要焦点）、`ACTION_PASTE`（需要焦点）、`ACTION_SHOW_ON_SCREEN/EXPAND/COLLAPSE/SELECT`、全局动作；`getWindows()` 必须 `flagRetrieveInteractiveWindows`；`typeAllMask` 才收 CONTENT_CHANGED；一次真实变更可能零事件；事件比树快 0.03–0.7s；静默判定约 508ms；`ret=true` 不等于生效、空框 `.text` 读到 hint、WebView `findByText` 失效、读自己 App 会被主线程拖住、遮挡下语义点击仍生效而坐标点击被吃掉。生产实现只用了其中约 1/5。

### 3.4 Shizuku 通道现状

- `PrivilegedShell.run(command): ShellResult`；生产只发少量状态读与特权写。
- 实测（`2026-10-16-shizuku-shell-capability-survey.md`）：设备内 shell UID 与 PC `adb shell` 同身份；往返 **26–37ms**；`uiautomator dump` 2,283ms 且不能读自己；`screencap` 321–398ms；`pm list packages` 48–58ms；`am start -W` 105–203ms；`input text` 仅 ASCII（非 ASCII `Virtual.kcm` 无 CJK）；`pm install` 被 MIUI 拒绝；`svc` 被裁剪；并发 `uiautomator dump` 互斥；二进制需 base64（×1.33）。
- 回包上限（`2026-10-17-shizuku-reply-ceiling.md`）：**520,187 JSON 字符**（Binder 事务约 1,040,384 B；跨会话有明显的空闲余量漂移）；大输出必须按字符预算并留余量。

### 3.5 上下文与模型协议

- 请求装配：`RequestFrame` / `TurnRunner` / `TurnTrimmer`（三刀：旧整回合、记忆、压缩）；`ConversationStore` 按路由隔离；`ContextBudgeter` 做形状与预算校验；记忆模块与 SKILL 目录；`load_skill` 按需拉取。
- 模型协议：`OpenAiChatCompletionsAdapter` + `DeepSeekResponsesAdapter`；`InputPart.Image` 契约存在，但适配器目前只解析 AUDIO，**没有图片编码**；`ModelHistoryItem.ToolOutput` 只有 `String`，工具结果无法带图。
- 模型往返是数量级最大的开销（具体值取决于档案，本报告未测量）；减少往返次数（动作自带观察）是最高杠杆。

### 3.6 证据与设备纪律（必须保持）

- 日志/证据只记计数/id/哈希/状态，不记正文（`D117`）；设备侧人工操作模拟一律走 `edith.phone.a11yprobe`（`D95`）；debug-only 测量接缝（`D116`）；`NotMeasured` 不得读成通过；K40 即验收设备（`D46/D65`）。
- 后续任何 a11y 设置写入：**先读现值 → 保留/追加 → 写后 raw 验证绑定**（STATUS §1 的 S1 流程缺口）。

---

## 4. 参考实现 A：e2e（移动端）

### 4.1 架构与驱动栈

- 仓库分层：`packages/e2e`（约 500 个源文件：runner、agent 循环、locator、断言、报告、replay、MCP）+ `packages/mobile`（17 个源文件，e2e 的移动引擎）+ 外部 npm `agent-device`（会话/设备池/平台驱动）。
- **Android 底层 = ADB + snapshot helper**（agent-device README 原文；其内部实现未在本机核实）。iOS 用 accessibility bridge/XCTest；Web 用 Playwright——所以 e2e 的抽象必须是平台无关的语义层。
- `@e2e-dev/mobile` 是**薄适配层**：只调用 agent-device 的类型化命令（`capture.snapshot`、`interactions.press/longPress/fill/type/scroll/swipe/drag`、`command.keyboard/back/home/alert/clipboard`、`apps.open/install`、`settings.update` 等），**没有直接执行 adb/shell**；`nodes.ts` 把 agent-device 的扁平快照投影成 `SemanticNode`（role/name/ref/rect）。

### 4.2 观察与动作模型

- 观察：语义树（不是截图优先）；首屏全量，之后 diff（>60 行或 >50% 变化才全量）；节点 id 稳定；`truncated` 语义；键盘状态；`location`（bundle/screen title）；截图长边 768、secure 字段遮罩。
- 动作：`DEVICE_ACTIONS`（tap/doubleTap/longPress/fill/clear/press/check/uncheck/focus/hover/dragTo/swipe）；坐标动作只支持 `tap/doubleTap/longPress/swipeTo`；节点失效最多重定位 2 次；动作后按 `settle-policy` 等待（tap 2s、scroll 500ms、held-still 最多 1s；移动端另有 settle 150ms/transition 500ms）。
- **动作结果 = 下一次观察**：不在命令输出上做文章，而是动作后重新观察并渲染屏幕更新；失败动作也回当前屏幕。
- 工具输出 400 行/16KB 截断并告知；观察有字节预算（最小 1KB，按 token 上限反推）；被取代的旧屏从 transcript 省略。
- 并发：`OperationQueue` 把并行 tool call 的观察/动作**按调用顺序串行化**（避免用旧树打错节点）；测试层面一个 worker 独占一台设备。
- replay cache：通过后续 verification 的动作序列按 `<bundle id> / <screen title>` 锚点重放，零模型调用。

### 4.3 Agent 循环与预算

- `agent.act`：instruction + params + 已完成步骤 + 脱敏屏幕 → 模型 → 工具调用（grammar verbs）→ 授权/执行/观察 → 循环直到 `complete_step`。
- 预算：默认 25 次模型调用 / 25 个动作步 / 120s；单个目标动作最多 15s；`assert/extract` 各 2 次模型调用、30s 判定超时；观察与工具输出都有上限。
- 失败/环检测：重复调用/循环检测；连续 3 次失败提示换策略、5 次要 verdict；`complete_step` 的 verdict 分 passed/failed/blocked。

### 4.4 对 EDITH 的可借鉴点（按价值）

1. **动作结果自动带观察**（省一次模型往返，最高杠杆）。
2. **首屏全量 + 后续 diff + 稳定 ref + revision**。
3. **按动作类型的 settle 策略**（tap/scroll/键入不同等待）。
4. **观察/工具输出的预算与截断告知**。
5. **并发 tool call 的串行化屏障**（EDITH 做并发时必须同时做"写串行 + 读并行 + 代际失效"）。
6. **能力声明 + 不可用不广告 + 明确 `UNSUPPORTED`**。
7. **replay/录制思路**：EDITH 的 SKILL 可存"App/屏幕锚点 + 动作序列"，命中锚点直接回放（可选，后置）。
8. **错误分类 + 恢复建议 + 证据**（`applied` 之外要有 `verified`）。

**不照搬**：测试 runner/报告、跨平台 engine 抽象、云设备/多设备池、模型判定式断言、secret 遮蔽体系（EDITH 有自身 D53/D117 规则）。

---

## 5. 参考实现 B：X-OmniClaw

> OPPO-Mente-Lab，Apache-2.0；`main` `8614c93`；Android 8+；Kotlin 329 个源文件 + 6 个 Python 文件（Chaquopy 嵌入）。手机内运行、跨 App 操作、多模态（屏幕/真实世界/语音）。

### 5.1 设备控制（底层）

- **主通路 = 无障碍服务（AccessibilityService）**：
  - 服务在独立 library 模块 `extensions/observer`（编译进同一 APK），`PhoneAccessibilityService` 与主模块同进程；`AccessibilityBinderService` 名字虽叫 binder，但 `onBind()` 返回 null，靠同进程静态 `serviceInstance` 暴露服务——**不是跨进程 AIDL**。
  - 配置：`typeAllMask`、`canRetrieveWindowContent`、`canPerformGestures`、`canTakeScreenshot`、`flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews`。
  - 观察：`dumpView()` 取 `windows`（多窗口）按 layer 降序，跳过系统悬浮窗，递归收集 `ViewNode`（text/resourceId/className/packageName/contentDesc/clickable/enabled/focusable/focused/scrollable/checkable/checked/selected/bounds/point）→ RefManager（`e1..eN`）→ SnapshotFormatter（compact/tree/interactive，header 带 seq/package/refs/top_packages）。
  - 动作：点击/长按用 `dispatchGesture`（200ms/600ms 坐标手势，**ref 只用于定位**）；滑动 `dispatchGesture`；HOME/BACK `performGlobalAction`；输入 `ACTION_SET_TEXT`（focused 优先，遍历 editable 兜底，支持 Unicode）；截图 `takeScreenshot`（API 30+）。
- **root shell = 可选增强**：`RootShellExecutor`（`su -c`，5s 检测缓存、15s 超时）用于 root 截图（息屏/后台）、启动未导出 Activity（`am start -n`）、deeplink 功能、root 方式自动开启无障碍。
- **截图三级 fallback**：root `screencap` → `AccessibilityService.takeScreenshot`（系统限流约 1 张/秒）→ MediaProjection（用户授权；注释称 Android 14+/ColorOS 息屏/后台会被 revoke）。另有 app UID `sh -c screencap` 兜底（**权限模型下大概率不可用，未验证**）。
- **无 Shizuku、无 PC ADB 运行时依赖**；ADB 只出现在调试触发/文档提示中。
- **已知源码风险**：`app` 模块与 `extensions/observer` 模块各有一份同名 `accessibility_service_config.xml`，app 版本缺 `canTakeScreenshot`；按 Android 资源合并优先级 app 覆盖 library，**实际生效的配置可能缺该能力**（未构建验证，记为风险）。另：非 BACK/HOME 按键、部分 `settings/cmd`、ASCII 输入兜底走 app UID `Runtime.exec`，权限上可疑。

### 5.2 工具面与 tool calling

- 两个注册表：通用工具（`read_file/write_file/edit_file/list_dir/exec/web_fetch/config_get/config_set`）+ Android 工具（**统一 `device` 工具** + list_installed_apps/start_activity/stop/log/system_settings/gallery/memory/schedule/install/send_image 等）。
- `device` 工具是单工具 + action 枚举：`snapshot / screenshot / act(kind=tap/multi_tap/type/press/long_press/scroll/swipe/wait/home/back) / open / clipboard / status`；snapshot 产出 ref，act 按 ref 操作。
- **on-demand 工具注入**（`LlmOnDemandToolInclusion` + `LlmToolRouter`）：几乎全部工具（含 `device`）默认不给 schema；首步让模型只输出 JSON 选工具（失败回退关键词），寒暄 `tools=[]`；有 Skills 时强制补 `read_file`。
- 工具结果 `SkillResult(content, metadata)`：metadata 带 `dual_track_source/confidence`、`retry_executed`、`requires_post_send_snapshot_verify` 等提示；工具超时默认 30s，长工具（gallery）300s；参数先 normalize（模型常把值包成 JSON 字符串）。
- **dual-track 感知**：结构树高置信优先（0.78），失败/低置信 → VLM 视觉 grounding（独立 HTTP 调用），并融合到同一坐标；语义坐标记忆 12h TTL/220 条；可选端侧 YOLO/OCR（`android_ui_detection.onnx`）。
- 动作后 settle：tap/type 800ms、open 1.5s、scroll 500ms；失败提示"先 snapshot"。

### 5.3 Agent Loop

- Kotlin `AgentLoop` 只是桥；主循环/上下文/loop 检测在 Python（`agent_logic.py`），通过 Chaquopy 调用；Kotlin bridge 提供 `call_llm / execute_tool / emit_progress / stop / tool definitions / skill trace`。
- 每轮：stop 检查 → 上下文预算 → 注入非持久 runtime state hint → LLM（180s 超时）→ tool calls 逐个执行（执行前 loop detector、执行后错误追踪）→ 无 tool calls 时判断"像未完成计划"则注入续跑 nudge（最多 6 次）→ final。
- Loop detector 四类：generic_repeat / known_poll_no_progress / ping_pong / global_circuit_breaker（warning 10、critical 20、全局 30）；warning 注入提示继续，critical 停止；连续 3 次相同错误停止；snapshot/ref 类错误注入"换视觉兜底"建议。
- 结果：final_content/tools_used/iterations/messages/usage，token 累加；每次迭代/LLM/工具耗时 + progress 事件。
- **注意**：`incremental_sensor.py`（UI 相似则跳过推理）实际未接入（`skip_reasoning` 事件无发送者，死代码）；阈值偏宽（10/20/30）。

### 5.4 上下文管理（本报告认为最值得借鉴的一块）

- 系统提示（`ContextBuilder`，22 段）：identity/tooling/safety/channel hints/**Skills**/**Memory Recall**/时间/设备/workspace/state trajectory/messaging/group/reasoning format/**bootstrap 文件**/runtime；bootstrap = `AGENTS.md` + `MEMORY.md` + `memory/USER-PROFILE.md`，**单文件 20k 字符、总 150k、截断保留 20% 尾部**；寒暄/非 Agent 场景用不含 AGENTS 的轻量集。
- Skills：always skills 全文注入；其余做 catalog（`name/path/description` TSV，最多 150 条/30k 字符），要求"先 read_file 读对应 SKILL.md，只读一个"；另有**关键词 priority hints**（如剪贴板强制 `clipboard-to-shortcut`、淘宝搜索强制 `taobao-search`）。
- 运行内上下文（`context_manager.py`，每轮 LLM 前）：预算 `context_window_tokens × 4 × 0.75` 字符；<30% 不动；工具结果 >50k 字符才修剪；软修剪保留头 1.5k+尾 1.5k；硬清除占位；**保留最近 3 条 assistant、最近 5 轮对话**；**历史 UI 树只保留最近一次完整 snapshot，其余压成 header + 说明**；**最后一条 tool 结果不截断**；超预算三阶段 aggressive。
- overflow 恢复（`ContextManager.kt`）：检测 overflow → LLM 摘要压缩（最多 3 次，keep last 3）→ `ToolResultTruncator` → 放弃。
- 会话：`HistorySanitizer.sanitize(maxTurns=5)` 过滤 system/thinking；JSONL 会话存储。
- Memory：`MemoryManager`/`MemoryIndex`/gallery memory/user profile；与 Skills 相邻但独立。

### 5.5 可借鉴 / 不照搬

**借鉴**：统一 `device` 工具 + snapshot/ref + 动作后 settle；on-demand 工具路由与寒暄短路；4 类 loop detector + 错误追踪 + runtime state hint + 文本计划续跑；工具结果感知的上下文压缩（最新树保留、最后 tool 豁免、overflow 恢复）；bootstrap 预算；Skills always/catalog/priority hints；多窗口/IME 读取；事件与自动开启无障碍；截图三级 fallback。
**不照搬**：Python/Chaquopy 桥（EDITH 是 Kotlin/Android 单栈）；通用 SnapshotFormatter 里的 per-app 硬编码（剪映/飞书提示）；app UID 的 shell 兜底；`incremental_sensor` 死代码；把 root 当默认依赖。

---

## 6. 参考实现 C：Mobile-Agent-v3.5

> X-PLUG/MobileAgent，`main` `11cea57`；Python；含 `mobile_use`（手机独立部署）、`computer_use`、`browser_use`、`android_world_v3.5`（评测）、`grounding_and_kb`（模型评测）、`cookbook`。模型族 GUI-Owl 1.5（2B/4B/8B/32B，Instruct/Thinking；README 声称 tool & MCP calling、长时记忆、可作多 Agent 角色）。

### 6.1 设备控制（底层）

- **纯 ADB，PC 侧 Python**（`mobile_use/utils.py` 的 `AdbTools`）：截图 `adb exec-out screencap -p`；点击/长按/滑动 `adb shell input tap/swipe`；按键 `input keyevent`；Home `am start -a MAIN -c HOME`；打开 App `monkey -p <pkg> -c LAUNCHER 1`；包列表 `pm list packages -3`；中文输入依赖 **ADB Keyboard**（`ime enable/set` + `am broadcast ADB_INPUT_TEXT`，README 要求用户安装并切换默认输入法）。
- AndroidWorld 评测路径：通过 `env`（gRPC AndroidEnv + adb_utils）执行 `env.execute_action`，本质仍是 ADB/模拟器接口。
- **无 a11y 树、无 Shizuku、无 root**；截图-only、坐标-only；模型输出 **0–1000 归一化坐标**，runner 按 `smart_resize` 后的尺寸换算。

### 6.2 Tool calling

- 主路径是**文本工具协议**（不是 OpenAI `tools` 字段，也不是 tool_call_id 配对）：system prompt 内嵌 `<tools>` 定义（只有一个 `mobile_use` 函数，action 枚举 `key/click/long_press/swipe/type/system_button/open/wait/answer/interact/terminate`），模型输出 `<tool_call>{"name":..., "arguments":{...}}</tool_call>`，runner 字符串切分解析。
- 仓库另有 `function_call_mobile_answer.py`（`qwen_agent` 的 `BaseTool`/`register_tool`）与 `mobile_agent_utils_new.build_system_messages`（`NousFnCallPrompt.preprocess_fncall_messages`）构成的真实 function-calling helper，但**不是 v3.5 主 runner 路径**。
- 特殊动作：`open`（包名表 + LLM resolver + 提示用户安装）、`interact`（终端用户接管）、`wait`、`answer`、`terminate`。

### 6.3 Agent Loop（两套）

**单 Agent（`mobile_use` / `gui_owl.py`）**：
```
for step in range(max_steps=50):
  adb screencap → build_messages(system + 最近 4 轮图片/输出 + 当前截图)
  VLM → 解析 <tool_call> → 坐标换算
  ADB 执行（click/type/swipe/...）
  history.append(output,image); 截图标注; sleep 2s
```
无 planner/reflector/loop detector；`gui_owl.py` 在 AndroidWorld 里用 `env.get_state`（`state.ui_elements` 取到了但**不喂给模型**）、`cut_current_messages(last_image=5)`、`convert_format` 重建历史、每步后 wait 3s。

**多 Agent（`mobile_agent_v3.py`，AndroidWorld）**：Manager（首次 plan/后续更新；严格 "Finished"；错误升级）→ Executor（最近 5 条动作/结果 → JSON action）→ `env.execute_action` → sleep 3s → 前后截图 → ActionReflector（A/B/C）→ 条件 Notetaker → InfoPool 更新。无 loop detector、无 token 预算；任务步数由 AndroidWorld suite 控制（README 称约 2× 人类平均）。

### 6.4 上下文管理

- 单 Agent：**窗口化历史**——最近 4–5 轮"截图 + 输出"；更早只保留文本摘要（`Step i: <Action 描述>`）；图片 `smart_resize`（factor 28、min 3136、max ≈10M px）；**无 token 计数/压缩/溢出处理**；`.mem` 模型变体走"记忆模型"分支。
- 多 Agent：`InfoPool`（instruction/plan/completed_plan/progress/action_history/summary_history/action_outcomes/error_descriptions/last_action/last_summary/important_notes/error_flag_plan/err_to_manager_thresh=2/future_tasks）；每个 Agent 只取自己需要的摘要切片；没有全量对话历史，也没有 token 预算需求（prompt 本身短）。
- 记忆：主要在模型内（GUI-Owl 1.5 长时记忆）；框架侧只有 v3 的条件 Notetaker；**无通用长期记忆存储/检索**。

### 6.5 可借鉴 / 不照搬

**借鉴**：单 `mobile_use` 工具 + action 枚举 + 归一化坐标的模型协议；窗口化历史 + 早期动作摘要；InfoPool 任务状态字段；Manager/Executor/Reflector 的"严格完成判据 / 错误升级 / 前后截图 A/B/C / 最近 5 条动作带 outcome"提示词模式；App 打开链（包名表 + `pm list packages` + LLM resolver + 提示安装）；`android_world` 的轨迹/action.jsonl/每任务步数预算评测形式。
**不照搬**：PC+ADB 部署（EDITH 在手机内）、截图-only/坐标-only、固定 sleep 2–3s、每步 2–4 次 VLM 调用、无 loop detector/无 token 预算、ADB Keyboard 依赖、硬编码中文应用表。

---

## 7. 横向对比与 EDITH 差距矩阵

| 维度 | e2e | X-OmniClaw | Mobile-Agent-v3.5 | EDITH 现状 | EDITH 建议 |
|---|---|---|---|---|---|
| 部署 | PC 侧测试框架 + 设备 | 手机内 App（a11y 主、root 可选） | PC + ADB | 手机内 App（a11y + Shizuku） | 保持手机内双通道 |
| 感知 | 语义树（跨平台投影） | a11y 树 + 视觉兜底 + 可选 YOLO/OCR | 截图-only（VLM） | a11y 文本快照（无 role/ref/bounds） | 富语义快照 + 截图/视觉兜底 |
| 定位/动作 | 稳定 ref + 语义动词 + 坐标兜底 | ref→坐标手势（a11y） | 归一化坐标（ADB） | index + 节点 ACTION_CLICK | ref/revision + 节点动作优先 + 坐标/手势兜底 |
| 工具面 | grammar verbs + 3 device tools | 统一 device 工具 + 分组工具 + on-demand | 单 mobile_use 工具（action 枚举） | 21 个碎工具、全量广告 | 统一 device + 分组 + 按需广告 |
| 动作后观察 | **自动**（settle + diff） | **自动**（settle + snapshot） | 下一步重新截图（sleep 后） | **否**（模型再调 read_screen） | **自动带 diff** |
| Loop 保护 | 重复/循环检测 + 预算 | 4 类 detector + 错误追踪 + nudge | 无（多 Agent 仅 2 次错误升级） | ProgressGuard(3 同 payload) | 4 类 + 错误追踪 + nudge |
| 上下文 | 观察预算 + 工具输出截断 + 旧屏省略 | 字符预算 + 类型感知压缩 + overflow 恢复 + bootstrap/Skills 预算 | 窗口化历史 / InfoPool 摘要；无预算 | TurnTrimmer 三刀 + Memory + store | 工具结果感知压缩 + overflow 恢复 |
| 记忆 | 无（replay 代替） | MemoryManager + gallery memory | 模型内建 + 条件 Notetaker | MemoryModule（语义检索） | 保留，补"运行态"与"任务状态" |
| 截图 | 有（artifact） | root→a11y→MediaProjection | ADB screencap | **无** | a11y→MediaProjection→Shizuku |
| 输入 | 语义 fill/type | SET_TEXT（focused+editable 兜底） | input text(ASCII)+ADB Keyboard | SET_TEXT（有 focus 限制） | SET_TEXT→剪贴板+PASTE→shell ASCII |
| 打开 App | app.open/restart | in-app Intent / root am start | monkey / 包名表 + resolver | Intent/Shizuku am start | Shizuku `pm list` + `am start`（修 M2） |
| 并发 | OperationQueue 串行化 | 单 loop | 单/多 Agent 串行 | 串行 tool calls | 读并行/写串行 + batch/job |
| 评测 | 测试框架 + replay | 有 UI/日志/usage | AndroidWorld + 轨迹 | JVM + a11yprobe + 设备走查 | 任务语料 + 延迟目标 + M2 复测 |

**总体差距排序（对 EDITH 价值）**：
1. 动作结果自动观察（省模型往返）；
2. 富语义快照与 ref/revision（决策质量）；
3. 工具结果感知的上下文压缩（长任务稳定性）；
4. Loop 保护与错误恢复（任务成功率）；
5. 应用目录修复（M2/AC-13/16）；
6. 截图/视觉兜底（非标准 UI）；
7. on-demand 工具路由（工具面扩大后的 token/选择成本）；
8. 并发/延迟（batch、持久 shell、事件等待）。

---

## 8. EDITH Agent 演进建议

> 以下是**建议**，不是批准的设计。落地须先立规格、再写计划、再实施与验收。

### 8.1 目标架构（建议分层）

```
Plugin / 模型侧
  └─ 语义工具层（对模型暴露：device / observe / 分组工具；结构化结果）
       └─ Agent Runtime（Loop 保护、预算、策略/确认、上下文治理、并发执行器）
            └─ DeviceControl 内核（唯一持有设备状态与通道）
                 ├─ Accessibility 通道（树/节点动作/手势/文本/截图/窗口/事件）
                 └─ Shell 通道（Shizuku shell UID / root；命令/状态/文件/截图）
```

原则：**模型永远不需要知道"这是 a11y 还是 shell"**；通道细节收在 DeviceControl；工具结果语义化；失败可解释。

### 8.2 工具面设计

**推荐形态**（可在规格阶段再裁剪）：

1. **统一 `device` 工具**（高频核心）：
   - `observe`（filter/window；首屏全量，后续 diff）；`snapshot` 与 `act` 可合并为 `action` 枚举；
   - `act`：`tap/double_tap/long_press/swipe/scroll/scroll_to_text/type/press_key/wait/home/back/open/clipboard`；
   - `screenshot`（全屏/区域/标注 ref）；
   - `status`（前台 App/窗口/IME/旋转/电量等）。
2. **分组工具**（低频/强力，按通道与配置广告）：`app`（列表/启动/强停/清数据/装卸/启停用/权限）、`settings`、`system`、`intent`、`file`、`content`、`shell`、`dumpsys/logcat`、`notification`、`batch`、`job`。
3. **保留**：`notify_user`、`load_skill`。
4. **去留建议**：`read_screen`→`observe`；a11y 五工具→`device.act`；`open_app` 与 `launch_app` 合并（shell 路由）；`list_apps` 保留（目录改 shell）；`system_action` 并入 `press_key`；`set_brightness` 并入 `settings`；Intent 的 `open_url/open_settings` 可保留为便捷工具；`set_alarm/compose_note/dial` 按新风险模型决定。
5. **on-demand 与短路由**：工具面扩大后，默认只广告核心工具；首步用轻量路由（LLM 输出工具名集合，失败回退关键词）；寒暄/闲聊不广告工具；Skills 命中时补 `read_file`/`load_skill`。
6. **能力声明**：每个工具声明所需能力（a11y/gestures/screenshot/window/shell/root），不可用不广告；`UNSUPPORTED` 与"通道不可用"分开。

### 8.3 观察与工具返回

**统一 envelope**（建议扩展 `ActionResult` 为结构化 payload，`detail` 保留一行摘要用于审计）：

```json
{
  "ok": true,
  "action": "tap",
  "target": "#n16",
  "channel": "accessibility",
  "accepted": true,
  "applied": true,
  "verified": {"kind": "screen_change", "rev": 42},
  "screen": {
    "rev": 42,
    "app": "com.android.settings",
    "activity": ".Settings",
    "ime": false,
    "truncated": false,
    "changes": {"mode": "diff", "added": [], "updated": ["#n16 button \"Wi‑Fi\" [focused]"], "removed": []}
  },
  "timing": {"actMs": 22, "observeMs": 47, "totalMs": 84}
}
```

要点：

- **首屏全量 + 后续 diff**；diff 超 60 行或 >50% 变化才全量；`unchanged` 显式说明；`truncated` 时禁止声称 removed。
- 节点行：`#n16 button "Wi‑Fi" id="android:id/title" [clickable focused] bounds=[...] actions=[click,long_click,show_on_screen]`；多窗口/IME 单独成段。
- **每个 mutating 动作后按动作类型 settle 并自动观察**，把 diff 放进结果；失败动作也回全屏。
- 错误码分层：通道（`CHANNEL_UNAVAILABLE`/`SHELL_CARRIER`/`SHELL_REPLY_TOO_LARGE`）、定位（`NODE_STALE`/`NODE_NOT_FOUND`/`AMBIGUOUS_TARGET`/`NODE_NOT_CLICKABLE`）、动作（`ACCEPTED_NO_EFFECT`/`SCROLL_AT_BOUNDARY`）、验证（`VERIFY_TIMEOUT`/`FOREGROUND_MISMATCH`）、超时（`ACTION_TIMEOUT`）。
- `applied=true` 仅在独立观察到效果时返回；Intent fire-and-forget 降级为 `accepted_only`。
- 输出预算：文本 ≤16KB/400 行，超出截断到行边界并说明；大输出写文件+分页；Shizuku 回包目标 ≤64K 字符（实测上限 520,187，留 8× 余量）；二进制只回 artifact id。

### 8.4 Agent Loop 演进

1. **Loop 检测（4 类）**：generic repeat（同 tool+args 重复）、known poll no progress（轮询结果不变）、ping-pong（两工具交替无进展）、global circuit breaker；warning 注入提示继续、critical 才停止（阈值可配置，参考值 5–10 / 10–20）。
2. **连续错误追踪**：相同错误 N 次（建议 3）→ 终止并给出错误码；错误分类提示（ref/snapshot 失败 → 先重新观察或换视觉兜底）。
3. **运行态提示（非持久）**：每轮注入一条临时状态（待验证动作、已输入未发送、当前前台、最后一次观察 revision），**不写入 ConversationStore**。
4. **文本计划续跑**：模型无 tool call 且文本像"接下来我将…/下一步…"时，不要当 final；注入 nudge 要求先观察再操作（最多 N 次；闲聊/问答模式不触发）。
5. **工具级超时**：默认 30s，长工具（安装/大文件/媒体）单独配置；超时返回 `ACTION_TIMEOUT` 并说明是否可能已生效。
6. **步数/观察预算分离**：只读工具声明 `countsTowardStepLimit=false`；另设观察预算防刷屏；`maxSteps` 只做挂死保护。
7. **进度/用量**：每次迭代、LLM、工具耗时与 token 累加，供 UI/日志/性能验收。

### 8.5 上下文管理演进

1. **工具结果感知压缩**（借鉴 X-OmniClaw，最优先）：
   - 历史 UI 树只保留**最近一次完整 snapshot**，旧树压成 header + "已压缩"说明；
   - **最后一条 tool 结果不截断**（刚执行完的输出要完整）；
   - 工具结果超阈值（如 50k 字符）才修剪；软修剪保头尾、硬清除占位；
   - 保留最近 N 轮 / N 条 assistant（参考 5 轮/3 条）。
2. **overflow 恢复**：检测上下文超限 → 压缩（LLM 摘要，最多 3 次）→ 截断超大工具结果 → 放弃并给出可解释错误。
3. **预算分层**：system/工具/观察/历史/记忆各有预算；观察预算按剩余 token 反推；工具输出 ≤16KB/400 行。
4. **bootstrap/文件预算**：AGENTS/MEMORY/USER-PROFILE 类文件单文件/总预算、截断保留尾部比例（参考 20k/150k/20%）。
5. **历史策略**：窗口化"最近 K 次观察（含截图/树）+ 更早动作摘要"；与现有 `TurnTrimmer` 三刀组合，而不是替代。
6. **Skills**：always skills 全文 + 其余 catalog（name/path/description）+ "只读一个 SKILL.md"约束 + 关键词 priority hints；与 EDITH 现有 `load_skill` 对齐。
7. **运行态与任务态**：`TaskRuntimeState`（前台/待验证/已输入未发送）+ `TaskState`（plan/completed/progress/outcomes/notes）与 `ConversationStore` 解耦，按需注入。

### 8.6 并发与延迟

- **DeviceControl 并发内核**：写操作单一 FIFO 串行；读操作可并行但带 generation 屏障（cross-write 的读标记 stale/重读）；每操作独立超时；a11y 调用用线程池 + Future 超时，超时降级 shell。
- **AgentLoop 多 tool call**：按 `readOnly` 分组——只读并行（有界，如 ≤4）、写串行、结果按调用顺序回填；`ProgressGuard` 改看"观察状态指纹"而非 payload 字符串；turn 取消要传到 DeviceControl。
- **batch/job**：`batch({actions:[...], stop_on_error})` 一次调用执行一串动作并返回逐步结果+最终观察；`job` 支持长任务异步（start/poll/cancel），避免阻塞回合。
- **延迟优化（按收益）**：动作自带观察 > 设备端等待/事件 > batch/scroll_to_text > diff > a11y in-process 优先 > 持久 shell（省 `sh -c` 进程创建）> 缓存（app 目录/label/settings/前台）> 并行读 > 限制昂贵路径（`uiautomator dump` 2.3s、未过滤 `dumpsys`、大 `logcat`、全表 `content`）。
- **目标（待实测验证，不得写成已达成）**：`observe`（interactive，中等屏）p95 ≤150ms；`tap+observe` p95 ≤400ms；`shell` 空命令 p95 ≤80ms；关键任务模型调用次数相对 0.21 基线下降 ≥40%。

### 8.7 设备控制通道实施要点

1. **a11y 配置补齐**：`canPerformGestures`、`canTakeScreenshot`、`flagRetrieveInteractiveWindows`、`flagReportViewIds`、`includeNotImportantViews`、`typeAllMask`；**只保留一份权威配置并验证 merged 结果**（X-OmniClaw 的双配置冲突是教训）。
2. **观察**：从 `rootInActiveWindow` 扩到 `getWindows()`（多窗口/IME/悬浮窗），节点属性补 role/id/bounds/states/actions/collection/range；稳定 ref 由节点路径+指纹生成；revision 与动作代际绑定。
3. **动作**：节点 `ACTION_CLICK`/`ACTION_LONG_CLICK`/`ACTION_SET_TEXT`/`ACTION_SCROLL_*`/`ACTION_EXPAND`/`ACTION_SELECT` 优先（遮挡免疫，S1 已实测语义点击在遮挡下仍生效）；`dispatchGesture` 做长按/滑动/多击/坐标兜底；`performGlobalAction` 做 HOME/BACK/RECENTS/NOTIFICATIONS；按键用 shell `input keyevent` 兜底。
4. **截图三级**：`AccessibilityService.takeScreenshot`（需 canTakeScreenshot，限流约 1 张/秒）→ MediaProjection（可选，用户授权）→ **Shizuku `screencap`**（K40 无 root 时比 root 路径可行；321–398ms、二进制 base64 ×1.33）。截图缩放长边 768；截图保存为 artifact，不塞 base64 进文本。
5. **输入三级**：`ACTION_SET_TEXT`（去掉 EDITH 现有 focus 硬依赖；S1 实测未聚焦也可写）→ 剪贴板+`ACTION_PASTE`（需焦点；写剪贴板用 App API）→ shell `input text`（仅 ASCII）。中文只走前两级（S1：`Virtual.kcm` 无 CJK）。
6. **打开 App**：用 Shizuku `pm list packages` + `cmd package resolve-activity --brief` + `am start -W -n`；修掉 App UID 包可见层导致的 `list_apps` 只有 EDITH / M2 0/10。**保留应用 label 解析与"未安装提示"**（参考 v3.5 的 resolver 链）。
7. **事件**：`onAccessibilityEvent` 订阅（typeAllMask），记录前台 App/Activity、窗口变化、内容变化；`wait_for` 用事件+有界轮询兜底（S1：事件不是每变必有；静默约 508ms）。
8. **自动启用 a11y**：Shizuku shell 写 `enabled_accessibility_services`/`accessibility_enabled`，**先读现值→合并→写后 raw 验证绑定**；不得只凭设置值声称生效。
9. **通道降级**：shell 挂 → shell 工具不广告；a11y 关 → 依赖语义树的工具不广告，保留 input/screencap 兜底；两者都无 → 明确恢复指引。

### 8.8 测试与验收

- **JVM**：DeviceControl/ToolResult/Context 规则用 fake 通道全覆盖；工具 schema 与结果 envelope 契约测试。
- **设备**：debug-only 测量接缝 + 可 grep verdict；设备侧人工模拟仍走 `a11yprobe`（`D95`）；a11y 设置写入按 STATUS §1 的"读-合并-写-验证"纪律。
- **能力矩阵**：每个工具 × 通道 × 正/负例 × 恢复路径在 K40 上跑通；未测写 `NotMeasured`。
- **延迟**：`observe`/`tap+observe`/`shell` p50/p95（n≥30）。
- **任务语料**：固定语料（含 0.21 M2 的"播放音乐"场景、设置/Wi-Fi/发消息草稿/文件操作等）成功率、模型调用次数、token 成本；**M2 复测与 AC-13/16 设备复测**随工具面修复进行。
- **回归**：全量 JVM、两端构建/安装、既有 AC 行逐条复核、drift 如实记录。

---

## 9. 建议路线图（供规格阶段裁剪）

### P0（最小闭环，先拿"观察-动作"质量）

| 项 | 内容 | 验收 |
|---|---|---|
| P0-1 | 统一 `device` 工具（observe/act/screenshot/status）+ 结果 envelope + 自动观察 diff + ref/revision | JVM 契约测试；K40 设备矩阵；模型面对工具数下降 |
| P0-2 | 上下文：工具结果感知压缩（最新树/最后 tool 豁免/overflow 恢复） | JVM 规则测试；长任务不爆上下文 |
| P0-3 | Loop：4 类 detector + 连续错误追踪 + runtime state + text-only nudge | JVM 场景测试；设备长任务成功率对比 |
| P0-4 | 应用目录改 shell 路径（`pm list` + resolve-activity + label） | **M2 复测**、AC-13/16 设备复测 |
| P0-5 | on-demand 工具路由 + 寒暄短路 | token/工具选择指标 |

### P1（能力与稳定）

| 项 | 内容 | 验收 |
|---|---|---|
| P1-1 | a11y 富快照 + 多窗口/IME + 手势/截图配置 + 事件 | 设备能力矩阵；配置 merged 验证 |
| P1-2 | 截图三级 fallback + 长边 768 + artifact | K40 实测（限流/息屏/后台） |
| P1-3 | 输入三级 fallback（去 focus 硬依赖、剪贴板+PASTE） | 中文/英文/emoji 实测 |
| P1-4 | Skills always/catalog/priority hints + bootstrap 预算 | prompt 体积与命中率 |
| P1-5 | 工具级超时 + 延迟/usage 指标 | p50/p95 读数 |

### P2（增强，按收益评估）

| 项 | 内容 | 验收 |
|---|---|---|
| P2-1 | dual-track 视觉兜底（截图 + VLM grounding）；可选端侧 YOLO/OCR | 非标准 UI 任务成功率 |
| P2-2 | 读并行/写串行 + batch/job + 持久 shell | 延迟与稳定性读数 |
| P2-3 | 语义坐标记忆、多会话并行、vision 模型档案 | 单独 spike |
| P2-4 | replay/SKILL 录制（屏幕锚点 + 动作序列） | 重复任务零模型调用 |

---

## 10. 需业主裁决的事项（提案，不是决策）

### 10.0 与现行规则的冲突（需改判，不能自行改）

| 现行规则 | 冲突点 | 建议 |
|---|---|---|
| `D53`/`D117`：日志/证据只记计数/id，不记正文；基线规格 §8.3：屏幕内容"不上线" | 新工具要求把屏幕树/截图作为工具结果发给模型 | 需新决策：**"会话运行即允许把当前屏幕内容发给所配置模型"**，日志/证据仍只记计数；单独列 AC |
| `D84`：坐标点击不暴露 | 最强能力需要坐标/手势兜底 | 改成"语义优先、坐标作为无节点时的显式兜底，返回里标注 via" |
| `D95`：一切设备侧人工操作模拟走 `a11yprobe` | 生产 agent 的工具不是"人工模拟"，但验收时的模拟仍必须用 probe | 保留 D95 作为**验收仪器纪律**；生产工具由 debug-only 测量接缝驱动验证 |
| `D120`/`D121`：工具面下版本重构 | 本任务正是该重构 | 单独立版（候选 0.25），先规格再计划 |
| `AC-10`：`applied` 不得冒充生效 | 新结果模型要扩展到 `accepted → applied → verified` 三级 | 保留并加强 AC-10 |
| `R-8`：`set_alarm`/`compose_note`/`dial` 不可用 | 工具面重构时去留 | 按"最强最全"重新定义（见 §8.2） |

1. **版本归属**：是否单独立版（候选 0.25）承接 `D121` 工具面重构；与 0.24 解耦。
2. **屏幕内容可发给模型**：修订 `D53`/基线规格 §8.3 口径——"会话运行即允许当前屏幕/截图发给所配置模型"，日志/证据仍只记计数（`D117` 不变）。
3. **坐标/手势/通用 shell/任意 content/文件等"最强能力"范围**：全开 vs 分组开关；破坏性不可逆动作（reboot/shutdown/pm clear）是否保留 L3 确认。
4. **Shizuku 是否作为推荐依赖**：a11y 必需、shell 可选降级；shell 不可用时的产品提示。
5. **vision 投入**：是否做 `InputPart.Image` 适配器 + `ToolOutput` 内容分片 + 多模态档案；不做时截图只作为 artifact/坐标依据。
6. **验收口径**：M2 复测 + 新能力矩阵 + 延迟目标 + 任务语料；按项目预注册/证据纪律执行。

---

## 11. 未测与风险（不得读成通过）

1. **生产 a11y 快照/动作延迟**：S1 只有探针读数；生产 `EdithAccessibilityService` 的 observe/gesture 延迟未单独测量。
2. **`takeScreenshot` / 全局动作**：S1 的 G08 故意未执行（怕打断另一条工作线）；K40 上未实测。
3. **多指 pinch/zoom、`continueStroke`、`ACTION_IME_ENTER`/`ACTION_PASTE` 在真实第三方 App**：未测。
4. **剪贴板+PASTE 中文输入在 K40/MIUI/微信等真实 App**：工程代码存在但未验证。
5. **无线调试 in-app ADB client**：未测；MIUI 行为未知。
6. **持久 shell 的延迟收益**：26–37ms/次含进程创建是实测；长驻 shell 收益是推断，未测。
7. **模型往返延迟、截图 token 成本**：未测（取决于档案）。
8. **MIUI 对 a11y 服务长期保活/被系统杀**：未测。
9. **X-OmniClaw 的双配置 merged 结果**：未构建/未真机验证；其 `canTakeScreenshot` 是否生效存疑。
10. **X-OmniClaw / Mobile-Agent-v3.5 / e2e 的运行行为**：本机均未安装依赖/未运行/未真机执行；本报告相关结论为源码/文档级。
11. **X-OmniClaw 的 app UID shell 兜底（input/screencap/settings）**：权限模型下大概率不可用，未验证。
12. **`SHELL_REPLY_TOO_LARGE` 在新工具实际使用中的稳定性/并发大回包**：未测。
13. **`takeScreenshotOfWindow`**：API 34+，K40 API 33 不可用；不作本版能力。

---

## 12. 参考索引

### e2e（`D:\Project_new\e2e`）

- `packages/mobile/src/{engine,surface,nodes,actions,tools,device,bindings,pool}.ts`
- `packages/e2e/src/agent/{tool-loop,action-dispatcher,screen-update,operation-queue,observation-budget,transcript-compaction,settle-policy,tool-output,primitives,observation,action-names}.ts`
- `docs/{mobile,agent-steps,tools,cache,core-concepts}.mdx`；`docs/reference/{agent,mobile}.mdx`
- 底层：`agent-device` 官方 README（Android = ADB + snapshot helper）

### X-OmniClaw（`D:\Project_new\X-OmniClaw`，`main` `8614c93`）

- `app/src/main/java/com/shijing/xomniclaw/agent/loop/{AgentLoop,ToolLoopDetection}.kt`
- `app/src/main/python/{agent_logic,context_manager,loop_detector,incremental_sensor,utils,session_logger}.py`
- `app/src/main/java/com/shijing/xomniclaw/agent/tools/{ToolRegistry,AndroidToolRegistry,Tool,ToolCallDispatcher,LlmToolRouter,LlmOnDemandToolInclusion}.kt`
- `app/src/main/java/com/shijing/xomniclaw/agent/tools/device/{DeviceTool,RefManager,SnapshotBuilder,SnapshotFormatter,DualTrackDecisionEngine}.kt` + `device/yolo/`
- `app/src/main/java/com/shijing/xomniclaw/agent/context/{ContextBuilder,ContextManager,MessageCompactor,ContextWindowGuard}.kt`
- `extensions/observer/src/main/java/com/shijing/xomniclaw/accessibility/service/{PhoneAccessibilityService,AccessibilityBinderService,A11yScreenshotter,AccessibilityEventDispatcher}.kt`
- `extensions/observer/src/main/java/com/shijing/xomniclaw/accessibility/{MediaProjectionHelper,RootScreencap,ObserverForegroundService}.kt`
- `app/src/main/java/com/shijing/xomniclaw/{DeviceController.kt,deeplink/RootShellExecutor.kt,util/AccessibilityAutoEnableHelper.kt,agent/skills/SkillsLoader.kt}`
- `extensions/observer/src/main/res/xml/accessibility_service_config.xml`；`app/src/main/res/xml/accessibility_service_config.xml`

### Mobile-Agent-v3.5（`D:\Project_new\MobileAgent\Mobile-Agent-v3.5`，`main` `11cea57`）

- `mobile_use/{run_gui_owl_1_5_for_mobile.py,utils.py,packages.py}`
- `android_world_v3.5/android_world/agents/{gui_owl.py,mobile_agent_v3.py,mobile_agent_v3_agent.py,function_call_mobile_answer.py,mobile_agent_utils_new.py,infer_ma3.py}`
- `android_world_v3.5/run_ma35.py`、`run_ma35.sh`、`run_guiowl15.sh`
- `cookbook/end2end_usage_mobile.ipynb`

### EDITH

- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/{AgentLoop,ToolRegistry,ToolProtocol,WhitelistActionPolicy,ProgressGuard,ContextPlanner,RequestFrame,TurnRunner,TurnTrimmer,ConversationStore}.kt`
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/{ExecutionRouter.kt,a11y/,shizuku/,intent/}`
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/HostCompositionRoot.kt`
- `phone-app/app/src/main/res/xml/edith_accessibility_config.xml`
- `common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/{Actions,ModelServices}.kt`
- `phone-app/assistant/model-runtime/src/main/kotlin/com/sheen/rokid/model/{OpenAiChatCompletionsAdapter,DeepSeekResponsesAdapter,...}.kt`
- 证据：`docs/project/evidence/2026-10-16-a11y-capability-survey.md`、`2026-10-16-shizuku-shell-capability-survey.md`、`2026-10-17-shizuku-reply-ceiling.md`、`2026-10-17-phase-7-f4-measurement.md`
- 基线：`docs/superpowers/specs/2026-10-17-edith-0.21-baseline-architecture-design.md`（§4.9 工具面、§8.3 隐私、§10 验收）
- 状态：`docs/project/{STATUS,DECISIONS,CONTRACTS,ACCEPTANCE}.md`

---

## 附录 A：Android API 级核对（`android-35` SDK；K40 = API 33）

| API | since | K40 可用 | 备注 |
|---|---:|---|---|
| `AccessibilityService.getWindows()` | 21 | ✅ | 需 `flagRetrieveInteractiveWindows` |
| `getWindowsOnAllDisplays()` | 30 | ✅ | 多显示器 |
| `getRootInActiveWindow()` | 16 | ✅ | 只读活跃窗口 |
| `getRootInActiveWindow(int)` | 33 | ✅ | 按 display |
| `dispatchGesture(...)` | 24 | ✅ | 需 `canPerformGestures` |
| `performGlobalAction(...)` | 16 | ✅ | 全局动作 |
| `getSystemActions()` | 30 | ✅ | 查询可用全局动作 |
| `takeScreenshot(...)` | 30 | ✅ | 需 `canTakeScreenshot` |
| `takeScreenshotOfWindow(...)` | 34 | ❌ | 不作本版能力 |
| `GLOBAL_ACTION_TAKE_SCREENSHOT` / `LOCK_SCREEN` | 28 | ✅ | S1 未执行 |
| `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` | 31 | ✅ | — |
| `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` | 24 | ✅ | — |
| `AccessibilityNodeInfo.getViewIdResourceName()` | 18 | ✅ | 需 `flagReportViewIds` 才易读回 |
| `getHintText()` | 26 | ✅ | 空框 `.text` 可能是 hint |
| `getPaneTitle()` / `getTooltipText()` | 28 | ✅ | — |
| `getActionList()` | 21 | ✅ | 决定可用节点动作 |
| `getCollectionItemInfo()` / `getRangeInfo()` | 19 | ✅ | 列表/滑杆 |
| `getDrawingOrder()` | 24 | ✅ | z 序 |
| `AccessibilityAction.ACTION_IME_ENTER` / `ACTION_PRESS_AND_HOLD` | 30 | ✅ | — |
| `FLAG_REPORT_VIEW_IDS` / `RETRIEVE_INTERACTIVE_WINDOWS` / `INCLUDE_NOT_IMPORTANT_VIEWS` | 16/64/2 | ✅ | 常量值 |
| `FLAG_REQUEST_MULTI_FINGER_GESTURES` | 30 | ✅ | 触摸探索用；多 stroke 手势不依赖它 |
| `CAPABILITY_CAN_TAKE_SCREENSHOT` / `android:canTakeScreenshot` | 30 | ✅ | 需在服务 XML 声明 |

> 数字来自本机 SDK `api-versions.xml` 与 `javap`；行为结论以 S1 的 K40 实测为准，二者不混用。

---

## 附录 B：工具结果 envelope 草案（供规格阶段细化）

```json
{
  "ok": true,
  "action": "tap",
  "target": "#n16",
  "channel": "accessibility",
  "accepted": true,
  "applied": true,
  "verified": {"kind": "screen_change", "rev": 42},
  "screen": {
    "rev": 42,
    "app": "com.android.settings",
    "activity": ".Settings",
    "ime": false,
    "truncated": false,
    "changes": {
      "mode": "diff",
      "added": [],
      "updated": ["#n16 button \"Wi‑Fi\" [focused]"],
      "removed": []
    }
  },
  "timing": {"actMs": 22, "observeMs": 47, "totalMs": 84},
  "error": null
}
```

失败示例：

```json
{
  "ok": false,
  "action": "tap",
  "target": "#n16",
  "channel": "accessibility",
  "accepted": false,
  "applied": false,
  "verified": null,
  "screen": {"rev": 43, "changes": {"mode": "full", "text": "…"}},
  "error": {"code": "NODE_STALE", "message": "ref no longer in newest observation", "recoverable": true}
}
```

---

## 附录 C：上下文规则速查（建议）

| 规则 | 参考值 | 来源/理由 |
|---|---:|---|
| 工具输出文本上限 | 16KB / 400 行 | e2e `tool-output.ts` |
| 观察最小预算 | 1024 B | e2e `observation-budget.ts` |
| diff 上限 | 60 行或 50% | e2e `screen-update.ts` |
| 长按默认 | 1000ms | e2e `actions.ts` |
| held-still 上限 | 1000ms | e2e `settle-policy.ts` |
| tap 类 changeWait | 2000ms | e2e `settle-policy.ts` |
| scroll 类 changeWait | 500ms | e2e `settle-policy.ts` |
| 工具结果修剪阈值 | 50k 字符 | X-OmniClaw `context_manager.py` |
| 保留最近 assistant | 3 条 | X-OmniClaw `context_manager.py` |
| 保留最近对话轮 | 5 轮 | X-OmniClaw `context_manager.py`；v3.5 窗口 4–5 图 |
| 上下文预算 | `tokens × 4 × 0.75` 字符（X-OmniClaw 近似） | EDITH 有真实 token 预算器，建议保留自身预算器 |
| bootstrap 单文件/总量/尾部 | 20k / 150k / 20% | X-OmniClaw `ContextBuilder` |
| Skills catalog 上限 | 150 条 / 30k 字符 | X-OmniClaw `ContextBuilder` |
| Shizuku 回包安全目标 | ≤64K 字符 | 基于实测上限 520,187 字符留 8× 余量 |

---

## 附录 D：建议工具面明细（草案）

> 依据 e2e 的经验：**动作变体分开建工具，不要做成一个 `kind` 可选参数**（模型会在普通按钮上误填 double_click）；**高频动词独立**，**低频系统能力按域聚合**（一个 `action` 枚举）。下表是"能力池"，实际广告时按通道与配置裁剪；核心常驻建议 10–12 个。

| 工具 | 参数要点 | 通道 | 建议 |
|---|---|---|---|
| `observe` | `filter`(interactive/text/all)、`window`(active/all/id)、`include_pixels` | a11y→shell | 替代 `read_screen`；首屏全量/后续 diff |
| `find` | `text`(精确/子串)、`id`、`role`、`desc`、`limit` | a11y | 返回候选 ref，避免整屏 dump |
| `screenshot` | `region`、`scale`、`annotate`、`return_image` | a11y/shell | 需先补 vision 适配器（§8.7/§11） |
| `device_status` | — | a11y+shell | 前台/窗口/IME/旋转/电量/网络/音量/亮度/勿扰 |
| `wait_for` | `text_appears/disappears`、`foreground`、`idle`、`timeout_ms` | 设备端 | 事件驱动 + 有界轮询，返回最终观察 |
| `tap` / `long_press` / `double_tap` | `ref` 或 `text`/`id` 或 `point`；`via`(auto/a11y/shell)；`expect`（期望后置条件） | a11y→shell | 三个独立工具（e2e 教训） |
| `swipe` | `direction`/`from,to`/`within_ref`/`duration`；`scroll_to_text` | a11y→shell | 含 `scroll_to_text`（长列表一次调用） |
| `gesture` | strokes（多点/路径/时长）、pinch/zoom 简写 | a11y | 无节点时使用 |
| `input_text` | `ref`/`text`（定位）、`value`、`mode`(replace/append/clear)、`submit` | a11y → shell(ASCII) | 不限 100 字符；支持换行/Unicode |
| `press_key` | `key`（BACK/HOME/RECENTS/NOTIFICATIONS/ENTER/DEL/TAB/ARROWS/VOLUME/MEDIA…）、`repeat`、`longpress` | a11y 全局动作 → shell | 统一入口 |
| `node_action` | `ref` + `action` + 参数（scroll 方向/selection/范围） | a11y | 暴露平台声明的一切节点动作 |
| `open_app` / `list_apps` / `app` | 名称或包名；`relaunch`；`action`(info/stop/clear/enable/disable/uninstall/permission) | shell | **合并现有 open_app/launch_app**；目录走 shell |
| `settings` | `get/put/delete/list`、`namespace`、`key`、`value` | shell | 覆盖亮度/音量/免打扰/输入法等 |
| `system` | `action`：wifi/airplane/bluetooth/location/dnd/rotation/lock/unlock/wake/sleep/stayon/font_scale/dark_mode/split_screen | shell | 按实测可用性广告 |
| `intent` | start/broadcast/service + action/data/component/extras/flags | shell | 通用能力 |
| `file` | `read/write/append/ls/stat/rm/mkdir/pull/push/base64`；`offset/limit` 分页 | shell | 大文件必须分页/落盘 |
| `content` | query/insert/update/delete/call + URI/projection/where/limit | shell | 最强读/写数据面 |
| `shell` | `command`、`timeout_ms`、`max_bytes`、`stdin`、`as_root` | shell/root | **"最全"的逃生口**；结构化 stdout/stderr/exit |
| `dumpsys`/`logcat` | service/filter/lines/since | shell | 限幅输出 |
| `notification` | list/post/dismiss/dnd | shell+Listener | — |
| `media`/`clipboard` | 播放控制 / 读写剪贴板 | shell/App API | 剪贴板后台读受限（实测 null） |
| `batch` | `actions[]`（每个可带 expect/wait）、`stop_on_error` | 设备端 | **一次调用执行一串动作**，返回逐步结果 + 最终观察 |
| `job` | start/poll/cancel + `kind`(shell/screenrecord/logcat…) | 设备端 | 长任务异步，避免占用回合 |
| `notify_user`/`load_skill` | 现有 | — | 保留 |

**与现有 21 个工具的去留**：

| 现状工具 | 处置 |
|---|---|
| `read_screen` | → `observe`（富属性 + diff + 窗口） |
| a11y `tap/long_press/scroll/input_text` | → 新动作集（ref/text/id/point、scroll 方向/到文本、Unicode、IME enter） |
| `open_app`（Intent） + `launch_app`（Shizuku） | 合并为 `open_app`，shell 路由；后台安全 |
| `list_apps` | 保留，目录改 shell+PM 合并 |
| `system_action` | 并入 `press_key`（全局动作） |
| `set_brightness` | 并入 `settings`（可保留便捷别名） |
| `open_url`/`open_settings` | 可保留为 Intent 便捷工具（前台时走 Intent，后台走 shell） |
| `set_alarm`/`compose_note`/`dial` | 按新风险模型决定；`dial` 等建议保留但按破坏性/外呼定级 |
| Shizuku 4 个 L3/L4 | 保留，并扩展 `app` 组（装卸/清数据/启停用/权限） |
| `notify_user`/`load_skill` | 保留 |

---

## 附录 E：契约与模块改动清单（草图）

| 位置 | 改动 |
|---|---|
| `common/contracts/plugin-api` | `ActionResult` 增结构化 payload/observation；`ActionDescriptor` 增 `readOnly`/分组；`ModelHistoryItem.ToolOutput` 支持内容分片（图片）；`InputPart.Image` 编码接口 |
| `phone-app/.../actions` | 新 `device/` 包：`DeviceControl`、`Observation`（walk/render/diff）、`ChannelHealth`、`Gesture`、`Screenshot`、`ShellSession`、`ToolCatalog`；旧 5 个 a11y 工具/4 个 shell 工具迁移 |
| `phone-app/.../actions/a11y` | 扩展 `EdithAccessibilityService`（窗口/事件/手势/截图/节点动作）、`edith_accessibility_config.xml` |
| `phone-app/.../actions/shizuku` | 持久 shell（AIDL 扩展：timeout/cancel/stream/分页）、命令结果结构化、二进制通道 |
| `agent-runtime` | 并发 tool 执行器、结果 envelope、观察预算、ProgressGuard 改造、步数/只读分类 |
| `model-runtime` | 图片编码（Chat Completions/Responses）、图片 token 预算、profile 门控 |
| `phone-app/app` | 应用目录改 shell+PM 合并、`open_app` 后台路由、debug 测量接缝 |
| `tools/probes` | 可选：`phone-control-probe` 或复用现有 debug receiver；验收模拟仍用 `a11yprobe` |

---

## 附录 F：DeviceControl 并发内核要点（草案）

- **状态**：`generation`（每次写 +1）、最新快照缓存、前台/窗口/IME 状态、通道健康。
- **写路径**：单一 FIFO 队列串行执行；每个写动作执行后 bump generation 并触发一次自动观察（可合并到动作结果）。
- **读路径**：可并行，但每个读在入队时记录 generation；若期间发生写，读结果标记 `stale` 或等到写完成后再读（读锁/代际屏障）。
- **超时/取消**：每个操作独立超时；a11y 调用可能被目标 App 主线程卡住（S1 实测），必须用线程池 + Future 超时，超时后标记通道降级并可换 shell 兜底。
- **结果版本化**：所有 ref 带 revision，跨 revision 的 ref 一律 `NODE_STALE`（或按稳定身份自动重定位，最多 2 次，e2e 做法）。
- **AgentLoop 侧**：按 `readOnly` 分组（只读并行有界 ≤4、写按模型顺序串行）；`ToolOutput` 按调用顺序回填；`ProgressGuard` 看观察状态指纹而非 payload 字符串；只读工具 `countsTowardStepLimit=false` + 观察预算；turn 取消传到队列与在途操作。

---

## 附录 G：a11y 实测能力清单（S1 摘要）

| 能力 | S1 实测 | 生产使用 |
|---|---|---|
| 跨应用整树可读 | Settings 136 节点/14 层；253 节点/56 层无截断；可统计 null children | ✅ 仅粗粒度 |
| 按 id 点击 | `findAccessibilityNodeInfosByViewId` 1/1 命中 | ❌ |
| 坐标点击/滑动/多击 | `dispatchGesture` 可用 | ❌（配置未开 gestures） |
| 长按/`ACTION_SHOW_ON_SCREEN`/`EXPAND`/`COLLAPSE`/`SELECT` | 实测可用 | ❌ |
| `ACTION_SET_TEXT` | 不需要焦点即可写未聚焦框；返回 true 不等于生效 | ✅（额外要求 focus） |
| `ACTION_PASTE` | 需要焦点；未聚焦返回 false | ❌ |
| 勾选/选中 | 平台无 `ACTION_CHECK/UNCHECK`；点击 + 读回 `isChecked`；`ACTION_SELECT` 可用 | ❌ |
| 全局动作 | HOME 可靠；顶层 BACK 未定论；RECENTS/NOTIFICATIONS 系统窗口可见 | ❌（绕道 shell） |
| 多窗口 | `getWindows()` 需 `flagRetrieveInteractiveWindows`（实测 3→0）；IME/悬浮窗只在这里可见 | ❌ |
| 事件 | `typeAllMask` 才收 CONTENT_CHANGED；一次真实变更可能零事件；事件比树快 0.03–0.7s；静默约 508ms | ❌（空实现） |
| 已知失败模式 | `ret=true` 但文字没变、空框 `.text` 读到 hint、WebView `findByText` 失效、读自己 App 被主线程拖住、遮挡下坐标点击被吃、语义点击仍生效 | 部分用指纹兜底 |

---

## 附录 H：延迟基线（实测/待测）

| 操作 | 读数 | 来源/备注 |
|---|---|---|
| Shizuku shell 往返 | **26–37ms**（设备内，n=10×3） | 实测；+ 命令自身耗时 |
| `settings get` | ~40ms | 实测 |
| `am start -W` | 105–203ms | 实测 |
| `pm list packages` | 48–58ms | 实测 |
| `screencap` | 321–398ms（PC 路径） | 实测；设备内 base64 ×1.33 |
| `uiautomator dump` | 2,283ms | 实测；仅最后兜底 |
| 事件静默判定 | ~508ms（250ms 阈值） | 实测 |
| a11y 树读取 | 未单独测 | S1 有探针读数；生产需补测 |
| a11y 节点动作/手势 | 未单独测 | 生产需补测 |
| 模型往返 | 未测 | 取决于档案；减少往返是第一优先 |
| `takeScreenshot`（a11y） | 未测（限流约 1 张/秒，代码/文档级） | K40 未实测 |
| 持久 shell 收益 | 未测 | 26–37ms 含进程创建是实测；收益为推断 |

---

## 附录 I：错误码与恢复建议（草案）

| 层级 | 错误码 | 模型/宿主如何恢复 |
|---|---|---|
| 通道 | `CHANNEL_UNAVAILABLE`(a11y/shell/root)、`SHELL_CARRIER`、`SHELL_REPLY_TOO_LARGE` | 换通道或提示用户开启；大输出改文件+分页 |
| 定位 | `NODE_NOT_FOUND`、`NODE_STALE`、`AMBIGUOUS_TARGET`（附候选 refs）、`NODE_DISABLED`、`NODE_NOT_CLICKABLE`（附最近可点击祖先 ref） | stale 自动刷新并回传新 ref；歧义让模型/用户选 |
| 动作 | `ACCEPTED_NO_EFFECT`（平台受理但指纹/截图都没变）、`SCROLL_AT_BOUNDARY`、`INVALID_ARGUMENTS` | 明确"别重复点"，换 long_press/滚动/坐标 |
| 验证 | `VERIFY_TIMEOUT`（带最后观察）、`FOREGROUND_MISMATCH` | 附当前前台/窗口信息 |
| 超时 | `ACTION_TIMEOUT`（附已执行到哪一步、是否丢弃） | 可重试但说明幂等性 |

> 关键：`applied=true` 只允许在"独立观察到效果"时返回；Intent fire-and-forget 这类"平台接了就算成功"必须降级成 `accepted_only`。

---

## 13. 修订记录

| 日期 | 内容 |
|---|---|
| 2026-10-19 | 初稿：合并 e2e / X-OmniClaw / Mobile-Agent-v3.5 三个参考实现源码级调研 + EDITH 基线差距 + 演进建议（工具面/返回/Loop/上下文/并发/设备通道/测试）。未批准、未立项、未提交。 |
| 2026-10-19 | 修订：并入原分析草案中仍有效的工具面明细（附录 D）、契约/模块清单（附录 E）、并发内核（附录 F）、a11y 能力清单（附录 G）、延迟基线（附录 H）、错误码表（附录 I）；独立草案文件删除（内容已并入本报告）。 |
