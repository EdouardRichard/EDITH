# EDITH 技术与产品决策登记（0.21 基线）

- **锚点**：EDITH 0.21（`edith-0.21-closed-20261017-01`）
- **状态**：只登记**仍生效**或**部分生效**的决策；被完整取代、一次性授权/测量、纯 checkpoint 裁决已删除（git 历史保留）。
- **状态词**：`Active`＝整条有效；`Partially superseded`＝个别子条被新决策取代，条目内写明。
- **ID 规则**：编号不重排、不复用；删除的编号不再分配给新决策。新决策追加新编号，并写明取代关系。
- **代码引用底线**：`D33`、`D35`、`D41`、`D46`、`D47`、`D49`、`D53`、`D57`、`D60`、`D73` 被代码/测试直接引用，必须保留且与实现一致。
- **职责**：本文件记录"为什么这么定"；"现在是什么"见基线架构规格；"证明了什么"见 `ACCEPTANCE.md`；"现在在哪"见 `STATUS.md`。

> **一次消歧**：`DECISIONS.md` 曾有两条同名 D59（2026-10-12 的 P2 工作树清理、2026-10-13 的 System-One 边界）。工作树清理条目已删除；**本文件的 D59 只指 System-One 边界条目**。删除条目不重新编号。

---

## 1. 范围、环境与版本

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D01 | 2026-10-05 | 目标平台：Android；大陆版 Rokid 眼镜；用户报告固件 `1.26.011`；有开发数据线 | 是用户报告，不是探针结果；实际 SDK/固件兼容性由实测确认 | 基线规格 §1；AGENTS 设备节 | Active |
| D05 | 2026-10-05 | 手机 CXR-L + 眼镜 CXR-S；模型可配置 | P4、声纹注册、转写开关已由 D57/D53 取代，不在本版范围 | 基线规格 §1/§7 | Partially superseded |
| D11 | 2026-10-05 | Kotlin / Compose / Coroutines；眼镜按官方轻量 Android Sample | 技术栈；不把候选写成已锁定能力 | 全部模块 | Active |
| D12 | 2026-10-05 | 编译期插件注册；插件只依赖公共契约；不加载第三方动态 APK | 首版无第三方代码 | `plugin-api`、`HostRuntime` | Active |
| D13 | 2026-10-05 | sherpa-onnx 作为 VAD / 本地中文 streaming ASR / 声纹的引擎栈 | 候选已成为实现；设备兼容性按实测 | `audio-android` | Active |
| D14 | 2026-10-05 | OkHttp + 通用协议设施；插件拥有业务调用；文本 SSE 起步 | 供应商兼容性不预设 | `model-runtime` | Active |
| D16 | 2026-10-05 | Keystore 管理密钥；敏感字段/数据库加密；首版不自建服务端 | 隐私与凭证边界 | `storage-runtime` | Active |
| D17 | 2026-10-05 | 首提示文字 P95 ≤ 3 s；延迟/触发/错误率分开统计 | 是目标，不是实测 | `ACCEPTANCE.md` | Active |
| D19 | 2026-10-05 | 工程流：Superpowers + 项目级状态/决策/契约/验收/交接；不并行启用 Spec Kit 管理同一计划 | 单一流程，避免双重账本 | 本文件与 `STATUS.md` | Active |
| D24 | 2026-10-06 | 开发测试用 DeepSeek，本地配置 API key；正式 App 由用户配置 | 凭证不落仓库 | `model-runtime`、STATUS | Active |
| D25 | 2026-10-06 | DeepSeek 开发测试采用 Responses API | 适配器选择；不影响正式多模型 | `model-runtime` | Active |
| D28 | 2026-10-06 | DeepSeek 仅用于开发测试；正式 App 供用户配置多个模型（纯文本/图片/语音输入多模态） | 不在代码里写死供应商 | 基线规格 §6 | Active |
| D37 | 2026-10-07 | 当前调试/验收设备为红米 K40：`2696813b` / `M2012K11AC` / alioth / Android 13 / API 33；不继承旧 K70 性能 | D46 明确不替代本条；K40 仍是设备基线 | 基线规格 §9 | Active |
| D40 | 2026-10-08 | 新建 `glasses-app`（眼镜端 CustomApp），完成不依赖人工的代码与验证 | 人工项先标记后验证 | `glasses-app` | Active |
| D46 | 2026-10-10 | 去除 K70 / Android 16 适配；K40 即交付目标；K40 实测即目标设备实测 | 取代旧 K70 报告；历史 K70 证据保留、不外推 | 基线规格 §1/§9；AGENTS | Active |
| D57 | 2026-10-12 | P4（面试助手）在本版本搁置：当前版本不做该插件 | 模块与注册保留，但不展示、不可激活；简历/JD/声纹归因/本人上下文/面试官触发不在本版范围 | 基线规格 §1；`PluginPicker` | Active |
| D58 | 2026-10-12 | 语音输出＝手机侧合成 + Android 原生音频输出（A2DP 到眼镜）；眼镜端 TTS 不做 | K70 A2DP 证据是 D46 的显式例外，仅限该条证据；不得读成 K40 已验证眼镜播放 | `AndroidSpeechPlayer`、`PlaybackGate` | Active |
| D65 | 2026-10-15 | 验收设备＝K40；K70 不单独做设备验收；K40 通过 ⇒ K70 通过（单向） | 反向不成立；SoC/厂商特有通路与 K40 不存在的硬件不适用 | 基线规格 §9 | Active |
| D66 | 2026-10-15 | B4（SKILL 效果）验证搁置，由业主单独优化 | 不改实现、不改验收词；B4 在 `ACCEPTANCE.md` 记 `NotMeasured`（实现与 JVM 测试在、真机语义效果未显示）；`ImplementedUnverified` 是旧词，不再使用 | `ACCEPTANCE.md` B4 | Active |
| D67 | 2026-10-16 | 0.2 不满足交付标准；Phase D 跳过；版本关闭；转入 0.21 | 0.2 的成果保留；不得读成已交付 | 基线规格 §1；STATUS | Active |
| D69 | 2026-10-15 | 0.21 范围 = (b)：修复生产链路（记忆被当成指令的症状）+ 完整重做会话/压缩 | 后续扩展为请求装配、记忆规范化、SKILL 生命周期、工具面、测量 | 基线规格 §1 | Active |
| D121 | 2026-10-17 | 0.21 以"不满足完整交付标准"闭环；应用工具下版本重构；M2/AC-13/16 随下版本复测；F4 不重跑 | 不宣称交付通过；AC-15 保持 `NotMeasured` | `STATUS.md`、`ACCEPTANCE.md` | Active |

## 2. 架构、契约与设备交互

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D41 | 2026-10-08 | 眼镜↔手机的所有交互 100% 按官方 demo/样本实现（层级、API、`Caps` 载荷形状与顺序） | "与 demo 一致"即正确性判据；硬件/功耗/可读性仍需实测 | `transport-rokid`、`glasses-app` | Active |
| D42 | 2026-10-08 | 眼镜 `SHOW` 上屏按需点面板：标准 Android 屏幕唤醒锁，只在提示显示期间持有，到期/清屏/断连/onDestroy 释放，15 s 上限兜底 | 厂商无主动亮屏 API 时才用平台官方 API | `glasses-app` | Active |
| D44 | 2026-10-08 | 把 demo 保真教训写进 `AGENTS.md` 铁律 | 回调层级、订阅方式、`Caps` 字段都照厂商样本 | `AGENTS.md` | Active |
| D45 | 2026-10-08 | 合并/交付后必须从权威检出用合并后的树构建并跑测试；`.gitignore` 目录模式根锚定；注意 Windows 大小写不敏感 | 工作树绿不等于合并树绿 | 工程纪律 | Active |
| D59 | 2026-10-13 | System-One（Laya）是**决策模型**，只支持 choice/score/noul 类判断，不做摘要/事实提取/对话生成 | 设备目标部分由 D46/D65 取代；接口动词由 D61 取代；其余边界仍有效 | 基线规格 §3/§7 | Partially superseded |
| D61 | 2026-10-13 | `SystemOneService` 收敛为单一 `ask(state, question)`；不保留三动词薄封装；`latencyMs` 入契约 | `instructions` 是闸门要判断的那句话；问题类型是数据不是方法 | `plugin-api/SystemOneServices.kt` | Active |
| D64 | 2026-10-15 | Laya 主动介入闸门跑在手机 **GPU**；CPU 是唯一允许降级；**NPU 不作为运行后端** | 目标机 NPU 全 Inf / 量化不可用；GPU 已验证同值；微调集设备推理仍属未测项 | 基线规格 §7 | Active |
| D71 | 2026-10-15 | `RequestFrame` 拼装是 `agent-runtime` 的唯一职责；插件只贡献 `Segment`，不再构造消息数组 | 新增纯 JVM 模块，避免三个 owner | `agent-runtime` | Active |
| D74 | 2026-10-16 | 会话按路由：每路由一条 `ConversationStore`；会话与循环解耦 | 有界、可压缩；回合执行器单次使用 | 基线规格 §7 | Active |
| D80 | 2026-10-16 | `ModelRequest` 收窄为纯线缆形状；`ContextPart` 与三处 `context` 全部删除 | 不删则"记忆所有者"永远有两个 | `plugin-api`、`model-runtime` | Active |
| D83 | 2026-10-16 | 工具面作为 0.21 前置任务；排除 Intent 通道（`open_app`/`open_url`/`open_settings`/`set_alarm`/`compose_note`/`dial`） | "打开 App"由 `launch_app` 承接；`set_alarm`/`compose_note`/`dial` 无承接；**代码仍广告 6 Intent + 5 Shizuku 特权工具（4 个 L3/L4 + `set_brightness` L2），见基线规格 §4.9/§10 drift**；下版本按 D121 重构 | 基线规格 §4.9/§10 | Active |
| D95 | 2026-10-16 | 一切设备侧人工操作模拟走 `edith.phone.a11yprobe`；禁止 `adb shell input tap/text` 替代；探针不装回 | 它同时是邻居工作线的仪器，动前先确认 | AGENTS 设备节 | Active |

## 3. 请求、会话、预算与完成判据

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D47 | 2026-10-10 | 确认窗口 120 s；插件闸门与手机 UI（卡片/对话框读秒、通知 TTL）全部由这一个数派生 | 闸门是超时的权威；宿主只展示、不结算 | `SmartConfirmation` | Active |
| D48 | 2026-10-10 | 确认问题必须可感知且弹在主屏：新通知通道带声音/震动；`SYSTEM_ALERT_WINDOW` + 后台拉起；模态对话框（标题/摘要/允许读秒/拒绝/急停），到点自动关闭 | 三项权限由用户授予，缺一项按降级处理 | 确认链路 | Active |
| D49 | 2026-10-10 | 人时不计入回合预算；回合预算与单次模型请求上限拆开；等待确认顺延 deadline，提示有效期同步顺延 | 量测墙钟、`try/finally`；通道异常也不惩罚已花时间 | `AgentLoop`、插件 | Active |
| D62 | 2026-10-14 | I2：第一条通知保持模型通道，第二条起 `voice`/`both` 降级为 `screen`；I3：超长通知不截断，由眼镜滚动显示 | §6.3 的 system message 本版不实现，如实记已知偏离 | 通知/上屏 | Active |
| D63 | 2026-10-14 | 提示上限 400 三档真机可交付；手机与眼镜必须同一次构建；>120 的提示在旧眼镜 APK 会被静默丢成 `undecodable` | 400 不是链路物理上限；>400/emoji/佩戴可读性未测 | `HintPolicy`/`HintWireCodec`/`ReadableHintAssembler` | Active |
| D68 | 2026-10-15 | 基线纪律：契约可动，但 B1–B11、C1–C10 的判据逐条保持通过；重写测试不改判据 | 每行要有"重写后仍验证同一行为"的证明 | `ACCEPTANCE.md` | Active |
| D70 | 2026-10-16 | 唤醒路径不设端到端延迟预算守护；1.5 s 首提示目标只约束主动辅助 | 唤醒与主动辅助分开 | 基线规格 §4.3 | Active |
| D72 | 2026-10-16 | 记忆不进系统帧、**不写入 `ConversationStore`**；在 `RequestFrame` 中是独立带标签段，位于本轮 `USER_INPUT` 之前；**线上**由网关折进首条 user 历史消息（首轮无 user turn 时折进 `input`） | 缓存前缀 + 信道不赋权两条理由 | `MEMORY_EVIDENCE` | Active |
| D77 | 2026-10-16 | 职责边界＝界面状态正确，不是物理世界真的发生 | 播放＝界面进入播放中；回复＝消息出现；屏幕变了就是判据本身 | 验收判据 | Active |
| D78 | 2026-10-16 | 两层完成判据：动作层（界面前后比对）+ 任务层（模型 `final=true` + 上屏证据）；上屏不驱动终态 | 眼镜断连不能把成功任务报成失败 | `AgentLoop`/上屏 | Active |
| D79 | 2026-10-16 | 每个动作类工具返回 `applied`（界面真的变了）+ `note`；每步要么可观测，要么显式声明不可观测 | 声明不可观测的步骤不把 `applied=false` 报成失败 | 工具面 | Active |
| D81 | 2026-10-16 | 预算＝统一窗口预算；超限按整回合/整条记忆省略，不截断；**0.21 规格里的“每块 maxBytes”未实现（drift）**；裁剪顺序旧回合→最不相关记忆→压缩（②在带回合组时通常 no-op，见 D97） | `ContextBudgeter`/`TurnTrimmer` 都是单一总预算；截断的记忆会变成另一条陈述 | `ContextBudgeter`/`TurnTrimmer` | Active |
| D82 | 2026-10-16 | 压缩摘要带 `origin=SUMMARY` + 显式边界标记，措辞不可能被读成用户发言 | 摘要与真实用户消息必须单向可判 | `SummaryBoundary` | Active |
| D97 | 2026-10-16 | 裁剪顺序固定为三刀：①旧整回合 → ②最不相关记忆 → ③压缩；本轮 `USER_INPUT` 按构造不被裁 | ①②同步，③决定同步、摘要异步；**②在带回合组的流上通常是 no-op**（记忆随①一起离开并记 `droppedTurns`，只在 `[memory, USER_INPUT]` 时单独生效，业主 2026-10-16 批准）；`USER_INPUT` 单独超限仍拒绝 | `TurnTrimmer` | Active |
| D98 | 2026-10-16 | 摘要可识别来自结构（`origin=SUMMARY`/`trusted=false`/边界），不是文本匹配；分离方式削弱为"穿在首条 user 消息里" | 三笔代价如实记：AC-6 分离削弱、AC-1 落位削弱、真实 user 回合数少一 | `ConversationStore`/`ContextCompressor` | Active |
| D99 | 2026-10-16 | 记忆与摘要**在线上**折进首条 user 历史消息；`RequestFrame`/`ConversationStore` 层仍是独立段；`AC-1b`（记忆不进系统帧）不受影响 | 独立前置 user 消息会被预算形状校验拒绝；开头的 assistant 消息也会被拒 | 基线规格 §7 | Active |
| D100 | 2026-10-16 | 压缩门＝`droppedTurns > 0 && compressionOwed`；`compressStore` 自己施加合取门 | 摘要不为当前请求腾空间，只影响下一回合 | `ContextCompressor` | Active |
| D101 | 2026-10-16 | R-9＝每条流 8 MiB 内存上限 + 可分辨 carrier 码；调用没回来与回包看不懂分开 | 8 MiB 是特权进程内存上限，不是链路上限；325 000 B 成功/350 000 B 失败；不得写"8 MiB 链路"或"325 kB 上限" | `PrivilegedShell` | Active |
| D102 | 2026-10-16 | 两条会话路径淘汰语义故意分开；一条已知窄竞态记账不修 | context 路径只测试使用，判据冻结；竞态修法留给后续 | 基线规格 §7 | Active |
| D103 | 2026-10-16 | 全量回归＝`test` + `testDebugUnitTest` 两个变体同一命令同跑；计数只从 JUnit XML 聚合，排除 `probes/` 结果 | 两个变体覆盖不重叠模块集 | `ACCEPTANCE.md` | Active |

## 4. 记忆与 SKILL

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D51 | 2026-10-11 | P3.4 记忆范围：记忆 + 标注集评测全做；记忆取最强实现；隐私边界不突破 | 日志只记计数/id；记忆写入零网络；注入受会话运行约束 | 记忆模块 | Active |
| D60 | 2026-10-13 | SKILL 全量注入；Laya 改做主动辅助闸门；state 无时间特征 ⇒ 纯事件驱动；节流复用 P3；不做投递性闸门；Laya 维持硬依赖 | **第 1 条由 D105/D73 取代**；第 2–6 条仍有效 | 基线规格 §4.3/§4.8/§7 | Partially superseded |
| D73 | 2026-10-16 | SKILL 两层注入：系统帧放有界目录（name + description），正文由 `load_skill` 按需 pull | 正文不常驻前缀；多一次往返是代价 | `SkillManager`/`LoadSkillTool` | Active |
| D75 | 2026-10-16 | SKILL 定义＝程序性知识四要素；`examples` 不存在；`triggers` 不再作为选择依据（代码 drift：字段仍被解析但无消费者）；新增 `applies_when`/`not_applies_when`（只允许机器可查条件） | 两层目录让 triggers/examples 没有位置；生产 evaluator 未接线 | `Skill.kt`/`SkillParser` | Active |
| D76 | 2026-10-16 | SKILL 作者回路＝L1：人工审阅 + 测试效果 | 对应人工策展档 +16.2 pp；自生成 −1.3 pp | 作者流程 | Active |
| D87 | 2026-10-16 | 规范化在写入侧：落库时一次抄写；保留原话 + 派生规范形 | 召回侧只做排序/下限/包装，不做语义改写 | `MemoryNormalizer` | Active |
| D88 | 2026-10-16 | 语义口径 (a)：保留"这是一次请求"的事实；抽象成偏好否决 | 与 C4 冲突且无证据 | 记忆渲染 | Active |
| D89 | 2026-10-16 | 改写方式＝纯模板（零模型调用、零新增依赖） | 模型改写的失败模式是"改错意思"，更糟 | 记忆写入 | Active |
| D90 | 2026-10-16 | 落库双存：原话（C4 + 用户可见）+ 规范形（供注入） | 只存规范形违反 C4 | 记忆存储 | Active |
| D91 | 2026-10-16 | 两份都索引：原话 embedding + 规范形 embedding，排序取较高者 | 只换规范形会作废 B5 实测 | 记忆检索 | Active |
| D92 | 2026-10-16 | 相关性下限本轮不定数值；只交付可注入 seam + 计数探针 | 默认关闭＝行为不变；标定需真实语料 | `MemoryModule` | Active |
| D105 | 2026-10-17 | D60 第 1 条失效：SKILL 改为两层注入，全量注入退出生产 | `SkillManager.injection()` 只作回退参考，不得接回生产 | `SkillManager` | Active |
| D106 | 2026-10-17 | 记忆与 SKILL 共用三态生命周期：ACTIVE→DORMANT→RETIRED；任一 pull 整体免 drift；两道闸防过早退役 | 通用实现只含 id/三态/计数；不落盘 | `LibraryGovernor` | Active |
| D107 | 2026-10-17 | drift 只对"本轮真正被列出的条目"计数 | 未提供过的条目不能被判"未使用" | `LibraryGovernor` | Active |
| D108 | 2026-10-17 | disabled 或文件被删除的条目在治理之外：不占目录位、不被 cap 降级、不推进状态 | 防止幽灵占位挤掉在库条目 | `SkillManager` | Active |
| D109 | 2026-10-17 | 降级与退役不静默：逐条记名字，正文永不进日志 | 只记计数/名字/原因码 | `P3_SKILL` 日志 | Active |
| D111 | 2026-10-17 | 两条路由共用一个 governor ⇒ 回合所有权＝owner generation + 一把锁 | WAKE 拥有并清理；PROACTIVE join；无活 owner 时接管 | 插件技能回合 | Active |
| D112 | 2026-10-17 | 记忆侧复用同一生命周期模型，但生产不接线（默认 `null`） | 记忆退役需要真实召回数据；未标定数值不能当操作点 | `MemoryModule` | Active |

