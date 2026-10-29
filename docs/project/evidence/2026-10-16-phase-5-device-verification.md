# Phase 5 真机验证证据（2026-10-16，K40）

**父任务**：为 EDITH 0.21 Phase 5 补齐**设备证据**（该相位此前 JVM 全绿、无任何真机读数）。
**设备**：红米 K40 `2696813b`（`M2012K11AC`，Android 13 / API 33，包 `edith.phone`）。眼镜 `1901092544027662` **本轮未触碰**。
**分支 / HEAD**：`feat/0.21-agentloop-context-redesign` @ `d4b54d5`（工作树有他线未提交改动，见 §5）。
**本文件只记计数、大小、布尔、标志与代号**；不含命令输出、正文、凭证，API Key 全程未被读取、未被打印。

---

## 结论（每项一行）

- **Task A — Measured**：真实回合走完 store 路径并**完成**（`P3_TURN end … outcome=COMPLETED`），store 计数第 1 回合 `store=0`、第 2 回合 `store=2`，真实网络请求 `P3_HTTP method=POST status=200`，`P3_CONTEXT trim route=WAKE droppedTurns=0 compressionOwed=false`。
- **Task B — Measured，但相位期望的结果没有发生**：真实 Binder 上 **8 MiB 上限根本没被触到**，`truncated=true` **一次都没观测到**；回复在 **250 000 B 成功 / 300 000 B 失败**之间断掉，失败一律回落为 `exit=-1 code=REPLY_INVALID`（**不是** `SHELL_REPLY_TOO_LARGE`）；`logcat -d`（设备侧 41 427 809 B）**2 050 ms 内返回**，无挂起、无死锁、无进程死亡。

---

## 0. 环境与安装

| 项 | 值 |
|---|---|
| 首次安装 APK sha256 | `CCCF372E1BABD29B9D21A7A06858827AEB69AEFF1E48B8CDFB2A9BE3224F519B` |
| 加探针后 APK sha256 | `045D17EDEAF667C88AF616FC322D71563C699DEBB38439AD9DF936ECEBD783E6` |
| 安装方式 | **两次都是纯 `adb -s 2696813b install -r`**（带 `$env:ANDROID_USER_HOME="D:\Project_new\EDITH\.local\p3\android-home"`），见 §5 |
| 无障碍探针服务 | 开始时已启用 `edith.phone.a11yprobe/.A11ySurveyNoIdsService:.A11ySurveyService:.A11yProbeService`，**本轮未改该设置** |
| Shizuku 通道 | `P3_EXEC channel=shizuku available=true`（两次会话均观测到） |

**构建 / 安装（两次）**：

```powershell
$env:JAVA_HOME="D:\Tools\JDK"; .\gradlew.bat :phone-app:assembleDebug --console=plain
$env:ANDROID_USER_HOME="D:\Project_new\EDITH\.local\p3\android-home"
adb -s 2696813b install -r phone-app\build\outputs\apk\debug\phone-app-debug.apk
```

两次均为 `Performing Streamed Install` / `Success`。**从未** `uninstall`、**从未** `connectedDebugAndroidTest`、**从未** `gradlew installDebug`。

---

## 1. Task A：Phase 5 store 路径的真实回合

### 1.1 实际执行的命令

```powershell
# 冷启动（清进程，取全新组合）
adb -s 2696813b shell am force-stop edith.phone
adb -s 2696813b shell am start -n edith.phone/.MainActivity
Start-Sleep -Seconds 8
# 「菜单」页签（无人值守，走无障碍探针；禁 input tap）
adb -s 2696813b shell "am start --activity-clear-top -n edith.phone.a11yprobe/.A11yClickActivity --es labels '菜单' --ez confirm true --el waitMillis 20000"
Start-Sleep -Milliseconds 1500
adb -s 2696813b shell am start -n edith.phone/.MainActivity
Start-Sleep -Seconds 6
# 「开始」＝用户同意动作（D53）
adb -s 2696813b shell "am start --activity-clear-top -n edith.phone.a11yprobe/.A11yClickActivity --es labels '开始' --ez confirm true --el waitMillis 25000"
Start-Sleep -Milliseconds 1500
adb -s 2696813b shell am start -n edith.phone/.MainActivity
Start-Sleep -Seconds 8
# 回合 1：唤醒 + 3 s 后请求（= 恰好一个回合）
adb -s 2696813b shell "am broadcast -a edith.DEBUG_INJECT -n edith.phone/.DebugAudioInjector --es path /data/local/tmp/edith-audio/wake-zh.wav"
Start-Sleep -Seconds 3
adb -s 2696813b shell "am broadcast -a edith.DEBUG_INJECT -n edith.phone/.DebugAudioInjector --es path /data/local/tmp/edith-audio/request-note.wav"
Start-Sleep -Seconds 32
adb -s 2696813b logcat -d -T "10-16 23:47:10.000" -s EdithP3:* System.out:* | Select-String -Pattern "P3_"
# 回合 2（同一进程、同一会话，间隔 >28 s）：重复上面三条注入 + 采集
adb -s 2696813b logcat -d -T "10-16 23:48:20.000" -s EdithP3:* System.out:* | Select-String -Pattern "P3_"
```

音频素材先 `ls` 过：`/data/local/tmp/edith-audio/wake-zh.wav`、`request-note.wav` 均在，**未改动任何素材**。

### 1.2 点击与通道（逐字）

