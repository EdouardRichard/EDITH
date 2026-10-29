# EDITH 的 ASR/上屏延迟：**只从 APP 自己的日志**能算出什么（K40 真机）

- 日期：2026-10-15 22:5x–23:0x（Asia/Shanghai）；数据源＝**当时 logcat 环形缓冲**，覆盖 `10-15 16:32:54 → 22:26:33`
- 设备：红米 K40 `2696813b`（MIUI / Android 13 / API 33），`edith.phone` 已在跑；眼镜 `1901092544027662`（固件 `1.26.011-20260929-150201`）
- 本文件是**对既有日志的离线分析**，本轮没有跑新会话、没有注入新音频、没有改任何代码或设备设置

## 0. 先记一个坑：P3 行分两个 logcat tag

| 来源 | tag | 行前缀 | 覆盖的标记 |
|---|---|---|---|
| 宿主/音频/模型（`phone-app` 的 `P3Log`） | **`EdithP3`** | `P3_…` | `P3_FRAME` `P3_AUDIO` `P3_CXR` `P3_ACOUSTIC` `P3_WAKE` `P3_INJECT` `P3_HTTP` `P3_LAYA` `P3_EXEC` `P3_CONTEXT` |
| 插件（`plugins-smart` 的 `P3Log`） | **`System.out`** | **`EdithSmart P3_…`** | `P3_ASR` `P3_TRANSCRIPT` `P3_TURN` `P3_HINT` `P3_PROACTIVE` `P3_CONV` `P3_MEMORY` `P3_TOOL` `P3_SUMMARY` `P3_SKILL` `P3_NOTIFY` `P3_RUN` |

插件是纯 JVM 模块，生产 sink 写 `System.out`（`plugins-smart/P3Log.kt:24`），Android 把它归到 **`System.out`** tag。
⇒ **只 `grep EdithP3` 会把 ASR/转写/回合/上屏行全漏掉**（本次我先踩了一次：`P3_ASR` 计数 0，是过滤器的假阴性，不是产品没打）。
正确取法：`adb logcat -d -v time -s EdithP3:* System.out:*` 再按 `P3_` 过滤。

**日志按设计只记计数与标志**（`chars=`/`usable=`/`final=`/`ms=`），**不记正文** ⇒ 时延、节律、空结果率可算；**字准/WER 不可算**。

## 1. 从该缓冲算出的 EDITH 读数（全部为**注入音频**路径，见 §3 边界）

| 指标 | 读数 | 样本 |
|---|---|---|
| `P3_ASR` 行 / 终稿 / 可用终稿 / **空终稿** | 52 / 20 / 13 / **7** | 全缓冲 |
| **语音起点 → 首个中间结果** | 36–876 ms（中位 **458 ms**） | 19 次 `P3_ACOUSTIC label=Speech` 之后的首条 `P3_ASR` |
| **语音起点 → 可用终稿** | **480 / 689 / 798 / 819 / 916 / 926 / 1071 / 1393 / 1522 / 1631 / 1857 ms（中位 926 ms）** | 11 次 |
| **首个中间结果 → 终稿** | 45–393 ms（中位 **122 ms**） | 13 次（剔除两个空终稿相邻对） |
| 空终稿的到达时间 | 230–876 ms（**很快，但 chars=0 usable=false**） | 7 次 |
| **上屏 ACK（`P3_HINT show` → `ack status=Displayed`）** | **62–93 ms（中位 77 ms）** | 7 次 |
| `P3_WAKE word=伊迪丝 confidence=1.0` | 10 次（当天） | — |
| Laya 闸门单次判决 `latencyMs` | 240–291 ms（GPU） | 5 次 |
| 模型 HTTP `P3_HTTP … ms=` | 113–499 ms | 11 次 |
| 主动提示生成 `P3_PROACTIVE hint … ms=` | 2815–3659 ms | 3 次 |
| 整回合 `P3_TURN start→end` | 9.8 s（steps=1）/ 15.5 s（steps=1，含云端模型） | 2 次 |
| 音频入口健康 | `overflow=0 gaps=0`（22:22 起） | `P3_FRAME` |