## 5. 工具面与执行

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D84 | 2026-10-16 | 通道分工：无障碍＝读屏/语义点击/中文输入；Shizuku＝打开 App/列应用/键盘动作/特权动作；模型不知道通道；坐标点击不暴露；**没有运行时通道回退**（D86 的 dump 降级尚未实现，属未来工作） | `ExecutionRouter` 只合并可用 backend 并按声明的工具名路由，a11y 与 Shizuku 工具集不相交 | `ExecutionRouter` | Active |
| D85 | 2026-10-16 | `launch_app` 用模糊名 + 候选列表；命中 0 → `APP_NOT_FOUND`，>1 → 返回候选，不静默选一个 | 标签来自桌面显示名；会话开始建表缓存 | `AppLaunchResolver` | Active |
| D86 | 2026-10-16 | `read_screen` 无障碍优先；Shizuku `uiautomator dump` 降级**尚未实现**（代码里只有探针使用 dump，生产无此回退） | dump 2.28 s/次；不得让降级读成正常；下版本若要实现，必须记 `via=` | `AccessibilityExecutionBackend` | Active |
| D110 | 2026-10-17 | 步数豁免走描述符声明 `countsTowardStepLimit`，不按工具名硬编码 | `AgentRun.steps` 仍记录每次调用；唯一生产 false 是 `load_skill` | `ToolRegistry`/`AgentLoop` | Active |
| D120 | 2026-10-17 | 本轮不修应用工具；继续 F4、如实记录 M2 失败 | 产品范围决定，不是测量规则变更；修复留后续增量 | 基线规格 §10 | Active |

## 6. 安全、隐私与权限

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D33 | 2026-10-06 | 固定测试句只进手机 RAM；**该测试路径不上传文本或音频**；正常会话的上传按 D53（会话运行即允许） | 新私人声纹/录音/录像/额外云上传不在授权内；D53 优先于本条的上传限制 | 采音与证据 | Partially superseded |
| D35 | 2026-10-06 | 当前流的源参数：16000 Hz / mono / 16 bit / little-endian | 是开发者来源，不是实测；PCM16 有符号解释与 duration/spectrum 仍由受控测试核对 | `PcmFormatEvidence` | Active |
| D53 | 2026-10-11 | ① 去掉会话级上传开关：会话运行即允许转写与其回合上下文发往所配置模型服务；唯一同意＝点「开始」 ② 主动辅助＝持久化全局开关，默认开、关掉后不得自动打开 ③ 取消独立连接入口，连接并入开始/停止/暂停 | 失去的保护如实记；日志只记计数/id；记忆写入零网络；仍不落盘原始音频/声纹/简历 | 上传/设置/连接 | Active |

## 7. 测量与验收

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D114 | 2026-10-17 | F4 预登记协议与 N=10：固定记忆/问句、A/B 交替、有效回合六条件、判读规则、证伪条件；数据出来后不许改判据 | 判据先于数据；n<10 按实际 n 写；n=0 → `NotMeasured` | `ACCEPTANCE.md` F4 | Active |
| D115 | 2026-10-17 | M2＝样板流程真机测量，与 F4 B 组同回合；通过判据＝B 组 10 个有效回合全部 `currentFulfilled` | 有效失败不重跑到成功；A 组 M2 只作旁证 | `ACCEPTANCE.md` M2 | Active |
| D116 | 2026-10-17 | 测量接缝 debug-only；默认生产路径逐字节不变 | 三个 debug 接收器只在 `src/debug`；release 无入口（结构上；未组装 release APK 检查） | `MemoryMeasurementForm` 等 | Active |
| D117 | 2026-10-17 | 原始输出隐私：正文只进 gitignored `.local/`；仓库只存分类/理由码/哈希/字符数 | 助手输出/ASR/记忆正文不入库、不打印 | 证据与日志 | Active |
| D118 | 2026-10-17 | 0.21 收口分层判定：范围内通过 / 范围外未测 / 已知降级；任何 `NotMeasured` 不得读成通过 | 不得用 M2 抵消 F4，也不得用 F4/B 抵消 M2 | `ACCEPTANCE.md` | Active |
| D119 | 2026-10-17 | 0.21 实际判定：F4 未被证伪（A n=9，`MEMORY=7`）；B 有效（n=10，`MEMORY=0`）；M2 0/10；AC-13/16 设备 finding；AC-15 `NotMeasured` | 判定写在数据之后，不改 D114 判据；全量 310/2671/0/0/0 | `ACCEPTANCE.md` | Active |

## 8. 变更规则

1. 新决策追加新 ID；若取代旧决策，必须在旧条目写 `Partially superseded` 或从本文件删除并在新条目写明取代关系。
2. 删除决策是删除"当前约束"，不是删除历史；旧文本由 git 保存。删除的 ID 不复用。
3. 局部实现细节按技能裁决并记录，不升级成用户审批；范围、公共接口破坏性变更、供应商/凭证/上传策略变化必须先获批准。
4. 状态词只描述决策是否生效，不描述实现是否完成；实现状态看 `ACCEPTANCE.md` 与代码/测试。
5. 代码里引用的 D 号若在本文件不存在，视为文档缺陷：要么补登记，要么改代码注释（需另立任务，不能在本文件里伪造）。

## 9. 0.22 目录 / 包名重构（D122–D127，2026-10-17）

| ID | 日期 | 决策 | 影响范围 | 状态 |
|---|---|---|---|---|
| D122 | 2026-10-17 | 交付代码统一 `com.sheen.rokid.*`；探针/工具保留 `edith.*`；vendored `com.laya` 不动 | 全部交付模块 | Active |
| D123 | 2026-10-17 | 两端 `applicationId` 改为 `com.sheen.rokid.phone` / `com.sheen.rokid.glasses`；接受一次卸载重装与重新授权 | 两端 App、设备验收 | Active |
| D124 | 2026-10-17 | 本次不改 Gradle 模块边界；只移动目录、改模块名、重组包 | 全部模块 | Active |
| D125 | 2026-10-17 | 顶层采用 `phone-app/`、`glasses-app/`、`common/`、`tools/`、`third_party/`、`docs/` | 仓库结构 | Active |
| D126 | 2026-10-17 | `common/` 只放两端 `build.gradle.kts` 都直接依赖的模块（contracts、output-runtime、test-support） | common、phone-app | Active |
| D127 | 2026-10-17 | 插件核心与手机端胶水用 `.host` 分包，避免同一包横跨两个 Gradle 模块 | plugin-smart-helper、phone-app | Active |

设计记录：`docs/superpowers/specs/2026-10-17-edith-0.22-directory-refactor-design.md`；实施计划：`docs/superpowers/plans/2026-10-17-edith-0.22-directory-refactor.md`。**本次只改路径/包名，不改行为。**

---

## 10. 0.23 官方助手适配（D128–D156，2026-10-17/18）

批准规格：`docs/superpowers/specs/2026-10-17-edith-0.23-official-assistant-adaptation-design.md`（业主 2026-10-17 批准）。需求与阶段划分：`docs/superpowers/specs/2026-10-17-edith-0.23-requirements-and-phase-division.md`。

| ID | 日期 | 决策 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D128 | 2026-10-17 | 0.23 手机助手页不自动跟随路由，保留手动切换 | 主动辅助到达不抢当前视图，避免打断阅读；两路由记录仍独立 | `PhoneScreen.kt` | Active |
| D129 | 2026-10-17 | 技能/记忆详情＝点击行 → 全屏详情页；删除保留在列表行 | 业主 2026-10-17 选择；不做长按；详情页可滚动/返回 | 手机技能/记忆页 | Active |
| D130 | 2026-10-17 | 眼镜缓存是窗口缓存：一次窗口内两条路由可共存但只显示当前；新会话 `HELLO`/Activity 销毁清空；手机 `ConversationLog` 不受影响；不新增会话结束命令 | 业主口径「只清眼镜端路由缓存；手机是会话完整内容；窗口过了就没了」 | `GlassesActivity`、`ConversationOrder` | Active |
| D131 | 2026-10-17 | 0.23 不改 `HintReadingBudget` TTL | 保持保守；避免同时改滚动与 TTL；后续再标定 | `HintReadingBudget` | Active |
| D132 | 2026-10-17 | 眼镜官方视觉：无路由标签/左右切路由；都左对齐；用户 14 px `#B340FF5E`、AI 16 px `#40FF5E`；1 px/帧 ticker 前 3 帧跳过 | U5/U9/U10；官方 B/J 级；取代 2026-10-15 的阅读节奏裁决（先停 1.5 s、再 1.4 s/行） | `GlassesActivity`、`ConversationView` | Active |
| D133 | 2026-10-17 | 眼镜 pin＝停止/恢复 ticker＋图钉（EDITH 无服务侧自动退出） | 官方 pin 是 `sendNotAutoExitToService`；本版映射为阅读控制，差异如实记录 | `ConversationOrder`、`GlassesActivity` | Superseded by D162/D164（2026-10-18） |
| D134 | 2026-10-17 | STOP 是 `wireVersion=1` 的加法式命令：`sessionId`+`generation`，无 sequence/ACK；手机映射 `UserAction.Stop`；插件 `stopAll()` 立即广播 busy=false；旧解码器 null 安全失败 | U11；两端同一次构建；回退成本低；P5 实现提交更新 `CONTRACTS.md` | 眼镜↔手机线缆、插件 | Active |
| D135 | 2026-10-17 | 删除 TTS「在的」；唤醒短提示音用平台 `ToneGenerator`，800 ms 去抖，唤醒时读「语音朗读」开关；不产生 Assistance/会话条目 | U12；厂商 FX 34 不可达，用平台等价替代；可听性需真人 | `HostCompositionRoot`、`WakeChime` | Active |
| D136 | 2026-10-17 | 手机 MIUI 首装优先用 a11yprobe/ADB 处理确认页；失败才 `NotMeasured` 并把手动确认推到 P6 末尾 | 业主授权设备操作；D95 纪律不变 | P1/P6 设备验收 | Active |
| D137 | 2026-10-17 | 主观项（侧键手感、听感、可读性、功耗）集中 P6 末尾；Agent 完成所有可自动化设备项 | 无人值守优先；未确认即 `NotMeasured` | P6 验收 | Active |
| D138 | 2026-10-17 | 0.23 不承接 D121 工具面重构；M2/AC-13/16 保持 0.21 判词，不重测、不改写 | 0.23 范围是 UI/交互；工具面另立版本 | `ACCEPTANCE.md`、`STATUS.md` | Active |
| D139 | 2026-10-17 | 0.23 验收行 U-1–U-11；JVM 只写 `Verified (JVM)`，设备未测 `NotMeasured` | 证据纪律；不以候选期读数替代 | `ACCEPTANCE.md` | Active |
| D140 | 2026-10-17 | P1 助手页交互细化：打开/切换路由停在最新；气泡不显示角色/时间；刷新只重读、不主动跳位（原本在底部且新条目到达时仍按跟随规则）；不做新消息提示；40 条封顶后以 last entryId 判断新条目 | 业主 2026-10-17 逐项确认；不改变 U-1/U-2 范围；`shouldAutoScroll` 保留，另加 `shouldFollowNewEntry` 覆盖封顶。**「气泡不显示角色」部分已被 D141 取代** | `PhoneScreen.kt`、`AssistantConversation.kt` | Active |
| D141 | 2026-10-17 | P1 气泡角色标识：AI 侧女性面孔头像（`Icons.Default.Face4`，盘发）、用户侧人像（`Icons.Default.Person`），32 dp 圆形；不显示时间；气泡行合并语义「助手说：…」/「你说：…」，头像对读屏器为装饰 | 业主 2026-10-17 追加：机器人图标太硬朗，换成偏女性的漂亮女性图标；可见头像让左侧=AI、右侧=用户一目了然；取代 D140 的「气泡不显示角色」部分 | `PhoneScreen.kt`、`AssistantConversation.kt` | Active |
| D142 | 2026-10-17 | P1 底部跟随判定改为 `wasAtBottom(previousLastId, visibleItems, viewportEndOffset, bottomPaddingPx)`：上一轮最后一条的**末尾**已进入视口才算读者在底部；`previousCount`/`previousLastId` 在启动滚动**之前**更新 | 40 条封顶后 count 不变且新条目可能尚未布局；长消息只露出下半截时「key 可见」不等于读完；动画被用户滚动取消时旧书签会造成误跟随。可见末尾 + 先更新书签同时覆盖这三种情况；`shouldAutoScroll`/`shouldFollowNewEntry` 仍保留为决策函数 | `PhoneScreen.kt`、`AssistantConversation.kt` | Active |
| D143 | 2026-10-17 | P2 技能/记忆交互细化：整行可点（删除按钮除外，不显示「查看」字样）；记忆列表单行 `时间 \| 类型 \| 缩略`；详情页全屏 Dialog、可选中复制；技能详情显示原始 UTF-8 全文（含 front matter） | 业主 2026-10-17 逐项选择（整行可点 / 单行缩略 / 支持选中复制）；在批准规格 §4.2 内细化，不改变存储、权限或范围 | `PhoneScreen.kt`、`MainActivity.kt`、`PhoneDetailPage.kt` | Active |
| D144 | 2026-10-17 | P2 设备证据方法：临时授予既有 `MANAGE_EXTERNAL_STORAGE`（保留 allow，记录回退命令）；合成技能文件 push 后删除；合成记忆用 debug-only `DebugMemoryLogSeed` 备份→替换→截图→逐字节校验还原；证据截图只含合成文本 | D95/D117；不碰真实记忆正文；业主的真实记忆文档执行前后均为 absent；还原 `verified=true` 才可声称恢复，失败即停记 `NotMeasured` | 手机技能/记忆证据、`src/debug` | Active |
| D145 | 2026-10-18 | P5 STOP 打断范围：除取消在途回合/主动请求并广播 `BUSY=false` 外，对**已提交（host accepted，可能已在屏）的主动提示**也调用 `cancelOutputTurn`（停播 + 撤屏） | 业主 2026-10-18 选择；对齐官方 `stopTalk`；wake 回合原本已由 `cancelOutputTurn` 覆盖，主动提示此前不会。设备忙碌回合是 WAKE，未单独构造「主动提示已在屏再 STOP」；该子路径由 JVM accepted 路径钉住 | `SmartAssistantPlugin.stopAll`、P5 证据 | Active |
| D146 | 2026-10-18 | P3/P4/P5 合并到一个会话执行；阶段验收与提交仍按 P3/P4/P5 分开 | 业主本轮明确「P3&P4&P5 Agent」且要求 plan 后无人值守；不改变 P0 的阶段划分与 P6 收口 | 本阶段计划/提交/STATUS | Active |
| D147 | 2026-10-18 | P5 唤醒设备证据用业主提供的 `D:\EDITH_File\Audio\唤醒词-英文.wav`（以及中文同名文件）经 `DebugAudioInjector` 注入实时 CXR ingress；本轮 KWS 命中英文 `EDITH` 关键字，中文录音未命中 | 只替换声学耦合；KWS/`wakeEmit` 是生产路径。中文命中率、真实声学耦合与可听性均 `NotMeasured`，不得外推 | P5 唤醒音证据 | Active |
| D148 | 2026-10-18 | 眼镜 BACK 在 `GlassesActivity.onKeyUp` 处理，而非 `installSideTouch` 的 unhandled-key listener | Activity 会先消费 BACK，unhandled listener 收不到；官方是在 `Dialog.onKeyUp` 处理。DPAD/ENTER 仍走 `installSideTouch` | `GlassesActivity`、P5 证据 | Active |
| D149 | 2026-10-18 | 眼镜忙碌状态按**当前显示路由**取 `BusyState.isBusy(route)`；左下提示与 BACK 用 `BusyState.any()` | U7/U8 路由隔离：观看 WAKE 时不应因 PROACTIVE 在跑而显示聆听/思考；打断语义则对任一路由生效 | `GlassesActivity.applyVisualState` | Active |
| D150 | 2026-10-18 | 用户行 4 行截断改为 **尾部 4 行**（`TailTextView`），不画前导「…」 | 官方 `StaticLayoutView.getLastLines` 正是取尾部子串；AOSP 多行 `TruncateAt.START` 只在单行生效，官方那句 `setEllipsize(START)` 实际不产生省略号。实现跟随官方可观察行为，而不是字面参数；设备测试断最后一个显式行仍在 | `TailTextView`、`GlassesPanelStateTest` | Active |
| D151 | 2026-10-18 | 忙碌+有身份但 STOP **发送失败**时，BACK 退出眼镜窗口并记 `reason=stop_unsent`；发送成功仍只打断不退出 | §5.3.1 只定义“发 STOP，不退出”；链路已死时该 STOP 不可能到达，继续不退出会把佩戴者困到本地 60 s busy 上限。成功路径不变，失败路径是安全兜底 | `GlassesActivity.onKeyUp`、P5 证据、`GlassesInteractionTest` | Active |
| D152 | 2026-10-18 | 组合根对 STOP 的二次身份校验只比 **sessionId**，不把 host generation 当门槛；generation 由链路在接收时校验 | `HostRuntime.pause()` 会递增 generation，而 `resume()` 不重发 HELLO，眼镜/链路仍持旧 generation；按 generation 二次比对会把 pause/resume 后的合法 STOP 误拒。新会话是新的 sessionId，跨会话 STOP 仍被拒 | `HostCompositionRoot.requestStop`、`CxrHintLinkTest` | Active |
| D153 | 2026-10-18 | 唤醒短提示音结果口径：`wakeEmit` 先确认 `host.currentIdentity() != null`；`P5_CHIME state=played` 只在 `ToneGenerator.startTone` 返回 true 时记，否则 `failed`；`disabled` / `debounced` 分开记 | 复核发现旧日志把“已请求播放”写成 `played`；设备可听性仍未测 | `WakeChime`、`HostCompositionRoot`、P5 证据 | Active |
| D154 | 2026-10-18 | U-11 设备证据＝渲染层双端（手机 `DebugConversationSeed` 两路由 + 眼镜 instrumentation 注入两路由 `SHOW`/`SAID`），不是真实模型主动回合；插件/日志路由隔离由 JVM 钉住 | 业主 2026-10-18 选择；不得把渲染层证据写成模型端到端 | P6 U-11、evidence | Active |
| D155 | 2026-10-18 | 0.23 版本级判词先出证据与提案，由业主逐字确认后才写入 `STATUS.md` / `ACCEPTANCE.md`；未确认前不写通过/不通过 | 业主 2026-10-18 选择；D137 的主观项集中到最后 | P6 文档收口 | Superseded by D165（业主已确认不通过） |
| D156 | 2026-10-18 | P6 全量 JVM 计数口径：计根项目中的产品/工具模块（`:tools:eval` 计入，`:tools:probes` 按 D103 排除），并排除 `third_party/`（2026-10-05 stale vendored sample，不在根 `settings.gradle.kts`）；XML 用显式 UTF-8 整文档解析（Windows PowerShell 5.1 的 `Get-Content` 会按 ANSI 解码，造成一份有效 XML 的假阳性） | 不把陈旧样本静默计入，也不因编码假阳性少算 `EngineBundleProvisioningTest` 的 7 个用例；P6 读数 301 suites / 2597 tests / 0 failures / 0 errors / 2 skipped | `tools/0.23-jvm-count.ps1`、P6 回归证据 | Active |

