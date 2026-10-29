# 0.4 Task 13d 修复报告：听写尾音隔离 + 跨会话 ASR 失效恢复（K40）

- **日期/时间**：2026-10-27 00:38 – 01:20（Asia/Shanghai）
- **分支 / 源码提交**：`feat/0.4-detached-wake-bundled-models` / **`c3b4272a`**（`fix(0.4): isolate dictation tails and restore ASR across sessions`）。会话开始时 HEAD 为 `4c1def3e`；期间工作线把 `9db5fcb4`（detach 停会话）与 `fd490b97`（composer 重构）提交进同一分支，本修复位于其上，只改 4 个文件（`HostCompositionRoot.kt`、`SpeechPipeline.kt` 及两份测试），**未触碰** `PhoneScreen.kt`/`TypedInput.kt`/`DictationInput.kt`。
- **设备二进制**：`E:\edith-0.4-builds\app-debug-0.4-task13d.apk`，**1,112,155,760 B**，SHA-256 **`9A1F9E38942897A6CBA4BB4288908C80267FBA115EE3DAEC4D9B07F69113615E`**，构建于 2026-10-27 00:54（当时工作树 = `fd490b97` + 本修复，即 `c3b4272a` 的等价内容）。**实测钉住**：APK 的 `classes.dex` 含 composer 重构独有字符串「发消息…」，即设备矩阵跑在 **新 composer + 本修复** 的二进制上。安装：`adb -s 2696813b install -r` → `Success`，`lastUpdateTime=2026-10-27 00:55:28`。
- **设备**：红米 K40 `2696813b`（MIUI / Android 13 / API 33，D46 验收设备）；眼镜 `1901092544027662`（仅 D10 用真实 CXR 链路）。
- **证据边界**：合成 TTS WAV（`question-zh.wav` 4.61 s、`wake-trim-zh-question.wav` 7.1 s）经手机扬声器播放；模型回答由 debug `ScriptedModelWire` 脚本化（**不是真实模型**）；**不是真实人声 / 不计算召回率**。本文件只写状态、计数、字符数、id、时间戳与 SHA；原始 logcat/截图在 `.local/task13d/`（不入 git）。
- **不得读成版本级通过**：Task 13e 的 UI 生命周期矩阵仍需在最终二进制上重跑；本文件不宣布 0.4 最终。

---

## 1. 观察 1（D8 尾音 → typed `busy`）：根因与修复

### 1.1 已钉住的机制（Task 13c 设备日志 + 代码）

Task 13c 原始日志（`.local/task13c/device-run/05-d8-phone-logcat.txt`）：

```text
00:11:36.444  P4_DICTATION state=final chars=14 reason=endpoint
00:11:36.552  P4_WAKE_AUDIO state=done bytes=147200
00:11:41.026  EdithSmart P3_ASR revision=0 final=false chars=4 usable=true
00:11:41.670  EdithSmart P3_ASR revision=1 final=false chars=11 usable=true
00:11:41.981  EdithSmart P3_ASR revision=2 final=false chars=18 usable=true
00:11:42.335  EdithSmart P3_ASR revision=3 final=true chars=18 usable=true
00:11:42.335  EdithSmart P3_CONV queue route=WAKE chars=18
```

- 听写 final 在 VAD 端点产生，**随后宿主立即把唤醒路由恢复**（帧/管线/KWS 都继续工作）。听写之后到达的语音（合成 TTS 的尾音/环境声）在恢复后的路由上形成**新的管线回合**（`P3_ASR revision=0` 从头计数，窗口内没有 `P3_WAKE`/`P3_KWS hit`）。
- 插件把该 final 当成上一答案的**续接句**入队（`P3_CONV queue`，D194 的 `wakeEnding` 分支：回合处于 Idle 且已发过答案）。`queuedUtterance != null` 使 `canAcceptTypedLocked()` 恒为 false → `P4_TYPED refused reason=busy`（用户可见「助手正在处理」）。
- 代码根因：听写 final 只结束了**管线回合**，没有结束「这段音频属于听写」这一**路由事实**；恢复后的唤醒路由继续接受没有 `P3_WAKE` 的语音并把它交给插件。这正是「听写音频不得变成唤醒/回合」（spec §5.3）在尾音上的漏洞。

### 1.2 修复（host 路由闸门）

`CxrAudioChain` 新增**转写路由闸门**（帧、KWS、管线状态都不变，只拦 hand-off）：

