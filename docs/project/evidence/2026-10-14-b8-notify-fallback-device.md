# 2026-10-14 B8 真机逐项（兜底 + 中间步骤降级）+ I3 长通知走真实通知路径

- **checkpoint**：`edith-0.2-phase-b-exec-20261014-09`
- **设备**：K40 `2696813b`（Android 13 / API 33）+ 眼镜 `1901092544027662`；手机 debug 构建、眼镜为**同日重建并重装**的构建（见 [上限证据](2026-10-14-hint-ceiling.md) §3）
- **授权**：业主 2026-10-14 授权 ADB/无障碍代操作；点「开始」是唯一同意动作（`D53`①）
- **目标**：把 `NotifyFallbackTest` 的 **8 条**对应场景在设备上跑一遍；顺带把 I3 的长通知在**真实通知路径**（assembler → policy → wire → 眼镜）上闭环。
- **隐私**：通知正文一律用**合成 ASCII 标记**（`progress-1` / 400 个 `Q`），日志只记计数/通道/时长；全部真机读数里**不含任何佩戴者正文**。

## 1. 仪器（只替换"模型选哪个工具"这一跳）

`notify_user` 是模型的唯一出口（§6.1），而 K40 上配置的模型**从不调用它**（2026-10-14 之前所有回合走的都是 §6.2 兜底）。
因此复用 P3.3 Stage C 已有的**脚本化模型线**（`ScriptedModelWire` + `DebugToolCallInjector`，debug 源集）并**最小扩展**：

| 参数 | 作用 |
|---|---|
| `--es action notify_user --es say <正文> --es channel <screen\|voice\|both\|silent>` | 脚本一次 `notify_user` |
| `--ei times <n>` | 一个回合里脚本 **n 次**工具调用（§6.3 的"第二次通知"需要 2） |
| `--ei times 0`（可不带 action） | 只有文本答复的模型 —— §6.2 兜底的那一轮 |
| `--ez fail true` | 直接给 `response.failed`（"失败回合也要说点什么"） |

**保持真实的**：`notify_user` 工具本体、每回合的 `NotifyLedger`、§6.3 降级、§6.2 兜底、`OutputArbiter`/`HintPolicy`/线缆/眼镜。
**被替换的只有**"模型选择调用 `notify_user` 与否"。扩展先红后绿（`ScriptedModelWireTest` +5：零调用只发文本、两次调用各自 call id、失败流、action 仅在真的要发工具调用时必需、notify 参数构造）。

**驱动**：每场景一次 `wake-zh.wav`（单唤醒）+ 3 s 后 `request-note.wav`（一句请求）= **恰好一个模型回合**；
长的 `wake-zh-new.wav` 一次注入会**响两次**开两个回合（也可用，但要按 turn id 归因）。

## 2. 八条场景的真机读数

| # | `NotifyFallbackTest` 场景 | 脚本 | 真机读数（`P3_NOTIFY` / `P3_HINT` / 眼镜） | 结论 |
|---|---|---|---|---|
| S1 | 未调 `notify_user` ⇒ 补 `screen` | `times 0` | `P3_TURN end COMPLETED steps=0` → **`P3_NOTIFY fallback=screen`** → `P3_HINT show chars=7` → 眼镜 `hint rendered chars=7` | ✅ |
| S2 | 已通知则**不**再显示答复 | `notify_user/screen` | `channel=screen effective=screen n=1 downgraded=false` → `P3_HINT show chars=10`（**通知正文**，不是收尾答复）→ `reply=suppressed n=1` | ✅ |
| S3 | 通道决定是否出声 | `notify_user/both` | `channel=both effective=both n=1 downgraded=false`（`optionalSpeech != null` 送达仲裁器）| ✅（**出声未测**，见 §5） |
| S4 | `silent` 不被回填 | `notify_user/silent` | `channel=silent effective=silent n=1 downgraded=false` → **该回合无 `P3_HINT show`** → `reply=suppressed n=1` | ✅ |
| S5 | 第一条不降级、第二条降级 | `times 2 / both` | `n=1 downgraded=false` → **`n=2 effective=screen downgraded=true`** → 两条都上屏 → `reply=suppressed n=2` | ✅ |
| S6 | `screen` 从不被"降级" | `times 2 / screen` | `n=1 downgraded=false`、`n=2 downgraded=false` | ✅ |
| S7 | 日志只记通道与计数、**绝不记正文** | 复用 S9b | 全设备 logcat 中 `QQQQ`（400 字正文）**0 行**；`P3_NOTIFY` 行只有 `channel=/effective=/n=/downgraded=` 或 `reply=suppressed n=` | ✅ |
| S8 | 失败回合也要说点什么 | `times 0 / fail` | `P3_SCRIPT served=failure` → `P3_TURN end outcome=FAILED code=PROVIDER_FAILED` → `P3_HINT show chars=13`（固定句）→ 眼镜 `chars=12` | ✅ |

（`S1`/`S2` 的逐字原行见 `.local` 运行日志；`S5` 一行是关键读数：
`P3_NOTIFY channel=both effective=both n=1 downgraded=false` 紧接 `P3_NOTIFY channel=both effective=screen n=2 downgraded=true`。）

## 3. I3 的长通知走**真实通知路径**（补充闭环）

