# EDITH 跨会话协作入口

本文件是项目导航与约束，不是产品设计批准书。上级指令和用户最新请求优先；加载当前实际可用的 Superpowers skills 后遵循其流程。

## 每个新会话先做什么

1. 确认实际工作目录，读取 [项目状态与交接](<docs/project/STATUS.md>)。
2. 读取 [0.21 基线架构规格](<docs/superpowers/specs/2026-10-17-edith-0.21-baseline-architecture-design.md>)、[技术决策](<docs/project/DECISIONS.md>)、[公共契约索引](<docs/project/CONTRACTS.md>)、[功能验收矩阵](<docs/project/ACCEPTANCE.md>)。
3. 根据索引只读取当前阶段计划（若有）、相关代码、测试和验收证据；不加载全部历史聊天/日志，也不恢复已退役的旧文档。
4. 核对 Git 分支、HEAD、未提交改动、实际依赖和前序接口。没有 Git/代码/报告时如实记录，不创建虚假的已完成状态。
5. 输出简短接续确认：当前阶段/任务、已批准基线、实测能力、未验证项和下一动作。可核实的已批准决定不重问；矛盾或缺失的批准要指出。
6. 遵守"铁律"章节内容

## 自动衔接与停止边界

- 用户指定阶段，不需要用户指定每个技能。Agent 根据已记录的步骤和批准状态自动选择技能。
- 已批准规格且内容未变时接续计划；已批准计划且前置条件有效时接续未完成任务，不从头 brainstorming 或重生成整份计划。
- 保留技能要求的用户审阅/执行方式选择、设备操作与敏感操作确认。不能把本文件或 Agent 自写的状态当作用户批准。
- 默认一会话只推进当前阶段；较大阶段可拆成设计/计划、实施、验收会话。阶段结束不自动开始下一阶段。
- 用户明确要求暂停、切换会话时，在最近安全任务边界保存 checkpoint。不得声称已新建会话或能可靠读取剩余上下文百分比。
- 新会话无法访问旧会话后台 job 的句柄时，先核对 PID/端口/文件或请用户确认，不重复启动同一服务。

## 版本口径与文档基线（2026-10-17）

- **0.21 已闭环，但判词是**：**0.21 范围内未完全通过 / 不满足完整交付标准。** 不得把 `NotMeasured` 读成通过；M2 0/10 与 AC-13/16 设备行为失败不得读成未测。
- **当前权威文档只有 5 份入口**：`STATUS.md`、0.21 基线架构规格、`DECISIONS.md`、`CONTRACTS.md`、`ACCEPTANCE.md`；证据保留集以 `ACCEPTANCE.md` §6 为准（2026-10-27 收口时 **55 份**引用；`docs/project/evidence/` 目录中另有历史阶段证据，不得用于当前判词）。
- 旧蓝图、阶段 spec/plan、P0–P3 进度账本、旧状态/交接文档已退役。**不要把旧文件恢复到工作树**；历史用 git 查。
- 同一主题只允许一处权威；发现代码与文档不一致，记 drift，不让任一份文件自动覆盖另一份。
- **工具面/权限口径（2026-10-22，D196）**：0.21 历史验收 9 个 / 当时代码 21 个的 drift 保留在 0.21 判词，不重写；0.3 Phase 4A 当时是 `Capability.Actions` 的 **24 默认工具**（18 atomic + 4 umbrella + `notify_user`/`load_skill`）；D196 已退役 `launch_app`，**当前为 23 默认工具（17 atomic + 4 umbrella + 2 交付）**；P4-A 历史判词仍记 24，不重写。`ExecutionRouter` 不再作为生产 provider；`propose_hint` 只在主动提示回合。**4B 的权限上限/确认/不拦截实现与 JVM 收口已完成；Phase 4 设备验收已由业主 D195 接受（P4-B/C/D Verified；P4-A Partial、P4-E NotMeasured 作为验证材料/方式边界），不是全部指标全绿，不是版本级发布通过。** **ASR 复读已按 D193 以 fp32 decoder 数据修复完成 K40 合成 TTS A/B；持续对话的 WAKE/PROACTIVE 下一句队列已按 D194 修复（WAKE 设备、PROACTIVE JVM）——仍不是版本级通过，真实 CXR 人声与主动路线设备多轮 `NotMeasured`（见 `ACCEPTANCE` §2.15）。**

