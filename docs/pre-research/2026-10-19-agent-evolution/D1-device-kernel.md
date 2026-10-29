# D1 设备控制内核（Device Kernel）设计草案

**草案，未批准；不是 spec，不得据此改产品代码。**

> 日期：2026-10-19 · 基线：`main` HEAD `1e0cc41`（0.24 已验收闭环，未 push）· 性质：只读调研 + 设计草案
> 范围：0.25 的 device 内核与解锁预检。工具 schema 归 D2，loop/上下文/预算归 D3，语料/验收归 D4。
> 本文件未修改任何产品代码、`DECISIONS.md`、`CONTRACTS.md`、`ACCEPTANCE.md`、`STATUS.md`。

---

## 0. 摘要、边界与证据口径

### 0.1 一句话

0.25 的 `device` 工具背后应当有一个**纯 JVM、可测、只此一份**的 DeviceControl 内核：它唯一持有观察（revision/generation/ref）、动作解析（语义 → 手势 → shell）、通道健康、设备就绪（解锁预检）与结果 envelope；生产 `EdithAccessibilityService` 留在 `app` 模块，退化成薄平台适配器；每次工具调用先过 `ensureReady()`，锁屏/息屏在「无安全锁屏」前提下用 `UnlockActivity + requestDismissKeyguard` 主路径、Shizuku 兜底，并全程遵守「不跟用户抢手机」。

### 0.2 已定前提（不再论证，只作为设计约束）

1. 视觉策略参考 X-OmniClaw：结构树优先；低置信/空树/SurfaceView/验证失败才走视觉兜底。
2. 工具面做薄，系统只做桥接与结果响应；最终工具为 `device / shell / open / notify_user / load_skill + dial`，删 `compose_note`，不设 intent 工具。
3. 手机锁屏/息屏时无障碍只能看到 SystemUI、操作不了目标 App；Shizuku 在小米/MIUI 息屏后会被停掉（外部证据见 §9.3）。工具调用前必须有解锁预检。
4. 业主口径：使用本 App 的前提是**无安全锁屏**；`isKeyguardSecure() == true` 直接判不可用，不尝试绕过。
5. 解锁时机工作假设 A：任务开始解锁一次，任务期间保持亮屏，任务结束释放；每次工具调用只做廉价状态检查。
6. 确认闸门只覆盖数据不可逆（卸载/清数据/覆盖删除）与花钱；外呼不问。
7. 上下文/预算：D81 统一窗口预算 + D97 裁剪顺序；D84 模型不知道通道、坐标不暴露；D117 证据只记计数/id。本任务按业主「不考虑隐私与安全」放宽**屏幕内容上传**，但证据纪律不变。

### 0.3 本草案不覆盖

- `device` / `shell` / `open` / `notify_user` / `load_skill` / `dial` 的正式 schema、action 枚举与 on-demand 广告策略（D2）。
- AgentLoop 的循环保护、多 tool call 并发、上下文裁剪与预算（D3）。
- 语料、成功率与验收判词（同目录对应草案与 `ACCEPTANCE.md`）。
- 具体实施提交、迁移批次与设备操作。

### 0.4 证据口径（全文遵守）

- **实测**：只引用本项目 K40 证据（S1 a11y / Shizuku 调研 / reply ceiling）或明确标注设备/版本的证据；不把探针读数写成生产读数。
- **源码级**：X-OmniClaw / e2e / AOSP 的行为结论来自本机源码阅读；本机未构建、未运行、未真机执行。
- **文档级/外部 issue**：KeyguardManager、Shizuku/MIUI issue、ouroboros issue、Android 14 FSI 限制只作依据，不是 K40 实测。
- **未测**：一律写「未测/推断」，不引用不存在的读数。

---

## 1. 模块与依赖

### 1.1 建议结论

| 层 | 模块 / 路径 | 包 | 职责 | 依赖 |
|---|---|---|---|---|
| 内核（新） | `:phone-app:assistant:device-control`，`phone-app/assistant/device-control/` | `com.sheen.rokid.device` | Observation / Ref / Diff、Action 解析与降级、Settle、Envelope、ChannelHealth、Unlock 状态机、坐标归一化 | 仅 Kotlin stdlib + `kotlinx-serialization-json`；**不依赖 Android、不依赖 `plugin-api`** |
| a11y 适配 | 现有 `:phone-app:app` | `com.sheen.rokid.phone.actions.a11y` | `EdithAccessibilityService`（系统组件）+ `A11yPlatformAdapter`（树/动作/手势/截图） | app + device-control |
| shell 适配 | 现有 `:phone-app:app` | `com.sheen.rokid.phone.actions.shizuku` | `PrivilegedShell` → 内核 `ShellPort`；保留 failure/truncated/relinked 语义 | app + device-control |
| 解锁/亮屏适配 | 现有 `:phone-app:app`（新增包） | `com.sheen.rokid.phone.actions.unlock` | `UnlockActivity`、Keyguard/Power 探针、FSI 通知、`ScreenAwakeLease`、`Settings.canDrawOverlays` 判定 | app + device-control |
| 装配 | 现有 `:phone-app:app` | `com.sheen.rokid.phone.app` | `HostCompositionRoot` 只做构造与注入 | 全部 |

建议目录（只列新增；旧 `actions/a11y`、`actions/shizuku` 保留）：

```text
phone-app/assistant/device-control/
  build.gradle.kts
  src/main/kotlin/com/sheen/rokid/device/
    port/            A11yTreePort / ShellPort / ScreenStatePort / UnlockSurfacePort
                     / ScreenAwakePort / ClockPort / ArtifactStorePort / VisionPort
    model/           Observation / ObservedWindow / Node / Ref / DisplayInfo
    observe/         ObservationBuilder / RefRegistry / DiffRenderer / TextBudget
    act/             ActionResolver / ActionDispatcher / SettlePolicy / FallbackPolicy / CoordinateSpace
    unlock/          UnlockStateMachine / UnlockLimiter / DeviceReadiness / UnlockError
    result/          DeviceResult / ErrorCode / ErrorLayer / Verification / Timing
    health/          DeviceCapability / ChannelHealth / CapabilitySet
    artifact/        ArtifactRef / ArtifactMeta
  src/test/kotlin/com/sheen/rokid/device/   （镜像主包；fake 端口）
```

命名规则按 `AGENTS.md` 的 0.22 规则：模块路径在 `phone-app/` 之下；包根用域 `com.sheen.rokid.device`，子包按上表；根包不放散文件（`DeviceControl.kt` 之类的门面放在 `com.sheen.rokid.device`，属于域根，和 `com.sheen.rokid.agent` 的既有做法一致）。**落地时必须在 0.22 规格 §5 模块清单登记** `device-control` 的 Gradle 路径、包名与依赖方向；本草案不改该文件。

### 1.2 a11y service 留在 app；端口/适配器这样切

**决定：`EdithAccessibilityService` 留在 `:phone-app:app`，不搬进库模块。**

理由：

1. 它是**系统绑定组件**：必须在 app 的 `AndroidManifest.xml` 里声明（`phone-app/app/src/main/AndroidManifest.xml:81-90`），配置资源是 app 的 `res/xml/edith_accessibility_config.xml`；搬到库模块只会多一层 manifest/resource merge，收益为零。
2. 它天然是**单实例、带系统生命周期**的（`onServiceConnected` / `onUnbind` / `onDestroy`，`A11yService:52-65`），JVM 测不到；把它做成内核只会把 Android 类型拖进纯模块。
3. **配置唯一权威**是硬要求（§5.4）。X-OmniClaw 的教训是源码里有两份同名配置（`XAppConfig` 与 `XObsConfig`），资源合并后哪份生效需要构建/设备验证；EDITH 不应复制这个结构。

切法：

- `EdithAccessibilityService` 只保留：`rootInActiveWindow` / `windows` / `dispatchGesture` / `performGlobalAction` / `takeScreenshot` / `serviceInfo` / `connected()`；**不再**自己做 BFS、序列化、diff、坐标决策。
- 新增 `A11yPlatformAdapter`（app 包）：把 `AccessibilityNodeInfo` 转成内核的 `RawNode`（只含 primitive），实现 `A11yTreePort` / `A11yActionPort`；所有调用走**单线程串行执行器 + Future 超时**（S1 已实测「读自己 App 会被自己主线程拖住」，`2026-10-16-a11y-capability-survey.md` §3.4）。
- `ShizukuShellAdapter`（app 包）：把 `PrivilegedShell.run` 的 `ShellResult` 映射为内核 `ShellOutcome`，保留 `failure` / `truncated` / `relinked`（`PrivilegedShell.kt:34-66`）。
- `UnlockActivity` 与 `ScreenAwakeLease` 在 app 包：Activity 是 Android 组件，WakeLock 是平台 API；内核只依赖 `UnlockSurfacePort` / `ScreenAwakePort`。
- 旧 `AccessibilityExecutionBackend` / `ScreenSnapshot` 在 D121 工具面替换完成前**保持不动**，避免 0.24 行为回归；新内核并行上线、由 D2 的 `device` 适配器接入，旧工具在 D2 切换完成后再删。

### 1.3 为什么不选「全放 app 包内」

1. **测试边界**：`device-control` 是 `kotlin("jvm")`，classpath 没有 `android.jar`，任何 Android import 都会编译失败；app 模块的 unit test 仍能偶然引用 Android 类型，边界靠纪律而不是编译器。
2. **依赖方向**：app → device-control 单向；内核不依赖 `plugin-api`，由 app 侧适配器把 `DeviceResult` 投影成 `ActionResult`。这样 D2 改工具 schema 不会反向污染内核。
3. **构建/测试成本**：观察/diff/解锁状态机会有大量纯 JVM 用例；放 app 会让每次测试都背着 Android 资源与变体。
4. **`HostCompositionRoot` 已经 2,939 行**（`HostRoot` 文件规模），再加内核会把装配层变成实现层。
5. **复用与演进**：debug 探针、未来 `shell`/`open` 工具、UI 诊断面板都可依赖内核；这是 0.25 之后继续演进的稳定内部 API。

反过来的边界也要守住：**不要把 Android 适配器再拆成第二个库模块**（0.25 不做）。`a11y`/`shizuku`/`unlock` 适配器留在 app，内核只定义端口；这样模块数只加一个，避免「为分层而分层」。

### 1.4 依赖与约束

- `device-control` 的 `build.gradle.kts`：`kotlin("jvm")`，`kotlinx-serialization-json`；测试用 `kotlin.test` + `common:testkit:test-support`（按现有 `agent-runtime` 的写法，`phone-app/assistant/agent-runtime/build.gradle.kts`）。
- **禁止** `device-control` 依赖 `common/contracts/plugin-api`：`ActionDescriptor` / `ActionResult` 是工具层契约，由 app 侧 adapter 映射；内核的 `DeviceResult` 是内部类型。
- 可选静态检查：在 `device-control` 的 test 里扫描 `src/main` 是否出现 `import android.`（廉价、可读）；真正的边界由模块 classpath 保证。
- 插件边界不变：`plugin-smart-helper` 不得 import `com.sheen.rokid.phone.*`；内核也不得被插件直接依赖。

### 1.5 迁移顺序（不改变 0.24 行为）

1. **M1（纯增量）**：建模块、模型、端口、fake 与 JVM 测试；不接生产。全量构建 + 全量 JVM 测试通过即可。
2. **M2（适配器）**：app 侧加 `A11yPlatformAdapter` / `ShizukuShellAdapter` / `UnlockActivity` / `ScreenAwakeLease`；用 debug-only probe 在 K40 上验证，不替换旧工具。
3. **M3（工具切换）**：按 D2 的 `device` schema 接内核；旧 `read_screen/tap/...` 与旧 backend 在 D2 验收通过后删除；`AppliedSemanticsPostProcessor` 同时退役（见 §4.4）。
4. **M4（清理）**：删旧 `ScreenSnapshot`/`currentSnapshot` 状态，确认无残留引用；更新 0.21 基线规格与 0.22 模块清单。

---

## 2. Observation 模型

### 2.1 数据形状（内核类型，非最终 JSON）