## 0.24 官方助手交互对齐（2026-10-18 业主批准）

批准规格：`docs/superpowers/specs/2026-10-18-edith-0.24-official-interaction-parity-design.md`（业主 2026-10-18 审阅通过）。实施计划待写；本表只登记已批准决策。

| ID | 日期 | 决策 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D157 | 2026-10-18 | 点一次「开始」= 常驻会话；关闭眼镜面板不结束会话；「停止」才结束 | 业主选 B：会话/采音/KWS 常驻，唤醒只开 UI；D53「开始=唯一同意」不变 | `SessionService`、`HostRuntime`、眼镜服务 | Active |
| D158 | 2026-10-18 | 眼镜端用 `SYSTEM_ALERT_WINDOW` + 前台服务承载悬浮面板，不再只用 Activity | 业主选 B：要官方式任意界面/息屏弹出；窗口类型/焦点与官方系统窗的差异如实记录 | 眼镜 manifest、服务、Activity | Active |
| D159 | 2026-10-18 | 语音球引入 `libpag` + 官方 9 个 `.pag` 资产，三段落照官方 | 业主选 A；0.23「不引入新依赖」作废；内存/启动/功耗需实测 | 眼镜依赖、assets、View | Active |
| D160 | 2026-10-18 | 会话内全量照搬：ASR 逐字、答案流式、每轮 item/状态、ASR“…”/思考 GIF、PAG、Markdown、工具状态行 | 业主选 A；相机/JSUI/云助手卡片不在 EDITH 能力范围 | 线缆、插件、眼镜 UI | Active |
| D161 | 2026-10-18 | 唤醒词保持 `伊迪丝`/`EDITH`，不改成官方「乐奇」 | 业主明确“乐奇是官方助手的唤醒词” | KWS 资产不变 | Active |
| D162 | 2026-10-18 | 触摸板语义完全照官方：上/下滚动、左/右无业务、单击单向钉住、双击三态；物理拍照/录像键与触摸面板亮屏属系统层，不动 | 业主选 A 并澄清；取代 D133 的 pin=ticker 映射 | 眼镜按键处理、`ConversationOrder` | Active |
| D163 | 2026-10-18 | 息屏唤醒必须实现；唤醒后面板出现并点亮屏幕 | 业主明确要求；依赖服务常驻 + 平台唤醒锁；S1 spike 验证 | 服务、唤醒锁、CXR | Active |
| D164 | 2026-10-18 | 10 s 无输入关闭；已钉住 → idle，120 s 后关闭；持续对话默认开 | 官方代码事实（`TIME_OUT=10000`、`TIME_WAITE_QUIT_TTS=120000`、`continue_dialogue` 默认 open）；120 s 服务侧执行未实测 | 状态机、插件 | Active |
| D165 | 2026-10-18 | 0.23 用户验收不通过；0.24 规格取代 0.23 的交互语义与 D133 | 业主 2026-10-18 验收；0.23 JVM/P6 证据只作历史读数 | `STATUS.md`、`ACCEPTANCE.md` | Active |
| D166 | 2026-10-18 | 允许新增 `libpag`、Markwon 依赖；0.23「不引入新依赖」非目标作废 | 为 D159/D160 服务；APK 体积/内存/启动需实测 | Gradle、APK | Active |
| D167 | 2026-10-18 | 保留 WAKE/PROACTIVE 两路由隔离；持续对话只属于 WAKE 路由；主动辅助仍是独立回合 | 0.23 U7/U11 不破坏；官方只有一套会话，这是 EDITH 内部边界 | 插件、缓存 | Active |
| D168 | 2026-10-18 | S1 设备 spike 是 0.24 第一道门：第三方悬浮窗收侧键、息屏 appStart/点亮、息屏 CXR 音频；任一失败停下交业主 | 业主选择“spike 失败回来决策”；不擅自换方案 | S1 门禁 | Active |

---

## 11. 0.24 S5 收口（D169–D173，2026-10-19）

这些是 S5 生产走查后由业主裁决或由实现/证据澄清的规则；0.24 S1–S4 的 D157–D168 不重写。

| ID | 日期 | 决策 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D169 | 2026-10-19 | **U-14 跟随官方**：唤醒段的空白 ASR 同样取消 10 s 计时器；**不加隐藏计时器**；U-14 记 `NotApplicable / 官方行为`，不记 `Failed` | 业主 2026-10-19 裁决。官方 `AsrMessageHandler` 在空文本判断之前就 `removeTimeout`；设备实测空白 final（10:05:19.158）早于 `PANEL_SHOWN`（10:05:19.231），面板 43 s 未自动关闭；真实回合后无 ASR 的 listening 窗口仍 10.001 s 关闭、pin 后仍 120.003 s 关闭，两者均实测 | 验收行 U-14；`ConversationCycle` 计时逻辑零改动 | Active |
| D170 | 2026-10-19 | **生产 CXR scene**＝透明的 RESUMED `GlassesActivity`（`Theme.Edith.CxrScene`，`windowIsTranslucent=true`）＋ `GlassesOverlayService` 画 480×380 overlay；Activity 只在 CXR scene/session 结束时 `finishSceneHost`；纯 overlay-only 不能维持 CUSTOM_APP 会话（实测 `GLASSES_APP_EXIT`） | 业主 2026-10-19 裁决 A。Activity 保持 RESUMED 是 CXR 会话要求；theme 透明是“面板隐藏时 launcher 可见”的要求（运行期 `Window.setFormat` 不够）；debug `GlassesPanelHostActivity` 只承接 instrumentation | 眼镜 manifest、`GlassesActivity`、`GlassesOverlayService`；D158 的 overlay 方案不变 | Active |
| D171 | 2026-10-19 | **Settings 下层不是 EDITH scene bug，而是平台保护**：`com.android.settings` 窗口带 `pfl=HIDE_NON_SYSTEM_OVERLAY_WINDOWS`，且 Settings 持有 `android.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS`（granted=true）；`dumpsys window` 将 Settings 列在 `Hiding System Alert Windows`。独立 `TYPE_APPLICATION_OVERLAY` 探针 `com.sheen.rokid.glasses.overlayspike` 在 Settings 上同样被强制隐藏（`isOnScreen=false`、Surface `shown=false`）。Task 21 的 ANR reason 为 `Application does not have a focused window`（无聚焦窗口），与平台隐藏 overlay + 注入 BACK 的链一致。**不外推为所有系统应用；业主 2026-10-19 接受该残留风险。** | 只读/控制证据（另一 agent；未改产品代码）；SoundRecorder/launcher 下层通过；只对 `com.android.settings` 的实测实例，其他系统应用未逐项测量 | `U-19` 的 Settings 分支记 Failed（验收行记 `Partial`）；D171 不再写“根因未查明” | Active |
| D172 | 2026-10-19 | 回答 final 的 per-emit 身份是 `Assistance.emitId`（每次 submit 一个），不是 `turnId`/`hintId`；`assistantFinal` 记 `say-${hintId}`，`assistantNotice` 记独立 `say-<uuid>` 且不得消费/最终化 pending answer；`OutputStateEvent`/`SpeechFinished` 携带同一 `emitId` | Task 14 C1 / Task 16 I1：同一 `hintId` 被替换时，旧 emit 的 `Rejected`/`SpeechFinished` 不得结算当前答案；手机内部，无 wire 改动；`null` 保留 legacy hintId 比较 | `ConversationDisplay`、`ConversationWireDisplay`、`OutputArbiter`/`HostRuntime`、`SmartAssistantPlugin` | Active |
| D173 | 2026-10-19 | **BACK 的 EXIT 实现口径**：未 pin / pin+非 ANSWERING → 眼镜隐藏面板并发 `UI_CLOSED`，手机 `onClose` 取消 turn/计时、清 pin；**不再额外发 `STOP`**。pin+ANSWERING → 只发 `STOP`，面板保留。规格 §5.3 的“STOP + UI_CLOSED”以实现为准：`UI_CLOSED` 已是 EXIT 的取消信号，双重发送会重复取消 | Task 13 实现 + Task 14 删除死掉的 `ConversationCycle.onBack`；本任务只记录，不改产品代码 | `OfficialConversation.backAction`、`GlassesPanelController`、`ConversationCycle.onClose/onStop`、`CONTRACTS.md` §4 | Active |
| D174 | 2026-10-19 | **0.24 版本级验收通过并闭环**：业主 2026-10-19 接受 S1–S5 已列实现 + 生产设备走查结果；U-14 按 D169 记官方行为，U-19 的 Settings 分支按 D171 记平台保护 `Partial`；其余未测项**不写成通过**，统一记入 `ACCEPTANCE.md` §2.7.2，下一版本统一测试验证（业主会提醒验收） | 设备/证据边界见 Task 21/22 记录；未测项包括真实 CXR 断连/重连、真实 wear、长 TTS vs 窗口、late ACK、权限页切换、Markdown/GIF/PAG 帧级、物理手感/功耗、libpag 内存/启动、其他系统下层 overlay、S1 a11y 写前证据缺口 | `ACCEPTANCE.md` §2.7/§2.7.2、`STATUS.md`；0.24 不 push/tag/merge | Active |

---

## 12. 0.3 Harness（D175–D184）

批准规格：[`2026-10-20-edith-0.3-harness-design.md`](../superpowers/specs/2026-10-20-edith-0.3-harness-design.md)（业主 2026-10-20 批准）。**D175–D183 是本 spec §2 的已批准决策，2026-10-20 定稿为 `Active`；它们不表示 Phase 3/4 已实现。** 实施进度与证据见 `STATUS.md`、`ACCEPTANCE.md` §2.9；D184 是 Phase 2 实施层裁决，不是新的产品批准。

| ID | 日期 | 决策 | 理由 / 边界 | 取代关系 | 状态 |
|---|---|---|---|---|---|
| D175 | 2026-10-20 | 0.3 采用 **A′：机制归基础层，内容/策略归插件层**；基础层不得出现 EDITH 业务字面量 | 统一 harness 但不模糊分层；用架构测试钉住 | 无 | Active |
| D176 | 2026-10-20 | 系统提示词资源化、版本化；静态段进 system，动态块进请求尾部；提示词版本按会话钉住 | DeepSeek 前缀缓存以 system 为前缀，system 变化会让历史全 miss；DSH 的 runtime-context 也是尾部独立快照 | 取代 `SmartPrompts` 硬编码、`ContextManager` 重复选择 | Active |
| D177 | 2026-10-20 | 预算完全由用户配置的 `ModelProfile` 派生；删除固定 `TokenBudget(4096)` 与 8192/1024 预填；统一 `TokenMeter` | 业主明确“不允许写死”；1M/256K 只是示例，不是常量 | 取代插件固定预算；D81 的统一窗口预算保留 | Active |
| D178 | 2026-10-20 | 缓存布局：system（静态）→ history（append-only）→ 动态尾部（时间/设备/感知/记忆/技能正文）→ 用户话语；压缩点之外不改前缀 | 记忆/感知/技能正文尾置是最大缓存收益；压缩是唯一允许重写前缀的时点 | **supersede D99 的“记忆/感知线上折进首条 user 消息”部分**；摘要头部落位保留；D72 的“不进 system/不写 store”保留 | Active |
| D179 | 2026-10-20 | 感知完整性优先：屏幕/通知/媒体/日历/剪贴板/声学/设备/眼镜内容进入模型上下文；**凭证也不做内容级例外**；日志/证据仍零正文；只发配置的模型服务 | 业主 2026-10-20 裁决：感知层核心目标是让模型感知到需要感知的一切，不因隐私/安全漏内容 | **supersede 通知 category-only 口径、任何“status 不返回用户正文”口径**；D53 的会话同意覆盖感知内容，D117 只约束日志/证据 | Active |
| D180 | 2026-10-20 | 视觉复用主模型多模态；profile 声明 `IMAGE` 且会话运行即默认可用，用户可关；不做独立 VLM 供应商 | 完整性优先；复用 D53 同意与现有 `InputPart.Image` 链路 | 取代“默认无截图/VLM”的 0.27 现状 | Active |
| D181 | 2026-10-20 | 主动辅助支持“提示后无需唤醒词续听 + 接话后执行”；两条路由仍独立；唤醒抢占 FOLLOWUP_TURN | 产品定义要求主动辅助具备持续对话与执行能力 | **supersede D167 的“持续对话只属于 WAKE”**；D167 的路由隔离保留 | Active |
| D182 | 2026-10-20 | 工具面**延迟优先**：0.3 **不退役**；旧 atomic 工具（a11y 5、Intent 5、Shizuku 5、launch 2、system_action 1）重新接线为默认 fast path；umbrella（`device`/`open`/`shell`/`dial`）保留；默认 24 个（+ 主动提示 `propose_hint`）；两档路由；工具集按 App 版本/会话固定，不按轮过滤；L3/L4 永不免确认 | 业主 2026-10-20 裁决“先不退役，保延迟”：atomic 工具可能更快、少一层 planner/settle/envelope；能力也更大 | **supersede 本 spec 早先的“只合并重复/退役”口径**；**supersede D83（0.3 重新启用 Intent 通道；后续版本再评估收敛）**；D121 工具面收敛延后，等延迟矩阵证明不回归再议 | Active |
| D183 | 2026-10-20 | 压缩采用四层：类型感知裁剪 → 工具结果清理 → 整回合淘汰+记忆写入 → 结构化异步摘要；soft/hard 阈值由 profile 派生 | 1M 窗口不等于不压缩；context rot/延迟/成本需要高信号上下文；压缩点之外保持 append-only | D97 的裁剪顺序保留；D100 的压缩门保留；插件固定预算被 D177 取代 | Active |
| D184 | 2026-10-21 | **0.3 Phase 2 实施裁决汇总（实现层，不是新的产品批准）**：(a) `PERCEPTION` 是请求作用域的尾部段：`AgentLoop` 在 `USER_INPUT` 之前折入一个不受信任段，与上一请求逐字节相同的块按 SHA-256 跳过；永不写入 history/`ConversationStore`/工具结果，永不进 system 槽。(b) `PerceptionPolicy` 默认 `elementLimit=60`、`notificationLimit=3`、`refTtlMillis=20_000`、`calendarTitleLimit=5`（`includeClipboard`/`includeVision` 默认开），是可配置 policy，不是模型常量；快照 `limit` 被政策上限夹住。(c) act 的 `applied` 只由动作后观察到的界面变化决定，`verified` 携带 `kind=screen_change` 与观察 revision；`open` 的 `verified` 要求前台包与 a11y 根包都等于请求包（URL/settings 目标则前台包非空且等于根包），`am start` 的 `Status: ok` 只是前置条件。(d) 视觉闸门优先于 coverage：闸门关时宿主 token 为 `disabled`；profile 无 `IMAGE` 时只把 `ok` 降级为 `unavailable(no_image_profile)`，`disabled` 不降级；`device` 的 `screenshot` 在闸门关时直接 `VISION_DISABLED`，不读通道。(e) `P3_CACHE` 由 `ModelClientGateway` 每个模型请求一行（仅当 provider 上报 usage；usage 为 null 时省略；`cached`/`miss`/`inputTokens`/`outputTokens` 四个 token 计数未上报为 `-`，`systemChars`/`dynamicChars` 恒为数值；尺寸取实际发送的裁剪后帧）；`P3_PROMPT` 每个**装配后**请求一行（`route` + `staticChars` + `dynamicChars`），在 loop 折叠 `RUNTIME_STATE`/`PERCEPTION` 之后、gateway 发送之前；`dynamicChars` 计 memory/skill/retry/perception，排除 loop 自有 `RUNTIME_STATE`；gateway 溢出重发只记 `P3_CACHE`。(f) HARD 恢复保留动态尾部为 `head + "\n...[truncated]...\n" + tail`（默认 400/100 字符 policy），仍超预算才按 `SKILL_BODY → MEMORY_EVIDENCE → PERCEPTION` 整段放弃并记录 `droppedOrigins`；`USER_INPUT`/`RUNTIME_STATE` 永不被清理；HARD 清理顺序永不选 `RETRY`，但 ① 整回合淘汰的 `isEvictable` 未排除它，仍可能随切片被淘汰。(g) 续听的 ambient 请求用 `full=false`，高容量源写 `not_requested`，设备/前台/媒体/coverage 仍渲染；`P3_PERCEPTION` 是每个渲染块的 count-only 行（snap 前缀、元素/通知/日历/图片计数、`screenChars`、`truncated`、coverage tokens），从不记内容。(h) **final-fix C1**：`PerceptionProvider.refresh()` 是显式真实重读（`observe(ALL)` → 新 snapshot id → `beginSnapshot` → `assign` → build/cache，不重置回合）；`snapshot()` 仍只读缓存。`device.observe` 与 `STALE_REF` 的新快照走 `refresh()`，`afterAction()` 仅用于 act 之后；默认 `refresh()` 退回 `snapshot(SCREEN, ALL, null)` 只保证旧实现/测试类编译，生产 `AndroidPerceptionProvider` 覆写。(i) **final-fix C2 + C2 re-review**：`ActionResult.nonPersistent`（默认 false）标记含感知内容的工具结果；`DeviceTool` 的每个内容型动作结果（`observe`/`act`/`wait`/`status`/`screenshot`，含 `STALE_REF` 答案与未挂 provider 的旧投影路径）置 true（C2 re-review）。`AgentLoop` 在运行内保留完整 detail（模型必须看到），但 `finish` 的 `AgentRun.transcript` 只把**本运行**（`runHistoryStart` 起）的对应 `ToolOutput` 正文换成 `(perception content not persisted)` 并清空 images；前缀旧输出即使 call id 被复用也原样保留，避免破坏 `recordStoreTurn` 的前缀检查而丢掉本回合工具轮。因此 `ConversationStore`/落盘 transcript 不含感知正文，该标记不改变其他工具。(j) **final-fix I3/I4/I7 与 deferral**：通知读取新增 `NotificationRead(items, available)`，producer 抛错 → coverage `unavailable(provider)`（未授权仍 `unavailable(no_access)`）；`ModelSummarizer` 的 gateway 传 `P3Log::line`，摘要请求有 `P3_CACHE`、无 `P3_PROMPT`（无装配帧）；`P3_PERCEPTION images=` 只计感知块携带的图片，Phase 2 恒 0，截图/工具图片由 `P3_METRIC images=` 计数。**§7.5 主动提示回合的完整感知快照未实现：`runProactive` 只调用 `actions().beginTurn()`，不打开 perception 边界、不发 `P3_PERCEPTION`；该项 `NotMeasured`/deferred to Phase 3，不读成已满足。** 已记录但不重开的 minor：`PerceptionSnapshotBuilder` 的 `enabled=false` 仍表现为 null（`DeviceNode.states` 只编码 true）；`MediaSessionSource` 首会话选择、`onNotificationRemoved` ring 清理、`DeviceProfile` clipboard capability、`OBSERVE_FIRST_NOTE` 散文、重复 `PerceptionPolicy`、`withPerception` 去重后首个请求之后的 ambient 淘汰（本条设计风险）；`snap` 前缀/rendered-not-sent/`screenChars`/`not_requested` 已在 (g) 记录。 | 这些是为避免 Phase 2 契约/行为漂移而定的实现口径；隐私/上传边界仍由 D53/D117/D179 约束，不新增豁免。 | `Perception.kt`、`PerceptionProvider`、`AgentLoop`/`RequestLayout`/`TurnTrimmer`、`ModelClientGateway`、`SmartPerceptionRenderer`、`AndroidPerceptionProvider`、`DeviceTool`/`OpenTool`、`CONTRACTS.md` §2.2；设备验证见 Task 14 | Active |

