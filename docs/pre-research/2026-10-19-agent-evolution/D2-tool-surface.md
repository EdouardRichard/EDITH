# D2：0.25 工具面重构设计（device / shell / open / notify_user / load_skill / dial）
**草案，未批准；不是 spec。**

- 基线：`main` HEAD `1e0cc41`（0.24 已验收闭环；未 push）。本文件只做只读调研与设计草案，不改产品代码、不改 `DECISIONS.md` / `CONTRACTS.md` / `ACCEPTANCE.md` / `STATUS.md`。
- 范围：工具清单、JSON schema、21→6 映射、raw/wrapped 判定、结果 envelope、图片分片、确认策略、档案门控、AC-13 应用目录。**device 内核细节见 D1；loop/上下文压缩策略见 D3**；本文只写工具面必须依赖的契约与口径。
- 证据标记：`[代码]` 已读源码；`[实测]` 引用预研报告里的设备实测读数（不是本轮新测）；`[推断]` 由代码/协议推导；`[未测]` 本轮没有设备或供应商协议证据。
- 业主已定前提（不再论证）：最终工具面 `device / shell / open / notify_user / load_skill` + `dial`；删 `compose_note`；不设 intent 工具；薄桥接；完整多模态；确认只覆盖数据不可逆与花钱；外呼不确认；D84 不让模型知道通道、不暴露坐标；不做 on-demand 工具路由；D117 证据纪律不变。

---

## 0. 结论摘要

1. **6 个工具、4 个设备执行后端**：`device`（a11y 包装）、`shell`（裸 shell）、`open`（应用名→包名，包装）、`dial`（裸 Intent），加上插件侧的 `notify_user` / `load_skill`。原 21 个动作工具全部有明确去向，见 §2。
2. **只有 `device` 与 `open` 需要封装**：`device` 封装 a11y 原生形状（index/text 不稳定、无 ref/revision、动作后要重新读屏）；`open` 封装 AC-13 的确定性解析与歧义返回。`shell`、`dial` 直接暴露原能力；`notify_user` / `load_skill` 的现有薄执行器保留。
3. **结果统一为结构化 envelope**：`ActionResult` 增加可选 `dataJson` / `code` / `applied` / `channel` / `images`，`detail` 继续作为一行人类摘要；`ToolRegistry` 负责组装 envelope。不用“把 JSON 塞进 detail”的方案（会被现有 `Refused:` 前缀与 `[applied=]` 前缀破坏，见 §4）。
4. **图片只以 artifact 引用进结果与历史**；`ModelHistoryItem.ToolOutput` 增加 `images`，`Segment` 增加 `images`（否则 `toSegment` 回环会丢图），编码时才由 `MediaSource.resolveImage` 读盘并 base64。请求装配时最多保留最近 **K=3** 张（D3 的 ⓪b），超预算的旧图替换为可见占位。
5. **确认策略不再按 risk 等级机械确认**：`dial`、`device`、`open` 永不确认；`shell` 由命令分类器动态判定，只有不可逆数据写入与花钱（以及无法判定的命令）要求确认；其余靠用户 `maxLevel` 天花板。
6. **档案门控是硬门**：智能助手只能使用 `inputs` 含 `IMAGE` 且当前 revision 有 `IMAGE_REQUEST=Verified` 的档案；纯文本档案给明确提示，不静默降级。debug `QwenOmni`（`TEXT+AUDIO`）需要显式迁移，不会自动改写。
7. **AC-13 的解析链改为 shell**：`pm list packages` → `resolve-activity` / launcher 枚举 → label 尽力解析 → `am start -W`；0 命中 `APP_NOT_FOUND`，多命中 `APP_AMBIGUOUS` 返回候选。当前 `launch_app` 把歧义当 `Done` 返回（`LaunchAppExecutor.kt:97-100`），是必须修掉的缺陷。
8. **本轮没有设备实测**：应用目录修复、图片历史、shell 分类器、profile 迁移都只有代码/协议推断；`[未测]` 清单见 §10。

---

## 1. 最终工具清单

### 1.1 总表

| 工具 | 用途 | 风险 | 确认 | 计入步数 | 能力声明 | 通道（仅结果诊断） |
|---|---|---|---|---|---|---|
| `device` | a11y 语义观察 + 节点/全局动作 + 截图 + 等待 + 状态 | L1 | 永不 | 是 | `A11Y`（必需）；`GESTURE`、`SCREENSHOT`（可选子能力） | `accessibility` |
| `shell` | 原始 shell 命令；承接 `am start/broadcast/service`、`pm`、`settings`、`cmd`、`dumpsys` 等 | L1 底 + 动态评估（可到 L4） | 按命令分类 | 是 | `SHELL`（必需）；`ROOT` 本版不声明 | `shizuku` |
| `open` | 应用名/包名 → 包名 + launcher component，启动并验证前台 | L1 | 永不 | 是 | `SHELL`（必需） | `shizuku` |
| `dial` | 直接外呼（`ACTION_CALL` + `CALL_PHONE`） | L2 | 永不 | 是 | `CALL`（必需） | `intent` |
| `notify_user` | 模型到用户的唯一输出通道（显示/语音/静默） | L0 | 永不 | 是 | `LOCAL_OUTPUT` | 无 |
| `load_skill` | 读取 SKILL 正文（正文进 system slot，不进结果） | L0 | 永不 | 否（沿用 D-9） | `LOCAL_READ` | 无 |

说明：

- `RiskLevel` 仍是用户天花板的输入；`requiresConfirmation` 仍是插件“强制提问”的声明。新增 `confirmationMode`（`BY_RISK` / `NEVER` / `ALWAYS` / `CLASSIFIED`）把“风险等级”和“是否提问”解耦。没有这一层，`dial` 的 L2 会被现有 `WhitelistActionPolicy` 无条件要求确认（`WhitelistActionPolicy.kt:41-56`），与业主前提冲突。
- `device` / `shell` 的“是否计入步数”受现有契约限制：`ActionDescriptor.countsTowardStepLimit` 是**工具级**字段（`Actions.kt:38-50`），不能按 `action` 细分。因此 `device.observe` 也会计一步；不要为它单独做免计步，除非同时改 loop 契约（见 §9 待裁决 5）。
- `shell` 的 `risk = L1` 是**底值**，不是最大能力声明；动态评估返回的 assessed risk 参与天花板判断（§6）。若业主不接受“描述符风险可被动态提升”，则 `shell` 只能是 L4 且默认 `maxLevel=L1` 下不可用，见 §9 待裁决 1。
- 本版**不声明 root**：`RootProbe` 明确“不声明任何动作，只回答探针结果”（`RootProbe.kt:33-41,61-62`），K40 上 `su` 也不可用。`shell` 只有 Shizuku shell UID；未来加 `as_root` 时必须先有真实 root 通道与独立风险审查。

### 1.2 `device`

**用途**：把 a11y 的“节点树 + 节点动作”变成模型可稳定寻址的形状：ref、revision、动作后自动观察、diff。它是唯一允许模型通过语义节点操作其他 App 的工具；不是通用 shell 的别名。

**JSON schema**（`additionalProperties:false`；`required` 只有 `action`，每个 action 的必填项由执行器严格校验，不用 `oneOf`/`if-then` 以免供应商 schema 支持差异）：

```json
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["observe", "act", "screenshot", "wait", "status"],
      "description": "observe=读取当前界面；act=执行一个语义动作并自动观察；screenshot=截屏并附 artifact；wait=等待界面条件；status=前台/IME/旋转等状态"
    },
    "kind": {
      "type": "string",
      "enum": ["tap", "long_press", "double_tap", "swipe", "scroll", "scroll_to", "type", "press_key", "back", "home", "recents", "notifications"],
      "description": "仅 action=act。tap/long_press/double_tap 必须给 ref；swipe/scroll 必须给 direction；scroll_to 必须给 text；type 必须给 text；press_key 必须给 key；back/home/recents/notifications 不需要额外参数"
    },
    "ref": {
      "type": "string",
      "description": "上次 observe/act 结果里的节点引用，如 #n16；只在同一 revision 内有效"
    },
    "revision": {
      "type": "integer",
      "minimum": 0,
      "description": "可选。模型带上它来声明 ref 来自哪次观察；不匹配返回 NODE_STALE，不按文本猜"
    },
    "text": {
      "type": "string",
      "description": "type 的输入文本（支持 Unicode/换行）；scroll_to 要找到的可见文本；wait_for=text 时要等待的文本"
    },
    "direction": {
      "type": "string",
      "enum": ["up", "down", "left", "right"],
      "description": "swipe/scroll 的方向"
    },
    "times": {
      "type": "integer",
      "minimum": 1,
      "maximum": 10,
      "default": 1,
      "description": "swipe/scroll 重复次数；每次都是一次记录动作"
    },
    "duration_ms": {
      "type": "integer",
      "minimum": 50,
      "maximum": 3000,
      "default": 300,
      "description": "手势时长；tap 类动作忽略"
    },
    "key": {
      "type": "string",
      "enum": ["BACK", "HOME", "RECENTS", "NOTIFICATIONS", "ENTER", "DEL", "TAB", "ESCAPE", "VOLUME_UP", "VOLUME_DOWN"],
      "description": "press_key 的按键名；back/home/recents/notifications 也有各自的 kind，key 是更通用的入口"
    },
    "replace": {
      "type": "boolean",
      "default": false,
      "description": "type 时先清空目标输入框再写；默认在现有文本后追加"
    },
    "wait_for": {
      "type": "string",
      "enum": ["idle", "foreground", "text", "change"],
      "description": "wait 的条件。idle=界面静默；foreground=指定 package 到前台；text=文本出现；change=自当前 revision 起有变化"
    },
    "package": {
      "type": "string",
      "description": "wait_for=foreground 时等待的包名"
    },
    "timeout_ms": {
      "type": "integer",
      "minimum": 100,
      "maximum": 30000,
      "default": 5000,
      "description": "wait 的上限；超时返回 VERIFY_TIMEOUT，不抛异常"
    },
    "filter": {
      "type": "string",
      "enum": ["interactive", "text", "all"],
      "default": "interactive",
      "description": "observe 的过滤；interactive 最省 token，all 用于诊断"
    },
    "window": {
      "type": "string",
      "enum": ["active", "all"],
      "default": "active",
      "description": "observe 的窗口范围；all 包含 IME/悬浮窗，需要 flagRetrieveInteractiveWindows"
    },
    "include_image": {
      "type": "boolean",
      "default": false,
      "description": "observe 时是否附带当前截图；screenshot action 总是附带"
    }
  },
  "required": ["action"],
  "additionalProperties": false
}
```