```kotlin
data class Observation(
    val revision: Long,              // 每次 observe +1；只读观察也 +1
    val generation: Long,            // 每次 mutating 动作/设备状态变化 +1
    val takenAtElapsedMs: Long,
    val display: DisplayInfo,        // width/height/rotation/densityDpi
    val windows: List<ObservedWindow>,
    val nodes: Map<Ref, Node>,       // 含所有窗口，document order 另存
    val order: List<Ref>,
    val truncated: Boolean,
    val omitted: Omitted?,           // 节点上限/字节上限
    val incomplete: Incomplete?,     // nullChildren 等「没读全」信号
    val source: ObservationSource,   // TREE / SHELL_DUMP（只进审计）
)

data class ObservedWindow(
    val windowId: Int,
    val type: WindowType,            // APPLICATION / SYSTEM / INPUT_METHOD / ACCESSIBILITY_OVERLAY / UNKNOWN
    val layer: Int,
    val packageName: String?,
    val activity: String?,
    val title: String?,
    val bounds: IntRect,
    val active: Boolean,
    val focused: Boolean,
    val nodes: List<Ref>,
)

data class Node(
    val ref: Ref,                    // 模型可见 "#n16"
    val parent: Ref?,
    val depth: Int,
    val indexInParent: Int?,
    val childCount: Int,
    val role: Role,                  // button/text/edit/switch/listitem/...（由 class+actions 推导）
    val className: String?,
    val packageName: String?,
    val viewId: String?,             // 需 flagReportViewIds；可能为 null
    val text: String?,
    val textTruncated: Boolean,
    val desc: String?,
    val hint: String?,
    val isHint: Boolean,             // 空输入框的 .text 可能是 hint（S1 A06）
    val bounds: IntRect,             // 内部用；默认不进模型文本（D84）
    val states: Set<NodeState>,
    val actions: Set<NodeAction>,    // getActionList 的白名单映射
    val collection: CollectionInfo?,
    val range: RangeInfo?,
    val windowId: Int,
    val visibleToUser: Boolean?,
    val password: Boolean,           // true 时不序列化 text
    val actionable: Boolean,         // shell dump 来源=false
)

data class Ref(
    val value: String,               // "#n16"
    val revision: Long,              // 铸造它的观察
    val generation: Long,            // 铸造时动作代际
    val identity: String,            // 内部稳定身份，不暴露
)
```

`Role` 的推导是确定性的（className + actions + collection/range），不做语义猜测：`EditText/AutoCompleteTextView → edit`；`Button/ImageButton → button`；`Switch/SwitchCompat/CheckBox/RadioButton → switch/checkbox/radio`；`TextView` 且有 click → `button`，否则 `text`；`RecyclerView/ListView/ScrollView → list`；有 `CollectionItemInfo → listitem`；`SeekBar/ProgressBar → seekbar/range`；`WebView → webview`；`SurfaceView/TextureView → surface`；`TYPE_INPUT_METHOD` 窗口 → `keyboard`；其余 `unknown`。角色只影响可读性和统计，**不参与寻址**（寻址靠 ref）。

### 2.2 字段语义与证据

| 字段 | 来源 / 规则 | 证据/边界 |
|---|---|---|
| `role` | 上述确定性映射 | 参考 X-OmniClaw `RefNode.role` 与 `SnapshotBuilder`；不是平台字段 |
| `viewId` | `getViewIdResourceName()`；需 `flagReportViewIds` 才能**读回**名字 | S1：`withFlag_visibleIds=24` vs `noFlag=0`；但按 id **查找**在无 flag 时也命中 13/13（`2026-10-16-a11y-capability-survey.md` §2.2 X08），所以「有 id」与「能按 id 找」不是一回事；系统应用 13 个节点共用 `android:id/title`，id 不能单独当身份 |
| `text` | `.text`；`isEditable && text == hintText` 时置 `isHint=true`，不把 hint 当值 | S1 A06：清空后 `.text` 读到 9 字符 hint；只读 `.text` 无法区分「空」与「hint」 |
| `desc` | `.contentDescription` | `findAccessibilityNodeInfosByText` 也匹配 desc（S1 T04）；新寻址不依赖该 API |
| `bounds` | `getBoundsInScreen()`；内部坐标，display px | 语义点击不依赖 bounds；手势/视觉需要。D84 下默认不进模型文本 |
| `states` | `isEnabled/isClickable/isLongClickable/isEditable/isScrollable/isCheckable/isChecked/isSelected/isFocused/isVisibleToUser` | S1：`isVisibleToUser` 只有 35/253（被裁剪的节点仍在树里、仍可寻址）；`isEnabled=false` 的点击返回 false（A11） |
| `actions` | `getActionList()` 映射到白名单：`click/long_click/set_text/paste/focus/clear_focus/scroll_forward/scroll_backward/scroll_up/down/left/right/show_on_screen/expand/collapse/select/clear_selection/dismiss/context_click/press_and_hold/ime_enter` | S1：`ACTION_SHOW_ON_SCREEN/EXPAND/COLLAPSE/SELECT` 实测可用；`ACTION_CHECK/UNCHECK` 不存在（编译期）；第三方 App 是否声明 expand/collapse 未验证 |
| `collection` | `getCollectionItemInfo()`：row/col/span/heading | 列表/网格定位辅助；S1 只测了形状，未测所有 App |
| `range` | `getRangeInfo()`：type/min/max/current | 滑杆/进度；K40 API 33 可用 |
| `window` | `getWindows()`；需 `flagRetrieveInteractiveWindows` | S1：同一屏有 flag=3 / 无 flag=0；IME 窗口 `TYPE_INPUT_METHOD` 只有这里可见（A23）；悬浮窗不在 `rootInActiveWindow`（O01） |
| `password` | `isPassword()`；true 时 `text=null`、只留标记 | 凭证红线（`AGENTS.md` 不记录凭证）；屏幕内容上传放宽不覆盖密码框正文；若要连密码框上传，需业主单独裁决 |
| `source` | TREE / SHELL_DUMP；只进审计，不进模型 | §5.5 降级矩阵 |

### 2.3 稳定 ref 的生成与失效

**目标**：同一个元素在相邻观察中保持同一个 `#n`，这样 diff 是「完整更新」而不是「重新编号」；模型上一轮看到的 ref 在没发生动作时仍然可用。

**身份（identity）**：启发式，必须可解释、可回退。

```text
identity = sha1(
  windowId | (viewId ?: className + "|" + role) |
  pathSignature(depth <= 12 的 child index 序列) |
  boundsBucket(16dp) |
  (viewId == null && !editable ? textOrDescHash : "")
)
```

- 有 `viewId` 的节点：不把 text 放进 identity（输入文字变化不应换 ref）。
- 无 `viewId` 的 `editable`：不把 text 放进 identity；用 class/role/path/bounds。
- 无 `viewId` 的纯文本节点：text 只作 tiebreaker；文本变化可能换 ref，这是可接受的（宁可 stale，不可误点）。
- 列表项滚动导致 path 变化：identity 会变，旧 ref 失效；由 diff 报 `removed/added`，模型重新拿 ref。**不做跨屏自动重绑**（除 §2.3 的受限 rebind）。

`RefRegistry` 规则：

1. 每个 identity 映射到一个 `#n<monotonic>`；ref 永不复用；节点消失后保留一个观察周期的墓碑用于 diff，然后丢弃。
2. 新观察中 identity 命中 → 复用 ref；未命中 → 铸新 ref。
3. **动作有效性**：`ref.generation == currentGeneration` 且该 ref 在最新观察中仍存在（identity 命中），才可直接执行；否则按 stale 处理。
4. **受限 rebind**：仅当 `ref.generation` 是上一代、前台包/窗口未变、观察未 truncated、ref 年龄 ≤ 20s、且新节点满足 `role` 相同 + `viewId` 相同（若有）+ 精确 text/desc 相同（若有）+ 几何中心距离在 `max(旧宽,新宽)+40px` / `max(旧高,新高)+40px` 内，才自动重绑一次；结果里带 `rebound=true` 与新 ref。否则 `NODE_STALE` 并回最新观察。
   - 这是 X-OmniClaw `isSafeToReuseSameRef` / `tryRebindRefByExactText` 的同类规则（`RefManager.kt:1706-1774`），不是照搬其业务特判。
5. **失效触发**：任何 mutating 动作（先 bump generation）、解锁/设备状态变化、前台/窗口变化、旋转/显示变化、IME 显隐导致布局变化、a11y 服务重连、观察 truncated（截断外的 ref 不存在）、ref 年龄超过 `REF_MAX_AGE_MS`（建议 20s，参考 `DeviceTool.kt:59`）、显式 `observe(forceFull)`。
6. **截断语义**：`truncated=true` 时，未列出的节点 **不等于** 被删除；`removed` 一律不报；ref 只对列出的节点有效。
7. **观察不完整**：`nullChildren > 0` 时置 `incomplete=true`，短延迟重读一次；仍不完整则 `truncated=true` 并在结果里说明。S1 的 `nullChildren=0` 是「这遍读全了」的凭证（`2026-10-16-a11y-capability-survey.md` §3.4）。

### 2.4 revision / generation

- `revision`：观察序号，从 1 开始；每次成功 `observe` +1；只读观察也 +1；用于 diff 的「since revision」和 ref 年龄。
- `generation`：动作代际，从 0 开始；**任何可能改变 UI 的动作/设备变化** +1：语义动作、手势、shell input、`open`、`back/home`、解锁、窗口/前台变化检测、服务重连、强制刷新。
- `Observation` 带 `revision` 与 `generation`；`Ref` 带铸造时的两者。
- 只读观察只改 `revision`，不改 `generation`；旧 ref 若 identity 仍命中，仍可用（动作有效性规则见 §2.3）。
- 动作的校验顺序是：先按**当前** generation 校验 ref → 校验通过后、dispatch 前 `generation++`（避免并发观察把旧 ref 当成新状态）→ dispatch → settle + observe，得到新 `revision` 与新 refs。校验与 bump 不能颠倒，否则合法 ref 会被自己刚 bump 的代际判成 stale。
- 观察缓存只保留**最新一份**；历史树不缓存（D117：不落盘、不记正文；内存中旧树只用于本次 diff，diff 后丢弃）。

### 2.5 全量 vs diff

- **全量**：每回合第一次观察、解锁后第一次观察、前台/窗口变化、旋转、`truncated` 状态变化、距上次观察超过 `FULL_REFRESH_MS`（建议 2 分钟，待校准）。
- **diff**：其余情况，按 ref 比较：
  - `added`：新观察有、旧观察无；
  - `changed`：同 ref 但行内容不同（保留 `(was: ...)`）；
  - `removed`：旧观察有、新观察无；**仅当新观察 `truncated=false` 才报**；
  - `unchanged`：显式一句，并说「ids 仍有效」。
- **阈值**：diff 行数 ≥ 60 或变化行数 > 新观察节点数的 50% → 改发全量，头部写「screen changed substantially」。参考 e2e `MAX_DIFF_LINES=60` / `MAX_DIFF_SHARE=0.5`（`screen-update.ts:27-30`、`:166-168`）。
- **焦点噪声**：diff 行去掉 `focused` 状态（焦点每次动作/自动都可能移动；e2e `withoutFocus`，`screen-update.ts:326-337`）；全量行保留 `focused`。
- **动作后无变化**：`expectChange=true` 且 diff 为空且未截断 → 明确说「没有可见变化，不要重复同一动作」；这与「屏幕没变但操作生效」的视觉型界面区分开（e2e `renderUnchanged`，`screen-update.ts:294-308`）。
- **键盘关闭提示**：动作前有 IME、动作后没有、且未截断 → 说「这次动作可能被用于收起键盘；如果目标无效果，键盘收起后再点一次」（e2e `keyboardClosedNote`，`screen-update.ts:311-315`）。
- **截断时**：diff 只能是「部分更新」，头部必须写明 `truncated`；不报 removed。
- 事件只作加速器：`TYPE_WINDOW_STATE_CHANGED` 可能领先树 0.03–0.7s，但一次真实变更可能零事件（S1 X00/E01）；真值始终是树 + 轮询。静默判定参考 508ms（S1 E02），但**不要**把它当固定产品值，按 App 校准。e2e 的 settle 表把动作分成「要等变化」和「只要读一眼」两档（`E2ESettle:62-106`），这个分类法与本节一致，可参考其 `changeWaitMs` / `look` 的分离，但数值不能直接搬到 Android。

### 2.6 多窗口 / IME / 悬浮窗

- `getWindows()` 是唯一正确入口；按 `layer` 降序；每窗口一个 section：`windowId/type/package/activity/title/bounds/active/focused`。
- 活跃窗口 + 焦点窗口单独标注；`rootInActiveWindow` 只用于「快速看一眼前台」，不能代替窗口枚举。
- IME：窗口类型 `TYPE_INPUT_METHOD`；模型文本单独一段 `IME`；`ime=true`；输入目标仍是 editable 节点；`ACTION_IME_ENTER` 可作回车（API 30）。
- 悬浮窗：`TYPE_SYSTEM`/`TYPE_APPLICATION_OVERLAY`；S1 O01 证明它只在 `getWindows()` 里可见，且 `rootInActiveWindow` 看不到。**遮挡判定**：如果目标节点不在 active 窗口、或被更高 layer 的窗口覆盖，语义点击可能仍生效（S1 O02），坐标点击可能被吃（S1 O03）；因此：
  - 语义动作：允许，按节点执行；
  - 坐标/手势：先检查目标窗口是否 active/focused；不是则拒绝（`WINDOW_NOT_ACTIONABLE`），不赌。
- 多显示器：`getWindowsOnAllDisplays()` API 30；K40 单屏，0.25 不做；`takeScreenshotOfWindow` API 34，K40 不可用（预研附录 A）。

### 2.7 截断 / 预算 / 长文本