- **0.4（2026-10-27 收口，未发布）**：脱离眼镜/手机麦、文字/听写输入、四组模型内置；最终 JVM 18 个产品/工具模块 **439 XML / 4,655 tests / 0F / 0E / 2S**（探针 15/152 排除），模型审计 15/15 + 15/15 + 6/6；最终 K40 矩阵 Task 13e 已完成（D3/D4 严格本地/D7/D8/D9/D10 Verified；D5 Verified（2026-10-27 业主真人验收；合成历史 5/5/0/3 见证据）；D11 权限拒绝 Verified、live revoke NM），KWS epoch reset 后中文合成有利素材 5/5（`P3_WAKE word=伊迪丝`），但同一 APK 冷启动脱离对同一素材 0/3；D5 已由 2026-10-27 业主真人验收改为 Verified，合成历史 5/5 与 0/3 保留为过程证据；最终 K40 APK `1,124,338,512 B` / SHA-256 `77858647A4D6DDB0DB270903C0D4128D424AC126E811325AB27FC6961A78C2BA`（KWS-fix build；13e 矩阵 APK `8fd5af06…` 仅早于 KWS reset），D1/D2 保持 `Partial`（release/干净安装/空间/字节可复现 NM）；release/干净安装/空间、keyguard、真实人声召回率/误触发、mic busy、APK 字节可复现、原生开关视口、精确 ASR fault code、KWS 多轮/长稳仍 `NotMeasured`。详见 `STATUS.md` 顶部与 `ACCEPTANCE.md` §2.16。**不是版本级通过**。2026-10-28 结项：已合入 `dev` 并推送 Origin（业主授权推送）；D5c 真机复测结项前未回，如实保留。

## 全局约束

- 本版本交付**智能助手**单插件；**面试助手（P4）已由 `D57`（2026-10-12）搁置**——模块与注册保留，但不在插件选择器中、不可激活。简历/JD、声纹注册与 SELF/OTHER/UNKNOWN 归因、本人回答上下文、面试官提问触发**全部不在本版范围**。
- Android 手机、大陆 Rokid Glasses、用户报告固件 1.26.011、已有开发线；SDK/固件兼容性以官方样本与实测为准（P0 已关闭）。
- App 提供公共采音、VAD、ASR、声纹、TTS、显示、网络/协议设施、存储、权限与延迟机制；插件拥有场景触发、业务上下文、Prompt 与模型调用决策。
- 本版**不注册声纹**、不承诺 SELF/OTHER/UNKNOWN 归因（D57）。
- 不把候选技术写成已锁定，不把硬件规格、Mock 或聊天结论当实机证据。
- 不提交或记录 API Key、原始声纹、未脱敏录音/简历；不因本规则而扩大上传授权。
- 不撤销用户改动，不擅自安装 Spec Kit、不擅自合并/推送/发布。
- 当前分支状态：0.4 于 2026-10-28 结项，`feat/0.4-detached-wake-bundled-models` 已合入 `dev` 并推送 Origin（**该次 push/merge 为业主当次授权**）；未打 tag；`main`/`dev` 的既有 push、`v0.3.0-preview` tag 与 0.22 merge `eccc090` 是历史事实；提交/推送授权不继承。

## 记录与变更

- 用户意图和已批准规格规定目标；代码/锁文件描述实际实现；测试证据描述验证范围。发现不一致应记录 drift，不让某一份文件自动覆盖另一份。
- 对范围、App/插件边界、公共接口破坏性修改、供应商/凭证/云上传策略变化，先提出变更与影响分析，获得批准后更新决策、契约、计划与回归列表。
- 纯局部实现细节遵循当前技能的裁决规则并记录，不把每个实现选择都升级成用户审批。
- 每完成可恢复的任务、发生重要决定、遇到阻塞或结束会话，更新相关验收行、当前计划（若有）、`STATUS.md`。不能只更新 GUI todo。
- `STATUS.md` 顶部记录 checkpoint 标识；更新 `STATUS.md` 作为最后一步。记录不一致时先核对代码和证据再恢复。
- Superpowers 原生账本如存在，记录其精确路径。其临时目录可能被流程清理；清理前把必要裁决、测试结果和任务/commit 对照持久化到项目记录，不删除原始实测证据。

## 官方文档地址
- CXR-S：https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/cn/index.html?documentId=201f7e36b17b4a4389ea4c38a4f23381
- CXR-L:https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/fa92e9303bc848e6b4a20c062f2c792b.html

