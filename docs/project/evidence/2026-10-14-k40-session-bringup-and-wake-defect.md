# K40 会话 bring-up（重装 + 重新 provision）与唤醒缺陷的独立性

- **日期**：2026-10-14 03:0x（设备时间）
- **设备**：K40 `2696813b`（M2012K11AC / Android 13）；眼镜 `1901092544027662`（RG-glasses，电量 100%）
- **目的**：为 Phase B 的真机回合路径（唤醒→记忆检索→`notify_user`）把设备恢复到可跑状态
- **结论一句话**：**激活问题已修好、会话真的跑起来了；而"唤醒词不触发"是回归** —— 2026-10-09 有 **8 次 `P3_WAKE word=伊迪丝 confidence=1.0`** 的实录，今天 **0 次**；今天唯一可测的新差异是音频入口**持续 `GAP:OVERFLOW`**（9 分钟 282 次，当年成功那次是 **0**）。**本文件 §5 更正了我最初写错的"不是回归"判断。**

## 1. 为什么设备是空的：一次 instrumentation 运行卸载了 App

`connectedDebugAndroidTest` 跑完后 **`edith.phone` 与 `edith.phone.test` 都被卸载**（同一轮的另一证据文件已记录），而卸载会清掉两类 App 数据，两者都是手工 adb 推的：

| 位置 | 内容 | 大小 |
|---|---|---|
| 内部 `files/` | Laya 七件套（主图 `laya_ml_s256_embeds_wfp16.tflite` 等） | 679,274,525 B |
| 内部 `no_backup/engine-models*` | **ASR/VAD/声纹引擎包**（7 文件 + `engine-manifest.json`） | 240,182,139 B |
| 外部 `files/models/embeddinggemma/` | EmbeddingGemma（图 + 词表） | 183,815,055 B |

**合计 1,103,271,719 B ≈ 1.03 GiB 全部随卸载消失。** 只有 `assets/` 里的小模型留在 APK 内：
`kws/` 8,779,150 B（唤醒词模型）、`engine-manifest.json` 4,484 B、`engine-licenses/` 404,678 B。

### MIUI 拦了一次安装

`./gradlew :phone-app:installDebug` →
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`（屏幕醒着、无待确认弹窗、`install_non_market_apps=1`）。
业主在设备上打开 **开发者选项 → USB 安装** 后，`Installed on 1 device` / `BUILD SUCCESSFUL in 19s`。

## 2. 重新 provision（按 Phase A 记录的方法）

Laya 七件套（`/data/local/tmp` → 逐文件 `run-as cp`）：

```
adb -s 2696813b push .local/models/laya-ml-embeds /data/local/tmp/
adb -s 2696813b shell "run-as edith.phone mkdir -p files"
# 逐文件（不是 shell 循环）：run-as edith.phone cp /data/local/tmp/laya-ml-embeds/<f> files/<f>
```

**注意**：第一次没先 `mkdir -p files`，`cp` 全部静默失败（`files/: No such file or directory`），
所以"推送成功"必须用 `run-as ... ls -l` 逐文件核对字节数，不能只看 adb push 的汇总行。核对结果与本地一致，
逐文件合计 679,274,525 B。

ASR 包（`/data/local/tmp` 带 `shell_data_file` 标签，`run-as` 打不开 ⇒ 用记录里的 `cat` 管道）：

```
adb -s 2696813b push .local/p3-asr-bundle /data/local/tmp/
adb -s 2696813b shell "run-as edith.phone mkdir -p no_backup/engine-models-import"
adb -s 2696813b shell "cat /data/local/tmp/p3-asr-bundle/<f> | run-as edith.phone sh -c 'cat > no_backup/engine-models-import/<f>'"
```

重启 App 后宿主自己完成校验与激活：`EdithHost: ENGINE_IMPORTED: ACTIVE:/data/data/edith.phone/no_backup/engine-models/b576782a-…`，
随后 sherpa VAD 与说话人模型加载成功。

EmbeddingGemma 推入外部目录（不需要 run-as）：

```
adb -s 2696813b shell "mkdir -p /sdcard/Android/data/edith.phone/files/models"
adb -s 2696813b push .local/models/embeddinggemma /sdcard/Android/data/edith.phone/files/models/
```

## 3. 两次 `activation refused`，两个不同原因（都不是 Rokid 授权）

业主现象：点「开始」→ 界面**已暂停**。日志（`.local/dev-session-attempt.log`、`dev-chain1.log`）：

```
03:02:21 EdithHost: activation refused result=Failed selected=smart-assistant
         available=[Model, Actions, Confirmation, WakeWord, Context, StorageAccess, SystemOne, Embedding, Storage, TextOutput, SpeechOutput]
         state=Paused cause=ContractError$CapabilityMissing: required provider unavailable