**每个 action 的语义与风险**：

| action | 行为 | 风险 | 自动观察 | 能力 |
|---|---|---|---|---|
| `observe` | 首次全量、之后按 revision/diff；节点行含 ref、role、text、bounds、states、actions | L1 | 不适用 | `A11Y` |
| `act` | 节点动作优先（`ACTION_CLICK` / `ACTION_LONG_CLICK` / `ACTION_SET_TEXT` / `ACTION_SCROLL_*`）；无节点能力时按 kind 走手势/全局动作 | L1 | 是，结果里带 diff | `A11Y`；`swipe`/`scroll` 需 `GESTURE` |
| `screenshot` | 截当前屏，缩到长边 ≤768，写 artifact，结果只带引用 | L1 | 否 | `SCREENSHOT`（不支持时 `UNSUPPORTED`，由模型改用 shell `screencap`） |
| `wait` | 事件驱动 + 有界轮询；命中/超时都返回当前观察 | L1 | 返回最终观察 | `A11Y` |
| `status` | 前台包/Activity、IME、旋转、窗口数 | L1 | 否 | `A11Y` |

**约束**：

- **不暴露坐标**：没有 `point` / `x,y` / `via` 参数（D84）。`swipe` 只给方向与次数；`tap` 只能按 ref。若自定义绘制界面没有可点击节点，模型只能截图说明并请求用户，不得让 host 猜坐标。
- **ref/revision**：ref 形如 `#n16`；`revision` 每次 observe/act 递增。ref 不匹配返回 `NODE_STALE`，结果里带当前 `revision`，模型重新 observe，不按文本自动重定位（重定位最多是 D1 内核能力，不在工具契约里承诺）。
- **动作后自动观察**：所有 mutating `act` 都按 kind settle 后重新观察，diff 放进 `data.observation`；失败动作也回当前观察（e2e 的“动作结果=下一次观察”，预研 §4.2/§8.3）。
- **`truncated`**：为 true 时结果不得声称 removed；只报告 added/updated 中确定的部分。
- **通道**：结果可带 `channel:"accessibility"` 供诊断，但 schema 无 `via`；模型不能选择通道（D84）。

### 1.3 `shell`

**用途**：平台原能力的裸逃生口。承接 `am start/broadcast/service`、`pm`、`settings`、`cmd`、`dumpsys`、`input`、`screencap` 等。**不做类型化封装**：任何封装都会变成一份不完整的第二套 API，且与“薄桥接”原则相反。

**JSON schema**：

```json
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "要执行的 shell 命令。模型负责引用与转义；host 不重写命令。只支持 ASCII 的 input text 不能输入中文，中文输入请用 device.act(type)"
    },
    "timeout_ms": {
      "type": "integer",
      "minimum": 1000,
      "maximum": 120000,
      "default": 15000,
      "description": "单次命令上限；超时返回 SHELL_TIMEOUT，不声称命令未执行"
    },
    "max_bytes": {
      "type": "integer",
      "minimum": 1024,
      "maximum": 65536,
      "default": 16384,
      "description": "stdout 上限；超出按行边界截断并置 truncated=true。回包上限远低于 Binder 实测失败区（预研 §3.4）"
    }
  },
  "required": ["command"],
  "additionalProperties": false
}
```

**结果 `data`**：

```json
{
  "exit_code": 0,
  "stdout": "...",
  "stderr": "",
  "truncated": false,
  "duration_ms": 41
}
```

- `exit_code` 是 OS 事实，不是成功判据；成功/失败由模型结合命令语义判断，或由 `open` 这类包装工具做状态验证。预研 §3.4 已实测 `pm grant` 返回 0 但状态未变、`cmd statusbar` 返回 1 但能力可用。
- `truncated=true` 时 stdout 是前缀；模型应缩小命令或分页，不得把前缀当完整结果。
- **二进制/大输出**：本版不返回 base64；需要二进制时走后续 artifact 分页（D1/后续增量），本版只返回文本与截断标记。
- **有界执行是前置条件**：现有 `PrivilegedShell.run(command)` 没有 `max_bytes`/`timeout` 参数（`PrivilegedShell.kt:69-71`），`ShellReply.MAX_REPLY_BYTES` 是**特权进程内存上限**，不是可发送上限（实测 Binder 在约 325–350KB 已失败，`ShellReply.kt:34-55`）。`shell` 的这两个参数需要 D1/实现层增加有界执行（或命令自身 `head -c`，但那会改变退出码语义）；不能把 8MiB 当“能发”。
- **中文**：`input text` 仅 ASCII（`Virtual.kcm` 无 CJK，预研 §3.4）；中文输入走 `device.act(type)`。
- **root**：不暴露 `as_root`；root 能力本版不广告。

### 1.4 `open`

**用途**：只做“应用名/包名 → 已安装、可启动的目标 → 前台验证”。它是 AC-13 的唯一承接者；不做 URL、不做 settings 页、不做闹钟。

**JSON schema**：

```json
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "用户说出的应用名（如 音乐、微信）或包名（如 com.miui.player）。包名形式优先按包名精确匹配"
    }
  },
  "required": ["name"],
  "additionalProperties": false
}
```

**结果 `data`**：

- 成功：`{"query":"音乐","label":"音乐","packageName":"com.miui.player","component":"com.miui.player/.MainActivity","verified":true}`
- 歧义：`{"query":"音乐","candidates":[{"label":"音乐","packageName":"com.miui.player"},{"label":"QQ音乐","packageName":"com.tencent.qqmusic"}]}`
- 未安装：`{"query":"某应用"}`，`code=APP_NOT_FOUND`。

**风险**：L1、永不确认；打开 App 不涉及数据不可逆/花钱。

### 1.5 `dial`

**用途**：保留现有外呼能力，直接 `Intent.ACTION_CALL` + `CALL_PHONE`；没有 `CALL_PHONE` 时明确 `CALL_PERMISSION_MISSING`，**不**降级为 `ACTION_DIAL`（现有行为，`IntentActionExecutor.kt:275-290`）。

**JSON schema**：

```json
{
  "type": "object",
  "properties": {
    "number": {
      "type": "string",
      "pattern": "^\\+?[0-9*#]{3,20}$",
      "description": "可拨号码；只接受数字、+、*、#"
    }
  },
  "required": ["number"],
  "additionalProperties": false
}
```

- 风险 L2、`confirmationMode=NEVER`（业主前提：外呼不确认）。
- `auditTargetArgument=null`，号码永不进审计（`Actions.kt:30-36` 的既有规则）。
- 结果 `data` 只含 `{"dialed":true}`；不回显号码。

### 1.6 `notify_user`

保留现有 schema 与执行器（`NotifyUser.kt:105-115`）：

```json
{
  "type": "object",
  "properties": {
    "text": {"type": "string", "description": "要通知用户的内容"},
    "channel": {
      "type": "string",
      "enum": ["screen", "voice", "both", "silent"],
      "description": "screen=仅眼镜显示；voice=仅语音播报；both=同时显示和播报；silent=不通知用户但阻止系统自动补发通知"
    },
    "final": {"type": "boolean", "description": "true=模型声明任务结束（spec §3.7 决策5b；执行器当前未读取）"}
  },
  "required": ["text", "channel"],
  "additionalProperties": false
}
```

- `scope` 不是 `terminal`，中途通知不结束任务；`auditTargetArgument=null` 保证通知正文不进审计。
- 结果 `detail` 的 `notified:<n>` 计数保留：`ProgressGuard` 以 payload 比较（`AgentLoop.kt:493`），同一句重复通知若 payload 恒定会被误判 `NO_PROGRESS`（`NotifyUser.kt:72-74`）。
- 现有 schema 里的 `final` 字段执行器并未读取（`NotifyUser.kt:82-94` 只读 `text`/`channel`）；这是既有 drift，本版不扩大范围，建议在规格里明确“保留但暂不生效”或删除。

### 1.7 `load_skill`

保留现有 schema 与执行器（`LoadSkillTool.kt:90-104`）：

```json
{
  "type": "object",
  "properties": {
    "name": {"type": "string", "description": "SKILL 目录中的 name"}
  },
  "required": ["name"],
  "additionalProperties": false
}
```

- 正文通过 `onLoaded` 进 system slot，**不进工具结果**；结果只给 `loaded:<n>` / `SKILL_DORMANT loaded:<n>`（D-8）。
- `countsTowardStepLimit=false` 是仓库里唯一的免计步声明（`LoadSkillTool.kt:100-103`），新工具面不改变它。

### 1.8 共同约束

- **能力声明**：`ActionDescriptor` 增加 `capabilities: Set<ToolCapability>`（默认空集），枚举建议为 `A11Y / GESTURE / SCREENSHOT / SHELL / ROOT / CALL / LOCAL_OUTPUT / LOCAL_READ`。语义：列出的能力是工具“需要”的能力，宿主用实时能力集过滤广告；子能力（如 `device.screenshot` 需要 `SCREENSHOT`）缺失时返回 `UNSUPPORTED`，不是 `CHANNEL_UNAVAILABLE`。`ROOT` 本版不声明（`RootProbe` 不提供动作，`RootProbe.kt:33-41,61-62`）。
- **不做 on-demand 工具路由**：6 个工具全量广告；工具选择成本低于多一次模型调用，而且工具面已经小到不值得先路由。
- **模型不知道通道**：schema 里没有 `via` / `channel` 入参；结果里的 `channel` 只供诊断，模型不得据此重试或选择通道（D84）。
- **不可用不广告**：后端不可用时不进 `ToolRegistry`；已广告但在执行时掉线返回 `CHANNEL_UNAVAILABLE`（`ExecutionRouter.kt:51-59` 的既有行为）。
- **`UNSUPPORTED` 与 `CHANNEL_UNAVAILABLE` 分开**：设备能力缺失（如没有 `canPerformGestures`）用 `UNSUPPORTED`；通道掉线用 `CHANNEL_UNAVAILABLE`。
- **无运行时通道回退**：D84 明确当前没有 a11y↔shell 自动回退；`device` 不偷偷走 shell，`shell` 也不假装是 `device`。模型看到的是工具，不是通道。