```
10-16 23:47:01.882  14956  3708 I EdithP3T3b: A1CLICK_MATCH tick=11 label='菜单' clicked=true package=edith.phone
10-16 23:47:01.882  14956  3708 I EdithP3T3b: P3_A1CLICK_VERDICT outcome=clicked labels=菜单 matched=菜单 clicked=true polls=11 waitMillis=20000 window=edith.phone visible=13
10-16 23:47:13.361  14956  3844 I EdithP3T3b: A1CLICK_MATCH tick=12 label='开始' clicked=true package=edith.phone
10-16 23:47:13.362  14956  3844 I EdithP3T3b: P3_A1CLICK_VERDICT outcome=clicked labels=开始 matched=开始 clicked=true polls=12 waitMillis=25000 window=edith.phone visible=17
10-16 23:47:13.402  3483  3483 I EdithP3 : P3_EXEC channel=intent available=true
10-16 23:47:13.402  3483  3483 I EdithP3 : P3_EXEC channel=accessibility available=false
10-16 23:47:13.402  3483  3483 I EdithP3 : P3_EXEC channel=shizuku available=false
10-16 23:47:13.402  3483  3483 I EdithP3 : P3_EXEC channel=root available=false
10-16 23:47:13.744  3483  3483 I EdithP3 : P3_EXEC channel=shizuku available=true
10-16 23:47:13.788  3483  3518 I EdithP3 : P3_AUDIO state=armed generation=1 transport=true loop=true
```

### 1.3 回合 1（逐字）

```
10-16 23:47:25.666  3483  3953 I EdithP3 : P3_INJECT state=start file=wake-zh.wav sampleRateHz=16000 channels=1 chunks=90 windows=2
10-16 23:47:25.667  3483  3953 I EdithP3 : P3_INJECT state=ambient_stopped
10-16 23:47:26.858  3483  3877 I EdithP3 : P3_ACOUSTIC label=Speech
10-16 23:47:27.324  3483  3877 I EdithP3 : P3_WAKE word=伊迪丝 confidence=1.0
10-16 23:47:27.362  3483  3607 I System.out: EdithSmart P3_WAKE turn=smart-d6f5b88d-865d-4480-98ea-449d1a605d08
10-16 23:47:27.436  3483  3607 I System.out: EdithSmart P3_ASR revision=0 final=true chars=0 usable=false
10-16 23:47:27.499  3483  3953 I EdithP3 : P3_INJECT state=end chunks=90 accepted=90 stale=0 unknown=0 overflow=0 windows=2 drained=true
10-16 23:47:30.983  3483  4025 I EdithP3 : P3_INJECT state=start file=request-note.wav sampleRateHz=16000 channels=1 chunks=294 windows=4
10-16 23:47:32.393  3483  3607 I System.out: EdithSmart P3_ASR revision=0 final=false chars=1 usable=true
10-16 23:47:33.051  3483  3607 I System.out: EdithSmart P3_ASR revision=2 final=true chars=3 usable=true
10-16 23:47:33.052  3483  3607 I System.out: EdithSmart P3_TRANSCRIPT turn=c4ef99df-0908-4df6-8c14-1bc3d7cd7276 rev=2 final=true chars=3
10-16 23:47:33.052  3483  3607 I System.out: EdithSmart P3_CONV said route=WAKE chars=3
10-16 23:47:33.054  3483  4040 I System.out: EdithSmart P3_TURN start turn=c4ef99df-0908-4df6-8c14-1bc3d7cd7276
10-16 23:47:33.067  3483  4040 I System.out: EdithSmart P3_SKILL load files=1 skills=1 enabled=1
10-16 23:47:33.788  3483  4040 I System.out: EdithSmart P3_MEMORY inject=n=1 chars=250 via=lexical bucket=none indexed=0 ids=43f34796-be45-41e2-84ff-92969be10b8e,00570cbb-5a77-4025-ae6f-1d386906bd1e,4abfef8b-9486-4ddf-aaf9-0c969c7b1a55,f781abe8-e9f7-476b-8f37-e35bf106dc1a,c3e0443d-c2f8-41af-bfba-d1440f8d0f22
10-16 23:47:33.789  3483  4040 I System.out: EdithSmart P3_CONTEXT route=WAKE store=0 historyChars=0 memory=1
10-16 23:47:34.165  3483  4045 I EdithP3 : P3_HTTP method=POST status=200 ms=181
10-16 23:47:36.442  3483  4040 I System.out: EdithSmart P3_TURN end turn=c4ef99df-0908-4df6-8c14-1bc3d7cd7276 outcome=COMPLETED code=- steps=0
10-16 23:47:36.445  3483  4040 I System.out: EdithSmart P3_CONTEXT trim route=WAKE droppedTurns=0 compressionOwed=false
10-16 23:47:36.447  3483  4040 I System.out: EdithSmart P3_NOTIFY fallback=screen
10-16 23:47:36.609  3483  3607 I System.out: EdithSmart P3_HINT show hint=e39ba558-0589-4d1a-83f0-004a042d078e chars=50
10-16 23:47:36.611  3483  3877 I System.out: EdithSmart P3_CONV answer route=WAKE chars=50
10-16 23:47:36.683  3483  3607 I System.out: EdithSmart P3_HINT ack hint=e39ba558-0589-4d1a-83f0-004a042d078e status=Displayed
```

### 1.4 回合 2（逐字，store 计数非零的那一次）