## 铁律
- 每次开发前，都要读取D:\Project_new\rokid-glass3-sdk-skill\SKILL.md，这是ROkid官方提供的AI辅助开发SKILL，可用于指导你开发。
- 开发、验证过程中，优先使用D:\Project_new\rokid-glass3-sdk-skill\SKILL.md寻找答案，然后联网检索，最后再测试验证，不允许编写臆想中的代码进行实机验证。
- 开发过程中可以参考官方文档，与第三方文档(路径D:\Project_new\rokid-docs)
- **眼镜端与手机端 App 的互联必须 100% 按官方 demo 与官方文档 SKILL 实现；与 demo/官方文档一致的交互确信度很高、视为正确，不需要专门做真机验证。**
  - 范围：会话创建与授权、自定义命令的收发、回调注册的**层级**（client 层 `CXRLink.setCXRCustomCmdCbk` vs session 层 `CxrSession.addCustomCmdCallback`）、
    订阅方式、`Caps`/载荷的**字段与顺序**，以及调用的是哪个对象上的哪个方法——全部以厂商样本为准，
    **严禁自行发明协议形状或"更合理"的写法**。
  - 实操顺序：先读 `SKILL.md`（注意它是**路由文档**，要按它的表打开其自带的参考：眼镜端/手机端 API 文档、FAQ、Demo 与代码示例），
    再对照官方样本源码（**本机归档：`D:\Project_new\rokid-Offical\`**——原路径 `.local/p2-t17-cxr-official-samples` 已于 2026-10-12 随 P2 工作树清理删除，同日已按官方 OSS 源重新取回并核对哈希；该目录的 `README.md` 记有每个样本的来源 URL 与 SHA-256）与**钉住 AAR 的 `javap` 实证**，最后才写代码。
  - **不得用"我们自己写的代码测不通"否定厂商设计**。2026-10-08 实测已更正：此前"眼镜→手机自定义命令方向不可用"的负结果
    全部来自我们自己的偏离——把入站回调注册在 session 层（demo 用 client 层）、并把回包当成裸 JSON（demo 用 `Caps.fromBytes` 解析序列化 `Caps`）。
    照 demo 对齐后，真机一次即通（`HELLO/PING/SYNC/SHOW/CLEAR` 下发，`PONG`/`ACK` 全部回收）。
    更正记录：`docs/project/evidence/2026-10-08-p2-demo-fidelity-alignment.md`、`docs/project/evidence/2026-10-08-p2-device-closure.md`。
  - 厂商 SDK 确实没有的能力（例：**主动亮屏**，官方 FAQ 只给 `FLAG_KEEP_SCREEN_ON` 且明确指环 `KEY_POWER` 应用层不可拦截），
    才允许改用**平台官方** API（如 Android 屏幕唤醒锁），并在代码注释与证据里写明"厂商无此 API + 官方依据"，不得当作 demo 写法使用。
  - **本条的豁免边界**：硬件/功耗/产品级体验（主动亮屏是否真的点亮、佩戴是否读得清、TTL/断连的真实表现）**仍需实测**；
    凭证与 `D33` 的 RAM 边界不变；**上传授权已按 `D53`（2026-10-11）变更**——**会话级上传开关取消、"会话在运行"即允许**转写与其所在回合上下文发往所配置的模型服务（唯一同意动作＝用户点「开始」）；**仍不变**：日志/证据只记计数与 id、不记正文，记忆写入零网络请求，不落盘原始音频/声纹/简历，不记录凭证；记账仍须如实——**未实测就写未实测，不引用未产生的数字**。

## 安卓 APP 目标载体说明
- 开发调试**与交付验收**均以**红米 K40**（SOC：骁龙 870，内存 12+256，序列号 `2696813b`，MIUI / Android 13 / API 33）为准；
  眼镜侧为 `1901092544027662`（固件 `1.26.011-20260929-150201`）。
- **K70 / Android 16 的适配已按用户 2026-10-10 决定取消**（D46）⇒ K70 **不再作为目标载体**，也不再作为验收行上的"未测项"；**K40 上的实测即目标设备实测**（不再称"外推"）。
- **验收口径按 D46/D65**：**验收设备＝K40；K70 不单独做设备验收**；K40 通过 ⇒ K70 通过成立，**反向不成立**。SoC/厂商特有通路（如 MediaTek NPU、Qualcomm 专属运行时）与 K40 上不存在的硬件不适用本口径，必须在其自有设备上单独测。
- 旧 K70 证据不进入当前证据集，不得外推到 K40；历史用 git 查。

### 设备侧无人值守（用户 2026-10-16 强制要求）

- **一切"人工操作模拟"必须走 `edith.phone.a11yprobe`**（仓库模块 `tools/probes/phone-accessibility-probe/`，包名 `edith.phone.a11yprobe`）。
  它当初就是为**无人值守**做的：由 adb intent 驱动，**没有显式确认不执行**（`A11yIntentActivity` 的 `confirm=true` extra），
  结论以一行可 grep 的 verdict 输出。
- **范围**：点击（节点 / 坐标）、滑动、滚动到可见、等待弹窗、点肯定项、跨应用读界面 —— 全部适用。
- **禁止**用 `adb shell input tap` / `input text` 之类的替代方式模拟操作。两条理由：
  ① 它测不到生产的真实通路（无障碍 vs shell 是两条不同的路）；
  ② `input text` **对非 ASCII 必失败**（`Virtual.kcm` 631 行零非 ASCII），中文输入**只能**走无障碍。
- **失败即如实记**：探针跑不通就写"未验证"，不得改用别的模拟方式把结论凑出来。
- **无障碍服务的启用与验证**（2026-10-16 更正，此前本文件把探针的一条设计选择误读成平台限制）：
  1. **可以脚本化开启**，不需要用户手点：`adb -s 2696813b shell settings put secure enabled_accessibility_services <组件>`
     （shell UID 有 `WRITE_SECURE_SETTINGS`；本项目已实测 Shizuku 的 shell UID 同样可写）。
  2. **写之前必须先 `settings get` 读现值，保留已有项再追加** —— 另一条工作线可能也注册了无障碍服务，
     直接覆盖会把它的删掉（那等于改动别人的仪器）。
  3. ⚠️ **写完必须验证真的生效**：设置写进去 ≠ `AccessibilityService` 实例连上。
     验证方式：logcat 里该服务的连接行，或一次真实动作成功。**验不到就记未验证，不得只凭设置值声称"已启用"**
     —— 这正是本项目"已受理 ≠ 已生效"那条规则的应用。
  4. 探针在服务未启用时会**拒绝执行**并打印 `A1_SERVICE_NOT_ENABLED`（`A11yIntentActivity` 的刻意设计，
     免得误触权限框）—— 看到这行说明要回去做第 1 步，不是探针有问题。
- **构建/安装**：
  1. 安装要带项目自己的调试签名 —— 设 `$env:ANDROID_USER_HOME="D:\Project_new\EDITH\.local\p3\android-home"`，
     否则 `install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
  2. **MIUI 首次安装会弹"继续安装"确认框，需要用户点一次**（此后 `install -r` 直接成功）。