---

## 2. 21→6 映射

### 2.1 旧清单（21 个动作工具）

来源：`HostCompositionRoot.kt:1914-1928` 合并 5 个后端；各后端 descriptor 列表见 `IntentActionExecutor.kt:90-162`、`ShizukuExecutionBackend.kt:55-119`、`AccessibilityExecutionBackend.kt:30-86`、`LaunchAppExecutor.kt:58-81`、`SystemActionExecutor.kt:60-72`；插件侧 `NotifyUser.kt:105-115`、`LoadSkillTool.kt:90-104`。

计数：6 Intent + 5 Shizuku 特权 + 5 a11y + 2 launch/list + 1 system + `notify_user` + `load_skill` = **21**。与预研 §3.2 一致。

### 2.2 映射表

| # | 旧工具 | 旧来源 | 去向 | 新位置/说明 |
|---|---|---|---|---|
| 1 | `open_app` | Intent L0 | **合并→`open`** | 名称→包名改由 shell 解析（AC-13）；绕开 App UID 的包可见性限制 |
| 2 | `open_url` | Intent L0 | **走 shell** | `am start -a android.intent.action.VIEW -d <url>`；不再有类型化工具 |
| 3 | `open_settings` | Intent L0 | **走 shell** | `am start -a android.settings.*`；原 10 页 allowlist 不再由工具层维护 |
| 4 | `set_alarm` | Intent L1 | **走 shell** | `am start -a android.intent.action.SET_ALARM --ei hour ...`；R-8 的“无承接”由此收口 |
| 5 | `compose_note` | Intent L1 | **删除** | 业主前提明确删除；App 内加密笔记不再对模型暴露 |
| 6 | `dial` | Intent L2 | **保留→`dial`** | 直接外呼；不确认；号码不入审计 |
| 7 | `clear_data` | Shizuku L3 | **走 shell** | `pm clear <pkg>`；命令分类为不可逆数据 → 确认 |
| 8 | `uninstall` | Shizuku L3 | **走 shell** | `pm uninstall --user 0 <pkg>`；不可逆 → 确认 |
| 9 | `disable_app` | Shizuku L4 | **走 shell** | `pm disable-user --user 0 <pkg>`；可逆（enable-user）→ 不确认 |
| 10 | `set_permission` | Shizuku L4 | **走 shell** | `pm grant/revoke`；可逆（再授权）→ 不确认；风险由分类器给级 |
| 11 | `set_brightness` | Shizuku L2 | **走 shell** | `settings put system screen_brightness <0..255>`；可逆 → 不确认 |
| 12 | `read_screen` | a11y L1 | **→`device.observe`** | 富属性（role/ref/bounds/actions）+ revision + diff；不再用 index 文本行 |
| 13 | `tap` | a11y L2 | **→`device.act(kind=tap)`** | ref 寻址；自动观察；不再按 index |
| 14 | `long_press` | a11y L2 | **→`device.act(kind=long_press)`** | 同上 |
| 15 | `scroll` | a11y L1 | **→`device.act(kind=scroll/scroll_to)`** | 方向 + 次数；`scroll_to` 用文本一次到位 |
| 16 | `input_text` | a11y L2 | **→`device.act(kind=type)`** | 不再限制 100 字符、支持换行/Unicode；`ACTION_SET_TEXT` 优先 |
| 17 | `launch_app` | Shizuku L1 | **合并→`open`** | 与 `open_app` 合并；不再有两条名称→包名路径 |
| 18 | `list_apps` | Shizuku L0 | **删除** | 目录改 shell `pm list packages`；候选列表由 `open` 返回。M2 的“只有 EDITH”根因不再走 `PackageManager` 枚举 |
| 19 | `system_action` | Shizuku L1 | **→`device.act(back/home/recents/notifications)`** | 全局动作走 a11y `performGlobalAction`；无 a11y 时模型可用 `shell cmd statusbar` / `input keyevent` |
| 20 | `notify_user` | 插件 L0 | **保留** | 不并入 `device`；它是输出通道，不是设备动作 |
| 21 | `load_skill` | 插件 L0 | **保留** | 免计步不变 |

### 2.3 D83 / R-8 / D121 的正式收口

| 决定 | 原文要点 | 本草案如何收口 | 备注 |
|---|---|---|---|
| **D83**（2026-10-16） | 工具面排除 Intent 通道；`open_app` 由 `launch_app` 承接；`set_alarm`/`compose_note`/`dial` 无承接 | 6 个 Intent 工具：`open_app`→`open`；`open_url`/`open_settings`/`set_alarm`→`shell`；`compose_note` 删除；`dial` 保留。**“无承接”被新决定取代** | `DECISIONS.md:55`；本文件不修改决策文件，规格阶段需正式改判 |
| **R-8**（0.21 残余） | `set_alarm`/`compose_note`/`dial` 本版不可用；9 个工具口径不完整 | `set_alarm` 由 shell 承接；`compose_note` 删除；`dial` 保留；工具面变为 6 个明确工具 | `ACCEPTANCE.md:251-252`；AC-13/16 需在下版本复测 |
| **D121**（2026-10-17） | 应用工具下版本重构；M2/AC-13/16 随下版本复测 | 本草案是 D121 的工具面设计输入；`open` 的解析链直接针对 M2/AC-13 | `DECISIONS.md:39` |
| **D120**（2026-10-17） | 0.21 本轮不修 | 0.25 承接修复；本文件不声称已修 | `DECISIONS.md:114` |

### 2.4 代码模块的去留（设计层）

| 现状 | 处置 |
|---|---|
| `IntentActionExecutor`（5 L0/L1 + dial） | 删除；`dial` 拆成独立 `DialActionExecutor`；其余 5 个不再有执行器 |
| `IntentExecutionBackend` | 删除；不再有恒可用的 intent 动作后端 |
| `LaunchAppExecutor`（`launch_app`/`list_apps`） | 删除；逻辑迁入 `open` |
| `SystemActionExecutor`（`system_action`） | 删除；全局动作并入 `device.act` |
| `AccessibilityExecutionBackend`（5 工具） | 替换为 `device` 执行器；旧的 `read_screen/tap/...` descriptor 全部删除 |
| `ShizukuExecutionBackend`（5 特权） | 删除；由 `shell` + 命令分类器承接 |
| `AppliedSemanticsPostProcessor` | 删除；`applied` 改由 envelope 字段表达（§4） |
| `ExecutionRouter` | 保留；后端变为 `device` / `shell` / `open` / `dial` 四个 |
| `notify_user` / `load_skill` | 保留，注册方式不变（`SmartAssistantPlugin.kt:2108-2117`） |

---

## 3. raw vs wrapped 的判定标准

### 3.1 可复用规则

1. **能直接用平台原语表达、且模型不需要跨调用状态** → 裸露（raw）。典型：shell 命令、`ACTION_CALL`。
2. **平台原语的“形状”对模型不稳定**（index 会变、文本不是标识、坐标不可用、没有 revision）→ 封装（wrapped）。典型：a11y 节点树。
3. **需要跨调用状态机**（ref 有效性、动作后观察、revision 失效）→ 封装；状态必须由 host 持有，不能塞给模型。
4. **需要确定性的业务解析**（应用名→包名、歧义返回）→ 封装；这不是“平台能力”，是产品语义，放给模型会退化成猜测。
5. **包装只增加“语义/状态/验证”，不增加第二套能力清单**。如果包装后仍要模型学一套不完整的命令子集，不如裸露原能力。
6. **安全/确认不靠工具形状**：不要把“删除”拆成单独的安全工具来避免确认；确认是策略层的事，工具面按能力而不是按风险切。
7. **一个工具一个动词族**：同一能力族的低频变体可以放 `action` 枚举；不同能力族不要合并。`device` 是业主已定的例外（一个工具承载 a11y 动作族），用严格的服务端校验和描述降低误填（e2e 的“不要把变体做成 `kind`”教训在这里以“同一能力族”为边界吸收，而不是拆成 12 个工具）。
8. **原生输出已经是结构化文本/退出码** → 不再包一层 JSON 语义；只做截断、错误分层和 envelope。

### 3.2 逐工具判定

| 工具 | raw/wrapped | 理由 |
|---|---|---|
| `device` | **wrapped** | a11y 原生是 `AccessibilityNodeInfo` 树 + index + `performAction`；index 在动作后失效，文本不是稳定 ID，没有 revision，动作返回 `true` 不等于生效（预研 §3.3）。必须由 host 生成 ref/revision、动作后自动观察、把 `applied` 与观察绑定 |
| `shell` | **raw** | 平台已有最全的命令面；任何类型化封装都会漏能力、增加维护面，且与“薄桥接”相反。host 只做超时、截断、错误分类、确认分类 |
| `open` | **wrapped** | 应用名→包名是产品解析问题，不是平台原语；AC-13 要求 0 命中/多命中可解释，不能靠模型猜包名；launcher component 解析与前台验证也需要 host 状态 |
| `dial` | **raw** | 一个 `ACTION_CALL` + 号码校验就是全部能力；没有跨调用状态，包装只会多一层 |
| `notify_user` | **薄封装** | 它是模型到用户的输出端口，需要 channel 语义、`ProgressGuard` 计数、审计脱敏；不是设备能力，故保留在插件侧 |
| `load_skill` | **薄封装** | 正文必须进 system slot 而非工具结果（D-2），且要区分 `NOT_FOUND`/`DISABLED`；直接读文件会破坏分层 |

### 3.3 反例（不要这样做）

