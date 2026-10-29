**需求决策记录，未批准；不是 spec，不是验收基线。**

# D8 0.25 需求决策总表（2026-10-19）

> 用途：把业主已口头确定的决策集中记录，作为 spec 的输入。每条注明来源与状态；未定的列在 §3。

## 1. 已确定

| # | 决策 | 内容 | 来源 |
|---|---|---|---|
| R-01 | **版本与范围** | 0.25 做 Agent 能力演进（缺陷修复 → DeviceControl/薄工具 → Loop/上下文/多模态）；锁屏/息屏解锁**移出 0.25**，下版本做 | 业主 2026-10-19；D7 |
| R-02 | **验收锚点** | 5–8 条固定任务语料（D4 草案，T5 解锁预检移出）；整组通过率 + 每条模型调用次数 + 是否卡死；跨 App 复合任务必过 | 业主 2026-10-19（E） |
| R-03 | **确认闸门** | 只覆盖数据不可逆（卸载/清数据/覆盖删除）与花钱；**外呼不问**；歧义澄清始终保留 | 业主 2026-10-19（B，外呼除外） |
| R-04 | **多模态** | 完整多模态：图片进历史；**EDITH 只允许使用支持 IMAGE 输入的多模态 LLM**；**AUDIO 本版不做**（TEXT+IMAGE） | 业主 2026-10-19（C + 本次补充） |
| R-05 | **协议** | **Responses 为权威/参考协议**；Chat Completions 同时支持；两者都要支持图片与工具历史；**Chat Completions 的图片只能放 user 消息**（工具结果图片用后续 user 图片消息的兼容 workaround）；档案必须声明 IMAGE 输入 | 业主 2026-10-19（C + Responses 为准）+ DeepSeek Vision 文档 |
| R-06 | **视觉参与策略** | 参考 X-OmniClaw：结构树优先；a11y 空/低置信/SurfaceView/验证失败才走视觉兜底；不是每步截图 | 业主 2026-10-19 |
| R-07 | **工具面** | 6 工具：`device` / `shell` / `open` / `dial` / `notify_user` / `load_skill`；删 `compose_note`；不设 `intent` 工具（`shell am` 承接）；`read_screen`/a11y 四件并入 `device`；`open_app`+`launch_app`→`open`；`list_apps` 删除；Shizuku 特权→`shell`；`set_brightness`→`shell settings`；`system_action`→`device.act`/`shell input keyevent` | 业主 2026-10-19（薄桥接）+ D2 |
| R-08 | **薄桥接原则** | 系统只做桥接与结果响应；能裸露原能力就不封装。必要封装只有 `device`（ref/revision/自动观察）与 `open`（名称→包名，AC-13） | 业主 2026-10-19 |
| R-09 | **D84 边界** | 模型不知道通道、不暴露 `via`/坐标参数；结果里可带 `channel` 供诊断；坐标只做内部/视觉兜底 | 继承 D84；D1/D2 |
| R-10 | **解锁（下版本）** | 0.25 不做；下版本正确方式 = **工具执行时触发解锁 + 防误触**，不是会话常驻解锁；0.25 仅加任务期间 `FLAG_KEEP_SCREEN_ON` 临时保护 | 业主 2026-10-19；D7 |
| R-11 | **阶段顺序** | 1) 修既有 P0 缺陷；2) DeviceControl + 薄工具 + 自动观察；3) Loop + 上下文 + 多模态；4) 语料验收 | 业主 2026-10-19（方向 1 的 Agent 部分） |
| R-12 | **契约倾向（待批）** | 不改 `plugin-api.ActionResult(detail:String)`；在 `agent-runtime` 内建结构化 envelope，由适配器投影（D1 方案），图片以 artifact 引用 | 主 Agent 建议；待 spec 批准 |
| R-13 | **上下文预算（待批）** | 保持 D81 统一窗口预算；在 D97 前插入类型感知省略（旧树→header、旧图→摘要、最后工具结果豁免、超长工具结果头尾）；不引入 per-category 预算 | D3 建议；待 spec 批准 |
| R-14 | **图片参数（待测）** | 历史保留最近 K=3 张、长边 768 为初值；DeepSeek 官方口径为按尺寸折算、**单图上限约 1024 tokens**（非 OpenAI 28px tile 公式），最终按真实档案实测校准 | D2/D3 建议 + DeepSeek Vision 文档；待实测 |
| R-15 | **多步任务 UX** | 关键节点：开始一句、每次工具一行（眼镜面板）、确认/失败/完成时 TTS 一句；**不逐条念**；沿用 STOP 立即取消 | 业主 2026-10-19（B） |
| R-16 | **语料清单** | 7 条：T1 音乐（M2 复测）、T2 亮度、T3 短信草稿、T4 应用目录+打开设置（AC-13/16 复测）、T6 Canvas 视觉兜底、T7 多步组合、T8 歧义/未安装/不可逆确认负例；T5 解锁预检移出；初始状态改为**已解锁 + 亮屏** | 业主 2026-10-19（A） |
| R-17 | **会话模型** | 每条语料**冷启动 App + 新会话**，隔离上下文；常驻会话不在本语料验收 | 业主 2026-10-19（A 默认，未反对） |
| R-18 | **shell 风险闸门** | 分类 + fail-closed：只读放行；不可逆（`pm clear`/`pm uninstall`/`rm`/`mv`/`dd`/`format`/`content delete` 等）确认；复合命令逐段分类；未知/无法解析/混淆语法要求确认；可逆操作不确认 | 业主 2026-10-19（A） |
| R-19 | **验收模型** | DeepSeek `deepseek-flash`（支持图片）；**Responses 为权威协议**；Chat Completions 兼容。官方文档：Responses 的 `input_image` 可出现在 user/developer 消息**以及 `function_call_output`/`custom_tool_call_output` 的输出里**；Chat Completions 图片仅 user 消息；单图约 ≤1024 tokens | 业主 2026-10-19（确认 DeepSeek 多模态）+ DeepSeek Vision/Responses 文档 |
| R-20 | **确认 UX** | 双通道：眼镜 TTS 念摘要 + 手机弹窗；手机点按或眼镜按键确认/拒绝；确认键映射在 spec 定（候选 ENTER=允许、BACK=拒绝、超时=拒绝） | 业主 2026-10-19（A） |

