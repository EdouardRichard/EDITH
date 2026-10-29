# EDITH 功能验收矩阵（0.21 基线）

- **锚点**：EDITH 0.21；结项 `edith-0.21-closed-20261017-01`
- **判词（逐字口径）**：**0.21 范围内未完全通过 / 不满足完整交付标准。**
- **规则**：任何 `NotMeasured` 不得读成通过；有效失败（M2、AC-13/16 设备行为）不得读成未测；不得用 F4/B 的正面读数抵消 M2 失败。
- **证据**：本矩阵的判词只引用 §6 索引中的 evidence；`docs/project/evidence/` 中另有历史阶段证据，保留但不用于当前判词。证据内容不可改写，完整设备/测量材料见 §6 证据索引。
- **0.23 P1–P6 新增**：U-1/U-2 于 §2.1；U-3/U-4 于 §2.2；U-5/U-6 于 §2.3；U-7/U-8 于 §2.4；U-9/U-10 于 §2.5；U-11 于 §2.6；0.21 判词与行不变。**0.23 版本级判词：业主 2026-10-18 验收不通过。0.24 S1–S5 已实施，S5 生产设备走查完成（2026-10-19）；业主 2026-10-19 版本级验收通过并闭环；Task 22 复核后 `b1d48a2` 修复 orphan wake panel，全量 JVM 314/3024；Settings 下层已定位为平台 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 保护，U-19 记 `Partial`，业主接受残留风险；U-12–U-20 判词见 §2.7，未测项见 §2.7.2，留待下一版本统一测试验证，不得读成通过。**

- **0.4 新增**：§2.16 登记 0.4 脱离眼镜/手机麦、文字/听写输入、模型内置的 J1–J12/D1–D12；最终 K40 矩阵 Task 13e 与 KWS epoch reset 已完成（D3/D4 严格本地/D7/D8/D9/D10 Verified；D5 Verified（2026-10-27 业主真人验收；合成历史 5/5/0/3 见证据）；D1/D2 保持 `Partial`；D11 权限拒绝 Verified、live revoke NM；D12 `NotMeasured`）；0.4 不是版本级发布通过，release APK/干净安装/空间不足、keyguard、真实人声、mic busy、APK 字节可复现、原生开关视口、精确 ASR fault code 仍为 `NotMeasured`。

- **0.5 新增（2026-10-29，纯手机模型；checkpoint，不是版本级发布通过）**：证据 [0.5 device](evidence/2026-10-29-0.5-phone-only-floating-ball-device.md)。**该文件在 `2c3621a4` 被原地改写**：改写前（git `29626079`/`1295edbd`）APK 为 91,182,525 B / SHA-256 `D94CD646…AF4C05`，当前文件为 sync 修复后 93,315,166 B / `17863B0A…CF2CCE`；旧 0.5 文件保持现状、不再改写，并列读数与 K2/K10 重分类见 [0.5.1 correction](evidence/2026-10-29-0.5.1-correction.md)。
- **0.5.1 更正（2026-10-29；checkpoint，不是版本级发布通过）**：用户可见名自 0.5.1 起为「纯手机模式」（`C1`）；C1–C16 实现 + 独立复审（T11 `54ed0341`，含 2 个 Important 修复）+ 全量 JVM **456 XML / 4,806 tests / 0F / 0E / 2S**（18 个产品/工具模块；探针 15/152 单列；排除 `EngineBundleProvisioningTest` 为 455/4,799/1S）+ K40 0.5.1 矩阵（K1–K11 见下表）。final debug 轻量 APK **91,240,820 B / SHA-256 `7DBC6B69…CA17044`**（API 33，`versionCode=5`/`0.5.0-preview`；设备 pull 回读同哈希）。**NM/Partial 见 [证据](evidence/2026-10-29-0.5.1-correction.md) §8/§9，不得读成版本级通过。**

| 行 | 结论 | 读数 / 边界 |
|---|---|---|
| K1 设置名「纯手机模式」（0.5 名为「纯手机模型」） | **Verified（JVM + 设备 channel 名）** | `DetachUiTest` 钉住文案；`SmartSettingsFormTest` 覆盖内部 `glassesDetached` 标志的保留（不改名）；0.5.1 设备通知 channel `phone_only_overlay` 的 `mName=纯手机模式悬浮球`（内部 id 未改）。**边界**：设置页截图未采集（设备文件 `12-settings-page.png` 实为助手页），设置页渲染 NM。 |
| K2 悬浮球外观/半隐藏/点击/长按/拖拽 | **Partial** | 0.5.1 截图 Verified：EDITH 图标、右边缘半隐藏、白圆底、绿色思考光环（[ball-default](evidence/0.5.1/2026-10-29-0.5.1-ball-default.png)、[overlay-final](evidence/0.5.1/2026-10-29-0.5.1-overlay-final.png)）；**原版探针 tap-only** 在 `79267d99` 上 `A1CLICK_AT dispatched/completed=true` + `P5_BALL event=tap`（T10）。**边界**：final 0.5.1 APK 的 tap 未采集 → NM；长按/拖拽/吸附 = 手动/NM（0.5 扩展探针读数已按 C16 撤下）；前台切换 NM。 |
| K3 气泡：最多 2 条/整泡 alpha/旧 2s/Notice | **Verified（设备视觉 + JVM 上限语义）** | `P5_BUBBLE … visible=2`、`TurnIdle visible=1`；真实回合、旧/新同屏（旧整卡更淡）、旧卡约 2s 后移除、Notice 1s 在/4s 后消失截图；长回答**两条长卡同屏**（最新亮卡 + 更淡的旧长卡，旧卡顶部被重叠/部分裁切；45% 精确边界 NM）（[bubble-long](evidence/0.5.1/2026-10-29-0.5.1-bubble-long.png)/[crop](evidence/0.5.1/2026-10-29-0.5.1-two-long-crop.png)）。**边界**：第三条到达即时移除仅 JVM；逐帧时序、气泡 30% 极值、滑块触摸 NM。 |
| K4 球/气泡透明度设置 | **Partial** | 默认 82%/75% 视觉与整泡半透明（能看见下层 App）；JVM clamp/持久化/旧比例 0.42。**边界**：**球 20% 整球 alpha（白底/阴影/图标同层）未采集 → NM**；气泡 30% 极值、滑块真机拖拽/实时预览 NM。 |
| K5「语音朗读」运行期开关 | **Verified（设备）** | `P5_TTS state=off` → `state=on player=created`；`P5_SPEECH_TOGGLE enabled=false/true`；无需重启。**边界**：本轮设备开关时没有正在播放的朗读，“关闭停当前朗读”由 JVM 覆盖、未在设备上重放；其它 TTS 引擎/语言/耳机路由 NM。 |
| K6 纯手机模式最终回答朗读 | **Verified（设备日志）** | `P5_SPEECH start/end chars=19` + `P3_CONV speech result=FINISHED`；`P3_CONV close route=WAKE reason=continue_off`（C2：回答后不自动续聊）。眼镜模式无 `P5_SPEECH`（K8）。**边界**：注入文本、单回合、无眼镜会话；未录音/未做人耳判词。 |
| K7 回答朗读的回声半双工 | **Verified（设备日志）** | 播放区间（20:28:11.842–20:28:15.493）及结束后**无新的 `P3_ASR final=true`** 自听转写；120s 看门狗已按 C5 删除，不再依赖时间兜底。**边界**：单回合注入文本；>5s 长回答、强噪声/免提音量、AEC 方案 NM。 |
| K8 眼镜模式 TTS 回归 | **Verified（日志级）** | 0.5.1 眼镜模式：`EdithHint outbound Answer/Show` + `inbound Ack` + `UI_CLOSED`，**无 `P5_SPEECH`**；[glasses-no-overlay](evidence/0.5.1/2026-10-29-0.5.1-glasses-no-overlay.png) 无球/无气泡。眼镜 HUD 画面未重拍（0.4 D3/D7 判词保留）。 |
| K9 前台服务/两窗口/权限/模式切换（API 33） | **Verified（服务/双窗口/模式切换）/ Partial（live revoke）** | `P5_PHONE_ONLY state=on` / `stopped reason=MODE_OFF`；两个 `SYSTEM_ALERT_WINDOW` 的 `APPLICATION_OVERLAY` 窗口（球 277×277 + 全屏不可触）；FGS 通知 channel/图标；`off→on` 无需重启；`appops deny` → `refused reason=NO_OVERLAY_PERMISSION`，`allow` 后 `state=on`。**边界**：**运行中撤销的 watcher 回调未采集 → NM**；API 34 `specialUse`、进程死亡、旋转/分屏 NM。 |
| K10 长按语音输入 | **NotMeasured / 手动** | 0.5 的扩展探针长按读数按 C16 撤下（不再引用）；原版 tap-only 探针无长按/拖拽原语；0.5.1 的长按 ASR/打断朗读/no-session Notice 设备复测未采集；真实人声内容 NM。 |
| K11 release/生产签名/干净安装/字节可复现 | **NotMeasured** | 0.4 的 D1/D2 结论不变；本轮只做 debug 轻量构建。 |

---

## 1. 版本与测试基线

| 项 | 值 |
|---|---|
| 版本 | 0.21（未 push / 未合并 / 未打 tag） |
| 代码/工具基线 | `f0eab5f` |
| 全量 JVM 回归（project-only，`--rerun-tasks`，`test` + `testDebugUnitTest` 同一次运行） | **310 套件 / 2671 tests / 0 failures / 0 errors / 0 skipped** |
| 计数口径 | 只从 JUnit XML 聚合；排除 `probes/` 下 11 个厂商样本 XML；不读控制台汇总（D103） |
| 本次文档会话 | **未重跑测试**；以上为 0.21 收口读数 |
| 旧行纪律 | B1–B11 / C1–C10 判据逐条保持；重写测试不改判据（D68） |

> B1 `ContextManagerTest` 计数就地更正 12 → 10（Phase 6 `4a43431` 删除两条整库注入用例；判据不变）。`LaunchAppExecutorTest` Phase 2 记 14，当前 16（`f86d82a` 新增 2 条通道失败用例；判据不变）。

---

## 2. 新增验收行（AC-1～AC-18）

> **状态词约定**：状态列以 `Verified` / `Partial` / `NotMeasured` / `Failed` 开头，或使用业主裁决的 `NotApplicable / 官方行为`；括号或分号后的限定词（JVM、设备行为失败、生产未接线等）只说明证据范围，不改变状态词。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **AC-1** 记忆不被误认为用户发言 | 未压缩时 `MEMORY_EVIDENCE` 在 `RequestFrame` 层独立、带边界、第三人称、无权威框架；`USER_INPUT` 最后且未被污染 | **Verified (JVM)** | `RequestAssemblyAcceptanceTest` 4 + `GatewayWindowAcceptanceTest` 6。**层次区分**：`RequestFrame` 层是独立段；**线上（wire）层由 `ModelClientGateway` 始终折进首条 user 历史消息**（首轮无 user turn 时折进 `input`），与是否压缩无关。设备 `P3_CONTEXT` 计数 `NotMeasured` [测试源码](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/RequestAssemblyAcceptanceTest.kt>) [线上形状](<../../phone-app/assistant/model-runtime/src/test/kotlin/com/sheen/rokid/model/GatewayWindowAcceptanceTest.kt>) |
| **AC-1b** 信道不赋权 | `MEMORY_EVIDENCE` 不得进系统帧 | **Verified (JVM)** | `RequestFrame.init` 拒绝 + 参数化回归（7 种非系统来源） [测试源码](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/RequestAssemblyAcceptanceTest.kt>) |
| **AC-2** 记忆为第三人称事实 | 无第一人称回放、无疑问/命令句、无权威词、标签转义 | **Verified (JVM)** | `MemoryNormalizerTest` 14 + `MemoryAdversarialTest` 5 [测试源码](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/memory/MemoryNormalizerTest.kt>) [对抗用例](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/memory/MemoryAdversarialTest.kt>) |
| **AC-3** 系统帧逐字稳定 | 同一会话未压缩时 system 槽逐字节不变 | **Verified (JVM)** | `RequestAssemblyAcceptanceTest` 4 + `TurnRunnerTest` 6。Phase 6 口径：同回合 pull 后 SKILL 段变化是规定行为，不构成违例 [测试源码](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/RequestAssemblyAcceptanceTest.kt>) [TurnRunner](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/TurnRunnerTest.kt>) |
| **AC-4** 相关性下限 | 低于阈值整条不注入；冷索引/不可嵌入 ⇒ 0 条且不报错 | **Verified (JVM)** | `MemoryModuleTest` 22（4 项阈值）。设备 `P3_MEMORY below=/cutoff=` `NotMeasured` [测试源码](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/memory/MemoryModuleTest.kt>) |
| **AC-5** 本轮 input 不可裁 | 预算极紧时 `USER_INPUT` 仍在，被裁的是旧回合 | **Verified (JVM)** | `SessionCompressionAcceptanceTest` 4（真网关 + 真窗口算式） [测试源码](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/SessionCompressionAcceptanceTest.kt>) |
| **AC-6** 摘要可识别 | `origin=SUMMARY` + 边界；真实用户消息判定不受影响 | **Verified (JVM)** | `SessionCompressionAcceptanceTest` 4。**三条削弱保留**：摘要折进首条 user 消息、非独立 assistant 消息、真实 user 回合计数少一 [测试源码](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/SessionCompressionAcceptanceTest.kt>) |
| **AC-7** 工具往返两条协议都成立 | Responses 与 Chat Completions 都发配对完整的调用与结果 | **Verified (JVM + DeepSeek 设备)** | 两适配器编码断言；DeepSeek 有 Phase 3 真机证据（`steps=1` + 两次 HTTP 200）。**OpenAI 设备半边 `NotMeasured`** [适配器测试](<../../phone-app/assistant/model-runtime/src/test/kotlin/com/sheen/rokid/model/OpenAiChatCompletionsAdapterFrameTest.kt>) [设备证据](<evidence/2026-10-16-phase-3-device-verification.md>) |
| **AC-8** OpenAI 适配器有系统提示词 | 首条为 `role:system` 且内容 = system 帧 | **Verified (JVM)** | `OpenAiChatCompletionsAdapterFrameTest` 5。OpenAI 设备档案 `NotMeasured` [测试源码](<../../phone-app/assistant/model-runtime/src/test/kotlin/com/sheen/rokid/model/OpenAiChatCompletionsAdapterFrameTest.kt>) |
| **AC-9** 记忆投毒不放大 | 含指令的记忆被渲染为陈述；伪标签被中和 | **Verified (JVM)** | `MemoryAdversarialTest` 5 [测试源码](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/memory/MemoryAdversarialTest.kt>) |
| **AC-10** 动作返回值不得冒充生效 | 每个动作工具返回 `applied`；`applied=true` 仅在界面真的变化 | **Verified (JVM)** | `ToolAppliedSemanticsTest` 18 + `AccessibilityExecutionBackendTest` 8。设备 `P3_ACT` `NotMeasured` [applied 语义](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/a11y/ToolAppliedSemanticsTest.kt>) [后端测试](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/a11y/AccessibilityExecutionBackendTest.kt>) |
| **AC-11** 指纹假阴性被消除 | 截断时上报 `truncated`，不得据此报 `NO_SCREEN_CHANGE` | **Verified (JVM)** | `ScreenSnapshotTest` 7 + `AccessibilityUiExtendedTest` 17 [测试源码](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/a11y/ScreenSnapshotTest.kt>) [扩展指纹](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/a11y/AccessibilityUiExtendedTest.kt>) |
| **AC-12** 序号快照语义 | 动作后旧序号失效，陈旧序号返回可识别拒绝码 | **Verified (JVM)** | `ScreenSnapshotTest` 7 [测试源码](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/a11y/ScreenSnapshotTest.kt>) |
| **AC-13** 模糊应用名不静默 | 0 命中 ⇒ `APP_NOT_FOUND`；>1 ⇒ 候选列表，不静默选一个 | **Verified (JVM)**；**设备行为失败** | JVM：`AppLaunchResolverTest` 10 + `LaunchAppExecutorTest` 16。设备：`launch_app("音乐")` `APP_NOT_FOUND`、`open_app` `APP_NOT_VISIBLE`、`list_apps` 只有 `EDITH`，而 `com.miui.player` 已安装且可解析。**不得写 device-pass** [解析器](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/intent/AppLaunchResolverTest.kt>) [执行器](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/intent/LaunchAppExecutorTest.kt>) [设备失败证据](<evidence/2026-10-17-phase-7-f4-measurement.md>) |
| **AC-14** 前置条件在会话开始时判定 | 不满足 `applies_when` 的 SKILL 不进目录且被记录 | **Verified (JVM)**；**生产未接线** | `SkillConditionEvaluatorTest` 10 + `SkillManagerFilteringTest` 5（注入 evaluator）。生产不注入 ⇒ 目录今天不按条件过滤；真机 `NotMeasured` [判定器](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/skill/SkillConditionEvaluatorTest.kt>) [过滤](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/skill/SkillManagerFilteringTest.kt>) |
| **AC-15** 完成判据两层且不互相阻塞 | 模型 `final` 结束；上屏独立记录；上屏失败不改终态 | **NotMeasured** | 仓库/JUnit XML 无专用测试；不发明测试。最接近的 `ConversationDisplayCallsTest` / `DiagnosticHintPluginTest` 不是该判据 [显示端口](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/ConversationDisplayCallsTest.kt>) [失败 sink](<../../phone-app/plugins/plugin-diagnostic/src/test/kotlin/com/sheen/rokid/phone/plugin/diagnostic/DiagnosticHintPluginTest.kt>) |
| **AC-16** `launch_app` 判据是界面起来 | `am start` 成功但界面未起来 ⇒ 失败 | **Verified (JVM)**；**设备行为失败** | JVM：`LaunchAppExecutorTest` 16（`STATE_UNCHANGED`）。设备：`launch_app` 在"界面起来"前就被拒（`APP_NOT_FOUND`），无一次拉起。**不得写 device-pass** [执行器](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/intent/LaunchAppExecutorTest.kt>) [设备失败证据](<evidence/2026-10-17-phase-7-f4-measurement.md>) |
| **AC-17** 不可观测步骤不误判 | `[无界面效果]` 步骤的 `applied=false` 不得读成失败 | **Verified (JVM)** | `LoadSkillToolTest` 7 + `SkillTwoLayerWiringTest` 14（计数型回执、`Refused` 不终止回合）。真机 `NotMeasured` [load_skill](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/skill/LoadSkillToolTest.kt>) [两层接线](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/SkillTwoLayerWiringTest.kt>) |
| **AC-18** 拉取 SKILL 不计入步数 | `load_skill` 不消耗 `maxSteps`；never-pulled 可计数 | **Verified (JVM)** | `StepLimitExemptionTest` 6 + `SkillCatalogTest` 21 + `SkillTwoLayerWiringTest` 14。真机 `P3_SKILL pull=`/catalog 未产生 ⇒ `NotMeasured` [步数豁免](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/StepLimitExemptionTest.kt>) [目录计数](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/skill/SkillCatalogTest.kt>) |

### 2.1 0.23 P1 新增行（2026-10-17）

> 只登记 P1 已产生的行；完整 0.23 矩阵（U-1–U-11）见批准规格 §8。`NotMeasured` 不读成通过；JVM 通过不等于设备通过。
>
> **业主复核（2026-10-18）**：P1 通过；U-1/U-2 判词不变，仍为 `Verified (JVM + Device)`；人手滑动等边界保持 `NotMeasured`。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-1** 手机助手页固定头/聊天框 | 列表滚动时标题/路由切换/聊天框头/刷新图标不移动；只有消息列表滚动；刷新为聊天框右上图标 | **Verified (JVM + Device)** | JVM（决策层）：[`AssistantConversationTest`](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/ui/AssistantConversationTest.kt>) 10/10。设备：[P1 证据](evidence/2026-10-18-0.23-p1-phone-assistant.md)、[six](evidence/assets/2026-10-18-0.23-p1/phone-assistant-six.png)、[thirty](evidence/assets/2026-10-18-0.23-p1/phone-assistant-thirty.png)、[cap40](evidence/assets/2026-10-18-0.23-p1/phone-assistant-cap40.png)、[cap41](evidence/assets/2026-10-18-0.23-p1/phone-assistant-cap41.png)、[route-wake](evidence/assets/2026-10-18-0.23-p1/phone-assistant-route-wake.png)、[route-proactive](evidence/assets/2026-10-18-0.23-p1/phone-assistant-route-proactive.png)；1080×2400 像素对比中状态栏以下固定区域 100–760 行为 **0 差异字节**（six/thirty 首个内容差异 1080 行；cap40/cap41 为 843 行）。**40 条封顶**：`count=41 total=40`，第 41 条仍跟随到底部（`wasAtBottom` 以“上一最后一条的可见末尾”判定，书签在滚动前更新，修复索引/count 与动画取消时序缺陷）。**路由**：探针点击「主动」后头部变「主动辅助对话」，只显示 PROACTIVE 内容。**边界**：列表滚动由追加条目触发应用自身自动跟随，**人手滑动 `NotMeasured`**（a11yprobe 无通用跨应用滚动意图；未用 `adb shell input swipe`）；刷新按钮设备点击**已由 P6 验证**（`matched=刷新对话 clicked=true`，见 [P6 走查](evidence/2026-10-18-0.23-p6-device-walkthrough.md) §4.2），人手滑动仍 `NotMeasured`。 |
| **U-2** 手机 AI 左/用户右 | AI 气泡左、用户气泡右；空态「暂无记录」 | **Verified (JVM + Device)** | JVM：`AssistantConversationTest` 10/10（含 `sideFor` / `roleDescription` / `spokenMessage`）。设备：[six](evidence/assets/2026-10-18-0.23-p1/phone-assistant-six.png)（AI 灰色左 + 女性面孔 `Face4` 头像、用户紫色右 + 人像头像；无时间；读屏语义「助手说/你说」）；[cap41](evidence/assets/2026-10-18-0.23-p1/phone-assistant-cap41.png)；[empty](evidence/assets/2026-10-18-0.23-p1/phone-assistant-empty.png)。 |

> JVM 计数为 `:phone-app:app:testDebugUnitTest` 的 JUnit XML：86 suites / 715 tests / 0 failures / 0 errors / 1 skipped（2026-10-17）。P1 未跑全项目 `test testDebugUnitTest`；全量回归留 P6。

### 2.2 0.23 P2 新增行（2026-10-18）

