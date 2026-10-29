# 0.3 ASR 复读与持续对话修复：K40 设备 A/B 与 JVM 门禁（2026-10-22）

> 一次性执行记录。语料是固定的合成 TTS 句子，不含用户数据；日志与证据只保留计数、id、哈希与固定合成文本，原始音频正文只留在 `.local/`。
> **判词边界**：本文件证明 ASR decoder 数据修复与两条持续对话输入丢失路径的修复；真实 CXR 人声、长稳、RTF P95、主动路线的设备多轮仍 `NotMeasured`，不是版本级通过。

## 1. 结论

1. **ASR 复读**：EDITH 侧设备 A/B 复现并关闭了「int8 decoder 是直接原因」这一结论。同一 APK、同一 bundle 其余文件、同一 WAV：
   - int8 decoder：`今天天天气怎么样`、`播放播放播放周周杰伦伦的稻香`（复读）；
   - fp32 decoder：`今天天气怎么样`、`播放周杰伦的稻香`（干净）。
2. **持续对话**：设备日志证明「LLM 响应期间说的下一句被静默丢弃」是当前代码的真实缺陷；修复后的 APK 上，该句进入 `P3_CONV queue`，在答案的 continuation 打开时以 `P3_CONV queued_consumed` 成为下一回合，`P3_TURN start` 使用该句自己的 ASR turn id。答案结束到 continuation 打开之间的 Idle 间隙同理由 JVM 回归覆盖。
3. **主动路线**：同一单槽队列机制已按路由实现（`P3_CONV queue route=PROACTIVE`），并有 JVM 回归；设备主动多轮仍 `NotMeasured`。
4. **未改官方 10 s 监听窗**：`ConversationCycle` 的 10 s/120 s 行为不变（D164/D169）。本轮修的是「窗口内/回合运行中已经说出的句子不被吞掉」，不是延长窗口。

## 2. 构建与设备基线

| 项 | 值 |
|---|---|
| 分支 | `feat/0.3-harness` |
| 最终 APK | `phone-app/app/build/outputs/apk/debug/app-debug.apk`，**102,789,648 B**，SHA-256 `A219FF711C29BD0C343E7D353F5C45D80FFED8A25BCCAAFE5C224D1F4B693008` |
| 设备 base.apk | 同大小同哈希（`adb pull` 后复算，`.local/asr-ab/base-final3.apk`） |
| 安装 | `adb -s 2696813b install -r` → `Success`；未卸载设备数据 |
| 设备 | K40 `2696813b`（M2012K11AC / Android 13）；眼镜 `1901092544027662` |
| 最终 engine bundle | `1b613596-0ed2-43ab-af28-b42b79b676e7`；decoder = `decoder-epoch-99-avg-1.onnx`（fp32），SHA-256 `2E3B5EC371F8899EE6ACD829FD753BA45772DF57A91BDF37CDE3136354E7DB7D` |
| bundle 持久化 | 冷启动 `ENGINE_REUSED: RECOVERED:/data/data/com.sheen.rokid.phone/no_backup/engine-models/1b613596-0ed2-43ab-af28-b42b79b676e7`（`EdithHost`，2026-10-22 12:37:18，最终 APK） |

APK 的 `assets/engine-manifest.json`：4,474 B，SHA-256 `1A94586A0F35FE1BEF4941416E547D0D30835D0B416DB5FAD25686BF5D2D2571`；设备 `no_backup/engine-models-import/engine-manifest.json` 同哈希。旧 int8 清单 4,484 B，SHA-256 `F6C8ADCC640D792B253D00BB82353FE294D4428C239722836917C5A37F80C552`。

## 3. ASR 设备 A/B

### 3.1 数据变更

根因与独立设备实验：[2026-10-22-asr-repetition-root-cause-and-fix-plan.md](2026-10-22-asr-repetition-root-cause-and-fix-plan.md)；补充验证（CPU/设备 TTS/系统 ASR）：[2026-10-22-asr-cpu-npu-tts-and-system-asr-validation.md](2026-10-22-asr-cpu-npu-tts-and-system-asr-validation.md)。