---

## 13. 0.3 Phase 2.1 自动视觉与截图压缩（D185，2026-10-21）

批准规格：[`2026-10-21-edith-0.3-auto-vision-design.md`](../superpowers/specs/2026-10-21-edith-0.3-auto-vision-design.md)（业主 2026-10-21 批准）。本条只登记 Phase 2.1 的范围与已产生的事实；D175–D184 不重写，Phase 3/4 不启动，0.21/0.25/0.26 判词不变。

| ID | 日期 | 决策 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D185 | 2026-10-21 | 0.3 Phase 2.1 只做“屏幕操作自动视觉 + 截图压缩”：**(a)** `device.observe` 在 `include` 含 `SCREEN` 时附一张当前屏；`device.act`（含 `scroll_to_text`、stale-ref/已派发拒绝）在动作后或刷新后附一张；`open` 仅在 `verified=true` 成功时附一张；`status`/`wait` 不自动附图，显式 `device.screenshot` 保留。**(b)** 自动图走既有 `ImageRef`/`ActionResult.images` 路径，结果 `data.vision=ok|disabled|unavailable(reason)`；截图失败只降级视觉，不改操作结果。**(c)** 门控＝用户 `visionEnabled` 且当前回合 profile 的 `inputs` 含 `IMAGE` 且该 profile **当前 revision 的最新 `IMAGE_REQUEST` 校验为 `Verified`**（选择规则与 `CapabilityGate` 一致：同 `profileRevision`、同 operation，按 `testedAtEpochMillis` 最大、同时间按列表后者；旧 revision、失败、未校验、缺失一律 false）；只声明 `IMAGE` 却未通过 image probe 的 profile 不附图，否则首个自动图会被 `CapabilityGate` 以 `CapabilityMissing` 终止整回合（final whole-branch fix I2）；协议支持维度（`protocolId`/`support`）仍由 `CapabilityGate` 拒绝，auto gate 不重复检查（residual）；profile 与回合配置同源（`AndroidProfileRepository.selections()` 优先，缺失/不可读回退 `smartWiring.selectedProfile()`），每次 capture 重新读取，null/throw fail closed；显式 `device.screenshot` 的 profile 门控仍是已知 Phase 2 残留，本次只做 auto gate。**(d)** 压缩按原生比例、宽≤720/长边≤1600、不放大/不裁切、JPEG q80，所有截图路径共用；用平台 `Bitmap` API，不新增 Gradle 依赖；`ImageBudget=1024`/`IMAGE_KEEP=3` 不变。**(e)** C2 不变：自动图只进当轮请求，`nonPersistent` 使 store/transcript 不含图片与感知正文。**(f)** 只重跑 T7/T6，不重跑 0.25 冻结 27 行；T6 的 cold-start 页面服务重启是 `.local` 测试工具修复，不是产品功能；T7/T6 判据不变，图片不是通过判据。 | 业主 2026-10-21 批准；目标是让模型在判断前/动作后看到真实当前屏，同时不新增图片通道、依赖和预算变更。**证据边界**：八模块 `--rerun-tasks` gate **314 suites / 3236 tests / 0 failures / 0 errors / 2 skipped**；设备 build `c5f55d82`，APK **102,886,626 B**，SHA-256 `00a28343a5ed4ac763f8789b66eeece9d95bca341db69afd84982d169c74b1d7`（host 已独立核验；设备 `base.apk` hash/install/`lastUpdateTime` 为**运行报告值，raw adb 输出未保留**，不当独立核验）。**T6 run 6 = Passed (device)**：cold-start page server 后 browser 前台、红/绿/蓝 + `EDITH_SEQUENCE_OK`、3 次有序 a11y 点击、images=7/steps=7；一次 pre-page MIUI overlay act 被拒且未推进页面；图片不是通过判据；**通过不能单独归因于自动视觉**——cold-start page-server 修复是必要前置，自动视觉贡献未隔离。**T7 run 7 = Partial（不是 pass）**：稻香/周杰伦 `playing=true`（视觉/媒体证据）、亮度 255/mode 0，但最终 top=player、home 未到达，模型却声称已回桌面；steps=18/images=15（含 1 次显式截图，auto-only 不可分）；`vision=ok`；`P3_CACHE cached=7808 miss=- systemChars=1645 dynamicChars=848`；**改善不能单独归因于自动视觉**。**P-08 本轮 = Partial**：post-T6-clear logcat + `docs/`/SDD/harness scan 0 命中；T7 probe-window full logcat 已被清、sentinel placement 不可观察；**不把本轮 P-08 记 Verified**，旧 Phase 2 P-08 行保持历史。`P3_PERCEPTION images=0` 是感知块硬编码字段，`P3_METRIC images=15/7` 才是运行工件。APK 从 90,557,722 B 增到 102,886,626 B，**原因未确定**（无 build-config/resource diff；代码/陈旧打包/ABI 均可能，不是“仅环境”）。**final whole-branch fix（2026-10-21，JVM-only；I2）**：auto gate 增补“当前 revision 最新 `IMAGE_REQUEST=Verified`”条件（见 (c)），provider 的 `profileSupportsImage` 默认改为 `false`（未接线即 fail closed），`DeviceTool.captureVision()` 的 provider 抛出降级为 `unavailable(SCREENSHOT_FAILED)`；八模块 `--rerun-tasks` gate 重跑 **314 suites / 3245 tests / 0 failures / 0 errors / 2 skipped**（app 117/1255/2 skipped）。设备 T6/T7 的 profile 已满足该 gate——T6 的 7 张图全为自动图（无显式截图）且两回合 `COMPLETED`，说明当轮 `CapabilityGate` 已接受该 profile 当前 revision 的 `IMAGE_REQUEST`——故本次 JVM-only 修复**不使设备读数失效**；**本轮未重跑设备、未重建/安装 APK**。deferred：`elidedImages` 遥测、declared-vs-verified helper dedup、设备重跑。**不写 Phase 2 通过；0.21/0.25/0.26 判词不变；不 push/tag/merge。** | `ScreenshotSizing.kt`、`EdithAccessibilityService.screenshot()`、`AndroidPerceptionProvider`、`AutoVisionProfile`、`PerceptionScreenshotMapping`、`DeviceTool`、`OpenTool`、`HostCompositionRoot`；`CONTRACTS.md` §2.3、`ACCEPTANCE.md` §2.10、`STATUS.md`、证据 [`2026-10-21-0.3-auto-vision.md`](evidence/2026-10-21-0.3-auto-vision.md) | Active |

---

## 14. 0.3 Phase 2.2 工具结果契约（D186，2026-10-21）

批准规格：[`2026-10-21-edith-0.3-tool-result-contract-design.md`](../superpowers/specs/2026-10-21-edith-0.3-tool-result-contract-design.md)（业主 2026-10-21 确认方向并批准；spec 的 `foreground`/内联 `vision(reason)` 示例只是示意，**以 committed 扁平编码为准**）。本条只登记 Phase 2.2 的已批准契约与已产生的事实；D175–D185 不重写，Phase 3/4 不启动，0.21/0.25/0.26 判词不变。

| ID | 日期 | 决策 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D186 | 2026-10-21 | **0.3 Phase 2.2：所有模型可见工具结果统一为 `{execution, observation|null, data}`，成功/拒绝/失败/超时/uncertain/`TOOL_THREW`/部分执行/取消同形状。****(a) 范围**：屏幕工具＝`device`/`open`/`shell`/`dial`（`ActionDescriptor.screenObservation=true`），每次结果尽量带调用后观察；纯工具＝`notify_user`/`load_skill` 只有执行事实、`observation=null`。Intent/Shizuku 后端动作不在当前主工具面；若未来广告按同一契约处理。**(b) 执行事实**：`ToolOutcome` 六态 `done|refused|failed|timeout|uncertain|cancelled`；`accepted/applied/verified/changed` 三态 `true|false|null`，`null` 只表示未知、禁止用 false 冒充未知；`uncertain=true` 时 `applied/changed=null` 并附 best-effort 观察；参数/策略拒绝 `accepted=false/applied=false`。**(c) 观察**：`ToolObservation` 为**扁平**十字段 `snapshotId/capturedAtEpochMillis/foregroundPackage/foregroundActivity/rootPackage/coverage/screen/truncated/vision/visionReason`；spec 的嵌套 `foreground` 与内联 `vision(reason)` 未实现。`status`/`wait` 成功只有文本观察 + `vision=not_requested`，失败尝试图；纯参数拒绝只给观察文本 + vision 原因；`open list`/未知动作无图。**(d) 只给事实，不给下一步建议**；没有 `next=retry/reobserve` 之类提示。**(e) 图片**：失败/超时/uncertain 的屏幕工具在 vision 可用时附图；图片仍走 `ToolResult.images`/`ImageRef`，`observation.vision` 只说明可用性；失败图计入 `ImageBudget`/`IMAGE_KEEP=3`，不落 store/transcript；loop 级 timeout/throw 的图片因 `ToolObservation` 无 `ImageRef` 通道，首轮 **NotMeasured**；Phase 3 已补 `observeWithImages`/失败图（JVM 证），设备 loop 图仍 **NotMeasured**（见 D188/§2.12），观察文本/vision 原因仍必需。**(f) 分层**：`ToolResult.payload` 就是唯一模型面 envelope（由 execution/observation/data 派生），OpenAI Chat Completions 与 DeepSeek Responses 适配器原样消费，不重新拼模板；公共 Kotlin API 加法式（`ToolResults.kt`、`ActionResult.execution/observation`、`ActionDescriptor.screenObservation`、`AgentRequest.observationSource`），无新依赖。**(g) C2**：observation 存在即 `nonPersistent`，完整内容只进当轮模型请求，`AgentRun.transcript`/`ConversationStore` 得到安全 marker 且无图片；loop 补观察的副本也撤持久化资格；不可依赖工具 flag 决定图片是否可持久。**(h) `P3_METRIC images=`** 只计 **distinct images produced by this run's tool calls, including images a terminal run never carried**，按 artifact id 去重，不从 history 携带计数；`P3_PERCEPTION images=` 仍是硬编码 0。**(i) data/trimmer**：生产路径 `data` 不再复述 observation/execution，`data.changed=false` 不再在 applied/changed 未知时出现；`TurnTrimmer` 从 `observation.screen` 取树，⓪ 只替换 `observation.screen`，保留 execution/data；legacy `perception==null`（仅测试/非生产）保留旧 `data.screen`，不伪造 observation。**(j) 残留**：250 ms 观察预算是建议值；生产 `PerceptionObservationSource` cache-only、deadline 仅 advisory 且不可抢占，本轮**未测得**真实耗时 ⇒ 250 ms 合规 **NotMeasured**，不是已满足；`open`/`shell` 观察刷新延迟无可用的逐调用计时 ⇒ **NotMeasured**。显式 `device.screenshot` 的 profile 门控仍是已知 Phase 2 残留（只受用户视觉开关约束，profile 无 `IMAGE` 时仍可能被 `CapabilityGate` 拒绝）。 | 业主 2026-10-21 批准“统一结果契约 + 屏幕工具观察/图 + 只给事实”。**证据边界**：八模块 `--rerun-tasks` gate **317 suites / 3366 tests / 0 failures / 0 errors / 2 skipped**；设备 build HEAD `e892ccb1`，APK **102,929,454 B**，SHA-256 `0f350286ff552856874d61a380eee681c31f316672b5860ddd23195d9df72300`，K40 设备 `base.apk` 同哈希（raw `sha256sum` 已保留），`lastUpdateTime=2026-10-21 15:11:48`。**T6 run 7 = Passed (device)**：cold-start page server 启动，browser 前台、`sequence=red,green,blue` + `EDITH_SEQUENCE_OK`、3 次有序 a11y 点击，steps=4/images=4；图片不是通过判据；T6 通过不能单独归因于本契约（页面服务修复是必要前置，贡献未隔离）。**T7 run 8 = Partial（不是 pass）**：亮度 settings 2047/mode 0（实际显示亮度与物理上限未测），但最终媒体 `playing=false`（当前曲目搁浅）、最终 top=player、home 未到达；steps=8/images=8；本轮**没有 failed `open`**，唯一失败是 `device act STALE_REF`，故“failed open 带观察+图+code 后模型纠正”的设备路径 **NotMeasured**；模型最终答复未声称已回桌面（如实报告稻香未播放并询问是否切回桌面），但有一次“前台其实是手机桌面”的错误陈述。**P-08 本轮 = Partial**：合成通知 sentinel 在 T7/T6 感知回合活跃（`notifications=3 truncated=true`），post-T6-clear logcat 14,323 行、repo `docs/` 223 文件、SDD 35 文件、harness 319 文件 **0 命中**；`2026-10-21T15:16:07.829+08:00` 的完整扫描之后，`2026-10-21T15:20:44.341+08:00` 在最终 docs HEAD `7dce01ff` 上只做 **repo-only** 复扫：`docs/` 226 文件 + harness 319 文件 **仍 0 命中**（该次未重读 logcat/SDD；不引用未产生的 24,884 行或 15:19:40 读数）；T7 probe-window full logcat 已被 T6 清掉、sentinel 在截断 cut 中的位置不可观察，**不记 Verified**。**NotMeasured**：设备 on-wire envelope/tri-state、failed-open 观察+图、loop timeout/throw 图、open/shell 刷新与 250 ms、设备 status/wait/dial 观察、物理最高亮度、逐调用图片归属、0.25 27 行旧语料。**不写 Phase 2 通过；0.21/0.25/0.26 判词不变；不 push/tag/merge。** | `ToolResults.kt`、`Actions.kt`、`ToolProtocol.kt`、`ToolResultEnvelope.kt`、`ToolRegistry.kt`、`AgentLoop.kt`、`ToolObservationSource.kt`、`DeviceTool`/`OpenTool`/`ShellTool`/`DialTool`/`ToolCatalog`、`SmartAssistantPlugin`/`PerceptionObservationSource`、两个模型适配器、`TurnTrimmer`、debug `ProbeResultProjection`；`CONTRACTS.md` §2.4、`ACCEPTANCE.md` §2.11、`STATUS.md`、证据 [`2026-10-21-0.3-tool-result-contract.md`](evidence/2026-10-21-0.3-tool-result-contract.md) | Active |
---

## 15. 0.3 Phase 2.2 → Phase 3 交接（D187，2026-10-21）

Phase 2.2 的最终 whole-branch review 判词为 **Ready with minor issues**（无 Critical/Important），但 `ACCEPTANCE` §2.11 的 R-01..R-07 全部为 Partial，**不是 Phase 2 通过**；D186 契约与实现不重开，旧 0.21/0.25/0.26 判词不变。

| ID | 日期 | 决策 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D187 | 2026-10-21 | **Phase 2.2 的未完成项转入 Phase 3 修复，不把 Phase 2.2 记通过。** Phase 3 范围＝0.3 design §8 主动辅助 + 关闭：R-07 failed-open 设备路径、P-08 完整窗口、设备 on-wire envelope/tri-state、设备 status/wait/dial 观察、loop timeout/throw 图、open/shell refresh 与 250 ms、设备 uncertain、物理最高亮度、逐调用图片归属、0.25 冻结 27 行，以及 final-review M1–M7。failed-open 语料用 K40 上不存在的应用 `打开一个叫 com.edith.nonexistent.app 的应用`，要求失败 `open` 返回 observation/image/code，模型纠正或如实失败。 | Phase 2.2 代码可 merge，但设备/验收条件未闭合；把它们留在 Phase 3 可避免把 Partial 读成通过。 | Phase 3 同时做主动辅助与 2.2 残留；不启动 Phase 4；不改旧判词；不 push/tag/merge。 | Active |

---

## 16. 0.3 Phase 3 主动辅助链路与工具结果残留（D188，2026-10-21）

批准规格：[`2026-10-21-edith-0.3-phase-3-proactive-and-result-residuals-design.md`](../superpowers/specs/2026-10-21-edith-0.3-phase-3-proactive-and-result-residuals-design.md)；计划：[`2026-10-21-edith-0.3-phase-3-proactive-and-result-residuals.md`](../superpowers/plans/2026-10-21-edith-0.3-phase-3-proactive-and-result-residuals.md)。D188 是实现层与验收层汇总，**不是新的产品批准**，不覆盖 D175–D187；Phase 4 未启动；旧 0.21/0.25/0.26 判词不变。