- 听写终态（final / timeout / cancel / error）→ `closeTranscriptRoute(reason)`，在 cleanup 恢复唤醒环之前关闭；听写自身 final 走 controller redirect，从不经过闸门。
- **只有显式输入**才重新打开：KWS 唤醒（`wakeEmit`，检测器先于管线拿到同一帧）、typed 发送、`Help`、新会话/新 audio epoch。
- 语义：听写之后到达的任何语音（尾音、环境声）都不会成为 WAKE 回合或续接队列项，直到佩戴者明确再次唤醒/打字/Help。**不是**屏蔽音频，**不是**自动发送。
- 计数遥测（无正文、无 hash）：`P3_TRANSCRIPT_ROUTE state=closed|open reason=…`、`state=drop reason=… partials=N chars=M`。
- fail-closed 顺序：终态回调先关闸再恢复环；KWS 命中在唤醒回合 ASR final 到达前开闸。

## 2. 观察 2（进程内 stop/start 后 ASR 静默）：根因与修复

### 2.1 机制

- `SpeechPipeline.fault()` 把管线置为**终态 `paused`**（R-CD-3 已知未修路径，`ACCEPTANCE` R-CD-3 / `STATUS.md` 明确记录）：`accept()` 直接丢弃所有帧 → 无 VAD、无 ASR 回合；**KWS 是独立检测器/VAD**，所以帧计数与 `P3_KWS` 继续增长；听写表现为 `timeout chars=0`、KWS 命中的唤醒回合没有任何 `P3_ASR`；只有 force-stop 重建进程内的管线才恢复。
- 管线是**进程级**的（一次 ~240 MB 模型加载，D204 禁止造第二套 recognizer/pipeline），而故障后的 `paused` 没有任何恢复入口。Task 13c 的实测序列（`06b-*`/`07-*`）与之一致：一次 stop/start 之后两次听写 `timeout chars=0`、一次 KWS 命中无 `P3_ASR`，force-stop 后恢复。
- 触发这次 `fault()` 的**具体 provider 错误码在 Task 13c 没有记录**（当时 fault/pause 事件只作为 `HostEvent` 传递，没有任何日志）——这是本轮必须补的观测缺口，不是可以猜的结论。

### 2.2 修复

- `SpeechPipeline.rearm()`：清除 `paused`、撤销故障遗留的回合/挂起请求、清 deferred/pre-roll、重置三个引擎；**只在关闭状态下无操作**。恢复边界刻意限定为「**另一个 session/generation 的第一帧**」（`accept` 内自动判定）以及「**新 audio epoch arm**」（composition 显式调用），同一 session 继续拒收（原「pauses and fences subsequent PCM」测试保持不变）。这样单次瞬时故障不会再毒死后续所有会话。
- 遥测：`P3_PIPELINE state=fault reason=<code>`、`state=paused`、`state=rearmed reason=session`（fault 事件在听写 redirect 路径也补记，避免再次静默）。

## 3. JVM 回归（TDD：先红后绿）

| 模块 | 新增测试 | 红（修复前） | 绿（修复后） |
|---|---|---|---|
| audio-runtime `SpeechPipelineTest` | `a provider fault pauses the pipeline and the next session re-arms it` | `AssertionFailedError at SpeechPipelineTest.kt:233`（新 session 首帧未进入 ASR） | PASSED |
| app `DictationWiringTest` | `postDictationSpeechDoesNotReachThePluginUntilAnExplicitInput` | `no dictation-tail transcript may reach the host/plugin: [TranscriptEvent(... text=这是一段听写 …)] expected:<0> but was:<1>` | PASSED |
| app `DictationWiringTest` | `aProviderFaultPausesAudioAndTheNextSessionReArmsIt` | `the new session's frame must reach the shared recognizer` | PASSED |
| app `DictationWiringTest` | `aWakeReopensTheTranscriptRouteADictationClosed`（KWS 开闸回归） | —（新 seam 的回归钉） | PASSED |

全量（同一工作树，`c3b4272a` 内容）：

- `:phone-app:app:testDebugUnitTest`：**146 classes / 1678 tests / 0 failures / 0 errors / 2 skipped**（任务给的历史基线 146/1668/0/0/2；本修复 +3；`fd490b97` 之前的 HEAD 已比该数字多出若干测试）。skip 是 Windows 无符号链接权限的既有 `EngineBundleProvisioningTest` assumption。
- `:phone-app:perception:audio-runtime:test`：**14 classes / 200 tests / 0 failures / 0 errors / 0 skipped**（基线 14/199，本修复 +1）。

## 4. K40 设备验证（新 composer + 修复二进制）

### 4.1 (a) D8 规定顺序：typed 回合 → 听写（答案运行中）→ final → 等 ≥10 s → typed 发送

原始：`.local/task13d/device-run/09-dict-final.txt`、`14-typed-after-dictation.txt`、`18-full-logcat.txt`。