`tools/scripts/p2/engine-manifest.json` 的 decoder 项：

| 字段 | int8（旧） | fp32（新） |
|---|---|---|
| path | `decoder-epoch-99-avg-1.int8.onnx` | `decoder-epoch-99-avg-1.onnx` |
| bytes | 13,091,040 | 13,876,452 |
| SHA-256 | `1A70C593…C4FA` | `2E3B5EC3…D7B7D` |
| sourceUrl | 同 revision `98590b7e…` 的 int8 文件 | 同 revision `98590b7e…` 的 fp32 文件 |

encoder/joiner/tokens/vad/speaker、`greedy_search`、6 角色/size/SHA/HTTPS/license 约束全部不变。`tools/scripts/p2/report-engine.tests.ps1` 的 fixture 同步为新 decoder 哈希；`report-engine.tests.ps1` **PASS 10 / FAIL 0**。

### 3.2 固定语料

| WAV | 字节 | SHA-256 | 文本 |
|---|---:|---|---|
| `ab-weather.wav` | 78,606 | `2FA65F781485595DA41086BACC5AFA9656FF1A154CF68EF631072DDC68A7466B` | 今天天气怎么样 |
| `ab-music.wav` | 83,086 | `078C243A814046F7D58BC3A65D3A8131B81D04D2CDF3CC4D8103809377F59DA1` | 播放周杰伦的稻香 |

生成：Windows SAPI `Microsoft Huihui Desktop`，16 kHz / mono / PCM16，语速默认；正文由码点构造，文件 hash 即上表。设备侧通过 `edith.DEBUG_INJECT`（`DebugAudioInjector`）注入 live CXR ingress；会话 live（`P3_CXR_AUDIO` 有回调）时才接受。

### 3.3 A/B 结果（头对头，同一 APK `3C7A65B3…F0A3`，同一 bundle 其余文件）

| 配置 | bundle | `ab-weather.wav` 的 F4 USER 文本 | `ab-music.wav` 的 F4 USER 文本 | `P3_F4ANSWER userChars / userSha` |
|---|---|---|---:|---|
| **int8 decoder** | `9c7c476b-f23a-463d-889a-3981bbf67e9e` | `今天天天气怎么样` | `播放播放播放周周杰伦伦的稻香` | 8 / `9E11589D…D10E3`；14 / `84C8121F…6BAE` |
| **fp32 decoder** | `741230da-7d81-4a30-b5e5-08c45b043461`（首次） | `今天天气怎么样` | `播放周杰伦的稻香` | 7 / `DE688223…AE0D`；8 / `2825D88E…C792` |
| **fp32（最终 bundle）** | `1b613596-0ed2-43ab-af28-b42b79b676e7` | — | `播放周杰伦的稻香` | 8 / `2825D88E…C792` |

最终 APK `A219FF71…3008` 上复跑 `ab-music.wav` 仍为 `播放周杰伦的稻香`（`userChars=8`，`userSha=2825D88E…C792`，`P3_F4ANSWER` 12:38:53），说明最终安装包加载的仍是 fp32 decoder。`P3_ASR` 的 final 计数与 `P3_TURN start` 也正常（例如 `final=true chars=7/8`）。

### 3.4 边界

- 本 A/B 的输入是合成 TTS WAV，经 `DebugAudioInjector` 进入 **live CXR ingress**（不是眼镜麦克风声学）；真实 CXR 人声、不同语速/口音仍未测。
- fp32 decoder 的延迟/内存：采用 2026-10-22 独立报告（RTF 中位 0.070→0.074，+5.7%；bundle +785,412 B）；EDITH 侧本轮未测 RTF P95，记 `NotMeasured`。
- 旧 int8 decoder 与旧 import 目录仍保留在设备上，作为回滚素材；最终 `active` 指向 fp32。
- 合成文本是固定测试语料，不是用户数据；原始 WAV 只在 `.local/asr-ab/`（gitignored）。