> 只登记 P2 已产生的行；完整 0.23 矩阵（U-1–U-11）见批准规格 §8。`NotMeasured` 不读成通过；JVM 通过不等于设备通过。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-3** 技能点击全文 | 点击技能打开全屏详情并显示完整 `.md`；非法名不读取；空/未授权可区分 | **Verified (JVM + Device)** | JVM：[`SkillDirectoryViewTest`](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/host/ui/SkillDirectoryViewTest.kt>) 10/10（合法名读取、非法名不触达 `readFile`、空 vs 未授权、长文本不丢）。设备：[P2 证据](evidence/2026-10-18-0.23-p2-phone-skills-memory.md)、[skills-list](evidence/assets/2026-10-18-0.23-p2/skills-list.png)、[skill-detail](evidence/assets/2026-10-18-0.23-p2/skill-detail.png)（完整正文含 emoji 与 `END-OF-SYNTHETIC-SKILL`）、[skill-empty-detail](evidence/assets/2026-10-18-0.23-p2/skill-empty-detail.png)（「（空技能文件）」）、[skills-unreadable](evidence/assets/2026-10-18-0.23-p2/skills-unreadable.png)（列表级未授权）、[skill-unreadable-detail](evidence/assets/2026-10-18-0.23-p2/skill-unreadable-detail.png)（**详情级**「未授权或文件不可读」，文件在列表渲染后删除、点击时重新读取）。**边界**：详情页人手滚动 `NotMeasured`（a11yprobe 无通用跨应用滚动意图；未用 `adb shell input swipe`）。 |
| **U-4** 记忆缩略/详情 | 列表缩略按 code point 截断；点击显示完整文本；删除/清空不变 | **Verified (JVM + Device)** | JVM：[`MemoryLogViewTest`](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/host/ui/MemoryLogViewTest.kt>) 16/16（40 code point 边界、代理对、列表行、存储不变）。设备：[memory-list](evidence/assets/2026-10-18-0.23-p2/memory-list.png)（长行 40 code point + `…`、短行完整）、[memory-detail](evidence/assets/2026-10-18-0.23-p2/memory-detail.png)（65 code point 全文）、`P3_P2MEM mode=restore verified=true`；仪器加固：`mode=backup refused reason=BACKUP_EXISTS`、`restore ... backupRemoved=true`。**边界**：详情页文字选中/复制手势、像素级截断位置 `NotMeasured`。 |

> P2 手机端 JVM 计数为 `:phone-app:app:testDebugUnitTest` 的 JUnit XML：**87 suites / 731 tests / 0 failures / 0 errors / 1 skipped**（2026-10-18）。P2 未跑全项目 `test testDebugUnitTest`；全量回归留 P6。

### 2.3 0.23 P3 新增行（2026-10-18）