```
10-16 23:48:17.729  3483  3877 I EdithP3 : P3_WAKE word=伊迪丝 confidence=1.0
10-16 23:48:17.762  3483  3607 I System.out: EdithSmart P3_WAKE turn=smart-c33651f6-247e-427e-91ef-9f6e91c53364
10-16 23:48:23.191  3483  3607 I System.out: EdithSmart P3_CONV said route=WAKE chars=3
10-16 23:48:23.193  3483  4171 I System.out: EdithSmart P3_TURN start turn=8f4e5619-3b81-4d81-bfd6-cee0ff72b174
10-16 23:48:23.893  3483  4171 I System.out: EdithSmart P3_CONTEXT route=WAKE store=2 historyChars=53 memory=1
10-16 23:48:24.174  3483  4045 I EdithP3 : P3_HTTP method=POST status=200 ms=147
10-16 23:48:25.939  3483  4171 I System.out: EdithSmart P3_TURN end turn=8f4e5619-3b81-4d81-bfd6-cee0ff72b174 outcome=COMPLETED code=- steps=0
10-16 23:48:25.942  3483  4171 I System.out: EdithSmart P3_CONTEXT trim route=WAKE droppedTurns=0 compressionOwed=false
10-16 23:48:25.943  3483  4171 I System.out: EdithSmart P3_NOTIFY fallback=screen
10-16 23:48:26.097  3483  3877 I System.out: EdithSmart P3_CONV answer route=WAKE chars=46
10-16 23:48:26.725  3483  3607 I EdithP3 : P3_CONTEXT keys=app.foreground,proactive.armed,time.local,time.period
```

判读：回合 1 开始时 store 为空（`store=0`），回合 1 结束后被记录；**回合 2 开始时 `store=2 historyChars=53`**，即 store 自己的历史**跨回合**进了请求装配（两个条目 = 上一回合的用户句与助手句）。两回合都 `COMPLETED`，两次都命中真实网络 `P3_HTTP … status=200`。

### 1.5 负向标记扫描（逐字：**空**）

```powershell
adb -s 2696813b logcat -d -T "10-16 23:46:00.000" | Select-String -Pattern "ConfigurationRejected|NO_USER_TURN|P3_TURN end.*FAILED|PLUGIN_RUN_THREW|P3_CONTEXT compress|P3_SUMMARY|SHELL_REPLY"
# 无任何输出
```

即本轮**没有** `FAILED` / `ConfigurationRejected` / `NO_USER_TURN` / `PLUGIN_RUN_THREW`，也**没有**任何压缩动作。

### 1.6 与相位描述的差异（就地记账）

1. **`P3_CONTEXT` 的 store 变体只带 `store=` / `historyChars=` / `memory=`**，**不带** `droppedTurns=` / `compressionOwed=`。这两个字段属于**另一行** `P3_CONTEXT trim route=… droppedTurns=… compressionOwed=…`（`SmartAssistantPlugin.recordTurnAndCompress`，`plugins-smart/.../SmartAssistantPlugin.kt:1333`）。两者都是逐字实测，见 §1.3/§1.4。
2. **本轮没有发生压缩**：两行 `trim` 都是 `droppedTurns=0 compressionOwed=false`，全日志无 `P3_CONTEXT compress`、无 `P3_SUMMARY`。原因可核实：store 只有 2 条 / 53 字符，远未越过预算；注入的请求句 ASR 只出 3 个字符。**「压缩真的发生」与「摘要进了下一回合请求」本轮 `NotMeasured`** —— 不是失败，是没有造出能触发压缩的会话长度；要造它需要一次长会话（多条长句），本轮未做。
3. `P3_HTTP` 两次都是 `200`，与「档案 Key 正确可用」的既有事实一致；**本轮未读、未打印该 Key**。

---

## 2. Task B：R-9 截断在真实 Binder 上

### 2.1 仪器

新增 **debug-only、exported** 接收器 `edith.phone/.DebugShellCapProbe`（action `edith.DEBUG_SHELLCAP`），经 `HostCompositionRoot.privilegedShellForProbe`（debug-only 访问器）打到**生产**的同一个 `ShizukuUserServiceShell` 实例上（**没有**新建第二个实例，因此没有第二条 Shizuku user-service 绑定）。它只记：固定 label、exit code、`stdout`/`stderr` **字节数**、`truncated` 标志、通道自己的失败代号、elapsed ms、以及是否返回。**命令本身与回复正文一律不落日志。**

「是否返回」的读法：`start` 行写在 binder 调用**之前**，`end` 行只在调用返回**之后**写 —— 因此**有 `start` 而无 `end` 就是没返回**（探针无法记录自己的挂起，这是刻意的）。

### 2.2 命令

```powershell
# 会话先起（点「开始」→ 通道绑定），先读通道状态
adb -s 2696813b shell "am start --activity-clear-top -n edith.phone.a11yprobe/.A11yClickActivity --es labels '菜单' --ez confirm true --el waitMillis 25000"
adb -s 2696813b shell "am start --activity-clear-top -n edith.phone.a11yprobe/.A11yClickActivity --es labels '开始' --ez confirm true --el waitMillis 25000"
# 控制臂（小命令）
adb -s 2696813b shell "am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe --es label control-brightness --es command 'settings get system screen_brightness'"
# 大命令
adb -s 2696813b shell "am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe --es label logcat-d --es command 'logcat -d'"
# 精确字节数的定点扫（每档一条，间隔 6-8 s）
adb -s 2696813b shell "am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe --es label bytes-<N>k --es command 'yes a | head -c <N>000'"
# 旁证：同一个大命令在设备侧的真实字节数
adb -s 2696813b shell "logcat -d | wc -c"
adb -s 2696813b logcat -d -T "10-16 23:50:30.000" -s EdithP3:* | Select-String -Pattern "P3_SHELLCAP"
adb -s 2696813b shell pidof edith.phone
```

### 2.3 逐字日志（控制臂 + 大命令）

