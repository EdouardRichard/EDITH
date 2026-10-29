# 2026-10-15 B3 收口：异步摘要的真机三态（成功 / 8 s 自超时 / 被下一回合取代）

- **checkpoint**：`edith-0.2-phase-b-exec-20261015-11`
- **设备**：K40 `2696813b`（Android 13；验收设备口径按 **D65**）
- **要补的那一块**：B3 的异步半边在真机上只观测到过 `summary=none`，且**当时没有任何一行能说明为什么**
  （摘要请求到底发出去没有、是空答案还是被拒、还是超时）。项目此前把 `none` 猜成"真实模型对摘要请求返回空答案"。

## 1. 先让判据可观测（这是本轮唯一改的代码）

`ModelSummarizer` 原本**一行都不打**（06 会话的裁决：`ContextCompressor` 已经打了 `summary=ok/none`，
第二个 sink 就是第二个让摘要正文泄漏的地方）。代价在 2026-10-15 显形：`summary=none` 有**四种**成因
（profile 缺失 / 空答案 / 请求被模型层按形状拒绝而抛异常 / 超时），而日志分不出来。

新增**只记计数与净化后的错误码**的一行（正文、请求体依旧永不入日志）：

```
P3_SUMMARY ok=true  ms=<n> chars=<摘要长度>
P3_SUMMARY ok=false ms=<n> chars=<非空文本长度> terminal=<COMPLETED|FAILED|DEADLINE|CANCELLED> code=<provider 的 reason 或 ->
P3_SUMMARY ok=false ms=<n> chars=0 error=<safeFailureCode>        # 请求被"抛异常"拒绝时
```

- **TDD**：`ModelSummarizerTest` 新增一例（成功 / 空答案 / profile 缺失 / 抛异常四种形态都要能分辨，且日志里不得出现正文）。
  **RED 已实测**：把源码改动 stash 掉后 `ModelSummarizerTest.kt:390` 抛 `NoSuchElementException`（6 tests, 1 failed），
  恢复后 6/6 绿。`safeFailureCode` 复用 `P3_RUN` 那个已复核过的净化器（`FailureCode.kt`）。
- 这一行**取代** 06 会话"本类不打日志"的裁决：那条裁决的原文理由是"第二个 sink 会多一处泄漏面"，
  本轮把范围收到"计数 + 净化码"，并把它换成了可判读性（账本有 Ruling 与代价）。

## 2. 真机怎么把压缩逼出来的（判据：历史估算 > 1638 token）

`ContextCompressor` 的触发是 `Σ ceil(UTF-8 bytes / 3) > budgetTokens`（`estimateTokens`），
而 `budget=1638`（真机实测）。⇒ **中文 1 字 = 1 token，ASCII 1 字 ≈ 1/3 token**。
第一轮用 300 字 ASCII 填充，灌到 `historyChars=2438` 仍不触发（≈813 token）；换成 **1920 字中文填充**
（SAPI 之外的路径：用调试工具线把一段 1920 字中文当**脚本化模型回答**注入，脚本源文件仍为纯 ASCII，
中文由码点构造）后，一个回合就把历史推过预算：

```
16:30:41.129  P3_CONTEXT compress truncated=18 budget=1638
16:30:41.260  P3_SCRIPT served=text
16:30:41.502  P3_SUMMARY ok=true ms=372 chars=1920          ← 脚本线（快），管线端到端打通
```

## 3. 真实模型下的三态（本轮的关键读数）

```
16:32:56.310  P3_SUMMARY ok=true  ms=2761 chars=57          ← ★ 真模型真的产出了摘要（57 字，2.76 s）
16:32:57.283  P3_CONTEXT summary=ok chars=57                ←   占位写回转写（§4.4 step 4）
16:33:00.298  P3_CONTEXT compress truncated=2 budget=1638
16:33:08.300  P3_SUMMARY ok=false ms=8001 chars=0 terminal=DEADLINE code=DEADLINE   ← 摘要自己的 8 s 上限
16:33:08.301  P3_CONTEXT summary=none
```

以及此前同一批注入节奏下、**下一回合紧跟着来时**的形态：

```
16:31:04.420  P3_SUMMARY ok=false ms=6638 chars=0 terminal=CANCELLED code=TURN_REPLACED
16:31:04.420  P3_CONTEXT summary=none
```

`TURN_REPLACED` 的语义在代码里钉死（`model-runtime/…/ControlledModelClient.kt:69-74`）：
**每个会话同时只允许一个在飞请求**，新请求的 `turnId` 与当前 owner 不同时，把该会话的在飞请求
一律 `end(ModelEnd.CANCELLED, "TURN_REPLACED")`。摘要请求的 turnId 是 `summary-<nanos>`，
所以**佩戴者一开口，正在跑的摘要就被取消**。

**因此 `summary=none` 的真因是"摘要与佩戴者赛跑"**：它要么输给**自己的 8 s 上限**（大转写），
要么输给**下一个回合**（6.6 s 就被换掉）。此前"真实模型返回空答案"的说法**不成立**——
真模型确实能产出摘要（`chars=57 ms=2761`）；`chars=0` 的两次都不是"空答案"，而是**没等到答案**。
（脚本线那次 `chars=1920` 的正文长度即脚本内容长度，与 57 不同，故 57 那次不可能是脚本线。）

## 4. 结论与边界

- **B3 升 `Verified（K40 真机 + JVM，范围见下）`**：同步截断真机发生（`truncated=18/3/2 budget=1638`）、
  被丢弃的消息**先写库再离场**（`P3_MEMORY write=n=1 kind=FACT` 一串）、摘要请求真的发出并且**真机成功过一次**
  （`ok=true chars=57`）、失败时按 §4.4 正确退化成纯截断（转写不变、回合不受影响）。
- **仍未测/未定（就地写明）**：真实佩戴者语料下的摘要质量（本轮被打断的是合成填充文本，正文不入证据）；
  摘要的 **8 s 上限与转写长度**的关系是否要改成自适应；以及 `TURN_REPLACED` 这条**要不要放行摘要**——
  这是产品选择（`ControlledModelClient` 的单在飞规则有它自己的理由，而 §4.4 只说摘要是"纯改进"），
  **本轮记录不改**。三个可选方向：① 维持现状并如实记"快节奏会话里摘要通常落不下"；② 让摘要不参与单在飞替换
  （它不是"一个回合"）；③ 只在会话静默/停用时才起摘要。
- §4.4 的**第三个锚点**（当前 SKILL 的确认消息）仍**没有表示形式**（D60 之后 SKILL 只贡献 prompt 文本、不产生转写条目），
  类 KDoc 已记；本轮未变。
- 顺带记录：填充过猛时**单回合**就能把 prompt 顶过总额度（`historyChars=3852 tokens=6967 budget=1838 over=true`），
  那一回合以 `PLUGIN_RUN_THREW` 失败、下一个回合的压缩把它修好——是**我的灌注手法**造成的，不是回归。
