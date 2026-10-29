# 无障碍操作第三方 App 的验证码与微信封锁：预研报告（2026-10-25）

- **性质**：预研笔记（pre-research），不是已批准的产品决策、契约或验收依据；结论供业主裁决与后续 spec 使用。
- **问题（用户原话）**：使用无障碍服务去操作其他 APP 时，会弹出验证码；以及微信完全不允许使用无障碍服务。如何解决？
- **前提（用户 2026-10-25 澄清）**：这是**其他应用的风控/安全行为**，不是 EDITH 自身系统的问题。因此本报告不把"被检测/被封锁"当作缺陷，也不提供绕过方案；只回答"外部事实是什么、我们能合法选什么、哪些必须放弃"。
- **方法**：Agent Team 3 路并行桌面调研（微信线 / 验证码风控线 / 平台替代通路线，分报告见 §9）+ Lead 交叉核验 + 与仓库既有 K40 实测证据（E1/E2）对齐。**未做设备新测试、未改任何代码。**
- **设备基准**：红米 K40（MIUI V816 / Android 13 / API 33）；**微信当前未在该机安装**，微信侧均为桌面调研。
- **信度标注**：【官方】【法源】【厂商】【媒体】【社区】【未核实】【项目实测】。凡"未核实"不得读成已验证。

---

## 0. 结论速览

| # | 问题 | 调研结论 | 建议 |
|---|---|---|---|
| 1 | 操作其他 App 时弹验证码 | 验证码是**外部应用风控评分越过阈值后的"人机分离"校验**，不是随机弹窗；金融/平台已工程化检测"无障碍/屏幕共享/录屏/远程控制" | **不存在合规的"消除"手段**；唯一正解是 **fail-closed：停手→交还用户→不重试**；打码/OCR/拟人化绕过 = 法律 + 封号风险，排除 |
| 2 | 微信"完全不允许无障碍服务" | 表述**不准确**：微信官方支持系统读屏（TalkBack/关怀模式）；它封锁的是**第三方自动化**——应用侧节点混淆 + 白名单校验 + 行为风控 + 协议处罚 | **全自动读/回微信 = No-go**；保留"通知只读→眼镜提醒""单向分享/官方客服触达""用户在场时辅助输入" |
| 3 | 换通道能否解决微信 | **不能**：节点混淆是微信应用侧机制，Shizuku/UiAutomation/root 也拿不到真实节点（社区实测）；换通道只是换一组可检测信号，且带来新的合规/隐私代价 | 不做"换通道重试"；把"目标 App 不可用"当作正常失败态并写入验收口径 |
| 4 | 替代无障碍的平台通路 | 没有正常授权通道能同时替代 a11y 的"读屏+点击+中文输入"；可替代的只有**读通知（+RemoteInput 回复）、Intent/DeepLink 直达、a11y 自身 ACTION_SET_TEXT** | 默认档：a11y 主力（明示授权+可停止）+ 通知监听 + Intent 优先；Shizuku 只做高级/脚本模式；root/Xposed 明确不做 |
| 5 | 这不是 EDITH 的 bug | 微信/银行等第三方风控是外部决策，我们无法消除、也不应绕过；用户本人设备 ≠ 获得平台授权 | 产品叙事与验收口径按"外部约束"处理，见 §5 |
| 6 | 如果 root 呢 | **不能解决，只会把问题换成"规避检测"的军备竞赛**：root 不给微信节点（节点混淆是应用侧白名单；社区实测微信 8.0.52+ 连 `uiautomator dump` 都只返回空根节点【社区，未核实】），且 root/解锁会被完整性校验与银行风控识别，账号风险不减、设备基线被破坏 | **不列入产品/验收路径**；纯研究需独立设备 + 可牺牲账号，见 §10 |

**一句话**：这类问题没有"技术解法"，只有"产品取舍"——把"全自动操作微信/自动过验证码"移出范围，把"提醒、分享、用户确认下的辅助"保留下来。

---

## 1. 问题澄清：两个流行误解

### 1.1 "微信完全不允许无障碍服务" —— 部分误读

