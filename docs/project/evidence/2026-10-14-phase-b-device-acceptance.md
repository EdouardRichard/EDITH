# 2026-10-14 Phase B 逐项真机验收（第一次：B1 / B5 / B6 / B7 拿到设备读数）

- **分支**：`main`；**设备**：K40 `2696813b`（Android 13）+ 眼镜 `1901092544027662`
- **授权**：业主 2026-10-14 授权 ADB/无障碍代操作；"无人介入"逐项推进
- **方法**：注入眼镜端唤醒音频开回合 + **真实模型**（档案 `deepseek · deepseek-flash`，`P3_HTTP POST 200`）
- **新增仪器（本节数据的来源）**：`P3_CONTEXT` 一行**只记计数与尺寸**——
  `route / instructions(字符) / history(条数) / historyChars / memory(条数) / tokens / budget / over`。
  没有它，"会话把上一轮带进了请求"与"什么也没带"在设备日志里完全一样（这正是这些行卡住的直接原因）。

## 1. B7 / B1：会话真的把上一轮带进下一轮（**设备读数**）

三次连续回合（间隔 28 s，每回合 `outcome=COMPLETED`）：

| 回合 | `history`（条） | `historyChars` | `memory`（条） | `tokens` |
|---|---|---|---|---|
| 1 | 1 | 9 | 0 | 1083 |
| 2 | **3** | **129** | 0 | 1195 |
| 3 | **5** | **238** | 0 | 1297 |

`history` 呈 **2n−1**：每一轮把上一轮的 user 与 assistant 都带了进来，本轮 user 在末位（未重复计数）。
⇒ **B7 的"全局会话"与 B1 的"会话转写驱动请求"在真机上成立，不再是"只在 JVM 里被构造"。**

## 2. B5：记忆写入 → 语义检索 → 注入（**设备读数**）

```
16:25:31  P3_MEMORY write=n=1 kind=PREFERENCE source=AUTO          ← §5.5 候选通过、写入并建索引
16:25:59  P3_MEMORY inject=n=1 chars=10 ids=memory:43f34796-…      ← 下一轮按查询检索到它
16:25:59  P3_CONTEXT … history=9 historyChars=452 memory=1 tokens=1501 budget=2238
```

随会话推进，注入条数随后升到 **n=5 / chars=239**，并由 `budget` 从 2238 降到 2024 反映出来
（记忆占用真实计入预算）。⇒ **生产检索走的是 `MemoryModule`（写入经 `remember` 建索引、检索含语义回退），
不是旧的纯词法路径**；`B5` 的"注入确实发生且计入预算"在真机成立。

**仍未测**：真机上"按语义而不是按词法排序"。`P3_MEMORY inject` 只带条数、字符数与 id（隐私规则），
**不带分数**，而 `MemoryRetrievalWiringTest` 证明的是 JVM 侧用假 embedding 的排序。要在真机上证明排序，
需要一个**只记计数与分数区间**的探针或一个小规模设备评测集，本轮没有做——此项保持未测。

## 3. B6：§4.4 / §4.9 的压缩环（**设备读数，账本此前标为"生产预算路径没有测试驱动"**）

推高 history 至超预算后，**真机第一次触发压缩**：

```
16:27:56  P3_CONTEXT route=WAKE history=22 historyChars=1883 memory=4 tokens=2846 budget=2117
16:28:00  P3_MEMORY write=n=1 kind=FACT source=AUTO                 ← ② 被丢弃的消息先入库
16:28:00  P3_CONTEXT compress truncated=1 budget=1638               ← ① 同步截断，预算=§4.3 的 1638
16:28:01  P3_CONTEXT summary=none                                   ← ③ 生产 Summarizer 真的跑了
```

- **① 压缩真的发生**：`truncated=1/2/3`、`budget=1638`（history 的规格额度）。
- **② 顺序正确**：被丢弃消息的写入行出现在 compress 行**同一毫秒内且紧随其后**（§4.4 步骤 2：先入库再离开转写）。
- **③ 异步摘要真跑且正确退化**：`summary=none` —— 生产 `ModelSummarizer` 向真实模型请求摘要、拿到空答案，
  按契约退化为纯截断、**不影响回合**（该回合仍 `COMPLETED`）。这正是 B3 的"云端不可用/空答案不改变截断结果"
  在真机上的第一次观测。
- **记忆随会话增长后压缩仍在同一额度上工作**：`history` 一度到 28 条，`memory` 稳定注入 5 条。

**仍未测**：`summary=ok`（摘要有正文）的路径在真机没有出现——真实模型对摘要请求返回了空答案；
摘要有正文时的占位写入仍只有 JVM 测试覆盖。

## 4. 本轮顺带修掉的两处可观测性缺口（都先红后绿）

1. **`P3_RUN` 只记异常类名**（`error=ConfigurationRejected`），**分不出**是哪一种拒绝——档案 revision 不匹配、
   端点无效、显式上下文超预算、凭证不可用**四者同类**。新增 `Throwable.safeFailureCode()`：只保留
   **形状合法的原因短语**（首字母开头、长度受限、拒绝 URL/路径/`@`/`=`/连字符凭证形状），其余一律 `UNSPECIFIED`；
   `SmartObservabilityTest` 的"message 永不入日志"断言保持不变并通过。
2. **`P3_CONTEXT` 探针**（上文 §1 的数据来源）。

## 5. 一次瞬态失败，诚实记录

16:13–16:14 有连续两个回合以 `outcome=FAILED code=PLUGIN_RUN_THREW`、`P3_RUN error=ConfigurationRejected`
结束（无 `P3_HTTP`）。**同一构建、同一档案在重启 App 后恢复正常**（16:20 起连续 20+ 回合全部 `COMPLETED`）。
当时**还没有**代码码仪器，所以**真因未被截获**；按现有证据它是一次**瞬态**（不是稳定复现的配置错误）。
下一个能读到 `code=` 的现场若再次出现，必须先取该行再判断。

## 6. 不得读成通过的部分

- **B5 的真机排序**未测（无分数可读）；**B3 的 `summary=ok`** 未在真机出现；**B4 的 SKILL 效果**未测；
  **B11**（Laya 闸门设备侧）未测；**B8** 的兜底/降级未逐项测；现场（非注入）唤醒召回未测。
- 本证据的全部正例都是**注入**唤醒音频 + **真实模型**出网；未使用假线，也未读取任何凭证内容。

## 7. 后记（同日晚，Task 3 追记）：§5 的"瞬态失败"已经被解释并修掉

上面 §5 记的是"一次瞬态失败、真因未截获"。同一天的 B8 真机逐项（[证据](2026-10-14-b8-notify-fallback-device.md) §4）
把它复现并定位了：**一次 `FAILED`（或 `CANCELLED`）回合会在会话转写里留下一条没有答复的 user 消息**
（`recordTurnAndCompress` 只在 `run.finalText` 非空时写 assistant 半边），而下一个请求的 history 被
`ContextBudgeter` 按设计拒绝（"末尾只有一个 user 的组"整组淘汰删不掉）⇒ 该会话**后续每个回合都失败**
（`PLUGIN_RUN_THREW` / `ConfigurationRejected` / `steps=0`），**重启 App 即恢复**——与本节观测完全一致。
修复与真机再验证见 B8 证据 §4；本节的读数保持原样不改，但"真因未截获"这一句自此不再成立。