典型一轮（16:32:54 注入 `b4-t1-open-settings.wav`，2.83 s）：

```
16:32:54.639 P3_INJECT state=start file=b4-t1-open-settings.wav chunks=140 windows=2
16:32:55.264 P3_ACOUSTIC label=Speech                       (+625 ms 相对注入起点)
16:32:55.802 P3_WAKE word=伊迪丝 confidence=1.0              (+538 ms 相对语音起点)
16:32:56.004 EdithSmart P3_ASR revision=0 final=false chars=2 usable=true   (+740 ms)
16:32:57.121 EdithSmart P3_ASR revision=4 final=true  chars=6 usable=true   (+1857 ms)
16:32:58.333 EdithSmart P3_TURN end … outcome=FAILED code=PLUGIN_RUN_THREW
16:33:00.451 EdithSmart P3_HINT show hint=… chars=13
16:33:00.544 EdithSmart P3_HINT ack  hint=… status=Displayed (+93 ms)
```

## 2. 与官方助手那一轮的口径对齐（**不可直接相减**）

| | 起点定义 | 读数 | 口径说明 |
|---|---|---|---|
| 官方（22:42 实测一轮） | **音频流开始**（=唤醒后起流，用户还没说完） | → 首个 `ASR_Result` **1.95 s**；→ final **2.52 s** | 含用户说话时长 + 眼镜→手机上行 + 手机→眼镜回推 |
| 官方同轮 | 首个中间结果 → final | **570 ms** | 这一小段可与 EDITH 的 122 ms 中位对齐 |
| EDITH | **VAD 判为语音**（已经开始说） | → 可用终稿 中位 **926 ms** | 不含"说话"时长；文件注入（无麦克风/噪声） |

**可比项只有一个**：`首个中间结果 → 终稿`——官方 570 ms vs EDITH 中位 122 ms。其余两边起点不同，**不能拿 2.52 s 和 0.93 s 比大小**。

端点参数同档（来源：代码/manifest，非日志）：EDITH `endpointMillis=350`（`tools/p2/engine-manifest.json`，允许 250/350/450；`minSilenceDuration=endpointMillis/1000`、`minSpeechDuration=0.25 s`、`maxSpeechDuration=20 s`，`audio-android/.../SherpaNativeConfiguration.kt:35`）
vs 官方手机侧 RFM ASR `--vad_end_ms=400`（`assets/rfmasr/rfm-asr.conf`，见 [官方助手证据](<2026-10-15-official-assistant-vad-asr-locality.md>)）。

## 3. 诚实边界

1. **全部是文件注入**（`edith.DEBUG_INJECT` → `P3_INJECT`），不是眼镜麦克风：**不含**距离、噪声、增益、佩戴姿态（用户 `D55` 2026-10-12 明确"跳过真实采音"）⇒ 这些数字应读作**同样句子下的上限**，不是端到端体验预测。
2. **样本小**：可用终稿 11 个、上屏 ACK 7 个、单日缓冲。**不是** P95，也不是验收口径（T16 的 RTF P95≤0.7 / 60 s / 600 s 仍未测，`D38` 推迟）。
3. **空终稿 7/20（35%）** 是本缓冲里最刺眼的读数：其中 22:05:18/21/23 三次是同一段连续语音被切成 3 次空终稿，属既有记录里的 `ASR_DRIFT` 家族（`D54`）——**记为问题，不记为通过**。
4. **准确率不在此列**：日志不记正文 ⇒ 字准/WER 只能靠标注语料 harness（P3.5 的 `recall 0.633 / precision 0.802` 是**触发**指标，不是 ASR 字准）。
5. 官方侧只有 1 轮 4 次会话可测（用户自发使用），**不具备统计意义**。

## 4. 复现命令

```powershell
$P='2696813b'
adb -s $P logcat -d -v time -s EdithP3:* System.out:* > p3.txt
# 只看插件行（ASR/回合/上屏）
Select-String -Path p3.txt -Pattern 'EdithSmart P3_'
# 语音起点→终稿：按时间排序，找每段 P3_ACOUSTIC label=Speech 之后的首条 P3_ASR 与首条 final=true
```
