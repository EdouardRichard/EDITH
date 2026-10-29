# 2026-10-14 B11 设备侧：生产 Laya 引擎切到 GPU，并在 K40 上真的编译成功

- **checkpoint**：`edith-0.2-phase-b-exec-20261014-09`
- **设备**：K40 `2696813b`（Android 13 / API 33，骁龙 870）
- **授权**：业主 2026-10-14 授权 ADB/无障碍代操作
- **背景**：计划的 Global Constraints 与 A5 证据都要求**交付目标机用 GPU**
  （`2026-10-13-phase-a-performance-k40.md:535`：同一张图 **CPU 742 ms vs GPU 231 ms**），并写明 GPU 必须
  `CompiledModel` + **显式 `Precision.FP32`**（FP16 实测 NaN）、LiteRT **必须 2.2.0**。而
  `LayaInferenceEngineImpl` 出厂默认值是 **CPU**（复审 M4 记过、一直没改、也没有文档记录这个偏离）。

## 1. 改了什么

- `LayaInferenceEngineImpl` 的 backend 默认 **CPU → GPU**，常量命名为 `DEFAULT_BACKEND` 并由 JVM 测试钉住。
- **退化被显式记录**：`initialize()` 若在请求的 backend 上失败，且请求的不是 CPU，则**重试一次 CPU**，
  并打 `P3_LAYA fallback from=GPU to=CPU`；两次都失败才返回 `false`（`SystemOneServiceImpl` 据此置
  `UNAVAILABLE`）。`effectiveBackend` 是**实际在用**的 backend，`ask` 用它 —— 降级后的闸门不可能被读成 GPU 闸门。
- **可观测性（"如实记录"那一半）**：每次尝试打一行
  `P3_LAYA init backend=<GPU|CPU> ok=<true|false> ms=<冷启动耗时>`；失败时同时释放半成品 host。
- 退化**策略**抽成纯函数 `cpuFallbackFor(requested)`（CPU 是唯一下探；请求 CPU 时无可下探），JVM 可测。

## 2. 真机读数（K40，`install -r` 后全新启动）

```
17:33:19.242  EdithP3 : P3_LAYA init backend=GPU ok=true ms=7134
```

- **GPU 图在目标机上真的编译并初始化成功**，冷启动 **7.13 s**（约 3.0–3.3 s tokenizer + 约 3.4 s GPU 编译，
  与计划给冷启动的预算一致）。
- **没有** `P3_LAYA fallback …` 行 ⇒ 本轮**没有走到退化路径**（GPU 自己就成了）。
- 该行出现在 App 启动后 ~25 s 内、会话尚未开始之前：`SystemOneServiceImpl` 在构造时起 loader 线程，
  `initialize()` 与 warm-up 都在那里，符合"Laya 不得进临界路径、冷启动要排期"的约束。

## 3. 边界（不得读成已完成）

- **本轮测到的是"GPU 图编译 + host 初始化成功"，不是"设备上真的产生过一次闸门判决"**。
  `SystemOneServiceImpl` 的 warm-up 确实会在 GPU 上跑一次 `ask`（服务只有 warm-up 后才 `READY`），
  但**没有生产日志行**证明它、也没有观测到一次 `P3_PROACTIVE` 的闸门判决 ⇒
  `B11` 的"真机闸门判决"仍**未测**（所需的触发未被廉价过滤放行：既有读数是 `P3_PROACTIVE skip reason=VAGUE`）。
- **退化路径没有被执行过**：`fallback` 行本轮未出现，因此"GPU 失败 → CPU 成功"这条真机路径**未验证**；
  其策略由 JVM 测试钉住，SDK 那一跳只能上真机（要有 GPU 编译失败的设备才能触发）。
- `GateForm` 仍是固定的 `NOUL`（业主 2026-10-14 裁决：维持并记"等 S3"），`NoulGate` 阈值仍是默认 0.5。
- 未测：GPU 单次 `ask` 的延迟（A5 的 231 ms 是 K70 读数，**不继承**到 K40）；功耗/发热；多轮并发下的表现。