| ID | 日期 | 决策 | 理由 / 边界 | 状态 |
|---|---|---|---|---|
| D188 | 2026-10-21 | **0.3 Phase 3 只做主动辅助链路 + Phase 2.2 结果残留，并把“已关闭/明确接受”如实登记；Q1=A0、Q3=最简、Q4=A、Q5=B、Q6=A、Q7=C、Q8=A；P3-13 两条独立会话；主动答案后连续对话；不新增 debug fault seam；不启动 Phase 4。** | 实际结果与 `NotMeasured`/`Partial` 见下；`Partial`/`NotMeasured` 不是通过；设备只到 K40 `2696813b` + 眼镜 `1901092544027662`；旧判词不改；不 push/tag/merge。 | Active |

### P3-1..P3-13 实施结果

| 子项 | 决定 | 实际结果 | 状态 |
|---|---|---|---|
| **P3-1** HINT_TURN | 主动候选通过 gate 后，用 `AgentLoop` 跑**一个** `propose_hint` 终态工具（`tools=[propose_hint]`、`maxSteps=1`、`allowPlanNudge=false`、无设备动作）；`data` 即提示文本；run 结束由插件提交上屏 | JVM 实现并测试；r3 的 3/3 正例产生自然 hint（29/40/17 字），`propose_hint done`、0 设备 `P3_TOOLARG`；r4 gate 3/3 接受但 hint 0/3（`NO_CALL`），不 supersede r3。**Provenance：r3 = build `09ccc93d`/APK `583d2b7c…85d1`；r4 = `8d075f40`/APK `c3ff29bb…125e`；A-01b/A-02 是 r3 的显式 carry，不是 `8d075f40` 读数。** | A-01b/A-02 `Verified (Device; no comparable JVM criterion)`（r3 carry） |
| **P3-2** FOLLOWUP_TURN | 接话后跑完整 `AgentLoop`（正常工具面/确认/审计），route 仍 PROACTIVE；提示答案后按 `onAnswerDone`→`openContinuation` 支持**持续多轮** | JVM 实现并测试（`ProactiveFollowupTurnTest`、`ConversationCycleTest` 等）；设备 A-04 `NotMeasured`（r3 panel_not_shown 在答案前关闭，0 continuation；r4 hint-silent）；持续对话只有 JVM 证据 | 实现；设备 `NotMeasured` |
| **P3-3** FOLLOWUP_LISTENING | 提示 `Displayed` ACK 后开有界窗口（10 s/120 s 语义）；**空白/仅唤醒词 ASR 不取消窗口**（Q4=A）；可用 final 取消并启动 FOLLOWUP_TURN；wake 可抢占 | JVM 实现并测试；设备 A-03 `NotMeasured`（r3 窗口先被 panel_not_shown 关闭，10 s 监听从未 armed；r4 无窗口）；Q4=A 的行为只有 JVM 证 | 实现；设备 `NotMeasured` |
| **P3-4** 唤醒抢占 | `WakeDetected` 取消未完成的 FOLLOWUP_TURN（不取消已上屏提示），记 `WAKE_PREEMPTED`；WAKE 回合照常；两条路由独立 | JVM 实现并测试；设备 A-05 `NotMeasured`（无自然 hint/确认窗口，wake 未注入） | 实现；设备 `NotMeasured` |
| **P3-5** prompt 三档 | `PromptRoute` = WAKE / PROACTIVE_HINT / PROACTIVE_FOLLOWUP；`PromptCatalog` 三档一起钉住；`P3_PROMPT route=` 用三档名 | JVM `PromptCatalogTest`/route 测试通过；r3/r4 的 hint turn 记录 `route=PROACTIVE_HINT` 语义与 prompt hash；A-02/A-07 使用 | 实现；JVM Verified |
| **P3-6** §7.5 感知 | HINT_TURN 与 FOLLOWUP_TURN 打开 perception 边界并渲染完整快照；gate state 保持四键/空字段省略/尾部截断（Q1=A0），不修 D1/D2/D3 | JVM 实现并测试；r3 四 hint turn + r4 七 hint-model turn 均九 token/`screenChars>0`；D1（空字段省略 vs 四键训练）、D2（合成 ~436 vs ~219 token + 尾部截断）、D3（`contextWindow=512` vs 实际 256）记录为 known drift/`NotMeasured` | A-07 `Verified (Device; no comparable JVM criterion)`；gate drift `NotMeasured` |
| **P3-7** 2.2 残留 M1/M2/M3/M5 | shell/dial post-dispatch throw → Uncertain；DeviceTool provider_threw/blind/non-screen；`data.device` 去重；catalog device/open 拒绝附图 | JVM 实现并测试（`ShellToolTest`/`DialToolTest`/`DeviceToolTest`/`ToolCatalogTest`）；设备 M1/M2/M3/M5 `NotMeasured`（无自然 throw/provider 抛错/无 catalog 级拒绝/data 不落日志），M4 `Partial` | 实现；设备 `NotMeasured` + M4 `Partial` |
| **P3-8** loop 观察刷新 | `ToolObservationSource.observeWithImages(deadlineMillis)`；生产 `refresh()` + 截图、单线程、250 ms、timeout/provider_threw、最多一张失败图；loop 失败/uncertain 结果带图 | JVM 实现并测试（`AgentLoopGuardTest` 失败捕获/超时/图/250 ms、`PerceptionObservationSourceTest`）；**R-04 记 `Partial`**：设备样本是 R07/R05 的 **`refused`** 行（`APP_NOT_FOUND`、`VERIFY_TIMEOUT`），不是字面 `failed`/`uncertain`，只证明 non-success 结果的 observation+image 形状；loop throw/timeout 图与 250 ms 实际值 JVM-only，**device `NotMeasured`**（Q3=最简，shared WAKE path） | 实现；R-04 `Partial`；设备部分 `NotMeasured` |
| **P3-9** 结果遥测 | 新增 count-only `P3_TOOLRESULT`/`P3_OBSERVE`；`shape=ToolResultEnvelope.shapeDigest(payload)`；逐调用 `images=` 与 run 级 distinct 区分；不记正文 | JVM 实现并测试；设备 declared set 21/21 行 well-formed/reconstructed/matched，13 distinct digests；R-01/R-02 `Verified (JVM + Device)`；逐调用 21/17 相加一致 | 实现；Verified |
| **P3-10** Q3=最简 | 只用既有 `DebugUtterance route=transcript`/`wake`；不新增 `DEBUG_GATE`/`DEBUG_FAULT`、不做 forced gate/fault；共享 AgentLoop/context/display/confirmation 不重复设备测试；不可达路径记 `NotMeasured` 并明确接受 | 执行；无新 debug seam；loop throw/timeout 图、250 ms、A-03..A-06、panel proof 等按接受边界记 `NotMeasured`；无 forced gate/hint | 接受 |
| **P3-11** 设备验收 | R-07 不存在应用语料；P-08 先扫完整窗口再 clear；status/wait/dial 独立回合；uncertain `wait timeout_ms=1`；亮度 Q2=A0；0.25 Q7=C | 执行 7 个 Task 10 回合 + r3/r4 主动矩阵；另存在 **reference-only** `corpus-r03d-2`（shell，无 dial，不作 pass/acceptance）；R-07 `Partial`、P-08 `Verified`、R-03 `Partial`、R-05 `Partial`、brightness `Partial`、P-07 `NotMeasured`/deferred；R03D test-case-invalid（§16/§17） | 按实际读数 |
| **P3-12** 范围 | 不启动 Phase 4；不重做 Phase 2/2.1/2.2；不改旧判词；不 push/tag/merge | 遵守；最终代码/构建 HEAD `35d9378e`（`fix(proactive): close the wake-final and continuation-busy races`），docs `555f5760`（本对齐提交前，提交后以 git log 为准）；设备读数仍对应已安装 build `8d075f40` | 遵守 |
| **P3-13** 会话独立 | proactive 与 wake 两条独立会话：route/store/turn 槽/cycle/摘要/prompt plan/显示/生命周期；复用 AgentLoop/ConversationCycle/确认/显示**机制**，不复用会话；ASR final WAKE 优先；WAKE final/answer/continuation 不进 PROACTIVE，反之亦然 | JVM 路由/会话独立测试（§ACCEPTANCE A-09）；**最终复查修复 Important 1/2**：WAKE `Running` 时到达的 usable final 不再进 `acceptTranscript`/proactive admission，answer→continuation ACK 期间由已安装 FOLLOWUP_TURN 保持 busy、不启动 HINT_TURN；设备并发 WAKE+PROACTIVE 回合未构造（Q3=最简）⇒ device `NotMeasured`；主动答案后连续对话机制 JVM 证、设备 A-04 `NotMeasured` | JVM 实现；设备 `NotMeasured` |

### Q1–Q8 实际结果

| 问题 | 决定 | 实际结果 / 边界 |
|---|---|---|
| **Q1** | **A0（2026-10-21）**：gate 输入保持现状（四键、省略空字段、尾部截断）；完整感知只进 HINT/FOLLOWUP；**D1/D2/D3 只记录为 known drift/`NotMeasured`，不修 gate 代码、不重训、不扩 state** | D1 空字段省略 vs 四键训练契约；D2 合成最大中文四键 ~436 tokens vs ~219 预算 + 尾部截断；D3 `ModelInfo.contextWindow=512` vs 实际 256。三者均 `NotMeasured`；无 gate 代码/重训/state 变更。 |
| **Q2** | 不做亮度专用改动；只记 settings/mode/前台/亮屏；物理亮度 `NotMeasured` | 设备读回 `screen_brightness=1000`、mode 0、屏幕 Awake、前台 launcher；**不证明平台最高/物理亮度**；无新 probe。 |
| **Q3** | **最简**：只用既有 `DebugUtterance route=transcript`/`wake`；不新增 `DEBUG_GATE`/`DEBUG_FAULT`、不做 forced gate/fault；共享路径不重复设备测试；不可达路径 `NotMeasured` 并接受 | loop throw/timeout 图 JVM + WAKE-shared，设备 `NotMeasured`；250 ms 设备值 `NotMeasured`；panel fix JVM/compile-only；shared AgentLoop/context/display/confirmation 未为 proactive 重复设备测试；A-03..A-06 `NotMeasured`；无 forced gate/hint。 |
| **Q4** | **A**：空白/仅唤醒词 ASR 不取消 proactive follow-up 的 10 s 窗口；只有可用 final 才取消并进 FOLLOWUP_TURN | JVM 实现/测试；设备 A-03 `NotMeasured`（窗口未 armed），Q4=A 的行为没有设备证据。 |
| **Q5** | **B**：预注册 2–3 负例 + 2–3 正例，每例一次行为尝试；setup/注入失败最多 3 次；行为未命中不重试；下游只在自然 gate 接受时验证 | r3：2 负例（1 hit/1 miss）+ 3 正例（3 gate accepts/3 hints）+ 4 下游触发（gate 3 accepts/1 pre-gate VAGUE，hint 0/4 natural；D-A03 no hint）；G-P2 有 1 次 setup retry（1/3），不是行为重试。r4：2 负例（0 gate refusals）+ 3 正例（3 gate accepts/0 hints）+ 4 下游（3 accepts/0 hints + 1 pre-gate VAGUE）；总 7/7 hint-model attempts silent in r4，0 natural hints/9 candidates；未做行为重试。 |
| **Q6** | **A**：设备 on-wire 用 loop 边界 `P3_TOOLRESULT` shape/tri-state + JVM adapter forwarding 证明；不抓 HTTP 原文 | 设备 21/21 declared 行 well-formed/reconstructed/matched；JVM 4 个 pinned digests；R-01/R-02 `Verified (JVM + Device)`；无 raw HTTP 采集。 |
| **Q7** | **C**：Phase 3 不重跑 0.25 冻结 27 行；旧 3 PASS / 15 FAIL / 9 NOT_MEASURED 原样保留；Phase 3 记 `NotMeasured`/deferred | 未运行 27 行/`run-batch.ps1`；P-07 `NotMeasured`/deferred；旧证据不改；不把旧 FAIL 读成 `NotMeasured`，不把跳过读成通过。 |
| **Q8** | **A**：逐调用 `P3_TOOLRESULT images=<n>` + run 级 distinct 对照；不记 artifact id/hash | declared set 21 calls/17 images，每 run per-call sum = metric，0 mismatch；reference-inclusive 22/18（不计入 declared）；逐调用行只有 count。 |

### 实际状态汇总（不得读成版本通过）

- **主动链**：A-01 `Partial`；A-01b/A-02/A-07 `Verified (Device; no comparable JVM criterion)`（**A-01b/A-02 为 r3 `09ccc93d`/APK `583d2b7c…85d1` 的显式 carry；r4 `8d075f40` 0/3 自然 hint，未重新建立**；A-01 的 hit 半边同样来自 r3）；A-03..A-06 `NotMeasured`；A-08 `Partial (JVM Verified；device WAKE regression not re-run)`；A-09/P3-13 `Partial (JVM Verified；device concurrent NotMeasured)`；panel-fix device proof `NotMeasured`（JVM/compile-only）。
- **结果残留**：R-01/R-02 `Verified (JVM + Device)`；**R-04 `Partial`（JVM Verified；device 仅 R07/R05 的 `refused` 行，literal failed/uncertain 未产生）**；R-06/P-08 `Verified (JVM + Device)`；per-call images `Verified (JVM + Device)`（`MetricLogTest` + 设备逐调用和）；R-03/R-05/R-07/brightness `Partial`；M1/M2/M3/M5 `NotMeasured`；M4 `Partial`；P-07 `NotMeasured`/deferred；M6 docs-only resolved；M7 JVM Verified（+1 test-only M7 用例）。
- **R03D（2026-10-21 owner ruling）**：test-case/instrument defect，不是 EDITH defect；**no rerun**；R03D sub-case `NotMeasured`/test-case-invalid；reference-only `corpus-r03d-2`（shell，无 dial）不作为 pass/owner acceptance；owner dialing acceptance 撤回、不引用。R-03 保持 `Partial`。
- **Gate**：冻结生产代码（M7 新增前）八模块 `--rerun-tasks` **319 suites / 3471 tests / 0 failures / 0 errors / 2 skipped**（`BUILD SUCCESSFUL in 1m 9s`，100 tasks）；新增 1 个 test-only 用例后最终树再跑 **BUILD SUCCESSFUL in 1m 1s**，100 tasks，**319/3472/0/0/2**（agent-runtime 39/511；app 118/1334/2 skipped；其余不变）。计数只从 JUnit XML。**最终复查修复后（Important 1/2 两个回归测试）同一八模块 `--rerun-tasks` 重跑**：`BUILD SUCCESSFUL in 1m`，100 tasks，**319 suites / 3474 tests / 0 failures / 0 errors / 2 skipped**（`plugin-smart-helper` 88/921；其余不变）。
- **设备/APK**：最终代码/构建 HEAD `35d9378e`（`fix(proactive): close the wake-final and continuation-busy races`），docs `555f5760`（本对齐提交前，提交后以 git log 为准）；installed APK（build `8d075f40`）**102,985,202 B**，SHA-256 `c3ff29bb074839968583f3730a833f5af5b6c3be0b30783e9b866d7cab16925e`；设备 `base.apk` 同哈希；`lastUpdateTime=2026-10-21 21:56:29`；Phase 3 收口提交未重建/未重装，含 `Actions.kt` comment-only KDoc 与 1 个 test-only M7 用例，无生产行为变更，APK 仍对应 `8d075f40` 构建。**最终复查修复后重建（仅构建，未安装）**：新 APK **102,985,418 B**，SHA-256 `2c0ba0e4e57b330534e133f3db47d97579f85e27be24c063ca1cddd581cf3f9d`；代码/build HEAD = `35d9378eef8bad44e564fb30aee5a7876686616f`（`fix(proactive): close the wake-final and continuation-busy races`）。**其余设备读数仍对应旧 build `8d075f40`/APK `c3ff29bb…125e`；A-01b/A-02（及 A-01 hit 半边）来自更早 r3 `09ccc93d`/APK `583d2b7c…85d1`；无设备重跑、无安装。**
- **最终复查修复（2026-10-21；JVM-only）**：Important 1 — WAKE turn `Running` 时到达的 usable final 属于 WAKE，不再经过 `acceptTranscript`（避免记成 Help 的下一条 wake utterance）或 proactive trigger（避免 park 后被 `releaseParkedProactive` 当成 HINT_TURN）；回归 `ProactiveFollowupWindowTest > a usable final while the wake turn is running never enters the proactive session`（在 WAKE 模型请求内重入注入 final，确保是 `Running` 而非 `AwaitingTranscript`）。Important 2 — follow-up 答案 emit 会清 `followupBusy`，但 continuation 要等该答案的 `Displayed` ACK；admission 现在把已安装的 `followupTurn` 也算 busy，因此该 gap 内 `ContextChanged` 不会启动 HINT_TURN；回归 `ProactiveFollowupTurnTest > a context change before the continuation ACK starts no hint`，并保留 continuation 仍打开。原以"Running 时第二个 transcript 触发 proactive"为前提的 `RouteConcurrencyTest`/`SmartProactiveTurnTest` 用例改为用 `ContextChanged` 触发（final 在该状态已归 WAKE，旧前提正是被修复的 leak）。Task 7 report 的 “cannot start a second HINT_TURN” 表述过宽：它只覆盖 parked-release 路径，未覆盖 answer→continuation ACK 窗口。
- **R6/R7 + 已知残留（docs-only；无设备重跑）**：**R6** 一次被拒的 `propose_hint` 允许一次反馈请求 ⇒ 该 HINT_TURN 最多 2 个模型请求（`maxSteps=1` 限执行步数，不限请求数；JVM 测试 `SmartProactiveTurnTest`，device `NotMeasured`）。**R7** `proactiveMessage` 保留 foreground/context 文案，与 request 尾部 PERCEPTION 重复（未去重，`NotMeasured`）。其他残留：`P3_PERCEPTION` 无 `route=`（spec §4.5 的 telemetry gap，不改代码/不重跑）；9d `UI_CLOSED` 在 acquire→submit 之间未重新检查（面板路径不改）；P-08 `leftActive` 是 posting-time read；`frameBuilderFor` 的 route 参数未使用；`handleHelp` 重置 cycle 但不 detach `followupTurn`，新增的 `followupTurn != null` busy 读可能在该 Help 之后（仍在答案-ACK gap 内）继续让 PROACTIVE admission 判 BUSY（既有实现、窗口窄，不是本轮复查修复的缺陷，无测试）。以上均 docs-only，不读成通过。
- **明确 `NotMeasured`/`Partial`（不是通过）**：A-03..A-06、A-08/A-09 device half、panel proof、R-03 status/dial、R-05 uncertain、R-07 literal failed、brightness physical、M1/M2/M3/M5、M4 loop 250 ms/图、Q1 D1/D2/D3、P-07、R6 device half、R7、P3_PERCEPTION route、9d UI_CLOSED acquire→submit、P-08 leftActive、`frameBuilderFor`；以及 `Partial` 的 A-01/R-03/**R-04**/R-05/R-07/brightness/M4。**Phase 4 未启动；无 push/tag/merge。**