```
10-16 23:50:37.367  4361  4361 I EdithP3 : P3_EXEC channel=shizuku available=true
10-16 23:50:49.196  4361  4838 I EdithP3 : P3_SHELLCAP state=CHANNEL label=control-brightness ready=true
10-16 23:50:49.196  4361  4838 I EdithP3 : P3_SHELLCAP start label=control-brightness
10-16 23:50:49.286  4361  4838 I EdithP3 : P3_SHELLCAP end label=control-brightness returned=true exit=0 stdoutBytes=3 stderrBytes=0 truncated=false code=none elapsedMs=89
10-16 23:50:59.739  4361  4896 I EdithP3 : P3_SHELLCAP state=CHANNEL label=logcat-d ready=true
10-16 23:50:59.739  4361  4896 I EdithP3 : P3_SHELLCAP start label=logcat-d
10-16 23:51:01.789  4361  4896 I EdithP3 : P3_SHELLCAP end label=logcat-d returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=2050
```

旁证（设备侧，同一命令）：`adb -s 2696813b shell "logcat -d | wc -c"` → `41427809`
调用后进程仍在：`adb -s 2696813b shell pidof edith.phone` → `4361`（与调用前同一 pid）。

### 2.4 逐字日志（精确字节数定点扫）

```
10-16 23:53:02.595  4361  5207 I EdithP3 : P3_SHELLCAP end label=bytes-50k returned=true exit=0 stdoutBytes=50000 stderrBytes=0 truncated=false code=none elapsedMs=82
10-16 23:53:09.904  4361  5225 I EdithP3 : P3_SHELLCAP end label=bytes-100k returned=true exit=0 stdoutBytes=100000 stderrBytes=0 truncated=false code=none elapsedMs=178
10-16 23:53:17.134  4361  5240 I EdithP3 : P3_SHELLCAP end label=bytes-200k returned=true exit=0 stdoutBytes=200000 stderrBytes=0 truncated=false code=none elapsedMs=122
10-16 23:53:50.866  4361  5319 I EdithP3 : P3_SHELLCAP end label=bytes-250k returned=true exit=0 stdoutBytes=250000 stderrBytes=0 truncated=false code=none elapsedMs=168
10-16 23:53:57.212  4361  5331 I EdithP3 : P3_SHELLCAP end label=bytes-300k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=197
10-16 23:54:03.534  4361  5348 I EdithP3 : P3_SHELLCAP end label=bytes-350k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=193
10-16 23:53:24.462  4361  5265 I EdithP3 : P3_SHELLCAP end label=bytes-400k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=221
10-16 23:53:31.846  4361  5282 I EdithP3 : P3_SHELLCAP end label=bytes-600k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=277
10-16 23:52:16.014  4361  4998 I EdithP3 : P3_SHELLCAP end label=bytes-900k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=370
10-16 23:52:24.691  4361  5030 I EdithP3 : P3_SHELLCAP end label=bytes-1500k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=541
10-16 23:52:33.881  4361  5069 I EdithP3 : P3_SHELLCAP end label=bytes-3000k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=1070
10-16 23:52:45.120  4361  5121 I EdithP3 : P3_SHELLCAP end label=bytes-9000k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=3118
```

> 日志行在控制台里被换行折断了（`stderrByte`s / `stdoutBytes` 处），上面是**未折断**的原始单行；其余字段逐字未改。

### 2.5 判读

1. **控制臂通过且计量精确**：`settings get system screen_brightness` → `exit=0 stdoutBytes=3 stderrBytes=0 truncated=false elapsedMs=89`；`yes a | head -c N` 三档小尺寸回报的字节数与请求**逐字节相等**（50 000 / 100 000 / 200 000 / 250 000）。这证明探针的字节计数与通道本身是可信的，因此下面的失败不是装置假象。
2. **8 MiB 上限在真机上不可达**：`truncated=true` **一次都没出现**。回复在 **250 000 B（成功）与 300 000 B（失败）之间**断掉 —— 远低于 8 388 608 B 的 cap。原因与 Binder 的每事务上限（回复以 UTF-16 写入，250k 字符 ≈ 500 kB parcel）一致；**8 MiB 上限仍然守住它的既定用途（限制特权进程的内存累积），但它不是链路的实际上限**，这与 `ShellReply.MAX_REPLY_BYTES` KDoc 自己的表述一致。
3. **`SHELL_REPLY_TOO_LARGE` 没有出现，出现的是 `SHELL_REPLY_INVALID`**：这正是 R-9 想要区分的两件事（「通道载不动」vs「本构建看不懂」）在真机上的实际结果 —— 一个载不动的回复仍然被报成 `REPLY_INVALID`（`stderr` 为 `SHELL_REPLY_INVALID`，19 字节；`exit=-1`；`stdout` 0 字节）。**这是与 `ShizukuUserServiceShell` KDoc 的「a reply the channel could not carry ⇒ SHELL_REPLY_TOO_LARGE with exit -1」相反的实测**。
   - 机制**未能从本探针区分**（如实记）：`ShizukuUserServiceShell.run` 先把 `TransactionTooLargeException` 单独接住、其余 `Throwable` 归 `null`，随后 `decode` 失败与「调用抛了别的异常」都会落到同一个 `SHELL_REPLY_INVALID`。本探针看到的是 `returned=true` 且 `run` 本身**没有**抛（若抛了会打 `threw=true`），所以失败发生在 `run` 内部那两段里；**要区分「回包截断未解析」与「binder 抛了非 TTL 异常」，必须改生产代码的错误分类**（本轮授权范围只允许新增 debug 探针与一个 debug-only 访问器，故未改）。