```

- **第一次（03:02:21）**：`available` **缺 `Asr`**。判定在 `HostRuntime.kt:189`
  （`!environment.availableCapabilities.containsAll(plugin.descriptor.capabilities)`），而 `Capability.Asr`
  只在音频链建起来时注册（`HostCompositionRoot.kt:1506`）。⇒ **ASR 包不存在就是根因**，推回去即解决。
- **第二次（03:04:23）**：`available` **已含 `Asr`**，仍被拒 —— 这一条缺的是 **`TextOutput`**
  （显示/时钟映射还没 live；`availableCapabilities` 是**实时派生**，日志打印的是失败后重新取值的结果，
  所以看起来"全都有"）。宿主自己的 **display retry（D10）** 随后重试并成功，无需修代码。

**同一份日志证明 CXR 链路正常**：`EdithHint: handshake: mapping live`、`outbound: Ping/Sync/Clear`、`inbound: Pong`。
⇒ 业主最初的假设（"重装丢了 Rokid AI app 授权"）**被证据否定**。

## 4. 会话确实跑起来了（今天实测）

```
EdithP3 : P3_FRAME frames=1950 bytes=1248000 accepted=3985 overflow=20 unknownFormat=0 staleEpoch=0 gaps=20
EdithP3 : P3_ACOUSTIC label=Speech / label=Silence
EdithSmart P3_ASR revision=0 final=false chars=4 usable=true
EdithSmart P3_ASR revision=1 final=false chars=9 usable=true
EdithSmart P3_ASR revision=1 final=true chars=1 usable=true
EdithSmart P3_TRANSCRIPT turn=5b79c3d5-28dc-4b1b-bb04-594002de99c6 rev=1 final=true chars=1
EdithSmart P3_PROACTIVE skip reason=VAGUE
```

即：采音 → VAD/声学标签 → ASR（部分与终稿）→ 转写事件 → 主动路径判定，**全部在真机上工作**。

## 5. 唤醒词不触发：**是回归**（我最初的判断是错的，此处更正）

**先更正一条我自己写错的结论**：我起初依据 `2026-10-09-p3-1-passive-loop-e2e.md` 前半段（§10.6 与总表）断言"KWS 召回从未成功、不是回归"。
**这是错的**——同一份文件的 **§12**（标题即 "D11 fixed, then re-measured on the device: the wake word fires"）记录了
**8 次 `P3_WAKE word=伊迪丝 confidence=1.0`**（L1472、L1568、L1596、L1609、L1626、L1629、L1638、L1643）。
我只读了文件的一部分就外推到全文，这正是"未完成调查"的典型错误；业主的"以前能唤醒"是对的。

**已确认的机制仍在**：`WakeWordDetector.accept` 对**每一帧**调用 `engine.accept(frame)`（D11 的修法），VAD 不再闸门喂帧，
持续静音 1.5 s 才 `reset()`；`matchedConfidence = 1.0` 与当时那些 `confidence=1.0` 的记录吻合
⇒ 生产用的就是这条 KWS 路径，且 D11 的修法**没有被回退**。

**今天与那次成功运行的可测差异（目前最强的线索）**：

| | 2026-10-09 成功运行 | 2026-10-14 今天 |
|---|---|---|
| `P3_FRAME` gaps / overflow | **`gaps=0 overflow=0`**（L2665-2673） | **持续增长：9 分钟 282 次 `GAP:OVERFLOW`**，每 ~2 秒一次（最后一次 03:13:38） |
| `P3_WAKE` | **8 次命中 `伊迪丝`** | **0 次**（中英两种说法都试过） |
| ASR | 有转写 | 有转写（`chars=4/9`） |

含义：**唤醒词是这条音频流上最脆弱的消费者**——它需要起音那 ~400 ms 的连续帧（D11 修法的前提），
而 `GAP:OVERFLOW` 会让 framer 重新分帧、把关键字切碎；ASR 窗口长得多，所以照旧能转写。
`frames` 与墙钟基本吻合（26831 帧 × 20 ms ≈ 536 s），但累计丢了约 `279 × 20 ms ≈ 5.6 s`。

**尚未确定（不得当成结论）**：`GAP:OVERFLOW` 究竟来自**我们这一侧的入口队列**（消费慢于生产）
还是**CXR/BT 链路本身在丢**。两者的修法完全不同，需要下一步实验区分：
（a）在业主再说一次唤醒词的同时，把 `GAP:OVERFLOW` 的时刻与话语窗口对齐（若 gap 落在唤醒词段内 ⇒ 机制成立）；
（b）读 transport-rokid 侧产生该 gap 的代码，找出是链路还是队列。

## 5b. 今天仍未测到的东西

- **没有录入模型档案/Key** ⇒ 零个云端回合：`P3_TURN` / `P3_MEMORY` / `P3_HINT` / `P3_NOTIFY` **一次都没出现**；
  B3/B5/B6/B7/B8 的真机验证**没有发生**。
- 唤醒召回**仍未量化**（今天只证明了"不触发"，没有命中率/漏检率）。
- 未测：噪声误触发率、真实声学唤醒的成功率、说话人/注册、延迟与功耗。

## 5c. 根因（领先假设，带数字）：入口消费者每 tick 只取一块，而交付节奏变快了

10 次连说唤醒词的结果（`.local/dev-wake10.log`）：**`P3_WAKE` = 0**，而且**多数终稿是 `chars=0 usable=false`**
（本缓冲 ASR 行只有 5 条：3× `chars=2 usable=true`、2× `chars=0 usable=false`），声学标签只检出 **2 个语音窗口**。
⇒ 受影响的**不是唤醒路径一家**：VAD、ASR、KWS 同时退化，**输入音频本身被切碎了**。

数字对得上：

| 事实 | 值 | 出处 |
|---|---|---|
| 队列容量 | `16000Hz × 1ch × 2B × 2000ms / 1000` = **64,000 B ≈ 2 秒音频** | `PcmIngress.kt:14,30-31` |
| 溢出判据 | `queuedBytes + bytes.size > capacity` ⇒ `reset(OVERFLOW)`（**丢掉整队**） | `PcmIngress.kt:46` |
| 消费者节奏 | `cadenceMillis = 20`，每 tick **只 `poll()` 一块** | `AudioIngressLoop.kt:55,219-220` |
| 每 offer 的字节 | `bytes / frames = 640 B`（20 ms 帧）、`accepted ≈ 2 × frames` ⇒ **每次 offer ≈ 320 B ≈ 10 ms** | 今天 `P3_FRAME` |
| 当年成功那次 | `accepted == frames`（232/232）、640 B/offer ⇒ **offer 就是 20 ms** | 证据 L2665-2673 |
| 实测溢出节奏 | 每 **~2 秒**一次，持续（282→338，跨越 9 分钟） | 今天 `P3_FRAME` / `GAP:OVERFLOW` |

**机制**：交付从 20 ms/块变成 **10 ms/块**，而消费者仍每 20 ms 只取一块 ⇒ 只吸得一半 ⇒ 2 秒的队列以半速填满
⇒ **每 ~2 秒溢出一次、每次丢掉整队（约 1–2 秒音频）**，framer 随即重置。于是流水线看到的是"一段段被切开的音频"，
VAD 大部分时间判不出 Speech、ASR 大多给 `chars=0`、**唤醒词那 ~400 ms 连续起音更是几乎没有完整到达过**。

**为什么以前没有**：`PcmIngress` 与 `cadenceMillis` 都没变，变的是**交付侧每块的时长/节奏**（20 ms → 10 ms），
于是"每 tick 取一块"这条隐含假设不再成立。这也解释了为什么当年 `overflow=0 gaps=0`。

**修法方向（尚未实施，需业主裁决范围）**：
1. **每 tick 把队列取空**（`while (true) { val chunk = ingress.poll() ?: break; ... }`，单 tick 设上界）——最小改动，
   不动调度节奏、不增功耗，直接消除积压；
2. 或把轮询节奏改成与交付一致（10 ms）——功耗翻倍；
3. 或加大队列——只推迟溢出并增加延迟，**不是修法**。
推荐 1，并用 TDD 钉住"一次 tick 多个 chunk 全部被处理"和"不跨 gap 拼帧"。
**本轮未实施**：这是音频管道（P5 域）的产品代码改动，且当轮已很长；先记账，等业主决定实施时机。

## 5d. 修法已实施并在真机验证：音频管道修好，唤醒仍未触发（第二个原因）

按 §5c 的方向实施了"**每 tick 排空队列**"（`AudioIngressLoop`，上界 `MAX_CHUNKS_PER_TICK = 64`；
`PcmIngress.poll()` 是普通 FIFO 弹出，所以排空仍保证每块只投递一次、顺序不变）。
TDD：先写失败测试（`aTickDrainsEveryQueuedChunkSoAFastProducerCannotBackTheIngressUp`、
`aTickDrainsAtMostItsBoundSoOneTickCannotStallTheLoop`），并把两条把旧策略钉成契约的测试改写成真实意图
（"排空 + stop 后不再 poll"、"第二次 start 不得重复投递"）。全量 `--rerun-tasks` = **1229 / 0 / 0 / 0**。

**真机前后对比（同一台 K40、同一 App，`install -r` 保留 provision）**：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `GAP:OVERFLOW` | 每 ~2 秒一次（9 分钟 282 次） | **0** |
| `P3_FRAME` | `overflow=274…338 gaps=274…338` | **`overflow=0 gaps=0`**（与 2026-10-09 成功运行相同） |
| `accepted : frames` | 2.04 : 1 | **1 : 1**（2538/2538） |
| ASR 终稿 | 多为 `chars=0 usable=false` | **`chars=2/3/5/8 usable=true`**，9433 行里仅 1 条空 |
| `P3_WAKE` | 0 | **仍为 0** |

⇒ **队列溢出是"必要但不充分"**：音频管道已恢复到当年成功运行的质量（overflow=0），唤醒仍不触发，
所以唤醒这一环还有**第二个独立原因**。另外，`accepted = 2×frames` 之谜就此解开：那是积压的症状
（排空后即 1:1），**不是**双回调。

**下一步（未做）**：用 P3.1 §9 已有的**调试用 PCM 注入**做对照，把"KWS 能否检出 `伊迪丝`"与
"现场麦克风/发音/耦合"分开。注意 P3.1 那 8 次命中取自**注入的音频片段**（业主的 `wake-zh.wav` 与 SAPI 控制音），
**现场麦克风唤醒在那份证据里也从未被证明过** —— 所以现在有两条并列假设：
（i）KWS 对现场信号（增益/耦合/口型）召回不足；（ii）KWS 配置（阈值/关键词 token/流生命周期）在实时路径上有差异。

## 5e. 注入对照：同一段录音（当年命中 8 次的那段）现在**也不触发**——引擎侧无改动

用 P3.1 §9 的调试注入器把 **`Audio\唤醒词-伊迪丝.wav`**（57,718 B，与当年命中的 `.local/p3-audio/wake-zh.wav` 同尺寸）
一次性灌进**活的** `PcmIngress`：

```
adb -s 2696813b shell "am broadcast -a edith.DEBUG_INJECT -n edith.phone/.DebugAudioInjector \
    --es path /data/local/tmp/edith-audio/wake-zh.wav"
