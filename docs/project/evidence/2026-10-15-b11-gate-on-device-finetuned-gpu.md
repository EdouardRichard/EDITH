# 2026-10-15 B11 收口：微调 Laya 在 K40 的 **GPU** 上跑通，闸门真的做了一次判决

- **checkpoint**：`edith-0.2-phase-b-exec-20261015-10`
- **设备**：K40 `2696813b`（骁龙 870 / Android 13）+ 眼镜 `1901092544027662`
- **授权**：业主 2026-10-15 授权 ADB/无障碍代操作；业主本轮决策：**Laya 运行后端＝GPU**（D64），微调模型已就绪
- **要补的两块**：① 微调交付集**从未在任何设备上跑过推理**（交接 §6）；② B11 的**一次真实闸门判决**从未观测到

## 1. 先修一个把设备挡死的缺陷：MediaTek NPU 运行时**无条件打进 APK** 会在非联发科设备上崩进程

**症状**：装上报 Fine-tuned 集的构建、启动 App ⇒ **SIGSEGV**，`Laya-LiteRT` 线程，
`LiteRtCreateCompiledModel` → `libLiteRtCompilerPlugin_MediaTek.so (LiteRtGetCompilerPluginSDKVersion+)`，
Java 栈 `LayaEngine.initialize → mainGraph → createGraph → CompiledModel.create`。

**性质**：D64 §3 已经记过这个组合（"应用包里混装联发科 compiler plugin，在非联发科设备上加载即崩"），
但当时只作为"跨 SoC 走 NPU 不可依赖"的观察，**没有改默认打包**。后果是 **K40（开发机）连 Laya 与
embedding 引擎都起不来** —— 比"服务不可用"更糟（拒绝服务是 B9 的既定行为，崩进程不是）。

**修法（本轮实施）**：`phone-app/build.gradle.kts` 把 MediaTek 运行时做成 **opt-in**
（`withMediaTekNpu`，默认 **false**）：默认既不声明 `litert-npu-runtime-mediatek`，也不把
`.local/npu-runtime` 加进 jniLibs；需要 NPU 诊断时 `gradlew -PwithMediaTekNpu=true :phone-app:assembleDebug`。
这与 D64 "**NPU 只作诊断、运行链只有 GPU→CPU**"一致，且 `useLegacyPackaging` 保持无条件（GPU 的 dispatch 库同样按路径解析）。

**实测**：默认 APK 的 `lib/arm64-v8a/` 里 MediaTek/neuron 条目 **0 个**（APK 98,711,140 → 88,783,976 B），
装 `install -r` 后不再崩。

## 2. 微调七件套在 K40 的 GPU 上真的跑了

推送（`/data/local/tmp/laya-ft` → 逐文件 `run-as cp files/`，7/7 字节数与本地一致；本地源
`E:\layaft\artifacts\laya-ml-embeds-bnd-seed20261014\`）后启动：

```
15:32:09.894  EdithP3 : P3_LAYA init backend=GPU ok=true ms=6578
```

⇒ **微调图（含 act head、tokenizer、393 MB 词表、calibration）在 GPU 上编译成功**，冷启动 6.58 s。
更关键的是**真的跑出了有限值**（见 §3 的 `confidence=0.6738`，不是一个 Inf/NaN）。

## 3. B11 的真机闸门判决（本次要补的那一块）

造一个**主动**触发（不含唤醒词，否则会开唤醒回合）：Windows SAPI 合成
"帮我查一下明天北京的天气"（含 `帮我` / `查一下` ⇒ `IMPERATIVE`）→ `edith.DEBUG_INJECT` 注入：

```
15:33:22.394  P3_INJECT state=start file=proactive-check-weather.wav chunks=176 windows=3
15:33:22.794  P3_ACOUSTIC label=Speech
15:33:25.620  P3_ASR revision=7 final=true chars=12 usable=true
15:33:25.660  P3_PROACTIVE admit score=4 features=IMPERATIVE,TIME,PRONOUN_SELF,LENGTH   ← 廉价过滤放行（不是 VAGUE）
15:33:25.667  P3_PROACTIVE request ctx=5 chars=12
15:33:25.963  P3_PROACTIVE gate form=NOUL intervene=true confidence=0.6738 latencyMs=284.762031 reason=-
15:33:25.978  P3_SKILL load files=1 skills=1 enabled=1                                  ← 主动路由也注入了 SKILL
15:33:27.123  P3_HTTP method=POST status=200 ms=368
15:33:28.558  P3_PROACTIVE hint chars=24 ms=2897
15:33:28.562  P3_HINT show hint=hint-b5092143-… chars=24
15:33:28.700  P3_HINT ack hint=hint-b5092143-… status=Displayed                          ← 眼镜真的显示了
```

- **`gate form=NOUL intervene=true confidence=0.6738 latencyMs=284.762031`** 就是 B11 缺的那一次判决：
  它由**微调后的 Laya 图**在 **GPU** 上产生，输出**有限**（0.6738），单次 **284.8 ms**。
  参照：A5 在 K70 上量到 GPU 231 ms / CPU 742 ms，本轮 K40 GPU 284.8 ms 与"GPU 量级"一致。
- 判决之后整条主动链也走完了：SKILL 注入 → 云端 `POST 200` → `hint` → **眼镜 `status=Displayed`**。
- 触发是**注入**语音、**不是佩戴者现场说话**（载体限定）；这一次注入一次即被放行（score=4）。

## 4. 边界（不得读成已完成）

- **K70 不需要单独的复验（`D65`，业主 2026-10-15）**：验收设备＝K40，K40 通过即认定 K70 通过（K70 性能更高 ⇒ 方向保守），
  **不对 K70 过度测试**。本条只改了"在哪台设备上验收"，**不改**下面这些仍未测的项。
- **CPU 退化路径仍未被执行**（没有 `P3_LAYA fallback` 行）；GPU 编译失败的设备才能触发它。
- **阈值仍是 `NoulGate` 的默认 0.5**：本轮 `confidence=0.6738` 落在阈值之上。微调的标定阈值**没有**读进闸门
  （交接 §7 的教训：阈值不得在报告集上选），要定它需要一份验证集，属另一件事。
- 未测：GPU 功耗/发热/持续负载；多轮并发；微调模型相对基座的**端到端行为差异**（本轮只证明它在设备上可用，
  不是"它比基座更好"——那要一份设备侧评测集）。
- **SoC/厂商特有通路不适用 `D65` 的推断**：NPU（MediaTek）在 K40 上根本执行不到，既不能由 K40 通过来认定，
  也不能由 K40 失败来否定；D64 已把 NPU 移出运行后端，故对生产链路无影响。
- 本轮把微调集推进了 K40 的 `files/`（覆盖了发布方原件）；发布方原件仍在设备
  `/data/local/tmp/laya-ml-embeds/` 与本地 `.local/models/laya-ml-embeds/`，可原样推回。