4. **最重要的那条：没有死锁。** 13 次调用**全部返回**（82 ms – 3 118 ms），其中 41 427 809 B 的 `logcat -d` 在 **2 050 ms** 返回；调用后 `pidof edith.phone` 仍是同一个 `4361`，无进程死亡、无 OOM 迹象、无 `start` 无 `end` 的悬挂。`drainStreams` KDoc 说它移除的那个死锁，在这一次真机实验里**没有复现**。
5. 期望值 vs 实测（父任务要求「stdout 应钉在 8 MiB cap 且 truncated=true」）：**该期望未被证实**。得到的是 `stdoutBytes=0 truncated=false code=REPLY_INVALID`。JVM 结果不能替代这个读数，本文件不把 JVM 当设备证据。

---

## 3. 仓库改动（可复审）

| 文件 | 类型 | 目的 |
|---|---|---|
| `phone-app/src/debug/kotlin/edith/phone/DebugShellCapProbe.kt` | **新增** | R-9 真机探针：exported debug 接收器（`edith.DEBUG_SHELLCAP`），跑一条命令并只记形状（label / exit / stdout 字节 / stderr 字节 / truncated / 通道失败代号 / elapsed ms）。不记命令、不记正文。`internal`（代号与常量）取自 `PrivilegedShell.kt`。 |
| `phone-app/src/debug/AndroidManifest.xml` | 追加 | 注册 `.DebugShellCapProbe`（`exported="true"`，理由与既有 4 个 debug 仪器相同：唯一调用者是 shell UID 的 `am broadcast`，未导出的接收器根本到不了）。 |
| `phone-app/src/main/kotlin/edith/phone/HostCompositionRoot.kt` | 两处小改 | ① 新增 `internal var privilegedShellForProbe: ShizukuUserServiceShell? = null`；② 在 `create` 里 `if (BuildConfig.DEBUG) root.privilegedShellForProbe = shizukuShell`（就在 `executionChannelBringUp` 之后）。**debug-only**：`BuildConfig.DEBUG` 是编译期常量，release 构建该分支是死代码，生产路径无任何读取者、无行为变化；用字段而非构造第二个 `ShizukuUserServiceShell`，是为了不动 app 真实的 Shizuku 绑定。 |
| `docs/project/evidence/2026-10-16-phase-5-device-verification.md` | **新增** | 本文件。 |

**未改动**：`ShizukuUserServiceShell.kt`、`ShellStreams.kt`、`ShellReply.kt`、`EdithShellService.kt`、任何 production 行为、任何测试、任何他线文件（`phone-accessibility-probe/*` 的工作树改动与 `docs/project/CONTRACTS.md` 等均**不是**本轮产生的）。
**未提交**：本轮改动留在工作树，未 `git add`、未 commit、未 push/merge/tag（父任务只要求「改动可复审」，见 §7）。
**探针的测试覆盖（如实记）**：该 debug 仪器**没有 JVM 测试**，与既有 `DebugLayaProbe` / `DebugAudioInjector` 的做法一致；它的正确性由本轮的**控制臂**在设备上验证（字节数逐字节相等 + 小命令 `truncated=false`），而不是由单测。

**改动对既有 JVM 套件的影响（本轮实测）**：`.\.gradlew.bat test` → `BUILD SUCCESSFUL in 8s`；其中 `:phone-app:testDebugUnitTest` **不是 UP-TO-DATE（因 debug 源集改动而重跑）**，结果 **77 suites / 662 tests / 0 failures / 0 errors**（读自 `phone-app/build/test-results/testDebugUnitTest/*.xml`）；其余模块该次为 UP-TO-DATE（未改动、未重跑）。**本轮没有拿到 292 suites / 2544 tests 的全量重跑**，故不引用该数字。

---

## 4. 失败 / 被拒 / 未达预期（如实记）

1. **Task B 的期望结果未出现**：`truncated=true` 与 `stdout=8 MiB` 未观测到；见 §2.5。这不是装置故障（控制臂精确），是真实链路行为。
2. **`SHELL_REPLY_TOO_LARGE` 未出现**：载不动的回复回落为 `SHELL_REPLY_INVALID`（§2.5-3）。
3. **压缩未触发**：Task A 两回合 `compressionOwed=false`、无 `P3_CONTEXT compress` / `P3_SUMMARY`（§1.6-2）。
4. **没有发生任何拒绝/异常路径**：无 `FAILED`、无 `ConfigurationRejected`、无 `NO_USER_TURN`、无 `PLUGIN_RUN_THREW`、无 `SHELL_REPLY_TOO_LARGE`、无 `Amber/ANR`、无进程死亡。
5. **未被使用/未触碰**：眼镜 `1901092544027662`；`a11yprobe` 的无障碍服务设置（开始前已是启用态，未读写）；API Key（未读、未打印、未输入）。

---

## 5. 安装与不可替代状态（provision + API key）