## 2. 已知既有缺陷（0.25 必须先修）

1. Chat Completions 历史只编码 `Text`，`ToolCall/ToolOutput` 被丢弃（`OpenAiChatCompletionsAdapter.kt:186`，已核实）。
2. 生产 store 只 `appendUser`/`appendAssistant`，工具轮/观察不进会话（`SmartAssistantPlugin.kt:2085`，已核实）。
3. `LaunchAppExecutor` 对 `Ambiguous` 返回 `done(...)`（`LaunchAppExecutor.kt:99`，已核实）。
4. `ModelClientGateway` 丢弃 `Terminal.usage`（D3 报告，待复核）。
5. `ContextBudgeter` 拒绝非文本、`ToolOutput` 只有 String、`MediaSource` 无图片解析（D3 报告，待复核）。

## 3. 未定 / 待澄清

1. **模型档案迁移**：现有纯文本档案（含默认 Responses）如何处理；debug QwenOmni（TEXT+AUDIO）如何迁移、是否删除该调试档案（spec 定）。
2. **生产 a11y / Shizuku 的启用与验证**：设备当前只启用探针 a11y、Shizuku 无 binder（D4/D5/D6），验收前必须恢复并验证。
3. **确认闸门的残余风险**：UI 层点击（App 内「删除 / 支付」按钮）无法在工具层分类；`shell` 之外的破坏性 UI 操作不在确认范围内（spec 写明）。
4. **单任务预算**：wall / 模型调用次数 / 工具步数上限，D4 暂定 `capCalls=14`、`wall=120s`；需 dry run 校准后冻结（spec/计划）。
5. **确认键映射**：眼镜 ENTER / BACK 与现有 pin/BACK 语义的关系；确认态是否暂停 pin（spec 定）。

## 4. 证据索引

- 预研：`docs/pre-research/2026-10-19-edith-agent-evolution-pre-research.md`
- 设计草案：`D1-device-kernel.md`、`D2-tool-surface.md`、`D3-loop-context.md`、`D4-acceptance-corpus.md`
- 锁屏调查：`D0a-unlock-history.md`、`D0b-unlock-paths.md`、`D5-wakelock-spike.md`、`D6-keyguard-spike.md`、`D7-scope-decision-defer-lock-screen.md`
- 协议调查：`D9-image-wire-shapes.md`（待产出）

## 5. 修订

- 2026-10-19：初稿，汇总业主已确定的需求与待澄清项。