- 微信**官方支持系统读屏**：2025-08 微信支付生活缴费宣布适配 iOS 旁白与安卓 TalkBack，并适配关怀模式字体放大【媒体】[S16]；官方《关怀模式》手册（更新 2026-06-12）提供大字体、听文字消息、安静模式、人声增强、锁屏一键视频通话【官方】[S2]。
- 微信对抗的是**未授权的第三方自动化**：社区（GKD 维护者）称微信对无障碍节点做**应用侧混淆 + 白名单校验**，只有"白名单内的无障碍服务"才能拿到正确节点；GKD #1069 明确 **`UiAutomation` 也解决不了微信节点混淆，对微信的无障碍将失效**【社区，未核实】[S17][S18]。
- 官方从未公开"节点混淆/白名单"规则（**未核实**），但行为侧有公开证据：2025-12 AI 手机助手自动操作微信后账号异常退出，微信回应"可能触发了微信原有的安全风控措施"【媒体】[S12][S13]；2026-06 渐冻症患者使用眼控软件（无障碍类辅助）被机器误判为"外挂"，账号频繁锁定直至彻底封禁，腾讯客服称可凭证明人工申诉、未来优化对残障辅助工具的识别规则【媒体】[S14]。
- **推论**：微信（及其他强风控 App）会**识别并处罚"无障碍自动化"**，且连正规辅助工具都可能误伤；用户本人设备、用户本人授权都**不能豁免**微信协议与平台风控。

### 1.2 "弹验证码" —— 不全是同一个原因

"会弹验证码"至少有三类来源，需要按具体 App 区分（本报告按类别覆盖）：

1. **风控检测到自动化/无障碍环境**（最符合用户描述）：应用侧读取无障碍服务列表、屏幕共享/录屏状态、调试/注入环境等，触发滑块/点选/短信/设备验证；
2. **正常登录/新设备/异地/频控**：与是否使用无障碍无关的常规风控；
3. **平台安全策略**：银行/支付类对录屏、屏幕共享、"AI 操作手机"直接弹强提示或退出（2025-12 农行/建行等实测报道）【媒体】[S12][S13]。

因此本报告的验证码结论按"最坏情况"写：**只要使用无障碍自动化，就必须假设外部风控会识别**。

---

## 2. 验证码/风控线：成因、边界与合规解

> 详细分报告：`.local/research/2026-10-25-a11y-blockers/02-captcha.md`（23 条来源，法源/官方文档已抓取核对）

### 2.1 为什么会被识别（证据分级）

| 信号 | 机制 | 等级 |
|---|---|---|
| 无障碍服务可被公开枚举 | 任意 App 可用 `AccessibilityManager.getEnabledAccessibilityServiceList()` / `isEnabled()` 读取已启用的无障碍服务（包名、能力）；`packageNames` 限制**不会**让服务对其他 App 隐身 | 【官方】[S5] +【项目实测】E1 |
| `isAccessibilityTool=false` | 非无障碍工具类服务启用后，系统会向用户提示隐私影响；第三方可据此视为可疑 | 【官方】[S3] |
| 侧载 + 敏感权限 | Android 13 起旁路安装 App 的"受限设置"可能拦截用户手动开启无障碍/通知使用权 | 【厂商/社区，K40 未实测】[C1] |
| 屏幕共享/录屏 | 银行 App 明确检测并要求关闭；设备指纹 SDK 把无障碍、远程控制列为风险标签 | 【厂商】山西银行采购公示 [S11]；【媒体】[S12][S13] |
| 设备完整性 | Play Integrity 等完整性判定（root/模拟器/篡改） | 【官方】[S6] |
| 行为轨迹 | 无真实触摸轨迹的点击、无焦点 `setText`、固定节奏；木马 Herodotus 专门加"类人输入层"骗风控，反证节奏/轨迹是被建模特征 | 【媒体】[S13] |
| 账号维度 | 新设备、异常频率、异地、批量 | 【媒体】[S12] |

**诚实说明**：各 App 的检测实现、阈値与黑名单不公开，网上多为猜测（【未核实】）；K40 上经 adb 启用无障碍是**调试路径**，不代表商店分发后的用户路径。

### 2.2 验证码类型与"必然不可行"场景

- 类型：滑块/拼图、点选（文字/图标/字序）、无感评分（reCAPTCHA v3 阈值处置）、短信验证码、人脸/活体、设备/本机号码验证【官方产品文档】。
- **必然不可行**：新设备首登且有活跃无障碍服务；支付/转账/改密/绑卡；批量或高频（抢票、群发）；检测到屏幕共享、录屏、注入、模拟器；**人脸活体**（须本人生物特征）；**验证码"识别"本身**（即对抗风控）。

### 2.3 合规解法分级