- 节点上限 `MAX_NODES=400`（沿用 `ScreenSnapshot.kt:94`）；超过即 `truncated=true`，`omitted.count` 能算则算，不能算则写 `unknown`。
- 模型文本预算：**≤16KB / 400 行**（预研 §8.3；e2e 的观察预算按「剩余 token 反推字节预算」的做法可参考 `E2EBudget:63-72`）；单节点 `text` ≤256 字符、`desc` ≤160 字符；超出用 `…(+N chars)` 并置 `textTruncated=true`。
- 头部 ≤1KB：`revision/generation/app/activity/ime/rotation/windows/truncated`。
- 截断只能发生在**行边界**；如果 D2 选择 JSON 作为模型载荷，必须保证 JSON 合法并在字段里标 `truncated`，不能截半个 JSON。
- 长文本平台上限未测（S1 §6 #3）；本设计只做产品侧预算，不声称平台行为。
- 读自己的 App 会被主线程拖住（S1 §3.4）：`nullChildren>0` 或超时 → `incomplete`；不要把它读成「控件不存在」。
- shell dump 来源：`uiautomator dump` 2,283ms、不能读自己、并发 dump 互斥（Shizuku 调研 §2.2）；解析成同一模型但 `actionable=false`，动作只能走坐标/按键。

### 2.8 settle 策略（按动作类型）

> 数值是**起始值**，不是实测产品值；要按 K40 校准。事件是加速器，树是判据。

| 动作 | 期望 | settle 策略（建议） |
|---|---|---|
| `tap` / `double_tap` / `long_press` / `press_key` / `back` / `home` | 窗口/前台/节点状态变化 | 先等事件或轮询，最多 2,000ms；看到变化后再「连续两次观察一致」（间隔 ~250ms）确认稳定；看不到变化则等满 2,000ms 后报 `ACCEPTED_NO_EFFECT` |
| `type` / `set_text` | 目标节点文本/状态变化 | 最多 2,000ms；看到文本变化即读（after-change），不要求「保持输入」；读回不等于 → `TEXT_MISMATCH` |
| `scroll` / `scroll_to` | range/首可见项变化 | 400–500ms；`scroll_to` 每滚一次观察一次，最多 N 次（建议 6），找到即停 |
| `open` | 前台/窗口变化 | 最多 3,000ms（`am start -W` 实测 105–203ms + 应用冷启，Shizuku 调研 §2.3）；超时则 `accepted_only` |
| `wait` | 条件 | 由 D2 的 schema 定上限；内核只提供有界等待 |
| `screenshot` | 无 | 不 settle；遵守 a11y 截图约 1 张/秒的限流（**文档/代码级，K40 未实测**） |
| `observe` | 无 | 不 settle；只读不 bump generation |
| 解锁 | 见 §6 | 单独状态机，不混入动作 settle |

- 事件类型：建议 `typeAllMask` 接收后在代码里过滤（S1 的配对实验说明「声明什么决定收得到什么」）；`notificationTimeout=100` 保留。
- 静默判定：S1 实测 508ms（250ms 阈值）可判定一次变更后的静默；这只作参考，不作为固定常量。
- 每次动作后**恰好一次**观察，diff 放进结果；不要「动作成功就不观察」。

### 2.9 模型可见投影（D2 最终定，这里给内核契约）

- 默认文本行；**不输出通道名、bounds、像素坐标**（D84）。示例：

```text
[win 2] app com.android.settings/.Settings active focused
#n1 list "设置" [scrollable] actions=[scroll_forward]
#n2 listitem "WLAN" id=android:id/title [clickable] actions=[click,long_click]
#n3 switch "WLAN" [checked clickable] actions=[click]
IME: com.baidu.input_mi/.ImeService (keyboard) ime=true
```

- 只输出：ref、role、text/desc、viewId、states、actions、collection/range；`password` 只输出标记。
- 截图：只给 artifact id + 宽高/缩放；不内联 base64。模型能否真正「看到」图片，取决于 D3 的上下文/历史契约并与 D2 的图片分片对齐（当前 `ModelHistoryItem.ToolOutput` 只有 `String`，`ModelServices.kt:101`；适配器也尚未编码图片，预研 §3.5）。
- bounds/坐标仅供内核解析与视觉映射；若 D2 决定在工具参数/结果里暴露它们，必须先改 D84（见 §8 Q1）。

### 2.10 并发与超时（0.25 最小版）

- **写路径**：单一 FIFO 串行。动作、解锁、`open` 都排在一条队列上；`generation` 在队首递增，后一个动作永远看到前一个动作后的观察。参考 e2e `OperationQueue` 的「调用顺序串行、失败不毒化队列」模型（`E2EQueue:16-29`）与预研附录 F。
- **a11y 调用**：所有 `AccessibilityNodeInfo`/`dispatchGesture`/`takeScreenshot` 调用走**单线程执行器**；每个操作带独立 Future 超时（建议 3s；截图 2s）。超时返回 `ACTION_TIMEOUT`/`OBSERVE_TIMEOUT`，并把通道标记 `DEGRADED`；不要无限等待，也不要因为一次超时就宣称通道死亡。
- **读路径**：0.25 先**全部串行**（包括 observe）。预研附录 F 的「读可并行 + generation 屏障」作为后续优化；当前 `EdithAccessibilityService` 有 `currentSnapshot` 可变状态，并行读会互相覆盖，收益不足以抵消风险。
- **shell 读**：可并行 ≤2 条（`pm list`/`settings get`），但 `uiautomator dump` 是单例，必须独占（Shizuku 调研 §2.2 的并发 dump 失败是实测）；shell 写动作仍进 FIFO。
- **取消**：回合取消/急停要传到队列与在途操作；a11y 的阻塞调用无法强杀线程，取消后把通道标记 `DEGRADED`，后续动作快速失败直到一次成功探针。
- **超时不等于失败已发生**：结果里要写「是否可能已生效」（`maybeApplied=true`），避免模型重复执行不可逆动作。

---

## 3. 动作模型

### 3.1 解析顺序

1. **参数/ref 校验**：`ref` 必填（除全局动作）；`ref` 必须在最新观察里；`generation` 匹配，否则 stale/rebind（§2.3）。
2. **语义节点动作优先**：在目标节点或最近祖先上找所需 action（click/long_click/set_text/scroll/select/...）；S1 已实测「承载文字的节点本身通常不可点，祖先可点」（A01 `carriedClickable=false ancestor=true`）。
3. **`dispatchGesture`**：仅当语义路径不存在（`getActionList` 没有该 action / 无可点祖先 / 目标在 SurfaceView）或语义路径返回「不支持」；坐标取节点 bounds 中心或安全区内点。
4. **shell 兜底**：`input tap/swipe/keyevent/text(ASCII)`；只在手势不可用/失败且 shell ready 时；屏幕必须 on + unlocked。
5. **最后手段**：`uiautomator dump` 定位 + shell input（2,283ms、不能读自己、并发互斥）；或视觉端口（若 D3 已支持图片输入）。
6. **无论成败**：settle + 一次观察；失败动作也回观察（§3.6）。

**绝不降级的情形**（这些是诚实失败，不是「换条路」）：

- `NODE_STALE` / `NODE_NOT_FOUND`：先重观察，不盲点；
- `NODE_DISABLED`：S1 A11 实测点击禁用控件返回 false，坐标点击也不应绕过；
- 滚动到边界：S1 A17 `retAtEnd=false`，`SCROLL_AT_BOUNDARY` 是答案；
- 非 editable 节点 `SET_TEXT`：S1 A07 `ret=false`；
- 未聚焦 `PASTE`：S1 A08 `ret=false`（PASTE 不是 SET_TEXT 的替代）；
- 被遮挡的坐标点击：S1 O03 会打到遮挡者；有语义路径时不许用坐标。

### 3.2 各动作的语义路径

| 动作 | 语义 | 手势 | shell 兜底 | applied / verified |
|---|---|---|---|---|
| `tap` | 最近 clickable 祖先 `ACTION_CLICK` | 单点，60–200ms | `input tap x y` | applied=树/前台有变化；verified=`screen_change` 或明确的前台/节点谓词；无谓词则 `none` |
| `double_tap` | 平台无双击 action | 一个 GestureDescription 两段不重叠 stroke（S1 A21 实测两击都落地） | 两次 `input tap` + 间隔（未测） | 同 tap |
| `long_press` | `ACTION_LONG_CLICK` | 600ms 单点 | `input swipe x y x y 600`（未测） | 同 tap |
| `swipe` | 无 | 两段 path | `input swipe` | applied=树变化；无变化不重试同点 |
| `scroll` | 目标 `isScrollable` 节点的 `ACTION_SCROLL_FORWARD/BACKWARD`（**不是「树里第一个 scrollable」**） | 区域内滑 | `input swipe` | applied=range/首可见项变化；边界 false → `SCROLL_AT_BOUNDARY` |
| `scroll_to` | 目标 scrollable 节点反复滚 + 观察 | 同 scroll | 同 scroll | 找到文本/id 即 applied；N 次未找到 → `TARGET_NOT_FOUND` |
| `set_text` | `ACTION_SET_TEXT`（**不需要焦点**，S1 A05） | 无 | 仅 ASCII `input text`；中文不可用（S1：`Virtual.kcm` 无 CJK） | 读回目标 `.text` 等于（注意 hint）；否则 `TEXT_MISMATCH` |
| `clear_text` | `ACTION_SET_TEXT("")` | 无 | 无 | 读回 `isHint`/长度，不把 hint 当空串（S1 A06） |
| `focus` | `ACTION_FOCUS` | 无 | 无 | `findFocus` 读回 |
| `press_key` | `performGlobalAction(HOME/BACK/RECENTS/NOTIFICATIONS)`；`ACTION_IME_ENTER` 回车 | 无 | `input keyevent` | HOME 可靠（S1 G03）；BACK 顶层未定论（S1 G02）；`none` 要如实标 |
| `show_on_screen` | `AccessibilityAction.ACTION_SHOW_ON_SCREEN.id` | 无 | 无 | 节点 `visibleToUser` 变 true |
| `expand/collapse/select` | 节点 action | 无 | 无 | 读回 expanded/selected；第三方是否声明**未验证**（S1 A20 仅夹具） |
| `check/uncheck` | 无独立 action；`ACTION_CLICK` 切换 + 读 `isChecked` | 无 | 无 | `isChecked` 等于目标值（S1 A12） |
| `open` | 不在 `device` 动作集；属 `open` 工具（D2） | — | shell `am start -W` / App 内 intent | 由 `open` 工具自定 |

### 3.3 坐标兜底：0–1000 归一化与映射

**用途**：内核内部的坐标路径（节点无可用语义 action、SurfaceView、视觉 grounding 返回点）；0.25 **不**对模型暴露 `x/y` 参数（D84）。

**定义**：`NormalizedPoint(x, y)`，`0..1000` 整数，原点左上，x 向右，y 向下，相对于**当前 display**（旋转已应用）或某个 artifact 声明的 viewport。

**映射**（display px）：

```text
px = clamp(round(nx * (width  - 1) / 1000.0), 0, width  - 1)
py = clamp(round(ny * (height - 1) / 1000.0), 0, height - 1)
```

- `nx=0` → 0；`nx=500` → `round((w-1)/2)`；`nx=1000` → `w-1`。宽 1081 时 1000 → 1080，不越界。
- **反向**：视觉模型若返回的是缩放截图像素，先在 artifact 的 `imageWidth/imageHeight` 内归一化到 0–1000（或直接换算到 display px），再映射；不要在缩放图和 display 之间混用。
- **viewport**：artifact 元数据必须写 `coordinateSpace=display-px|image-px`、`width/height/scale/sourceBounds`；手势用的是 display px。
- **旋转**：观察带 `rotation`；动作前若旋转变了，旧观察/旧 ref 一律 stale，重观察后再算。
- **校验**：外部输入越界 → `INVALID_ARGUMENTS`/`COORDINATE_OUT_OF_RANGE`；内部计算 clamp；不要静默接受模型的像素值。
- **遮挡**：坐标点必须假定「可能打到遮挡者」（S1 O03）；执行后必须验证；无变化时不得在同一坐标重试（参考 X-OmniClaw 二次点击最小距离 24px、置信度 ≥0.45，`DeviceTool.kt:75-76`、`:882-883`）。
- **测试**：0/500/1000、奇数宽高、1px、旋转 90/270、缩放 artifact 0.711、屏幕密度无关（归一化本身不依赖 dpi）。

### 3.4 动作后自动观察

- 每个 mutating 动作：校验 ref → `generation++` → dispatch → settle → `observe()` → 计算 diff → 放进结果。
- 只读动作（observe/status）不 `generation++`，不自动再观察。
- 动作被拒（stale/invalid/channel down）且未执行：不 `generation++`；若还能读，回最新观察（或明确说明不能读）；不要伪造「动作后观察」。
- 解锁/设备状态变化后：`generation++`，下一次观察强制全量，旧 ref 失效（§6.6）。

### 3.5 accepted / applied / verified 三级语义