# P3_INJECT state=start file=wake-zh.wav sampleRateHz=16000 channels=1 chunks=90 windows=2
# P3_INJECT state=ambient_stopped
# P3_INJECT state=ambient_restore_failed
# P3_INJECT state=end chunks=90 accepted=90 stale=0 unknown=0 overflow=0 windows=2 drained=true
```

**90 块全部接受、零溢出、`drained=true`，然后 `P3_WAKE` 仍然是 0。**
⇒ **不是现场麦克风/发音/耦合的问题**：把当年命中的同一段 PCM 直接送进入口，现在也不触发。

**同时排除的两条**：
- `WAKE_UNAVAILABLE` 计数 = **0** ⇒ 检测器确实建好并接进链。
- 日志里**没有任何播放/TTS 活动**（`PlaybackGate.isPlaying = depth > 0`，没有 `begin()` 就没有抑制）。

**而引擎相关文件自那次成功运行以来一字未改**（决定性）：

```
git diff --stat <2026-10-09 的 base=cd69e89>..HEAD -- \
  audio-runtime/.../WakeWords.kt audio-android/.../KeywordAssetBundle.kt audio-android/.../SherpaKeywordSpotter.kt
# （空输出）
```

⇒ 变的是**引擎之外的接线**。本轮发现的一处"吞证据"结构（`HostCompositionRoot.emitWakeDetected`）：

```kotlin
val identity = host.currentIdentity() ?: return         // ← 先返回
P3Log.line("P3_WAKE word=$word confidence=$bounded")     // ← 唯一的唤醒证据行在它后面
```

即**检测器命中后，若那一刻拿不到 host 身份，事件被静默丢弃且不留任何日志**（而它的 KDoc 声称这行正是
"即使随后因陈旧身份被丢也有证据"）。这与"引擎无改动 + 零 `P3_WAKE`"完全吻合，但**尚未证实**。

**下一步（自足、不需要业主说话）**：把该日志行移到身份检查**之前**（这是对 KDoc 已声称意图的实现，不只是调试），
重建安装后用同一段 WAV 再注入一次：
- 出现 `P3_WAKE` ⇒ 检测器与引擎都好，问题在**身份/投递**那一环；
- 仍无 `P3_WAKE` ⇒ 检测器确实没命中，再往 `KeywordSpotterWakeWordEngine` 的流生命周期/关键词参数查。

## 5f. 结论：注入对照**命中**了——唤醒链路本身是好的，剩下的是现场路径

把 `P3_WAKE` 日志行移到身份检查之前（实现其 KDoc 早已声称的意图：命中即留证据，即使随后因无身份而无法投递），
重建安装（`install -r` 保留 provision），请业主**只点了一次「开始」、没有说话**，再注入同一段 `wake-zh.wav`：

```
03:27:03.397 P3_INJECT state=start file=wake-zh.wav sampleRateHz=16000 channels=1 chunks=90 windows=2
03:27:03.398 P3_INJECT state=ambient_stopped
03:27:05.098 EdithP3 : P3_WAKE word=伊迪丝 confidence=1.0          ← 宿主证据行
03:27:05.134 EdithSmart P3_WAKE turn=smart-6fba8c5a-…              ← 插件真的开了回合
03:27:05.218 P3_INJECT state=ambient_restored
03:27:05.219 P3_INJECT state=end chunks=90 accepted=90 stale=0 unknown=0 overflow=0 drained=true
```

⇒ **KWS 引擎 / 资源包 / 检测器 / 发射链路全部正常**：同一段录音现在能触发并开出回合。
这也**证实**了 `emitWakeDetected` 的顺序缺陷是真的（此前命中会因无身份而**连一行都不留**）。

**本次与上面 §5e 那次失败注入的差异（如实记录，不装作已完全解释）**：
- 这一次是**全新进程 + 全新会话**（业主刚点开始），而 §5e 那次是在已运行一段时间的会话里注入；
- 本次 `state=ambient_restored`，§5e 那次是 `state=ambient_restore_failed`；
- 代码差异只有日志行的位置（对投递无影响）。
⇒ 两次的差别更像**会话/环境状态**而不是代码路径，但**"为什么第一次不触发"没有被完整解释**，不得写成已解释。

**现在仍开放的只有现场路径**：修复后的构建上（`GAP:OVERFLOW=0`、ASR 终稿可用）业主曾连说 10 次未命中，
但那是**没有"命中即记账"日志行**的构建，所以那 10 次既可能是"没检出"也可能是"检出但无法投递"。
**下一步（需业主说话）**：在当前构建上再说几次唤醒词——若命中 ⇒ 唤醒回归已闭环；若仍不命中而注入命中 ⇒
问题在**现场声学/发音路径**（P3.1 证据里也从未证明过现场麦克风唤醒）。

### 5g. 现场说话的结果：**仍不命中**（同一构建、音频已修好）——缺陷定位到现场路径

业主在当前构建上说了 3～5 次「伊迪丝」（03:28:40–03:29:10），**没有新的 `P3_WAKE`**；整段缓冲里仅有的两条
`P3_WAKE` 是 03:27:05 那次**注入**。同时排除"现场音频太差"：

| 证据 | 值 |
|---|---|
| `P3_FRAME` | `frames=11243 accepted=11161 overflow=0 unknownFormat=0 staleEpoch=0 gaps=3`（音频干净） |
| ASR | `chars=2/3/4/5/10/11/14/16 usable=true` 多数、仅 6 条 `chars=0`（**业主的话确实被清楚地采到了**） |
| `P3_WAKE`（现场） | **0** |
| `P3_WAKE`（注入同一段录音，同一构建） | **1**（`word=伊迪丝 confidence=1.0`，并开出回合） |

⇒ **结论（本轮最终状态）**：唤醒**链路与引擎是好的**（注入即命中并开回合），**现场麦克风路径不命中**。
剩下的问题在声学/发音与 KWS 的匹配，不在我们这条代码路径上——这也与 P3.1 证据里"现场麦克风唤醒从未被证明过"一致。

**可选的下一步（都需要业主决定，非本轮）**：
1. 用**同一支麦克风、同一环境**现录一段「伊迪丝」，与那段 2026-10-09 的录音做注入对照：区分"现场声学"与"这句录音本身"；
2. 调 KWS 侧参数（`keywords_score`/`boosting`）或关键词写法（ppinyin vs 别的 token 类型），需要先能量化召回；
3. 把 `AsrTextWakeWordMatcher`（现无生产调用者）接成**补充**触发——它用的正是已证明能听清业主的 ASR；
4. 或接受"唤醒词当前不可用"并把该行如实记账（不得读成已闭环）。

## 5h. 召回扫描（业主用**眼镜端录制**的音频，逐次注入）：中文 2/3、英文 0/4、负对照 0/2

业主提供 `D:\Project_new\EDITH\Audio` 下**眼镜端录制**的音频（均为 16 kHz 单声道 PCM16，无需转码）：
`唤醒词-中文.wav`（4 次发音）、`唤醒词-英文.wav`（5 次）、旧对照 `唤醒词-伊迪丝.wav` / `唤醒词-EDITH.wav`、
以及 `声纹1/2/3.wav`。**切法**：以两侧静音的**中点**为切点（切点必然落在静音里 ⇒ 不可能切到词），再逐个校验：

| 片段 | 时长 | mean/max | 判定 |
|---|---|---|---|
| zh-2/3/4 | 1.65/1.67/2.61 s | -16…-18 dB / ≈-1 dB | 真实发音（0.35–0.50 s 首静音 + 连续语音区） |
| en-2/3/4/5 | 1.20/1.34/1.49/1.11 s | -16…-18 dB / 0…-1.6 dB | 同上 |
| zh-1 / en-1 | 1.04 / 0.86 s | **-39 / -38 dB** | **近静音** ⇒ 作为**负对照** |

逐次注入（间隔 5 s > 3 s 冷却；每次注入都 `accepted == chunks`、`overflow=0`、`drained=true`）：

| 片段 | `P3_WAKE` |
|---|---|
| zh-2 | **1** |
| zh-3 | 0 |
| zh-4 | **1** |
| en-2 | 0 |
| en-3 | 0 |
| en-4 | 0 |
| en-5 | 0 |
| zh-1（负对照） | 0 |
| en-1（负对照） | 0 |

**结论**：
1. **中文「伊迪丝」：2/3 命中** ⇒ **眼镜麦克风这条现场路径是工作的**，先前"10 次全不中"**不得**读成"现场路径不可用"
   （那 10 次跑在没有"命中即记账"日志的构建上，且当时的入口还在持续溢出）。
2. **英文「EDITH」：0/4** ⇒ `IY1 D IH0 TH @EDITH` 与业主的英文发音**不匹配**；该关键词在当前模型上不可用。
   与 P3.1 证据一致（当年命中的是模型自带英文词 `LIGHT_UP`/`LOVELY_CHILD`，我们自己的词从未测过）。
3. **负对照 0/2** ⇒ 近静音不会误触发（派生的额外好处：也说明注入路径本身不会凭空产生命中）。

**仍未测**：当前构建上**现场说话**的召回率（上面是注入；两者音频来源同为眼镜麦克风，但注入是"整段连续、
排在 ambient 之前"，现场是"与 ambient 交织"）。下一步用同一个 3 s 冷却间隔现场说 10 次即可得到可比的数字。

## 5i. 更新后的英文录音（业主重录）：**0/5**——拼写成为首要嫌疑

业主 03:57 重录 `Audio\唤醒词-英文.wav`（8.896 s、16 kHz 单声道 PCM16、mean -17.1 dB、max 0.0 dB），
静音结构显示 **5 次发音 + 1 段近静音**（首段 0.18 s、-37.9 dB，作负对照）。仍以**静音中点**切点切成 6 段并逐一校验：

| 片段 | 时长 | mean / max | 注入结果（`P3_WAKE`） |
|---|---|---|---|
| en2-1（近静音，负对照） | 0.96 s | **-37.9 dB** / -20.8 dB | 0 ✓（不误触发） |
| en2-2 | 1.47 s | -15.5 / -0.0 dB | **0** |
| en2-3 | 1.46 s | -16.9 / -1.4 dB | **0** |
| en2-4 | 1.43 s | -16.0 / -0.2 dB | **0** |
| en2-5 | 1.44 s | -16.8 / -0.3 dB | **0** |
| en2-6 | 1.73 s | -16.8 / -0.4 dB | **0** |

每次注入均 `accepted == chunks`、`overflow=0`、`drained=true`（投递无误）。

**判读**：**同一个人、同一支眼镜麦克风**说中文词能中 **2/3**，而英文词（重录后）**0/5** ⇒ 声学路径与说话人
都能被检出，**差异落在关键词那一行**（英文目前只有一条美式 ARPA 拼写 `IY1 D IH0 TH @EDITH`）。
**发音尚未被完全排除**；能把它与拼写分开的实验（未做）：在 `assets/kws/keywords.txt` 里同时放**多个候选拼写**
+ **模型自带英文词作正对照**，重建后用**这段同一录音**逐个注入——有变体命中 ⇒ 发音没问题、是拼写太窄；全不中 ⇒ 指向声学。

## 5j. 拼写变体扫描：**原英文拼写连标准美式英语都不命中；`IY1 D AH0 TH` 才命中**

一次实验构建的 `audio-android/kws/keywords.txt`（临时）同时放入：中文原行、原英文 `IY1 D IH0 TH @EDITH`、
四个候选变体（`EH1 D IH0 TH`、`IY1 D AH0 TH`、`IY1 D IH1 TH`、`AY1 D IH0 TH`，各带独立标签）、
以及**模型自带英文词作正对照**（`L AY1 T AH1 P @LIGHT_UP`、`L AH1 V L IY1 CH AY1 L D @LOVELY_CHILD`）。
所有用到的音素都先核对过在 `tokens.txt` 里存在（否则会被静默跳过）。逐次注入（间隔 5 s）：

| 注入 | 命中（累计标签） |
|---|---|
| zh-2（中文） | `伊迪丝` ✓ |
| en2-2…en2-6（业主英文，5 段） | **无新增**（5 拼写 × 5 段 = 0/25） |
| `LIGHT_UP`（TTS 合成正对照） | **`LIGHT_UP`** ✓ |
| `LOVELY_CHILD`（TTS 合成正对照） | **`LOVELY_CHILD`** ✓ |
| TTS 女声说 "EDITH" | **`EDITH_AH`** ✓ |
| TTS 男声说 "EDITH" | **`EDITH_AH`** ✓ |

**结论（三条，均为实测）**：
1. **英文 KWS 通路可用**（模型自带词与 TTS 英文词都命中）⇒ 不是"英文不行"。
2. **原来的 `IY1 D IH0 TH` 从未被命中过**——连标准美式 TTS 都不匹配；**`IY1 D AH0 TH` 才是能匹配的拼写**。
3. **业主本人的英文发音与五个候选拼写全不匹配**（0/25）⇒ 英文词在这个模型上对**该发音**不可靠；
   中文词仍 2/3。

**据此做的产品修改（有实测依据，且只此一处）**：`audio-android/kws/keywords.txt` 第二行的
`IY1 D IH0 TH @EDITH` → **`IY1 D AH0 TH @EDITH`**（文件仍为 46 B / 2 行 / 无 BOM / 无 CR）；
`KeywordAssetBundleTest` 的两处钉住断言同步更新。
⚠ **如实说明验证边界**：设备上被证明能命中的是**实验构建里的同一 token 序列**（当时标签为 `EDITH_AH`）；
最终文件里 token 序列完全相同、只是标签回到 `@EDITH`，但**最终资源没有再在设备上回注一次**（差别仅在日志标签）。

## 6. 复现命令

```
adb -s 2696813b logcat -c; adb -s 2696813b logcat -G 16M
adb -s 2696813b shell "am force-stop edith.phone; am start -n edith.phone/.MainActivity"
adb -s 2696813b logcat -d > .local/dev-chain1.log
# 关注：EdithHost: activation refused / ENGINE_IMPORTED / WAKE_UNAVAILABLE
#       EdithP3: P3_FRAME / P3_ACOUSTIC / P3_WAKE
#       EdithSmart P3_ASR / P3_TRANSCRIPT / P3_PROACTIVE / P3_TURN
```