- 不要为 `am start` / `pm uninstall` 各做一个 typed 工具：它们已经是 shell 的稳定命令面，封装只会得到一份永远落后于 `pm` 的子集。
- 不要把 `tap/long_press/swipe` 的“拆成独立工具”（e2e 的做法）和“合并成一个 `kind` 枚举”（本版的做法）同时保留；业主已定一个 `device`，规格里只能有一套，且要用严格的服务端校验降低误填。
- 不要用“安全”为理由把 `rm` 改成 `delete_file`：这会误导模型以为有额外保护；保护应在确认策略和天花板里。

---

## 4. 结果 envelope

### 4.1 形状

所有工具结果统一为一个 JSON 对象（**模型可见**；日志/证据不记录该对象正文，见 §4.4）：

```json
{
  "v": 1,
  "ok": true,
  "outcome": "done",
  "code": "OK",
  "detail": "tapped #n16",
  "tool": "device",
  "action": "tap",
  "channel": "accessibility",
  "accepted": true,
  "applied": true,
  "data": {
    "target": "#n16"
  },
  "observation": {
    "revision": 42,
    "app": "com.android.settings",
    "activity": ".Settings",
    "ime": false,
    "truncated": false,
    "mode": "diff",
    "changes": {
      "added": [],
      "updated": ["#n16 button \"Wi-Fi\" [focused]"],
      "removed": []
    }
  },
  "images": [],
  "timing": {"toolMs": 84}
}
```

字段语义：

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 是 | envelope 版本；首版 `1`。加字段不升版本，改语义才升 |
| `ok` | 是 | 与 `ToolResult.ok` 同义；`false` 仍然回填给模型，不是 run 终止 |
| `outcome` | 是 | `done` / `refused` / `failed`，由 `ActionOutcome` 映射 |
| `code` | 是 | 稳定机器码；`OK` 表示 done；错误码见 §4.3 |
| `detail` | 是 | 一行人类摘要，**不含屏幕正文/用户内容**；审计和 UI 可用 |
| `tool` | 是 | 工具名 |
| `action` | 否 | `device`/`shell` 等工具内部的 action；`open`/`dial` 可省略 |
| `channel` | 否 | 仅诊断：`accessibility`/`shizuku`/`intent`；**不是入参**，模型不能据此选择通道（D84） |
| `accepted` | 是 | 平台/通道是否接受了动作。Intent fire-and-forget 只有 `accepted=true`，不能推出 `applied` |
| `applied` | 否 | 是否独立观察到效果。`null` = 未验证；`true/false` = 已验证。`Done` 不再自动等于 `applied=true` |
| `data` | 否 | 工具特定结构化数据；必须是 JSON object。`shell` 的 stdout/stderr 放这里 |
| `observation` | 否 | `device` 的观察结果；结构见 D1，本文件只约定“必须在动作后返回” |
| `images` | 否 | 图片引用数组；图片正文不内联（§5） |
| `timing` | 否 | `toolMs`，执行器耗时；**不参与 `ProgressGuard` 指纹**（见 §4.4） |

### 4.2 与 `ActionResult(outcome, detail: String)` 的兼容策略

**推荐：契约新增可选字段，不把 JSON 塞进 `detail`。**

现状与问题：

- `ActionResult` 只有 `outcome/detail`（`Actions.kt:70`）。
- `ToolRegistry` 对 `Refused`/`Failed` 会加 `"Refused: "` / `"Failed: "` 前缀（`ToolRegistry.kt:93-97`）。
- `AppliedSemanticsPostProcessor` 又加 `[applied=true|false]` 前缀（`AppliedSemanticsPostProcessor.kt:47-51`）。
- 因此 `detail` 如果直接放 JSON，会被两层前缀污染；解析方要么剥前缀，要么把 JSON 当字符串再解码，脆弱且不可审计。
- `detail` 同时是审计/日志可用的摘要；把 JSON 放进去会诱发“日志记录屏幕正文”，违反 D117。

提案：

```kotlin
data class ActionResult(
    val outcome: ActionOutcome,
    val detail: String,
    /** 稳定机器码；null 时由 outcome 推导（Done=>OK，Refused=>REFUSED，Failed=>FAILED）。 */
    val code: String? = null,
    /** 工具特定 JSON object 字符串；plugin-api 不做 JSON 解析，只做非空/大括号形状检查。 */
    val dataJson: String? = null,
    /** true/false 仅在独立观察到效果时填写；null = 未验证。 */
    val applied: Boolean? = null,
    /** 平台是否接受；默认 Done=true，其余=false。 */
    val accepted: Boolean = outcome == ActionOutcome.Done,
    /** 仅诊断；不是入参。 */
    val channel: String? = null,
    /** 图片引用；正文不随结果走。 */
    val images: List<ImageRef> = emptyList(),
)
```

兼容性：

- 现有 `ActionResult(ActionOutcome.Done, "x")` 位置参数仍然编译；新增字段都有默认值。
- `ActionResult` 是 `plugin-api` 的 data class；新增字段会改变 JVM 构造器签名（Kotlin 默认参数不保留旧构造器），仓库内全量构建即可；**不承诺二进制兼容**。
- `ToolRegistry` 负责把 `ActionResult` 组装成 envelope：`detail`、`code`、`applied`、`channel`、`dataJson`、`images` 原样放入；`ToolResult.payload` 变成 envelope 字符串。旧的 `Refused: ` / `Failed: ` 前缀删除；模型通过 `outcome/code` 判断。
- 预执行错误（`UNKNOWN_TOOL`、`ARGUMENTS_INVALID`、`TOOL_THREW`）和 loop 的策略拒绝（`REFUSED_BY_POLICY`、`CONFIRMATION_*`）也统一包成 envelope（`ok=false`，`outcome` 按码映射 `refused`/`failed`），避免模型看到两种形状；`ToolResult.error` 的签名不变，只改 payload 内容。对应测试更新。
- `ToolResult` 增加 `images: List<ImageRef> = emptyList()`；`ToolRegistry` 把 `ActionResult.images` 映射到这里，`AgentLoop` 回填 `ModelHistoryItem.ToolOutput` 时带上 images（§5）。
- `AppliedSemanticsPostProcessor` 删除；`applied` 由各后端在验证后填写。Intent/launch 这类 fire-and-forget 填 `null`，不再用 `outcome=Done` 冒充 `applied=true`（AC-10 语义更诚实）。

### 4.3 错误码分层

| 层 | 码（稳定字符串） | 含义 |
|---|---|---|
| 控制面 | `UNKNOWN_TOOL` / `ARGUMENTS_INVALID` / `TOOL_THREW` | 注册表/解析/执行器抛异常；属于模型可纠正的步骤 |
| 策略 | `REFUSED_BY_POLICY` / `CONFIRMATION_UNAVAILABLE` / `CONFIRMATION_DENIED` / `CONFIRMATION_TIMEOUT` | 现有 loop 码（`AgentLoop.kt:142-148`），保留 |
| 通道 | `CHANNEL_UNAVAILABLE` / `SHELL_NOT_BOUND` / `SHELL_REPLY_TOO_LARGE` / `SHELL_REPLY_INVALID` / `SHELL_TIMEOUT` / `ROOT_UNAVAILABLE` / `UNSUPPORTED` | 通道不可用或能力缺失；`UNSUPPORTED` 与 `CHANNEL_UNAVAILABLE` 必须分开 |
| 定位 | `NODE_STALE` / `NODE_NOT_FOUND` / `NODE_NOT_CLICKABLE` / `AMBIGUOUS_TARGET` / `APP_NOT_FOUND` / `APP_AMBIGUOUS` | 目标问题；`APP_*` 是 `open` 的产品语义 |
| 动作 | `ACCEPTED_NO_EFFECT` / `STATE_UNCHANGED` / `SCROLL_AT_BOUNDARY` / `LAUNCH_FAILED` / `FOREGROUND_MISMATCH` | 平台接受但未生效/未达目标 |
| 验证 | `VERIFY_TIMEOUT` / `OBSERVATION_MISSING` / `ARTIFACT_MISSING` | 观察/验证/artifact 读取失败 |
| 限制 | `OUTPUT_TRUNCATED` / `SCREENSHOT_TOO_LARGE` / `TIMEOUT` | 输出被截断或资源超限；截断本身不是错误 |

映射旧码（规格阶段要写迁移表）：

- `NO_SCREEN_CHANGE` → `ACCEPTED_NO_EFFECT`（旧 a11y 的“接受但无变化”，`AccessibilityExecutionBackend.kt:239`）。
- `APP_NOT_VISIBLE` → `APP_NOT_FOUND`（旧 Intent 包可见性失败；语义上是“找不到”，`IntentActionExecutor.kt:236`）。
- `COMMAND_FAILED` → `SHELL_EXIT_NONZERO`（或保留 `COMMAND_FAILED` 作为兼容别名；规格阶段定）。
- `INVALID_SETTINGS_PAGE` / `INVALID_TIME` / `INVALID_URL` / `INVALID_NOTE` / `STORAGE_UNAVAILABLE` 随对应工具删除而消失；shell 承接后不再有这些产品级码。

### 4.4 ProgressGuard、审计与日志

- **`ProgressGuard` 指纹不能包含 `timing`**：现在它比较 payload 字符串（`AgentLoop.kt:493`）；如果 envelope 每次都带不同的 `toolMs`，连续相同动作永远不会触发 `NO_PROGRESS`。D3 §2.4 已把 guard 改为“观察状态指纹”；在 D3 落地前若仍比较 payload，则需要 `ToolResult.fingerprint`（默认 payload）并让 `AgentLoop` 用它喂 guard——envelope 的易变字段只进 payload，不进 fingerprint。两条路径都不能让 `timing` 参与去重。
- **审计**：`AuditSink.record` 仍只记 `action/scope/risk/decision/outcome/confirmationSource` 与 `auditTargetArgument` 的一个安全值（`Actions.kt:29-36`）；`shell` 的 `auditTargetArgument=null`，命令正文不进审计。`open` 建议 `auditTargetArgument=null`；若沿用现有 `launch_app` 的 `auditTargetArgument="name"`（`LaunchAppExecutor.kt:63-69`），只记模型给的应用名，解析出的包名与用户输入正文不进审计。
- **日志/证据**：`P3_RUN`、`P3_TURN`、`P3_ACT` 等只记计数/id/码；不记录 envelope 的 `data`/`observation`/`stdout`。D117 不变；业主放宽的是“屏幕内容可发给模型”，不是“屏幕内容可写日志”。图片相关日志只允许数量/尺寸/字节数/`imagesMissing`，不记 `ImageRef`、不记 base64、不记文件路径（与 D3 §6 一致）。
- **`channel`**：结果可带；日志可不带或只带枚举名。它不构成“模型选择通道”的能力（D84）。