## 4. 持续对话：缺陷与修复

### 4.1 设备实测的缺陷（修复前，HEAD `4B2` 构建）

同一组定时文本注入（`edith.DEBUG_UTTERANCE`，`route=wake/transcript`）：

```text
12:08:03.905  P3_WAKE turn=smart-...
12:08:03.907  P3_TRANSCRIPT turn=debug-utterance-1 ... final=true
12:08:04.064  P3_TURN start turn=debug-utterance-1
12:08:08.975  P3_ASR revision=2 final=true chars=4 usable=true   ← 第二句
（之后没有 P3_TRANSCRIPT、没有 P3_TURN start、没有 proactive 行：该句被静默丢弃）
12:08:14.637  P3_CONV continue turn=smart-... from=debug-utterance-1
```

代码位置：`SmartAssistantPlugin.handle(TranscriptEvent)` 的 `wakeOwnsFinal` 守卫（为阻止 Running 回合的 final 被 `Help` 复读或被 parked 成 HINT_TURN 而设计）把可用 final 从 `acceptTranscript` 和 proactive 两个去处都排除了，但没有第三个去处。`P3_ASR` 仍产生，所以不是 CXR/ASR 链路断。

### 4.2 修复

- `SmartAssistantPlugin`：新增单槽、latest-wins、按路由的 `QueuedUtterance`。当可用 final 到达而 WAKE/PROACTIVE 回合处于 `Running`（或答案已发出、continuation 尚未打开的 Idle 间隙，且自身 TTS 未在播放）时入队；`openContinuationTurn` 打开下一回合时消费该句，adopt 它的 ASR turn id、启动模型并发布官方 ASR 行。窗口/路由关闭、STOP、UI_CLOSED、会话结束、新 wake 会清空队列；120 s 是会话级上界（D164/D169 的 120 s 关闭地平线），不是新的监听窗口。
- `SmartTurn`：新增 `answerSpeechFinished`，在 TTS 播放结束事件匹配 emit id 时置位；用于拒绝「麦克风听到助手自己朗读」产生的 final。默认 speech-off 不受影响。
- 主动路线：同一机制（`route=PROACTIVE`），由 follow-up 答案的 continuation 消费；与 WAKE 路由独立（P3-13）。
- 日志新增 count-only：`P3_CONV queue route=… chars=N`、`P3_CONV queued_consumed route=… chars=N`、`P3_CONV queue_drop route=… reason=self_speech|stale`。

### 4.3 修复后的设备读数（最终 APK `A219FF71…3008`）

```text
12:37:55.558  P3_TURN start turn=debug-utterance-1
12:37:59.511  P3_CONV queue route=WAKE chars=4
12:38:08.110  P3_TURN end turn=debug-utterance-1 outcome=COMPLETED steps=2
12:38:08.399  P3_CONV continue turn=debug-utterance-2 from=debug-utterance-1
12:38:08.400  P3_CONV queued_consumed route=WAKE chars=4
12:38:08.421  P3_TURN start turn=debug-utterance-2
```

修复前同一场景的对照见 §4.1（HEAD `4B2` 构建），以及 2026-10-22 12:25（前一修复构建）的 `P3_CONV queue/queued_consumed` 读数。

同轮设备测试还暴露了队列初版 30 s TTL 过短：一次带确认的工具回合持续 53 s，队列在 continuation 打开前被 `queue_drop reason=stale` 丢弃。最终常量改为 120 s（会话关闭路径仍会清空），并由 JVM 套件覆盖；**长确认回合的设备复跑未做**，记 `NotMeasured`。

答案结束到 continuation 打开的 Idle 间隙（设备上观测到约 3 s）由
`RunningUtteranceQueueTest > a final in the idle gap before the continuation is queued too` 覆盖（JVM）；该场景的最终设备复跑未做。