同一套仪器把 **400 code point** 的通知正文（400 个 `Q`）经 `notify_user` 送进产品路径，而不是走 §4 的调试探针：

```
P3_SCRIPT served=tool_call action=notify_user call=call_scripted_3_1
P3_NOTIFY channel=screen effective=screen n=1 downgraded=false
P3_HINT show hint=hint-df387a39-… chars=400          ← 插件侧真的把 400 字交给输出
P3_TURN end … outcome=COMPLETED code=- steps=1
P3_NOTIFY reply=suppressed n=1
EdithGlassesBridge: inbound Show chars=697            ← 眼镜收到完整报文（含 hint-<uuid> 信封）
EdithGlasses: hint rendered lines=25 maxLines=6 chars=400 clipped=true scrollMillis=26600
```

⇒ 业主裁决的"**超长通知不能截断、要眼镜端滚动显示**"在**真实通知路径**上成立：400 字 → **25 行** → 滚动 **26.6 秒**，
`HintReadingBudget` 把窗口一并延长（`emit` 的 `hintValidUntil` 修正）。这是 I3 的端到端闭环读数
（§4 的调试探针走的是同一条 `HintPolicy`/线缆/眼镜，但没有 `ReadableHintAssembler` 这一环）。

## 4. 真机跑出来的一个**生产缺陷**（已修、已再验证）

**症状**：一次 `FAILED` 回合之后，**该会话后续每一个回合都失败**：
`P3_TURN end outcome=FAILED code=PLUGIN_RUN_THREW steps=0`，`P3_RUN error=ConfigurationRejected
code=Explicit context exceeds safe budget or is malformed`，直到**重启 App** 才恢复。真机三次独立复现：
S8（故意失败）→ S9（400 字通知）失败 → S10（纯文本回合）**仍然**失败；`force-stop` + 重启后同一个纯文本回合
`COMPLETED`（S11b）。

**根因**（代码定位）：`SmartAssistantPlugin.recordTurnAndCompress` **无条件** `appendUser(...)`，而 assistant 半边
只在 `run.finalText` 非空时写 ⇒ 失败（或 `CANCELLED`）回合在会话转写里留下**一条没有答复的 user 消息**。
下一回合的第一个请求把这份转写当 history 交给 `ContextBudgeter`，而它**有意**拒绝"末尾是一个只有 user 的组"
（`groups.last().size == 1`，因为整组淘汰永远删不掉它）⇒ `ConfigurationRejected` ⇒ 该回合无声。

**顺带解释了 08 交接里的"瞬态失败"**：08 会话按 8 s 间隔注入时得到 `outcome=CANCELLED` 的回合（交接原文：
"8 s 会把上一回合取消（`outcome=CANCELLED`，没有 `P3_MEMORY`）"），`CANCELLED` 同样没有 `finalText`，留下的形状
一模一样 —— 于是"连续两回合 `PLUGIN_RUN_THREW`、重启即恢复"正是这个缺陷，而不是云端配置问题。

**修复**（TDD 先红后绿）：`recordTurnAndCompress` 现在**总是**写 assistant 半边，取的正是**佩戴者真的看到的那句话**：
`run.finalText` → 否则 `ledger.lastText`（模型通过 `notify_user` 说的通知正文，§6.1 要求它进上下文）
→ 否则该终态的固定句（与 `submitOutcome` 同一张 `FAILURE_HINTS`）。
测试 `FailedTurnTranscriptTest` 断言"失败回合之后，转写末尾必须是 assistant 文本"。
**真机再验证**：S12（故意失败）→ S13（下一个纯文本回合）**`COMPLETED`** + `fallback=screen` + 眼镜 `chars=12`。

## 5. 载体与未测（就地写明）

- **唤醒是注入的**（`wake-zh.wav` + `request-note.wav`），**不是佩戴者现场说话**；本轮亲眼见到一次**未命中**
  （注入后无 `P3_WAKE`，ASR 正常出字），与既有 2/3 召回读数一致 —— 重试一次即成功。
- **所有正文都是合成 ASCII**：Windows PowerShell 5.1 把无 BOM 的 UTF-8 脚本当 ANSI 读，中文一旦进入脚本或命令行就会
  破坏解析/传参（本轮真的踩到）。因此场景用 `progress-1` / `Q…` 这类标记；**通道与降级逻辑与语言无关**，
  中文正文的**渲染与滚动**已在 [上限证据](2026-10-14-hint-ceiling.md) 用合成 CJK 测过。
- **`voice` 通道的实际出声未测**：设备读数证明 `channel=both` 带着 `optionalSpeech` 送达仲裁器，但**没有 TTS 日志行**，
  且现场无佩戴者 ⇒ "声音是否真的从喇叭出来"仍未测。`Assistance` 没有"只播不显"的表示形式（B8 行早已记明）。
- **`--es` 的空格陷阱**：adb 会把参数重新拼成设备 shell 命令，未加引号的值带空格时只有第一个词到达
  （实测 `--es text already done for you.` 得到 7 字符的 `already`）。驱动脚本已对 `text`/`say` 加设备侧单引号。
- **未测**：佩戴可读性；§6.3 的 system message 半边（按 D62 不实现）；proactive 路由的通知；现场（非注入）唤醒召回。