---

## 17. 0.3 Phase 4 工具面与权限确认（D189–D192，2026-10-22 批准）

批准规格：[`2026-10-22-edith-0.3-phase-4-tool-face-and-permission-design.md`](../superpowers/specs/2026-10-22-edith-0.3-phase-4-tool-face-and-permission-design.md)（业主 2026-10-22 批准）。本条登记设计批准与范围；**D189–D192 的实现与 JVM 收口已完成（2026-10-22），设备矩阵 P4-A Partial、P4-B/C/D Verified、P4-E NotMeasured**；旧 0.21/0.25/0.26/Phase 3 判词不改；无 push/tag/merge。范围 = 24 工具面/规范/延迟/旧语料 + 权限上限/确认交互/“不拦截”/shell 分类器。

| ID | 日期 | 决策 | 理由 / 边界 | 状态 |
|---|---|---|---|---|
| D189 | 2026-10-22 | **“不拦截”模式为全局、持久、默认关**：开启后 L0–L4 全部不弹确认，并绕过 `maxLevel`；对两条路由、所有会话生效；App 关闭/被杀/设备重启都不改配置、不自动回退，只有用户显式关闭才退出；保留审计（动作/风险/结果/计数，不记正文）、STOP 与持续状态提示；开启需二次确认 + 风险说明。 | 业主明确选择 UX 优先；仅在模式开启时 supersede D182 的“L3/L4 永不免确认”与 `WhitelistActionPolicy` 的硬上限；D47/D48 只在确实需要确认时适用；模式关闭时普通模式语义完全不变。**安全风险已记录并接受**；旧判词不改。 | Active（JVM 完成；设备 Verified：zero-confirm/persist/restore/UI 二次确认/审计 readback/真实 急停均有读数；P4-C 保持仅在该模式范围内 Verified） |
| D190 | 2026-10-22 | **普通模式 `maxLevel` 改为“自动执行上限”**：≤上限自动执行；>上限走确认（不再直接 Refuse）；L3/L4 无论上限如何都必确认；无可用确认通道时拒绝；默认 `maxLevel=L1`；L2 精确白名单保留。**统一 atomic/umbrella 风险级**：`tap`/`long_press`/`input_text` 由 L2 统一为 L1（与 `device` 对齐），`set_brightness` L2，`clear_data`/`uninstall` L3，`disable_app`/`set_permission` L4。 | 修“同一动作 atomic L2、umbrella L1”的不一致；让默认 L1 下核心 UI 动作可用，同时保留 L3/L4 必确认。UI 层不可分类的不可逆操作仍是残余风险，不宣称覆盖。 | Active（JVM 完成；设备 L4/L2 有 allow/deny 读数；无通道/后台锁定未测） |
| D191 | 2026-10-22 | **确认交互不要求进入 Edith**：非 Edith 通道优先级 = 眼镜自动开面板 + ENTER/BACK > 通知 action > 语音精确词 > Edith 内模态框兜底；通道不可用必须记录降级原因；120 s 窗口（D47）与可感知弹主屏（D48）只在确实需要确认时适用。 | “必须进 Edith”是真实降级路径，不是单点 bug；设备验收覆盖后台/锁屏/面板隐藏/通知关闭/Settings 上层。 | Active（JVM 完成；设备眼镜 ENTER/BACK、后台、锁屏 Verified；语音 synthetic 辅助；通知 action/no-visible/notifications-off/真实声学语音 NotMeasured） |
| D192 | 2026-10-22 | **shell 分类器与确认修复**：放行 `cmd package list packages` 等良性查询；对 `pm clear/uninstall`、`content insert/update/delete`、`rm/mv/dd`、`reboot`、`input`、`am force-stop`、`pm disable`、`settings put/delete`、`su`、`kill` 等确认；未知/复合/变量/重定向 fail-closed。不拦截模式跳过 shell precheck；用户白名单仍只用于 L2 精确 `(action, scope)`。 | 修误报与“高风险命令静默放行”的不一致；分类器白名单仍硬编码、不可由用户任意扩写。 | Active（JVM 完成；设备危险命令 confirm/deny 有读数；良性完整命令设备 NotMeasured） |

### 17.1 Phase 4B 实施裁决（2026-10-22；非新业主决策）

- **B1 摘要**：>上限确认的 summary 使用 A5 的用户 `title`，不使用模型 `description`；plan Task B1 的旧措辞在收口中修正。
- **B2 gate 位置/时序**：`NoInterceptionGate` 留在 phone-app host（B2 计划优先；spec §6.2 的 agent-runtime 位置作为文档漂移）；“关闭立即生效”按**设置保存后 live gate 生效、无需重启**解释，不改成开关自动保存；设置页二次确认/持久 banner 只有 JVM/编审证据。
- **B5 voice**：语音只结算**已呈现**的精确 allow/deny，不是独立展示 rung；语音“急停”仍未实现，记录为 deviation，不读成 §5.4 全部完成。
- **B6 compound**：复合命令沿用 0.25 的**逐段判定**（所有段 Allow 才 Allow；任一段危险/不可读即拒绝/确认），不实现“任何多段都确认”。
- **B7 P4_CONFIRM**：成功 raised 不单独发一行（closed decision 无 presented token；保持 one-per-question）；present-time 只为 unavailable/none 发；B9/设备解析必须同时读 `EdithP3` 与 `EdithSmart`。
- **B8 debug harness**：`DebugNoInterceptionControl` 与 shell command extra 只在 debug source，release 不含；设备不拦截读数走生产 store/gate，不是设置页点击；证据必须写清这一边界。
- **§5.3 指示 deviation**：通知/眼镜的“不拦截已开启”显示未实现，只有 App 内持久 banner；记录为未完成项（与 voice-急停同级），不读成 §5.3 全通过。
- **P4_CONFIRM shape drift**：实际 line 为 `tool=... decision=... channel=glasses|notification|voice|dialog|none`，无 `risk=`；spec §6.3 的 `risk=`/`channel=phone` 与 §5.4 的 `available=/reason=` 作为文档漂移记录，实际降级原因在 `P3_CONFIRM_NOTIFY`。
- **ASR-fixed closure run（2026-10-22）**：P4-A 为 `Partial`、P4-B/C/D 为 `Verified`、P4-E 为 `NotMeasured`；不得把 24/0 广告、L0–L4 zero-confirm、眼镜 ENTER/BACK 等局部读数升级为整行通过。closure 用的 `argsB64`/`sequenceB64`/forced-surface/settings harness 是 debug-only acceptance 工具，release 不含，并已在收口后删除；原 APK `C3FF…` 已装回。修正语料经独立 review（Approved with required corrections）；旧 active-24/旧 27 未重跑，旧 27 `3/15/9` 不变；P4-E 不因新 24-tool 语料而通过。

## 18. 0.3 ASR 复读与持续对话修复（D193–D194，2026-10-22）

本轮由业主直接指派修复 0.3 的两个现场问题；不改变官方 10 s 监听窗（D164/D169），不新增供应商、不上传新数据。

| ID | 日期 | 决策 | 理由 / 边界 | 状态 |
|---|---|---|---|---|
| D193 | 2026-10-22 | **ASR decoder 采用 int8 encoder/joiner + fp32 decoder 的混合组合**：`engine-manifest.json` 的 decoder 换为同仓库同 revision 的 fp32 `decoder-epoch-99-avg-1.onnx`（13,876,452 B / `2E3B…D7B7D`）；解码仍 `greedy_search`；encoder/joiner/VAD/speaker 不变。 | 独立设备实验与 EDITH 内 A/B 均表明 int8 decoder 是复读直接原因；只换 joiner/beam/blank penalty 无效。代价 bundle +785,412 B、独立实验 RTF 中位 +5.7%；EDITH 侧 RTF P95 `NotMeasured`。同 revision/同 Apache-2.0 声明，license/declaration 不变。 | Active（K40 设备 A/B：int8 复读、fp32 干净；真实 CXR 人声 NotMeasured） |
| D194 | 2026-10-22 | **持续对话的下一句改为按路由的单槽、latest-wins 队列**：可用 final 在 WAKE/PROACTIVE 回合 `Running` 期间或答案已发出、continuation 未打开的 Idle 间隙入队；`openContinuationTurn` 消费并以其 ASR turn id 启动下一回合；窗口关闭/STOP/UI_CLOSED/会话结束/新 wake 清空；单句等待上界 120 s（会话关闭地平线，不是新监听窗口）；助手自身 TTS 播放期间的 final 不入队。 | 修「响应中/响应结束瞬间说话被静默丢弃」；`wakeOwnsFinal` 的原有目的（Help 不被复读、Running final 不被 park 成 HINT_TURN）保持不变。**不延长官方 10 s 窗口**；窗口外仍需再次唤醒。设备只验证唤醒路线（合成 final）；主动路线与真实声学为 `NotMeasured`。 | Active（JVM + WAKE 设备路径；PROACTIVE 设备 NotMeasured） |

## 19. 0.3 Phase 4 验收裁决（D195，2026-10-22）

| ID | 日期 | 决策 | 理由 / 边界 | 状态 |
|---|---|---|---|---|
| D195 | 2026-10-22 | **业主接受 Phase 4 设备验收通过**：P4-B/C/D `Verified`；P4-A `Partial`、P4-E `NotMeasured` 作为验证材料/方式边界被明确接受，不在后续统计中改写成全绿。真实 CXR 移除、真实声学人声、完整 24 工具选择、L-02/延迟分布仍不声称通过。 | 用户明确表示阻塞项属于验证材料与验证方式，不是产品功能失败；P4-A/U 的剩余项无法用当前已删除的临时 harness 复跑。保留历史证据与边界；不把 owner acceptance 当成“全部指标全绿”。Phase 4 通过不等于版本级发布通过。 | Accepted（owner decision；不是版本级全部指标通过） |





| D196 | 2026-10-22 | **退役 `launch_app`（工具面 24→23）；保留 `open_app` 并修其歧义语义；应用打开统一 `open` 优先、`open_app` 仅在 Shizuku 不可用时兜底** | 2026-10-22 K70 验收同一回合 4 步只有第 4 次成功：`open_app`→`APP_NOT_VISIBLE`（NotFound/Ambiguous 被吞且 `recoverable=false`）、`launch_app` 两次失败、`open` 成功。`launch_app` 历史 0/10，与 `open` 共用 resolver/通道但无候选、无包名兜底、无启动后校验，是唯一真正冗余工具；`open_app` 是唯一"已解锁且 Shizuku 掉线"的启动路径（删它会收窄通道能力），且是唯一 L0，故保留。`open` 描述里"精确包名可用 launch_app"与实际能力不符，一并改为 open 优先。 | 用户 2026-10-22 批准（1 保留 `open_app`、2 删除 `launch_app`、3/4/5 修路由与语义）；24→23 属已验收口径变更，P4-A 历史判词不改，由本行登记变更；K70 非验收设备（D46/D65），K40 未复跑 | Active |

| D197 | 2026-10-22 | **移除 agent 回合的 180 s 墙钟上限；保留单请求超时、24 步/无进展/重复错误护栏；主动提示生成仍 15 s。同时移除「不拦截」开启时 App 顶部的常驻横幅。** | 2026-10-22 K70 验收：主动 follow-up 用 22 步有效操作（`modelMs=120997` + `toolMs=56056`）跑到 180 s 被 `DEADLINE` 终止并显示「回答超时了，稍后再试」。主流做法（Claude Agent SDK 的 maxTurns/maxBudgetUsd 默认关闭、OpenAI Agents SDK 10 轮、LangGraph recursion_limit）用步数/轮次与单请求超时，不用总时长；工作型长任务应允许完成，由用户 STOP 或步数护栏停止。24 h 仅作算术/调度的 sanity ceiling，不是产品上限；`SmartTurn.deadlineNanos` 不再兼职 hint 窗口的硬边界（`emit` 仍按每条 hint 的阅读预算设定 `hintExpiry`）。 | 业主 2026-10-22 确认；K70 仅行为观察（D46/D65），K40 未复跑；插件模块 JVM 9 个失败可归因于工作树未提交的 `00-identity.md`/`30-safety.md` 提示词改动（用 HEAD 版本时对应测试通过），不是本次代码引入 | Active |

---

## 20. 0.4 脱离眼镜唤醒、上下文输入与模型内置（D198–D207，2026-10-26 批准；2026-10-27 文档收口，未发布）

批准规格：[`2026-10-26-edith-0.4-detached-wake-and-embedded-models-design.md`](../superpowers/specs/2026-10-26-edith-0.4-detached-wake-and-embedded-models-design.md)；计划：[`2026-10-26-edith-0.4-detached-wake-and-embedded-models.md`](../superpowers/plans/2026-10-26-edith-0.4-detached-wake-and-embedded-models.md)（业主 2026-10-26 批准，含当日追加的“锁屏下要能唤醒”）。本节登记 0.4 的产品决策与实施裁决；**0.4 已完成实现/JVM/K40 矩阵收口（不是版本发布）**，未完成项见 §20.2。旧 0.21/0.23/0.24/0.25/0.3 判词不改；不 push/tag/merge。

| ID | 日期 | 决策 | 理由 / 边界 | 状态 |
|---|---|---|---|---|
| D198 | 2026-10-26 | **0.4 范围＝脱离眼镜唤醒（含锁屏）+「对话上下文记录」输入入口（文字/听写）+ 四组模型内置 APK + 0.4 版本收口**；不含眼镜端 App、CXR 协议/`Caps` 形状、主动辅助判据、唤醒词、10 s/120 s、工具面/提示词体系改动。 | 业主 2026-10-26 需求清单；保持“唤醒后 ASR 直接给 LLM”与原会话下游处理不变；不为新入口另造第二套模式。 | Active（范围实现完成；Task 13e K40 最终矩阵已完成；见 §20.1/§20.2） |
| D199 | 2026-10-26 | **「脱离眼镜」＝严格本地**：不连接 CXR、不向眼镜发送任何内容；WAKE 路线从手机麦克风采音（KWS/VAD/ASR/声纹复用既有实现，16 kHz/mono/16-bit 几何一致）；**锁屏/息屏仍可唤醒**（前台服务 `microphone` + 必要 `PARTIAL_WAKE_LOCK`；不承诺自动亮屏/锁屏 UI）；答案显示在手机「对话上下文记录」页，TTS 只受既有「语音朗读」开关控制；默认**关**；保存后**下个会话**生效；运行中保存**先停止会话并提示**（不热切换音频源/会话）。 | 业主选择严格本地/默认关/下个会话生效/运行中先停会话，并追加锁屏唤醒；厂商无主动亮屏 API，只保证唤醒+回合执行。 | Active（实现；K40 D3/D4/D5/D5b/D6/D11 有读数；D4 严格本地最终 outbound 计数已由 Task 13e 验证；keyguard、mic busy/初始化失败 `NotMeasured`；见 §20.1） |
| D200 | 2026-10-26 | **手机麦克风唤醒只替换采音源**：复用 `PcmFramer`→`AudioIngressLoop`→KWS/`SpeechPipeline`→WAKE；不新增第二条唤醒路线，不改唤醒词与 10 s/120 s；格式不匹配报 `MIC_FORMAT_UNSUPPORTED`，起始失败/权限缺失/设备占用分别报 `MIC_START_FAILED`/`MIC_PERMISSION_MISSING`/`MIC_UNAVAILABLE`，不静默停在“已开始”。 | 需求“路线/代码复用”；§1.4；回声回路（手机扬声器+手机麦）只记录实际干扰，不声称已解决。 | Active（JVM + K40 D4/D5 合成声学；真实人声/召回率 `NotMeasured`；Task 13e 最终矩阵已完成） |
| D201 | 2026-10-26 | **脱离模式不依赖 CXR 时钟映射激活插件**：host 的 `liveOutput` 换成手机显示设备（`PhoneHintDevice`）。它 **ack-only**、不写 `ConversationLog`（插件 `publishAssistantFinal` 已写最终答案；再写会覆盖同一 `say-<hintId>` 的 memory-ack 行），`Displayed` 后插件才结算答案/续听；`isPanelVisible` 在脱离模式为 true；不伪造 `PANEL_SHOWN`；眼镜模式路径不变。 | 无眼镜时激活/上屏的最小诚实实现；`AnswerEmitIdentityIntegrationTest` 钉住答案+memory ack 不互相覆盖。**实现裁决**：生产顺序是 `sink.submit` → `PhoneHintDevice.show` → `publishAssistantFinal`，故 ack **无条件、同步、恰好一次**；答案行存在是设计不变量，不是运行时检查。spec §4.3 的“行不存在按失败处理”措辞已在 `96e66a3d` 修正，见 §20.1。 | Active（JVM + K40 D4/D5 有脱离会话/答案读数；D201 ack 措辞已在 spec 修正；设备最终矩阵 Task 13e 已完成） |
| D202 | 2026-10-26 | **脱离模式有效主动辅助 = 存储的 `proactiveEnabled && !glassesDetached`**；host 感知事件闸门、`proactive.armed` 上下文键、插件 `armedNow`/`SmartProactive.admit` 都读有效值；**不写回**存储值，关闭脱离并重启后恢复用户原选择。 | 需求“开关打开时主动辅助禁用”；D53②；会话运行中切换会先停会话，不存在热切换打断正在显示的主动提示。 | Active（JVM + K40 D6 Verified；存储值读回不变；最终二进制未重跑） |
| D203 | 2026-10-26 | **输入框文字进入 WAKE 路线**，与一条已接受的唤醒 final 走同一 `runTurn`/`runTurnBody` 之后的下游处理；**不得触发 PROACTIVE**。协议采用**加法式** `UserAction.Action.Typed` + `UserAction.text: String? = null`（不序列化、不上线缆；唯一穷尽 `when` 在插件，编译器强制处理）。组合根提供 `requestTypedMessage(text): TypedSendResult`；只共享 `handleHelp` 的“开 WAKE 回合”尾部；回合 id `typed-<uuid>`，WAKE-only cycle 释放，不启动 3 s wake cooldown，**不写** `lastFinalTranscript`，不复制 Help 的双路由 reset/pending-hint 取消；空白、无身份、WAKE 回合 Awaiting/Running/Idle-未结算/已有排队句时拒绝（回调枚举，不写 sticky `SessionUiState.errorCode`）；文字**不经** `isUsableUtterance`；不打开 10 s 持续对话窗口。 | 需求“信息处理与原逻辑不变”；避免 debug `TranscriptEvent+Help` 造成双回合；忙碌/契约/cooldown 结论来自 typed-message recon。 | Active（JVM + K40 D7 有脚本模型读数；Task 13e 最终矩阵已完成） |
| D204 | 2026-10-26 | **输入框语音＝手机麦克风一次性听写**：识别完成后**填入输入框**、可编辑、**不自动发送**；听写与唤醒共用同一 ASR 引擎/`SpeechPipeline`，但听写文本**只进 UI、不进插件/`HostRuntime`**；唤醒后语音 ASR 直接给 LLM 的既有行为不变；15 s 上限，VAD 端点/用户停止/取消结束；眼镜模式用帧路由暂停/重定向，不重启 CXR transport；同一时刻只有一个采音所有者（显式 lease），不创建第二 recognizer/pipeline。 | 业主选择“手机麦克风/识别后填入输入框”；2026-10-26 澄清；共享管线避免 ~241 MB 重载与唤醒抢占。 | Active（12a/12b-i/12b-ii 已提交；K40 D8/D10 听写有读数，尾音闸门 `c3b4272a` 已设备复验；Task 13e 最终 UI 生命周期已复验） |
| D205 | 2026-10-26 | **四组模型全部随 APK 分发**；构建期从仓库外模型目录按可提交清单 `tools/scripts/p2/model-bundles.json` 校验 bytes/SHA-256 后生成 assets；首启自动、幂等、可校验地 materialize 到现有运行目录（ASR 复用 `EngineModelStore.import`；Laya/EmbeddingGemma 复制+receipt），无需用户配置；release 也启用；另设仅开发/CI 的 `-PwithoutBundledModels=true` 轻量构建（不交付，release 禁用）；许可证/声明随 APK 内置，README/NOTICE 更新。 | 业主选择“全部打包 + 开发轻量开关”；免下载/免 adb push；模型文件不进 git（与 KWS 既有规则一致）；`distributionReview` 仍 Incomplete，不是法律审计结论。 | Active（K40 Task 4 debug 首启 Verified；15 份许可证/派生 bundleId/manifestDigest 已提交 `ee3b43d5`；release 路径/干净安装/空间不足/最终构建首启迁移 `NotMeasured`；见 §20.1） |
| D206 | 2026-10-26 | **版本两端 `versionName = "0.4.0-preview"`、`versionCode = 4`**（同一次 bump 提交）；README/NOTICE 更新；不打 tag、不 push、不 merge。 | 0.4 版本口径；AGENTS git 规则。0.4 契约变化全部加法式，`wireVersion` 仍 1。 | Active（14a 已提交 `a8ecc067`；最终 debug APK 设备读回 `versionCode=4`/`versionName=0.4.0-preview`；docs 最终提交见 git log；无 push/tag/merge） |
| D207 | 2026-10-26 | **验收口径**：K40 `2696813b` + 眼镜 `1901092544027662`；JVM 全量 gate；设备能做就做真机读数，不能做如实 `NotMeasured`；旧判词不变；设备 UI 操作只走 `edith.phone.a11yprobe`；合成 TTS/脚本模型必须标注，不当真实人声/真实模型证据。 | AGENTS 设备规则、D46/D65/D114/D117；`Partial`/`NotMeasured` 不是通过。 | Active（JVM 已收口；最终 K40 矩阵 Task 13e 运行中，未把 pending 行写成通过；见 §20.2） |
| D208 | 2026-10-28 | **脱离眼镜唤醒固定回应「我在」+ 回声静默替换**：仅 glassesDetached=true 且 host 接受唤醒时，用本机 TTS 说「我在」替代 beep；EchoMuteWindow 在播放请求时同步开窗、onDone/onError+150 ms 混响尾关窗、5 s watchdog 兜底；窗口内麦帧换零（时间轴保留），ASR/VAD/KWS 均不收回声；WAKE 事件不延迟，零新增延迟；遵守「语音朗读」；不是回合/不写对话/不发 CXR。眼镜模式、回答 TTS、听写、主动辅助均不受影响（窗口从不为它们打开）。 | 业主 2026-10-27/28 确认：脱离无法辨别唤醒；半双工代价（ack 窗口内抢话丢失）已接受；AEC 留待后续。 | Active（JVM 35 定向 + app 148/1693 绿；真机 ack 交互待业主复测） |