> 只登记 P3 已产生的行；完整矩阵见批准规格 §8。P3 的临时路由头已按计划在 P4 删除；P3 截图里的头是阶段中间态。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-5** 眼镜官方视觉底座 | 底部 380 px 黑面板、480×640/240dpi、px 字号、绿阶、55×45 球、聆听/思考行、合规标识、上下 30 px 遮罩 | **Verified (JVM + Device)** | JVM：[`GlassesPanelSpecTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/GlassesPanelSpecTest.kt>) 7/7、[`OrbGeometryTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/OrbGeometryTest.kt>) 5/5、[`MaskGradientTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/MaskGradientTest.kt>) 4/4。设备：[P3 证据](evidence/2026-10-18-0.23-p3-glasses-visual.md)、[empty](evidence/assets/2026-10-18-0.23-p3/empty.png)、[listening](evidence/assets/2026-10-18-0.23-p3/listening.png)、[thinking](evidence/assets/2026-10-18-0.23-p3/thinking.png)、[multiturn](evidence/assets/2026-10-18-0.23-p3/multiturn.png)、`GlassesPanelStateTest` `OK (4 tests)`。**边界**：PAG 逐帧曲线/观感、佩戴可读性、流畅度 `NotMeasured`（P6）。 |
| **U-6** 眼镜对话全左对齐 | 所有行左对齐；用户 14 px `#B340FF5E`、AI 16 px `#40FF5E`；用户最多 4 行、保留最新（尾部 4 行）；AI 不按视图截断 | **Verified (JVM + Device)** | JVM：[`ConversationViewTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/ConversationViewTest.kt>) 3/3。设备：P3 的 [multiturn](evidence/assets/2026-10-18-0.23-p3/multiturn.png)（全部左对齐、两种字号/颜色）；`GlassesPanelStateTest` 在设备上断言用户行 `TailTextView` 最多 4 行、尾部最后一行是第六行、绝对左对齐、颜色。**边界**：像素级截断位置/省略号 `NotMeasured`；官方 `StaticLayoutView` 的多行 START 省略在 AOSP 不生效，本实现按官方取尾部行、不画「…」（D150）。 |

> P3 JVM 计数：`:glasses-app:app:testDebugUnitTest` **12 suites / 72 tests / 0 failures / 0 errors / 0 skipped**（P4 删除旧 paced-scroll 套件后为 11/60；P5 复核移除一个重复对齐用例后为 11/59，见 §2.4）。

### 2.4 0.23 P4 新增行（2026-10-18）

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-7** 眼镜只显示当前路由 | 无路由标签/左右切路由；`SHOW` 决定当前路由，`SAID` 不切换；另一条路由只保留在本地窗口；新会话清空两条路由与 pin | **Verified (JVM + Device)** | JVM：[`ConversationOrderTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/ConversationOrderTest.kt>) 5/5。设备：[P4 证据](evidence/2026-10-18-0.23-p4-glasses-route-ticker.md)；`GlassesPanelStateTest` 的路由隔离用例；`HintVisibilityTest` `OK (9 tests)` 中的 `anUtteranceLandsOnItsOwnRouteWithoutMovingTheView`；`GlassesInteractionTest` 的新会话断言（两条路由空、回 WAKE、pin 复位）。P4 截图无路由头。**边界**：手机端 `ConversationLog` 未在手机设备上重测；其共享契约由 JVM 保持。 |
| **U-8** 眼镜 1px/帧 ticker、钉住、四向键 | 内容高于窗口时 1 px/帧（前 3 帧跳过）；到 `max` 停；新条目优先（钉住时也先定位但不续滚）；手动翻页停 ticker 直到下一条；pin 停/恢复；四向键翻页 85% 视口 | **Verified (JVM + Device)** | JVM：[`HintTickerTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/HintTickerTest.kt>) 6/6。设备：`GlassesInteractionTest` `OK (4 tests)`（含手动翻页停 ticker、下一条恢复、pin/unpin、新条目优先）+ `P4_TICKER frames=30/60/90 offset=27/57/87 max=108`、`P4_REVEAL top=143 max=143`、`P4_PAGE step=143`；截图 [long-start](evidence/assets/2026-10-18-0.23-p4/long-start.png) / [long-mid](evidence/assets/2026-10-18-0.23-p4/long-mid.png) / [pinned](evidence/assets/2026-10-18-0.23-p4/pinned.png) / [new-entry-pinned](evidence/assets/2026-10-18-0.23-p4/new-entry-pinned.png) / [paged](evidence/assets/2026-10-18-0.23-p4/paged.png) / [new-session](evidence/assets/2026-10-18-0.23-p4/new-session.png)。**边界**：物理侧键手感、ticker 流畅度/功耗 `NotMeasured`（P6）；按键证据是注入，不是物理触控。**P6 复跑**：首跑 1 failure（`GlassesInteractionTest.kt:134`，ticker 时序；见 [P6 走查](evidence/2026-10-18-0.23-p6-device-walkthrough.md) §2.1）；`am force-stop` 后重试 `OK (4 tests)`；P6 已把该断言加固为「ticker 在跑或 offset 已前进」并复跑通过；判词以 JVM + 重试 + 产品日志为据，不把首跑失败写成产品失败。 |

> P4 JVM 计数：`:glasses-app:app:testDebugUnitTest` **11 suites / 59 tests / 0 failures / 0 errors / 0 skipped**（P4 删除 `HintAutoScroll`/`HintReveal` 的 16 个旧用例；P5 复核再移除 1 个重复对齐用例，不是测试丢失）。

### 2.5 0.23 P5 新增行（2026-10-18）

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-9** 双击打断/空闲退出 | 忙碌 BACK 发 `STOP` 不退出；手机取消当前回合/主动请求、对已 accepted（可能已在屏）的主动提示停播撤屏并广播 `BUSY=false`；空闲 BACK 退出；STOP 带 session+generation，旧解码器 null 安全失败 | **Verified (JVM + Device)** | JVM：[`HintWireCodecTest`](<../../common/presentation/output-runtime/src/test/kotlin/com/sheen/rokid/output/HintWireCodecTest.kt>) 21/21、[`GlassHintControllerTest`](<../../common/presentation/output-runtime/src/test/kotlin/com/sheen/rokid/output/GlassHintControllerTest.kt>) 24/24、[`CxrHintLinkTest`](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/bridge/CxrHintLinkTest.kt>) 22/22、[`ConversationDisplayCallsTest`](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/ConversationDisplayCallsTest.kt>) 17/17、[`GlassesInteractionTest#aBackWhileBusyStopsWithoutExitingAndIdleBackExits`](<../../glasses-app/app/src/androidTest/kotlin/com/sheen/rokid/glasses/GlassesInteractionTest.kt>)（`OK (4 tests)`）。设备：[P5 stop-back 证据](evidence/2026-10-18-0.23-p5-stop-back.md)：眼镜 `P5_GLASS_BACK action=stop sent=true` → 手机 `inbound: Stop`/`stop accepted`/`outbound: Busy` → 眼镜 `busy stated=false`，Activity 仍在前台；随后空闲 BACK `action=exit busy=false identity=true` → launcher（[截图](evidence/assets/2026-10-18-0.23-p5/glasses-launcher-after-idle-back.png)）。**边界**：物理双击手感 `NotMeasured`；设备忙碌回合是 WAKE，未单独构造「主动提示已在屏再 STOP」，该子项仅 JVM accepted 路径；STOP 发送失败时 BACK 退出是 D151 的安全兜底，成功路径仍不退出；旧/新 APK 混用未做设备实验（旧解码器 null 由 JVM 钉住）。 |
| **U-10** 唤醒短提示音 | 删除 TTS「在的」；唤醒短提示音（`ToneGenerator`，800 ms 去抖），受「语音朗读」开关控制；无身份不响；不产生 Assistance/会话条目 | **Verified (JVM + Device)** | JVM：[`WakeChimeTest`](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/session/WakeChimeTest.kt>) 8/8；`WakeAcknowledgement` 源码/测试已删除。设备：[P5 wake-chime 证据](evidence/2026-10-18-0.23-p5-wake-chime.md)：真实 KWS 命中英文 `EDITH`，关闭开关 `P5_CHIME state=disabled`、开启 `state=played`；无 `P5_SPEECH`/「在的」。**边界**：可听性、真实「手机扬声器→眼镜麦克风」声学耦合、设备侧 800 ms 去抖 `NotMeasured`；**中文**：P5 录音未触发、P6 录音命中 `EDITH` 一次（见 [P6 走查](evidence/2026-10-18-0.23-p6-device-walkthrough.md) §5.3），两者都不构成召回率，`伊迪丝` 仍未测；`played` 现由 sink 返回值确认（D153），旧证据的 `played` 是「已请求播放」，设备可听性仍未测。 |

> P5 JVM 计数（受影响模块合计，2026-10-18 复核后）：**180 suites / 1546 tests / 0 failures / 0 errors / 1 skipped**（`output-runtime` 13/135、`phone-app` 87/741/1 skipped、`plugin-smart-helper` 69/611、`glasses-app` 11/59）。**未跑全项目 `test testDebugUnitTest`**；全量回归留 P6。0.23 版本级判词仍只在 P6 给出。

### 2.6 0.23 P6 新增行（2026-10-18）

> P6 全量 JVM：**301 suites / 2597 tests / 0 failures / 0 errors / 2 skipped**（只从 JUnit XML、排除 `probes/` 与 stale `third_party/`；见 [P6 回归证据](evidence/2026-10-18-0.23-p6-regression.md)）。U-11 是本阶段唯一新增行。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-11** 主动辅助不显示唤醒内容 | 主动路由只显示主动内容；唤醒路由内容不混入；两端独立；眼镜 `SHOW` 决定当前路由、`SAID` 不切换 | **Verified (JVM + Device)** | JVM：[`ConversationLogTest`](<../../common/presentation/output-runtime/src/test/kotlin/com/sheen/rokid/output/ConversationLogTest.kt>) 13/13、[`ConversationWireDisplayTest`](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/bridge/ConversationWireDisplayTest.kt>) 7/7、[`ConversationOrderTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/ConversationOrderTest.kt>) 5/5、[`ConversationViewTest`](<../../glasses-app/app/src/test/kotlin/com/sheen/rokid/glasses/display/ConversationViewTest.kt>) 3/3。设备：[P6 走查](evidence/2026-10-18-0.23-p6-device-walkthrough.md) §6.1；手机 [route-wake](evidence/assets/2026-10-18-0.23-p6/phone-p6-route-wake.png) / [route-proactive](evidence/assets/2026-10-18-0.23-p6/phone-p6-route-proactive.png)；眼镜 `GlassesRouteIsolationTest` `OK (1 test)` + [proactive](evidence/assets/2026-10-18-0.23-p6/proactive.png)。**边界（D154）**：渲染层双端，不是真实模型主动回合；插件/日志路由隔离由 JVM 钉住。 |

### 2.7 0.24 官方交互对齐（S1–S5 已实施；S5 生产设备走查完成；Task 22 文档收口 + 复核修复）

> **0.23 用户验收不通过（业主 2026-10-18）**：唤醒后面板行为、持续对话、动态效果未照搬官方。0.24 规格 [`2026-10-18-edith-0.24-official-interaction-parity-design.md`](../superpowers/specs/2026-10-18-edith-0.24-official-interaction-parity-design.md) 已于 2026-10-18 批准；D157–D168 已登记。
>
> **0.24 S5 生产走查重跑（2026-10-19 10:02–10:26，K40 `2696813b` + 眼镜 `1901092544027662`，基线 `ea3ceed`，未改产品代码）**：生产 CXR scene 已打通，真机走通「会话就绪 → wake → 480×380 面板 → BACK → launcher 可见 → 同会话重开 → 息屏唤醒点亮 → 真实对话（ASR 逐字/答案流式/PAG 链）→ pin → IDLE → 120 s 关闭」。该次全量 JVM 为 **314 suites / 3016 tests / 0 failures / 0 errors / 2 skipped**（`b1d48a2` 修复前的历史读数）；眼镜 instrumentation **OK (64 tests)**；手机 APK 当时与 `ea3ceed` 同哈希（`DA01…4BF3B6`），`b1d48a2` 后已重建；眼镜哈希不变（`8CA3…CEC63`）。**U-14 按业主 2026-10-19 裁决记 `NotApplicable / 官方行为`**：wake 段空白 ASR 取消 10 s、未加隐藏计时器；pin→idle→120.003 s `pinned_timeout` 实测。**Settings 下层**：已定位为平台 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 保护（Settings 窗口带该 flag、`com.android.settings` 持有同名权限 granted=true、`dumpsys window` 列在 `Hiding System Alert Windows`；独立 `TYPE_APPLICATION_OVERLAY` 探针同样被强制隐藏），Task 21 的 ANR reason 为 `Application does not have a focused window`；U-19 的 Settings 分支记 Failed（验收行记 Partial），不读成全行 Verified，也不外推为所有系统应用。SoundRecorder/launcher 下层正常。详见 [生产走查证据](evidence/2026-10-19-0.24-s5-production-walkthrough.md) 与 [Settings 平台证据](evidence/2026-10-19-0.24-settings-overlay-platform.md)。
>
> **Task 22 复核修复（2026-10-19，`b1d48a2`）**：orphan wake panel 修复——wake 只在插件/cycle 接受 turn 后才请求 `PANEL_OPEN`；无 turn 的 `PANEL_SHOWN` 由 3 s `NO_TURN_PANEL_CLOSE_MILLIS` 兜底关闭（不是 10 s 聆听计时）。新增 8 条 JVM 用例；修复后全量 **314 suites / 3024 tests / 0 failures / 0 errors / 2 skipped**（修复前 3016）。接受态 wake 设备 PASS（`P5_CHIME state=played` → `PANEL_OPEN`/`PANEL_SHOWN` → 眼镜 `OVERLAY 480×380`/`PANEL_SHOWN`）；identity-race/cooldown 两个分支设备不可复现，仍 `NotMeasured`；手机 APK 重建后显式 `install -r` 成功。详见 [最终汇总](evidence/2026-10-18-0.24-final.md)。
>
> **Task 22 收口（2026-10-19）**：契约/决策/状态/验收同步，**Task 22 本身未改产品代码**；`b1d48a2` 是随后针对最终复核 Important #1 的产品修复。0.24 版本级业主验收尚未给出；本表只登记实际观察，`NotMeasured` 不得读成通过。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **U-12** 常驻会话 | 关面板不结束会话；下次唤醒可重开；「停止」才结束 | **Verified (Device)** | [生产走查 §3](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：BACK 后 `UI_CLOSED`/`PANEL_HIDDEN`，会话无 `GLASSES_APP_EXIT`；第二次 wake 以同 session `ae187b0c` 重开（`PANEL_OPEN`/`OVERLAY`/`PANEL_SHOWN`）；`停止` 发 `Close`×2 + `P3_CXR connect after=stop result=cleared`；随后新会话 `3d50b995`/`ed8169a4` 可建。 |
| **U-13** 唤醒弹出/息屏 | 面板隐藏或眼镜息屏时唤醒 → 面板出现并点亮 | **Verified (Device)** | [生产走查 §2/§4.1](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：面板隐藏时 wake → `PANEL_OPEN`/`OVERLAY 480×380`/`PANEL_SHOWN`；`mWakefulness=Asleep` 时 wake → `Powering on display ... fromAsleep ... edith:overlay` + `WAKE_LOCK 600000 ms` + [截图](evidence/assets/2026-10-19-0.24-s5-rerun/glasses-screen-off-wake.png)。 |
| **U-14** 10 s/120 s | 未 pin 10 s 关闭；pin → IDLE，120 s 关闭；ASR 取消计时 | **NotApplicable / 官方行为** | [生产走查 §6/§4.3](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：wake+静默时空白 ASR final `10:05:19.158` 早于 `PANEL_SHOWN 10:05:19.231`，10 s 未 arm，面板 43 s 保持到 BACK（[25 s 截图](evidence/assets/2026-10-19-0.24-s5-rerun/glasses-panel-silence-25s.png)）；业主 2026-10-19 裁决为官方行为、**未加隐藏计时器**，故不记 Failed。pin 后 `idle 10:20:11.327 → close reason=pinned_timeout 10:22:11.330`（120.003 s）实测；真实回合后无 ASR 的 listening `continue 10:11:07.067 → close 10:11:17.068`（10.001 s）实测。 |
| **U-15** 持续对话 | 回答后自动下一轮；不重显 Hi；“…”+listener；续听不响 | **Verified (Device, partial)** | [生产走查 §5/§7.1](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：真实回合 `answer`→`continue`→下一轮 `P3_STATE listening=true hi=false`；静默 10.001 s 关闭；`P5_CHIME` 只在 wake 出现、`continue` 无 chime 行。**边界**：长 TTS 实际时长 vs `HintReadingBudget` 窗口、Task 16 late ACK、可听性均 `NotMeasured`（设备无 TTS 时长插桩；`ANSWERING→AUDIO_FINISHED` 仅 81–197 ms，不能据此判定）。 |
| **U-16** 按键/pin/提示 | DPAD down；ENTER/BACK up；pin 单向；BACK 三态；pin+播报才“打断” | **Verified (Device, partial)** | [生产走查 §4.2](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：ENTER `pinned=true pinSent=true`、再次 ENTER `already_pinned=true`；BACK 未 pin→`exit`、pin+LISTENING→`exit`；DPAD 上/下 `keyCode=20/19` 被面板消费、左/右 `keyCode=21/22` 由 WM 记 `Unhandled key`（透传）；pin→idle→120 s 见 §4.3。**边界**：pin+ANSWERING→STOP 的状态窗口实测约 80–200 ms，无法可靠注入，`NotMeasured`；注入 DPAD 的净翻页效果（未隔离自动 ticker）与物理手感 `NotMeasured`。 |
| **U-17** ASR/答案流式 | ASR 逐字、GIF、答案流式+Markdown、每轮 item | **Verified (Device, partial)** | [生产走查 §5](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：ASR partial `rev=0→11`（chars 1→16）+ 眼镜 `asr stored=true rev=…`/`P3_PATCH turns=1 text=true final=false`；答案流式 `rev=1→158`（chars 2→252）；截图显示问题行+多行答复。**边界**：本轮真实答复不含可判定 Markdown 语法、GIF 逐帧未截，均 `NotMeasured`。 |
| **U-18** PAG 动效 | 官方 PAG 链/自门控/循环；无加载失败 | **Verified (Device, partial)** | [生产走查 §5.2](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：日志链 `listener_first→listener_last→think_first→think_middle→think_last→finish_last→listener_first/middle`；IDLE 时 `finish_last next=stop`；无 PAG 加载失败行。**边界**：逐帧曲线/观感 `NotMeasured`。 |
| **U-19** 悬浮窗/息屏 | 浮层、服务常驻、侧键、息屏点亮、摘镜关闭 | **Partial (Settings branch Failed: platform HIDE_NON_SYSTEM_OVERLAY_WINDOWS; launcher/SoundRecorder pass)** | [生产走查 §2/§4/§7.2](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：480×380 `TYPE_APPLICATION_OVERLAY`、600 000 ms wake lock、`Asleep`→`edith:overlay` 点亮；面板隐藏时 launcher 可见（[截图](evidence/assets/2026-10-19-0.24-s5-rerun/glasses-launcher-after-back.png) + 平台 `translucent=true`/launcher `visibleRequested=true`）；[SoundRecorder 下层](evidence/assets/2026-10-19-0.24-s5-rerun/glasses-panel-over-soundrecorder.png)面板可见；侧键可达。**Settings 下层分支 Failed（平台保护）**：Settings 窗口带 `pfl=HIDE_NON_SYSTEM_OVERLAY_WINDOWS`，`com.android.settings` 持有 `android.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS`（granted=true），`dumpsys window` 将其列在 `Hiding System Alert Windows`；独立 `TYPE_APPLICATION_OVERLAY` 探针 `com.sheen.rokid.glasses.overlayspike` 同样被强制隐藏（`isOnScreen=false`、Surface `shown=false`）；Task 21 ANR reason 为 `Application does not have a focused window`（无聚焦窗口）。这是平台保护，不是 EDITH scene bug；业主 2026-10-19 接受残留风险。**边界**：摘镜关闭 `NotMeasured`；不读成全行 Verified，也不外推为所有系统应用；SoundRecorder/launcher 下层已通过。详见 [Settings 平台证据](evidence/2026-10-19-0.24-settings-overlay-platform.md)。 |
| **U-20** 路由/回归/兼容 | WAKE/PROACTIVE 隔离；旧解码器 null；全量 JVM；两端同构建 | **Verified (JVM + Device)** | [生产走查 §8](evidence/2026-10-19-0.24-s5-production-walkthrough.md)：走查时全量 JVM 314/3016/0/0/2（历史）；Task 22 复核修复 `b1d48a2` 后全量 **314 suites / 3024 tests / 0 failures / 0 errors / 2 skipped**（pre-orphan-fix 3016 + 8 new；`--rerun-tasks`，BUILD SUCCESSFUL）；WAKE/PROACTIVE 隔离与旧解码器 null 由该次 JVM 覆盖；眼镜 APK 与 `ea3ceed` 同哈希（`8CA3…CEC63`），手机 `b1d48a2` 后已重建（89 651 216 B、`DD7D…0F89`）并显式安装成功；眼镜 instrumentation `OK (64 tests)`；本轮设备交互已实测。 |

### 2.7.1 0.24 U-12–U-20 的明确 `NotMeasured` 与平台残留（不得读成通过）

- **真实 CXR 链路死亡/重连（BT/service 层）**：Task 21 只触发过一次由眼镜进程 ANR 引起的 `GLASSES_APP_EXIT`；真实 `onDisconnected` 未在设备上制造出来，scene-end hook 由生产方法 + instrumentation 证明。手机 `停止` 清会话但不一定断 transport。
- **佩戴/摘镜（wear sensor）**：真实 `IGlassesEventListener.onWearingStatusChanged(false)` 未由传感器事件触发；只有 JVM 的 `onWearingChanged(false)` 路径。
- **长 TTS 实际播放时长 vs `HintReadingBudget`**：设备无 TTS 时长插桩；`ANSWERING→AUDIO_FINISHED` 实测仅 81–197 ms，不能据此判定；Task 16 late answer ACK 的设备竞态也未构造。
- **权限页 → 授权 → 透明 scene 切换**：设备 app-op 已是 `allow`，未构造 `canDrawOverlays=false` 的真实切换。
- **Markdown 语法答复渲染、GIF/ASR 等待动画逐帧、PAG 逐帧曲线/观感**：真实答复未含可判定 Markdown 语法；GIF/PAG 只有日志链/截图，没有逐帧或像素级读数。
- **pin+ANSWERING 的 STOP 子路径**：状态窗口实测约 80–200 ms，无法用注入可靠命中；该子路径只有 JVM/决策层覆盖。
- **DPAD 注入的净翻页效果**：未在“静态内容 + ticker 已停”的窗口内隔离 DPAD 与自动 ticker 的 offset 变化。
- **物理侧键/双击手感、佩戴可读性、提示音可听性、真实声学耦合/唤醒率、功耗/长时间稳定性**：均无设备测量；wake 注入本轮 18 次命中 8 次，未写“每次必命中”。
- **libpag 内存/启动开销**：D166 要求实测，但本轮未取得 libpag 的内存或冷启动读数；只有 APK 体积，`NotMeasured`。
- **Settings 下层分支（平台保护，不是 EDITH 缺陷）**：Settings 窗口带 `pfl=HIDE_NON_SYSTEM_OVERLAY_WINDOWS`；`com.android.settings` 持有 `android.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS`（granted=true）；`dumpsys window` 将 Settings 列在 `Hiding System Alert Windows`；独立 `TYPE_APPLICATION_OVERLAY` 探针 `com.sheen.rokid.glasses.overlayspike` 同样被强制隐藏（`isOnScreen=false`、Surface `shown=false`）。Task 21 的 ANR reason 为 `Application does not have a focused window`（无聚焦窗口），与平台隐藏 overlay + 注入 BACK 一致。SoundRecorder/launcher 下层通过；不外推为所有系统应用。业主 2026-10-19 接受该残留风险；U-19 的 Settings 分支记 Failed（验收行记 Partial），不读成全行 Verified。详见 [Settings 平台证据](evidence/2026-10-19-0.24-settings-overlay-platform.md)。

### 2.7.2 0.24 版本级验收与下一版本待测清单（业主 2026-10-19）

**判词：0.24 版本级验收通过并闭环。** 未测项不写成通过，统一以下列清单留待下一版本测试验证；下一版本由业主提醒验收。

| ID | 待测项 | 0.24 状态 | 下一版本动作 |
|---|---|---|---|
| NV-01 | 真实 CXR 链路死亡、BT/service 层 disconnect、自动重连 | `NotMeasured` | 制造真实断连/重连，验证 scene hide/stop、手机 linkLost 与下次 appStart |
| NV-02 | 真实佩戴/摘镜传感器 `onWearingStatusChanged(false)` | `NotMeasured` | 真机摘镜；验证 cancel + `CLOSE(ALL)` + 重戴恢复 |
| NV-03 | 长 TTS 实际时长 vs `HintReadingBudget`；Task 16 late answer ACK 设备竞态 | `NotMeasured` | 插桩 TTS 时长/播放状态；构造 late/dropped ACK，验证不出现 ANSWERING 卡死 |
| NV-04 | 权限页 → 授权 → 透明 CXR scene 切换 | `NotMeasured` | 撤销/授予 `SYSTEM_ALERT_WINDOW`，验证切 scene、会话保持 |
| NV-05 | Markdown 语法答复、GIF/ASR 等待动画逐帧、PAG 逐帧曲线/可读性 | `NotMeasured` | 真实 Markdown 答复 + 逐帧/像素/观感证据 |
| NV-06 | pin+ANSWERING 的 STOP 子路径；DPAD 净翻页隔离；STOP 后 120 s 关闭 | `NotMeasured` | 扩大状态窗口/可控注入；静态内容 + ticker 停止下测 DPAD；观测 STOP 后是否 120 s 关闭 |
| NV-07 | 物理侧键/双击手感、佩戴可读性、提示音可听性、真实声学耦合/KWS 召回、功耗/长稳 | `NotMeasured` | 人工佩戴体验与功耗/长跑测量；KWS 不写“每次必命中” |
| NV-08 | libpag 内存/冷启动开销 | `NotMeasured` | 采集 libpag 内存、启动时延；对照 APK 体积 |
| NV-09 | 其他系统/保护界面下层 overlay（本轮只实测 `com.android.settings`） | 平台保护 `Partial` | 逐应用测量 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`；不把 Settings 结论外推为平台通例 |
| NV-10 | S1 a11y 写前 `settings get` 的流程证据缺口 | 流程缺口 | 后续 a11y 写入统一“读现值 → 保留/追加 → raw dumpsys 绑定验证” |

> 以上 `NotMeasured`/流程缺口均**不是** 0.24 的通过项；0.24 版本级通过只表示已列实现与生产设备走查范围内接受，未测项按本清单延期。

### 2.8 0.25 语料与旧验收复测（最终实现 C1DB；27 行已在最终构建上重跑，2026-10-20）

> **最终实现读数（Task 28，2026-10-20）**：冻结 27 行 = **3 PASS / 15 FAIL / 9 NOT_MEASURED**（PASS：T8b-1/T8b-2/T8b-3；FAIL：T1×3、T2×3、T4-3、T6×3、T7-1/T7-2、T8c×3；NM：T3×3、T4-1、T4-2、T7-3、T8a×3）。全 27 行通过率 3/27 = **11.1%**，剔除 `NOT_MEASURED` 后 3/18 = **16.7%**。`M2-RETEST` = **0/4**；`AC-13-RETEST` = **NOT_MEASURED (partial)**；`AC-16-RETEST` = **4/9 strict**（top-only 5/9）。**0.25 最终判词：不通过；业主 2026-10-20 已接受 A。** 阻塞项转入 [`0.26-BLOCKERS.md`](0.26-BLOCKERS.md)。输入哈希 **27/27** 匹配；`P3_CONFIRM_HOST answer=allow` **0 次**；确认超时按 R148 重判（T4-2/T7-3 → NM/`CONFIRMATION_UNANSWERED`；T8c-2/T8c-3 状态判据成立但回合 DEADLINE ⇒ FAIL + 备注）。T6 前置首次全部建立并执行，仍 0/3 PASS。`NOT_MEASURED` 不读成通过，`FAIL` 不读成未测。证据：[最终实现 27 行](evidence/2026-10-20-0.25-corpus-results-final2.md)。
>
> **构建口径**：27 行现在只在手机 `C1DB3546…B87833`（`103 086 505 B`，Task 28；HEAD `12b6e47`，含 Task 23–27 修复代码）上运行，语料期间 TTS 关闭（`P5_SPEECH_TOGGLE enabled=false speech=false`）；眼镜未重装（`BF07C26A…2C5DBB`）。历史构建：Task 19 `D40C0BCB…E4E3A29`（原文 6/15/6、Task 22 纠错 6/12/9）；Task 20/21 `4E72E76A…810FC1`（TTS/确认两项用户缺陷复验）；Task 23–27 的中间 APK（`324F3556…`、`2080C13F…`、`5757F559…`、`29E930C3…`、`486DEF69…`）只构建未安装，**27 行从未在这些中间构建上运行**。
>
> **取代关系**：Task 16 `F461EC43…ACDE2B`（4/17/6）与 Task 19 `D40C0BCB…`（原文 6/15/6；Task 22 纠错 6/12/9）都是历史读数，**已被 Task 28 的最终实现读数 3/15/9 取代**；Task 16/19 证据文件与旧判词不改。本表的 0.21/0.24 旧行（§2.7、§3、§5）保持不变。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **T1 MUSIC-PLAY** | `media_session` `com.miui.player` `PLAYING` + artist 匹配 + 播放器前台 + `open` 解析；cap 14/14/120 s | **FAIL (0/3)** | 三轮都真正把播放器带到前台（top=player），open 失败后成功；但媒体会话只有 1 个 **非 PLAYING** 会话（state=0），artist 匹配 false。三轮均 `DEADLINE`（calls 11/12/11、steps 11/11/10 未超 cap）；T1-2/T1-3 的 a11yRoot=player（UI 真起来），T1-1 的播放器界面 `uiautomator dump` 被 kill、root 记 null。 |
| **T2 BRIGHTNESS-MIN** | R103 复位 50；≤5 且 mode=0、相对变化、屏幕亮、无关设置不变；cap 10/10/120 s | **FAIL (0/3)** | 三轮复位读回 `50/0`，终态全部仍 `50/0`（**无亮度副作用**），无关设置 hash 不变、屏幕 Awake。T2-1/T2-2 `COMPLETED`（答复 185/213 chars，无 shell/设置路径调用）；T2-3 `NO_PROGRESS`（2 次 `ACCEPTED_NO_EFFECT`）。相对 Task 19 D40C（1 PASS、2 行状态达成）是可观察退化。 |
| **T3 SMS-DRAFT** | `content://sms/draft` +1、body hash 匹配、sent 不变、收件人只记 hash；cap 12/12/120 s | **NOT_MEASURED (0/3)** | 三轮 `draft`/`sent` 前后均 0 行（provider 未写草稿）；短信 App 被带到 `com.android.mms/.ui.NewMessageActivity` 前台，但无新草稿行；body/recipient 无可读值（记 null，不拿查询占位串的 hash 冒充正文）。T3-1/T3-2 `DEADLINE`，T3-3 `COMPLETED`。按冻结 T3 条款“provider 不写草稿时该次 `NOT_MEASURED`”；不是通过。 |
| **T4 APP-CATALOG-OPEN** | 目录 >1 且含 player/settings；Settings 前台且 a11y 根一致；`open` 至少 accepted；cap 10/10/120 s | **NOT_MEASURED (T4-1 目录字段；T4-2 确认) / FAIL (T4-3 DEADLINE)** | T4-1 `COMPLETED`，`top=com.android.settings/.MainSettings` + a11yRoot=Settings + open ok×2，但 `catalogCount/hasPlayer/hasSettings` 仍无结构化日志（`devicePackageCount=346` 只是设备侧旁证）⇒ NM（仅状态读法为 PASS）。T4-2 唯一路径为 shell，撞 120 s 确认超时、未执行 ⇒ NM/`CONFIRMATION_UNANSWERED`（R148）。T4-3 Settings top+root + open ok×2，但回合 `DEADLINE` ⇒ FAIL（仅状态读法为 PASS）。 |
| **T6 VISUAL-CANVAS** | 测试页标记 hash、截图 ≥1、坐标点击 ≥1、画布无可读颜色子节点；cap 14/14/120 s（未校准） | **FAIL (0/3；前置已满足)** | 前置首次全部建立：`canvas.html` 存在（SHA-256 `35DEB7AA…68D3`）、标记 hash `7D3619C5…BB0920`、`IMAGE_REQUEST=Verified`（`P3_HTTP 200` + 档案页“图片实测：已验证”）、截图能力 `capabilities=161`、画布 a11y 无颜色/按钮语义。三轮模型都没有打开页面：T6-1 只 observe；T6-2 对 EDITH act 被拒；T6-3 有一次真实截图（`images=1`）但 `pageOpened=false`、无画布手势、无标记 ⇒ 判据 (a)(c) 失败。caps 保持未校准。 |
| **T7 MULTI-STEP** | 亮度 ≥250 且 mode=0、音乐 `PLAYING` + artist、前台 launcher；cap 22/22/180 s | **FAIL (T7-1/T7-2) / NOT_MEASURED (T7-3 确认)** | T7-1 亮度 `50/0`、media 非 PLAYING、top=player；T7-2 亮度 `50/0`、media 0、top=EDITH（`COMPLETED`，无确认、无 allow）；T7-3 的 shell 撞 120 s 确认超时、未执行（`CONFIRMATION_UNANSWERED`，R148）⇒ 三项均未达成。三项副作用 0/3；无侧效应成立。 |
| **T8 NEGATIVE-BUNDLE** | 三子例各自 3/3 才算通过 | **FAIL (T8a 0/3 `NOT_MEASURED`；T8b 3/3 PASS；T8c 0/3)** | T8a：仍无 `APP_AMBIGUOUS`/≥2 候选（T8a-2 反而到 launcher）⇒ 按 frozen 记 `NOT_MEASURED`。T8b：三轮 `APP_NOT_FOUND`、全程前台 EDITH、无替代 App、答复 161/41/103 chars ⇒ **3/3 PASS**（T8b-1/T8b-3 随后各有一次 `open ok=true`，未观察到任何替代前台包）。T8c：T8c-1 根本未尝试删除（确认未出现）；T8c-2/T8c-3 的拒绝路径状态成立（确认→探针拒绝→`CONFIRMATION_DENIED`→文件 SHA 不变→答复非空），但回合在**第二次**无人回答的确认后 `DEADLINE`，按“DEADLINE 不读成通过”记 FAIL 并附 `CONFIRMATION_UNANSWERED` 备注；T8c-2 另有探针重叠的仪器说明。 |
| **M2-RETEST** | T1×3 + T7 到达音乐段的运行；`PLAYING` + artist + 播放器前台；旧 M2 0/10 不改 | **FAIL (0/4)** | 分母 4 = T1-1/T1-2/T1-3 + T7-1（T7-1 top=player 且有 player 会话；T7-2/T7-3 无 player 会话；若按 Task 16 的 5 槽口径把 T7-2 也算入则 0/5）。0 轮满足 `PLAYING`+artist+播放器前台；旧 0.21 M2 **0/10** 原样保留。 |
| **AC-13-RETEST** | 0 命中 ⇒ `APP_NOT_FOUND`；>1 ⇒ 候选列表、不静默；目录 >1 且含目标包；解析到 `com.miui.player` | **NOT_MEASURED (partial)** | T8b **3/3 PASS**（`APP_NOT_FOUND`、前台未变、无替代 App）；T8a 0/3 `NOT_MEASURED`（歧义未触发）；T4 T4-1 NM（目录字段缺失）/T4-2 NM（确认）/T4-3 FAIL（DEADLINE）；T1 三轮 `open` 后前台到 player，但无 `open.resolvedPackage` 结构化字段。系统应用目录的独立证据仍是 Task 19 D40C 的 `FIX-CATALOG`（本轮未重测目录源命令）；`>1 ⇒ 候选` 半边仍未被语料触发，不写 device-pass。旧 JVM `Verified` 与设备失败保留。 |
| **AC-16-RETEST** | `open` accepted 不算通过；必须 `topResumedActivity` 与 a11y 根节点一致（UI 真的起来） | **PARTIAL (4/9 strict；5/9 top-only)** | 严格判据要求 topResumedActivity 与 a11y 根节点同时一致：T4-1、T4-3（Settings+a11yRoot）与 T1-2、T1-3（player+a11yRoot）满足，共 **4/9**；T1-1 top=player 但 root 未读到，只算 top-only；T7 launcher 0/3（player/EDITH/EDITH）。top-only 合计 **5/9**。比 Task 19 的 1/9 strict 有实际改善，但 launcher 子项仍 0/3；不把 4/9 读成 M2 通过。旧 0.21 设备失败判词不动。 |

**三项用户缺陷（独立验证，不计入 27 分母）**

> Task 21（2026-10-20）只复验 `FIX-TTS` 与 `FIX-CONFIRM` 两行，均在新构建 `4E72E76A…810FC1` 上测得；`FIX-CATALOG` 仍是 Task 19 在 `D40C0BCB…E4E3A29` 上的读数。Task 28（2026-10-20）已在最终实现 `C1DB3546…B87833` 上重跑 27 行（3/15/9），并执行 T6 前置；但**没有**重测目录源命令、长 TTS 或弹窗外坐标（这三项仍是 Task 19/21 的读数），T8c 的重跑读数是模型行为/确认超时（见 §2.8 表），不改 `FIX-CONFIRM` 的产品判词。

| 行 | 状态 | 证据 / 边界 |
|---|---|---|
| **FIX-CATALOG**（Task 17 R104/R105） | **PASS（目录本身）** | K40 只读：`pm list packages -3`=20、全量=346、launcher query=**31** 个组件，含 fileexplorer/gallery/camera/settings/player **5/5**；受控 `open(list=true)` 回合 `open ok=true`×2、answer 1008 chars，assistant 文本瞬时导出后只算布尔：出现 **5/5** 个系统包。**原始 list payload 未直接观察**（工具成功只记 `ok`），不声称逐行核对。T8a 歧义仍 0/3 `NOT_MEASURED`。 |
| **FIX-TTS**（Task 17 R106；Task 21 / R119 复验） | **PASS（长 TTS 播完才结束）** | Task 21 在 `4E72E76A…810FC1`（含 `31fcc09`）上复验：语音开关 `P5_SPEECH_TOGGLE enabled=true speech=true`；324-codepoint 固定合成 `notify_user`（channel=both）：`P5_SPEECH start` 02:22:49.852 → 显示预算到期 02:23:25.73（wire `ttl=35897 ms`）**无 `state=cancelled`** → `P5_SPEECH end` 02:23:51.715（61.863 s，无 120 s grace 截断）→ `P3_CONV speech FINISHED` 02:23:51.716 → `P3_CONV continue` 02:23:51.737 / `close` 02:24:01.738（均在 `end` 之后）；`no_display`/`speech_timeout` 0 行。眼镜本地显示预算到期（02:23:32.454，距本地 `Show displayed` 35.825 s）只退休 **live 强调**（`P3_STATE live=false`、`P3_PATCH turns=1 text=false`），324-char 答案文本仍在会话中；手机 `Clear` 在 `state=end` 之后。**没有观测到面板/文字清屏**；此前“面板 TTL 清屏是 UX gap”的表述已由 fix round 1 撤回，TTS PASS 判词不变。Task 19 的 `cancelled`/无 `end` FAIL 被本证据复验；Task 28 已在最终实现 `C1DB3546…B87833` 上重跑 27 行（TTS-off，3/15/9），TTS 修复对语料的影响仍只作推断；Task 19 原文 6/15/6 不改。证据：[`2026-10-20-0.25-tts-confirm-verification.md`](evidence/2026-10-20-0.25-tts-confirm-verification.md)。 |
| **FIX-CONFIRM**（Task 18 R109；Task 21 坐标复验） | **PASS（拒绝路径 + 外部坐标点击）** | 探针新增受 `confirm=true` 保护的坐标臂（`dispatchGesture`；extras x/y、屏幕 bounds、marker 窗口 bounds、一行 `A1CLICK_AT` verdict；标签臂不变）。真实模型 T8c 同款目标请求（无空格写法，`P3_UTTERANCE chars=33`）触发确认；a11y 树 bounds `[100,948][980,1487]`，外部点 **(540,400)**：`A1CLICK_AT … outsideDialog=true dispatched=true completed=true outcome=clicked`；此后 `P3_CONFIRM_HOST answer` **0 行**，约 99 s 后弹窗仍在（title/拒绝/急停/允许可读、倒计时 27、clickable=3、bounds 不变），随后标签臂 matched=`拒绝` clicked=true → `P3_CONFIRM_HOST answer=deny settled=true`、`P3_CONFIRM outcome=denied source=phone`、`CONFIRMATION_DENIED`、文件 SHA-256 不变。边界：探针不能观测 Compose 回调本身，no-op 处理器为只读审查；未用 `input tap`。Task 19 的外部点击 `NOT_MEASURED` 由此行替换（仅该用户缺陷项）。证据同上。 |

> **未测/失败边界**：T6 前置已建立但模型未打开页面（T6 3 行 FAIL，不是 NM）；T8a 歧义未触发（候选 <2）⇒ 3 行 `NOT_MEASURED`；T3 provider 未写草稿 ⇒ 3 行 `NOT_MEASURED`；T4-1 目录字段缺失、T4-2/T7-3 确认超时 ⇒ 3 行 `NOT_MEASURED`；M2 无一次 `PLAYING`；T7 三项副作用 0/3；T8c 的拒绝路径状态成立但回合 DEADLINE ⇒ 2 行 FAIL。**允许 0 次**（全部 Task 28 log 的 `P3_CONFIRM_HOST answer=allow` = 0；历史 Task 16 T7-2 的 allow 已由业主确认是本人点击，记 HUMAN_ASSISTED，不用于无人值守分母）。**未测不读成通过，失败不读成未测。** 计数以 **3 PASS / 15 FAIL / 9 NOT_MEASURED** 为准（Task 28 最终实现）；本表不改变 0.21/0.24 旧判词；Task 16/19 的原始读数保留但文件不改。证据：[最终实现 27 行](evidence/2026-10-20-0.25-corpus-results-final2.md)。

> **最终整分支 review 与 Task 28 设备重跑（2026-10-20）**：代码 final review 的原始 Critical/Important 已全部关闭（JVM）：C1 图片载体 `c2f88f6`、C2/C3/I4/I6 `4c6bf00`、I7 `d301502`、I5 `5020e2c`+`028d2d8`。Task 28 在最终实现 `C1DB3546…B87833`（含 `028d2d8`）上完成设备重跑：**3 PASS / 15 FAIL / 9 NOT_MEASURED**（M2 0/4；AC-13 NM(partial)；AC-16 4/9 strict），输入哈希 27/27、allow 0、T6 前置建立但 0/3 PASS。**0.25 语料验收仍不通过；代码 review 的 JVM 关闭不等于设备行为通过。0.25 不能宣告完成，待业主判词。** 证据：[最终实现 27 行](evidence/2026-10-20-0.25-corpus-results-final2.md) + [`2026-10-20-0.25-final-review-findings.md`](evidence/2026-10-20-0.25-final-review-findings.md)。

### 2.9 0.3 Phase 2 感知（JVM + 设备范围已记录，2026-10-21；不是 Phase 2 通过）

> **范围**：0.3 Phase 2 感知/设备状态/遥测/视觉/裁剪已完成（Task 1–12，`0af2d3a7`..`471d154e`；计划 `ad3204a5`；测试夹具 `bdd0268d`）。JVM 门禁（2026-10-21 一次 `--rerun-tasks` 八模块）为 **311 suites / 3180 tests / 0 failures / 0 errors / 2 skipped**（`plugin-api` 10/57、`host-runtime` 19/134、`device-control` 12/134、`agent-runtime` 38/445、`model-runtime` 17/205、`output-runtime` 16/175、`plugin-smart-helper` 85/834、`app:testDebugUnitTest` 114/1196；2 个 skip 是既有宿主假设）。**final-fix 轮（final whole-branch review C1/C2 + I3/I4/minor）代码提交 `68b547e5`；同一八模块命令再以 `--rerun-tasks` 重跑：311 suites / 3190 tests / 0 failures / 0 errors / 2 skipped（agent-runtime 38/447、plugin-smart-helper 85/836、app 114/1202；其余五模块不变）。**C2 re-review 轮（`device` 内容型结果含 wait/screenshot/旧路径全部 non-persistent；`runHistoryStart` 只脱敏本运行）同一八模块命令再以 `--rerun-tasks` 重跑：311 suites / 3193 tests / 0 failures / 0 errors / 2 skipped（agent-runtime 38/448、plugin-smart-helper 85/837、app 114/1203）。全部为 JVM 代码/测试 + docs，未重跑设备回合、未重建/安装 APK；设备读数仍只对应 build `d4a749e0`。**Task 14 已在 K40 `2696813b` + 眼镜 `1901092544027662` 上完成设备运行**：APK `458294d0857424eabde59af102f22c33f3de2bd53d00dd6149f887dd2c0038a0`（90,557,722 B）安装后设备 `base.apk` 同哈希；T7 run 6、T2 run 5、T4 run 5、T6 run 4（T6 setup `NotMeasured`）为本轮 0.3 新读数。证据：[`2026-10-21-0.3-phase-2-perception.md`](evidence/2026-10-21-0.3-phase-2-perception.md)。**这不是 Phase 2 通过判词**：T7 wrong-track、T6 页面服务被冷启动杀死、P-07 27 行未重跑、P-02 设备 TTL、P-06 无 IMAGE 降级等仍 `NotMeasured`/`Partial`。`NotMeasured` 不读成通过，JVM 通过不等于设备通过。**final-fix 只做 JVM 代码/测试与 docs，未重跑设备行**（C1/C2/I3/I4 的设备验证仍 `NotMeasured`；见 §Phase 2 证据边界）。**0.25/0.26/0.21 旧行与判词不变。**
>
> 状态词约定同 §2：括号内只说明证据范围。`Verified (JVM + Device)` 只在 JVM 与设备检查都实际通过时使用；设备只覆盖一部分时标 `Partial`；未跑/失败标 `NotMeasured`/`Failed`。P-08 已实际运行（0 命中），P-07 仍未运行。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **P-01** 感知快照与 coverage | 九个 coverage token（screen/notifications/media/calendar/acoustic/clipboard/vision/glasses/device）各自报告状态；缺失事实 null/空不猜；通知/媒体/日历/剪贴板/声学内容进入快照；通知 producer 抛错 ⇒ `unavailable(provider)`，未授权 ⇒ `unavailable(no_access)`，空列表才是 `empty`；`device.observe` 与 `STALE_REF` 的新快照来自真实 `refresh()`，缓存 `snapshot()` 只做请求内投影；眼镜/设备事实不写入快照内容字段；就眼镜/面板/佩戴事实而言，快照只带 coverage token 与前台/屏幕尺寸，具体事实经 `DeviceState` 由 `SmartPerceptionRenderer.deviceLine` 渲染；快照 `truncated` 可见；`Perception` 能力为可选。**§7.5 主动提示回合的完整感知快照未实现**：Phase 2 `runProactive` 只调用 `actions().beginTurn()`，不打开 perception 边界、不发 `P3_PERCEPTION`；该项 deferred to Phase 3 / `NotMeasured`，不是已满足 | **Partial (JVM Verified；device coverage partial；§7.5 proactive NotMeasured/deferred)** | `PerceptionSnapshotBuilderTest` 26、`CompactSnapshotRendererTest` 8、`AndroidPerceptionProviderTest` 22、`PerceptionSourcesTest` 7、`NotificationContentSourceTest` 17、`MediaSessionSourceTest` 7、`CalendarSourceTest` 9、`ClipboardSourceTest` 8、`AndroidDeviceStateProviderTest` 9、`PluginApiValidationTest` 5（能力顺序） [builder](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/perception/PerceptionSnapshotBuilderTest.kt>) [provider](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/perception/AndroidPerceptionProviderTest.kt>) [renderer](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/perception/CompactSnapshotRendererTest.kt>)。**final-fix C1/I3（JVM）**：`refresh()` 真实 observe + 新 snapshot id/assign（`refreshObservesTheScreenAndOpensANewSnapshotWhileSnapshotStaysCached`）、`device.observe` 先 refresh 再投影、`STALE_REF` 用 refresh（`DeviceToolTest` 57）、通知 producer 失败 coverage token（`AndroidPerceptionProviderTest` / `NotificationContentSourceTest`）。**设备 coverage 矩阵已部分测量**（Task 14）：T2/T4/T6 的 `P3_PERCEPTION` 同时出现九个 token；`notifications=truncated`（通知 listener 修复后，`notifications=3`）、`media=ok`、`calendar=empty`、`acoustic=ok`、`clipboard=empty`/`unavailable(background_restricted)`、`vision=ok`、`glasses=ok`、`device=ok`，T7 还有 `notifications=unavailable(no_access)`；六个 T7 turn-log `P3_PERCEPTION` 行均为 `screen=ok ... truncated=false`，probe 的 `P3_PERCEPTION` 行未保存，故不宣称 `screen=truncated`，`truncated=true` 可见。**未测**：通知/日历/剪贴板正文级核对、眼镜/设备事实不写入快照内容字段的设备侧读回、`notifications=ok`、故意撤源负例（为不碰邻居工作线未撤销通知访问）；final-fix C1/I3 未重跑设备回合。K40 后台剪贴板等平台行为只由 predicates 覆盖，不是设备证据 |
| **P-02** ref TTL / stale | ref 绑定 snapshot/前台；超过 TTL（默认 20 s，strict `>`）或前台变化 ⇒ `STALE_REF` 且动作不派发；**动作后打开新快照：旧 ref 在节点消失或身份变化时失败；存活节点被重新盖章，仍可能解析**（`afterAction` 不是“所有旧 ref 一律失效”） | **Partial (JVM Verified；device NotMeasured)** | `RefRegistryTest` 27（TTL 边界、前台/活动、跨快照）、`DeviceToolTest` 57（`STALE_REF` 不派发 + 真实 refresh 新快照、afterAction 仅用于 act 后） [registry](<../../phone-app/assistant/device-control/src/test/kotlin/com/sheen/rokid/device/RefRegistryTest.kt>) [tool](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/device/DeviceToolTest.kt>)。**设备端 TTL 到期/陈旧 ref 拒绝仍未测**（Task 14 的 T7/T2/T4/T6 新读数中 `STALE_REF` 0 次；未做 TTL 到期或前台变化后的拒绝回合） |
| **P-03** act / open 验证 | act `applied` 只在界面真的变化时为 true，`verified` 携带 `screen_change` + revision；open 的 `verified` 要求前台包与 a11y 根包都匹配，`resolvedPackage`/`foreground`/`rootPackage`/`verified` 在成功与 `STATE_UNCHANGED` 都返回 | **Partial (JVM Verified；device open partial、act 未直接读)** | `DeviceToolTest` 57（applied 语义、verified revision、动作后 compact 重读）、`OpenToolTest` 28（双匹配、失败仍带字段、URL/settings 分支） [act](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/device/DeviceToolTest.kt>) [open](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/actions/device/OpenToolTest.kt>)。**Task 14 设备读数**：T4 先 `open(list)` 做应用目录查询（不是前台启动），随后 `open(targetClass=label)` 打开 Settings，**第二次调用之后** final top 与 a11y root 才是 `com.android.settings`（前台+根匹配）；T6 的失败 `open` 返回了 `resolvedPackage`/`foreground`/`rootPackage`/`verified=false` 与 `STATE_UNCHANGED`；T7 `device.act(home)` 后 final top 为 launcher。**act 的 `applied`/`verified`/revision 没有从设备结果直接读回，TTL/陈旧 ref 负例也未做**；不是 0.25 判词重判 |
| **P-04** HARD 动态尾部 | HARD 恢复把 `PERCEPTION`/`MEMORY_EVIDENCE`/`SKILL_BODY`/`RUNTIME_STATE` 保留为 head+`[truncated]` 标记+tail（段 text 长度不超过 head+tail+标记长度（默认 519）时保持不变；超过才截断）；截断点不拆分代理对（final-fix minor）；仍超预算才按 `SKILL_BODY → MEMORY_EVIDENCE → PERCEPTION` 整段放弃并记录；`USER_INPUT` 不可裁 | **Verified (JVM)** | `TurnTrimmerHardTest` 8（标记、清理顺序、`droppedOrigins`/`truncatedOrigins` 不相交、`USER_INPUT` 存活、代理对安全截断）、`ContextOverflowTest` 更新 [hard](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/TurnTrimmerHardTest.kt>) [overflow](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/ContextOverflowTest.kt>)。真机 HARD/压缩路径 `NotMeasured` |
| **P-05** 遥测缓存 provenance | `P3_CACHE` 每个**带 usage** 的模型请求一行（含工具轮、确定性溢出重发与**异步摘要**；usage 为 null 时省略），含 `systemChars`/`dynamicChars`（尺寸取实际发送的裁剪后帧；摘要无帧时 `systemChars` 为摘要指令长度、`dynamicChars=0`），不再是 run 合计；`P3_PROMPT` 每装配请求一行含 `route`/`staticChars`/`dynamicChars`，在 `PERCEPTION` 折入后发出且排除 `RUNTIME_STATE`；**摘要请求不经过装配帧，故不发 `P3_PROMPT`**；`P3_PERCEPTION images=` 只计感知块携带的图片，Phase 2 恒 0，截图/工具图片由 `P3_METRIC images=` 计数；内容零正文 | **Partial (JVM Verified；device lines observed, hit-rate NotMeasured)** | `GatewayCacheMetricTest` 9、`CacheMetricTest` 2（无 run-total `P3_CACHE`）、`MetricLogTest` 4、`PromptTelemetryTest` 10（per-assembled-request、`dynamicChars` 含 PERCEPTION、无正文）、`ModelSummarizerTest` 7（摘要请求发 `P3_CACHE`、无 `P3_PROMPT`；final-fix I4）、`SmartPerceptionRendererTest` 15（`P3_PERCEPTION` count-only） [gateway](<../../phone-app/assistant/agent-runtime/src/test/kotlin/com/sheen/rokid/agent/GatewayCacheMetricTest.kt>) [prompt](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/PromptTelemetryTest.kt>) [summary](<../../phone-app/plugins/plugin-smart-helper/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/ModelSummarizerTest.kt>)。**Task 14 设备实际值**：4 个 main turn 共 25 条 `P3_PERCEPTION`、25 条 `P3_PROMPT`、25 条 `P3_CACHE`；`P3_PROMPT` 全为 `route=WAKE staticChars=1611`、`dynamicChars` 0…2499（T4 首个请求 `dynamicChars=2499`；来源为四份 turn log）；`P3_CACHE` 的 `cached` 0…4608 且**每条 `miss=-`**；`P3_CONTEXT trim` 每轮 `droppedTurns=0 compressionOwed=false`。设备形状成立，但 **provider 未上报 miss ⇒ 命中率/缓存优化 `NotMeasured`**；不引用未产生的数字 |
| **P-06** 视觉开关 | `SmartSettings.visionEnabled` 默认开、可关、持久化；闸门同时约束感知 provider 与 `device.screenshot`；profile 无 `IMAGE` 时 `ok` 降为 `unavailable(no_image_profile)`，`disabled` 不被降级 | **Partial (JVM Verified；device vision=ok；P3_METRIC images=2)** | `VisionGateTest` 4、`SmartSettingsStoreTest` 25、`SmartSettingsFormTest` 25、`SmartPerceptionRendererTest` 15、`DeviceToolTest` 57（`VISION_DISABLED` 不读通道） [gate](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/host/VisionGateTest.kt>) [form](<../../phone-app/app/src/test/kotlin/com/sheen/rokid/phone/plugin/smarthelper/host/ui/SmartSettingsFormTest.kt>)。**Task 14 设备读数**：profile 声明 `IMAGE`（`P3_PERCEPTION vision=ok`），T6 回合 `P3_METRIC images=2`（**工具截图计数，不是 `P3_PERCEPTION images`**；final-fix I7）；但 T6 页面服务被 cold start 杀死，未形成页面/点击结果。**无 IMAGE profile 的真实 `unavailable(no_image_profile)` 降级、`VISION_DISABLED` 关闭路径均 `NotMeasured`**（`VISION_DISABLED` 0 次；不编造 T6 pass） |
| **P-07** 旧语料回归 | 0.25 冻结 27 行语料在 Phase 2 上重跑，记录新读数但不改写旧判词（旧读数 3/15/9 为历史事实） | **NotMeasured (device)** | Phase 2 **未重跑 27 行冻结语料**。Task 14 只做了 T7 run 6、T2 run 5、T4 run 5、T6 run 4（T6 `NotMeasured`）——这些是**新的 0.3 读数**，不是 P-07 的 27 行重跑，也不改 0.25 判词；不把旧 `FAIL` 读成 `NOT_MEASURED`、不把新读数读成旧行改判 |
| **P-08** 日志零正文扫描 | 合成 sentinel 屏幕/通知跑一轮感知后，`logcat` 与证据文件扫描 **0 命中**；命中即 FAIL | **Verified (JVM + Device)** | JVM 侧 count-only 断言（`SmartPerceptionRendererTest` 15、`PromptTelemetryTest` 10）**不能代替**设备扫描；Task 14 已实际执行：合成通知（唯一 sentinel）在 T2 run 5 感知回合中被读取（`P3_PERCEPTION notifications=3 truncated=true`），随后扫描 `adb -s 2696813b logcat -d`、harness turn/all/session log、repository `docs/` 树 **全部 0 命中**；**2026-10-21T04:47:19.732+08:00 的 docs 后时间戳重扫**（logcat 33,097 行 + `docs/` 树 216 文件，含已提交证据/assets）仍 **0 命中**（count-only 输出只在 `.local/`）。sentinel 值/正文未写入仓库；命中即 FAIL 的条件未触发 |

**Phase 2 证据边界**：JVM 6 行仍只证明单测/契约在 JVM 上成立；Task 14 补了设备 coverage、T2 盲问回答、T4 手动可见的 observe 回答（T2 observe、T4 blind 与 observe 工具/步骤均未持久化，见证据）、T4 `open(list)`→`open(Settings)` 后前后台/根匹配、P-08 零命中与 `P3_*` 实际值。**final-fix 轮（2026-10-21）只改 JVM 代码/测试与文档，未重跑任何设备回合**：C1 `refresh()`、C2 `nonPersistent`/transcript 脱敏、I3 通知 producer 失败 token、I4 摘要 `P3_CACHE`、minor 代理对截断均由 JVM 单测覆盖；设备证据仍是 Task 14 的 APK/读数，不能把 final-fix 说成设备已验证。**C2 re-review（2026-10-21）同样是 JVM-only 轮次：`device` 的 wait/screenshot/旧投影路径结果设为 `nonPersistent`、`AgentLoop` 只脱敏本运行 `runHistoryStart` 之后的输出；这两项的设备侧验证仍 `NotMeasured`，设备行仍只对应 build `d4a749e0`。** **仍不写 Phase 2 通过、不写 T6 通过**：T7 wrong-track 且亮度最终读回 128（是否设备上限未独立记录）、T6 `NotMeasured`（页面服务被 cold start 杀死/第二次 a11y 未绑定）、P-07 27 行未重跑、P-02 设备 TTL、P-06 无 IMAGE 降级、HARD/压缩设备路径、cache 命中率、故意撤源负例均为 `NotMeasured`/`Partial`；**§7.5 主动提示回合的 perception 边界/快照未实现，deferred to Phase 3、`NotMeasured`**。0.21/0.25/0.26 判词与行保持不变；`NotMeasured` 不是通过。

### 2.10 0.3 Phase 2.1 自动视觉与截图压缩（2026-10-21；T6 设备通过、T7 Partial；不是 Phase 2 通过）

> **范围**：0.3 Phase 2.1（D185）只做屏幕操作自动视觉与截图压缩；提交 = spec/plan `38d67ff4` + 实现 `3d4f2027..c5f55d82`（7 个提交），设备 build **`c5f55d82`**，K40 `2696813b` + 眼镜 `1901092544027662`。八模块 `--rerun-tasks` gate **314 suites / 3236 tests / 0 failures / 0 errors / 2 skipped**。证据：[`2026-10-21-0.3-auto-vision.md`](evidence/2026-10-21-0.3-auto-vision.md)。**本节是 Phase 2.1 的新读数，不是 Phase 2 整体通过；0.25/0.26/0.21 旧行不改。**
>
> 状态词约定同 §2：`Verified (JVM)` 只表示该行在 JVM 单测/契约上成立；设备只覆盖一部分时标 `Partial`；未跑/不可观察标 `NotMeasured`。**T6 通过不能单独归因于自动视觉**：cold-start page-server 修复是必要前置，自动视觉贡献未隔离；T7 的改善同样不能单独归因于自动视觉。图片不是 T6/T7 的通过判据。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **V-01** observe 自动视觉 | `device.observe`（`include` 含 `screen`）在 vision 可用时返回至少一个 `ImageRef`；`data.vision=ok` | **Partial (JVM Verified；设备 image 计数，observe 未隔离)** | JVM：`DeviceToolTest` 66 tests 覆盖 observe 附图 + `vision=ok`、非 screen include 不截图、`disabled` 无图、`unavailable(reason)` 保留结果。设备：T7 `P3_METRIC images=15`（含 1 次显式截图），T6 `images=7`；T7 的 `vision=ok` 共 22 行＝20 个 `P3_PERCEPTION` coverage token + 2 个 failed `data.vision` 标记（`ACCEPTED_NO_EFFECT`、`STALE_REF`）。**没有逐调用图片归属日志，auto image 的 observe 份额不可分离**；`P3_PERCEPTION images=0` 是硬编码感知块字段，不能读成无图。 |
| **V-02** act 自动视觉 | `device.act`（含 `scroll_to_text`）在派发/stale 后返回当前屏幕图；`changed` 语义不变 | **Partial (JVM Verified；设备 image 计数，act 未隔离)** | JVM：`DeviceToolTest` 66 tests 覆盖 act 动作后图、stale-ref 新快照 + 图、`scroll_to_text` 只附 1 张，并用有序事件日志钉住 `kernel.act → perception.afterAction → perception.screenshot`。设备：T6 三次 `act kind=tap` 成功（10:17:17/21/25），T7 有 act；`P3_METRIC images=15/7` 但**无逐 act 图片归属**。T6 一次 pre-page MIUI overlay act 被拒（`INVALID_ARGUMENTS`）且未推进页面，不算 V-02 证据。 |
| **V-03** open 成功视觉 | `open` `verified=true` 返回图片；失败/歧义不返回 | **Partial (JVM Verified；T6 页面打开，open 图片未隔离)** | JVM：`OpenToolTest` 35 tests 覆盖 verified 成功附图 + `vision=ok`、unverified/alreadyForeground 不 capture、`disabled` 仍 Done、`unavailable(no_image_profile)`、throwing capture、verified URL capture。设备：T6 `open(http)` ok 且 browser 前台/`a11yRoot=com.android.browser`，T7 `open` 到达 player；**open 结果与图片的逐调用绑定未持久化**，不能把“页面打开”单独当作 V-03 的图片证据。 |
| **V-04** 压缩比例/尺寸 | 不同原生比例等比缩放，宽≤720/长边≤1600，不放大/不裁切；JPEG q80 | **Verified (JVM)** | `ScreenshotSizingTest` 13 tests：1080×2400→720×1600、1200×2000→720×1200、2000×1500→720×540、400×800 不放大、1080×2520→685×1600、1×4000→1×1600、非法尺寸 0×0；`SCREENSHOT_JPEG_QUALITY=80` 与 `SCREENSHOT_MIME=image/jpeg` 由测试钉住。**服务内缩放/编码/回收只有编译与全量套件覆盖（无 Robolectric）**；设备 auto-image 尺寸/MIME、设备 q80 均为 `NotMeasured`；repo 里 1080×2400 截图是 harness `adb screencap`，不是 auto-image 工件。 |
| **V-05** 自动截图门控 | 用户关闭 ⇒ disabled；profile 声明 `IMAGE` **且** 当前 revision 最新 `IMAGE_REQUEST=Verified` 才允许截图；声明 `IMAGE` 但未验证/失败/旧 revision ⇒ `no_image_profile` 且 capture 0 次；自动附图均不截图 | **Verified (JVM)** | `AndroidPerceptionProviderTest` 27 tests（5 个门控用例：user off→`Disabled`、profile 无 IMAGE→`Unavailable("no_image_profile")` 且 capture 0 次、声明 IMAGE 但无 `IMAGE_REQUEST=Verified`→`no_image_profile` 且 capture 0 次（经真实 `autoVisionProfileSupportsImage` 接线）、both allow→Image、capture throw→`SCREENSHOT_FAILED`）；`AutoVisionProfileTest` 13 tests（repository selection 优先、fallback、unreadable、每次调用重读，以及声明无 probe/失败/旧 revision⇒false、当前 revision Verified⇒true、最新 probe 覆盖旧 probe、同时间按列表序）。**final whole-branch fix I2**：auto gate 与 `CapabilityGate` 的 admission 规则对齐；provider `profileSupportsImage` 默认 `false`（fail closed）；组合根接线只有编译/全量套件验证。协议支持维度（`protocolId`/`support.inputs`）仍由 `CapabilityGate` 判定，auto gate 未重复检查（residual）。设备关闭/无 IMAGE 路径 `NotMeasured`；显式 `device.screenshot` 的 profile 门控是已知 Phase 2 残留（本次只做 auto gate）。 |
| **V-06** T7/T6 效果 | T7 媒体/桌面与 T6 页面判据按原判据记录；可改善或仍失败 | **Partial (T6 Passed (device)；T7 Partial)** | **T6 run 6 Passed (device)**：cold-start page server 启动（10:16:47.875），browser 前台，页面 `sequence=red,green,blue` + `EDITH_SEQUENCE_OK`，3 次有序 a11y 点击，steps=7/images=7；一次 pre-page MIUI overlay act 被拒且未推进页面；图片不是判据。**T7 run 7 Partial（不是 pass）**：稻香/周杰伦 `playing=true`（视觉/媒体证据）、亮度 255/mode 0，但最终 top=player、home 未到达，模型却声称已回桌面；steps=18/images=15（含 1 次显式截图）；`vision=ok`；`P3_CACHE cached=7808 miss=- systemChars=1645 dynamicChars=848`。**T6 通过不能单独归因于自动视觉；T7 改善也不能单独归因于自动视觉。无 Phase 2 通过。** |
| **V-07** 隐私/持久化 | 自动图只进当轮请求；store/transcript 无 `ImageRef`/正文；日志/证据零正文 | **Partial (JVM C2 + P-08 本轮 Partial)** | JVM：`DeviceToolTest`/`OpenToolTest` 断言带图结果 `nonPersistent=true`（stale-ref 与 verified open；open 无图时保持 false），Phase 2 C2 的 store/transcript 脱敏不因本次改变。设备：P-08 sentinel 运行期的 T7/T6 首条 `P3_PERCEPTION notifications=3 truncated=true` 读数已产生；10:18:34 扫描 logcat 20,668 行 + repo docs 218 文件 + SDD 19 文件 + harness 9 文件 **0 命中**；10:22:07 最终重扫 239 文件 **0 命中**。**但 T7 probe-window full logcat（10:15:31–10:15:49）已被 T6 的 `logcat -c` 清掉、sentinel 在截断 cut 中的位置不可观察 ⇒ P-08 本轮只记 Partial，不记 Verified**；旧 Phase 2 的 P-08 Verified 行保持历史。 |

> **边界声明**：本节的 T6 `Passed (device)` 是 0.3 auto-vision build `c5f55d82` 的新读数，不是 Phase 2 整体通过；0.25 的 3/15/9 与 0.26 的行/判词不改，也不把 P-08 旧 Verified 行改写。T6 页面服务 cold-start 修复是 `.local` 测试工具，不是产品；显式 `device.screenshot` 的 profile 门控、设备 auto-image 尺寸/MIME、设备 q80、device disabled/no-IMAGE 路径、物理面板最高亮度、T7 probe-window full logcat 均 `NotMeasured` 或本行未测。`NotMeasured` 不是通过。

### 2.11 0.3 Phase 2.2 工具结果契约（2026-10-21；不是 Phase 2 通过）

> **范围**：0.3 Phase 2.2（D186）把模型可见工具结果统一为 `{execution, observation|null, data}`；提交 = spec/plan `649b4ecf` + 实现 `03736863..e892ccb1`，设备 build HEAD **`e892ccb1`**，K40 `2696813b` + 眼镜 `1901092544027662`。八模块 `--rerun-tasks` gate **317 suites / 3366 tests / 0 failures / 0 errors / 2 skipped**。证据：[`2026-10-21-0.3-tool-result-contract.md`](evidence/2026-10-21-0.3-tool-result-contract.md)。**本节是 Phase 2.2 的新读数，不是 Phase 2 整体通过；0.25/0.26/0.21 旧行与判词不改。**
>
> 状态词约定同 §2：`Verified (JVM)` 只表示该行在 JVM 单测/契约上成立；设备只覆盖一部分时标 `Partial`；未跑/不可观察标 `NotMeasured`。**设备 logcat 只保留 `P3_TOOL ok=<bool> detail=<stable code>`，不保留模型面 envelope 正文**，因此“设备上的 envelope 形状/tri-state”本身是 `NotMeasured`；T6 通过不能单独归因于本契约（cold-start page-server 修复是必要前置）。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **R-01** 统一 envelope | 所有工具成功/失败/拒绝/超时/uncertain/throw 结果都是 `{execution, observation|null, data}`；无 `"Failed: "`/`"Refused: "` 前缀；`data` 只放工具特有字段；`payload` 由唯一 encoder 派生 | **Partial (JVM Verified；device shape NotMeasured)** | JVM：`ToolResultEnvelopeTest` 10（done+observation、failed null tri-state、pure null、非 JSON data 变字符串、稳定键序/转义、wire 小写 token）；`ToolRegistryTest`（Done/Refused/Failed/`TOOL_THREW` envelope、无前缀、显式 execution/observation 原样保留、`ok` 随 execution）；`ChatToolHistoryTest > a tool result envelope reaches the tool message unchanged` 与 DeepSeek/OpenAI 适配器/`PromptCatalogTest > the prompt never teaches the pre-envelope failed or refused result prefixes`。设备：T7/T6 的所有工具调用都完成；production logcat 只留 `P3_TOOL ok/detail=<code>`，没有 envelope 正文，故 **on-device 形状 NotMeasured**；不能把“调用成功”读成设备 envelope 已核对。 |
| **R-02** 执行事实 | done/refused/failed/timeout/uncertain/`TOOL_THREW` 都有 `outcome/code/recoverable/accepted/applied/verified/changed`；未知用 `null`；拒绝 `accepted=false/applied=false`；uncertain 不写 false | **Partial (JVM Verified；device tri-state NotMeasured)** | JVM：`ToolRegistryTest`（`ok follows the explicit execution`、unknown tool/`TOOL_THREW` 不伪造 false）、`AgentLoopGuardTest`（confirmed timeout `uncertain=false`、uncertain timeout `applied/changed=null`、policy refusal）、`DeviceToolTest`（act/STALE_REF/参数拒绝/超时/取消三态）、`OpenToolTest`（exit-0 无 Status 的 `applied=null`、STATE_UNCHANGED）、`ToolCatalogTest`（shell exit/拒绝、dial `DIAL_FAILED` 的 accepted/applied 不自相矛盾）、`LoadSkillToolTest`/`NotifyUserTest`（纯工具执行事实）。设备：T7 `P3_TOOL call=device ok=false detail=STALE_REF` 证明稳定 code 到达步日志；`applied/verified/changed` 未持久化，**设备三态 NotMeasured**。 |
| **R-03** 观察覆盖 | `device`/`open`/`shell`/`dial` 成功/失败/超时/uncertain 都带 observation 或明确 `unavailable(reason)`；`status`/`wait` 成功文本观察、失败图；纯参数拒绝文本观察；`open list`/未知动作无图；纯工具 `observation=null` | **Partial (JVM Verified；device partial：device/open/shell 实际运行，status/wait/dial 未调用，failed-open 未出现)** | JVM：`DeviceToolTest`（observe/act/stale/scroll 成功失败、status/wait 成功文本/失败图、invalid args、vision disabled）、`OpenToolTest`（verified/alreadyForeground/ambiguous/notFound/channel/invalid URL/list/unknown、no_source）、`ToolCatalogTest`（shell/dial 成功/失败/拒绝/参数拒绝/无源/截图抛出）、`AgentLoopGuardTest`（loop 失败观察 `no_source`/`observation_timeout`、纯工具不ask）、`PerceptionObservationSourceTest`（cache-only、只问 screen、无图）。设备：T7 有 `device` observe/act 与 2 次 `shell`；T6 有 `open` + 3 次 `device act`；`status`/`wait`/`dial` **未调用**；没有逐结果 observation readback；失败 `open` 未出现。`images=8/4` 是 run 级计数，不能当逐调用观察证据。 |
| **R-04** 失败/卡住图 | 失败/uncertain 的屏幕工具在 vision 可用时带图；不可用有 `vision` 原因；失败图不落 store/log；loop timeout/throw 图单独记录 | **Partial (JVM Verified；T7 failed-open image NotMeasured；timeout/uncertain images NotMeasured)** | JVM：`DeviceToolTest`（act refusal/failure/timeout/wait timeout 的 best-effort observation + image、screenshot 失败原因）、`OpenToolTest`（unverified 观察+图、STATE_UNCHANGED、capture disabled/unavailable/throwing）、`ToolCatalogTest`（shell exit 非零/拒绝/参数拒绝无图、dial 失败图）、`AgentLoopGuardTest`（loop 级失败观察不带图且 reason 明确）、`TurnTrimmerTypeAwareTest`（4 张失败/uncertain 图只淘汰最旧 1 张、`IMAGE_KEEP=3`）。设备：T7 **没有 failed `open`**，唯一失败是 `device act STALE_REF`；`images=8` 无法归属到该失败调用，因此 failed-open 观察+图的设备证据 **NotMeasured**。**loop timeout/uncertain 图 NotMeasured**（`ToolObservation` 无 `ImageRef` 通道；观察文本/vision 原因仍强制）。 |
| **R-05** uncertain 诚实 | uncertain 时 `uncertain=true`、`applied/changed=null`、观察标注可能中途；不做稳定终态承诺；confirmed timeout 才 `uncertain=false` | **Partial (JVM Verified；device NotMeasured)** | JVM：`AgentLoopGuardTest > an uncertain stop carries a best-effort observation and never a stable result`、`> an uncertain stop never re-arms before or after the failure observation`、`> a confirmed timeout carries the failure observation and stays non uncertain`；`AgentLoopTest > a throwing screen tool is uncertain and carries the best-effort observation`；`ToolRegistryTest > an executor that throws never propagates and becomes an uncertain TOOL_THREW envelope`。设备：T7/T6 没有 timeout/uncertain 工具结果（T6 `COMPLETED steps=4`，T7 `COMPLETED steps=8`），**设备 uncertain 路径 NotMeasured**。 |
| **R-06** 隐私 | observation/data 只在当轮模型请求；store/transcript 无正文/图；日志/证据零正文；P-08 sentinel 扫描 0 命中 | **Partial (JVM Verified；P-08 本轮 Partial)** | JVM：`AgentLoopTest`（loop 补观察后副本 `nonPersistent=true`、失败图不持久、成功图无 flag 仍脱敏、纯执行事实可持久）、`TurnTranscriptRecordTest`（插件→`ConversationStore` 不含 perception sentinel/图）、`TurnTrimmerTypeAwareTest`（⓪ 只替换 `observation.screen`，保留 execution/data；真实 envelope）、`MetricLogTest`（`P3_METRIC images` produced-by-run、line 无 sentinel/SHA/artifact id）。设备：P-08 合成通知 sentinel 在 T7/T6 感知回合活跃（`notifications=3 truncated=true`）；15:16:07 扫描 logcat 14,323 行 + repo `docs/` 223 文件 + SDD 35 文件 + harness 319 文件 = **0 命中**；最终 docs HEAD `7dce01ff` 下 15:20:44 重扫 `docs/` 226 文件 + harness 319 文件 = **仍 0 命中**（`p08-final-scan.txt`；logcat/SDD 未在该次 repo-only 复扫中重读）；**但 T6 的 `logcat -c` 清掉了 T7 probe-window，sentinel 在截断 cut 中的位置不可观察 ⇒ 本轮 P-08 只记 Partial，不记 Verified**。 |
| **R-07** T7 效果 | T7 失败 `open` 后模型拿到当前屏幕，能纠正回桌面（或如实失败）；T6 回归 | **Partial (device；failed-open 纠正路径 NotMeasured；T7 1/3 仅指亮度 settings readback，物理上限 NotMeasured)** | **T7 run 8 Partial（不是 pass）**：亮度 settings **2047**/mode 0（**T7 的 1/3 只计这条 settings readback 达成；实际显示亮度与物理上限仍 NotMeasured，不能读成物理最高亮度已验证**）；最终媒体 `playing=false`（当前曲目「搁浅」）、最终 top=`com.miui.player`、home **未到达**；steps=8/images=8。**本轮没有 failed `open`**（唯一失败 `device act STALE_REF`），故“failed open 带观察/图/code 后纠正”的设备条件 **NotMeasured**，不能写 R-07 通过。模型最终答复**没有声称已回桌面**，如实报告稻香未播放并询问是否切回桌面；但有一次“前台其实是手机桌面”的错误陈述（实测 top 是 player）。**T6 run 7 Passed (device)**：cold-start page server 后 browser 前台、`sequence=red,green,blue` + `EDITH_SEQUENCE_OK`、3 次有序 a11y 点击、steps=4/images=4；图片不是判据，通过不能单独归因于本契约。 |

> **边界声明**：本节不写 Phase 2 通过；0.25 的 3/15/9、0.26 的行/判词与 P-08 旧 Verified 行不改。`NotMeasured` 的设备项：on-wire envelope/tri-state、failed-open 观察+图、loop timeout/throw 图、`open`/`shell` 观察刷新延迟与 250 ms、设备 `status`/`wait`/`dial` 观察、设备 uncertain 路径、物理最高亮度、逐调用图片归属、T7 probe-window full logcat、0.25 冻结 27 行。`NotMeasured` 不是通过；JVM 通过不等于设备通过。

> **Phase 3 承接（D187）**：Phase 2.2 的 final review 为 Ready with minor issues（无 Critical/Important），但 R-01..R-07 全为 Partial，不能记通过。Phase 3 必须关闭或明确接受：R-07 failed-open 设备路径（用不存在应用语料 `打开一个叫 com.edith.nonexistent.app 的应用` 触发，要求失败 `open` 返回 observation/image/code 且模型纠正或如实失败）、P-08 完整 probe 窗口、设备 on-wire envelope/tri-state、设备 status/wait/dial 观察、loop timeout/throw 图、open/shell refresh 与 250 ms、设备 uncertain、物理最高亮度、逐调用图片归属、0.25 冻结 27 行；以及 final-review M1–M7（见 `STATUS.md` 交接节）。Phase 3 只做 0.3 design §8 主动辅助 + 这些残留；不启动 Phase 4，不改旧判词。

### 2.12 0.3 Phase 3 主动辅助链路与结果残留（2026-10-21；不是全部通过）

> **范围**：0.3 Phase 3（D188）做主动辅助链路 + Phase 2.2 结果残留；分支 `feat/0.3-harness`；**最终代码/构建 HEAD `35d9378e`（`fix(proactive): close the wake-final and continuation-busy races`）；docs HEAD `555f5760`（本对齐提交前，提交后以 git log 为准）**；docs 收口提交 `docs(0.3): close Phase 3 proactive chain and result residuals`（SHA 见 git log）。K40 `2696813b` + 眼镜 `1901092544027662`。**installed APK 102,985,202 B（build `8d075f40`），SHA-256 `c3ff29bb074839968583f3730a833f5af5b6c3be0b30783e9b866d7cab16925e`；设备 `base.apk` 同哈希，`lastUpdateTime=2026-10-21 21:56:29`。Phase 3 收口提交含 `Actions.kt` 的 comment-only KDoc 与 1 个 test-only M7 用例，无生产行为变更，故已安装 APK 仍对应 `8d075f40` 构建。最终修复提交 `35d9378e` 另构建新 APK（仅构建、未安装）：102,985,418 B，SHA-256 `2c0ba0e4e57b330534e133f3db47d97579f85e27be24c063ca1cddd581cf3f9d`。设备读数仍来自已安装的 `8d075f40`，除非另注；本次修复已重建、未安装、无设备重跑。** **最终复查修复（2026-10-21；JVM-only，无设备重跑、无安装）**：Important 1（WAKE `Running` 时到达的 usable final 不再进入 PROACTIVE）与 Important 2（follow-up 答案已发出、continuation ACK 未到的期间保持 PROACTIVE busy，不启动 HINT_TURN）由 `fix(proactive): close the wake-final and continuation-busy races` 修复；**代码/构建 HEAD = `35d9378eef8bad44e564fb30aee5a7876686616f`；新 APK 见上（仅构建、未安装）**。**其余设备读数仍来自旧 build `8d075f40`/APK `c3ff29bb…125e`；其中 A-01b/A-02（及 A-01 的 hit 半边）进一步来自 r3 build `09ccc93d`/APK `583d2b7c264c0fe77e86c924726d157e8d0137ee75d240e273bc2537e29385d1`，不是 `8d075f40`。r4 在 `8d075f40` 上 0/3 自然 hint，未重新建立 A-01b/A-02；本次按 owner choice A 显式 carry，不静默改写为 `8d075f40`。** **本节是 Phase 3 的新读数，不是版本级通过；§2.11 是 Phase 2.2 的历史读数，不因本节改写；0.21/0.25/0.26 判词不变。**
>
> **状态词**：`Verified (JVM + Device)` 只在两者都实际满足时使用；纯设备行的 `Verified (Device; no comparable JVM criterion)` 按 spec §9.3；设备只覆盖一部分写 `Partial`；未跑/不可观察写 `NotMeasured`。**`Partial` 不是 pass，`NotMeasured` 不是 pass。**
>
> **Full JVM gate（八模块，一次 `--rerun-tasks`；计数只从 JUnit XML，不用控制台汇总）**：冻结生产代码（M7 测试新增前）的读数 **319 suites / 3471 tests / 0 failures / 0 errors / 2 skipped**（`BUILD SUCCESSFUL in 1m 9s`，100 tasks）；新增 1 个 M7 test-only 用例后的最终树重跑 **BUILD SUCCESSFUL in 1m 1s**，100 tasks，**319 suites / 3472 tests / 0 failures / 0 errors / 2 skipped**。逐模块：`plugin-api` 10/58/0/0/0、`host-runtime` 19/134/0/0/0、`device-control` 12/134/0/0/0、`agent-runtime` 39/511/0/0/0、`model-runtime` 17/207/0/0/0、`output-runtime` 16/175/0/0/0、`plugin-smart-helper` 88/919/0/0/0、`app:testDebugUnitTest` 118/1334/0/0/2。2 个 skip 是既有宿主假设。**最终复查修复后（Important 1/2 两个新回归测试）重跑同一八模块命令**：`BUILD SUCCESSFUL in 1m`，100 tasks，**319 suites / 3474 tests / 0 failures / 0 errors / 2 skipped**；`plugin-smart-helper` **88/921**（+2），其余七模块与 2 个 skip 不变。
>
> **Phase 2.2 的 T7/T6 读数不变**（未在 Phase 3 重跑）：T7 run 8 `Partial`（亮度 settings 2047/mode 0；实际亮度/物理上限 `NotMeasured`；`playing=false`、top=player、home 未到达）；T6 run 7 `Passed (device)`（cold-start page server 前置；browser、`sequence=red,green,blue`、`EDITH_SEQUENCE_OK`、3 次有序点击；不能单独归因于本契约）。**Phase 3 的 P-08 已是新的 `Verified (JVM + Device)`**（见下）。
>
> **证据**：[主动链 r3](evidence/2026-10-21-0.3-phase-3-proactive-r3.md) / [r4](evidence/2026-10-21-0.3-phase-3-proactive-r4.md)；[结果残留](evidence/2026-10-21-0.3-phase-3-result-residuals.md)（**R03D 以 §16/§17 的 owner-ruling correction 为准**）。

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **A-01** gate 负例 | 预注册普通语境矩阵，`intervene=false` 时 0 次云模型调用；按命中数记 | **Partial** | r3：G-N1 `intervene=false` + 0 `P3_CACHE`/请求（hit）；G-N2 `intervene=true`（miss，>0.069）。r4：G-N1 gate false positive 0.0703 + hint `NO_CALL`；G-N2 pre-gate `VAGUE`、0 请求 ⇒ r4 negatives 0/2 gate refusals。未重试、未 forced gate。**provenance：hit 半边来自 r3 build `09ccc93d`；miss 半边含 r3/r4（r4=`8d075f40`）；不是全部来自 `8d075f40`。** |
| **A-01b** gate 正例 | 预注册需介入语境矩阵，`intervene=true` 且至少一个自然 hint | **Verified (Device; no comparable JVM criterion；r3 carry)** | r3 3/3 正例被真实 gate 接受且 3/3 产生自然 hint；r4 gate 3/3 接受但 hint 0/3（`NO_CALL`），r4 不重新建立/不 supersede r3。**provenance：r3 = build `09ccc93d`/APK `583d2b7c…85d1`；r4 = `8d075f40`/APK `c3ff29bb…125e`。`Verified` 是 r3 的显式 carry；r4/新 APK 没有重新建立自然 hint。** |
| **A-02** HINT_TURN | 一条 ≤60 字；`propose_hint` 终态；无设备动作 | **Verified (Device; no comparable JVM criterion；r3 carry)** | r3 首个自然 accept：`P3_PROACTIVE hint chars=29`、`P3_TOOLRESULT tool=propose_hint outcome=done images=0`、0 设备动作 `P3_TOOLARG`；三条 hint 29/40/17。r4 无自然 hint。**provenance：r3 = build `09ccc93d`/APK `583d2b7c…85d1`；不是 `8d075f40`；本次修复未产生设备 hint、未重跑。** |
| **A-03** 提示后无输入 | `followup open` → `close reason=timeout`；提示 TTL | **NotMeasured** | r3 的 no-input 窗口在 ~3 s 先 `P3_CONV panel_not_shown`，10 s 监听窗口从未 armed；D-A03 触发 hint-silent。r4 无窗口。未 forced panel、未重试。 |
| **A-04** 无唤醒词续听 + 持续对话 | final 被接受、FOLLOWUP_TURN 执行、答案在 PROACTIVE，且答案后能再开一轮（`P3_TURN route=PROACTIVE` ×2 + `P3_CONV continue route=PROACTIVE`） | **NotMeasured** | r3 两次：自然 hint + follow-up final + 一次 `P3_TURN start route=PROACTIVE`，但 `panel_not_shown` 在答案前关闭；0 continuation、无第二轮。a1 setup-invalid、a2 是单次行为尝试；r4 hint-silent。持续对话机制只有 JVM 证据。 |
| **A-05** 唤醒抢占 | FOLLOWUP_TURN 运行时 wake 注入，`WAKE_PREEMPTED`、follow-up 取消、WAKE 正常、无双上屏 | **NotMeasured** | r3/r4 均无自然 hint/确认窗口，`route=wake` 未注入；`P3_PROACTIVE preempt` 0；未 forced。 |
| **A-06** L3/L4 确认拒绝 | follow-up 中删除合成文件；a11yprobe 拒绝；`P3_CONFIRM`/`CONFIRMATION_DENIED`；文件 hash 不变 | **NotMeasured** | 无自然 hint/window；r4 D-A06 在 gate 前 `VAGUE`，deny probe armed 但没有可拒绝的确认；**无 A-06 claim**。Task 10 harness 的 `denyOk = denyClickSeen && fileUnchanged`（`confirmationDeniedSeen` 记录但不参与），§1 of Task 10 evidence 的旧表述被 §16.4/§17 修正。 |
| **A-07** 主动完整感知 | 主动回合有完整 `P3_PERCEPTION`，九 token + `screenChars>0` | **Verified (Device; no comparable JVM criterion)** | r3 四个 hint turn：九 token、`screenChars=3110/1437/3110/3110`；r4 七个 hint-model turn 也全部九 token + `screenChars>0`（truncated=true）。边界：是段落/裁剪后的快照，不是佩戴可见性。 |
| **A-08** WAKE 回归 | 0.24 WAKE 持续对话/TTS/STOP 不回归；JVM 既有行 + 设备既有证据；不重跑 0.24 判词 | **Partial (JVM Verified；device WAKE regression not re-run)** | 既有 WAKE JVM 套件在最终 gate 全绿（含 route 默认/WAKE 行为）；0.24 设备行保持不变但**未在 Phase 3 build 上重跑** WAKE 持续对话/TTS/STOP；Task 10 的 WAKE-route turns（R03D/P08）只覆盖部分 WAKE turn，不是 A-08 的完整回归。 |
| **A-09 (P3-13)** 会话独立 | 并发 WAKE + PROACTIVE 时 final/answer/store/续听不串；ASR final WAKE 优先；两条 store 不互读 | **Partial (JVM Verified；device concurrent NotMeasured)** | JVM 路由/会话独立测试钉住 two stores/slots/cycle/display/prompt postures、WAKE final 不进 PROACTIVE、PROACTIVE final 不进 WAKE、continuation 不跨路由；**最终复查新增/加强**：`ProactiveFollowupWindowTest > a usable final while the wake turn is running never enters the proactive session`（WAKE `Running`，不是 `AwaitingTranscript`；无 `P3_TRANSCRIPT`/park/resume/HINT_TURN，窗口不被消费）与 `ProactiveFollowupTurnTest > a context change before the continuation ACK starts no hint`（答案发出后、continuation ACK 前的 gap 不启动 HINT_TURN，continuation 仍打开）；设备并发 WAKE+PROACTIVE 回合未构造（Q3=最简），`NotMeasured`。 |
| **Panel-fix device proof** | 9d `PANEL_OPEN(PROACTIVE)` before hidden-panel hint submit + pre-armed round；隐藏面板成功/超时分支 | **NotMeasured** | 9d fix 为 **JVM/compile-only**（plugin 模块 919/0；`ProactiveFollowupWindowTest` 等）；r4 无自然 hint，`PANEL_OPEN`/`PANEL_SHOWN`/`listen`/`panel_not_shown` 全 0，路径未进入。r3 的 panel defect 仍是最后一次设备观察。 |
| **R-01/R-02** on-wire envelope/tri-state | 设备 `P3_TOOLRESULT` 六态/tri-state/`shape` 出现；done/refused/failed 真实覆盖；不可自然触发的状态允许 `NotMeasured` | **Verified (JVM + Device)** | declared Task 10 set：6 runs with tool calls / 21 `P3_TOOLRESULT` lines；exact field order 21/21、tri-state 21/21、`shape` 8 hex 21/21；done 15/refused 5/failed 1；21/21 independently reconstructed+matched；13 distinct digests；4 JVM-pinned digests reproduced。`timeout`/`uncertain`/`cancelled` 与 `TOOL_THREW` 未自然发生，保持 `NotMeasured`（row 允许）。参考 run 另加 1 行，不计入 21/21。 |
| **R-03** status/wait/dial | 三个独立回合各至少一次 `P3_TOOLARG`/`P3_TOOLRESULT`；status/wait 成功 `vision=not_requested`，dial 拒绝 observation | **Partial** | wait `Verified (Device; JVM not applicable)`（done/`vision=not_requested`）；status `NotMeasured`（R03S 0 tools；**“status not called”只限 R03S**；P08 成功调用 `device status` 两次）；dial literal `NotMeasured`（R03D-1 invalid；reference-only run 仍 0 dial）。**R03D = `NotMeasured`/test-case-invalid；owner acceptance 撤回、不得引用**；详见 evidence §16/§17。 |
| **R-04** 失败/uncertain 图 | 失败/uncertain 屏幕工具带 observation+image；逐调用相加=run metric；loop throw/timeout 图单独 | **Partial (JVM Verified；device 仅 refused 行)** | R07 与 R05 的设备行均为 **`outcome=refused`（pre-dispatch）**，不是 literal `failed`/`uncertain`，但都带 `observation=image`/`images=1`/`vision=ok`；declared set 7 runs（6 with tool calls）的 per-call sum = run metric（17/17）；JVM 覆盖 failed/uncertain/timeout/throw；loop throw/timeout 图 JVM 证、设备 **NotMeasured**（Q3=最简、shared WAKE path；不新增 fault seam）。**边界：设备半边只证明 non-success（refused）结果的 observation+image 形状，未命中 literal failed/uncertain；因此本行记 `Partial`，不写 `Verified (JVM + Device)`。** |
| **R-05** uncertain | `device.wait timeout_ms=1` → `outcome=uncertain`、`applied/changed=null`、`uncertain=true` | **Partial** | 设备实际 `outcome=refused code=VERIFY_TIMEOUT uncertain=false`、`applied=-`/`changed=-`、observation=image；不是行要求的 uncertain tri-state。模型答复与 refusal 语义一致；未改代码。 |
| **R-06/P-08** 零正文全窗口 | sentinel 活跃，先扫完整 logcat/证据，再 clear；0 命中 | **Verified (JVM + Device)** | sentinel 39 chars/hash；full window **14,947 lines / 1,868,268 B / 0 hits**；clear 前再读 **37,717 lines / 0**；repo `docs/` 128 text files/0；SDD 470/0；harness 63 text/0（39 PNG skipped）；wrapper 22/0；clear 后 21 行/0。`leftActive=true` 为 **posting-time** read（§16.5/§17），非 end-of-window；full-window order 未破坏。JVM 侧为既有 count-only 测试，Task 10 未重跑。 |
| **R-07** failed open | 不存在应用语料；失败 `open` 返回 observation/image/code；模型纠正/如实失败 | **Partial** | 真实 `open`：`outcome=refused code=APP_NOT_FOUND observation=image images=1 shape=5103d2a6`；attempt 2 模型如实说“应用未安装/无法打开”并请求确认。行要求的字面 `outcome=failed` 未产生（pre-dispatch refusal 语义）；attempt 1 的答案文本未保留 ⇒ honesty 子项 `NotMeasured`。未改代码。 |
| **R6** refused propose_hint feedback | 一次被拒的 `propose_hint` 允许一次反馈请求，因此该 HINT_TURN 最多 2 个模型请求；`maxSteps=1` 限制的是执行步数，不是请求数 | **Verified (JVM；device NotMeasured)** | `SmartProactiveTurnTest > a second tool call in the same hint turn is refused by the one step bound`：第 2 个请求到达但第二次 proposal 不执行；设备没有自然 refusal 路径，`NotMeasured`。 |
| **R7** proactiveMessage 重复前台/上下文 | `proactiveMessage(trigger)` 仍保留 foreground/context 文案，request 尾部注入的 PERCEPTION 也带同样事实，存在重复；未做去重 | **NotMeasured / known residual** | docs-only 记录；不改代码、无设备重跑；感知块仍到达请求（A-07 判据不受影响）；去重收益/影响 `NotMeasured`。 |
| **P-07** 0.25 27 行 | Phase 3 不重跑；旧 3/15/9 原样保留；Phase 3 记 `NotMeasured`/deferred | **NotMeasured / deferred (Q7=C)** | 未运行 27 行/`run-batch.ps1`；旧读数 **3 PASS / 15 FAIL / 9 NOT_MEASURED** 不变；旧证据不改；不把旧 FAIL 读成 `NotMeasured`，也不把跳过读成通过。 |
| **物理最高亮度 (Q2=A0)** | 只记录实际 settings/mode/前台/亮屏；物理亮度 `NotMeasured` | **Partial** | 亮度 settings **1000**/mode 0；屏幕 Awake、非锁屏；前台 launcher；未证明平台最高或物理亮度；无新 probe/sysfs/dumpsys/photometer。模型自述“最大”不作为证据。 |
| **逐调用图片归属 (Q8=A)** | 逐调用 `P3_TOOLRESULT images=` 相加 = run 级 `P3_METRIC images=`；不记 artifact id/hash | **Verified (JVM + Device)** | JVM `MetricLogTest > per call P3_TOOLRESULT images sum to the run level distinct count`；设备 declared set 21 calls / 17 images，每个 run 的 per-call sum = metric，0 mismatch；reference-inclusive 22/18（不计入 declared 21/17）。行只有 count，无 artifact 身份。 |
| **M1** shell/dial post-dispatch throw | 已派发后 Throwable → `Uncertain`/accepted=true/unknown tri-state | **NotMeasured (device)；JVM Verified** | `ShellToolTest`/`DialToolTest` 钉住 post-dispatch throw；设备无 `TOOL_THREW`/`uncertain`，shell done、dial 未调用；无 fault seam。 |
| **M2** provider-threw observation | provider 存在但投影/刷新抛错 → 显式 `unavailable(provider_threw)`；blind/non-screen `screen=null` | **NotMeasured (device)；JVM Verified** | `DeviceToolTest`（provider_threw、blind、non-screen）与 `PerceptionObservationSourceTest`；设备无 loop-source 行，失败 observation 来自工具自身。 |
| **M3** data.device 去重 | `data.device` 不含 `foreground=`；observation 仍带 foreground；runtimeState 保留 | **NotMeasured (device)；JVM Verified** | `DeviceToolTest` data dedup/runtimeState；设备 `data` 内容按设计不落日志，无法读回值。 |
| **M4** loop 观察刷新 + 250 ms + 图 | 真实 refresh、250 ms 截止、timeout/provider_threw、最多一张失败图 | **Partial** | declared set：6 个 `P3_OBSERVE source=tool`（open 81/224、140/153、68/187、136/227；shell 157/189、100/154 ms，全 ok），0 `source=loop`、0 `observation_timeout`；250 ms 设备值与 loop 图 **NotMeasured**（JVM 证）。reference-inclusive 7 行。 |
| **M5** catalog device/open 拒绝图 | a11y 关闭、unlock 拒绝、shell 不可用等 catalog 拒绝带 observation + best-effort 图 | **NotMeasured (device)；JVM Verified** | `ToolCatalogTest` 三类 catalog 拒绝 + disabled/no-image 变体；设备无 catalog-level 拒绝（a11y on、shell up、phone unlocked、dial 未调用）；open APP_NOT_FOUND/STALE_REF 是 tool-level enrichment。 |
| **M6** 文档 drift | D186/ACCEPTANCE 扫描时间、`AgentRunRequest`→`AgentRequest`、`Actions.kt` KDoc、Q1=A0/Q3=最简 记录 | **Verified (docs-only; no behavior change)** | D186 的旧 15:19:40/24,884/SDD 复扫表述已删除，改为 evidence 的 **15:16:07.829 全扫 + 15:20:44.341 repo-only**；ACCEPTANCE §2.11 R-06 的同一读数已核对一致；`AgentRequest.observationSource` 修正；`Actions.kt` KDoc 指向 `observeWithImages`/envelope；Q1/Q3 写入 evidence/STATUS/CONTRACTS。无 gate 代码/重训/state 变更。 |
| **M7** edge tests | post-dispatch shell/dial throw；provider-threw；blind/non-screen；data dedup；loop timeout/throw image；catalog device/open 拒绝图；uncertain tri-state；per-call image sum | **Verified (JVM)** | `ShellToolTest`/`DialToolTest`/`DeviceToolTest`/`ToolCatalogTest`/`PerceptionObservationSourceTest`/`AgentLoopGuardTest`/`MetricLogTest` 覆盖；本轮新增 `AgentLoopGuardTest > a thrown screen tool's capture image rides the next model request and never the stored transcript`（M7 唯一缺失组合；test-only）。agent-runtime 39/511。 |
| **Q1=A0** gate 输入 drift | D1 空字段省略 vs 四键训练契约；D2 合成 436 vs 219 token + 尾部截断；D3 `contextWindow=512` vs 实际 256 | **NotMeasured (known drift；no fix)** | 只在 evidence/STATUS/CONTRACTS 记录；Phase 3 不改 gate 代码、不重训、不扩 state；真实分布 `NotMeasured`。 |

**已知残留（2026-10-21 最终复查；docs-only，无设备重跑）**

| 残留 | 事实 / 边界 | 处置 |
|---|---|---|
| **P3_PERCEPTION 缺 `route=`** | spec §4.5 要求 `P3_CONV`/`ConversationDisplay`/`P3_PROMPT`/`P3_PERCEPTION` 带 route；当前 `P3_PERCEPTION` telemetry 行没有 route 字段 | telemetry gap；本轮不改 P3_PERCEPTION 代码、无设备重跑；记 `NotMeasured` |
| **9d `UI_CLOSED` acquire→submit** | hint panel 已 acquire、尚未 submit 之间到达的 `UI_CLOSED` 未被重新检查 | 9d 面板路径不改；device panel proof 仍 `NotMeasured` |
| **P-08 `leftActive`** | `leftActive` 是 posting-time 读，不是 end-of-window 活性 | 已在 R-06/P-08 行记录；full-window 0 命中结论不升级为窗口末端活性 |
| **`frameBuilderFor` route 参数** | 该参数当前未被使用 | 代码清理残留；不影响行为；未在本轮改动 |
| **`handleHelp` 不 detach `followupTurn`** | `handleHelp` 只 `conversationCycle.reset()`，不 detach 已安装的 `followupTurn`；若 Help 落在 follow-up 答案已发出、continuation ACK 未到的 gap，新增的 `followupTurn != null` busy 读会让 PROACTIVE admission 在该 Help 之后仍判 BUSY | 既有实现、窗口窄；不是本轮复查修复的缺陷，无测试；docs-only 记录，不改代码/不重跑 |

> **边界声明**：本节不写 Phase 3 全部通过。**`Partial`/`NotMeasured` 行不是 pass**：A-01/A-08/A-09、R-03/R-04/R-05/R-07、brightness、M4 为 `Partial`；A-03..A-06、panel proof、M1/M2/M3/M5、Q1 drift、P-07、R6 device half、R7、P3_PERCEPTION route、9d UI_CLOSED acquire→submit、P-08 leftActive、`frameBuilderFor` 为 `NotMeasured`/deferred/known residual；`handleHelp` 后 `followupTurn` busy 残留按既有/窄边界记录。**R03D 是 test-case/instrument-invalid（`NotMeasured`），不是 EDITH defect；无 rerun；参考 run 不作 pass/owner acceptance；owner dialing acceptance 撤回、不引用**。Q3=最简 的接受项（loop throw/timeout 图、250 ms、shared AgentLoop/context/display/confirmation 不重复设备测试、无新 debug seam）已明确记录。`NotMeasured` 不是通过；JVM 通过不等于设备通过；旧 0.21/0.25/0.26 判词与 §2.11 历史读数不变。

### 2.13 0.3 Phase 4A 工具面（2026-10-22；JVM-only，不是 Phase 4/版本通过）

| 行 | 判据 | 当前判词/证据 |
|---|---|---|
| **P4-A 工具面** | 24 默认工具、0 同名冲突、描述覆盖、两档路由、架构测试、硬编码扫描 | **Partial (JVM Verified；device NotMeasured)**：一个 `Capability.Actions` provider，24 = 18 atomic + 4 umbrella + 2 交付；`dial` 单 owner；24 条描述 + `title`/`description` 分离；`ToolContractTest`/`ToolRoutingTest`/`ToolLabelMapTest`/`WhitelistActionPolicyTest`；A8 六条架构断言 + 源码 guard。设备 24 工具广告/选择未测。 |
| **P4-B 普通权限** | L3/L4 确认 100%；>上限确认；白名单精确；无通道拒绝；插件收紧 | **NotStarted / NotMeasured**（4B）。普通模式默认 `maxLevel=L1` 仍按当前 `Refuse` 行为；`RequireConfirmation`/L3/L4 确认未实现。 |
| **P4-C 不拦截** | L0–L4 0 确认；绕过上限；App 重启/被杀后仍生效；审计/STOP；关闭恢复 | **NotStarted / NotMeasured**（4B）。 |
| **P4-D 确认交互** | 至少一条非 Edith 通道 allow/deny；后台/锁屏/面板隐藏/通知关闭；降级原因 | **NotStarted / NotMeasured**（4B）。 |
| **P4-E 延迟/语料** | K40 atomic vs umbrella 矩阵；旧 27 行 + 新指标；旧判词不改 | **NotStarted / NotMeasured**（4B）。P4_TOOL 仅 JVM 行/解析器；无设备读数。 |

**4A 门禁**：全量 JVM（10 模块，含 plugin-diagnostic/eval-trigger，`--rerun-tasks`）**338 suites / 3,659 tests / 0 failures / 0 errors / 2 env skipped**（app 2 skip：symlink + 缺 tokenizer 语料）。逐模块：plugin-api 11/60、host-runtime 19/134、device-control 12/134、agent-runtime 39/522、model-runtime 17/207、output-runtime 16/175、plugin-smart-helper 91/954、plugin-diagnostic 2/26、app 123/1,374/2 skipped、eval-trigger 8/73。**本阶段无设备运行；无新 APK 安装；已安装 APK 仍是 `8d075f40`/`c3ff29bb…125e`。**

**4A 已知残留（不读成通过）**：

- `P4_TOOL` 目前对 policy-refused/confirmation-denied/unknown-tool 等未执行调用也发一行 `outcome=failed`；B9 必须按 executed/refusal 过滤或记录规则，不能把 0 ms 样本读成真实延迟。
- 24 个工具 schema + 24 工具 prompt 增大前缀；tool-schema token 未计入 `fixedPromptTokens`/trimmer，三个既有测试窗口从 4k 扩到 32k。4B/设备前必须用配置 profile 验证不会 `PROMPT_EXCEEDS_BUDGET`/`CONTEXT_OVERFLOW`。
- A8 guard 的已披露残余：短英文/<20 Han 行、拼接片段、前缀日志 token、ProactiveGate 默认文案（由 PINNED 资源等值测试兜底）。
- 4A 的 atomic a11y index 已按回合失效（`ToolSurface.beginTurn` → backend）；umbrella `ref` 仍按 revision 生效；两者语义不同但均已文档化。
- 4A 的 `set_brightness` L2 描述已补“按当前权限策略可能需要确认，也可能被拒绝”；普通模式的 >上限 `RequireConfirmation` 仍属 4B。

> **判词**：**Phase 4A 完成的是工具面/提示词/架构/遥测的 JVM 收口，不是 Phase 4 通过、不是版本级通过。** P4-A 为 Partial（JVM Verified；device NotMeasured）；P4-B..E 未启动；4B 的设备/确认/不拦截/延迟/旧语料仍为 `NotMeasured`。旧 0.21/0.25/0.26/Phase 3 判词不改。

### 2.14 0.3 Phase 4B 权限、确认与“不拦截”（2026-10-22；JVM + 部分设备，不是 Phase 4/版本通过）

**代码/构建**：`feat/0.3-harness`，HEAD `5f698420`；K40 `2696813b` / 眼镜 `1901092544027662`。ASR+4B base APK `A219FF…3008`（P4-B/C 设置与确认、后台/锁屏、shell），closure harness `9D6E41…1ABF`（24/0、argsB64、延迟、ASR clean-TTS），final surface/settings harness `7042E983…3146`（notification/off/no-surface/whitelist）；原 APK `C3FF29BB…925E` 测试后已装回。证据见 [Phase 4 closure](evidence/2026-10-22-0.3-phase-4-closure.md) 与 [4B device](evidence/2026-10-22-0.3-phase-4b-device.md)。

| 行 | 判据 | 当前判词/证据 |
|---|---|---|
| **P4-A 工具面** | 24 默认工具、0 同名冲突、描述覆盖、两档路由、架构测试、硬编码扫描 | **Partial (JVM Verified；device representative；业主接受，D195)**：JVM 24/0/描述/路由/架构 guard 全绿；设备 `P4_TOOLFACE tools=24 duplicate=0`，representative `read_screen`/`device.observe`/`set_permission`/`set_brightness`/`shell`/`notify_user` 有读数；完整 24 工具选择矩阵未跑，属于验证材料/方式边界，不读成全绿。 |
| **P4-B 普通权限** | L3/L4 确认 100%；>上限确认；白名单精确；无通道拒绝；插件收紧 | **Verified (device + JVM; plugin tightening JVM-only)**：JVM 全绿；设备 L4 allow/deny、L3 allow/deny（不存在包安全代理）、L2 above L1 deny、危险 shell deny；`maxLevel=L2` + `set_brightness|device` → `P4_POLICY L2 Allow`，错误 scope `set_brightness|app.ui` → `RequireConfirmation`；debug no-surface → `P4_CONFIRM unavailable channel=none` + `CONFIRMATION_UNAVAILABLE`；无生产 descriptor 声明 `requiresConfirmation=true`，该分支为 JVM-only。 |
| **P4-C 不拦截** | L0–L4 0 确认；绕过上限；App 重启/被杀后仍生效；审计/STOP；关闭恢复 | **Verified (device)**：设置页开关+二次确认+banner 有截图；L0–L4 全部 `mode=no_interception Allow` 且无 `P4_CONFIRM`；force-stop/重启后保持；关闭恢复 normal `RequireConfirmation`；审计页读回 L1/L3/L4 记录；真实「急停」由 a11yprobe 点击（`P3_A1CLICK_VERDICT ... matched=急停 clicked=true`，`P3_CONFIRM ... source=cancel`，`P3_TURN end ... CANCELLED`）。 |
| **P4-D 确认交互** | 至少一条非 Edith 通道 allow/deny；后台/锁屏/面板隐藏/通知关闭；降级原因 | **Verified (device; debug forced-surface)**：眼镜 ENTER/BACK、后台、锁屏；通知 allow/deny 均为 `P4_CONFIRM ... channel=notification` + `P3_CONFIRM_NOTIFY delivered=true`；notifications-off → `P3_CONFIRM_NOTIFY posted=false reason=PERMISSION_DENIED` + `P3_CONFIRM_RAISE raised=true` 但截图停 home；debug no-surface → `P4_CONFIRM ... unavailable channel=none` + `CONFIRMATION_UNAVAILABLE`。真实 CXR 移除/真实声学人声仍 `NotMeasured`；合成 TTS 按业主决定采用。 |
| **P4-E 延迟/语料** | K40 atomic vs umbrella 矩阵；旧 27 行 + 新指标；旧判词不改 | **NotMeasured（业主接受，D195）**：`read_screen 661ms`/`device.observe 991ms`、`open_app 1058ms`/`open 2235ms`、`system_action 853ms`/`device act home 1442ms`、`set_brightness 773ms`/`shell 914ms` 均为单样本；L-02 无有效 clickable index；不能当 p50/p95。修正语料已 review，旧 active-24/旧 27 未重跑，旧判词 3/15/9 原样保留。 |

**最终 JVM gate**（11 模块，含 plugin-diagnostic/eval-trigger/glasses-app，`--rerun-tasks`）：**359 suites / 3,936 tests / 0 failures / 0 errors / 2 env skips**。逐模块：plugin-api 12/62、host-runtime 19/135、device-control 12/137、agent-runtime 39/533、model-runtime 17/207、output-runtime 16/175、plugin-smart-helper 92/970、plugin-diagnostic 2/26、app 125/1,424/2 skipped、glasses-app 17/194、eval-trigger 8/73。

**已知残留/不读成通过**：

- P4-D 通知 action / 无可见面 / notifications-off 已用 debug `DebugConfirmationSurface` 设备验证；P4-B 无通道/白名单已设备验证，插件收紧为 JVM-only。真实 CXR 移除（session Interrupted/CapabilityMissing）仍 `NotMeasured`。
- P4-E 只有两条单样本延迟对；active-24/旧 27 未重跑；旧 27 判词 3/15/9 原样保留。
- 不拦截设备 UI 已点击（settings 二次确认/banner/off），审计 readback 已截图；真实「急停」已由 a11yprobe 点击并取消回合；debug session-control STOP 只是另一条独立仪器。
- ASR 合成 TTS clean 句（7 chars，无复读）已验证；业主选择以合成 TTS 作为确认语音证据；真实 CXR 人声仍 `NotMeasured`，不把它读成真实人声通过。
- 完整 24 工具选择未跑；索引工具 `tap/long_press/input_text` 因跨回合快照限制仍 `NotMeasured`。
- 修正语料已 review，但 reviewer 指出的 C1/C2/I1–I5 已在 overlay 记录；旧 active-24 不重跑是记录在案的 deviation，不是 pass。
- **§5.3 持久指示 deviation**：通知/眼镜的“不拦截已开启”显示尚未实现，当前只有 App 内 banner；与 voice-急停同级记录为未完成项。
- **P4_CONFIRM shape drift**：实际 line 为 `tool=... decision=... channel=glasses|notification|voice|dialog|none`，无 `risk=`；spec §6.3 的 `risk=`/`channel=phone` 与 §5.4 的 `available=/reason=` 是文档漂移，降级原因在 `P3_CONFIRM_NOTIFY`。
- P4_CONFIRM 竞态/缺口（仅计数）：present 期间结算可能 `channel=none`；shell precheck 在 present 前 unavailable 时可能没有 `P4_CONFIRM`；未测试，不影响执行结果。
- B4/B5 的 `channel=dialog` 乐观标记、B7 成功 raised 不单独记行、B5 voice-急停未实现；延迟/旧语料证据 [latency-corpus](evidence/2026-10-22-0.3-phase-4-latency-and-corpus.md)。
- **temporary harness removed**：`DebugConfirmationSurface` / `DebugSmartSettingsControl` / `Sequence` / `argsB64` 等只在 debug source、已在本轮清理；P4-B/D 的 forced-surface 证据为历史读数，不等同于真实移除 CXR 眼镜面。设置已恢复 `maxLevel=L1`、空 whitelist、唤醒词 `伊迪丝,edith`。

> **判词（2026-10-22 业主 D195 接受）**：**Phase 4 设备验收通过。** P4-B/C/D = Verified；P4-A = Partial、P4-E = NotMeasured 作为**验证材料/方式边界**被明确接受，不在发布统计中改写成全绿；真实 CXR 移除、真实声学人声、完整 24 选择和延迟分布仍不声称通过。**不是版本级发布通过。**

### 2.15 0.3 ASR 复读与持续对话修复（2026-10-22；设备 A/B + JVM；不是版本级通过）

**代码/构建**：`feat/0.3-harness`；最终 debug APK **102,789,648 B**，SHA-256 `A219FF711C29BD0C343E7D353F5C45D80FFED8A25BCCAAFE5C224D1F4B693008`，K40 `2696813b` 设备 `base.apk` 同哈希；fp32 bundle `1b613596-0ed2-43ab-af28-b42b79b676e7`（冷启动 `ENGINE_REUSED: RECOVERED`）。证据：[2026-10-22-asr-fp32-decoder-and-continuous-dialogue.md](evidence/2026-10-22-asr-fp32-decoder-and-continuous-dialogue.md)。

| 行 | 判据 | 当前判词/证据 |
|---|---|---|
| **R-ASR-1 ASR 复读** | 固定语料不再复读；decoder 数据可复核；不换模型版本、不换解码策略 | **Verified (Device A/B, synthetic TTS; 真实 CXR 人声 NotMeasured)**：同一 APK `/` 同一 bundle 其余文件、固定 WAV：int8 decoder → `今天天天气怎么样`、`播放播放播放周周杰伦伦的稻香`；fp32 decoder → `今天天气怎么样`、`播放周杰伦的稻香`。最终 APK 复跑 `播放周杰伦的稻香`（`userChars=8` / `userSha=2825D88E…C792`）。证据 §3；真实眼镜麦克风/正常语速人声与 RTF P95 仍 `NotMeasured`。 |
| **R-CD-1 唤醒路线响应中输入** | LLM 响应中/答案结束到 continuation 之间说的可用 final 不丢，成为下一回合；不改变官方 10 s 窗口 | **Verified (JVM + Device synthetic final)**：设备修复后 `P3_CONV queue route=WAKE chars=4` → `P3_CONV continue` → `P3_CONV queued_consumed` → `P3_TURN start turn=debug-utterance-2`；Idle 间隙由 JVM 回归覆盖。真实声学语音的同一路径 `NotMeasured`；10 s 窗口保持不变。 |
| **R-CD-2 主动路线 follow-up 输入** | follow-up 答案运行中说的可用 final 由 PROACTIVE continuation 消费；路由不混 | **Verified (JVM only)**：`ProactiveFollowupTurnTest > a final during the follow-up answer becomes the proactive continuation utterance` 及既有 proactive 窗口/路由套件全绿；设备自然 hint/多轮 `NotMeasured`。 |
| **R-CD-3 已知未修（不读成通过）** | 同轮分析发现的输入静默死亡路径 | **NotMeasured / open**：`SpeechPipeline.fault()` 永久 `paused`、`HostRuntime` 插件异常静默 `pause()`、`AudioSegmentCapture.release` 后不可复用、主动路线隐藏面板 3 s `panel_not_shown` 与跨路由 `PANEL_SHOWN`；本轮未修。 |

**JVM gate（11 模块，`--rerun-tasks`）**：**359 suites / 3,936 tests / 0 failures / 0 errors / 2 skipped**（plugin-smart-helper 92/970，app 125/1,424/2 skipped）。新增回归：`RunningUtteranceQueueTest` 3 tests、`ProactiveFollowupTurnTest` 1 test。

> **判词**：ASR 复读的 int8 decoder 根因与数据修复已在 K40 上以合成 TTS 完成 A/B；唤醒/主动两条持续对话输入丢失路径已修并有 JVM + 唤醒路线设备证据。**不是版本级通过**：真实 CXR 人声、主动路线设备多轮、长确认回合的设备复跑、R-CD-3 均 `NotMeasured`。

---

### 2.16 0.4 脱离眼镜 / 文字与听写输入 / 模型内置（2026-10-27；实现、JVM 与 K40 最终矩阵收口；不是版本级发布通过）

**代码/构建**：分支 `feat/0.4-detached-wake-bundled-models`，最终 HEAD `59f41190`（`b2d03c06` KWS epoch reset；`c594fbe1` Task 13e 证据；`ab25cadc` 文档收口）。最终 JVM gate（`test --rerun-tasks`，最终 HEAD，JUnit XML）：**18 个产品/工具模块 / 439 XML / 4,655 tests / 0 failures / 0 errors / 2 skipped**；探针 **15 XML / 152 tests** 按 D156 单列排除，不进入 gate 分母；2 个 skip 是既有宿主假设（Windows 无符号链接权限、缺 tokenizer 语料）。构建级模型审计：**15/15 模型 + 15/15 许可证 + 6/6 KWS**，`bundleId=f06c6a5beaa21ed39dd5ed189e77bc3b078796d53a74436ce549693127455565`，`BUNDLED_MODEL_MANIFEST_RESULT=PASS`，独立复核 `ASSET_AUDIT_RESULT=PASS`。原始门禁报告：`.local/final-gate/full-test-kwsfix.log`（gitignored，未提交）。

**设备边界**：K40 `2696813b` + 眼镜 `1901092544027662`。最终矩阵 Task 13e 的设备构建为 **1,124,338,500 B / SHA-256 `8fd5af06…ef09ff75`**（含 strict-local、detached 状态、听写/ASR、新输入行、草稿生命周期）；KWS epoch reset 的最终构建为 **1,124,338,512 B / SHA-256 `77858647…1A78C2BA`**，K40 `install -r` Success、设备 `base.apk` 同哈希。按「眼镜会话 → 脱离 → 手机麦会话」有利素材 wake13g.wav 5/5（修复后新进程；pre-fix 冷启动亦曾命中有利素材）；但**同一 APK 在脱离会话冷启动上对同一 wake13g.wav 2 次 + wake-zh.wav 1 次为 0/3 `P3_WAKE`**（03:03–03:04，进程 29852），故 **D5 记 `Partial`（中文合成不稳定；真实人声/召回未知）**；弱素材 wake-trim 离线 8 流 0 命中、1/5 对照无效，不作判据（证据 `2026-10-27-0.4-kws-epoch-reset-device.md`）。所有 `Partial`/`NotMeasured` 行不得读成通过。

**证据**：[bundled models](evidence/2026-10-26-0.4-bundled-models-device.md)、[detached wake](evidence/2026-10-26-0.4-detached-wake-device.md)、[typed/dictation pre-fix](evidence/2026-10-26-0.4-typed-dictation-device.md)、[typed/dictation final binary](evidence/2026-10-27-0.4-typed-dictation-final.md)、[fix1 retest](evidence/2026-10-27-0.4-typed-dictation-fix1-retest.md)、[dictation-tail/ASR re-arm](evidence/2026-10-27-dictation-tail-asr-fix-report.md)、[final K40 acceptance](evidence/2026-10-27-0.4-final-acceptance.md)、[KWS epoch reset](evidence/2026-10-27-0.4-kws-epoch-reset-device.md)。最终 JVM 原始报告见 `.local/final-gate/`；未提交的原始 logcat/截图按项目规则留在 `.local/`。

#### J 行（JVM；全部为 PASS/Verified）

| 行 | 判据摘要 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **R-0.4-J1** 设置/保存 | `glassesDetached` 缺键默认 false；保存/读取；会话运行中保存先停会话/服务；`proactiveEnabled` 不被改写 | **Verified (JVM)** | `SmartSettingsStoreTest` 36、`DetachGateTest` 20、`DetachUiTest` 6、`SmartSettingsFormTest` 28。最终 gate 全 PASS。 |
| **R-0.4-J2** 采音选择/生命周期 | phone ingress 被 arm 时 CXR 不被调用；眼镜模式仍走 transport；start/stop/pause 幂等；采集失败有明确错误 | **Verified (JVM)** | `DetachedAudioWiringTest` 14、`DetachGateTest` 20、`WakeLockHoldTest` 13、`DetachedStartTest` 9。 |
| **R-0.4-J3** 手机麦 KWS 路径 | fake ingress + 帧序列驱动 `AudioIngressLoop`；wake/管线消费与 CXR 同形；`P3_FRAME` 计数；格式不匹配拒绝；换 epoch 重置 8 条 wake 流 | **Verified (JVM)** | `PhoneMicCaptureTest` 10、`AudioIngressLoopTest` 14、`PcmIngressTest` 9、`WakeWordDetectorTest` 25。 |
| **R-0.4-J4** 无 CXR 激活 | 去掉 transport/`linkReady` 后插件仍激活；`TextOutput` 由手机设备使能；`isPanelVisible` 脱离为 true；眼镜模式能力集合不变 | **Verified (JVM)** | `CxrHostEnvironmentTest` 10、`DetachedStartTest` 9。 |
| **R-0.4-J5** 手机显示设备 | ack-only（不写 `ConversationLog`）；`show` 内**无条件/同步/恰好一次**回调 `Displayed`；不按答案行存在做运行时 gating；答案行是插件顺序保证的设计不变量；不发 CXR 命令 | **Verified (JVM)** | `PhoneHintDeviceTest` 5；D201/§4.3/R-0.4-J5 的措辞已在 spec 修正（`96e66a3d`）。 |
| **R-0.4-J6** 主动辅助有效值 | detach on 时 host 不发射感知事件、`proactive.armed=false`、插件 `admit` 拒绝；detach off 恢复存储值；存储值不变 | **Verified (JVM)** | `ProactiveArmingTest` 10、`ProactiveArmingPersistenceTest` 5。 |
| **R-0.4-J7** 文字消息 | 一条请求同形于已接受 WAKE final；只产生一个 WAKE 回合；不触发 PROACTIVE；空白/无身份/Awaiting/Running/Idle-未结算/排队句拒绝；不启动 cooldown、不写 `lastFinalTranscript`；WAKE-only cycle reset；Help 不变 | **Verified (JVM)** | `TypedMessageTurnTest` 14、`TypedInputRowTest` 16。 |
| **R-0.4-J8** 听写 | final 填 UI、不进 `HostRuntime`；取消/超时/无结果明确；听写期间 KWS 不误触发；不重启 CXR transport；单一采音 lease；听写尾音路由闸门 + `SpeechPipeline.rearm` | **Verified (JVM)** | `DictationControllerTest` 14、`DictationWiringTest` 29、`DictationInputTest` 10、`SpeechPipelineDictationTest` 10、`SpeechPipelineDictationAdapterTest` 3。设备 D8 的尾音/busy 路径见 D8。 |
| **R-0.4-J9** 模型 provisioning | fake assets/streams 下首次导入、二次跳过、摘要不匹配拒绝、拷贝中断不留 active、空间不足错误；release 路径启用；Laya/Embedding receipt 使更新生效 | **Verified (JVM)** | `BundledModelProvisionerTest` 29、`AssetEngineBundleProvisioningTest` 4、`ModelReadinessTest` 5、`EngineBundleProvisioningTest` 7（1 个 Windows 符号链接 skip）。 |
| **R-0.4-J10** 构建清单校验 | 尺寸/SHA/缺文件/多文件夹具；`withoutBundledModels` 只影响开发变体且 release 禁用 | **Verified (JVM + build audit)** | `BundledModelManifestContractTest` 4、`BundledModelsManifestTest` 11；`verifyBundledModelManifest` entries=21/emitted=15/licenses=15、`bundleId` 派生校验；独立资产审计 PASS。 |
| **R-0.4-J11** 架构/边界/唤醒锁 | 插件不 import `com.sheen.rokid.phone.*`；新增文件进已登记包；无新增 CXR 协议；锁屏唤醒锁在 stop/pause/异常路径释放 | **Verified (JVM)** | `PromptArchitectureTest` 6、`HardDependencyTest` 8、`WakeLockHoldTest` 13。 |
| **R-0.4-J12** 全量 `--rerun-tasks` | 全产品/工具模块 0 failures / 0 errors / 既有 skip 不变 | **Verified (JVM)** | **439 XML / 4,655 tests / 0 failures / 0 errors / 2 skipped**；18 个产品/工具模块；探针 15/152 排除；计数只从 JUnit XML 聚合（`.local/final-gate/full-test-kwsfix.log`）。 |

#### D 行（K40；最终矩阵 Task 13e + KWS epoch reset）

| 行 | 场景 | 状态 | 证据 / 边界 |
|---|---|---|---|
| **R-0.4-D1** 完整 APK 安装 | `adb -s 2696813b install -r` Success；`base.apk` 哈希一致；APK assets 尺寸/哈希抽检 | **Partial** | 最终矩阵 APK `1,124,338,500 B` / `8fd5af06…ef09ff75` 与 KWS reset APK `1,124,338,512 B` / `77858647…1A78C2BA` 均 `install -r` Success、数据保留（`firstInstallTime` 未变）；**13e APK 设备 `base.apk` 与本机逐字节同哈希**（KWS APK 的 device base hash 未在证据中记录）；`verifyBundledModelManifest` 15/15 + 15/15。**release APK 未构建**（既有 `assembleRelease` dependency-lock 失败）、干净安装/受控空间不足 `NotMeasured`；APK 字节可复现 `NotMeasured`。 |
| **R-0.4-D2** 首启 provisioning | 冷启动自动准备；`P4_MODELS ready`；目标目录 bytes/哈希与内置一致；会话可启动 | **Partial** | Task 4 debug（当时 **10 份许可证文本**的构建）实测 launch→ready **13.79 s**、provisioning **12.81 s**、二次冷启动 **0.453 s**（`ENGINE_REUSED`）、设备 **14/14 SHA** 匹配，且未 `adb push`。`ee3b43d5` 后的 **15 份许可证 + 派生 bundleId + manifestDigest receipt 迁移**已 JVM/构建审计（15/15 + 15/15 + 6/6），但**该最终构建的首次安装/迁移未重跑**；干净安装、release provisioning、空间不足/中断恢复 `NotMeasured`。 |
| **R-0.4-D3** 开关 UI/持久化/生效 | 设置页可切换；force-stop 后保持；运行中保存先停会话；下次开始生效 | **Verified (Task 13e 最终矩阵)** | 真实 Switch ON/OFF；运行中保存 → `P4_AUDIO disarmed`、`session=Idle`、`SessionService`/本包 `NotificationRecord` 消失；脱离启动 `source=phone_mic`、`P3_CXR`=0、`session=Ready`；空闲置 ON + force-stop 冷启动读回 `glasses_detached=true`。**原生 1080×2400 视口滚动触达 `NotMeasured`**（临时逻辑显示 `1080×4800`，探针无滚动原语）。证据：[final acceptance](evidence/2026-10-27-0.4-final-acceptance.md) §D3。 |
| **R-0.4-D4** 脱离模式严格本地 | 不请求/不建立 CXR；插件激活；`P4_AUDIO source=phone_mic`；**切换后零 outbound** | **Verified (core；Task 13e 最终矩阵)** | live 眼镜会话 + mapping → 真实 switch ON → `P4_DETACH_LINK state=neutralized session=cleared connection=closed`；neutralization 后 **60 s 零 `EdithHint: outbound:`、零 `P3_CXR`、眼镜侧零 inbound**；脱离启动 +30 s 零 outbound/零 reconnect。测量窗口从 `P4_DETACH_LINK`/`P4_DETACH state=on` 起算，**不把切换前正常 stop 的 Clear/Busy/Close 或 `outbound suppressed` 诊断算作 outbound**（`CONTRACTS` §2.8.2）。证据：[final acceptance](evidence/2026-10-27-0.4-final-acceptance.md) §D4。 |
| **R-0.4-D5** 手机麦克风唤醒 | 真人说中文唤醒词，KWS 触发；`P3_WAKE`/`P4_AUDIO` 有读数；WAKE 回合完成 | **Verified（2026-10-27 业主真人验收）** | 业主在 K40 上真人实测可唤醒。此前合成声学读数保留为过程证据：中文 `wake13g.wav` 一次 5/5，但同 APK 冷启动脱离 0/3；说明合成命中率不是稳定属性。真实人声的召回率/误触发、多轮切换、长稳仍 `NotMeasured`；本行按业主真人验收判 Verified，不把合成 5/5 当证据。证据：[KWS epoch reset](evidence/2026-10-27-0.4-kws-epoch-reset-device.md)。 |
| **R-0.4-D5b** 锁屏/息屏唤醒 | 会话运行→锁屏/息屏→合成唤醒词→`P3_WAKE` 与回合完成；`P4_AUDIO ... lockscreen_hold` 可见；FGS 存活 | **Verified (合成声学，screen-off；fix1 构建)**；**keyguard NotMeasured** | 息屏 `mWakefulness=Asleep`/`Display State=OFF` 时 `P3_WAKE` + `P3_TURN COMPLETED`（真实模型）、`edith:detached-wake` 持有、FGS 前台；arm 行 `lockscreen_hold=true`、disarm `false`。**安全锁屏（keyguard）`NotMeasured`**；回合后仍息屏，不承诺亮屏；最终二进制未重跑。 |
| **R-0.4-D5c** 脱离唤醒确认 | 脱离唤醒成功后固定说「我在」；回声不进 ASR；眼镜模式保持 beep | **JVM Verified；真机待业主复测** | EchoMuteWindow 窗口内帧换零（AudioIngressLoopTest 钉住 ASR/KWS 收全零且时间轴连续）；detached wake ck=[我在]/chime=0/P3_WAKE 立即；glasses ack=0/chime=1；speakText 精确文本+gate。2026-10-27 旧实现曾把「我在」送入模型并破坏后句（真机日志），本实现以半双工静默替换修复；代价：ack 窗口内抢话丢失。证据：[echo-mute](evidence/2026-10-28-detached-wake-ack-echo-mute.md)。 |
| **R-0.4-D6** 主动辅助抑制 | `proactive.armed=false`；无 Laya gate/提示回合；关闭脱离并重启后用户原设置恢复 | **Verified (首轮/fix1 构建)** | `proactive_stored=true effective=false`、`P3_PROACTIVE skip reason=DISARMED`；关闭脱离后 `effective=true`、存储值仍 true；force-stop 冷启动读回不变。最终二进制未重跑。 |
| **R-0.4-D7** 文字消息 | 中文输入 → 发送 → 用户行+助手答案行；`P4_TYPED accepted`；无 PROACTIVE 回合 | **Verified (Task 13e 最终矩阵；脚本模型)** | 新输入行 SET_TEXT/READ 回读匹配；`P4_TYPED accepted chars=9`；恰好一个 typed WAKE 回合 `COMPLETED steps=1`；用户/助手行各 1；发送后输入框 `len=0`；无 `P3_PROACTIVE`。模型回答为 debug `ScriptedModelWire`，不是真实模型；真实网络回答 `NotMeasured`。证据：[final acceptance](evidence/2026-10-27-0.4-final-acceptance.md) §D7。 |
| **R-0.4-D8** 语音听写 | 手机麦克风播放合成语音 → 听写填入输入框（读回文本）→ 点发送 → 答案；不自动发送；尾音不造成 Busy；草稿跨 tab；超时状态可清 | **Verified (Task 13e 最终矩阵；合成声学)** | `started→final chars=4 endpoint`、回读 `len=4`、发送前无 `P4_TYPED`/回合；**final 后立即 typed accepted**（尾音路由闸门）；6 字符草稿切 SKILLS/MEMORY 往返保留；15.0 s `timeout` 后编辑清除状态并可发送。`c3b4272a` 的路由闸门在设备上复验：final 后 35 s 无 `P3_CONV queue`；听写后的环境语音被刻意丢弃（D8 边界）。合成音频，真实人声 ASR `NotMeasured`。 |
| **R-0.4-D9** 唤醒 ASR 直达 LLM 不变 | 唤醒 → 直接说请求 → 单个 WAKE 回合/答案；输入框无内容变化 | **Verified (Task 13e 最终矩阵；合成声学)** | `P3_WAKE word=伊迪丝` → 恰好一个 `P3_TURN`/答案 `COMPLETED steps=0`；输入框 `len=0`；无 typed/dictation/proactive。KWS 合成命中概率性，不计算召回率；真实人声 `NotMeasured`。 |
| **R-0.4-D10** 眼镜模式回归 | 关闭脱离：CXR 连接、唤醒、提示上屏、文字/听写入口仍可用（听写走手机麦） | **Verified (Task 13e 最终矩阵；真实眼镜链路)** | `P4_AUDIO source=cxr transport=true`；typed 10 字符 → 一个回合 + `P3_HINT Displayed` + 眼镜 `Show displayed`；听写 `final chars=16` + 回读 16 + 不自动发送；final 后立即 typed accepted；CXR callbacks 持续、`P3_CXR connect`=0、眼镜 `Hello`=0；`Show displayed` seq 1/2。模型为脚本，真实 CXR 声学耦合 `NotMeasured`。 |
| **R-0.4-D11** 失败语义 | 拒绝 `RECORD_AUDIO` → 启动被拒且原因可见；麦克风占用/失败有明确错误 | **Partial (Task 13e 最终矩阵)** | 权限拒绝：眼镜 live 会话 + 无 `RECORD_AUDIO` → `P4_DICTATION state=refused chars=0 reason=permission` + 「需要麦克风权限」提示；同一 pid/会话 Ready/CXR 继续；re-grant 后恢复。**live session + revoke 组合被平台杀进程 `NotMeasured`**（detached/glasses 均是）；**mic busy / 录音初始化失败 `NotMeasured`**。 |
| **R-0.4-D12** 真实人声/长稳/首启/RSS | 按实际测量或 `NotMeasured` | **NotMeasured** | 真实人声 KWS/ASR、召回率/误触发、真实 CXR 声学、长稳/功耗/RSS、多轮眼镜↔脱离切换后的 KWS 稳定性均 `NotMeasured`；Task 4 的 debug 首启耗时（13.79/12.81/0.453 s）不等于最终 15 许可证构建或 release 读数。 |

**统一未测/不得读成通过**：D11 live revoke（平台杀进程）；release APK/release provisioning；最终 15 许可证构建的干净首启/迁移；受控空间不足；keyguard；真实人声/召回率/真实 CXR 声学；mic busy/录音初始化失败；APK 字节可复现；原生 1080×2400 开关视口；精确 ASR fault code（`P3_PIPELINE state=fault reason=<code>` 已补，待下一次复现命名）；composer 实际观感/键盘避让；KWS 多轮眼镜↔脱离切换/长稳；长稳/功耗/RSS。**0.4 不是版本级发布通过；0.21/0.23/0.24/0.25/0.3 的旧判词不变。**

---

## 3. F4 与 M2（Phase 7）

### 3.1 F4：记忆形态 A/B 对照

| 组 | 有效 n | MEMORY | MIXED | CURRENT | NEITHER | INVALID |
|---|---:|---:|---:|---:|---:|---:|
| A（原话回放 `LEGACY_RAW`） | **9** | **7** | 0 | 2（A7, A10） | 0 | 1（A5） |
| B（现行生产 `PRODUCTION`） | **10** | **0** | 0 | 9 | 1（B3） | 0 |

- **机制：成立、未被证伪。** A `MEMORY=7`（n=9）触发预登记的"A ≥1 ⇒ 机制成立"；证伪条件（A=0，n=10）没有发生。
- **处理形态：有效。** B `MEMORY=0`（n=10）。
- **A 组按 n=9 读**：A5 `NotMeasured`（3× `NO_WAKE`）；**不写 7/10、不写 N=10**。
- **措辞边界**：9 个 A 回答都提到本轮曲目；**不得声称"A 忽略了当前问题"**；`MEMORY` 是预登记关键词规则。
- **ASR 混杂**：所有 valid 槽是同一份 17 字符乱码转写；不得把"本轮参与"单独归因于 A/B 形态。

### 3.2 M2：样板流程真机测量

| 项 | 读数 |
|---|---|
| 判据 | B 组 10 个有效回合全部 `currentFulfilled` |
| 实际 | **0/10 成功** |
| 判定 | **M2 未通过；有效失败，不是 `NotMeasured`** |
| 观测 | 每个 B 回合 `foreground=edith.phone`，媒体会话从未 `PLAYING`，无 `月半小夜曲` |
| 根因观测 | `launch_app("音乐")` `APP_NOT_FOUND`；`open_app` `APP_NOT_VISIBLE`；`list_apps` 只有 `EDITH`；`com.miui.player` 已安装且可解析、`QUERY_ALL_PACKAGES: granted=true`。**确切 MIUI 原因未查明** |
| 处置 | 业主 2026-10-17：本阶段不修应用工具（D120）；下版本重构并复测（D121） |

---

## 4. 旧行回归（B1–B11 / C1–C10）

**全量 JVM 回归（0.21 收口回归）**：310 套件 / 2671 tests / 0 failures / 0 errors / 0 skipped；40 个旧行具名套件全绿。

| 旧行 | 当前读数 | 边界 |
|---|---|---|
| B1 两条路由独立 | `ContextManagerTest` 10（原记 12→10，判据不变）+ `ConversationContextTest` 9 | 真机未重测 |
| B2 Token 预算分类 | `TokenBudgetTest` 11 + `ProductionBudgetCompressionTest` 3 | 真机未重测 |
| B3 压缩方案 E | `CompressionTest` 16 + `StoreCompressionTest` 12 + `ContextEvictionSemanticsTest` 4 + `ModelSummarizerTest` 6 | 真机压缩/摘要未重测 |
| B4 SKILL | `SkillParserTest` 17 + `SkillManagerTest` 10 + `SkillManagerFilteringTest` 5 + `SkillManagerLogTest` 3 + `SkillInjectionWiringTest` 6 + `StorageAccessSkillDirectoryTest` 3 + `SkillLibraryTest` 2 | 判据不动；两层注入实际效果 `NotMeasured`（D66） |
| B5 记忆模块 | `MemoryEvictionTest` 15 + `MemoryModuleTest` 22 + `VectorIndexTest` 9 + `MemoryModuleRetrievalTest` 3 + `MemoryRetrievalWiringTest` 5 + `SemanticRetrieverTest` 9 + `SmartMemoryRetrievalTest` 9 | 真机排序未重测 |
| B6 写入先于压缩 | `MemoryBeforeCompressionTest` 5 | 真机未重测 |
| B7 全局会话 + notify_user | `AgentLoopHistoryTest` 7 + `NotifyUserTest` 13 | 真机往返未重测 |
| B8 兜底 + 降级 | `NotifyFallbackTest` 8 | 真机未重测 |
| B9 硬依赖 | `HardDependencyTest` 8 | 真机未重测 |
| B10 并发规则 | `RouteConcurrencyTest` 4 | 真机未重测 |
| B11 Laya 闸门 | `ProactiveGateTest` 21 + `ProactiveGateWiringTest` 7 | 真机闸门/GPU 延迟未重测 |
| C1 透明背景 | 设备截图行；本轮未重测 | 不声称重新验收 |
| C2 上下文展示 | JVM 锚点绿：`ConversationViewTest` 3、`ConversationOrderTest` 5、`SaidSequenceGateTest` 3、`HintPresentationTest` 10；`HintAutoScrollTest`/`HintRevealTest` 已由 0.23 P4 的 `HintTickerTest` 6 取代（D132），不再作为当前读数 | 眼镜真机观感/触控见 §2.3–§2.4；0.21 观感未重测 |
| C3 加载动画 | `BusyStateTest` 9 + `LoadingIndicatorTest` 4 | 真机动画未重测 |
| C4 唤醒响应 | `WakeChimeTest` 8；TTS「在的」已由 D135 删除；P5/P6 的设备 `P5_CHIME` 证据见 §2.5 与 [P6 走查](evidence/2026-10-18-0.23-p6-device-walkthrough.md) §5.2/§5.3；`played` = sink 接受音调，不代表可听（D153） | 真机可听性/声学耦合/设备侧 800 ms 去抖未测 |
| C5 主动辅助介入 | `ProactiveGateTest` 21 + `ProactiveGateWiringTest` 7 | 真机主动回合未重测 |
| C6 手机端菜单 | 设备截图行；本轮未重测 | 不声称重新验收 |
| C7 手机端主界面 | `PhoneNavigationTest` 6 | 真机截图未重测 |
| C8 底部四页签 | 设备截图行；本轮未重测 | 技能页设备渲染/删除未验 |
| C9 上下文切换 | 设备截图行；本轮未重测 | 不声称重新验收 |
| C10 CXR 会话迁移 | 设备截图行；本轮未重测 | 不声称重新验收 |

> 本表只证明 JVM 锚点在 0.21 收口全量回归中仍绿；C1/C6/C8–C10 是设备截图行，C2/C3/C4/C5/C7 的部分内容依赖真机观感，本轮未重测。`HintVisibilityTest` 是 instrumentation，按约束未运行。

---

## 5. 0.21 版本级结项

### A. 范围内通过

- JVM 已 Verified 的 AC 行：AC-1、AC-1b、AC-2、AC-3、AC-4、AC-5、AC-6、AC-7（DeepSeek 设备半边）、AC-8、AC-9、AC-10、AC-11、AC-12、AC-13（JVM 判据）、AC-14（JVM 判据）、AC-16（JVM 判据）、AC-17、AC-18。
- F4 机制成立/未证伪；B 处理形态有效。
- 旧行 B1–B11 与 C2/C3/C4/C5/C7 的 JVM 锚点随 310/2671/0/0/0 全绿。
- 测量协议与数据：预登记先于数据；独立审计 8/8、0/19 标签不一致；清理已验证。

### B. 范围内未通过 / 不完整

1. **M2 0/10**：已测有效失败，不读成未测。
2. **AC-13/16 设备行为失败**：应用工具看不到已安装且可解析的 `com.miui.player`；JVM 判据仍 Verified，但不得写 device-pass。
3. **A5 缺失**：A 组只有 n=9；按预登记边界不构成证伪，但不是完整 10/10。

### C. 范围外未测（逐条 `NotMeasured`）

A5 的 F4/M2；AC-15；AC-1 设备半边；AC-4 设备半边；AC-7/AC-8 OpenAI 设备半边；AC-10 `P3_ACT`；AC-14 真机行为；AC-17 真机行为；AC-18 `P3_SKILL pull=`/catalog；M2 媒体元数据；AC-13/16 的确切 MIUI 原因；模型档案 id；Phase 5/6 遗留（两层注入效果、K/上限标定、跨进程、贡献度、目录 token 成本、真机压缩、R-9 精确上限、`truncated=true` 真机路径、`SHELL_NOT_BOUND` 复现）。

### D. 已知降级

- **AC-14**：`applies_when` 条件过滤生产未接线；接线是加法式，时效性是会话粒度。
- **R-8**：`set_alarm`/`compose_note`/`dial` 本版不可用；0.21 验收口径工具面 9 个，**不得声称工具面完整**。
- **代码/文档 drift**：生产 `ExecutionRouter` 在通道可用时仍广告 **6 个 Intent 工具 + 5 个 Shizuku 特权工具（4 个 L3/L4 + `set_brightness` L2）**（`open_app`/`open_url`/`open_settings`/`set_alarm`/`compose_note`/`dial`，以及 `clear_data`/`uninstall`/`disable_app`/`set_permission`/`set_brightness`），共 11 个额外动作工具；全开时 19 个动作工具 + `notify_user` + `load_skill` = **21 个**，与“9 个工具”的验收口径不一致。**本矩阵保留 0.21 的 R-8 原文与判词，不把 drift 改写为通过**；D120 只表示本轮不修，**下版本按 D121 重构工具面**。

### E. 结论

**0.21 范围内未完全通过 / 不满足完整交付标准。** F4 的正面读数不抵消 M2 0/10 与 AC-13/16 设备失败；M2 与设备失败不读成未测；任何 `NotMeasured` 不读成通过。

### F. 业主闭环决定（2026-10-17）

0.21 以本状态闭环（`edith-0.21-closed-20261017-01`），**不宣称交付通过**；应用工具下版本重构；M2 与 AC-13/16 随下版本复测；F4 不重跑；AC-15 保持 `NotMeasured`。

---

## 6. 证据索引（保留集）

| 证据 | 用途 |
|---|---|
| `evidence/2026-10-27-release-apk.md` | **0.4 正式（release）APK：phone 1,090,267,283 B / `6B6B26…3DDA12`、glasses 24,490,325 B / `C90DE6…6FE`；release 变体 + zipalign + preview debug V3 签名；K40 覆盖安装 Success、P4_MODELS ready、无 FATAL；干净安装/release provisioning/字节可复现 NM** |
| evidence/2026-10-28-detached-wake-ack-echo-mute.md | **D208 脱离唤醒「我在」回声静默替换：窗口内麦帧换零、WAKE 不延迟、眼镜/回答/听写不受影响；JVM 35 定向 + app 148/1693 绿；真机待业主复测** |
| `evidence/2026-10-27-0.4-final-acceptance.md` | **0.4 Task 13e 最终 K40 矩阵：D3 真实开关/持久化/运行中保存、D4 严格本地（neutralization 后 60 s 零 outbound + 30 s 脱离窗口）、D5 合成唤醒、D7/D8/D9 输入、D10 眼镜回归、D11 权限拒绝；含 9 张截图资产；合成 TTS/脚本模型；真实人声/keyguard/release/原生视口等 `NotMeasured`** |
| `evidence/2026-10-27-0.4-kws-epoch-reset-device.md` | **0.4 KWS epoch reset（`b2d03c06`）：换音频 epoch 重置 8 条 KWS 流；K40「眼镜会话 → 脱离 → 手机麦」有利素材 5/5，但同一 APK 冷启动脱离对同一素材 0/3；合成读数不稳定；2026-10-27 业主真人验收 KWS 可唤醒，D5 Verified；召回率/误触发/多轮切换/长稳 `NotMeasured`** |
| `evidence/2026-10-27-dictation-tail-asr-fix-report.md` | **0.4 Task 13d：听写尾音 → typed `busy` 根因/路由闸门修复（`c3b4272a`）；进程内 stop/start 后 ASR 静默的 `SpeechPipeline.rearm()`；K40 合成 TTS 设备复验与 `P3_TRANSCRIPT_ROUTE`/`P3_PIPELINE` 遥测；真实人声/fault code `NotMeasured`** |
| `evidence/2026-10-27-0.4-typed-dictation-final.md` | **0.4 Task 13c 最终二进制 D7–D11：文字/听写/唤醒直达/眼镜模式/权限拒绝；脚本模型+合成 TTS；D8 `busy` 与切页签丢草稿的如实边界** |
| `evidence/2026-10-27-0.4-typed-dictation-fix1-retest.md` | 0.4 Task 13b fix1 复测：D10 眼镜模式听写 `final chars=16`、CXR 不重启；D8/D9 复跑；预修复 D10 `NotMeasured` 被取代 |
| `evidence/2026-10-26-0.4-typed-dictation-device.md` | 0.4 Task 13 预修复设备矩阵 D7/D8/D9/D10（脚本模型/合成 TTS）；D10 听写转写 `NotMeasured`、D11 本构建 `NotMeasured` 的历史记录 |
| `evidence/2026-10-26-0.4-detached-wake-device.md` | **0.4 脱离模式 D3/D4/D5/D5b/D6/D10/D11：fix2 真实开关/持久化/服务与通知、手机麦、screen-off 唤醒、主动辅助有效值、眼镜回归、权限拒绝；keyguard/mic busy/真实人声 `NotMeasured`** |
| `evidence/2026-10-26-0.4-bundled-models-device.md` | **0.4 内置模型：debug 完整 APK 1,111,007,965 B、首次 materialize 13.79/12.81 s、二次 0.453 s、14/14 SHA；当时包内 10 份许可证文本；release/干净安装/空间/字节可复现 `NotMeasured`** |
| `evidence/2026-10-22-asr-fp32-decoder-and-continuous-dialogue.md` | **0.3 ASR 复读与持续对话修复：fp32 decoder 设备 A/B（int8 复读 vs fp32 干净）、最终 APK/bundle 哈希、唤醒路线 queue/consume 设备日志、主动路线 JVM 队列、11 模块 gate 359/3936/0/0/2、`NotMeasured` 清单** |
| `evidence/2026-10-21-0.3-tool-result-contract.md` | **0.3 Phase 2.2 工具结果契约：build `e892ccb1`、gate 317/3366/0/0/2、T6 run 7 Passed (device)、T7 run 8 Partial、设备 base hash raw 同哈希、P-08 本轮 Partial、R-01..R-07 的 `NotMeasured` 清单；旧判词不改** |
| `evidence/2026-10-21-0.3-auto-vision.md` | **0.3 Phase 2.1 自动视觉/截图压缩：build `c5f55d82`、gate 314/3236/0/0/2、T6 run 6 Passed (device)、T7 run 7 Partial、P-08 本轮 Partial、`NotMeasured` 清单；旧判词不改** |
| `evidence/2026-10-20-0.25-corpus-results-final2.md` | **Task 28 最终实现（C1DB）上的 27 行重跑：3/15/9、输入哈希 27/27、allow 0、T6 前置、M2/AC、R148 重判** |
| `evidence/2026-10-20-0.25-acceptance-corrections.md` | Task 22 验收记录纠错：AC-16 strict 1/9、T3×3 `NOT_MEASURED`、6/12/9；Task 16 superseded 指针与 D40C/4E72 构建口径 |
| `evidence/2026-10-20-0.25-corpus-results-final.md` | Task 19 27 行原始测量（D40C、TTS-off；原文 6/15/6）；T3 三行由纠错文件重分类 |
| `evidence/2026-10-19-0.25-corpus-results.md` | Task 16 修复前读数（`F461EC43…`，4/17/6）——**历史，已被 Task 19/28 取代**，不用于当前判词 |
| `evidence/2026-10-20-0.25-tts-confirm-verification.md` | Task 21 长 TTS/确认弹窗复验（4E72；TTS PASS、外部坐标点击 PASS） |
| `evidence/2026-10-18-0.24-final.md` | 0.24 S1–S5 最终汇总：实现、JVM 314/3024、设备读数、U-12–U-20、carries 与业主裁决 |
| `evidence/2026-10-19-0.24-settings-overlay-platform.md` | Settings 下层平台保护：window flag/permission、`Hiding System Alert Windows`、独立 overlay 探针、ANR 链、SoundRecorder/launcher 对照、业主接受 |
| `evidence/2026-10-19-0.24-s5-production-walkthrough.md` | Task 21 S5 生产设备走查重跑（CXR scene/唤醒/面板/真实回合/120 s） |
| `evidence/2026-10-19-0.24-s5-cxr-scene-fix.md` | 透明 CXR scene 存活与 launcher 可见性修复（含 62/64 tests） |
| `evidence/2026-10-18-0.24-jvm-regression.md` | 0.24 全量 JVM 314/3016/0/0/2（`b1d48a2` 前历史读数）回归记录 |
| `evidence/2026-10-18-0.24-device-walkthrough.md` | Task 21 首轮设备走查（生产 CXR 阻塞记录，保留为历史；诊断段不是验收证据） |
| `evidence/2026-10-18-0.24-s1-overlay-spike.md` | S1 悬浮窗/侧键/息屏/CXR 音频设备门禁 |
| `evidence/2026-10-18-0.23-p6-regression.md` | P6 全量 JVM（301/2597/0/0/2）与 U-11 路由隔离 JVM 覆盖 |
| `evidence/2026-10-18-0.23-p6-device-walkthrough.md` | P6 两端构建/安装/走查、U-11 渲染层双端、CXR 唤醒/STOP/中文/去抖 |
| `evidence/2026-10-17-phase-7-f4-measurement.md` | F4/M2、AC-13/16 设备 finding、0.21 收口；原始正文只在 `.local/` |
| `evidence/2026-10-16-phase-5-device-verification.md` | R-9、Phase 5 设备读数 |
| `evidence/2026-10-16-phase-3-device-verification.md` | AC-7 DeepSeek 设备半边 |
| `evidence/2026-10-17-shizuku-reply-ceiling.md` | R-9 壳层回复上限 |
| `evidence/2026-10-16-a11y-capability-survey.md` | 无障碍工具面能力 |
| `evidence/2026-10-16-shizuku-shell-capability-survey.md` | Shizuku 工具面能力 |
| `evidence/2026-10-14-hint-ceiling.md` | D63 提示 400 真机 |
| `evidence/2026-10-15-b4-skill-effect-ab.md` | B4（D66 搁置） |
| `evidence/2026-10-15-phase-c-device-acceptance.md` | 0.2 Phase C / C 行设备 |
| `evidence/2026-10-14-phase-b-device-acceptance.md` | 0.2 Phase B / B 行设备 |
| `evidence/2026-10-08-p2-demo-fidelity-alignment.md` | D41 demo 保真 |
| `evidence/2026-10-08-p2-device-closure.md` | D41/P2 设备闭环教训 |
| `evidence/2026-10-13-phase-a-performance-k40.md` | Laya GPU K40 读数 |
| `evidence/2026-10-15-laya-int8-npu-device-readings.md` | D64 NPU 不可用 |
| `evidence/2026-10-14-b11-laya-gpu-device.md` | D64 GPU 设备 |
| `evidence/2026-10-15-b11-gate-on-device-finetuned-gpu.md` | B11 闸门设备 |
| `evidence/2026-10-15-phase-c-noul-threshold.md` | B11 `noul` 阈值 |
| `evidence/2026-10-15-b3-async-summary-device.md` | B3 异步摘要设备 |
| `evidence/2026-10-15-b5-ordering-contrast-device.md` | B5 排序对照设备 |
| `evidence/2026-10-14-b5-semantic-ranking-probe.md` | B5 语义排序探针 |
| `evidence/2026-10-14-b8-notify-fallback-device.md` | B8 兜底设备 |
| `evidence/2026-10-10-p3-3-f1-f4.md` | D49 人时预算 F1/F4 |
| `evidence/2026-10-11-p3-3-layout-i1-i3.md` | D49 I-1 晚答/提示有效期 |
| `evidence/2026-10-14-k40-session-bringup-and-wake-defect.md` | 唤醒/会话起不来（D53③） |
| `evidence/2026-10-14-wake-recall-and-turn-path-k40.md` | 唤醒召回/回合路径 |
| `evidence/2026-10-13-session-and-memory-full-chain.md` | 会话与记忆全链 |
| `evidence/2026-10-14-embeddinggemma-semantic-smoke-k40.md` | Embedding 语义冒烟 |
| `evidence/2026-10-13-embeddinggemma-tokenizer-is-bpe.md` | 分词器 BPE 根因 |
| `evidence/2026-10-15-official-assistant-vad-asr-locality.md` | 官方助手 VAD/ASR 本地性 |
| `evidence/2026-10-15-edith-asr-log-derived-metrics.md` | ASR 日志派生指标 |
| `evidence/2026-10-29-0.5-phone-only-floating-ball-device.md` | 0.5 纯手机模式（0.5 预览名「纯手机模型」）：悬浮球/气泡/TTS/回声半双工设备证据。**该文件在 `2c3621a4` 被原地改写**（APK 91,182,525 B/`D94CD646…` → 93,315,166 B/`17863B0A…`；K8/K10 及 K2 长按/拖拽判词同时被改）；旧文件保持现状，不作“未变”证据 |
| `evidence/2026-10-29-0.5.1-correction.md` | **0.5.1 更正 append-only 证据**：旧读数（git `29626079`/`1295edbd`）与新读数并列；0.5.1 APK 91,240,820 B / `7DBC6B69…CA17044`（设备回读同哈希）；全量 JVM 456/4,806/0F/0E/2S；K40 K1–K11 的 Verified/Partial/NM；原版 tap-only 探针与长按/拖拽 NM 重分类、0.5 证据改写记录 |

> 本表是**当前判词直接引用的证据索引**（截至 2026-10-29 共 **57 份**：历史 47 + 0.4 8 + 0.5 1 + 0.5.1 1）。`docs/project/evidence/` 目录中另有历史阶段证据，保留但不进入当前判词；**不因未列入本表而删除，也不得用未列入者支撑当前判词**。新增引用须先补入本表；证据内容不可改写，旧证据不得因新读数重写。

---

## 7. 维护规则

1. 新验收行追加到 §2；旧行判据不改，必要时就地更正**计数**并注明来源。
2. 证据缺失时只能记 `NotMeasured`，不得补造测试或引用未产生的数字。
3. 有效失败与 `NotMeasured` 分开记；不得互相抵消。
4. 版本级判词只在 `STATUS.md` 与本节同步修改；两处必须逐字一致。

## 0.3 Phase 4A 工具面变更（2026-10-22，D196）

- `launch_app` 已退役（工具面 24 → 23：17 atomic + 4 umbrella + 2 交付）；`open_app` 保留为"已解锁且 Shizuku 不可用"的唯一启动兜底，歧义改为 `APP_AMBIGUOUS` + 候选（可重试），未找到改为 `APP_NOT_FOUND`（此前两者都被吞成 `APP_NOT_VISIBLE` 且 `recoverable=false`）。
- 应用打开路由改为 `open` 优先（包名/候选/启动后校验），`open_app` 仅兜底；`open` 描述里"精确包名可用 `launch_app`"的错误指引已修正。
- 上文 P4-A 的"24 默认工具 / `P4_TOOLFACE tools=24`"是 2026-10-22 变更前的历史判词，按 D195 保留不改；D196 之后的当前口径为 23。
- 证据边界：JVM 测试已更新（`ToolContractTest`/`ToolFaceWiringTest`/`ToolFaceCompositionTest`/`ToolLabelMapTest`/`PromptArchitectureTest`/`PromptCatalogTest` 等）；K70 同一回合 4 步仅 1 次成功为触发证据，K70 非验收设备（D46/D65），K40 未复跑。