```text
01:09:54.251  P4_TYPED state=accepted turn=typed-a499919c-… chars=2
01:09:57.589  P4_DICTATION state=started chars=0 reason=user
01:10:01.125  P3_TURN end turn=typed-a499919c-… outcome=COMPLETED steps=0      （上一答案 → Idle 回合 + 续接窗口）
01:10:03.368  P4_WAKE_AUDIO state=playing file=question-zh.wav bytes=147200 chunks=230
01:10:08.012  P3_TRANSCRIPT_ROUTE state=closed reason=dictation_end
01:10:08.013  P4_DICTATION state=final chars=14 reason=endpoint
01:10:08.106  P4_WAKE_AUDIO state=done bytes=147200                              （final 后仍有源音频尾音）
… 01:10:08 – 01:10:43 无 P3_CONV queue、无 P3_ASR、无 P3_TURN、无 P3_PIPELINE fault/paused …
01:10:43.295  P3_TRANSCRIPT_ROUTE state=open reason=typed
01:10:43.295  P4_TYPED state=accepted turn=typed-2618e687-… chars=3
01:10:43.387  P3_TURN start turn=typed-2618e687-…
```

- **判词：Verified（合成 TTS / 脚本模型）**。听写 final（chars=14，播放结束后 0.09 s 仍有尾音，正是 13c 的尾音条件）之后 **35 s 内没有任何 `P3_CONV queue` 来自听写音频**；typed 发送在 final 后第一次尝试即 **accepted**（非 `busy`）；闸门按 `dictation_end` 关闭、按 `typed` 打开。
- 另一次干净会话（无前置答案回合）：`01:05:04.866 started` → `01:05:11.856 final chars=16` → final 后 `state=drop reason=dictation_end` ×5（`01:05:14.058`/`19.065`/`21.028`/`23.796`/`27.807`，均 `partials=0 chars=0`）→ `01:05:54.047 P4_TYPED state=accepted chars=3`、同刻 `state=open reason=typed`。即听写后到达的转写**全部被闸门丢弃**、typed 不被拒。
- **环境说明（如实记录）**：01:01–01:06 期间设备环境有持续环境声（区间出现多段 usable final；`01:01:41.762 state=drop partials=4 chars=9`、`01:02:08.626 chars=32` 等）。其中一次尝试的 typed 发送确实收到 `P4_TYPED refused reason=busy`（`01:02:38.061`），但队列来自**听写开始之前**的环境声（`P3_CONV queue` 出现在听写前的 Idle 间隙），不是听写音频；该次不作为修复判词，只记录环境干扰。修复后的判定以 §4.1 的 35 s 空窗 + 一次成功发送为准。

### 4.2 (b) 进程内 stop/start ×2 + 合成唤醒 ASR

原始：`.local/task13d/device-run/15-cycle1.txt`、`15-cycle2.txt`、`18-full-logcat.txt`。

```text
01:06:20.128  P4_AUDIO state=disarmed … reason=DISARMED    （cycle 1 stop）
01:06:22.650  P4_AUDIO state=armed source=phone_mic …     （cycle 1 start）
01:07:45.065  P4_AUDIO state=disarmed … reason=DISARMED    （cycle 2 stop）
01:07:47.517  P4_AUDIO state=armed source=phone_mic …     （cycle 2 start）
01:08:52.628  P3_KWS hit=伊迪丝 tokens=6
01:08:52.630  P3_WAKE word=伊迪丝 confidence=1.0
01:09:08.813  EdithSmart P3_ASR revision=5 final=true chars=15 usable=true
01:09:21.445  EdithSmart P3_CONV said route=WAKE chars=22 final=true
01:09:21.788  EdithSmart P3_TURN start turn=12db4386-…
01:09:27.793  P3_SCRIPT served=text
01:09:28.174  EdithSmart P3_TURN end turn=12db4386-… outcome=COMPLETED code=- steps=0
```

- **判词：Verified（合成声学；两轮 in-process stop/start）**。第二次 stop/start 后 KWS 命中 → `P3_WAKE` → ASR final（`P3_ASR chars=15`）→ 插件 WAKE 回合 `COMPLETED`。整个窗口**没有** `P3_PIPELINE state=fault/paused`，也没有 `state=rearmed`（本 build 该会话未发生 fault；rearm 只在 `paused` 时生效且会打 `state=rearmed reason=session`）。
- **边界（不夸大）**：本轮没有在设备上**复现** `fault()` 触发（13c 的故障码当时未记录，现在 `P3_PIPELINE` 遥测可捕获下一次）。因此「故障后恢复」的**机制**由 JVM 回归证明（audio-runtime `SpeechPipelineTest` + app `DictationWiringTest` 的 stop/start 场景）；设备判词是「stop/start 后唤醒 ASR 正常，且新遥测可命名任何 fault」。