- 两次安装都是**纯 `adb -s 2696813b install -r`**，输出均为 `Performing Streamed Install` / `Success`，**没有**任何 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`、**没有**签名不匹配、**没有** `uninstall`、**没有** `connectedDebugAndroidTest`、**没有** `gradlew installDebug`。
- 结论：**1.03 GiB 手工 provision 的模型集与可用 API Key 都保住了**。
  证据不只是「install 成功」：安装后的**同一个新进程**里，两回合都真实出网并返回 `P3_HTTP method=POST status=200`（`ms=181` / `ms=147`）—— 若 Key 或档案丢失，这里会是 401/失败而不是 200。此外 `edith.phone` 的 `lastUpdateTime` 是就地更新（`install -r`），包未经历卸载。

---

## 6. 隐私

- 本文件与本轮所有日志行只含：计数、字节数、布尔、标志、代号、turn/hint 的 id、包名、时间戳、文件名（音频素材）。
- **不含**：命令输出、模型正文、提示正文、ASR 正文、用户文本、屏幕内容、凭证、API Key。
- 探针**刻意**不打印所执行的命令与回复正文；`label` 被清洗为 `[A-Za-z0-9_-]` 且有界，因此它本身也无法成为正文通道。

---

## 7. 一句话交接

Task A 拿到真机 store 读数（`store=0` → `store=2`，回合 `COMPLETED`，`P3_HTTP 200`）；Task B 在真机上**否证**了「8 MiB cap + `truncated=true`」这一预期：真实上限在 250–300 kB 之间，越限回落为 `SHELL_REPLY_INVALID` 而**不是** `SHELL_REPLY_TOO_LARGE`，且**所有调用都有界返回（无死锁）**。要闭合 R-9 的「载不动 ≠ 看不懂」这条区分，需要改 `ShizukuUserServiceShell.run` 的错误分类（生产代码，本轮未授权）。

---

## 8. R-9 收口（2026-10-17，同一台 K40，同一探针）

> 本节是**新增**的一轮：§2 记录的是修前实测（当时生产分类代码未获授权改动），本节记录**改完之后**的真机复测。
> 口径不变：只记计数、字节数、布尔、标志、代号与**异常类名**；不含命令、回复正文、凭证。

### 8.1 发现的异常类（本轮的关键读数）

**`android.os.DeadObjectException`** —— 逐字来自探针新增的 `throwable=` 字段：

```
10-17 00:17:06.815  6421  6986 I EdithP3 : P3_SHELLCAP end label=bytes-400k returned=true exit=-1 stdoutBytes=0 stderrBytes=21 truncated=false code=SHELL_REPLY_TOO_LARGE arm=carrier throwable=android.os.DeadObjectException elapsedMs=203
```

修前 `ShizukuUserServiceShell.run` 先单独接住 `TransactionTooLargeException`、其余 `Throwable` 归 `null`；实测说明**那个类根本没被抛出**，所以命名给它的 `SHELL_REPLY_TOO_LARGE` 永远不会触发，而真正的载不动被吞成 `decode(null)` → `SHELL_REPLY_INVALID`。**这正是「按异常类名分类」这一做法的失败**：分类必须对**任何**从 binder 调用里抛出的东西成立。

**机制（设备侧应用进程日志，逐字）**：

```
10-17 00:17:04.467  6892  6918 W edith.phone:shell: Large reply transaction of 750080 bytes, interface descriptor edith.phone.actions.IRemoteShell, code 2
10-17 00:17:09.286  6892  6965 W edith.phone:shell: Large reply transaction of 2700080 bytes, interface descriptor edith.phone.actions.IRemoteShell, code 2
```

即：远端在**写回复事务**时报 `Large reply transaction`，**binder 连接随之死亡**，调用方拿到 `DeadObjectException`。
远端 shell 进程随后被框架**重启**（`ps -A` 实测：`6892 shell edith.phone:shell`；下一次调用即在新 pid 上成功）⇒ 这是**可恢复的、按次**的通道失败，不是永久断链。

### 8.2 分类改动（生产）

| 事实 | 代号 | `ShellResult.failure` | 含义 |
|---|---|---|---|
| 没绑定 | `SHELL_NOT_BOUND` | `NOT_BOUND` | 通道在 `available()` 与本次调用之间死了 |
| **调用没回来** | `SHELL_REPLY_TOO_LARGE` | `CARRIER` | binder 载不动 / 连接死亡。**不再是单纯的异常类判断**：任何 `Throwable` 都归这里 |
| 回包到了但看不懂 | `SHELL_REPLY_INVALID` | `PARSE` | 只表示「载体交付了本构建无法解析的东西」 |
| 正常回包 | `stderr` 由命令自己写 | `null` | 命令自己的诊断，不是通道失败 |

**`readable()` 的口径同步收紧**（同一族里已知的洞）：`isAStateReply()` 从「不等于那两个已知代号」改成**白名单**——`failure == null && stderr.isEmpty()`。旧写法让 `SHELL_NOT_BOUND`（以及任何后来新增的代号）读成「成功但 stdout 为空」，于是 `installed()` 对没人问过的目标答 `false`，后端把它判成「动作没生效」。本题用变异测试证明：把实现改回旧两码比较，`SHELL_NOT_BOUND` 与「未识别代号」两条测试**立刻变红**。

### 8.3 修前 vs 修后（同尺寸同标签）

修前（§2.4，2026-10-16，build 无本节改动）：

```
… P3_SHELLCAP end label=bytes-300k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=197
… P3_SHELLCAP end label=bytes-400k returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=221
… P3_SHELLCAP end label=logcat-d  returned=true exit=-1 stdoutBytes=0 stderrBytes=19 truncated=false code=REPLY_INVALID elapsedMs=2050
```

修后（2026-10-17 00:19，最终 build，`logcat -c` 后重跑）：

```
10-17 00:19:09.234  7507  7702 I EdithP3 : P3_SHELLCAP end label=control-brightness returned=true exit=0 stdoutBytes=3 stderrBytes=0 truncated=false code=none arm=none throwable=none elapsedMs=73
10-17 00:19:11.570  7507  7722 I EdithP3 : P3_SHELLCAP end label=bytes-250k returned=true exit=0 stdoutBytes=250000 stderrBytes=0 truncated=false code=none arm=none throwable=none elapsedMs=186
10-17 00:19:13.850  7507  7734 I EdithP3 : P3_SHELLCAP end label=bytes-300k returned=true exit=0 stdoutBytes=300000 stderrBytes=0 truncated=false code=none arm=none throwable=none elapsedMs=164
10-17 00:19:16.136  7507  7745 I EdithP3 : P3_SHELLCAP end label=bytes-400k returned=true exit=-1 stdoutBytes=0 stderrBytes=21 truncated=false code=SHELL_REPLY_TOO_LARGE arm=carrier throwable=android.os.DeadObjectException elapsedMs=184
10-17 00:19:18.306  7507  7758 I EdithP3 : P3_SHELLCAP end label=logcat-d returned=true exit=0 stdoutBytes=35217 stderrBytes=0 truncated=false code=none arm=none throwable=none elapsedMs=46
```

判读：
1. **区分成立**：≥300 kB（实测 400 kB）不再是 `REPLY_INVALID`，而是 `code=SHELL_REPLY_TOO_LARGE arm=carrier`；小回复与 ≤300 kB 回复 `arm=none code=none`，正常路径未被破坏（控制臂 `exit=0 stdoutBytes=3` 逐字节正常，后注入的真实回合仍 `P3_HTTP 200`）。
2. **无死锁**：四次调用 73 / 186 / 164 / 184 ms 全部返回，无「有 `start` 无 `end`」。
3. **`stderr` 字节数 19 → 21**：`SHELL_REPLY_INVALID` 是 19 B，`SHELL_REPLY_TOO_LARGE` 是 21 B —— 计数器如实反映了代号的改变。
4. **`logcat-d` 本次 35 217 B（成功）**：本轮设备 logcat 已被多次 `-c` 清空，**不能**与 §2.3 那次的 41 427 809 B 比较；41 MB 那一档在 §8.4 另测。
5. **本次上限仍不可精确定**：`bytes-300k` 成功、`bytes-350k` 失败（§8.4），与 §2.5 的「250–300 kB 之间」**不同**。两次读数的差别无法用本探针分辨（远端进程新旧、当时的 log 体量都会改变那条 1 MB 事务的两侧开销），因此本文件**只按各自实测如实记**，不合并成一个数字。

### 8.4 逐字节扫描（00:17 那一轮，同 build）

```
… label=bytes-250k  exit=0  stdoutBytes=250000 code=none arm=none
… label=bytes-275k  exit=0  stdoutBytes=275000 code=none arm=none
… label=bytes-300k  exit=0  stdoutBytes=300000 code=none arm=none
… label=bytes-325k  exit=0  stdoutBytes=325000 code=none arm=none
… label=bytes-350k  exit=-1 stdoutBytes=0 stderrBytes=21 code=SHELL_REPLY_TOO_LARGE arm=carrier throwable=android.os.DeadObjectException
… label=bytes-400k  exit=-1 … arm=carrier throwable=android.os.DeadObjectException
… label=bytes-900k  exit=-1 … arm=carrier throwable=android.os.DeadObjectException
… label=logcat-d   exit=-1 … arm=carrier throwable=android.os.DeadObjectException
```

⇒ 本轮**最大成功 325 000 B、最小失败 350 000 B**；41 MB 的 `logcat -d` 也落 `carrier`（不再是 `REPLY_INVALID`），且**有界返回**。真实上限仍**未被精确定到字节**，只能说落在 325–350 kB 之间（本轮）。

### 8.5 常量决定（Step 3）

`ShellReply.MAX_REPLY_BYTES` **保持 8 MiB 不变**，理由与旧文档不同，已在 KDoc 里改写：

- 它是**特权进程的内存上限**，**不是链路限制**；真机在 ~325–350 kB 就失败，**远低于任何我们设的 cap** ⇒ `truncated=true` 在真机上**一次都没出现**（§2 + 本节）。
- **不改成贴近链路的值**：把 cap 压到 ~325 kB 会让「今天还能整份到达」的回复开始被**截断**，把「进程不得无限增长」和「binder 载不动」两件事混成一件。
- 旧理由（「最大成功 1 228 800 B 的 6.7×」）**撤回**：那个 1.2 MB 读数的探针回的是**计数**而不是正文，与本通道的实际交付不是一回事；此后实测**整份到达的最大回复是 325 000 B**。

### 8.6 仍未验证 / 不得读成通过

1. **上限的精确值**：仍未定位到字节；本轮区间 325–350 kB，§2 那轮是 250–300 kB，两次不一致的原因**未查**（只如实记两个区间）。
2. **`truncated=true` 在真机上的行为**：修后仍未出现过（链路先死）；该路径只有 JVM 测试覆盖。
3. **`SHELL_NOT_BOUND` 的真机复现**：`readable()` 对它的排除由 JVM 测试 + 变异测试钉住，**没有**在真机上造出「调用中途通道死亡」。
4. **载不动之后 `ready()` 的语义**：实测远端被重启、下一次调用成功（§8.1），因此**没有**在 `run` 里主动清空 `remote`；「连接死亡瞬间 `ready()` 仍报 true」这一小窗口**未在真机上测量**。
   - **补充（R-9 收尾记账，2026-10-17）**：**「载不动之后 `remote` 永久为 `null`」这件事，我们自己的代码不能证明它被排除**。
     `bind()` 只在组合根创建时调用一次（`HostCompositionRoot.kt:1700`），**失败路径上没有任何重绑**；`onServiceDisconnected`
     只把 `remote` 置 `null`，恢复完全依赖 Shizuku 框架自己的重投递（真实 Shizuku UserService 才能验证）。
     正向证据即本节：**同一通道在更后一轮、新远端 pid 上成功**（不是紧随其后的那一条 sweep 项）。⇒ 本项记「未验证」，
     不得读成「已排除」；本轮**刻意不改**（属行为变更，不在收尾范围内）。
5. **真实回合的压缩**：仍 `NotMeasured`（与 §1.6-2 相同，本轮未造长会话；§8.7 的回合只为证明资源与凭证仍在）。
6. **K70**：按 `D65` 不单独做设备验收，本轮未测（K40 通过的有效性口径不变）。

### 8.7 资源与凭证（不可替代状态）

- 本轮共两次 `adb -s 2696813b install -r phone-app-debug.apk`（00:13、00:19），输出均为 `Performing Streamed Install` / `Success`；**无** `INSTALL_FAILED_UPDATE_INCOMPATIBLE`、**无**签名不匹配、**无** `uninstall`、**无** `connectedDebugAndroidTest`、**无** `gradlew installDebug`。
- **模型集仍在（实测）**：注入 `wake-zh.wav` + `request-chat.wav` 后，本地 ASR 出字并 final —— `P3_ASR revision=2 final=true chars=2 usable=true`、`P3_CONV said route=WAKE chars=2`，随后 `P3_ASR … final=true chars=12`。
- **API Key 仍在（实测）**：同一回合真实出网 —— `P3_TURN start turn=212a02cb…` → `P3_HTTP method=POST status=200 ms=184` → `P3_TURN end turn=212a02cb… outcome=COMPLETED code=- steps=0`。Key 全程**未被读取、未被打印**。
- **进程连续性**：探针调用前后 `pidof edith.phone` 同为 `7507`；远端 shell `edith.phone:shell` 在载不动后被重启（新 pid），下一次调用成功。
- **眼镜 `1901092544027662` 未触碰**：本轮所有 `adb` 命令都带 `-s 2696813b`；`adb devices` 两次都列出两台设备，**没有**对眼镜执行任何动作。
- **无障碍设置未改动**：写入前后都只是 `settings get secure enabled_accessibility_services`（读），值仍为 `edith.phone.a11yprobe/.A11ySurveyNoIdsService:…/.A11ySurveyService:…/.A11yProbeService`。

### 8.8 代码与测试（本轮）

| 文件 | 改动 |
|---|---|
| `phone-app/src/main/kotlin/edith/phone/actions/PrivilegedShell.kt` | 新增 `ShellFailure`（`NOT_BOUND`/`CARRIER`/`PARSE`）+ `ShellResult.failure` + `carrierCodeFor(thrown)`（**任何** `Throwable` → carrier 代号）+ `SHELL_NOT_BOUND` 常量；`isAStateReply()` 改为 `failure == null && stderr.isEmpty()`（白名单，堵住 `SHELL_NOT_BOUND` 这个同族洞） |
| `phone-app/src/main/kotlin/edith/phone/actions/ShizukuUserServiceShell.kt` | `run` 的错误分类：不再单独判 `TransactionTooLargeException`；每个失败分支同时给出代号与 `failure`；新增 `lastCarrierThrowable`（**只含类名**，供探针读） |
| `phone-app/src/main/kotlin/edith/phone/actions/ShellReply.kt` | `MAX_REPLY_BYTES` 的 KDoc 改写（内存上限、不是链路限制、撤回旧 1.2 MB 依据）；类 KDoc 明确 `SHELL_REPLY_INVALID` 只表示「载体交付了看不懂的东西」 |
| `phone-app/src/debug/kotlin/edith/phone/DebugShellCapProbe.kt` | `end` 行新增 `arm=` 与 `throwable=`（只用**类名**）；`classify()` 从 `ShellResult.failure` 取判断，不再靠字符串 |
| `phone-app/src/test/kotlin/edith/phone/actions/ShellCarrierFailureTest.kt` | **新增**（5 条）：任何非 TTL 异常都归 carrier、与 parse 可分辨、`failure` 不经字符串解析 |
| `phone-app/src/test/kotlin/edith/phone/actions/ShellStateReadabilityTest.kt` | **新增**（5 条）：`SHELL_NOT_BOUND` / carrier / parse / **未识别代号**都不可读；空 `stderr` 仍可读 |
| `phone-app/src/test/kotlin/edith/phone/actions/ShellReplyCapTest.kt` | 既有的「too large ≠ parse」用例对齐新形状（补 `failure`、改为断言 `failure` 而非 `isAStateReply`） |

**测试读数（本轮，实测）**：`.\gradlew.bat :phone-app:testDebugUnitTest :plugins-smart:test` → **BUILD SUCCESSFUL**；
`phone-app` 79 套件 / **672 tests** / 0 failures / 0 errors；`plugins-smart` 60 套件 / **530 tests** / 0 failures / 0 errors（聚合自各自 `build/test-results/**/*.xml`）。

**TDD 与变异证据（RED 逐字）**：
1. 先写测试 → 编译失败（`Unresolved reference 'carrierCodeFor'` / `'ShellFailure'` / `No parameter with name 'failure'`）⇒ 证明测试确实在钉未实现的行为。
2. 实现后全绿（672/0）。
3. **变异 1**：`carrierCodeFor` 改成返回 `SHELL_REPLY_INVALID` ⇒ `ShellCarrierFailureTest` **2 条变红**（`a binder failure that is not TransactionTooLarge is still classified as a carrier failure`、`every carrier failure is distinguishable from a parse failure`）；恢复后绿。
4. **变异 2**：`isAStateReply()` 改回旧两码比较 ⇒ `ShellStateReadabilityTest` **2 条变红**（`SHELL_NOT_BOUND is not a state reply…`、`any unrecognised failure code is excluded rather than trusted`）；恢复后绿。

### 8.9 隐私（本节）

只记录：字节数、毫秒、布尔、`arm`/`code` 代号、**异常类名**（`android.os.DeadObjectException`）、包名、pid、turn id、音频**文件名**基线。
**不含**：命令文本、回复正文、`stderr` 内容、模型/提示/ASR 正文、用户文本、凭证。探针的 `throwable=` **只取类名**，不取 `message`（`message` 可能引用命令）。