| 级别 | 定义 | 证据 |
|---|---|---|
| **accepted（已受理）** | 平台/通道接受了这次操作 | a11y `performAction` 返回 true；`dispatchGesture` `onCompleted`；shell `failure==null && exitCode==0`；`startActivity` 未抛异常 / `am start` 输出 `Status: ok` |
| **applied（已生效）** | 动作后独立观察到了变化（**方向/目标不一定对**） | 树 revision/fingerprint、前台/窗口、节点状态、range 变化；shell 写操作用 `TargetState` 重读（`ShizukuExecutionBackend.kt:177-231`） |
| **verified（已验证）** | 变化**符合动作意图**的谓词 | tap/滚动/打开的前台/节点/文本/range 谓词；没有谓词就 `verified.kind=none`，不冒充「已验证」 |

- `accepted=true` 不代表成功：S1 A09 实测 `ret=true` 但文本没变；A17/A24/A11/A07 给出可信的 `false`。所以「false 可信、true 不可信」这条要保留。
- `applied` 是三态：`true` / `false` / `null`（无法观察，例如 Intent fire-and-forget 或 shell 命令无状态可读）。`null` 时只报 `accepted_only`，不报成功。
- `verified` 是对象：`{kind: screen_change|foreground|node_state|text|range|none, expected?, actual?, revision}`；`screen_change` 是最弱的一档，只说明「变了」，不说明「变对了」。
- `ok` 的定义：无错误，且（只读成功 或 `applied != false`）；`applied==null` 时 `ok=true` 但 `acceptedOnly=true`，不宣称生效。
- 映射到 `ActionOutcome`：`Done` 只在 `ok && applied!=false`；`Failed` 用于「已执行但 `applied=false` 或 verify 失败」；`Refused` 用于「未执行」（通道/设备/定位/参数）。

### 3.6 失败动作也回观察

- 已执行但无效果（`ACCEPTED_NO_EFFECT`）、验证超时、部分滚动失败：回**动作后的观察**和 diff，让模型能改路径；不要让模型「失败了还得再花一次 observe」。
- stale/not-found：回最新观察 + 新 refs（如果有），错误里带候选 refs；不要回旧树。
- 通道/设备不可用：不能观察就不回树，错误里说明「未执行」和恢复建议；不要伪造空树。
- 回退路径失败：结果里带 `fallback: {tried:["gesture","shell"], exhausted:true}`，让模型知道「已经试过哪些路」。

### 3.7 回退资格矩阵（重要）

| 失败/情形 | 语义 → 手势 | 手势 → shell | 说明 |
|---|---|---|---|
| `NODE_STALE` / `NODE_NOT_FOUND` | 否 | 否 | 先重观察；盲点会点错节点 |
| `NODE_DISABLED` | 否 | 否 | 诚实失败（S1 A11） |
| `NODE_NOT_ACTIONABLE`（无可点祖先/无 action） | 是（bounds 中心） | 是 | 仅目标在 active 窗口且可见 |
| `performAction` 返回 false（非 disabled/stale，且 action 不在 `getActionList`） | 是 | 是 | 平台确实没有这个语义动作 |
| `ACCEPTED_NO_EFFECT` | 是，**最多一次** | 是，最多一次 | 换点/换通道；结果带 fallback 记录；不得同点重试 |
| `GESTURE_CANCELLED` | 否 | 是 | 记录 `onCancelled`（S1 未构造取消场景，未测） |
| 手势不可用（无 `canPerformGestures`） | 否 | 是 | `CAPABILITY_NOT_CONFIGURED` 或 shell |
| shell 不可用 | — | 否 | 回到语义/手势；不要用 dump 硬点 |
| 目标被遮挡且只能坐标 | 否 | 否 | 语义可穿遮挡（O02），坐标会被吃（O03）；先找语义节点 |
| 屏幕 off / locked | 否 | 否 | `ensureReady()` 先处理（§6） |

**视觉兜底的触发条件**（按业主前提，不在上表里当「通道回退」）：

1. 树为空或几乎没有可交互节点（0 interactive nodes）；
2. 树里出现 `SurfaceView`/`TextureView`，且目标不在树中；
3. 语义/结构 ground 的置信度低于阈值（阈值由视觉 provider 定）；
4. 语义动作 + 手势动作都 `ACCEPTED_NO_EFFECT`，验证失败。

触发后的顺序是：**先截图 artifact** → `VisionPort.ground(target, artifact)` → 返回 `NormalizedPoint`（§3.3）→ 手势；视觉结果同样要 `verified`，不能「VLM 说点了」就算成功。若 `VisionPort` 未接（当前 `ToolOutput` 只有 String、适配器未编码图片），返回 `UNSUPPORTED`/`VISION_UNAVAILABLE` + 截图 artifact 或文本观察，**不盲点**；这条与 §8 Q4 对应。

---

## 4. 结果 envelope

### 4.1 JSON 形状（canonical `DeviceResult`）

> 本节是**内核**的 canonical 形状；D2 的工具面 envelope 负责对模型/宿主的最终包装（raw/wrapped、图片分片、action schema 映射），以 D2 为准。D1 的字段是两者的公共子集，D2 增字段不得改变这里的语义（`accepted/applied/verified`、`truncated`、无 base64）。

成功（带 diff）：

```json
{
  "v": 1,
  "ok": true,
  "action": "tap",
  "target": "#n16",
  "channel": "accessibility",
  "accepted": true,
  "applied": true,
  "verified": {
    "kind": "screen_change",
    "revision": 42,
    "detail": "foreground=com.android.settings/.Settings"
  },
  "screen": {
    "revision": 42,
    "generation": 7,
    "foreground": {"package": "com.android.settings", "activity": ".Settings"},
    "ime": false,
    "rotation": 0,
    "truncated": false,
    "changes": {
      "mode": "diff",
      "added": ["#n88 switch \"WLAN\" [checked]"],
      "updated": ["#n12 text \"已连接\" (was: \"正在连接\")"],
      "removed": []
    }
  },
  "artifacts": [],
  "timing": {
    "precheckMs": 2,
    "unlockMs": 0,
    "resolveMs": 0,
    "actMs": 22,
    "settleMs": 410,
    "observeMs": 47,
    "totalMs": 481
  },
  "error": null
}
```

失败（已执行但无效果）：

```json
{
  "v": 1,
  "ok": false,
  "action": "tap",
  "target": "#n16",
  "channel": "accessibility",
  "accepted": true,
  "applied": false,
  "verified": {"kind": "none", "revision": 43},
  "screen": {
    "revision": 43,
    "generation": 8,
    "truncated": false,
    "changes": {"mode": "unchanged", "added": [], "updated": [], "removed": []}
  },
  "artifacts": [],
  "timing": {"precheckMs": 1, "unlockMs": 0, "actMs": 21, "settleMs": 2010, "observeMs": 41, "totalMs": 2073},
  "error": {
    "code": "ACCEPTED_NO_EFFECT",
    "layer": "act",
    "retryable": false,
    "reason": "screen_unchanged_after_settle",
    "hint": "不要重复同一动作；重新观察或换目标/换路径"
  }
}
```

截图 artifact（不内联）：

```json
{
  "artifacts": [
    {
      "id": "art-7f3c",
      "kind": "screenshot",
      "coordinateSpace": "display-px",
      "width": 1080,
      "height": 2400,
      "scale": 0.711,
      "sourceBounds": [0, 0, 1080, 2400],
      "bytes": 203804,
      "sha256": "…",
      "ttlMs": 600000
    }
  ]
}
```

### 4.2 字段与不变量

| 字段 | 说明 |
|---|---|
| `v` | envelope 版本，先 `1`；形状变更才升 |
| `ok` | 无错误且（只读成功 或 applied=true）；`acceptedOnly` 单独标 |
| `action` / `target` | 动作名；`target` 是 ref（或 null 全局动作）；不含 bounds/坐标 |
| `channel` | `accessibility / shell / intent`，**只给宿主/审计**；模型投影默认剥离（D84） |
| `accepted` / `applied` / `verified` | §3.5；`applied` 可 null |
| `screen` | 最新观察 + diff；`revision/generation/foreground/ime/rotation/truncated/changes` |
| `artifacts` | 0..n 个引用；不内联 base64；不含本地绝对路径 |
| `timing` | 各阶段墙钟毫秒；`unlockMs` 0 表示未走解锁 |
| `error` | `code/layer/retryable/reason/hint`；成功为 null |
| `fallback` | 可选：`{tried:[...], exhausted:bool}`；让模型知道已经试过哪些路 |
| `acceptedOnly` | 可选：`applied=null` 时 true |

不变量：

- `ok=false` ⇔ `error!=null`。
- `applied==true` ⇒ `accepted==true`。
- `verified.kind!=none` ⇒ `applied!=false`。
- `screen.truncated==true` ⇒ `changes.removed==[]`。
- `screen.revision` 必须等于本次实际观察的 revision；不许用旧 revision 充数。
- `artifacts` 里没有 `base64`/`data:`；没有原始屏幕正文进证据。
- `channel` 不进模型投影；`target` 不暴露 bounds/像素坐标（D84）。
- 文本载荷 ≤16KB/400 行；超限截断到行边界并标 `truncated`。

### 4.3 错误码分层

| 层 | 错误码 | 含义 | 恢复建议 |
|---|---|---|---|
| device（前置） | `DEVICE_SCREEN_OFF` | 屏幕灭；未尝试解锁或本轮不允许 | 等用户/下一轮；不要反复唤醒 |
| device | `DEVICE_LOCKED` | 无安全锁屏但当前锁着；未解锁或用户拒绝 | 让用户解锁；同轮不重试 |
| device | `DEVICE_LOCKED_SECURE` | `isKeyguardSecure()==true`；按业主口径直接不可用 | 不绕过；提示用户移除安全锁屏（不弹密码/图案） |
| device | `UNLOCK_FAILED` | 已尝试但失败（`reason=surface_unavailable / timeout / callback_error / verify_failed / user_dismissed / tree_systemui_only`） | 结束本轮设备动作；不要抢手机 |
| device | `SHIZUKU_LOST_AFTER_WAKE` | 唤醒后 Shizuku 服务/shell 不可用且 rebind 失败 | 提示用户重开 Shizuku/接线；a11y-only 降级 |
| channel | `CHANNEL_UNAVAILABLE` | 通道当前不可用（a11y 关、shell 死） | 换通道或请用户开启 |
| channel | `SHELL_NOT_BOUND` / `SHELL_CARRIER` / `SHELL_REPLY_TOO_LARGE` / `SHELL_PARSE` | 既有 `ShellFailure` 三类 + 解析 | 大输出分页/写文件；换通道 |
| capability | `UNSUPPORTED` | 本设备/构建根本不具备（API 级别、产品未实现） | 不重试；换任务路径 |
| capability | `CAPABILITY_NOT_CONFIGURED` | 能力存在但 a11y 配置未声明（gestures/screenshot/windows/ids） | 更新配置并重连服务；不要假装支持 |
| locate | `NODE_STALE` / `NODE_NOT_FOUND` / `AMBIGUOUS_TARGET` / `NODE_DISABLED` / `NODE_NOT_ACTIONABLE` | 目标问题 | `NODE_NOT_ACTIONABLE` 附最近可点祖先 ref；stale 附新 refs |
| act | `ACCEPTED_NO_EFFECT` / `SCROLL_AT_BOUNDARY` / `GESTURE_CANCELLED` / `FALLBACK_EXHAUSTED` / `INVALID_ARGUMENTS` / `COORDINATE_OUT_OF_RANGE` | 已执行或参数问题 | 不要重复同点；换方向/换目标 |
| verify | `VERIFY_TIMEOUT` / `FOREGROUND_MISMATCH` / `TEXT_MISMATCH` | 验证失败 | 回观察；说明当前前台/文本 |
| timeout | `ACTION_TIMEOUT` / `OBSERVE_TIMEOUT` | 超时 | 可重试一次但说明是否可能已生效；a11y 超时后标记通道 degraded |
| artifact | `ARTIFACT_UNAVAILABLE` | 截图/artifact 失败 | 继续文本路径；不要内联 base64 |

`UNSUPPORTED` 与 `CHANNEL_UNAVAILABLE` 必须在模型可见的结果里**分开**：前者「别再试这个能力」，后者「换个通道/请用户开启」。`DEVICE_*` 是第三种：设备暂时不可用，等用户/下一轮。

### 4.4 与 `plugin-api` 现有 `ActionResult(detail: String)` 的关系

- 现有契约：`data class ActionResult(val outcome: ActionOutcome, val detail: String)`（`common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/Actions.kt:70`）；`ActionExecutor.execute` 返回它（`:72-76`）。
- 现有链路：`ToolRegistry.execute` 把 `Done → payload=detail`、`Refused/Failed → payload="Refused: detail"`（`phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ToolRegistry.kt:93-97`）；`ToolResult.payload` 仍是 `String`（`ToolProtocol.kt:28`）；历史里 `ModelHistoryItem.ToolOutput(callId, output: String)` 也只有字符串（`ModelServices.kt:101`）。
- **0.25 建议：不改 `plugin-api`。** 内核返回 `DeviceResult`；app 侧 `device` 适配器把它投影成 `ActionResult`：
  - `outcome`：`Refused` = 未执行（device/channel/locate/invalid）；`Failed` = 已执行但 `applied=false`/verify 失败；`Done` = `ok && applied!=false`。
  - `detail`：模型可见文本（第一行摘要 + screen diff + error hint），≤16KB/400 行；`ToolRegistry` 的前缀逻辑不变，模型看到 `Refused: DEVICE_LOCKED` / `Failed: ACCEPTED_NO_EFFECT`；结构化 envelope 不直接进 `detail`。
  - 宿主/UI 若需要结构化字段，保留内存中的 `DeviceResult`（按 callId 关联）或未来给 `ActionResult` 加可选的 `payloadJson: String?`（additive，不是 0.25 必需）。