---

## 5. 图片内容分片契约

> 这一节只定“工具结果如何带图、历史如何存、预算如何算”；编码器/截图内核的细节见 D1，历史压缩的 loop 策略见 D3。

### 5.1 `ImageRef` 与存储

`ImageRef` 进 `plugin-api`（依赖无关，只有元数据）：

```kotlin
data class ImageRef(
    val ref: String,          // artifact id：内容寻址，如 "sha256:<hex>" 或 session 内 ULID
    val mime: String,         // "image/png" / "image/jpeg"
    val width: Int,           // 像素
    val height: Int,
    val byteSize: Long,
    val sha256: String? = null, // 十六进制；用于去重与证据
)
```

- 存储：app 私有 `filesDir/artifacts/<sha256>.png`（内容寻址，去重）；不写外部存储，不进日志。
- 生命周期：session 内保留；session 结束/插件 deactivate 时清理（`SmartAssistantPlugin` 已有清 store 的路径，`SmartAssistantPlugin.kt:873`）；额外设容量上限（建议 64 MiB）与 LRU 兜底。
- 读取：`MediaSource` 增加 `resolveImage(ref: String): ResolvedImage?`；`ResolvedImage(bytes, mime, width, height)`。
- **缺失语义分两类**：
  - **截图/观察图**（`ToolOutput.images`）：ref 过期或未落盘时，编码器用可见占位 `[图像不可用：ref 已过期或未落盘]` 替换该图并继续请求（D3 §5.4）；`ContextBudgeter` 对缺失图只计占位文本；metrics 只记 `imagesMissing` 计数。**不静默丢图**，占位就是告诉模型。
  - **用户输入图**（`InputPart.Image`，本版尚未接入）：缺失是请求内容缺失，返回 `CapabilityMissing` 并拒绝，和音频 `resolveAudio` 的现有行为一致（`OpenAiChatCompletionsAdapter.kt:36-39`）。
- `ref` 对模型只暴露 `artifact:<ref>`（或 envelope 里的 ref）；不暴露文件系统路径。
- `ImageRef` 只进 `plugin-api`；本文件不再引入 `ArtifactKind`，图片类型由 `mime` 表达。

### 5.2 历史形状：`ToolOutput` / `Segment` / `RequestFrame`

现状的断点：

- `ModelHistoryItem.ToolOutput(callId, output: String)` 只有字符串（`ModelServices.kt:101-104`）。
- `Segment` 只有 `text`（`Segment.kt:44-49`），`RequestFrame` 只携带 segment 文本（`RequestFrame.kt:12-19`）。
- 插件的 `toSegment()` 把 `ToolOutput` 编成 `{"type":"output","callId":...,"output":...}` JSON 文本（`SmartAssistantPlugin.kt:3353-3365`），`ModelClientGateway.toolRoundItems` 再解回来（`ModelClientGateway.kt:340-370`）。**如果只给 `ToolOutput` 加图片，不在 `Segment` 里带，回环会把图片丢掉。**

提案：

```kotlin
data class ToolOutput(
    val callId: String,
    val output: String,
    /** 图片引用；正文不内联。默认空，旧构造调用不变。 */
    val images: List<ImageRef> = emptyList(),
) : ModelHistoryItem

data class Segment(
    val origin: SegmentOrigin,
    val trusted: Boolean,
    val text: String,
    val role: SegmentRole? = null,
    /** 该 segment 附带的图片引用；只有 TOOL_ROUND/TRANSCRIPT（未来用户图）会非空。 */
    val images: List<ImageRef> = emptyList(),
)
```

- `ConversationStore.appendToolOutput(callId, output, images = emptyList())`；`snapshot()` 把 images 带进 `Segment`（否则 frame 路径丢图）。
- **字段名以 D3 为准**：D3 提议 `Segment.toolMeta` 同时承载图片与 UI tree header；本文的 `Segment.images` 只是为了说明图片分片，规格阶段统一为一个 `toolMeta` 字段，**不要两个字段并存**。
- **生产还没有观测记录**：当前 `recordTurnAndCompress` 只 `appendUser`/`appendAssistant`（`SmartAssistantPlugin.kt:2085-2086`），`appendToolCall/appendToolOutput` 生产无调用；因此“跨回合图片历史”实际要等 D3 §3.5 的“每回合记录至多一个观察”方案获批。本文只定形状，不声称该路径已存在。
- `toSegment()` 的 `output` JSON 增加 `"images":[{"id":...,"mime":...,"width":...}]`；`ModelClientGateway.toolRoundItems` 解回 `ToolOutput.images`。**这是本设计唯一能保证“历史里的图不丢”的路径**；只改 `ToolOutput` 不改 `Segment` 是无效的。
- `RequestFrame` 不新增字段；images 挂在 `Segment` 上，`systemText`/`userInputSegment` 的现有约束不变。
- `Segment.text` 仍必须非空：纯图片结果写一个占位文本（如 `"[screenshot attached]"`），图片靠 `images` 走。

### 5.3 线上编码

- OpenAI Chat Completions：`tool` 角色消息的 `content` 用数组：
  ```json
  {"role":"tool","tool_call_id":"call_1","content":[
    {"type":"text","text":"..."},
    {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}
  ]}
  ```
- DeepSeek Responses：`function_call_output.output` 用数组（`input_text` + `input_image`，OpenAI Responses 形状）。
- **未测 / 回退形状**：两家供应商是否接受工具消息里的图片内容数组，本轮没有协议实测。Chat Completions 的 `tool` 消息内容按规范只保证 text parts，图片很可能被拒；此时回退为“tool 文本消息 + 一条紧随其后的 user 观察消息（文本 `tool observation <callId>` + image）”。这条 user 消息是**适配器对 `ToolOutput.images` 的线上渲染**，不新增 `ModelHistoryItem`，因此 `ContextBudgeter` 仍只按 `ToolOutput` 的图片成本计费，不需要改 loop 的分组规则；模型看到的是“工具观察”，不是用户又说了话。规格阶段必须选定一种形状并做供应商实测；**在实测通过前，`ProtocolSupport.inputs` 不得包含 `IMAGE`**，`CapabilityGate` 会因此拒绝请求（`CapabilityGate.kt:15-26`），不会静默降级成纯文本。这是“宁可明确拒绝，不可静默降级”的落点。
- **前置缺陷**：Chat Completions 适配器的历史分支目前只渲染 `Text`，`ToolCall`/`ToolOutput` 被静默丢弃（`OpenAiChatCompletionsAdapter.kt:186-194`）。在修好工具往返之前，该协议的“图片随工具结果进历史”无法验收；这是 D3 §5.6 已记录的 drift，不在本文件里直接改代码。
- 图片只在编码时从 artifact 读盘并 base64；`data`/envelope/日志里永远只有引用与元数据。
- 截图为 UI 文本服务，建议 PNG；若字节过大再考虑 JPEG。缩放长边 ≤768（预研 §8.7 的建议值），本轮未测画质与 token 收益。

### 5.4 `ConversationStore` 序列化与迁移

- **现状：`ConversationStore` 不落盘**。它只在内存中持有 `SessionItem`（`ConversationStore.kt:47-49`），由 `SmartWiring` 每个 route 建一个（`SmartWiring.kt:74-75`），进程重启即丢。因此本版**没有 store 的磁盘迁移**。
- 若后续加持久化：使用带版本的 JSON；`ToolOutput` v1 没有 `images`，v2 增加 `images: []`；v1 解码为 `images=emptyList()`，v2 解码旧数据兼容。artifact 文件与 store 分离；store 里的 ref 找不到文件时，替换为占位文本并置 `ARTIFACT_MISSING`（不静默丢整条历史）。
- **建议的持久化形状（未实现，仅约束）**：`{"version":2,"items":[{"role":"user","text":"..."},{"type":"toolOutput","callId":"...","output":"...","images":[{"ref":"...","mime":"image/png","width":768,"height":1706,"byteSize":12345}]}]}`；只写 ref+metadata，不写字节、不写路径。v1 → v2 迁移只补空数组，不重写正文。
- 迁移不是“把旧 profile 自动加 IMAGE”；profile 见 §7。

### 5.5 历史里图片的生命周期

- **K=3（与 D3 §5.3 一致，建议值，未标定）**：整个请求（含当前轮）最多 3 张图；最新 3 张保留，超出的旧图在**渲染时**去掉 `ImageRef` 并替换为 D3 的元数据占位（如 `[截图已省略：第 N 次观察，前台=<package>，尺寸=WxH]`），不调用 VLM 生成 caption。
- **保留位置是 D3 的 ⓪b，不是 `ConversationStore` 的 append**：store 只存 ref+metadata；K 的裁剪发生在请求装配/`TurnTrimmer` 的“旧图片→占位”一刀，只在实际超预算时执行（D3 §3.2/§5.3）。本文不重复实现，只约定 `ToolOutput.images` 是唯一数据源。
- **最新一张/当前轮最新观察永不丢**；同一回合多张图只保留最后 K 张。
- **不存字节**：store 序列化只允许 ref+metadata，禁止 base64/文件路径（D3 §5.5）；artifact 文件在 session 结束统一清理，容量上限 + LRU 兜底。
- `screenshot` action 的图、`observe(include_image=true)` 的图都按同一 K 处理；`device` 自动观察默认不带图，避免每次动作都产生图片成本。
- 生产当前不写工具轮（`SmartAssistantPlugin.kt:2085-2086`），所以“跨回合的旧图”规则在 D3 §3.5 的观测记录获批前没有对象；本版不声称跨回合图片已可用。

### 5.6 `ContextBudgeter` / `TurnTrimmer` 的成本口径

