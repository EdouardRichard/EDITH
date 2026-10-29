# 2026-10-14 续：新版唤醒录音的实况注入召回 + 真机回合路径第一次跑通

- **分支**：`main`（本文件写于会话中，提交见当日 docs 提交）
- **设备**：K40 `2696813b`（Android 13 / API 33），眼镜 `1901092544027662`（**注入时 `adb devices` 报 offline**，
  但 CXR 链路在会话内是活的：`P3_CXR connect attempt=start result=ok`，`P3_FRAME` 持续增长）
- **授权**：业主 2026-10-14 明确授权本会话"通过 ADB / 无障碍替你操作手机"；**点「开始」是唯一同意动作**（`D53`①）
- **仪器**：P3.1 §9 的 debug 注入器 `edith.DEBUG_INJECT`（`DebugAudioInjector`）＋ `P3Log` 行；切分脚本
  `.local/dev/cut-wake-clips.py`

## 1. 新录音与切分

业主本轮提供了两份新录音（本机 `Audio\`）：

| 文件 | 时长 | 说明 |
|---|---|---|
| `唤醒词-中文.wav` | 6.976 s（111,616 帧 @16 kHz 单声道） | 2026-10-14 03:32 写入 |
| `唤醒词-英文.wav` | 8.896 s（142,336 帧） | 2026-10-14 03:57 写入（业主英文，**已按裁决搁置**） |

按上一会话记录的方法切分：20 ms 窗 RMS，静音阈值 −40 dBFS、最短静音 300 ms，**切点取静音中点**（因此切点
不可能落在词内），两侧留 150 ms margin。中文得 **4 段**、英文得 **6 段**；每段按"时长 + 电平 + 语音起点"
逐条核验（见脚本输出，`.local/dev/`）：

| 片段 | 时长 | 平均电平 | 判定 |
|---|---|---|---|
| zh-1 | 0.820 s | **−38.2 dB** | **近静音负对照** |
| zh-2 | 1.660 s | −16.5 dB | 唤醒词 |
| zh-3 | 1.680 s | −16.1 dB | 唤醒词（**切分可疑**，见 §3 记账） |
| zh-4 | 1.320 s | −14.9 dB | 唤醒词 |
| en-1 | 0.770 s | **−36.9 dB** | **近静音负对照** |
| en-2…en-6 | 1.43–1.47 s | −15.4 … −16.9 dB | 英文唤醒词 ×5 |

## 2. 实况注入结果（每条间隔 6 s > 3 s 冷却；注入期间停止环境流）

**中文（新录音，业主本人发音）**：

| 注入 | 到达模型 | `P3_WAKE` |
|---|---|---|
| zh-1（近静音） | — | **0**（负对照，正确） |
| zh-2 | `P3_TURN end outcome=COMPLETED` + `P3_HINT show chars=59` | **✅ `word=伊迪丝 confidence=1.0`** |
| zh-3 | — | 0 |
| zh-4 | — | **✅ `word=伊迪丝 confidence=1.0`** |

⇒ **中文召回 2/3**（与上一会话注入旧录音得到的 2/3 一致；两次独立录音合计 **4/6**）。负对照 0/1，无误触发。

**英文（业主本人发音）**：en-1（近静音）0、**en-2…en-6 全 0** ⇒ **0/5**，与上一会话的 0/25 同向。

**zh-2 的重复注入（召回不稳定性）**：zh-2 单独重复注入 **5 次，命中 3 次**（15:41:28 ✅、15:44 前后 ❌ 未命中且
被 P3 判 `P3_PROACTIVE skip reason=VAGUE`、15:48:06 ❌、15:48:36 ✅、15:48:45 ✅）。**同一条同音量（−16.5 dB）
的片段命中 3/5**，这是"召回有损且不稳定"的直接读数，而不是"某条切得不好"。

**每一次注入的入口账都是干净的**：`state=end chunks=N accepted=N stale=0 unknown=0 overflow=0 drained=true`
（中文 41/83/84/66，英文 38/73/71/72/73/73，zh-2 重复 83/83/83）——所以"未命中"不是"没注入进去"。

## 2b. SKILL 设备半边（B4 / C3）：已闭环

业主裁定"SKILL 可以基于规则生成一份"，本轮据此产出一份 SKILL 并**在真机上验证它真的被读到并注入**：

1. **文件**：`docs/project/skills/device-task-discipline.md`（2,652 B，kebab-case 文件名与 `name` 一致）。
   内容只使用**工具表里真实存在的**动作名（`open_app` / `open_url` / `open_settings` / `set_alarm` / `compose_note` /
   `dial`）与 `notify_user` 的四个 channel，不发明新能力。
2. **解析器验证**（JVM，先写测试）：`SkillLibraryTest`（新，2 条）用**真实的 `SkillParser`** 解析仓库里每一份 SKILL，
   断言能解析、`enabled`、正文无占位符（`TODO`/`TBD`/`待补`）、文件名与 `name` 一致。
3. **provision**：`mkdir -p /sdcard/EDITH/skills` → 推送该文件；
   `appops set edith.phone MANAGE_EXTERNAL_STORAGE allow`（`cb4c276` 已声明该权限，此前**未授权**）。
4. **真机读数**（K40，新构建 `install -r`，provision 完好）：

```
15:48:36.765  P3_WAKE word=伊迪丝 confidence=1.0
15:48:36.966  EdithSmart P3_SKILL load files=1 skills=1 enabled=1     ← App 读到了磁盘上的那份 SKILL
15:48:40.184  P3_TURN end … outcome=COMPLETED
15:48:48.678  P3_HINT show … chars=100
```

⇒ **B4 的设备半边不再是"没有可读对象"**：目录存在、权限已授、App 实际列出并解析了 1 份 SKILL 且 `enabled=1`。

**一个必须记下的取证坑**：`adb shell run-as edith.phone ls /sdcard/EDITH/skills/` 报 **Permission denied**，但这**不能**
用来判断 App 的权限——`run-as` 有自己的 SELinux 上下文，读不到 `/sdcard`。判定只能靠 App 自己的进程读（上面那行
`P3_SKILL load`）。这个坑写在这里，免得下一个会话据此把已经闭环的事又当成没权限。

**为此新增的一行可观测性**（TDD，先红后绿）：`SkillManager` 原先**只在"重复/跳过"时打日志**，成功加载是无痕的，
于是"读到了 N 份并注入"和"目录不存在"在设备日志里完全一样。现在加载结束打
`P3_SKILL load files=<读到的文件数> skills=<解析出的> enabled=<会注入的>`，**只记计数、不记正文**
（`SkillManagerLogTest` 3 条钉住：有计数、空目录报 0、坏文件计入 files 但不计入 skills；`SkillManagerTest` 的
"重复报告不携带正文"改为对**所有**日志行断言不含正文）。

未测：现场说话（不经注入）的召回；英文词的实际匹配（已按业主裁决搁置）。

## 3. 真机回合路径：第一次跑通（端到端）

同一条 zh-new 全长录音（348 块、5 窗口）注入后，一个**完整回合**在真机上走完：

```
15:40:18.989  P3_INJECT state=start file=wake-zh-new.wav sampleRateHz=16000 channels=1 chunks=348 windows=5
15:40:23.235  P3_WAKE word=伊迪丝 confidence=1.0                     ← 宿主证据（detector 命中）
15:40:23.281  P3_WAKE turn=smart-58a0cf56-…                          ← 插件真的开了回合
15:40:23.424  P3_TURN start turn=cd886495-…
15:40:23.830  P3_HTTP method=POST status=200 ms=225                   ← 模型请求真的发出并成功
15:40:25.728  P3_TURN end … outcome=COMPLETED code=- steps=0
15:40:25.734  P3_NOTIFY fallback=screen                                ← §6.2 兜底（模型没调 notify_user）
15:40:25.889  P3_HINT show hint=hint-c258f535-… chars=44
15:40:26.102  P3_HINT ack … status=Displayed                          ← **眼镜确认已显示**
```

**这条链的意义**：阶段 B 的 `runTurn` → `buildPrompt`（WAKE 路由）→ 网关 → 回合终态 → 提示上屏，
**在目标设备上真的跑过一次**，而不是只在 JVM 测试里。`P3_HINT ack status=Displayed` 是眼镜侧真的收到并显示的
回执。

**必须同时记下的两条诚实边界**：

1. **这一轮发的是真实网络请求**（`P3_HTTP POST 200`），即业主的转写按其已同意的"会话在运行即允许"（`D53`）
   发往所配置的模型服务。K40 上**已有可用档案与凭证**（`files/profileInstalled` 存在），所以这不是假线，
   也没有使用 `ScriptedModelWire`。本证据里不含任何请求/响应正文（日志本身只记状态码与耗时）。
2. **这次唤醒是"注入"而不是"现场说话"**，而且喂的是**整段录音**（5 个窗口）。因此它证明的是
   "唤醒→回合→上屏"这条**代码链**在设备上成立，**不是**现场口语召回率。

## 4. 记账（未做 / 不改）

- **`zh-3` 单条未命中，不改判定**：三条真话语 2 命中，样本量太小，不能区分"该条切分不完整"与"召回本身有损"。
  记账方式：记成 2/3，并在切分表里标出 zh-3 的切分可疑。
- **英文不放宽**：按业主 2026-10-14 裁决"英文唤醒搁置"，本轮不再试拼写变体。
- **未改任何生产代码**：本证据只来自运行既有构建；本节所有结论都是测量，不是修复。