- **不要复用 `AppliedSemanticsPostProcessor`**：它把 `Done` 一律写成 `[applied=true]`（`AppliedSemanticsPostProcessor.kt:25-32`、`:47-51`），会把 Intent fire-and-forget 的「受理」误读成「生效」，与新三级语义冲突。`device` 路径必须绕过它；旧工具退役时一起删。
- **图片**：`InputPart.Image(localRef)` 契约存在（`ModelServices.kt:51-58`），但 `ToolOutput` 只有 String、适配器尚未编码图片；因此 0.25 的 envelope 只带 artifact 引用，真正的图片附件需要 D3 改模型/历史契约（D2 负责图片分片）。不要为了「让模型看到图」在 `detail` 里塞 base64。

---

## 5. 通道健康与能力声明

### 5.1 能力枚举

`DeviceCapability`（内核）：

```text
OBSERVE_TREE           读节点树
NODE_ACTIONS           节点语义动作
GESTURES               dispatchGesture
SCREENSHOT_A11Y        AccessibilityService.takeScreenshot
SCREENSHOT_SHELL       shell screencap
MULTI_WINDOW           getWindows() 交互窗口
VIEW_IDS               flagReportViewIds 读回 id
EVENTS                 事件订阅
IME_ACTIONS            ACTION_IME_ENTER 等
SHELL_COMMAND          任意 shell 命令
SHELL_STATE            TargetState 状态重读
UNLOCK_ACTIVITY        UnlockActivity 可被拉起
UNLOCK_SHELL           wm dismiss-keyguard 兜底
SCREEN_LEASE           任务期间保持亮屏
VISION_GROUNDING       视觉 grounding（依赖 D3/外部 VLM）
```

`ChannelHealth`：

```text
UP           可用
DOWN(reason) 当前不可用（a11y 关、shell 死、屏幕灭、锁屏）
DEGRADED(missing) 可用但不完整（缺 flag、截图被限流、事件缺失）
UNSUPPORTED(reason) 本设备/构建不可能（API 级别、未实现、无权限）
```

### 5.2 广告规则：不可用不广告，但区分三种「不可用」

1. **通道健康**驱动广告：`ExecutionRouter` 现有原则是正确的——`descriptors()` 只合并 `available()` 的 backend（`ExecutionRouter.kt:51-52`），`execute` 再查一次（`:54-59`），不缓存。新 `device` 工具沿用：`available()` 只问通道（a11y connected / shell ready），不问锁屏状态。
2. **能力**驱动 action 枚举：`DeviceControl.capabilities()` 给出当前可用集合；D2 可以缩 action 枚举或隐藏整个工具。缺 `GESTURES` 时不应广告「swipe」；缺 `SCREENSHOT_A11Y` 时不应广告「screenshot」（除非 shell 截图可用）。
3. **设备状态不直接改广告**：`DEVICE_SCREEN_OFF` / `DEVICE_LOCKED` 是**每次调用**的前置错误，不是通道死亡；屏幕灭时工具仍可被调用并触发解锁（否则模型只会说「我没有这个能力」）。**例外**：`isKeyguardSecure()==true` 按业主口径「直接判不可用」——设备 UI 工具在该回合不广告/直接拒绝（见 §8 Q5 的严格程度）。
4. `UNSUPPORTED` vs `CHANNEL_UNAVAILABLE`：
   - `UNSUPPORTED`：不可能（如 `takeScreenshotOfWindow` API 34、K40 API 33；视觉未接；设备无此 API）；
   - `CAPABILITY_NOT_CONFIGURED`：本可支持，但服务 XML 没声明（gestures/screenshot/windows/ids）——修复是配置，不是用户开关；
   - `CHANNEL_UNAVAILABLE`：已实现但当前 down（服务关、Shizuku 死）——修复是用户/重连；
   - `DEVICE_*`：通道在，设备状态不允许——修复是解锁/等用户。
5. **广告必须廉价**：`available()` 不能走树、不能 dump、不能 `screencap`；只允许 `connected()` / `ready()` / 标志位。树是否可读由最近一次观察/事件推断，不在广告路径做重活。

### 5.3 廉价探针

| 能力 | 探针 | 成本/证据 |
|---|---|---|
| a11y 连接 | `EdithAccessibilityService.connected()`（`A11yService:276-279`） | O(1)；服务 `onUnbind/onDestroy` 清空（`:57-65`） |
| shell | `ShizukuUserServiceShell.ready()`（`ShizukuUserServiceShell.kt:162-163`）；`binderAlive()`（`:241-244`） | O(1)；binder ping + permission |
| 屏幕/锁屏 | `PowerManager.isInteractive`；`KeyguardManager.isKeyguardLocked/isKeyguardSecure` | O(1)；Android 平台 API |
| a11y 配置 | `AccessibilityServiceInfo` 的 flags/capabilities（在 `onServiceConnected` 读取并缓存） | O(1)；配置变化时重读 |
| 截图限流 | 记录上次 `takeScreenshot` 时间；失败码 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` | 代码级；K40 未测 |
| 事件 | 上次事件时间 + `typeAllMask` 是否配置 | 事件可能缺失（S1 E01），只能当提示 |

### 5.4 a11y 配置：唯一权威 + 写后验证

当前生产配置 `phone-app/app/src/main/res/xml/edith_accessibility_config.xml:9-15` 只有：

```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
android:accessibilityFlags="flagDefault"
android:canRetrieveWindowContent="true"
```

**缺**：`canPerformGestures`、`canTakeScreenshot`、`flagRetrieveInteractiveWindows`、`flagReportViewIds`、`flagIncludeNotImportantViews`、`typeAllMask`。

建议改为（唯一一份，只改 app 的这份）：

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:description="@string/a11y_service_description"
    android:notificationTimeout="100" />
```

验证纪律（沿用 `AGENTS.md` / STATUS §1）：

1. 写入/更新前先读当前 `enabled_accessibility_services`，保留已有项再追加/更新；
2. 更新 App 后，服务配置是否真的生效必须用 **运行时** 证据：`dumpsys accessibility` 的 bound service、`getWindows()` 返回 >0、一次 `dispatchGesture` 成功、一次 `takeScreenshot` 成功；
3. 不能只凭 `settings get` 的设置值声称「已启用」；也不能只凭 XML 声称「有能力」。

**X-OmniClaw 的双配置教训（源码级）**：它有两份同名配置——`XAppConfig`（`app/src/main/res/xml/accessibility_service_config.xml:24-32`，有 `canPerformGestures`、`flagRetrieveInteractiveWindows`，**无** `canTakeScreenshot`）与 `XObsConfig`（`extensions/observer/src/main/res/xml/accessibility_service_config.xml:3-10`，**有** `canTakeScreenshot` 与 `flagIncludeNotImportantViews`）；只有 observer 的 `PhoneAccessibilityService` 在 manifest 里声明（`extensions/observer/src/main/AndroidManifest.xml:30-41`）。app 模块的同名 resource 在 Android 资源合并中优先级更高，因此「最终生效的是哪份、`canTakeScreenshot` 是否真的进到服务」在源码层面就不确定。预研也把它列为未验证（报告 §11.9）。EDITH 只保留 app 的一份，且写后做运行时验证。

### 5.5 降级矩阵

| a11y | shell | 可用能力 | 不可用/说明 |
|---|---|---|---|
| UP | UP | 全部（树、语义、手势、截图、窗口、shell 兜底） | 最佳 |
| UP | DOWN | 树/语义/手势/a11y 截图；无 shell 兜底；无 `wm dismiss-keyguard`、无 `input`、无 `am start` | 手势/截图仍可；`open` 用 App 内 intent（若允许） |
| DOWN | UP | shell `uiautomator dump`、`input tap/swipe/keyevent/text(ASCII)`、`screencap`、`am start`；**无**语义动作、无中文输入、无窗口语义、dump 2,283ms、不能读自己 | 慢、瞎、只能坐标；D84「无运行时回退」被本设计改写（Q1） |
| DOWN | DOWN | 无 | 工具不广告；`notify_user` 给恢复指引 |
| UP 但缺 flag | UP/DOWN | 缺 gestures → 无手势；缺 screenshot → 只能 shell 截图；缺 windows → 只有 active 窗口；缺 ids → `viewId=null` | `CAPABILITY_NOT_CONFIGURED`，不是 `UNSUPPORTED` |
| 任一 | 任一，但屏幕灭/锁 | 设备前置不满足 | 走 §6；不是通道死亡 |
| 任一 | 任一，但 `isKeyguardSecure` | 设备 UI 不可用 | `DEVICE_LOCKED_SECURE`；不绕过 |

---

## 6. 解锁预检状态机（重点）

### 6.1 状态

```text
ScreenState =
  INTERACTIVE_UNLOCKED   isInteractive && !isKeyguardLocked
  INTERACTIVE_LOCKED     isInteractive && isKeyguardLocked && !isKeyguardSecure
  SCREEN_OFF_UNLOCKED    !isInteractive && !isKeyguardLocked
  SCREEN_OFF_LOCKED      !isInteractive && isKeyguardLocked
  SECURE                 isKeyguardSecure（无论当前是否锁着）
```

先判 `SECURE`（业主口径：只要设置了安全锁屏就不可用，即使此刻已解锁）。

```text
DeviceNeed = UI_READY | SHELL_READY
```

- `UI_READY`：屏幕亮 + 无 keyguard + 需要的 a11y 通道 up（+ 若该动作要 shell 兜底，shell up）。
- `SHELL_READY`：shell up；是否需要亮屏由动作决定（`input`/`screencap` 需要亮屏；`pm list` 不需要）。
- 0.25 默认：所有 `device` 的 UI 动作都要 `UI_READY`；`shell` 工具是否也要 `UI_READY` 见 §8 Q2。

### 6.2 `ensureReady()` 步骤

```text
suspend fun ensureReady(need: DeviceNeed): ReadyResult {
  val s = screenProbe.read()                       // O(1): interactive/locked/secure
  if (s.keyguardSecure) return LockedSecure        // 不尝试、不弹凭据

  if (s.interactive && !s.locked) {
      if (channels.ready(need)) {
          lease.ensureHeld()                       // 廉价；已持有则 no-op
          return Ready(unlockedByUs=false)
      }
      return ChannelUnavailable                    // 屏幕已就绪但通道 down；解锁帮不上忙
  }

  // 需要解锁/点亮：每回合最多一次
  if (!limiter.tryAcquire()) {
      return if (s.locked) Locked else ScreenOff   // 本轮不重试
  }

  val surface = unlockSurface.raise(s)             // suspend；Activity/FSI/shell
  if (surface == SURFACE_UNAVAILABLE) {
      return if (s.locked) UnlockFailed(surface_unavailable) else ScreenOff(surface_unavailable)
  }

  val result = unlockWaiter.await(timeout = UNLOCK_TIMEOUT_MS)  // 5s 起始值
  if (result != SUCCEEDED) return UnlockFailed(result.reason)

  // 唤醒/解锁后：Shizuku 可能被 MIUI 停掉
  if (need.requiresShell && !shell.ready()) {
      shell.rebindOnce()                           // 有界，见 6.5
      if (!shell.ready()) return ShizukuLostAfterWake
  }

  if (!verifyReady(need, timeout = VERIFY_TIMEOUT_MS))  // 2s 起始值
      return UnlockFailed(verify_failed)

  lease.ensureHeld()
  generation.bump(DEVICE_STATE_CHANGE)             // 旧 ref 失效，下次强制全量
  return Ready(unlockedByUs=true, unlockMs=...)
}
```

要点：

- **廉价检查在每次工具调用都做**（`screenProbe.read()` + `connected()/ready()`）；只有不 ready 且 limiter 允许时才拉起 UI。
- `SECURE` 是**第一判断**，不是「锁着才判」；符合业主「使用前提无安全锁屏」。
- `channels.ready(need)` 只查 O(1) 状态；不在 precheck 里读树/dump/screencap。
- 解锁成功后 `generation++`，强制全量观察；解锁可能把前台变成 launcher/最后 App，模型需要重新观察（甚至重新 `open`）。

### 6.3 主路径 A：`UnlockActivity` + `requestDismissKeyguard`

`UnlockActivity`（app，新增）：