### 4.3 (c) D8 / D10 听写回归（修复未破坏）

- **D8（detached）**：§4.1 两次听写 `final chars=16` / `chars=14`（都来自端点），无 auto-send、无 `P4_TYPED`/`P3_TURN` 在 final 前发生。
- **D10（眼镜模式，真实 CXR）**：原始 `.local/task13d/device-run/16-d10-readback.txt`、`17-d10-send.txt`。

```text
01:10:56.780  P4_DETACH state=off effective_proactive=true audio=cxr
01:10:58.949  P3_CXR connect attempt=start result=ok
01:10:59.498  P4_AUDIO state=armed source=cxr transport=true loop=true
01:11:18.726  P4_SESSION_READBACK session=Ready error=none
01:11:26.078  P4_DICTATION state=started chars=0 reason=user
01:11:32.548  P4_WAKE_AUDIO state=playing file=question-zh.wav bytes=147200 chunks=230
01:11:37.184  P3_TRANSCRIPT_ROUTE state=closed reason=dictation_end
01:11:37.184  P4_DICTATION state=final chars=14 reason=endpoint
01:11:51.748  P3_A1TEXT_VERDICT op=read … len=14 chars=14 sha=974570cb5a2d065b2cb563998bd3e50197e5cb753f5ee27963cef25c71da2053
01:12:00.046  P3_TRANSCRIPT_ROUTE state=open reason=typed
01:12:00.048  P4_TYPED state=accepted turn=typed-d2a58481-… chars=14      （发送的正是听写回填文本）
01:12:06.214  P3_TURN end turn=typed-d2a58481-… outcome=COMPLETED code=- steps=0
01:12:06.386  P3_HINT show hint=hint-57d3acb6-… chars=4
01:12:06.421  P3_HINT ack hint=hint-57d3acb6-… status=Displayed
```

- **判词：Verified（D10 听写 + 回填 + 发送 + 上屏；合成 TTS / 脚本模型）**。readback `len=14` 与 13c 同一 SHA；听写后闸门关闭、typed 发送按 `typed` 重开并 `accepted`；CXR 会话未重启（`P3_CXR connect` 计数不增，`source=cxr` 保持）。

## 5. 设备终态与原始产物

- 终态（`19-terminal.txt`）：`P3_SESSION_CONTROL action=stop`、`P4_AUDIO state=disarmed source=cxr … reason=DISARMED`、`P4_SETTINGS_READBACK glasses_detached=false proactive_stored=true speech=true profile=ddddbb1d-…`、`P4_SESSION_READBACK session=Idle error=none`、媒体音量 `70/150`；无障碍 4 项在测试中因安装被系统清空后按「读→保留→追加→回读→dumpsys」恢复（`edith.phone.a11yprobe` ×3 + `com.sheen.rokid.phone/...EdithAccessibilityService`，`dumpsys accessibility` 4 项 bound、`Crashed services:{}`）。
- 原始（`.local/task13d/`，不入 git）：`build/`（构建日志、APK SHA、安装、a11y 前后、dex 校验）、`device-run/00-*`（冷启动/模式/会话就绪）、`03/05-*`（typed 回合）、`08/09/14-*`（D8 顺序与重试）、`13/15-*`（唤醒/两轮 stop-start）、`16/17-*`（D10）、`18-full-logcat.txt`（全量 logcat）、`19-terminal.txt`、UI dump。

## 6. 关注点 / 残余（不读成通过）

1. **`fault()` 触发的具体 code 仍未在设备上命名**：Task 13c 没留下 fault 日志；本修复补了 `P3_PIPELINE state=fault reason=…` / `state=paused` / `state=rearmed`，下一次复现可直接读故障码。恢复路径本身由 JVM 红→绿回归钉住。`NotMeasured`。
2. **听写后、下一次显式输入前，环境语音转写被闸门丢弃**（含眼镜模式的 ambient/proactive 转写）。这是隔离的刻意代价：听写音频不得变成回合/续接；显式唤醒、typed、`Help` 或新会话立即恢复。若 13e 需要「听写后不唤醒也能续聊」，需产品裁决。
3. **真实人声/召回率/误触发**：`NotMeasured`（合成 TTS + 脚本模型）。
4. **UI 生命周期项（陈旧 typed 失败状态、切页签丢草稿）**：本任务按边界未触碰 UI 文件；归 Task 13e/UI 工作线。
5. 环境声曾在本轮造成一次**听写前**的 `queuedUtterance` → `busy`（§4.1 已记录，不归因于听写尾音）。该现象是 D194 续接队列在「Idle 间隙入队、没有后续 continuation 消费」时的既有行为，本轮不把它写成修复判词，也不声称已修。