- `ContextBudgeter` 现在对任何非 `Text` 的 input 直接拒绝（`ContextBudgeter.kt:13-15`），文本成本是 UTF-8 字节 + 32（`ContextBudgeter.kt:55,86`）。要支持图片，按 D3 §5.2 的口径：
  - 删除“非 Text 即拒绝”；`InputPart.Image` 与历史 `ImageRef` 通过注入的 `MediaEstimator` 计成本；缺失 estimator 时仍按现状拒绝，**不发明比例**；
  - `ToolOutput` 成本 = `message(output)` + `sum(imageUnits)`；
  - `Segment` 成本 = `utf8BytesPlusOverhead(text)` + `sum(imageUnits)`（字段名以 D3 的 `toolMeta` 为准）；
  - `imageUnits = ceil(width/28) * ceil(height/28)`（e2e 保守上界，`D:\Project_new\e2e\packages\e2e\src\agent\model\adapter.ts:106-121`）。例如 768×1706 ≈ 28×61 = 1708；768×432 = 28×16 = 448；1080×2400 ≈ 39×86 = 3354。**未得到供应商真实 token 计费验证，是保守上界**。
  - 这个单位与现有“文本字节当 token 上界”同属 byte-scaled units；**图片成本远高于同尺寸文本**，必须显式计入，不能只算 ref 的几十字节；不新增“图片类别预算”（D81 统一预算）。
- `TurnTrimmer` 的 `measure` 现在是 `utf8BytesPlusOverhead(it.text)`（`TurnTrimmer.kt:113`）；改为把 segment 的 `imageUnits` 加进去。整回合淘汰仍按 `isTurnStart`，图片随回合一起走；`TrimReport` 建议加 `droppedImages: Int`（只计数），便于诊断。
- **口径一致性**：`ContextBudgeter.prepare` 与 `TurnTrimmer.total` 必须共用同一个 estimator/公式；否则 trim 认为能发、budgeter 认为超限，会重演 2026-10-14 的 `ConfigurationRejected` 类故障（该事件的记录见 `SmartAssistantPlugin.kt:2063-2069` 与 `ModelClientGateway.kt:236-247` 的注释背景）。
- **顺序**：D3 的 ⓪b“旧图片→占位”先于①旧整回合淘汰；不要在整回合淘汰之后才处理图片（那会把最新图也带走）。本文只强调契约顺序，具体裁剪实现归 D3。

### 5.7 破坏性变更

| 变更 | 影响 | 兼容策略 |
|---|---|---|
| `ModelHistoryItem.ToolOutput` 增加 `images` | 所有构造/`copy`/解构调用点；sealed `when` 不受影响 | 默认参数；全量重编译；旧 JSON（若有）缺省为空 |
| `Segment` 增加 `images`（D3 统一为 `toolMeta`） | `RequestFrame`、`TurnTrimmer`、`ConversationStore.snapshot`、插件 `toSegment` 全部要带 | 默认参数；测试更新 |
| `InputPart.Image` 增加 `width/height`（或改为 `ImageRef`） | `CapabilityGate`、`ContextBudgeter`、编码器 | 默认 0；0 时用保守常数/从 ref 解析 |
| `ActionResult` 增加字段 | `plugin-api` 契约；所有 executor 的构造调用 | 默认值；不承诺 JVM 二进制兼容 |
| `ToolResult` 增加 `images` / `fingerprint` | `agent-runtime` 契约；测试 | 默认值 |
| `ActionDescriptor` 增加 `confirmationMode` / `capabilities` | `plugin-api` 契约；所有 descriptor | 默认值（`BY_RISK` / 空集） |
| `ConversationStore` 磁盘格式 | 当前无磁盘格式，**不涉及迁移** | 未来持久化时用 v2 JSON；v1 图片为空 |
| `ModelProfile` JSON | 已有 `inputs` 数组，**格式不变** | 只是语义从“可选”变“必须含 IMAGE”；旧档案不自动改写 |

---

## 6. 确认策略

### 6.1 原则

- 只覆盖两类：**数据不可逆**（卸载、清数据、覆盖/删除用户数据）与**花钱**。
- 外呼（`dial`）不确认；打开 App、点击/输入/滚动、通知、读 SKILL 不确认。
- 可逆操作（停用 App、改权限、改亮度、`settings put`、`mkdir/cp`）不确认；靠用户 `maxLevel` 天花板兜底。
- 确认不是“安全沙箱”：shell 是任意命令，静态分类永远有绕过；本设计的目标是**把明确的不可逆/花钱场景挡在人面前**，并把残余风险写清。

### 6.2 `shell` 任意命令如何判定

**推荐方案：命令分类器 + 动态风险 + 确认/拒绝，而不是 denylist 单点。**

理由：denylist 只能挡已知字符串，`sh -c`、变量拼接、base64、别名、`$()` 都能绕过；仅 typed 工具又违背“shell 承接任意命令”的定位。分类器不追求“证明安全”，只做三件事：

1. 识别出**明确不可逆**的命令 → 确认；
2. 识别出**明确花钱**的入口 → 确认；
3. **无法判定/复合命令** → 也确认（保守），而不是放行。

**分类粒度**：

| 类别 | 示例 | 评估风险 | 处理 |
|---|---|---|---|
| 只读 | `pm list packages`、`dumpsys`、`getprop`、`settings get`、`ls/cat/stat`、`cmd package resolve-activity`、`screencap` | L1 | 放行 |
| 可逆写/启动 | `am start`、`input keyevent`、`input tap/swipe`、`cmd statusbar`、`pm disable-user`、`pm grant/revoke`、`settings put` | L2 | 放行（不确认） |
| 不可逆数据 | `pm uninstall`、`pm clear`、`rm`、覆盖重定向 `>`（写到非临时路径）、`dd`、`mkfs`、`content delete`、`settings delete`（用户数据类） | L3/L4 | 确认；超过 `maxLevel` 则拒绝 |
| 花钱 | 已知支付/购买入口的 `am start`/`intent`（如支付包、运营商计费 URI） | L4 | 确认；超过 `maxLevel` 则拒绝 |
| 无法判定 | 复合命令（`;`、`&&`、`||`、`|`、`$()`、反引号）、`sh -c`/`bash`/`eval`、未知首命令 | L4 | 确认；超过 `maxLevel` 则拒绝 |

**确认摘要（用户可见）**：显示命令本身 + 类别（如“可能删除数据”/“可能产生费用”）。这是用户做决定需要的信息；**不写日志**，日志只记 `P3_POLICY ... code=CONFIRM_REQUIRED risk=L3 len=<n> hash=<short>` 之类，不记命令正文（D117）。

**残余风险（必须写进规格/验收）**：

- 分类器是保守启发式，不是沙箱；`sh -c`/编码混淆/未知命令会被当成“未知→确认”，但用户点“允许”后仍可能执行任意内容。
- “花钱”无法穷举支付入口；只有已知入口会被识别，其他仍可能被当成 L2。
- 命令可以启动其他 App 再借 UI 完成不可逆操作；这不是 shell 分类器能识别的，属于“模型+用户确认”的边界。
- 本版无法在 host 侧可靠解析任意 shell 的最终效果；确认后的执行结果仍以 `exit_code/stdout` 为准，不承诺“已生效”。

**实现落点**：

- `ActionDescriptor` 增加 `confirmationMode: ConfirmationMode`（`BY_RISK` 默认、`NEVER`、`ALWAYS`、`CLASSIFIED`）。
- `ActionPolicy.decide` 增加 `argsJson` 参数（当前只收 descriptor，`Policy.kt:10-12`；`AgentLoop.kt:420` 调用点改传 `call.argsJson`）；`WhitelistActionPolicy` 对 `CLASSIFIED` 调用注入的 `ShellCommandRiskAssessor`。
- 评估结果同时给出 `assessedRisk: RiskLevel`；策略先用 `max(descriptor.risk, assessedRisk)` 和 `maxLevel` 比较，再决定 `Allow/RequireConfirmation/Refuse`。
- `shell` 的 descriptor `risk=L1`（底值）、`confirmationMode=CLASSIFIED`、`requiresConfirmation=false`；`dial` 的 `risk=L2`、`confirmationMode=NEVER`。
- 分类器只依赖命令字符串，不依赖通道；测试用表驱动覆盖每类命令。

### 6.3 `dial` 不确认

- `dial` 的 descriptor：`risk=L2`、`confirmationMode=NEVER`、`requiresConfirmation=false`。
- 仍然受 `maxLevel` 天花板约束：默认 `maxLevel=L1` 时 L2 的 `dial` 会被 `WhitelistActionPolicy` 判 `Refuse`（`WhitelistActionPolicy.kt:43`）。这不是“确认”，是“用户把上限设到了 L1”。是否要在 0.25 把默认上限提高或把 `dial` 降到 L1，见 §9 待裁决 4。
- `CALL_PHONE` 缺失 → `CALL_PERMISSION_MISSING`，不降级到拨号盘（现有行为，`IntentActionExecutor.kt:283-290`）。

### 6.4 与现有策略的关系

- `requiresConfirmation=true` 仍然是插件“强制提问”，不能被 `confirmationMode=NEVER` 覆盖；`NEVER` 只用于描述符明确声明“这个工具本身不提问”的情况。
- L3/L4 的旧工具已删除，静态“L3/L4 永远确认”的规则只对 `shell` 的动态评估生效；不再有 `clear_data`/`uninstall` 这类固定 L3 工具。
- `maxLevel` 仍是硬天花板：评估风险高于上限直接 `Refuse`，确认也不能越过（与现有 `WhitelistActionPolicy.kt:41-43` 一致）。

---

## 7. 档案门控

### 7.1 硬门：智能助手只接受多模态档案

`ModelProfile` 已有 `inputs: Set<InputModality>`（`ModelServices.kt:22-39`），`InputModality` 已有 `IMAGE`（`ModelServices.kt:5`），`CapabilityGate` 已能把 `InputPart.Image` 映射成 `IMAGE_REQUEST` 验证项（`CapabilityGate.kt:16-47`）。本版把“可选”改为“智能助手硬要求”：

在 `runTurn` 进入 loop 之前（或 session 启动时）检查所选 `ModelProfile`：