- `Theme.Translucent.NoTitleBar`、`excludeFromRecents`、`noHistory`、`launchMode=singleTop`（避免多实例）。
- `onCreate`：`setShowWhenLocked(true)` + `setTurnScreenOn(true)`（API 27+；K40 API 33）。
- **可见后**再调用 `KeyguardManager.requestDismissKeyguard(this, callback)`（API 26+）。文档要求 Activity 必须可见；在 `onCreate` 里调用可能立即失败（**文档级，K40 未测**）。建议放在 `onPostResume`/首帧后。
- 回调：
  - `onDismissSucceeded` → 回 `SUCCEEDED`，finish；
  - `onDismissCancelled` → 用户取消 → `USER_DISMISSED`，finish，**本轮不再拉起**；
  - `onDismissError` → `CALLBACK_ERROR`，finish；
  - 5s 无回调 → 超时，finish，`UNLOCK_FAILED(timeout)`。
- 无 keyguard 但屏幕灭（`SCREEN_OFF_UNLOCKED`）：不需要 `requestDismissKeyguard`；由 `setTurnScreenOn` + `ScreenAwakeLease` 点亮，等 `isInteractive` 即可 finish。
- keyguard 不安全/设备 trusted 时，API 会立即解除、无需交互（文档级）；如果它没有立即解除，走兜底 B。
- **绝不**在 secure keyguard 下调用；**绝不**自己输入 PIN/图案/密码。

拉起路径（按优先级）：

1. App 在前台（用户刚点了开始/会话页可见）：直接 `startActivity(UnlockActivity)`。
2. App 在后台，但有 `Settings.canDrawOverlays(context)`：走现有「后台拉起」路径——`HostRoot:2149-2169` 的确认弹窗就是先判 overlay 权限再 `startActivity`，并注明 MIUI 的「后台弹出界面」开关；解锁复用同一判定。**没有 overlay 权限就不假设能拉起**。
3. 通知 + full-screen intent：新建 `edith.unlock` 高优先级 channel，`IMPORTANCE_HIGH`，`setFullScreenIntent(openUnlock, true)`；参考现有确认通知 `AndroidNotificationSurface.kt:54-67` 与 `openAppIntent` `:112-122`。屏幕灭/锁屏时系统拉起 Activity；K40/API 33 不受 Android 14 的 FSI 限制（manifest 已声明 `USE_FULL_SCREEN_INTENT`，`AndroidManifest.xml:30`）。通知文案要说明「EDITH 需要点亮屏幕继续操作」，可取消；成功/失败/取消时撤销。
4. 都不行 → `UNLOCK_FAILED(surface_unavailable)`，不要静默失败。

### 6.4 兜底 B：shell 唤醒 + `wm dismiss-keyguard`

适用：屏幕亮着但 Activity 拉不起（无 overlay/FSI），或屏幕刚灭、Shizuku 还没被 MIUI 停掉。

```text
if (!shell.ready()) -> 不能走 B
if (screen.keyguardSecure) -> 不走（同主路径）
if (!screen.interactive) shell.run("input keyevent KEYCODE_WAKEUP")
if (screen.locked)      shell.run("wm dismiss-keyguard")
wait/verify (同 6.6)
```

- `input keyevent KEYCODE_WAKEUP`：Shizuku 调研实测 exit=0、85ms（§2.1）；是否真的点亮屏幕在 K40 上未单独断言（**未测**）。
- `wm dismiss-keyguard`：AOSP `WindowManagerShellCommand` 的子命令；K40 上**未实测**。只在 `isKeyguardSecure()==false` 时用；secure 时绝不尝试。
- 兜底 B 失败/命令不存在 → 回主路径 A 或 `UNLOCK_FAILED`；不要把 shell 错误当成「用户拒绝」。
- 兜底 B 也是「抢手机」风险：只在主路径不可用时；同样受每回合一次限流。

### 6.5 Shizuku rebind（唤醒后）

- 前置事实：小米/MIUI 息屏后 Shizuku 服务会停（外部 issue `RikkaApps/Shizuku#1426`；**K40 未复现/未测**，只作设计依据）。所以「解锁成功」≠「shell 还在」。
- 步骤：
  1. `shell.ready()` false → `Shizuku.pingBinder()`：
     - false → Shizuku server 本身没了 → `SHIZUKU_LOST_AFTER_WAKE`（非 root 设备需要 adb 重启 Shizuku；App 不自动做）。
     - true → permission 仍 granted → `bind()` 一次（`ShizukuUserServiceShell.bind`，`:135-138`）；`bind()` 可重复调用（KDoc `:134`）。
  2. 等待 ≤1s（建议 300ms 轮询）再 `ready()`；仍 false → `SHIZUKU_LOST_AFTER_WAKE`。
  3. rebind 的限流沿用现有 `RelinkGate` 思路（窗口 3s，`PrivilegedShell.kt:115-183`）或解锁状态机自己的 `rebindOnce`；**不要**在每回合反复 bind。
  4. 任何情况下都不调用 `requestPermissionIfNeeded()` 在回合中弹权限框（会跟用户抢前台）；权限请求只在会话开始、App 前台时做（现有 `HostRoot:2140-2142`）。
- 若 `SHIZUKU_LOST_AFTER_WAKE`：a11y-only 降级；如果动作必须 shell（如 `open`/`input`），报错并给用户恢复指引；不要假装成功。

### 6.6 验证条件（为什么不能只看 a11y 树）

成功条件（同时满足）：

1. `PowerManager.isInteractive == true`；
2. `KeyguardManager.isKeyguardLocked == false`；
3. 若 `need` 需要 a11y：至少有一个非 keyguard 的可读窗口，或能证明当前 SystemUI 是用户主动拉下的通知栏而不是锁屏（用窗口类型/标题/`getWindows()` 判定）。**判据的主次**：KeyguardManager 是权威；a11y 树只是通道可用性检查，不能用「只有 SystemUI」直接判成功或失败。
   - 解锁前是锁定/息屏，解锁后 `isKeyguardLocked` 已 false、但 2s 内树仍只有 SystemUI：返回 `UNLOCK_FAILED(tree_systemui_only)`，不要在这棵树上执行目标动作；
   - 解锁前就是亮屏解锁，用户只是拉下通知栏：SystemUI 是合法前台，视为 ready（`foreground=systemui`），由 observe 决定下一步，不要误报解锁失败。
4. 若 `need` 需要 shell：`shell.ready() == true`（或已按 6.5 rebind 成功）；
5. 连续两次（间隔 ~100ms）满足 1–2，避免 keyguard 动画/短暂回调造成的假成功。

为什么：

- 锁屏下无障碍只能看到 SystemUI、看不到目标 App；**不能把「只有 SystemUI 的树」当成目标 App 没控件**（外部 issue `razzant/ouroboros#1035`；K40 的 keyguard 场景 S1 未测，见 `2026-10-16-a11y-capability-survey.md` §6 #4）。
- a11y 树可能滞后/缺事件；KeyguardManager 是权威，但 Activity 回调也可能在动画中提前成功，所以要「双条件 + 去抖」。
- 解锁后前台可能是 launcher；不要把「launcher 有树」当成目标 App 已就绪；下一次动作前要重新观察/重新 `open`。

### 6.7 任务期间亮屏与释放

- **租约**：`ScreenAwakeLease` 持有 `PowerManager.WakeLock`：`SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`（API 17+ 已标 deprecated，但平台仍提供；K40 上未测），tag `EDITH:device`，`setReferenceCounted(false)`，超时 `LEASE_MAX_MS`（建议 10 分钟，防挂死）。
- **权限**：当前 `AndroidManifest.xml` 的权限块（`:20-35`）**没有 `WAKE_LOCK`**；需新增（normal permission，无用户弹窗）。
- **获取时机**：`ensureReady` 成功、或回合开始已在 `INTERACTIVE_UNLOCKED` 时获取；不是每次工具调用都重新获取。
- **释放时机**：回合结束（`AgentLoop` 结束/失败/取消）、会话停止/暂停、解锁失败、用户接管、进程死亡（系统自动释放）。必须 `try/finally` + 幂等 `release()`。
- **不等价于 `FLAG_KEEP_SCREEN_ON`**：后者只对可见窗口有效；agent 在操作别的 App 时，App 窗口不可见，flag 无效。
- **备选**（仅在 wake lock 在 K40 上被 MIUI 证明无效时）：1px 透明 overlay + `FLAG_KEEP_SCREEN_ON`（已有 `SYSTEM_ALERT_WINDOW` 权限，但 overlay 会出现在 `getWindows()`/截图里，S1 O01 已证明；副作用大）；或 shell 写 `screen_off_timeout`（改用户设置，不优先）。写入 `screen_off_timeout` 必须记录原值并恢复，且失败即如实记。
- **证据边界**：眼镜端 S1 探针在 `Asleep` 下用 `SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP` 点亮成功（`2026-10-18-0.24-s1-overlay-spike.md:183-203`，`dumpsys power` 的锁行 `:195`）；但那是**眼镜 Android 12**，按 D46 不外推到 K40；K40 的 wake lock 亮屏**未测**。Activity 本身没有点亮屏幕（同证据 `:203`），所以不要只靠 `startActivity` 就宣称亮屏；手机上 `setTurnScreenOn(true)` 是 Activity 侧的正规机制，是否足够也要实测。

### 6.8 每回合一次限流与「不跟用户抢手机」

**限流**：

- `UnlockLimiter`：每回合（`turnId`）最多 1 次**解锁尝试**；成功/失败都消耗。廉价状态检查不消耗。
- 跨回合冷却：失败后至少 30s 才能再尝试；用户 dismissal 后冷却 5 分钟（建议值，可配置）。
- 需要 D3/宿主在回合边界调用 `beginTurn(turnId)` / `endTurn()`；若 D3 暂不接，退化成一时间窗限流（例如 30s 内最多 1 次），但会偏离「每回合一次」的语义（在 §8 Q2 之外单列风险）。
- 解锁尝试的计数进日志：只记 `attempt/turn/result/reason/duration`，不记屏幕正文（D117）。

**不跟用户抢手机**：

1. `isKeyguardSecure()==true`：不尝试、不弹凭据、不猜 PIN/图案（业主）。
2. 用户取消（`onDismissCancelled`）或按 Home/Back 离开 `UnlockActivity`：视为拒绝；本轮不重拉，冷却期不骚扰。
3. **用户主动息屏**：如果租约持有期间屏幕变成 off，推断为用户按电源键（租约本应阻止超时息屏）；立即释放租约，结束本轮设备动作，返回 `DEVICE_SCREEN_OFF`（`reason=user_takeover`），**不自动唤醒**。这是启发式：MIUI 省电策略也可能息屏，若发生误判，用户可下一轮显式开始；不因此反复唤醒。
4. 任务中 keyguard 重新出现（用户又锁了）：`DEVICE_LOCKED`，本轮不再解锁；不要跟用户抢。
5. 会话暂停/停止、`SessionState` 非运行：不拉起解锁。
6. 通话/用户正在交互：没有可靠 API 时不强行检测；至少做到「只尝试一次、可取消、失败就退」。
7. 通知/Activity 文案要说明是 EDITH 在请求解锁；不伪装成系统弹窗。

### 6.9 错误码映射（解锁相关）

| 情形 | 错误码 | 说明 |
|---|---|---|
| `isKeyguardSecure` | `DEVICE_LOCKED_SECURE` | 第一判断；不尝试；提示移除安全锁屏 |
| 屏幕灭，且本轮 limiter 已用完 / surface 不可用 | `DEVICE_SCREEN_OFF` | `reason=no_permit / surface_unavailable`；不反复唤醒 |
| 无安全锁屏但锁着，本轮没尝试 | `DEVICE_LOCKED` | `reason=no_permit / cooldown` |
| 尝试了但回调错误/超时/验证失败 | `UNLOCK_FAILED` | `reason=callback_error / timeout / verify_failed / tree_systemui_only` |
| 用户取消/主动息屏 | `UNLOCK_FAILED` | `reason=user_dismissed / user_takeover`；本轮不重试 |
| 唤醒后 shell 不在，rebind 失败 | `SHIZUKU_LOST_AFTER_WAKE` | 若 Shizuku server 仍在，先 bind 一次；server 不在则只能提示用户 |
| 解锁成功但 a11y 仍关 | `CHANNEL_UNAVAILABLE` | 设备 ready、通道 down，分开报 |
| 解锁成功但前台是 launcher | 不是错误 | 结果带 `foregroundChanged=true`；下一次动作前重新观察/`open` |

### 6.10 Android 14+ FSI 边界

- 当前 `targetSdk=34`（`phone-app/app/build.gradle.kts:28`），K40 是 API 33；`USE_FULL_SCREEN_INTENT` 已声明（`AndroidManifest.xml:30`）。
- Android 14+ 对 FSI 收紧：非通话/闹钟类 App 默认不自动授予；FSI 可能降级成 heads-up，无法在锁屏/息屏上拉起 Activity（Android 14 behavior changes，文档级）。**K40 API 33 不受影响**，记为未来边界。
- `UnlockSurface` 在 API 34+ 必须检查 `NotificationManager.canUseFullScreenIntent()`（若可用）；不能假设 `setFullScreenIntent` 一定拉起。
- 未来路径：优先 `Settings.canDrawOverlays` 的直接 `startActivity`（已有确认弹窗先例）；次选 FSI；都不行 → `UNLOCK_FAILED(surface_unavailable)` / 在能力声明里标 `UNSUPPORTED`，不要静默。
- K70/Android 16 已按 D46 取消，不在 0.25 设备验收范围。