- **装回 / 启用 / 卸载前先确认另一条线是否在用**：这个包同时是邻居工作线的仪器，动它前先核对。
- **安装一律显式指定设备**：`adb -s 2696813b install -r`。**禁用 `gradlew installDebug`** ——
  它会装到**所有已连接设备**（2026-10-16 实测：把探针同时推到了眼镜上）。
## 目录、模块与包命名（0.22 起）

本节是目录/模块/包名规则的唯一权威；设计记录见 `docs/superpowers/specs/2026-10-17-edith-0.22-directory-refactor-design.md`。

1. **顶层只允许六个目录**：`phone-app/`、`glasses-app/`、`common/`、`tools/`、`third_party/`、`docs/`。新增顶层目录必须先改本节并获业主批准。
2. **可部署物在根、以 `-app` 结尾**：模块名与 Gradle 路径同名（`:phone-app:app`、`:glasses-app:app`），`applicationId = com.sheen.rokid.<end>`。
3. **库模块只能放在四个位置**：`phone-app/`（手机专属）、`glasses-app/`（眼镜专属）、`common/`（两端共用）、`tools/`（仪器）。判断"共用"的标准是两端 `build.gradle.kts` 都直接依赖；只有一端使用就不得进 `common/`。
4. **包名规则**：`com.sheen.rokid.<domain>[.<subdomain>]`，全小写、无下划线/连字符；根包不得直接放文件，每个文件必须落在领域包；模块与包不要求同名，但必须登记在 0.22 规格的模块清单里。
5. **依赖方向**：`apps → 本端模块 → common`；`common/contracts` 不依赖任何实现；`tools → 产品模块` 单向，产品模块不得依赖工具与 `test-support` 的生产代码。
6. **插件边界**：`plugin-smart-helper` 不得 import `com.sheen.rokid.phone.*`（只允许 `phone-app` 的 `plugin.smarthelper.host` 包跨边界）；眼镜端不得依赖 `phone-app/`。
7. **测试包镜像主包**；新增文件必须进已有领域包，或先更新本节再加包。
8. **例外登记**：vendored `com.laya` 保持原包名；探针保留 `edith.*`；`third_party/cxr`（原 `probes/p0/vendor`）是 gitignored 构建输入。
9. **移动纪律**：只允许 `git mv`；每次移动后全量构建 + 全量测试；不改 Intent action、资源 id、通知 channel id、系统属性键、唤醒词等运行期协议。
10. **文档同步**：代码路径变化必须在同一提交更新 `README.md`、`AGENTS.md`、0.21 基线规格与验收链接；`docs/project/evidence/` 不可改写，旧路径只在迁移映射里出现。