### 20.1 0.4 实施裁决、复审修复与残余（2026-10-27 更新；非新增产品决策）

- **D201 ack 修正已完成（docs-only）**：spec §4.3、R-0.4-J5 与 `CONTRACTS` §2.8.2 已统一为：`PhoneHintDevice.show` 在 `sink.submit` 内**无条件、同步、恰好一次**回调 `Displayed`；它不检查 `say-<hintId>` 是否存在；答案行由插件在 submit 后 `publishAssistantFinal` 写入，是**设计不变量**，不是运行时 gating。`PhoneHintDeviceTest` 5 tests 钉住该契约；未改产品行为。
- **P4 遥测归属已修正**：`P4_DETACH` 不含 `lockscreen_hold`；`lockscreen_hold` 在 `P4_AUDIO`；spec §4.4/§7.2 已按实现改写。
- **Task 9 stop-before-save（fix1 `fa05b5de` + fix2 `39b36852`）**：运行中保存先停宿主会话，再直接 `Context.stopService`；false/throw → `SESSION_STOP_FAILED`，不写 blob、不移动 live gate、`stoppedSession` 仍如实为 true；`SessionService.onDestroy` 先取消收集器再 `stopForeground(REMOVE)` + 直接 `NotificationManager.cancel`。直接 stopService 的拒绝路径只有 JVM 覆盖，通知取消为设备证据。
- **Task 12 拆分已完成**：12a `c6bfb7d4`；12b-i `ae2cad59`；12b-ii `f7389627` + fix1 `0f2e4bda` + fix2 `da6032ff`；`MIC_DICTATION_ENABLED=true`，单 lease/帧路由/`P4_DICTATION` 已接线；设备 D8/D10 有读数。听写**不是**自动发送，也不进插件/`HostRuntime`。
- **Task 4 模型证据边界**：K40 debug APK `1,111,007,965 B` / `8b4973eb…`，首启 launch→ready `13.79 s`、provisioning `12.81 s`、二次冷启动 `0.453 s`、14/14 SHA；**该构建只有 10 份许可证文本**。`ee3b43d5` 后改为 15 份 + 派生 bundleId + manifestDigest receipt；构建审计 15/15 + 15/15 + 6/6 PASS，但该最终构建的设备首启/旧 receipt 迁移未重跑。release provisioning、干净安装、空间不足/中断恢复、APK 字节可复现仍 `NotMeasured`。
- **Task 9 设备矩阵（fix1 `fa05b5de`；fix2 `39b36852`）**：D3 Verified（真实开关+持久化+运行中先停会话；原生 1080×2400 视口触达 `NotMeasured`）；D4 最终 Verified（Task 13e；strict-local neutralization 后 60 s 零 outbound）；D5 **Verified（2026-10-27 业主真人验收）**（最终；有利素材 5/5、同文件控制 1/5）；D5b Verified（screen-off；keyguard `NotMeasured`）；D6 Verified；D10 smoke verified；D11 Verified（仅系统权限拒绝；mic busy/初始化失败 `NotMeasured`）。证据：[detached-wake-device](evidence/2026-10-26-0.4-detached-wake-device.md)。
- **2026-10-27 整分支复审 v2 修复**：
  - **严格本地（`4c1def3e`）**：live 眼镜会话后同进程切脱离仍发 `EdithHint: outbound: Ping/Sync`；`CxrHintLink.detach()` 取消 clock renewal/PING timeout、发送层拒绝所有后续 sends、清除 mapping/panel/session，组合根关闭并遗忘 vendor connection；stale wear/STOP/PIN/CONFIRM/PONG 不再进入 host；`P4_DETACH_LINK state=neutralized ...` / `state=reattached` 为锚点。**JVM only；最终二进制 outbound 计数已由 Task 13e 验证**。
  - **停所有 live 会话（`9db5fcb4`）**：`DetachGate.RUNNING_STATES = SessionState.entries - Idle`，覆盖 Authorizing/Connecting/BuildingScene/Interrupted/Reconnecting/Error；`stopSession` 后即记 `stoppedSession=true`，服务 stop 被拒仍拒绝保存并报 `SESSION_STOP_FAILED`。
  - **15 份许可证/派生 id/摘要（`ee3b43d5`）**：15 份文本统一进 `model-bundles.json licenses[]`；构建从 21 条目重算 canonical `bundleId` 并对声明值 fail-closed；receipt 改为 `bundleId` + `manifestDigest` 两行，旧 id-only receipt 视为 stale、升级后首次重拷一次；`verifyBundledModelManifest` 审计 entries=21/emitted=15/licenses=15。
  - **听写尾音闸门 + ASR re-arm（`c3b4272a`）**：听写终态在恢复唤醒环前关闭宿主转写路由；只有显式 KWS wake/typed/Help/新会话/新 audio epoch 重开；尾音/环境声被计数丢弃（`P3_TRANSCRIPT_ROUTE state=drop ...`），**刻意代价**。`SpeechPipeline.rearm()` 在另一个 session/generation 首帧或新 audio epoch arm 时清 paused/回合/pre-roll 并重置引擎；`P3_PIPELINE` 记录 fault/paused/rearmed。设备 13d：final 后 35 s 无 `P3_CONV queue`、typed 首次 accepted；两轮 in-process stop/start 后唤醒 ASR 正常；**精确 fault code 仍未在设备命名 `NotMeasured`**。
  - **composer（`fd490b97`）与草稿/状态生命周期（`851bd478`）**：输入行改为 pill composer；草稿/结果提升到 `PhoneScreen` 跨页签保留，跨 live 边界清旧 `result` 不碰 draft；听写 final 一次性消费。Task 13e 在最终二进制上复验了草稿跨页签保留、NoSpeech 终态清除与 final 后立即 typed accepted；**实际观感/键盘避让仍 `NotMeasured`**。
- **KWS 换 epoch 重置（`b2d03c06`）**：`AudioIngressLoop.start(epoch)` 在换 epoch/音频源时调用 `wake?.reset()`，重新错相 8 条原生 KWS 流；同一 epoch 重复 start 仍为 no-op。JVM `AudioIngressLoopTest.startingAFreshEpochResetsTheWakeStreams` 红→绿。设备（APK `77858647…1A78C2BA`）在「眼镜会话 → 脱离 → 手机麦」路径上有利素材 5/5 合成播放 `P3_WAKE`，同窗口零 CXR；但同一 APK 冷启动脱离对同一 `wake13g.wav` 为 0/3（`wake-zh` 1 次亦 0），两读数并列，D5 记 `Partial`（中文合成不稳定）；真实人声、多轮切换与长稳 `NotMeasured`。证据：[KWS epoch reset](evidence/2026-10-27-0.4-kws-epoch-reset-device.md)。
- **最终 JVM / 模型审计（最终 HEAD `59f41190`，KWS fix 后重跑）**：18 个产品/工具模块 **439 XML / 4,655 tests / 0F / 0E / 2S**（app 147/1,689、audio-runtime 14/200、plugin-smart-helper 93/969）；探针 15/152 排除；`BUNDLED_MODEL_MANIFEST_RESULT=PASS`，独立 `ASSET_AUDIT_RESULT=PASS`（15/15 + 15/15 + 6/6）。原始 `.local/final-gate/full-test-kwsfix.log`。

### 20.2 0.4 当前状态与剩余工作（2026-10-27 更新；不得读成版本通过）

- **已收口**：三块功能线实现与 JVM 收口；最终 JVM **439 XML / 4,655 tests / 0F / 0E / 2S**（18 模块；探针 15/152 排除），模型审计 15/15 + 15/15 + 6/6；Task 13e 最终 K40 矩阵（D3/D4 严格本地/D7/D8/D9/D10 Verified；D5 Verified（2026-10-27 业主真人验收；合成历史 5/5/0/3 见证据）；D11 权限拒绝 Verified、live revoke NM）与 KWS epoch reset（有利素材 5/5、同 APK 冷启动 0/3，D5 Verified（2026-10-27 业主真人验收））；`ACCEPTANCE.md` §2.16、`STATUS.md` 已按最终读数收口。
- **仍 `Partial` / `NotMeasured`**：D1/D2——debug 完整 APK 安装/首启有读数，但 release APK、release provisioning、干净安装、受控空间、APK 字节可复现 `NotMeasured`；D5b 安全锁屏（keyguard）、D6 最终二进制重跑、D11 mic busy/录音初始化失败仍未测；D12 `NotMeasured`。
- **剩余工作**：业主审阅（`ACCEPTANCE.md` §2.16 / `DECISIONS.md` §20）；真实人声/召回率/真实 CXR 声学、原生 1080×2400 开关视口、精确 ASR fault code、composer 视觉/键盘避让、KWS 多轮切换/长稳、长稳/功耗/RSS 仍 `NotMeasured`。
- **0.4 不是版本级发布通过**；D198–D207 的 `Active` 只表示决策生效、实现已提交/设备矩阵收口，不是版本发布完成。无 push/tag/merge。

### 20.3 0.4 final acceptance record（2026-10-27）

- **HEAD**：`59f41190`；分支 `feat/0.4-detached-wake-bundled-models`；未 push/tag/merge。
- **最终 JVM gate**：18 个产品/工具模块 **439 XML / 4,655 tests / 0 failures / 0 errors / 2 skipped**（探针 15/152 按 D156 排除）；模型审计 15/15 模型 + 15/15 许可证 + 6/6 KWS，`BUNDLED_MODEL_MANIFEST_RESULT=PASS`。
- **最终 K40 APK**：`1,124,338,512 B` / SHA-256 `77858647A4D6DDB0DB270903C0D4128D424AC126E811325AB27FC6961A78C2BA`（KWS-fix build；Task 13e 矩阵 APK `8fd5af06…ef09ff75` 仅早于 KWS reset）。
- **最终 K40 矩阵（Task 13e，APK `8fd5af06…`）**：D3/D4（neutralization 后 60 s 零 `EdithHint: outbound`）/D7/D8/D9/D10 Verified；D5 Verified（2026-10-27 业主真人验收；合成历史 5/5/0/3 见证据）；D11 权限拒绝 Verified、live revoke 平台 NM；D1/D2 保持 `Partial`；D12 `NotMeasured`。D4 窗口从 `P4_DETACH`/`P4_DETACH_LINK` 起算，不含切换前正常 stop 的 `Clear`/`Busy`/`Close`。
- **KWS epoch reset（`b2d03c06`，APK `77858647…`）**：同进程「眼镜会话 → 脱离 → 手机麦」有利素材 `wake13g.wav` 5/5（02:46），但同一 APK 冷启动脱离对同一素材 0/3（03:03–03:04，含 wake13g×2 + wake-zh×1）；两读数并列，只证明 reset 代码正确、设备效果不稳定，D5 为 Verified（2026-10-27 业主真人验收）。多轮/长稳、真实人声仍 `NotMeasured`。证据 [KWS epoch reset](evidence/2026-10-27-0.4-kws-epoch-reset-device.md)。
- **D198–D207** 的 `Active` 表示决策生效、实现已提交；**0.4 不是版本级发布通过**。旧 0.21/0.23/0.24/0.25/0.3 判词不变。

### 21. EDITH 0.5 纯手机模型（2026-10-29；checkpoint，不是版本级发布）

> **0.5.1 注记（2026-10-29，C1）**：用户可见名自 0.5.1 起改为「纯手机模式」；本节 D209–D217 的历史措辞、内部标识、设置键与 channel id 保持原样。收口已完成：见 §21.2（D218–D233 与 D215–D217 编号映射）；D213 的「看门狗 5s→120s」已被 D222 取代。