### 6.11 与现有确认链路的关系

现有两条可复用先例：

1. `MainActivity` 在 `ACTION_SHOW_CONFIRMATION` 时 `setShowWhenLocked(true)` + `setTurnScreenOn(true)`（`MainActivity.kt:69-76`；`onNewIntent` 同样 `:177-183`）——证明这套 flag 在本 App/设备链路里可用，但只对确认弹窗生效。
2. 后台拉起确认弹窗先判 `Settings.canDrawOverlays`，没有就退回通知（`HostRoot:2149-2169`），并明确 MIUI「后台弹出界面」的额外限制。

解锁复用这两条**机制**，但不复用确认的 action/channel/requestId：

- 新 `ACTION_UNLOCK` / `UnlockActivity`，不要 hijack `ACTION_SHOW_CONFIRMATION`；
- 新通知 channel（`edith.unlock`），不要和确认 channel 混用 TTL/声音；
- 解锁 Activity 不展示业务 UI，只做 keyguard 解除；完成后立即 finish。

---

## 7. JVM 测试接缝、fake 端口、设备矩阵、延迟目标

### 7.1 端口与 fake

| 端口 | 生产实现 | JVM fake |
|---|---|---|
| `ScreenStatePort` | `PowerManager` + `KeyguardManager` | `FakeScreen(interactive, locked, secure)`；可脚本化状态序列 |
| `A11yTreePort` | `A11yPlatformAdapter`（app） | `FakeTree`：按脚本返回 Observation/WalkResult；可制造 `nullChildren`、truncated、空树、SurfaceView |
| `A11yActionPort` | 同上 | `FakeActions`：记录调用，按脚本返回 accepted/false/timeout |
| `GesturePort` | `dispatchGesture` | `FakeGesture`：completed/cancelled/timeout |
| `ScreenshotPort` | a11y `takeScreenshot` / shell `screencap` | `FakeScreenshot`：返回固定 PNG 字节/尺寸或限流错误 |
| `ShellPort` | `ShizukuShellAdapter` | `FakeShell`：命令→`ShellOutcome` 队列；可制造 `NOT_BOUND/CARRIER/PARSE/truncated/relinked` |
| `UnlockSurfacePort` | `UnlockActivity`/FSI/shell | `FakeUnlockSurface`：success/cancel/error/timeout/surface_unavailable；记录 raise 次数 |
| `ScreenAwakePort` | `ScreenAwakeLease` | `FakeLease`：记录 acquire/release 次数与 held |
| `ClockPort` | `SystemClock.elapsedRealtime`/`System.nanoTime` | `FakeClock`：手动推进；不 sleep |
| `SchedulerPort` | coroutine delay/executor | `FakeScheduler`：按虚拟时间触发轮询 |
| `ArtifactStorePort` | app files/cache | `FakeArtifacts`：内存 map；记录 save/delete |
| `EventPort` | `onAccessibilityEvent` 缓存 | `FakeEvents`：按脚本投递/不投递 |
| `VisionPort` | 未接（D3） | `FakeVision`：返回 NormalizedPoint 或 null |

测试接缝的既有先例：`ShizukuUserServiceShell.forTest`（`ShizukuUserServiceShell.kt:266-299`）与 `RelinkGate` 注入时钟（`PrivilegedShell.kt:115-183`）。新内核同样用注入的 `ClockPort`/`SchedulerPort`，不 sleep、不依赖真实时间。

### 7.2 JVM 用例清单（至少）

1. **Observation/Ref**：同一树两次观察 ref 稳定；文本变化但 viewId 不变 → ref 不变；节点消失/新增 → ref 退休/新铸；跨 generation 的动作 → stale；受限 rebind 成功/失败各一例；截断时不报 removed。
2. **Diff**：首屏全量；无变化显式 unchanged；added/changed/removed；焦点变化不触发 changed；≥60 行或 >50% → 全量；truncated → 不报 removed；键盘关闭提示。
3. **CoordinateSpace**：0/500/1000、奇数宽高、1px、旋转、缩放 artifact；越界拒绝；dpi 无关。
4. **SettlePolicy**：tap 变化/不变/超时；type 读回；scroll 边界；open 超时 accepted_only；虚拟时钟推进，不真实等待。
5. **Accepted/Applied/Verified**：a11y true 但无变化 → accepted=true/applied=false/`ACCEPTED_NO_EFFECT`；手势 completed 但无变化；shell exit0 但状态没变 → `STATE_UNCHANGED`；intent fire-and-forget → applied=null/acceptedOnly；verified 谓词命中/不命中。
6. **FallbackPolicy**：NODE_DISABLED 不降级；NODE_NOT_ACTIONABLE 降级手势；手势取消→shell；shell 不可用→不硬点；遮挡坐标不降级；每次最多一次、结果带 fallback 记录。
7. **Envelope**：成功/失败/截图三份 golden JSON；不变量（§4.2）；`channel` 不进模型投影；无 base64；`truncated` 规则；16KB 截断在行边界。
8. **ChannelHealth**：a11y only/shell only/both/neither 的 action 广告矩阵；缺 flag → `CAPABILITY_NOT_CONFIGURED`；API 级别 → `UNSUPPORTED`；`available()` 不读树（用 fake 计数证明）。
9. **UnlockStateMachine**：SECURE 第一判断；screen off unlocked；screen off locked；interactive locked；成功；callback error；timeout；user dismiss；tree SystemUI-only；surface unavailable；shell lost after wake + rebind 成功/失败；每次尝试只 raise 一次；user takeover 后不再唤醒；租约 acquire/release 幂等。
10. **Limiter**：每回合一次；失败冷却；成功也消耗；跨回合重置；无 turn hook 时时间窗退化（若实现）。
11. **Budget**：400 节点/16KB/单节点 256 字符；超限标 truncated；不截 JSON/半行。
12. **D117**：日志/证据只出现计数/id/哈希/状态；fake 记录里没有屏幕正文；artifact 元数据无正文。
13. **模块边界**：`src/main` 无 `import android.`；`device-control` 的依赖里没有 `plugin-api`/Android。
14. **Error mapping**：§4.3 每个 code 至少一例；`UNSUPPORTED`/`CHANNEL_UNAVAILABLE`/`DEVICE_*` 不混用。
15. **Reconnect**：a11y 掉线/重连后 refs 失效、generation bump、强制全量；shell bind 成功/失败。

### 7.3 设备矩阵（K40 = 验收设备；未跑即 `NotMeasured`）

> 当前 K40 上生产 a11y 服务**未连接**（`STATUS.md` §3；预研 §3.3：`enabled_accessibility_services` 是探针服务，生产 `connected()` false）。设备测试前按「读现值 → 保留/追加 → 写后验证绑定」启用生产服务；探针 `edith.phone.a11yprobe` 是邻居工作线的仪器，动前先确认（`AGENTS.md`）。