### 4.4 主动路线

- JVM：`ProactiveFollowupTurnTest > a final during the follow-up answer becomes the proactive continuation utterance` 及既有 `ProactiveFollowupWindowTest`（含 `a hidden panel follow-up final starts a proactive turn`、`a usable final while the wake turn is running never enters the proactive session`）全绿。
- 设备：自然 proactive hint/多轮 `NotMeasured`（本轮未构造自然 gate 命中；不把 JVM 当设备通过）。已知风险仍在：隐藏面板的 3 s `panel_not_shown`、`onPanelShown` 的跨路由 `liveTurn` 语义；本轮未改，记录在 STATUS 的残留清单。

## 5. JVM 门禁（最终代码）

11 模块 `--rerun-tasks`：**359 suites / 3,936 tests / 0 failures / 0 errors / 2 skipped**（skips 为既有宿主假设：符号链接、tokenizer 语料）。

逐模块：plugin-api 12/62、host-runtime 19/135、device-control 12/137、agent-runtime 39/533、model-runtime 17/207、output-runtime 16/175、plugin-smart-helper **92/970**、plugin-diagnostic 2/26、app 125/1,424/2 skipped、glasses-app 17/194、eval-trigger 8/73。计数来自 JUnit XML，不用控制台汇总。

本轮新增 JVM 回归：
- `RunningUtteranceQueueTest`：3 tests（Running 期间入队并消费；Idle 间隙入队；latest-wins）；
- `ProactiveFollowupTurnTest > a final during the follow-up answer becomes the proactive continuation utterance`：1 test。

## 6. 文件与命令（可复核）

- 模型清单：`tools/scripts/p2/engine-manifest.json`；fixture：`tools/scripts/p2/report-engine.tests.ps1`。
- 插件：`phone-app/plugins/plugin-smart-helper/src/main/kotlin/com/sheen/rokid/phone/plugin/smarthelper/SmartAssistantPlugin.kt`、`.../turn/SmartTurn.kt`。
- 测试：`phone-app/plugins/plugin-smart-helper/src/test/.../RunningUtteranceQueueTest.kt`、`.../proactive/ProactiveFollowupTurnTest.kt`。
- 构建：`.\gradlew.bat :phone-app:app:assembleDebug`（`JAVA_HOME=D:\Tools\JDK`）。
- 安装：`$env:ANDROID_USER_HOME="D:\Project_new\EDITH\.local\p3\android-home"; adb -s 2696813b install -r phone-app/app/build/outputs/apk/debug/app-debug.apk`。
- provision（本机 → 设备 import 目录）：`adb push` 到 `/data/local/tmp/edith-engine/`，再 `cat | run-as com.sheen.rokid.phone sh -c 'cat > no_backup/engine-models-import/...'`；冷启动 `ENGINE_IMPORTED`，再次冷启动 `ENGINE_REUSED: RECOVERED`。
- ASR 单点：`edith.DEBUG_INJECT` 注入 live ingress + `edith.DEBUG_F4_ANSWER` 取 `f4-user.txt`；连续对话：`edith.DEBUG_UTTERANCE` 的 `route=wake|transcript`。

## 7. 未测 / 不读成通过

- 真实 CXR 人声与真实语速；fp32 decoder 的 RTF P95/内存/长稳；眼镜麦克风声学复读。
- 长确认工具回合下 120 s TTL 的设备复跑；Idle 间隙修复的设备复跑（仅 JVM）。
- 主动路线的设备多轮、自然 hint、隐藏面板 `panel_not_shown`、跨路由 `PANEL_SHOWN`。
- `SpeechPipeline.fault()` 的永久 `paused`（本轮仅在分析中记录，未修）；`HostRuntime` 插件异常静默 `pause()`（未修）；`AudioSegmentCapture.release` 后不可复用（未修）。
- 官方 10 s 监听窗未改；窗口外的说话仍需要再次唤醒（D164/D169）。