1. `InputModality.IMAGE in profile.inputs`；
2. 当前 `profile.revision` 存在 `CapabilityVerification(operation="IMAGE_REQUEST", state=Verified)`；
3. 对应协议的 `ProtocolSupport.inputs` 包含 `IMAGE`（`OpenAiChatCompletionsAdapter.kt:22-23` 目前只有 TEXT/AUDIO；`DeepSeekResponsesAdapter.kt:10` 只有 TEXT）；
4. `RequestConsent.image` 为 true（`CapabilityGate.kt:25`）。
5. **`CapabilityGate` 必须把历史图片算进 modality 集**：它现在只从 `request.input` 推导输入模态（`CapabilityGate.kt:16-22`），`history` 里后来才出现的 `ToolOutput.images` 不会触发 `IMAGE_REQUEST` 检查；不改这一点，纯文本档案仍可能带着历史图通过。

任何一条不满足：

- 拒绝本轮，稳定码：`PROFILE_NOT_MULTIMODAL`（缺 IMAGE 声明） / `PROFILE_IMAGE_UNVERIFIED`（缺验证） / `PROTOCOL_IMAGE_UNSUPPORTED`（协议不支持）。
- 给用户明确提示：例如“当前档案不支持图片输入。请到设置→模型档案勾选‘图片’，完成图片验证后再开始；本版本不会把截图降级成纯文本。”
- 日志只记 `P3_PROFILE code=... profileRevision=...`，不记 baseUrl/model/secret。

**不要静默降级**：不得把 `device` 的截图结果替换成“无图”继续跑，也不得把 `ToolOutput.images` 丢掉后照常请求。宁可这一轮明确失败。

### 7.2 现有纯文本档案怎么处理

- **不自动改写** `inputs`；旧档案保持原样。
- 档案列表/选择器显示明确状态：`图片：未声明` / `图片：未验证` / `图片：可用`（现有 `ProfileListScreen.kt:51` 的“图片：未接入 / 未验证”要随实现更新，不能留旧文案）。
- `smart-assistant` 的选择：如果所选档案不满足硬门，设置页阻止“选择用于智能助手”，或允许选择但在 session start 立即给出上面的明确失败；推荐前者（更早失败），保留后者作为兜底。
- 其他插件（诊断/面试已搁置）仍可使用纯文本档案；硬门只加在智能助手的 `runTurn`/session 启动路径，不改 `ModelProfile` 本身的通用性。

### 7.3 debug `QwenOmni` 档案迁移

现状（`DebugProfileCreator.kt:43-45`）：

```kotlin
val inputs = if (protocolId == "OPENAI_CHAT_COMPLETIONS")
    setOf(InputModality.TEXT, InputModality.AUDIO) else setOf(InputModality.TEXT)
```

它创建 `qwen-omni-dev`，声明 `TEXT+AUDIO`，**没有 IMAGE**；按新硬门不可用。

迁移方案（debug-only，不碰用户档案）：

1. `DebugProfileCreator` 新建档案时，`OPENAI_CHAT_COMPLETIONS` 改为 `TEXT + AUDIO + IMAGE`；其他协议仍 `TEXT`（若协议不支持图片，硬门会拒绝）。
2. 已存在的 `qwen-omni-dev`：增加一个显式迁移分支（`EXTRA_MIGRATE=true`）：
   - 读取旧 profile（保留 secret，`AndroidProfileRepository.saveWithSecret(draft, null)` 的 coordinated 路径会复用 old secret，`AndroidProfileRepository.kt:56-85`）；
   - `inputs = old.inputs + IMAGE`，`verifications = emptyList()`（revision 递增后旧验证自动失效，`AndroidProfileRepository.kt:69`）；
   - 重新跑 `testImage` 验证，写回 `IMAGE_REQUEST=Verified`；**不把验证结果手写成 Verified**。
3. 若 endpoint 不支持图片，迁移后仍不可用；这是正确结果，不改回纯文本。
4. `DevelopmentProfileLoader` 的 `OPENAI_CHAT_COMPLETIONS` 分支（`DevelopmentProfileLoader.kt:31-32`）同样要加 `IMAGE`，否则开发档案也会被硬门拒绝。
5. `CapabilityGate` 的 probe 分支当前只允许 `TEXT/AUDIO`（`CapabilityGate.kt:27-32`）；要支持 `IMAGE_REQUEST` 验证，必须允许 `IMAGE`（在 `consent.syntheticProbe` 下用合成图），并新增 `ProfileTester.testImage`（现有接口只有 `testText`，`ProfileTester.kt:8`）。
6. UI 文案 `ProfileEditorScreen.kt:82` 的“图片请求：当前无图片适配器”在适配器落地后要删除/改写；否则是新的 drift。

**未测**：QwenOmni 端点是否接受 `image_url` data URL、是否返回稳定结果；迁移命令和设备验证都没有跑。

### 7.4 协议能力声明

- `OpenAiChatCompletionsAdapter.support` 增加 `InputModality.IMAGE`，前提是编码器实现并通过实测；否则不要加，让 `CapabilityGate` 继续明确拒绝。
- `DeepSeekResponsesAdapter.support` 保持 `TEXT`，除非供应商 Responses 协议实测支持图片；不要让 `profile.inputs` 的 IMAGE 单独“骗过” gate。
- `ProtocolSupport` / `ModelProfile.inputs` 两层都要含 IMAGE；`CapabilityGate` 已经同时检查两者（`CapabilityGate.kt:23-24`）。

---

## 8. AC-13 应用目录

### 8.1 解析链

`open(name)` 的执行链（全部走 Shizuku shell；不依赖 App UID 的 `PackageManager` 枚举，因为 K40 上正是它把目录限制成只有 EDITH，`ACCEPTANCE.md:45`、`IntentLauncher.kt:69-109`）：

1. **枚举包**：`pm list packages`（全用户；实测 48–58ms，预研 §3.4）。输出行形如 `package:<name>`；去掉前缀、去重、跳过空行，得到 `Set<packageName>`。
2. **枚举 launcher**（优先一次调用，失败再逐包）：
   - 首选 `cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER` 一次拿到可启动 component；
   - 若该命令在 MIUI 不可用/输出不可解析（**未测**），退化为“只对匹配到的候选包”调用 `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>`（现有 `LaunchAppExecutor.kt:109-125` 的形态）。
3. **解析 label（尽力）**：
   - 先试 App 侧 `PackageManager.getApplicationInfo(pkg, 0)` + `loadLabel`；包可见时最准（当前 M2 失败说明大量包不可见，**未测修复后覆盖率**）；
   - 不可见时解析 `dumpsys package <pkg>` / `cmd package dump <pkg>` 里的 `nonLocalizedLabel=`（有则用；`labelRes=` 是资源 id，不是文本，不能直接当 label）；
   - 再退到一张**小型内置别名表**覆盖常见系统 App（音乐/设置/相机/电话/短信/浏览器/时钟/计算器/应用商店等；如 `com.miui.player`→音乐）。这不是通用方案，但能让 AC-13 的典型“音乐”场景可测；
   - 最后用包名作为显示名。**不因 label 缺失而丢包**；候选里 `label` 可以是包名。
4. **匹配**：先按 label/包名精确（trim、忽略大小写），再按 label 包含、包名包含；规则沿用 `AppLaunchResolver.kt:49-63` 的“精确优先、子串次之、歧义不猜”。
5. **启动**：`cmd package resolve-activity --brief ...` 拿 component（现有代码取最后一行含 `/` 的行，`LaunchAppExecutor.kt:117`），`am start -W -n <component>`；无 component 时回退 `am start -W -a MAIN -c LAUNCHER -p <pkg>`（`LaunchAppExecutor.kt:118-125`）。`Status: ok` 只表示接受，不等于前台已切换。
   - **插值前校验**：package 名用 `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+`，component 用 `[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+`；不符合就 `APP_NOT_LAUNCHABLE`，不把 shell 输出原样拼进命令（复用 `ShizukuExecutionBackend.kt:44` 的“拒绝而不是转义”原则）。
6. **验证**：settle 后读 `dumpsys activity activities` 的 top resumed（或 `device.status` 的前台；`open` 后端不依赖 a11y，优先 dumpsys），与目标包比较：
   - 匹配 → `applied=true`，`code=OK`；
   - 不匹配 → `applied=false`，`code=FOREGROUND_MISMATCH`，`accepted=true`；
   - 读不到 → `applied=null`，`accepted=true`（accepted_only，不冒充成功）。

### 8.2 失败语义

| 情况 | `ok` | `code` | `data` |
|---|---|---|---|
| 0 命中 | false | `APP_NOT_FOUND` | `{"query":...}` |
| 多命中 | false | `APP_AMBIGUOUS` | `{"query":...,"candidates":[{label,packageName}]}` |
| 包存在但无 launcher activity | false | `APP_NOT_LAUNCHABLE` | `{"packageName":...}` |
| `pm list` / `am start` 通道失败 | false | `CHANNEL_UNAVAILABLE` / `SHELL_NOT_BOUND` | `{}` |
| `am start` 非 0 / 无 `Status: ok` | false | `LAUNCH_FAILED` | `{"exitCode":...}` |
| 启动后前台不是目标 | false | `FOREGROUND_MISMATCH` | `{"expected":...,"actual":...}`（actual 若是包名，可给；若是用户内容，截断/不记） |

- **歧义必须是 `ok=false`**。现有 `LaunchAppExecutor.launchApp` 在 `LaunchResolution.Ambiguous` 时返回 `done(formatCandidates(...))`（`LaunchAppExecutor.kt:97-100`），把“没启动”报成 `Done`；这是 AC-13 的缺陷，`open` 必须修掉。
- 候选列表只含 `label`/`packageName`，不含用户输入正文；`query` 可回显用户说的应用名（属于模型输入，不是屏幕内容），但日志不记。
- `APP_NOT_FOUND` 不自动猜“最相似”的应用；AC-13 明确不许静默选一个。

### 8.3 缓存与性能