| ID | 日期 | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D209 | 2026-10-29 | 用户可见名「脱离眼镜模式」改为「纯手机模型」；**内部标识不改**：`DetachGate`、`glassesDetached`、设置 JSON 键、日志事件名、`Capability` 都保持原样。 | 改名是用户口径；内部改名会迁移已安装设备的设置 blob 与既有日志/验收检索口径，收益不抵风险。 | `DetachUi`、`MainActivity`、README；`DetachUiTest`。 | Active（JVM 钉住；设备设置页截图 NM）。 |
| D210 | 2026-10-29 | 纯手机模型开启时显示**半透明、可拖拽、释放吸附左右边缘的悬浮球**；球面用 EDITH 启动器前景图 + 白色圆底，半径 28dp，透明度默认 82%（20–100 可调）；停靠时球心在屏幕边缘（半隐藏）；长按显示麦克风并进入 BALL 听写（识别完**直发**，不进 composer 草稿）；点击打开 App；绿色思考光影只表示 AI 工作/朗读；无会话长按给提示。 | 业主批准的原型（v4）与 2026-10-29 三轮反馈；球是纯手机模型唯一的常驻入口。半隐藏是业主的拖动吸附要求；应用图标、系统字体、不自定义颜色由反馈确定。 | 新增 `overlay/*`、`PhoneOnlyOverlayService`、`BallPlacement`、`BallGestureMachine`；`DictationTarget.BALL` + `startDictation/stopDictation`。 | Active（JVM 手势/几何/听写；K40 球可见 + a11y 探针 tap `P5_BALL event=tap`；拖拽/吸附/长按/长按 ASR 为 NM）。 |
| D211 | 2026-10-29 | 消息用**独立** Material3 卡片（系统字体/默认色）；新消息全文字、卡片背景按气泡透明度（默认 75%，30–100 可调）；**旧消息整卡（卡片+文字）按固定 0.42 再淡化**，2s 后移除；只有最新卡片带右下（左边缘时左下）小尾巴；最多 3 条（**0.5.1 起由 D223 改为最多 2 条**）；回答最多 8 行省略；工具行/思考行与眼镜端同语义。 | 业主：不要硬样式、用安卓通用组件、不要自定义字体颜色；旧/新消息是各自独立的消息框，旧消息按固定比例淡化后 2s 消失；尾巴只在最新一条。 | `PhoneOnlyBubbleContent`、`BubbleStackModel`、`PhoneBubbleRepository`、`PhoneBubbleEvent`；`SmartSettings.OLD_MESSAGE_ALPHA_RATIO=0.42`。 | Active（JVM + K40 真实回合/旧消息 2s 截图；长行/3 条以上/边界帧 NM；**0.5.1 起上限 2 条，见 D223**）。 |
| D212 | 2026-10-29 | 悬浮球与气泡透明度**分别**可调（球 20–100 默认 82；气泡 30–100 默认 75）；设置页两个 Material3 Slider，拖动实时预览、释放一次性写加密 blob；旧消息比例固定、不单独配置；恢复默认按钮。 | 业主最终确认：「悬浮球透明度与气泡透明度改成支持可调整。旧消息按固定比例在气泡透明度的基础上再增加淡化，不单独增加配置」。 | `SmartSettings` 新字段（加法、旧 blob 有默认）；`HostCompositionRoot.previewPhoneOnlyOpacity/setPhoneOnlyOpacity`；`PhoneOnlyAppearance`。 | Active（JVM clamp/持久化/旧比例；设备默认值截图；滑块真机拖拽 NM）。 |
| D213 | 2026-10-29 | **语音朗读运行期即时生效**（创建/安装/释放 player、`SpeechOutput` 能力同步、关闭停当前朗读、无需重启）；**纯手机模型**下最终回答请求朗读（`phoneOnly()` 实时读 `DetachGate`），眼镜模式保持 0.4 HUD-only 最终回答（`notify_user(voice/both)` 不变）；纯手机模型首次开启默认开一次，用户显式关后任何路径不自动打开；**每次播放打开 `EchoMuteWindow`、`onDone/onError/cancel` 关闭**，窗口内手机麦帧换零；看门狗 5s→120s（**0.5.1 起由 D222 删除 120s 看门狗，改显式 `clear()` + 150ms 混响尾**）。 | 业主报「语音朗读无效，怀疑只适配眼镜」。实因：① 最终回答 emit 从不带 `optionalSpeech`；② player 只在组合创建时构建，运行期开关只写 blob。设备复测又发现回答被手机麦自听：ASR 把回答转成用户句并触发续聊自答；故把 0.4 只用于唤醒 ack 的窗口扩展到所有播放。眼镜模式改了会改变其回合完成语义，超出本次手机模型修复。 | `SpeechPlayerRouter`、`AndroidSpeechPlayer`（echoMute）、`HostCompositionRoot`、`SmartAssistantPlugin`（`phoneOnly`）、`SmartWiring`、`AndroidSpeechPlayerTest`、`SpeechPlayerRouterTest`。 | Active（JVM + K40：`P5_TTS off→on player=created`、最终回答 `P5_SPEECH start/end`、修复后无自听 `P3_ASR final=true`；眼镜模式 TTS 未重跑、>5s 长回答与其它引擎 NM；**120s 看门狗条款已由 D222 取代**）。 |
| D214 | 2026-10-29 | 悬浮球/气泡用 `specialUse` 前台服务 + 两个 `TYPE_APPLICATION_OVERLAY` 窗口（小球可触、全屏气泡不可触）；自定义 `OverlayLifecycleOwner`；服务 `START_NOT_STICKY`；只在模式开 + `SYSTEM_ALERT_WINDOW` 已授予 + 组合存在时启动；模式关/权限缺失/组合缺失即停；设置页在缺权限时给系统授权入口；通知低优先级、静音、常驻。 | 桌面级悬浮球必须活过 App 后台；Service 里 Compose 没有 Activity 生命周期，需要自定义 owner；全屏不可触窗口让气泡不挡下方 App；`specialUse` 是持久 overlay 的真实类型。 | Manifest 权限/服务 + `PhoneOnlyOverlayService` + `OverlayLifecycleOwner`；`MainActivity.onResume`/`DetachGate` 同步。 | Active（K40 API 33：服务 on、两窗口、FGS count=1；API 34 类型/权限拒绝/进程死亡/旋转 NM）。 |
| D215 | 2026-10-29 | 气泡时序：最多 3 条（**0.5.1 起由 D223 改为最多 2 条**）；新消息 200ms 淡入；旧消息被顶替后保持 2s 再 250ms 淡出移除；3s 无事件整栈淡出；思考行在用户句后自动出现；工具 START/END 更新同一行；流式回答更新同一气泡。 | 与眼镜端 HUD 的时序语义对齐；业主确认 2s 与固定比例；模型测试负责边界。 | `BubbleStackModel`（纯模型）、`PhoneBubbleRepository`。 | Active（JVM 时序；设备截图；逐帧/边界抖动 NM）。 |
| D216 | 2026-10-29 | 纯手机模型只显示 WAKE 对话；PROACTIVE 行不进入气泡（仓库直接拒绝非 WAKE）；主动辅助在纯手机模型下仍不生效（0.4/D202 不变）。 | 纯手机模型没有主动提示的展示/关闭契约；0.4 已确认主动辅助关闭。 | `PhoneBubbleRepository`、设置页提示。 | Active（JVM）。 |
| D217 | 2026-10-29 | 气泡工具行/思考行与眼镜端同语义（真实工具标签、START 转圈、END 对勾、系统字体与默认色）；回答行最多 8 行；朗读时最新回答带小喇叭图标；悬浮球的光环只表示思考/朗读，不做文字。 | 业主「工具调用信息/样式与眼镜一致；字体不要自定义颜色/样式，用系统默认」。 | `PhoneOnlyBubbleContent`、`PhoneOnlyBallContent`。 | Active（设备截图；字体/大字号/横屏布局 NM）。 |

- **边界**：0.5 未改 `wireVersion`、未改 CXR 协议、未改 `Assistance`/插件公共契约；`SmartAssistantPlugin` 新增的 `phoneOnly` 构造参数带默认值（加法），`PhoneBubbleFeed`/overlay 类型为 phone-app 内部。0.5 **不是版本级发布通过**；设备 NM 项见 [0.5 device evidence](evidence/2026-10-29-0.5-phone-only-floating-ball-device.md)。无 push/tag/merge。

### 21.1 0.5.1 实施裁定（T8；C12/C14/C15，收口时并入 D218+）

> 本节记录 0.5.1 实现中必须落定的技术裁定，不是新的产品决策；按 spec §8，编号在 T12 收口时并入 D218+。

- **生命周期依赖保留 lifecycle-runtime 2.10.0 / savedstate 1.4.0（C14）**：`phone-app/app/build.gradle.kts` 继续显式声明这两个坐标，不回退到 Compose/Activity 图默认的旧版本族。原因：0.5 的服务内 `ComposeView` 需要 `ViewTreeLifecycleOwner`/`ViewTreeSavedStateRegistryOwner` 在运行期可解析，且 0.5 的 overlay 行为已按这两版验证；回退需要重新验证服务内 Compose 挂载，收益只是版本号变小。两者都是 app 内部依赖，不改 wire/插件契约/settings blob。
- **`ViewTreeLifecycleOwner` 直接 import 在本构建图不可用（C14）**：实测 `import androidx.lifecycle.ViewTreeLifecycleOwner` 在 `:phone-app:app:compileDebugKotlin` 报 `Unresolved reference`（Kotlin 编译类路径拿到的是 lifecycle KMP metadata，不是 Android AAR 的 `androidMain` 类）；因此两个 ViewTree owner 继续用反射安装。差别是 0.5 的“失败只记日志”被移除：安装失败现在发布为可见启动失败（`PhoneOnlyStartFailure.LIFECYCLE_OWNER`），设置页给出理由与重试。若未来构建图能让 AAR 类进入 Kotlin 编译类路径，再改直接 import。
- **overlay 运行状态是进程内 app 状态（C12）**：`PhoneOnlyOverlayRuntime.isRunning`/`startFailure` 由 `PhoneOnlyOverlayService` 写入、设置页读取；`isRunning=true` 只在两个窗口都添加成功后发布，`startFailure` 跨实例保留以支持重试。`sync` 的判定抽到纯 `PhoneOnlyStartStopPolicy`（JVM `PhoneOnlyStartStopPolicyTest`）；K40 上的真实 AppOps 回调/窗口消失仍是 T12 设备项。设置页「已启用」不再只由 `canDrawOverlays` 推导。

### 21.2 0.5.1 纯手机模式更正决定（D218–D233；C1–C16）

> spec §2 的 C1–C16 在此落为 D218–D233，状态均为 Active（实现已提交；JVM/设备读数或 NM 见各行）。0.5.1 是 **checkpoint，不是版本级发布通过**；完整设备矩阵与 NM 清单见 [0.5.1 correction](evidence/2026-10-29-0.5.1-correction.md)。

| ID | C | 规则 | 理由 / 边界 | 影响 | 状态 |
|---|---|---|---|---|---|
| D218 | C1 | 用户可见名「纯手机模型」改为「纯手机模式」；`DetachGate`/`glassesDetached`/设置 JSON 键/`P4_DETACH`/通知 channel id `phone_only_overlay` 均不变；不做 blob 迁移。 | 业主口径；内部改名会迁移已装设备 blob 与既有检索口径。 | `DetachUi`、`MainActivity`、overlay 通知名、README；`DetachUiTest`/`SmartSettingsFormTest`。 | Active（JVM；设备 channel 显示名「纯手机模式悬浮球」；设置页截图 NM）。 |
| D219 | C2 | **仅纯手机模式**取消回答后的自动续聊 10s：新增 `continueAfterAnswer: () -> Boolean` seam，生产传 `settings.continueDialogue && !phoneOnly()`；唤醒后的初始 10s、queued utterance、眼镜模式不变；下一句由长按悬浮球手动 ASR。 | 纯手机模式场景不同；初始唤醒窗口不是续聊窗口。 | `ConversationCycle`/`SmartAssistantPlugin`/`SmartWiring`；`PhoneOnlyContinuationTest`。 | Active（JVM；设备 `P3_CONV close route=WAKE reason=continue_off`）。 |
| D220 | C3 | 听写 owner 隔离：`stopDictation(target)` 只停匹配 owner；BALL 只在 lease 真正获得时停止/取消；页面过滤显式 BALL，`owner==null` 放行；BALL lease 时 composer mic 禁用；BALL 被拒只提示、不碰 composer。 | 0.5 球会截断 composer 草稿，是实际 bug（review Important #2）。 | `HostCompositionRoot`、`PhoneOnlyOverlayService`、`PhoneScreen`、`DictationOwnerTest`。 | Active（JVM；真实 composer/BALL 交替设备交互 NM）。 |
| D221 | C4 | 回答仍在朗读时开始长按：先 `stopCurrent()` 停朗读并 `echoMute.clear()`，再进入 BALL 听写；取消也清理。 | 手动 ASR 是纯手机模式下一句的唯一入口，必须在朗读中可接管。 | `HostCompositionRoot`、`SpeechPlayerRouter`、`PhoneOnlyOverlayService`。 | Active（JVM；真实长按设备复测 NM）。 |
| D222 | C5 | 删除 `EchoMuteWindow` 120s 时间看门狗；先修 close/ack/router 所有权，再用显式 `clear()` 保底（手动 BALL、关闭朗读、会话停止、长按打断）；player close/cancel 保留 150ms 混响尾；丢失 TTS 结束回调最多静音到下一次手动长按/停止，**不会永久静音**。 | 业主明确移除看门狗；旧看门狗掩盖了生命周期漏洞。 | `EchoMuteWindow`、`AndroidSpeechPlayer`、`SpeechPlayerRouter`、`HostCompositionRoot`；`EchoMuteLifecycleTest`。 | Active（JVM + 设备 K7：播放结束无自听 final；>5s 长回答 NM）。 |
| D223 | C6 | 气泡最多 **2 条**（最新 + 1 条旧）；新消息到达时原最新变旧，原旧**立即移除**；不补 200ms 加速淡出；旧 2s/250ms 淡出、3s idle、最新尾巴不变。 | 业主 2026-10-29 追加；「其余逻辑不变」。 | `BubbleStackModel.MAX_VISIBLE=2`、`PhoneBubbleRepository`、`BubbleStackModelTest`。 | Active（JVM + 设备截图 `visible=2`/旧卡移除；第三条即时移除仅 JVM）。 |
| D224 | C7 | 整泡透明度：最新整泡（卡片+文字+尾巴+阴影）alpha = 气泡透明度（默认 75%，30–100）；旧整泡 = 0.42 × 气泡透明度 × fade；卡片底色 `surface`、系统字体/颜色不变；`PhoneOnlyAppearance.oldMessageAlpha` 必须被 UI 使用。 | 业主选「文字也随滑块变淡」；半透明让下方 App 可见、减少遮挡。 | `PhoneOnlyBubbleContent`、`PhoneOnlyAppearance`、`BubbleRenderMath`、`PhoneOnlyAppearanceTest`。 | Active（JVM + 设备默认值视觉；30% 极值/滑块拖拽 NM）。 |
| D225 | C8 | phone-only 时系统提示追加稳定 STATIC section：「悬浮球/气泡是 EDITH 自己的界面…不要点击…以目标 App 窗口为准」；眼镜模式不追加；`PromptCatalog.VERSION` 由 0.4.1 升为 **0.5.1**（目录新增 18 号资源 `phone-only/overlay.md`）。 | Agent 工具调用会读屏；全不透明/无归属说明会被误判。版本随目录内容变化，避免同版本混内容。 | `PromptCatalog`、`SmartAssistantPlugin`、`prompts/phone-only/overlay.md`、`PromptTelemetryTest`、`PhoneOnlyPromptTest`。 | Active（JVM + 设备 `P3_PROMPT version=0.5.1`、phone-only `staticChars=2442` vs 眼镜 2301；模型实际不误判 NM）。 |
| D226 | C9 | 球透明度作用于整颗球：shadow + 白圆底 + EDITH 图标 + 麦克风 scrim 同一 alpha 层；绿色思考/朗读光环在层外、不随球透明度变。 | review Important #3；20% 时白底/阴影仍是实心白盘。 | `PhoneOnlyBallContent`、`BallPlacement`/`PhoneOnlyAppearance`。 | Active（JVM alpha clamp；设备 20% 整球视觉 NM）。 |
| D227 | C10 | 本地 Notice（无会话、听写被拒等）3–4s 自动消失；不依赖 `TurnIdle`；不占旧/新气泡的 2s 规则（或明确同规则）。 | review Important #7；Notice 曾永久留在桌面。 | `BubbleStackModel.dismissAfter`、`PhoneBubbleRepository`。 | Active（JVM + 设备：1s 在、4s 后消失截图）。 |
| D228 | C11 | 拖拽用**屏幕坐标** delta：service 记录球窗口原点，controller 用绝对指针位置计算位移；不再依赖窗口移动后的局部 `positionChange()`；长距离真实拖动仍手动/NM。 | review Important #8；实际拖动与手势机测试路径不一致。 | `PhoneOnlyOverlayService`、`BallDragController`、`BallDragControllerTest`。 | Active（JVM controller 级指针流；真实拖动/吸附 NM/手动）。 |
| D229 | C12 | 权限运行中撤销：注册 `AppOpsManager.startWatchingMode(SYSTEM_ALERT_WINDOW)`，撤销即移除窗口/停服务/清通知；设置页「已启用」读 `isRunning`；启动失败可见可重试；`onDestroy` 注销 watcher。 | review Important #5/#12；0.5 撤销不停 overlay、只看 `canDrawOverlays`。 | `PhoneOnlyOverlayService`、`PhoneOnlyStartStopPolicy`、`PhoneOnlyOverlayRuntime`、`PhoneOnlyStartStopPolicyTest`。 | Active（JVM；设备 `refused NO_OVERLAY_PERMISSION` + 重新授权 `state=on`；**运行中撤销 watcher 回调 NM**）。 |
| D230 | C13 | 球顶部约束：垂直范围保留顶部 45% 屏高的气泡栈空间；球可拖到保留线但不能再上；气泡始终在球上方，不翻到球下方。 | review Important #13；业主确认「气泡在球上方」。 | `BallPlacement`、`BubbleLayout.reserveTopPx`、`BallPlacementTest`/`BubbleLayoutTest`。 | Active（JVM；设备观察：**两条**长卡同屏、旧卡顶部被重叠/裁切（证据 §3.1）；45% 的精确边界 NM）。 |
| D231 | C14 | 保留 lifecycle-runtime 2.10.0 / savedstate 1.4.0 并记录；`ViewTreeLifecycleOwner` 本构建图不可直接 import，两个 ViewTree owner 继续反射安装；安装失败发布为可见启动失败并可重试。 | 回退需重验服务内 Compose 挂载；不把依赖升级留成未记录副作用。详见 §21.1。 | `phone-app/app/build.gradle.kts`、`PhoneOnlyOverlayService`、设置页。 | Active（JVM + 设备 overlay 启动；失败路径设备未注入 NM）。 |
| D232 | C15 | 关键 Minor 一并修：透明度滑块在模式关闭时也可见并说明；`onDestroy` 取消 BALL、清空气泡；通知用 app 自有单色图标；光环只在有活动时跑；气泡帧循环只在可见时跑；snap 250–350ms、真实 insets；sync 用显式 requested/状态并区分 refused/stop；`onDestroy`/refused 日志分开；球被拒文案「打开 App 开始会话」；D215–D217 编号映射修正（见下）。 | review Minor；不改变业主确认的视觉/时序语义。 | `PhoneScreen`、`PhoneOnlyOverlayService`、`PhoneOnlyBallContent`、`res/drawable/ic_edith_notification.xml`、`BubbleLayout`、`PhoneOnlyStartStopPolicy`。 | Active（JVM + 设备 channel/图标读数；`startAttempted`/`drop(1)`/watcher-failure/white pulse 等剩余 Minor 见证据 §8）。 |
| D233 | C16 | 证据 append-only：新增 `docs/project/evidence/2026-10-29-0.5.1-correction.md`，并列旧/新读数；旧 0.5 证据不改写；只回退本次新增的探针扩展（产品无障碍/独立探针 app 保留）；补 review 缺失测试；独立复审 + 全量 JVM + K40/眼镜复验后再收口；不 push/tag/merge。 | 0.5 证据被原地改写、共享探针被改是流程违规；业主明确「只改这次加的」。 | `docs/project/evidence/*`、`ACCEPTANCE §6`、`tools/probes/phone-accessibility-probe` 回退。 | Active（T10/T11/T12；探针 1/0.1.0 `92C43882…`；0.5.1 矩阵 Verified/Partial/NM 见证据）。 |

- **D215–D217 编号修正（收口；2026-10-29 T12 review 复核修正）**：0.5 spec §3 的 **D215/D216/D217** 分别是「尾巴 / 容量（最多 3 条）/ 3s 收起」，与 DECISIONS §21 的 **D215（气泡时序）/ D216（仅 WAKE）/ D217（工具行）** 不是同一组条目。本收口以 DECISIONS §21 表为权威；spec 侧映射为：**spec D215（尾巴）→ DECISIONS D211（`只有最新卡片带…小尾巴`）/ D223（`最新尾巴不变`）**；spec D216（最多 3 条）→ DECISIONS D211，已被 **D223 改为最多 2 条**；spec D217（3s 收起）→ DECISIONS D215，保留。其余 spec 决策逐条对应：**spec D210（球）→ D210**（含长按 ASR 与无会话提示）、**spec D211（气泡样式）→ D211**、**spec D212（长按 ASR/直发/无会话提示）→ D210 + D220**（D220/C3 为听写 owner 隔离）、**spec D213（语音朗读）→ D213**（其 120s 看门狗条款由 D222 取代）、**spec D214（生命周期）→ D214**。历史条目不改写。
- **0.5.1 收口读数**：全量 JVM `test testDebugUnitTest --continue --rerun-tasks` 18 个产品/工具模块 **456 XML / 4,806 tests / 0F / 0E / 2S**（探针 15/152 单列；排除 `EngineBundleProvisioningTest` 为 455/4,799/1S）；K40 0.5.1 final debug APK `91,240,820 B` / SHA-256 `7DBC6B69…CA17044`（设备回读同哈希）。K1/K3/K5/K6/K7/K8 Verified；K9 Verified（服务/双窗口/模式切换）/ live revoke NM；K2/K4 Partial；K10 NotMeasured。**不是版本级发布通过**；无 push/tag/merge。