| # | 场景 | 操作/判据 | 状态 |
|---|---|---|---|
| K1 | 亮屏已解锁，a11y on | observe/tap/type/scroll；refs 稳定；p50/p95 | 未测 |
| K2 | 亮屏+不安全滑动锁屏 | `ensureReady` 拉起 `UnlockActivity`；`requestDismissKeyguard` 成功；验证 tree 不是 SystemUI-only | 未测 |
| K3 | 息屏+不安全锁屏 | FSI/overlay 拉起；`setTurnScreenOn` + wake lock 点亮；解锁；Shizuku 状态 | 未测 |
| K4 | 息屏+无锁屏 | 只点亮；不调用 `requestDismissKeyguard`；租约保持 | 未测 |
| K5 | 安全 PIN/图案锁屏 | `DEVICE_LOCKED_SECURE`；不弹凭据、不尝试；同一会话不再广告设备 UI 工具（按 Q5） | 未测 |
| K6 | 任务中用户按电源键 | 推断 user takeover；释放租约；`DEVICE_SCREEN_OFF(reason=user_takeover)`；不再唤醒 | 未测 |
| K7 | 任务中用户重新锁屏 | `DEVICE_LOCKED`；本轮不再解锁 | 未测 |
| K8 | 用户取消 UnlockActivity | `UNLOCK_FAILED(user_dismissed)`；本轮不重拉；冷却生效 | 未测 |
| K9 | a11y off / shell on | `uiautomator dump` 2,283ms；`input` 坐标/按键；无中文；工具降级广告 | 未测 |
| K10 | 屏幕灭后 Shizuku 被 MIUI 停 | 唤醒后 `ready()` false；`bind()` 一次；成功/`SHIZUKU_LOST_AFTER_WAKE` | 未测（外部 issue 只作依据） |
| K11 | Shizuku server 本身死 | `pingBinder()` false；不自动启动；a11y-only 降级 | 未测 |
| K12 | 无 overlay 权限、通知可用 | FSI 拉起；或只出通知不拉起，报 `surface_unavailable` | 未测 |
| K13 | 无 overlay、通知被拒 | 无 surface；`UNLOCK_FAILED(surface_unavailable)`；不假装成功 | 未测 |
| K14 | IME 打开，中文 `SET_TEXT` | 树里有 IME section；写中文成功；键盘关闭提示；`input text` 中文必失败（不测为通过） | 未测 |
| K15 | 多窗口/悬浮窗 | `getWindows()` 3 窗口；悬浮窗只在 windows；语义点击穿遮挡；坐标被吃（S1 O02/O03） | 部分实测（S1），生产未测 |
| K16 | SurfaceView/空树 | 触发视觉兜底条件；无视觉时 `TREE_UNAVAILABLE`/截图 artifact；不盲点 | 未测 |
| K17 | a11y 截图 | 首次成功；1s 内第二次 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`；artifact 不内联 | 未测 |
| K18 | 旋转 | 旋转后旧 ref stale；坐标映射正确 | 未测 |
| K19 | 读自己的 App | `nullChildren`/超时；不误判「无控件」；失败即如实报 | S1 有探针证据，生产未测 |
| K20 | Android 14+ FSI | 不在 K40；未来设备单独测 | 不适用/未测 |

### 7.4 延迟目标（**目标，不是实测**）

| 指标 | 目标 p95 | 依据/备注 |
|---|---:|---|
| `ensureReady` 廉价检查（已解锁） | ≤10ms | 目标；只有 O(1) 查询 |
| `ensureReady` 解锁端到端（息屏→ready） | ≤5s | 目标；含 FSI/Activity/回调/验证；K40 未测 |
| `observe`（interactive 中等屏） | ≤150ms | 预研 §8.6 建议目标；生产未测 |
| `tap + observe` | ≤400ms | 同上 |
| shell 空命令往返 | ≤80ms | 预研目标；设备内实测 26–37ms（Shizuku 调研 §3.3） |
| a11y 截图 | ≤1s | 目标；a11y `takeScreenshot` K40 未测 |
| shell 截图 | ≤600ms | `screencap` 实测 321–398ms（PC 路径）+ 回传 |
| `uiautomator dump` | 不设目标（仅兜底） | 实测 2,283ms；超时预算 3s |
| 模型往返 | 未测 | 取决于档案；减少往返仍是最优先 |

> 这些数字**不得写成已达成**。设备阶段用 n≥30 的 p50/p95 记录；未跑写 `NotMeasured`。

### 7.5 证据纪律

- 日志/证据只记：revision/generation、ref 计数、节点数、耗时、错误码、artifact id/字节/哈希、通道状态；**不记屏幕正文**（D117）。业主放宽的是「屏幕内容可上传给模型」，不是「证据可落正文」。
- 设备侧人工操作模拟仍走 `edith.phone.a11yprobe`（D95）；不要用 `adb shell input` 替代。
- a11y 设置写入：先读现值 → 保留/追加 → 写后 raw 验证绑定（`AGENTS.md`、STATUS §1）。
- `NotMeasured` 不得读成通过；单次读数不得写成 p95。

---

## 8. 待裁决问题（5 条）

| # | 问题 | 为什么现在要定 | 本草案默认 | 影响 |
|---|---|---|---|---|
| Q1 | **D84 的“无运行时通道回退 / 坐标不暴露”要不要在 0.25 规格里改写？** 本设计的内核会做 a11y→gesture→shell 回退，但模型仍不知道通道、工具参数仍不暴露 `x/y`。 | 现行 D84 明确写「没有运行时通道回退」；预研 §8.7 和本草案都要求回退。不改 D84 就无法合法实现回退。 | 只改「回退」这一句；「模型不知道通道、坐标不暴露」保留；`channel` 只进审计。 | 若不改，`device` 只能单通道，SurfaceView/语义失败只能报错。 |
| Q2 | **解锁预检覆盖所有工具调用，还是只覆盖需要 UI 的 `device` 动作？** 业主前提说「工具调用前必须有解锁预检」「`isKeyguardSecure` 直接不可用」，但 `shell` 的非 UI 命令（如 `pm list packages`）在锁屏且 Shizuku 仍活时技术上可跑。 | 决定 `shell`/`open` 在锁屏时是否也一律拒绝；影响「安全锁屏 = 整个设备工具不可用」的严格程度。 | `device` 的 UI 动作必须 `UI_READY`；`shell` 只要求 shell ready，不要求解锁（但 MIUI 息屏通常已把 shell 停掉，实际多数会失败）。 | 若要求全部 `UI_READY`，安全锁屏下连只读包查询也不可用；若不要求，锁屏时仍可能执行非 UI 命令。 |
| Q3 | **任务期间亮屏用哪个机制？** `SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP`（需新增 `WAKE_LOCK`）vs overlay `FLAG_KEEP_SCREEN_ON`（已有 overlay 权限，但会进 `getWindows()`/截图）vs shell 写 `screen_off_timeout`（改用户设置）。 | 影响功耗、权限、可撤销性与 MIUI 行为；K40 上均未测。 | 首选 wake lock；overlay 只在 wake lock 被证明无效时；`screen_off_timeout` 不优先。 | 选错会导致任务中再次息屏/无法解锁，或引入可见的悬浮窗副作用。 |
| Q4 | **0.25 的“视觉兜底”交付到什么程度？** 当前 `ModelHistoryItem.ToolOutput` 只有 `String`、适配器没有图片编码；真正的 VLM 兜底依赖 D3 的图片输入/历史契约与 D2 的图片分片。 | 若 0.25 必须端到端视觉兜底，D1 的范围要扩到截图上传/视觉 grounding；若只交付截图 artifact + 触发条件 + `VisionPort`，则 0.25 仍以树为主。 | 交付截图 artifact、触发条件、`VisionPort` 端口与 fake；真实 VLM 留到 D3/0.26，不阻塞 0.25 树路径。 | 影响 0.25 能否宣称「视觉兜底」已实现；避免把“有截图”误读成“有视觉理解”。 |
| Q5 | **`isKeyguardSecure()==true` 的产品行为：整个会话拒绝开始，还是允许聊天但设备 UI 工具不可用？** | 业主前提「直接判不可用」可读成两种：能力不可用 vs 会话不可用。 | 允许聊天；`device` UI 工具在该回合不广告/直接 `DEVICE_LOCKED_SECURE`，用 `notify_user` 提示移除安全锁屏。 | 若要求整个会话拒绝，会影响会话启动 UX 与验收口径；若允许聊天，需要 UI 明确说明「本机设备控制不可用」。 |

---

## 9. 证据索引与未测清单

### 9.1 代码索引（别名 → 完整路径）

> 正文用「别名:行号」引用；下表给出完整路径，便于复核。

| 别名 | 完整路径 |
|---|---|
| `A11yService` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/a11y/EdithAccessibilityService.kt` |
| `Snapshot` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/a11y/ScreenSnapshot.kt` |
| `A11yBackend` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/a11y/AccessibilityExecutionBackend.kt` |
| `Applied` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/a11y/AppliedSemanticsPostProcessor.kt` |
| `Router` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/ExecutionRouter.kt` |
| `HostRoot` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/HostCompositionRoot.kt` |
| `MainActivity` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/MainActivity.kt` |
| `A11yConfig` | `phone-app/app/src/main/res/xml/edith_accessibility_config.xml` |
| `Manifest` | `phone-app/app/src/main/AndroidManifest.xml` |
| `SessionService` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/session/SessionService.kt` |
| `NotifSurface` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/session/confirmation/AndroidNotificationSurface.kt` |
| `Shell` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/shizuku/PrivilegedShell.kt` |
| `UserShell` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/shizuku/ShizukuUserServiceShell.kt` |
| `ShizukuBackend` | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/shizuku/ShizukuExecutionBackend.kt` |
| `Actions` | `common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/Actions.kt` |
| `ModelServices` | `common/contracts/plugin-api/src/main/kotlin/com/sheen/rokid/plugin/ModelServices.kt` |
| `ToolProtocol` | `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ToolProtocol.kt` |
| `ToolRegistry` | `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/ToolRegistry.kt` |
| `AgentLoop` | `phone-app/assistant/agent-runtime/src/main/kotlin/com/sheen/rokid/agent/AgentLoop.kt` |
| `Build` | `phone-app/app/build.gradle.kts` |
| `XDeviceTool` | `D:\Project_new\X-OmniClaw\app\src\main\java\com\shijing\xomniclaw\agent\tools\device\DeviceTool.kt` |
| `XRefManager` | `D:\Project_new\X-OmniClaw\app\src\main\java\com\shijing\xomniclaw\agent\tools\device\RefManager.kt` |
| `XDualTrack` | `D:\Project_new\X-OmniClaw\app\src\main\java\com\shijing\xomniclaw\agent\tools\device\DualTrackDecisionEngine.kt` |
| `XService` | `D:\Project_new\X-OmniClaw\extensions\observer\src\main\java\com\shijing\xomniclaw\accessibility\service\PhoneAccessibilityService.kt` |
| `XScreenshotter` | `D:\Project_new\X-OmniClaw\extensions\observer\src\main\java\com\shijing\xomniclaw\accessibility\service\A11yScreenshotter.kt` |
| `XAppConfig` | `D:\Project_new\X-OmniClaw\app\src\main\res\xml\accessibility_service_config.xml` |
| `XObsConfig` | `D:\Project_new\X-OmniClaw\extensions\observer\src\main\res\xml\accessibility_service_config.xml` |
| `E2EScreen` | `D:\Project_new\e2e\packages\e2e\src\agent\screen-update.ts` |
| `E2ESettle` | `D:\Project_new\e2e\packages\e2e\src\agent\settle-policy.ts` |
| `E2EBudget` | `D:\Project_new\e2e\packages\e2e\src\agent\observation-budget.ts` |
| `E2EQueue` | `D:\Project_new\e2e\packages\e2e\src\agent\operation-queue.ts` |

### 9.2 本项目实测证据

| 证据 | 用到的结论 |
|---|---|
| `docs/project/evidence/2026-10-16-a11y-capability-survey.md` | 跨应用整树可读；A01 祖先可点；A02 按 id；A03/A22 手势；A05 SET_TEXT 不需焦点；A06 hint；A07/A08 失败可信；A09 true 不等于生效；A11 禁用；A12 勾选；A17 滚动边界；A19 show_on_screen；A20 expand；A21 多击；A23 IME；A24 stale；E01 事件可能缺失；E02 静默 508ms；G02 BACK 未定论；G03 HOME；O01 悬浮窗只在 windows；O02 语义穿遮挡；O03 坐标被吃；T01/T02 树规模；X00 事件领先 0.03–0.7s；X08 flag 与按 id 查找的关系；§3.4 读自己会被主线程拖住；§6 keyguard 未测 |
| `docs/project/evidence/2026-10-16-shizuku-shell-capability-survey.md` | shell UID 身份；26–37ms；`uiautomator dump` 2,283ms/不能读自己/并发互斥；`screencap` 321–398ms；`am start -W` 105–203ms；`input keyevent WAKEUP` 85ms；`input text` 仅 ASCII（`Virtual.kcm` 无 CJK）；MIUI 安装限制；§3.4/§5 的通道差异 |
| `docs/project/evidence/2026-10-17-shizuku-reply-ceiling.md` | 回包上限约 520,187 JSON 字符；按字符而非字节预算；大输出留余量 |
| `docs/project/evidence/2026-10-18-0.24-s1-overlay-spike.md` | **眼镜端**息屏下 `SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` 点亮成功（`:183-203`、`:195`）；Activity 本身未点亮（`:203`）；按 D46 不外推到 K40 |
| `STATUS.md` §3 / 预研 §3.3 | K40 当前 `enabled_accessibility_services` 是探针服务，生产 a11y `connected()` false；启用需读-合并-写-验证 |

### 9.3 外部依据（文档/issue，非 K40 实测）

- `https://github.com/RikkaApps/Shizuku/issues/1426`：小米/MIUI 息屏后 Shizuku 服务停止。
- `https://github.com/razzant/ouroboros/issues/1035`：锁屏后 Accessibility 只暴露 SystemUI，keyguard 后的 App UI 不可操作；不能把只有 SystemUI 的树当成目标 App 没控件。
- `https://developer.android.com/reference/android/app/KeyguardManager`：`requestDismissKeyguard(Activity, callback)` API 26+；keyguard 不安全/设备 trusted 时立即解除；Activity 必须可见，否则立即失败；`setTurnScreenOn(true)` 时解除锁屏同时点亮屏幕。
- AOSP `WindowManagerShellCommand` 的 `wm dismiss-keyguard`：可作 Shizuku 兜底；K40 未实测。
- Android 14+ 对 `USE_FULL_SCREEN_INTENT` 的限制：非通话/闹钟类 App 默认不自动授予；K40=API 33 不受影响，记为未来边界。
- `phone-app/app/build.gradle.kts:28`：`targetSdk=34`；当前 K40 是 API 33。

### 9.4 明确未测（不得读成通过）

1. K40 上生产 a11y 的 `observe` / 节点动作 / 手势 / 截图延迟。
2. K40 上 `takeScreenshot`（含 1 张/秒限流）与 `takeScreenshotOfWindow`（API 34 不可用）。
3. K40 上 `wm dismiss-keyguard`、`input keyevent WAKEUP` 是否真的点亮/解锁。
4. K40 上 `SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP` 是否点亮屏幕；MIUI 是否允许长时间持有。
5. K40 上锁屏/Keyguard 场景的 a11y 树（S1 §6 #4 未测）；外部 issue 只作设计依据。
6. K40 上 MIUI 息屏停 Shizuku 的具体时序（外部 issue 只说明存在）。
7. K40 上后台拉起 `UnlockActivity`（overlay 权限 / MIUI「后台弹出界面」/ FSI）。
8. `requestDismissKeyguard` 在 K40 的 `onCreate` vs `onResume` 时机、回调时序。
9. 多窗口/分屏、悬浮窗、第三方 App 的 `EXPAND/COLLAPSE`、WebView/Flutter/Compose 语义树细节。
10. 中文 `ACTION_SET_TEXT` 在微信/QQ 等真实第三方 App（S1 未装微信）。
11. 长文本平台截断、跨用户/工作资料。
12. 视觉 grounding/VLM（本机未接，D3 依赖）。
13. 任务期间亮屏的功耗与 MIUI 省电策略交互。

---

## 10. 与现行代码/决定的差异（供规格阶段处理）

| # | 现状 | 本草案 | 处理 |
|---|---|---|---|
| 1 | `read_screen` 只序列化 text/traits，无 role/id/bounds/actions（`A11yBackend:205-226`） | 新 Observation 有 role/id/desc/bounds/states/actions/collection/range | D2 换工具面时替换；旧 backend 保留到切换完成 |
| 2 | 动作按 index 寻址，动作后快照作废，模型需再 `read_screen`（`A11yService:112-155`、`A11yBackend:230-240`） | 稳定 ref + generation + 动作后自动观察/diff | 新内核；旧路径不动 |
| 3 | `scroll` 找「树里第一个 scrollable」（`A11yService:131-142`） | 目标节点 scrollable；无目标才手势 | 新内核 |
| 4 | `setText` 依赖 `findFocus`（`A11yService:81-88`） | `ACTION_SET_TEXT` 直接按节点写；不要求焦点 | 新内核 |
| 5 | `input_text` ≤100 字符、拒绝换行（`A11yBackend:280-297`） | 内核不设 100 字符限制；换行是否允许由 D2 schema 定 | D2 定；内核支持长文本预算 |
| 6 | 成功判据是屏幕指纹变化，可能「变了但变错」（`A11yBackend:235-239`） | accepted/applied/verified 三级 + diff | 新内核 |
| 7 | `AppliedSemanticsPostProcessor` 把 `Done` 一律 `[applied=true]`（`Applied:25-32`、`:47-51`） | 三级语义；device 路径不复用它 | 旧工具退役时删除 |
| 8 | `ExecutionRouter` 只合并可用 backend（`Router:51-59`） | 保留；新增能力声明与 `UNSUPPORTED`/`CAPABILITY_NOT_CONFIGURED` | 扩展，不推翻 |
| 9 | 配置缺 gestures/screenshot/windows/ids（`A11yConfig:9-15`） | 补齐，且只保留一份权威配置 | 实施时改 XML + 运行时验证 |
| 10 | D84「没有运行时通道回退」 | 内核做回退，但模型仍不知道通道、不暴露坐标 | **需 Q1 裁决**；不改 D84 则回退不合法 |
| 11 | `MainActivity` 只在确认 FSI 时 `setShowWhenLocked`（`MainActivity:69-76`） | 新增 `UnlockActivity`，复用机制不复用 action | 新 Activity |
| 12 | 后台拉起确认依赖 overlay 权限（`HostRoot:2149-2169`） | 解锁复用同一判定；无 overlay 用 FSI | 复用 + 新通知 channel |

---

## 11. 修订记录

| 日期 | 说明 |
|---|---|
| 2026-10-19 | 初稿。只读调研 + 设计草案；未改产品代码与项目权威文档。 |