- 目录缓存：session 内或 30–60s TTL；`pm list packages` 实测 48–58ms，`resolve-activity`/label 解析按需做，不每次全量。
- label 缓存以 `(packageName, versionCode?)` 为键；本版没有安装/卸载工具，版本变化只可能由用户外部操作引起，TTL 兜底即可。
- 大目录不一次性进结果：`open` 只返回匹配候选；`list_apps` 已删除，模型需要全量目录时用 `shell pm list packages`（包名，可能截断）。
- 启动耗时实测 `am start -W` 105–203ms（预研 §3.4）；端到端 `open+verify` 未测。

### 8.4 未测/风险

- `cmd package query-activities` 在 MIUI/Android 13 的可用性与输出格式：**未测**。
- `dumpsys package` 的 `nonLocalizedLabel` 覆盖率：**未测**；系统 App 可能没有。
- label 别名表的维护成本与“新 App 装了就认识”的期望：**推断**，需要产品决定（§9 待裁决 3）。
- 修复后 AC-13/16 的设备行为：**未测**，必须在 K40 上复测，不能拿 JVM 测试替代。

---

## 9. 待裁决问题（5 条）

1. **`shell` 的动态风险与默认 `maxLevel`**：推荐 `shell` descriptor `risk=L1`（底值）+ `confirmationMode=CLASSIFIED`，分类器可把单次命令评估到 L4 并按 `maxLevel` 拒绝/确认；但这意味着描述符的 `risk` 不再是“工具最大风险”，是一个语义变化。备选是 `shell risk=L4`，默认 `maxLevel=L1` 下直接不可用。请定：是否接受“动态 assessed risk 覆盖 descriptor risk”，以及默认上限是否仍为 L1。
2. **图片历史 K 与缩放**：推荐 K=3、长边 768、`ceil(w/28)*ceil(h/28)` 保守 token 上界；在 8k 输入预算下三张 768×1706 图可能占 ~5.1k，和树文本竞争。请定：K=3/768 是否可接受，还是 8k 档案降到 K=1/512，或按 `usableWindowTokens` 自适应。
3. **App label 缺失时的候选**：`shell` 无法稳定拿到不可见包的 label。推荐“label 尽力解析，最后以包名作为 label；内置常见系统 App 别名表兜底”。请定：是否接受“候选可能显示包名”，还是必须为 AC-13 维护更大的别名表/引入 APK 资源解析（依赖与安全成本更高）。
4. **`dial` 的默认可用性**：`dial` 风险 L2 + `NEVER`，而默认 `maxLevel=L1`（`SmartSettings.kt:26`）会把它判 `Refuse`（不是确认）。请定：把 `dial` 降到 L1（外呼不确认且默认可拨），还是保持 L2、要求用户把上限调到 L2 才能外呼。
5. **`device.observe` 的步数/自动观察**：`countsTowardStepLimit` 是工具级（`Actions.kt:38-50`），`device.observe` 也会计一步；若要“只读观察免计步”就得改 loop 契约（D3）。另外“每个 mutating act 自动观察”会固定增加设备耗时。请定：接受现状（全部计步、全部自动观察），还是在 0.25 只对 `observe`/`screenshot` 免计步（需要契约扩展）。

---

## 10. 未测与推断清单

| 项 | 状态 | 说明 |
|---|---|---|
| AC-13 解析链（`pm list` + `resolve-activity` + label + 前台验证） | **未测** | 只有代码/预研数据；必须在 K40 上复测 |
| `cmd package query-activities` 在 MIUI 的可用性/格式 | **未测** | 失败则退化为逐包 `resolve-activity` |
| `dumpsys package` 的 `nonLocalizedLabel` 覆盖率 | **未测** | 不可见包可能没有 |
| OpenAI/DeepSeek 工具消息携带 `image_url`/`input_image` | **未测** | 实测前不得在 `ProtocolSupport.inputs` 中声明 IMAGE |
| OpenAI Chat Completions 的工具往返 | **代码缺陷/未修** | 历史分支丢弃 `ToolCall`/`ToolOutput`（`OpenAiChatCompletionsAdapter.kt:186-194`）；不比图片更早修，图片历史无法验收 |
| 768 长边截图的画质、token、端到端延迟 | **推断** | 公式来自 e2e，未在 EDITH 上测 |
| 图片历史 K=3 的上下文收益/成本 | **推断** | 无 A/B 数据 |
| shell 分类器的绕过抵抗 | **推断/风险** | 静态启发式，不能当沙箱 |
| QwenOmni 档案迁移与 `IMAGE_REQUEST` 验证 | **未测** | 端点是否接受图片未知 |
| `shell` 输出 16KB 上限与 Binder 回包 | **实测参考** | 回包上限 520,187 JSON 字符、`screencap` 321–398ms 来自预研 §3.4；本工具新实现未测 |
| `device.act` 的 settle 时长 | **推断** | 参考 e2e；具体值由 D1 定 |
| `ProgressGuard` 与 envelope `timing` 的相互作用 | **推断** | 设计上加 `fingerprint`；未实现 |

---

## 11. 参考

### EDITH 代码

- `common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/Actions.kt`：`ActionDescriptor` 20-50、`ActionResult` 70、`RiskLevel` 6。
- `common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/ModelServices.kt`：`InputModality` 5、`ModelProfile` 22-39、`InputPart` 51-64、`ModelHistoryItem` 87-105。
- `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ToolProtocol.kt`：`ToolResult` 28、`ToolDescriptor` 39-58、`SCOPE_TERMINAL` 66。
- `.../ToolRegistry.kt`：schema 校验 29-38、合并 59-77、`Refused:` 前缀 93-97。
- `.../AgentLoop.kt`：策略/确认 420-484、step 计数 401-406、`ToolOutput` 回填 491、guard 493。
- `.../WhitelistActionPolicy.kt`：41-56。
- `.../Policy.kt`：10-24。
- `.../Segment.kt`：9-53。
- `.../ConversationStore.kt`：47-49、81-83、138-145、340-344、347。
- `.../TurnTrimmer.kt`：113、120-122、126-150。
- `.../ModelClientGateway.kt`：120-125、130-142、264-322、340-370。
- `phone-app/assistant/model-runtime/.../ContextBudgeter.kt`：13-15、55-63、64-65、86。
- `.../CapabilityGate.kt`：16-47。
- `.../OpenAiChatCompletionsAdapter.kt`：22-23、33-39、186-194。
- `.../DeepSeekResponsesAdapter.kt`：10、111-126。
- `.../MediaSource.kt`：24-26。
- `phone-app/app/.../actions/ExecutionRouter.kt`：43-59。
- `.../actions/intent/IntentActionExecutor.kt`：90-162、216-224、232-239、275-290。
- `.../actions/intent/LaunchAppExecutor.kt`：58-81、97-100、104-130、139-150。
- `.../actions/intent/SystemActionExecutor.kt`：31-36、60-72。
- `.../actions/intent/IntentLauncher.kt`：69-109。
- `.../actions/intent/AppLaunchResolver.kt`：49-63。
- `.../actions/a11y/AccessibilityExecutionBackend.kt`：30-86、157-159、230-240、280-297。
- `.../actions/a11y/AppliedSemanticsPostProcessor.kt`：39-51。
- `.../actions/shizuku/ShizukuExecutionBackend.kt`：55-119、179-231。
- `.../actions/shizuku/PrivilegedShell.kt`：16-66、254、299-300。
- `.../actions/shizuku/ShellReply.kt`：34-55、63-82。
- `.../actions/shizuku/RootProbe.kt`：33-41、61-62。
- `phone-app/app/.../app/HostCompositionRoot.kt`：1882-1929、1930-1998、2020-2061。
- `phone-app/plugins/plugin-smart-helper/.../output/NotifyUser.kt`：61-115。
- `.../skill/LoadSkillTool.kt`：38-104。
- `.../SmartAssistantPlugin.kt`：2101-2117、2280-2290、3284-3291、3331-3365。
- `.../host/SmartWiring.kt`：56-75。
- `phone-app/app/.../storage/AndroidProfileRepository.kt`：56-85、154-170。
- `phone-app/app/src/debug/.../DebugProfileCreator.kt`：43-45。
- `phone-app/assistant/model-runtime/.../DevelopmentProfileLoader.kt`：31-32。
- `phone-app/app/src/main/AndroidManifest.xml`：39-43（`QUERY_ALL_PACKAGES`）。

### 预研与验收

- `docs/pre-research/2026-10-19-edith-agent-evolution-pre-research.md`：§3.2 工具面 99-113、§7 差距矩阵 272-299、§8.2 工具面 319-333、§8.3 观察/返回 334-367、附录 D 637-681、附录 E 685-697。
- `docs/project/DECISIONS.md`：D121 39、D83 55、D84 110、D120 114。
- `docs/project/ACCEPTANCE.md`：AC-13 45、R-8/drift 251-252。
- `docs/project/CONTRACTS.md`：186。

### 参考实现

- `D:\Project_new\X-OmniClaw\app\src\main\java\com\shijing\xomniclaw\agent\tools\ToolRegistry.kt`：112-132（on-demand schema）。
- `...\AndroidToolRegistry.kt`：54-80（device 单工具 + 分组）。
- `...\device\DeviceTool.kt`：140-268（`action/kind` schema）、270-283（dispatch）、56-59（settle/ref age）。
- `...\LlmOnDemandToolInclusion.kt`：9-44（默认不广告）；`LlmToolRouter.kt`：34-41（轻量路由）——本版**不采用**（工具只剩 6 个）。
- `D:\Project_new\e2e\packages\e2e\src\agent\primitives.ts`：317-606（grammar tools、动作结果=观察）。
- `...\tool-output.ts`：11-14、35-65（400 行/16KB 截断）。
- `...\action-names.ts`：10-34、62-87（动作名表）。
- `...\model\adapter.ts`：106-121（image token 上界）。
- `...\observation-budget.ts`：34、63-73（像素预留）。
- `D:\Project_new\MobileAgent\Mobile-Agent-v3.5\mobile_use\run_gui_owl_1_5_for_mobile.py`：61-71（`<tool_call>` 解析）、74-87（归一化坐标）、90-137（open 解析 + LLM resolver）、168-188（历史带图）、274-280（每步截图进历史）。