| 级别 | 做法 | 判定 |
|---|---|---|
| (a) 官方 API/开放平台 | Autofill 框架（官方跨 App 填表，但只回填**用户自己的数据**）；SMS Retriever（**只能取发给本 App 的短信**，EDITH 不是发码方，不适用）；商家/ISV 开放平台（不覆盖"个人助手操作任意 App"） | 可用范围窄 |
| (b) **人在回路（推荐主路径）** | 检测到验证码/风控页 → **立即停止** → 眼镜 TTS/显示提示"请在手机上完成验证" → 等用户完成 → 确认后继续，超时终止 | **EDITH 应采用** |
| (c) 工程优化（合法侧） | 真实设备/真实账号/用户在场显式发起/低频/不后台批量/不用 root/Xposed/注入/不伪造触摸 | 可做 |
| (c') 对抗侧（越界） | 模拟输入节奏、隐藏/伪装无障碍状态、Xposed 规避检测、伪造指纹 | **不可做**（协议 + 法律风险） |
| (d) 仅测试环境 | Appium/UIAutomator2/Espresso、测试账号、沙箱、mock | 不得进发布包 |
| (e) 打码/OCR/行为模拟 | 《网络安全法》第 27/63 条；刑法 285 条第 3 款（最高检"小黄伞"打码案）；出售手机号+验证码被判帮信罪；微信外挂封号公告 | **明确排除** |

### 2.4 EDITH 的具体决定（建议）

1. **验证码/风控页当一等失败原因**：新增 `CAPTCHA_DETECTED` / `RISK_BLOCKED`（只记类型与计数，不记页面正文，对齐 D53）；**不重试**（重试会加剧风控）。
2. **不申请读短信权限**代读第三方验证码；**不自动采集/回填**通知里的验证码。
3. 自动填回仅在**用户完成验证或明确授权口述**后，用 a11y `ACTION_SET_TEXT` 写入并读回校验（K40 实测 A05：无焦点可写；A08：粘贴需焦点）。
4. 引导用户用系统 Autofill/输入法验证码填充，EDITH 只做前后步骤。

---

## 3. 微信线：外部事实与可行应对

> 详细分报告：`.local/research/2026-10-25-a11y-blockers/01-wechat.md`（25 条来源，含微信协议、开放平台文档、处罚判例）

### 3.1 三层限制（外部风控事实）

| 层 | 证据 | 表现 |
|---|---|---|
| **节点层**：应用侧节点混淆 + 白名单 | GKD #1338/#1069（社区，2026）【社区，未核实】[S17][S18]；EasyClick 论坛称 K40 + 微信 8.0.58 "死活抓不到"【社区】[S19] | 自动化服务读到的节点缺失、乱码或空白；换 Shizuku/UiAutomation/root 也拿不到真实节点 |
| **行为层**：登录环境异常、强制下线、屏幕共享风控 | 2025-12 AI 手机助手自动操作微信 → 账号异常退出，微信回应"可能触发原有风控"；豆包手机助手替用户回微信后被强制下线【媒体】[S12][S13]；农行/建行对录屏/"AI 操作手机"强弹窗【媒体】[S12] | 强制下线、风控弹窗、功能受限 |
| **处罚层**：协议与公告 | 协议 8.2.1.6 禁止第三方软件/插件/外挂/系统"进行自动化操作"；8.2.1.7 禁止规避安全保护措施；8.5.1 可警告→限制→封禁→注销【官方】[S1]；2024-03 外挂打击公告【官方公告/媒体】[S9]；2026-01-30 公告把"辅助服务读取"聊天记录列为违规、把"模拟按键/自动化脚本"列为外挂【媒体转述官方公告】[S10] | 阶梯处罚，用户申诉成本由用户承担 |

### 3.2 官方通路能给什么（全部是"单向/受限"）

| 通路 | 能做什么 | 不能做什么 |
|---|---|---|
| 移动应用 OpenSDK | 分享文本/图片/视频/网页/小程序/文件到会话/朋友圈/收藏；微信登录（OAuth） | 无读取会话、无静默发送；分享须用户在微信内选择并确认；需开放平台注册/签名校验【官方】[S3][S4] |
| 公众号/服务号 | 客服消息（用户先发消息后 48h 内最多 5 条）；模板/订阅通知（未逐页校验） | 不能读私人聊天、不能以用户身份发消息【官方】[S6] |
| 小程序 | 客服消息（48h/5 条；进入客服会话 1 分钟内 2 条） | 无"以用户身份向好友发消息"接口（检索结论，【未核实】）【官方】[S5] |
| 企业微信 | 内部应用消息；客户联系-企业群发 | 群发**不直接发送**，需成员确认；客户每月接收条数有限【官方】[S8] |
| 微信客服 API | 客户主动发消息后 48h 内最多 5 条 | 仅限企微客服会话，不能进普通好友会话【官方】[S7] |

**边界一句话**：官方能力全是"助手/企业 → 微信用户"的单向触达或受限客服会话，**没有"以用户身份操作其个人会话"的授权接口**。

### 3.3 非无障碍通道在微信上的现实可行性

| 通道 | 能做什么 | 外部限制/风险 | 应对类型 |
|---|---|---|---|
| `NotificationListenerService` | 读微信通知 title/text（发送者、摘要）→ 眼镜提醒；不抢占屏幕 | 受通知使用权、微信"显示消息详情"、MIUI 通知策略影响（K40 未实测）；不能回复、不保证完整；属个人信息处理 | **C 非触屏（Conditional-go，POC）** |
| 通知内联回复 `RemoteInput` | Android 原生通知内回复 | 微信原生未见 RemoteInput；社区需 Xposed 模块（NevolutionXposed-fork 覆盖 8.0.72–8.0.79）才能实现【社区项目】 | **D 放弃** |
| 自定义 IME | 在已聚焦输入框提交任意 Unicode | 不能打开会话/定位联系人/点发送；用户须手动切键盘（见 §4）；Shizuku 通道装 APK 受 MIUI 限制（E2） | **B 半自动辅助**（仅"用户在聊天页时"） |
| 剪贴板 + 粘贴 | 辅助用户把文本粘入已打开输入框 | 需用户先聚焦；不能导航；Android 13 有剪贴板隐私提示 | **B 半自动辅助** |
| OpenSDK / `ACTION_SEND` | 把内容分享到用户选定会话 | 单向；需注册审核；不能读 | **A 官方（Go）** |
| `MediaProjection` 截屏+OCR | 读屏内容做理解 | 每次采集需用户确认（Android 13）；微信/银行已把屏幕共享纳入风控【媒体】；不能点击 | **D 放弃** |
| Shizuku / ADB `input` | 点击、读界面 | 中文 `input text` 必失败（E2 实测）；普通用户设备无 Shizuku/ADB；违反协议 8.2.1.6/8.2.1.7 | **D 放弃（产品化）** |
| Root / LSPosed / 伪装类名 | 绕节点混淆/隐藏检测 | 属"规避、破坏安全保护技术措施"（协议 8.2.1.7）；封号与法律风险；K40 非 root 交付 | **D 放弃** |

### 3.4 微信线裁决矩阵（建议）

| 能力 | 裁决 | 理由 |
|---|---|---|
| a11y 全自动读/回微信、自动抢红包、自动群发 | **No-go（明确移出范围）** | 节点混淆 + 行为风控 + 协议禁止 |
| Shizuku/UiAutomation/Root/伪装绕节点 | **No-go** | 应用侧混淆，换通道无解；伪装属规避安全措施 |
| 通知只读 → 眼镜提醒/播报 | **Conditional-go（K40 装微信后 POC）** | 不依赖 a11y、成本最低；受通知权限与微信"消息详情"设置影响，需实测 |
| 通知内联回复 | **No-go** | 微信原生不支持 RemoteInput |
| OpenSDK / `ACTION_SEND` 单向分享 | **Go**（需开放平台注册审核） | 正常应用契约，用户确认 |
| 公众号/小程序/微信客服/企微消息 | **Conditional**（仅"助手→用户"通知渠道） | 48h/5 条、资质与场景限制 |
| 用户在场时辅助输入（剪贴板/IME） | **Conditional（仅辅助）** | 不能导航/发送；需用户手动完成最后一步 |

---

## 4. 平台替代通路：能不能绕过？不能

> 详细分报告：`.local/research/2026-10-25-a11y-blockers/03-platform-alternatives.md`（420 行，15 个官方来源正文核对 + 13 个社区来源）

### 4.1 能力对比（精简）

| 通路 | 读屏(结构) | 点击 | 中文输入 | 读通知 | K40/A13 | 关键限制 |
|---|---|---|---|---|---|---|
| AccessibilityService | ✅ 节点树 | ✅ 语义/坐标 | ✅ `ACTION_SET_TEXT` | 需另配监听 | ✅ | 被第三方风控识别；隐私面最大 |
| Shizuku（shell UID） | 🟡 `uiautomator dump`（2.3 s） | ✅ `input tap/swipe` | ⛔ 非 ASCII 失败（E2） | 🟡 | 🟡 需安装+授权+重启重开 | 不能装 APK；单例 dump；通用 shell = 越权 |
| ADB shell（PC） | 🟡 同上 | ✅ | ⛔ | 🟡 | 🟡 仅开发机 | 需电脑/调试；非用户通路 |
| UiAutomation/uiautomator2/Appium | ✅ 全屏 dump | ✅ | ⛔ ASCII | 🟡 | 🟡 需 adb/instrumentation | **官方定义是测试 API**；默认 suppress 已有 a11y 服务 |
| Root（Magisk/libsu） | ✅ 理论上全 | ✅ | ✅ | ✅ | ⛔ K40 非 root | 完整性校验、银行拒用、合规红线 |
| MediaProjection+OCR | ⛔（只能看像素） | ⛔ | ⛔ | ⛔ | 🟡 未实测 | 每次授权、可感知、`FLAG_SECURE` 拿不到 |
| NotificationListener+RemoteInput | ⛔ | ⛔ | ✅（仅通知回复） | ✅ | ✅ 官方 API | 仅通知内容；隐私面广 |
| 自定义 IME | ⛔ | ⛔ | ✅ `commitText`（未实测） | ⛔ | 🟡 需设默认输入法 | 能看全部输入（含密码）；用户可感知 |
| Intent/DeepLink/Share | ⛔ | 🟡 只能直达 | 🟡 靠 extra | ⛔ | ✅ | 只能到 App 暴露的入口 |

### 4.2 三条硬结论

1. **a11y 对第三方 App 是"公开可枚举"的**：`AccessibilityManager.getEnabledAccessibilityServiceList()` 是公开 API（【官方】[S5]），`packageNames` 限制不会让服务隐身。这是微信类风控能"一眼识别"的根因——**不是 EDITH 的系统缺陷，而是外部安全行为**。
2. **换通道 ≠ 绕过风控**：Shizuku/UiAutomation/root 各自暴露另一组可检测信号（包存在性、adb/调试状态、root/完整性、注入痕迹），而风控核心往往还是**行为模型 + 官方客户端协议校验**；把"被检测到无障碍"换成"被检测到调试/注入环境"并没有消除封号风险。微信节点混淆更是应用侧机制，**换底层通道无解**（社区实测）。
3. **UiAutomation 不是产品通路**：`UiAutomation` 官方定义是**测试用** API（跨应用写测试），由 instrumentation 提供；`uiautomator dump`/Appium 依赖 adb/shell 身份，普通 App 无法在用户设备上生产化使用。它适合当 E2E/验收工具，这也与项目"设备侧人工操作模拟必须走 a11yprobe、禁用 adb shell input 替代凑结论"的纪律一致（E3）。

### 4.3 推荐技术栈（EDITH）

| 档位 | 组成 |
|---|---|
| **默认档** | a11y（用户明示授权，`isAccessibilityTool=false`，可随时停止）做读屏/语义点击/中文 `ACTION_SET_TEXT`；`NotificationListenerService` 读通知；`Intent/DeepLink` 打开页面；验证码/风控页 fail-closed；`takeScreenshot()`+本地 OCR 兜底看像素（若已开 a11y，优先于 MediaProjection） |
| **增强档（高级用户显式开关）** | Shizuku：`uiautomator dump` + `input tap/swipe/keyevent` + ASCII 输入；沿用 E2 §6.3 的窄工具面（只给窄动作，不给通用 shell；不可逆动作不进工具面） |
| **不做档** | 静默开 a11y（`settings put secure enabled_accessibility_services`）、改 appops、root/Magisk/Xposed/LSPosed、伪造 `isAccessibilityTool`、伪装类名/多服务轮换、root 注入、自动过验证码、换通道重试对抗风控 |

---

## 5. 对 EDITH 的落地建议（结合仓库现状）

### 5.1 已有能力映射

- **无障碍五件套已产品化**：`read_screen / tap / long_press / scroll / input_text`（L1）+ `device`（observe/act/screenshot/wait/status）——微信线不建议使用（节点混淆），但**其他正常 App 的 A 类场景**继续用。
- **通知内容读取已存在**：`NotificationCategoryService`（D179 允许读 title/text/subText/bigText/action 标签，只进内存不落盘）——正是"微信消息→眼镜提醒"的现成底座，缺的是**微信场景的白名单/字段裁剪/POC 实测**与**是否可回复**的验证。
- **Intent 通道已存在**：`open_app / open_url / open_settings / set_alarm / compose_note / dial`——"能直达就不模拟点击"应作为第一优先。
- **Shizuku 通道已收窄**：生产只有 5 个 L2–L4 动作，不注入输入/不读界面（E2 §7）——**不要**因为本调研把 `input`/`uiautomator` 加进默认工具面。
- **验证码处理目前无专门逻辑**：需要新增失败原因与"停手交还用户"的编排。

### 5.2 建议动作（按优先级）

- **P0（低风险、立即可做）**
  1. 新增"验证码/风控页"识别 → 立即停止 → 眼镜 TTS/显示提示用户接管 → 超时终止；新增 `CAPTCHA_DETECTED` / `RISK_BLOCKED`（只记类型计数，不记正文）。
  2. 验收口径补充：**遇到风控/不可用即停并明示**；禁止"换通道重试直到成功"；禁止用 `input`/root/静默开 a11y 补齐结论。
- **P1（需设备 POC，K40 装微信后）**
  3. 微信通知 POC：用 `NotificationListenerService` 读通知字段，验证"是否含发送者/摘要/是否被微信隐藏/是否有 action/是否有 RemoteInput"，产出 `NotMeasured`→实测结论；若可读，接"眼镜提醒 + 用户确认（打开微信由用户操作）"。
  4. `ACTION_SEND` 分享到微信可用性验证（文本/图片→用户选择会话）。
  5. 用户在场时辅助输入：剪贴板/`ACTION_SET_TEXT` 到用户已打开的聊天页（仅当用户明确要求，不自动发送）。
- **P2（需产品/合规决策）**
  6. 评估接入微信开放平台移动应用（OpenSDK 分享）——需公司主体注册/审核，且能力仅单向。
  7. 若出海/上架 Google Play：**不要**以"LLM 自主规划并操作其他 App"的形态提交 a11y 声明（Play 政策明文禁止自主发起/规划/执行操作或决策；deterministic 规则脚本除外）【官方】[S1]；国内商店细则需逐家核实（未核实）。
  8. 把"微信全自动读/回"正式记录为**不在范围**（建议业主裁决，避免后续反复投入）。

### 5.3 建议写入验收口径的三条

1. 目标 App 风控/不可用 = **正常失败态**，必须停手并明示用户，不得换通道重试。
2. 验证码/风控页 = **一等失败原因**，计数只记类型，不记页面正文（D53）。
3. 任何"静默开启敏感权限/修改系统开关/隐藏检测"的实现一律不进入产品与工具面（E2 §6 已列）。

---

## 6. 合规与风险要点

| 主题 | 要点 | 依据 |
|---|---|---|
| 微信协议 | 8.2.1.6 禁止第三方软件/插件/外挂/系统"进行自动化操作"；8.2.1.7 禁止规避安全保护措施；8.5.1 阶梯处罚 | 【官方】[S1] |
| 反不正当竞争 | 2026-07 成都某公司 "AutoCMD+" 用无障碍 + 通知使用权对抖音/微信/QQ 自动化，被四川天府新区市场监管局按《反不正当竞争法》罚款 3 万元；同期法院行为保全确立"平台授权 + 用户自主授权"双重授权 | 【法源/媒体】[S20] |
| 网络安全/刑法 | 《网络安全法》第 27/63 条；刑法 285 条第 3 款（最高检"小黄伞"打码案）；出售手机号+验证码判帮信罪 | 【法源】[S7][S8] |
| Google Play | a11y API 不得用于"自主发起、规划、执行操作或决策"（deterministic 规则脚本除外）；禁止规避平台安全/隐私控制与通知；非无障碍工具须声明+醒目披露+同意 | 【官方】[S1] |
| 国内商店 | 未检索到统一权威条款；"敏感权限用途明示 + 用户同意"是普遍要求（**未核实**） | 【未核实】 |
| 个人信息 | 通知/屏幕内容属个人信息：告知同意、最小化、本地处理、不落正文；对齐 D53 | 【法源/PIPL，未逐条核对】[S22] |

---

## 7. 未核实与待实测清单（不得读成已验证）

| # | 项 | 状态 | 补法 |
|---|---|---|---|
| 1 | 微信在 K40 上的实际版本、通知字段可读性、通知 action/RemoteInput、`ACTION_SEND` 可用性 | **未实测**（K40 未装微信） | 装微信后用 `edith.phone.a11yprobe` + 通知监听做只读 POC；**不做触发封号实验** |
| 2 | 微信 a11y 树是否混淆/空白（社区说法） | **未核实** | 同上，只读观察，不主动操作 |
| 3 | K40/MIUI V816 是否弹 Android 13 "受限设置"（sideload 开 a11y） | **未实测** | 走一次真实用户路径验证 |
| 4 | 各 App 风控信号组合与阈值 | **不公开/未核实** | 不做逆向；以"失败即退"设计规避 |
| 5 | 国内应用商店对"无障碍自动化"的审核细则 | **未核实** | 逐家查开放平台规范/问审核 |
| 6 | `FLAG_INPUT_METHOD_EDITOR`(API 33) 能否免切默认输入法 commitText 中文 | **未实测** | 单独 a11y 探针验证 |
| 7 | 自定义 IME 中文注入端到端 | **未实测** | 需用户批准"切默认输入法"后测 |
| 8 | Play 政策页 2026-10-25 版本；Lead 复核时内部 fetch 端点 404，仅经搜索摘要与调研员抓取原文核对 | **部分未复核** | 上线前人工打开原文复核 |

---

## 8. 来源（精选；完整清单见三份分报告）

**微信官方**
- [S1] 腾讯微信软件许可及服务协议 — https://weixin.qq.com/cgi-bin/readtemplate?lang=zh_CN&t=weixin_agreement&s=default
- [S2] 微信支付·关怀模式用户手册 — https://pay.weixin.qq.com/doc/user/4021838466
- [S3] 微信开放平台·移动应用·Android 分享与收藏 — https://developers.weixin.qq.com/doc/oplatform/Mobile_App/Share_and_Favorites/Android.html
- [S4] 微信开放平台·微信登录开发指南 — https://developers.weixin.qq.com/doc/oplatform/Mobile_App/WeChat_Login/Development_Guide.html
- [S5] 小程序·客服消息·发送消息 — https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/customer-message/send.html
- [S6] 公众号·客服消息 — https://developers.weixin.qq.com/doc/subscription/api/customer/message/api_sendcustommessage
- [S7] 微信客服·发送消息 — https://developer.work.weixin.qq.com/document/path/96427
- [S8] 企业微信·创建企业群发 — https://developer.work.weixin.qq.com/document/path/92135
- [S9] 微信安全中心 2024-03 外挂打击公告（光明网转述）— https://m.gmw.cn/2024-03/28/content_1303698258.htm
- [S10] 2026-01-30 公告（快科技转述）— https://m.mydrivers.com/newsview/1101318.html

**Android / Google**
- [S3'] `AccessibilityServiceInfo` — https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
- [S5] `AccessibilityManager` — https://developer.android.com/reference/android/view/accessibility/AccessibilityManager
- [S6-UI] `UiAutomation`（测试 API）— https://developer.android.com/reference/android/app/UiAutomation
- [S10-UI] `NotificationListenerService` — https://developer.android.com/reference/android/service/notification/NotificationListenerService
- [S11-UI] `RemoteInput` — https://developer.android.com/reference/android/app/RemoteInput
- [S12-UI] `InputConnection.commitText` — https://developer.android.com/reference/android/view/inputmethod/InputConnection
- [S13-UI] Android 10 剪贴板隐私变更 — https://developer.android.com/about/versions/10/privacy/changes
- [S1-Play] Google Play：Use of the AccessibilityService API — https://support.google.com/googleplay/android-developer/answer/10964491
- [S2-Play] Google Play：敏感信息访问权限与 API — https://support.google.com/googleplay/android-developer/answer/16558241

**媒体/判例/厂商**
- [S11] 山西银行：移动端反电诈远程控制检测采购公示（2025-10-27）— https://www.shxibank.com/sxyh/jrsc/xwzx/zbggx/psgg/2025102709284414624/index.shtml
- [S12] 中国城市报：淘宝/微信/农行/建行拒绝 AI 外挂手机访问（2025-12-04）— https://www.zgcsb.com/news/jingJi/2025-12/04/ma_637594.html
- [S13] 财联社（九方转载）：AI"外挂"引发入口之争（2025-12-04）— https://www.9fzt.com/detail/sh_000050_8_8f215de607d849e6030fd7bf56308ddc.html
- [S14] 济南时报：渐冻症患者眼控软件用微信被误判"外挂"（2026-06-26）— http://news.e23.cn/wanxiang/2026-06-26/2026062600128.html
- [S16] 凤凰科技：微信支付生活缴费适配 TalkBack（2025-08）— https://tech.ifeng.com/c/8lbRVgI8T6f
- [S20] 上游新闻：无障碍权限自动化操作抖音/微信遭罚（2026-07-28）— https://www.cqcb.com/news/56/2026-07-28/6188959.html
- [S7] 《网络安全法》全文 — https://www.beijing.gov.cn/zhengce/zhengcefagui/201905/t20190522_59746.html
- [S8] 最高检：撞库打码牟利行为如何定性处理 — https://www.spp.gov.cn/spp/llyj/201808/t20180803_387127.shtml

**社区（低可信，需谨慎引用）**
- [S17] gkd-kit Discussion #1338（微信节点混淆/白名单）— https://github.com/orgs/gkd-kit/discussions/1338
- [S18] gkd-kit Discussion #1069（UiAutomation 无法解决微信节点混淆）— https://github.com/orgs/gkd-kit/discussions/1069
- [S19] 老冷论坛 EasyClick 讨论（微信节点乱码，K40/8.0.58 跟帖）— http://bbs.laoleng.vip/forum.php?mod=viewthread&action=printable&tid=1411
- [C1] Esper：Android 13 sideload 受限设置 — https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis

**项目内证据**
- [E1] [无障碍通道能力实测（K40/Android 13）](../project/evidence/2026-10-16-a11y-capability-survey.md)
- [E2] [shell UID（Shizuku 通道）能力实测](../project/evidence/2026-10-16-shizuku-shell-capability-survey.md)
- [E3] `AGENTS.md`（设备侧纪律：a11yprobe 唯一模拟通道；D53 隐私口径；D179 通知内容可感知）

---

## 9. 分报告与调研方法

- 微信线：[01-wechat.md](../../.local/research/2026-10-25-a11y-blockers/01-wechat.md)（`.local/` 为 gitignored 工作笔记；如需入库可迁入 `docs/pre-research/`）
- 验证码线：[02-captcha.md](../../.local/research/2026-10-25-a11y-blockers/02-captcha.md)
- 平台通路线：[03-platform-alternatives.md](../../.local/research/2026-10-25-a11y-blockers/03-platform-alternatives.md)
- 团队分工：wechat-research（task-1）、captcha-research（task-2）、platform-research（task-3）；Lead 负责本地能力盘点、交叉核验与合稿。
- 方法限制：全部为桌面调研（web_search/web_fetch；内部 fetch 端点 2026-10-25 起持续 404，部分页面经调研员 HTTP GET 抓取正文核对）；**无设备新测**；微信未安装；各 App 风控实现不公开。
- **本报告不改变任何既有判词/验收行/版本状态**；是否把"微信全自动"正式移出范围、是否接入 OpenSDK，属于业主裁决与后续 spec，不在本报告内擅自决定。

---

## 10. 追问（2026-10-25）：root 能不能解决？

**结论：不能。root 不改变微信"应用侧节点混淆 + 白名单"的机制，只会把问题从"被检测"换成"规避检测的军备竞赛"，并引入更高的设备、账号、合规代价。维持 No-go。**

1. **root 本身不给节点。** 社区实测（2026-09 技术博客【社区，未核实】）称微信 8.0.52+ 对普通第三方无障碍返回混淆/空节点树，`uiautomator dump` 对微信任何界面只返回空根节点；已知的绕过方式是"伪装成白名单/系统无障碍服务类名"或 hook 微信进程——前者属**规避安全保护措施**（协议 8.2.1.7），后者需与被 hook 客户端的完整性/反注入对抗。root 只是让这些规避"更容易实现"，不是"让微信兼容"。
2. **root 会让其他 App 的风控更严，不是更松。** 解锁 bootloader/root 会被 Play Integrity 类完整性校验识别；2025-05 起新版 Play Integrity 大面积启用后，银行/支付类 App 对 root/刷机设备的拒绝更普遍（[快科技报道](https://m.mydrivers.com/newsview/1050454.html)）。原本"弹验证码"的 App，root 后可能直接拒服务。
3. **账号风险不因 root 消失。** 微信侧还有账号信誉、行为模型、服务端风控；Magisk DenyList/Zygisk/隐藏模块是针对特定版本的动态对抗（社区 runbook 也只敢说"三件套"对某个版本有效），版本升级即可能失效；一旦被识别，处罚仍是封号，且"规避检测"本身是协议明示违规。
4. **对交付设备不可接受。** K40 是**非 root 验收基线**（D46/D65），且是**共享验收设备**（另一条工作线在用）；root 需解锁 bootloader（清数据、掉 OTA、完整性降级），会破坏现有证据与仪器状态，未获业主批准不应进行。
5. **纯研究目的的轻量替代**：不需要 root。装微信后用非 root 的 `edith.phone.a11yprobe` **只读**观察节点树是否为空/混淆即可验证"应用侧"判断；但账号仍有被风控风险，应使用独立设备 + 可牺牲账号，且**不做**主动触发封号的实验。
6. **产品化不可行**：不能要求用户 root；root 打开的是"越权读取/规避检测"的口子，与项目 D53 隐私口径、Play 政策（禁止规避平台安全控制）及 2026-07 AutoCMD+ 判例冲突。

**建议**：不把 root 列入任何产品/验收路径；产品路线维持 §5 的"通知只读 + Intent/分享 + 用户确认 + 验证码交还用户"。
