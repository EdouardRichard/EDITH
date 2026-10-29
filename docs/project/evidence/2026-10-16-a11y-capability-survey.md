# Android 无障碍服务通道能力实测（K40 / Android 13 / MIUI）——给 LLM agent 的原子操作清单

- 日期：2026-10-16
- 设备：红米 K40 `2696813b`（`M2012K11AC` / MIUI V816 / Android 13 / API 33）
- 探针：`phone-accessibility-probe`，新增 S1 调查组件（`A11ySurveyService` / `A11ySurveyNoIdsService` /
  `A11ySurveyActivity`），**只读平台 API，只写 logcat**
- 前置：`D46`（K70 不再作为目标载体）、`D65`（K40 通过即视为交付设备通过）、`D53`（日志只记计数/id，不记正文）
- 产物：`phone-accessibility-probe-debug.apk`，3 163 960 B，
  SHA-256 `5784E146A1A08C947518ED4B57818729A1A06DC50A9171FDAC6E9EC240093E94`

> 本文中所有 `SURVEY_*` 行都是 **K40 上亲自跑出来的 logcat 原文**（标签 `EdithA11ySurvey`）。
> 标注为「文档」的条目只依据官方 javadoc/参考页链接；标注为「未验证」的条目在 §6 集中列出。
> 屏幕正文、聊天内容、联系人姓名一律未记录：本文出现的字符串全部是探针自己写的常量、类名、资源 id、包名或计数。

---

## 1. 一句话结论

**在非 root 的 K40 上，无障碍服务能给 LLM agent 的是一套「语义动作 + 结构化读屏」，但它的返回值只能读作"平台已受理"、
绝不能读作"已经生效"**——实测既拿到了「节点点击被遮挡仍生效」「`ACTION_SET_TEXT` 对未聚焦控件可用」「WebView 虚拟节点可写可点」
这类正面能力，也拿到了「动作返回 `true` 而文字没变」「坐标点击被悬浮窗吃掉」这类必须靠"屏幕真的变了"兜底的证据。

---

## 2. 能力清单表

图例：**实测** = K40 上跑通并留有 logcat 原文；**文档** = 仅官方文档依据；**未验证** = 见 §6。
「现有代码」列指 `phone-app/.../a11y/` 与 `actions/ExecutionRouter.kt` 当前的实现。

### 2.1 动作类

| 能力 | API | 前提 | 证据（实测原文 / 文档） | 可靠性边界 | 现有代码是否已用 |
|---|---|---|---|---|---|
| 按文字点击（语义） | `findAccessibilityNodeInfosByText` → 向上爬最近 `isClickable` 祖先 → `ACTION_CLICK` | 服务 `canRetrieveWindowContent` | **实测** `SURVEY_A01_TEXT_CLICK carried=true carriedClickable=false ancestor=true ancestorClass=android.widget.LinearLayout ret=true hitsDelta=1` | 承载文字的节点**本身不可点**（`carriedClickable=false`），必须爬祖先；`findAccessibilityNodeInfosByText` 是**子串+忽略大小写**匹配，取 `firstOrNull()` 有瞄错行的风险 | ✅ 已用（`EdithAccessibilityService.clickText`） |
| 按 id 点击 | `findAccessibilityNodeInfosByViewId` → `ACTION_CLICK` | 服务需 `flagReportViewIds` 才能**读回**名字（见 §2.2 A/B） | **实测** `SURVEY_A02_ID_CLICK found=true resolvedId=edith.phone.a11yprobe:id/survey_button ret=true hitsDelta=1` | 只有**设置了资源 id** 的控件可寻址：自绘/动态布局读得到节点也读不到 id（本机系统应用 136 节点里 85 个有 id，另一台自绘页面上会少得多） | ❌ **未用**（新增能力） |
| 按坐标点击 | `dispatchGesture`（单点 tap，`StrokeDescription(path,0,60ms)`）+ `GestureResultCallback` | 服务 `canPerformGestures`；`canRetrieveWindowContent` 用于回报坐标落下点 | **实测** `SURVEY_A03_COORD_TAP bounds=[32,216][1094,325] dispatched=true completed=true hitsDelta=1` | **对遮挡敏感**：`SURVEY_O03_OCCLUDED_COORD_TAP dispatched=true completed=true targetHitsDelta=0 overlayHitsDelta=1`（全屏悬浮窗覆盖按钮中心时，点击被悬浮窗吃掉）；**对布局变化敏感** | ❌ 未用（探针有坐标模式） |
| 长按 | `ACTION_LONG_CLICK` | 节点 `isLongClickable` | **实测** `SURVEY_A04_LONG_CLICK ret=true longHitsDelta=1` | 与点击同源；同样只保证"受理" | ❌ 未用 |
| 输入文本（替换式） | `ACTION_SET_TEXT` + `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` | 节点 `isEditable`；**不需要输入焦点** | **实测** `SURVEY_A05_SET_TEXT focusedBefore=…:id/survey_button focusIsEdit=false nodeEditable=true ret=true readLen=11 equalsProbe=true` | **整段替换**、不需聚焦；对 `isEditable=false` 的节点 **`ret=false`**（`SURVEY_A07 … nodeEditable=false ret=false`）；被应用自己吞掉时**返回 true 但没生效**（`SURVEY_A09_SET_TEXT_REJECTING ret=true setTextCalls=1 readLen=11 equalsProbe=false`） | ✅ 已用（但**多加了焦点要求**，见 §4） |
| 清空文本 | 同上，传空串 | 同上 | **实测** `SURVEY_A06_SET_TEXT_CLEAR ret=true readLen=9 blank=false hintLen=9 textEqualsHint=true hintTextPresent=true` | ⚠️ **清空后 `.text` 读到的是 hint 而不是空串**（9 字符 = hint 长度）⇒ 只读 `.text` 无法区分"空"与"hint 文本"，必须同时读 `hintText`/长度 | ✅ 已用（清空路径） |
| 粘贴 | `ACTION_PASTE` | **目标框必须有输入焦点**；剪贴板需有内容 | **实测** 未聚焦：`SURVEY_A08_PASTE focusedBefore=null ret=false`；已聚焦：`SURVEY_A08B_PASTE_FOCUSED focusIsEdit=true ret=true readLen=23 containsClip=true`（23 = 原 11 + 剪贴板 12） | **与 `SET_TEXT` 语义不同**：`PASTE` 在光标处**插入**、要求焦点；未聚焦直接 `false` | ❌ 未用 |
| 勾选 / 选中 | 勾选：**没有** `ACTION_CHECK/ACTION_UNCHECK`（编译期即不存在），用 `ACTION_CLICK` 切换 + 读 `isChecked`；选中：`ACTION_SELECT` / `ACTION_CLEAR_SELECTION` | 节点 `isCheckable` / 可选中 | **实测** `SURVEY_A12_CHECKABLE checkable=true before=false clickRet=true afterFirst=true secondClickRet=true afterSecond=false`；`SURVEY_A13_SELECT ret=true selectedAfter=true clearRet=true selectedAfterClear=false` | 勾选是"点击的副作用"，平台没有独立动词；状态**能读回**（`isChecked`） | ❌ 未用 |
| 滚动 | `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`（作用在 `isScrollable` 节点上） | 节点 `isScrollable` | **实测** `SURVEY_A16_SCROLL_FORWARD ret=true before=0-13 after=13-27 moved=true`；`A18_SCROLL_BACKWARD ret=true from=48-59 to=34-47` | **到顶/到底返回 `false`**：`A17_SCROLL_TO_END scrolls=4 atEnd=48-59 retAtEnd=false movedAtEnd=false` ⇒ 滚动**不是**"返回 true 但没动"的那一类，边界是诚实的 | ❌ 未用（探针 A1 用过） |
| 把屏外节点滚进视野 | `AccessibilityAction.ACTION_SHOW_ON_SCREEN.id` | 节点已在树里（被布局但被裁剪） | **实测** `SURVEY_A19_SHOW_ON_SCREEN found=true visibleBefore=false ret=true visibleAfter=true` | 只对**已布局**的节点有效；`ListView/RecyclerView` 未回收出来的行不在树里，无从寻址 | ❌ 未用 |
| 展开 / 折叠 | `ACTION_EXPAND` / `ACTION_COLLAPSE` | **控件必须在 `onInitializeAccessibilityNodeInfo` 里自己声明这两个 action** | **实测** `SURVEY_A20_EXPAND_COLLAPSE before=EXPANDPROBE_SHUT expandRet=true expanded=EXPANDPROBE_OPEN collapseRet=true collapsed=EXPANDPROBE_SHUT` | 声明了才有效；第三方 App 是否声明**未验证**（见 §6） | ❌ 未用 |
| 聚焦 | `ACTION_FOCUS` | 节点可聚焦 | **实测** `SURVEY_A15_FOCUS ret=true focusedIsEdit=true focused=edith.phone.a11yprobe:id/survey_edit` | 是 `SET_TEXT` 之外的补充；`findFocus(FOCUS_INPUT)` 可读回 | ❌ 未用 |
| 滑动 / 手势 | `dispatchGesture`（`Path.moveTo/lineTo` + 时长） | `canPerformGestures` | **实测** `SURVEY_A22_SWIPE dispatched=true completed=true before=0-13 after=19-33` | 同坐标点击：受遮挡与动画影响；无"元素"概念 | ❌ 未用（探针有） |
| 多步手势（一次手势内多击） | 同一个 `GestureDescription` 里放两段**时间不重叠**的 `StrokeDescription` | 同上 | **实测** `SURVEY_A21_MULTI_STEP_TAPS dispatched=true completed=true hitsDelta=2 ownWindow=true` | 两击都落地；比"多次 `dispatchGesture`"少一次跨进程往返 | ❌ 未用 |
| 全局动作：返回 | `performGlobalAction(GLOBAL_ACTION_BACK)` | 服务已连接 | **实测** `SURVEY_G07_BACK_FROM_SHADE ret=true windows=…(主屏 3 窗口恢复)`（下拉通知栏被关掉） | 返回值只表示"受理"；**在 Settings 顶层按返回没有观察到窗口变化**（`G02_BACK_LEAVES_APP ret=true active=com.android.settings`）——本机任务栈里还有另一个 Settings 页面，无法定论 | ❌ 未用 |
| 全局动作：主页 | `GLOBAL_ACTION_HOME` | 同上 | **实测** `SURVEY_G02_BACK_LEAVES_APP … active=com.android.settings` → `SURVEY_G03_HOME ret=true active=com.miui.home` | 可靠，可直接用"落到桌面"当判据 | ❌ 未用 |
| 全局动作：最近任务 | `GLOBAL_ACTION_RECENTS` | 同上 | **实测** `SURVEY_G04_RECENTS ret=true windows=…APPLICATION:active=true:focused=true:pkg=com.miui.home` | MIUI 把最近任务画在**桌面进程**里，因此只能观察到"桌面窗口变活跃"，看不到独立的 recents 窗口 | ❌ 未用 |
| 全局动作：通知栏 | `GLOBAL_ACTION_NOTIFICATIONS` | 同上 | **实测** `SURVEY_G06_NOTIFICATIONS ret=true windows=SYSTEM:active=true:focused=true:bounds=[0,0][1080,2400]:pkg=com.android.systemui` | 整屏 SYSTEM 窗口变 active+focused，是干净的判据 | ❌ 未用 |
| 全局动作：锁屏 / 快捷设置 / 电源菜单 / 截图 | `GLOBAL_ACTION_LOCK_SCREEN` / `QUICK_SETTINGS` / `POWER_DIALOG` / `takeScreenshot()` | 见文档 | **未执行**：`SURVEY_G08_NOT_EXECUTED quickSettings=5 lockScreen=8 powerDialog=6 reason=state_on_shared_acceptance_device` | 锁屏会打断共享验收设备上的另一条工作线，本次**故意不跑** | ❌ 未用 |
| 读剪贴板 | `ClipboardManager.getPrimaryClip()` | Android 10+：应用需**有焦点** | **实测（反面）** `SURVEY_X07_CLIPBOARD_BACKGROUND hasClip=false items=-1 matchesProbe=false`（App 自己刚设过剪贴板，退到后台再读即为 null） | **无障碍服务不能当剪贴板外泄通道**：后台读不到 | ❌ 未用 |

### 2.2 读取类

| 能力 | API | 前提 | 证据 | 可靠性边界 | 现有代码是否已用 |
|---|---|---|---|---|---|
| 当前窗口的节点树 | `AccessibilityService.rootInActiveWindow` | `canRetrieveWindowContent` | **实测（自己的 App）** `SURVEY_T01_SHAPE nodes=253 maxDepth=56 maxChildCount=120 truncated=false nullChildren=0 visible=35 withText=191 withDesc=1 withViewId=18 withClassName=253 clickable=7 editable=2 scrollable=1 checkable=1 withBounds=253 distinctActions=16` | 只能看到**一个**窗口（活跃窗口）；`isVisibleToUser` 只有 35/253（被 `ScrollView` 裁剪的算不可见，但**仍在树里、仍可寻址**） | ✅ 已用（`screenFingerprint`） |
| 读得到多深 | 递归 `getChild` | 无 | **实测** `maxDepth=56`（夹具是 50 层嵌套 + 外层容器）、`SURVEY_T02_LIMITS deepLabelFound=true` | 到 56 层**没有观察到平台截断**；`nullChildren=0` 说明这一遍是**完整读**而不是读了一半 | ✅（自带上限 400 节点/40 层） |
| 子节点数上限 | 同上 | 无 | **实测** `maxChildCount=120`、`SURVEY_T02_LIMITS wideChildCount=120 wideExpected=120` | 120 个子节点全部暴露，**没有观察到 50 之类的截断** | — |
| `findAccessibilityNodeInfosByText` | 同名字符串 | 无 | **实测** `SURVEY_T04_FIND_BY_TEXT exact=1 substring=1 lowercase=1 byContentDescription=1 absent=0` | **子串匹配 + 忽略大小写 + 也匹配 `contentDescription`** ⇒ 精确匹配必须自己做（探针里 `findExactNode` 已经这样做） | ✅ 已用（但用的是子串语义，见 §4） |
| `findAccessibilityNodeInfosByViewId` | 全限定 id `pkg:id/name` | 文档称需 `flagReportViewIds` | **实测 A/B（同一屏、两个只差 flag 的服务）** `SURVEY_X08_A_B_SYSTEM_IDS withFlag_visibleIds=24 noFlag_visibleIds=0 withFlag_lookupAndroidIdTitle=13 noFlag_lookupAndroidIdTitle=13` | ⚠️ **实测与常见读法不同**：`flagReportViewIds` 决定 `getViewIdResourceName()` **能不能读回名字**（24 → 0），但**不决定按 id 查找能不能命中**（13 → 13，且这是系统应用）。文档原文见下方「文档依据」 | ❌ 未用 |
| 读到自己 App 以外的窗口 | 同上 | 无 | **实测** `SURVEY_X01_ACTIVE pkg=com.android.settings readable=true isOwn=false`；`SURVEY_X02_SYSTEM_APP_SHAPE nodes=136 maxDepth=14 withText=19 withDesc=1 withViewId=85 withClassName=136 clickable=19 scrollable=1 visible=98 truncated=false` | 跨应用整树可读；系统应用的节点**只有 19/136 带文本**（列表项文本稀疏），读屏不等于能"读懂" | ✅ 已用 |
| 系统应用的 id 与文本 | 同上 | 无 | **实测** `SURVEY_X04_SYSTEM_VIEW_IDS count=24 sample=android:id/content\|android:id/icon\|android:id/input\|android:id/inputArea\|android:id/summary\|android:id/title`；`SURVEY_X05_SYSTEM_TEXT_READ matched=true textLen=4 clickable=false class=android.widget.TextView clickableAncestor=android.widget.LinearLayout` | 系统应用大量使用**框架 id**（`android:id/title` 之类），同屏 13 个节点共用同一个 id（`lookupAndroidIdTitle=13`）⇒ 按 id 找行**不唯一** | ❌ 未用 |
| 多窗口 / 悬浮窗 / 输入法 | `getWindows()`（每个窗口各自的 `root`） | **必须** `flagRetrieveInteractiveWindows` | **实测 A/B** `SURVEY_A_B_WINDOWS withFlag_windows=3 … noFlag_windows=0`（同一屏，只差这一个 flag）；输入法：`SURVEY_A23_IME windows=4 hasImeWindow=true windowList=SYSTEM:…pkg=com.miui.home\|SYSTEM:…pkg=com.android.systemui\|INPUT_METHOD:…pkg=com.baidu.input_mi\|APPLICATION:active=true:focused=true:pkg=edith.phone.a11yprobe` | **没有这个 flag，`getWindows()` 返回空列表**（不是"返回自己一个"）；输入法窗口类型是 `TYPE_INPUT_METHOD`，第三方输入法也能看到 | ❌ 未用 |
| 悬浮窗内容可读性 | `getWindows()` → `window.root` | 同上 | **实测** `SURVEY_O01_OVERLAY visible=true inActiveTree=0 inActiveTreeByWalk=0 inAnyWindow=1 windows=…SYSTEM:…bounds=[0,80][1080,2356]:pkg=edith.phone.a11yprobe\|…` | **悬浮窗内容不在 `rootInActiveWindow` 里**（0），只能靠枚举 `getWindows()` 读（1）；`TYPE_APPLICATION_OVERLAY` 在无障碍侧被报成 `TYPE_SYSTEM` | ❌ 未用 |
| 被标为 not-important 的容器 | 自动（重要后代被上提） | 无 | **实测** `SURVEY_T05_NOT_IMPORTANT_CONTAINER nestedTextStillFound=1` | 容器声明 `IMPORTANT_FOR_ACCESSIBILITY_NO` 后，后代重要节点**仍然可达**（会被重挂到最近的重要祖先下） | — |
| 空输入框怎么读 | `text` vs `hintText` | 无 | **实测** `SURVEY_A06… readLen=9 blank=false hintLen=9 textEqualsHint=true hintTextPresent=true` | ⚠️ **`.text` 会返回 hint**：只读 `.text` 无法区分"空框"与"框里有 hint 文字" | ⚠️ 现有 `setText` 不读回，暂不受影响 |
| WebView 虚拟节点树 | `getChild` 遍历 + `isEditable` | 无 | **实测** `SURVEY_W01_WEB_SHAPE nodes=6 maxDepth=4 editable=1 clickable=2 withText=4 withViewId=2 withClassName=6 nullChildren=0` | 树很小但可读；输入框节点 `className=android.widget.EditText`、`nodeViewId=f`（HTML id 被映射成 viewId） | — |
| WebView 里的 `findAccessibilityNodeInfosByText` | 同上 | 无 | **实测（反面）** `SURVEY_W03_WEB_CLICK byFindNode=false byManualWalk=true ret=true jsClicksDelta=1` | ⚠️ **在 WebView 虚拟树里 `findAccessibilityNodeInfosByText` 找不到明明存在的文本**，必须自己遍历子树匹配 | — |

### 2.3 事件类

| 能力 | API | 前提 | 证据 | 可靠性边界 | 现有代码是否已用 |
|---|---|---|---|---|---|
| 事件类型与计数 | `onAccessibilityEvent` + `accessibilityEventTypes` | 在服务配置里声明要收的类型 | **实测** `SURVEY_E03_HISTOGRAM total=14 distinct=3 counts=2048=8,1=5,8=1`；`SURVEY_E04_EVENT_TYPES types=1,8,2048`；`SURVEY_E05_EVENT_CONSTANTS windowStateChanged=32 windowContentChanged=2048 viewFocused=8 viewClicked=1` | 只有声明了 `typeAllMask` 的服务收得到 `CONTENT_CHANGED`；只声明 `typeWindowStateChanged` 的孪生服务一条都没收到（配置决定可见性，不是平台不给） | ❌ **`onAccessibilityEvent` 目前是空实现** |
| 事件字段 | `eventType` / `packageName` / `eventTime` … | 同上 | 同上一行（本次只用了类型与到达时刻） | 事件正文含屏幕文本，按 `D53` **没有记录、也不建议落盘** | — |
| 「等界面稳定」 | 事件静默窗口 | 同上 | **实测** `SURVEY_E02_SETTLE timeToQuietMs=508 quietThresholdMs=250` | 一次变更后约 0.5 s 内可判定静默；阈值需按 App 调 | ❌ 未用 |
| 「等某个界面出现」 | `TYPE_WINDOW_STATE_CHANGED` vs 轮询树 | 同上 | **实测（两次）** `SURVEY_X00_SCREEN_SWITCH firstEventMs=58 firstWindowStateMs=58 treeNamedNewAppMs=754`；复跑 `firstEventMs=44 firstWindowStateMs=44 treeNamedNewAppMs=92` | **事件比树领先约 0.03–0.7 s**（复跑只差 48 ms，说明这个领先量本身不稳定） | ❌ 未用 |
| 事件是否"每变必有" | 同上 | 同上 | **实测（反面）** `SURVEY_E01_EVENT_VS_TREE firstEventMs=-1 firstContentChangedMs=-1 treeVisibleMs=13`（一次真实状态变更**一个事件都没有**，而树 13 ms 就反映出来了） | ⚠️ **事件不能当唯一完成信号**：应用不主动 announce 的变更（例如通过 `performAccessibilityAction` 改 `contentDescription`）不产生事件 ⇒ 事件只适合当"提示"，真值仍要读树 | — |

### 2.4 已知不可为 / 边界

| 事项 | 依据 | 结论 |
|---|---|---|
| 没有 `ACTION_CHECK` / `ACTION_UNCHECK` 常量 | **实测（编译期）**：`e: Unresolved reference 'ACTION_UNCHECK'` / `'ACTION_CHECK'` | 勾选只能靠"点击 + 读 `isChecked`" |
| 没有 `AccessibilityNodeInfo.ACTION_SHOW_ON_SCREEN` 常量 | **实测（编译期）**：`e: Unresolved reference 'ACTION_SHOW_ON_SCREEN'` | 要用 `AccessibilityAction.ACTION_SHOW_ON_SCREEN.id` |
| 后台应用读不到剪贴板 | **实测** `SURVEY_X07_CLIPBOARD_BACKGROUND hasClip=false` | 无障碍通道不能外泄剪贴板内容 |
| 后台应用不能 `startActivity` | **实测**：`G02_SETTINGS_ON_TOP active=com.miui.home`（App 在后台时拉起 Settings 被拒），改为前台先拉之后 `G01 … active=com.android.settings` | 任何"打开某 App"的动作必须发生在本 App 仍在前台时 |
| 被遮挡时坐标点击会打到遮挡者 | **实测** `SURVEY_O03_OCCLUDED_COORD_TAP … targetHitsDelta=0 overlayHitsDelta=1` | 坐标类动作必须假定"可能打在别人身上" |
| 屏幕变化的窗口变化会被程序化点击改掉 | **实测** `SURVEY_O02_OCCLUDED_NODE_CLICK ret=true hitsDelta=1`（同一时刻，被全屏悬浮窗完全盖住的按钮，语义点击照样生效） | 语义点击**无视遮挡**（框架直接在应用进程里 `performClick`），这是它相对坐标点击最硬的优点 |
| `FLAG_SECURE` 窗口 | **实测（本 App 自己的窗口）** `SURVEY_S02_SECURE_WINDOW found=1 byIdFound=1 editReadLen=11 setTextRet=true wroteLen=11 wroteEqualsProbe=true` | **`FLAG_SECURE` 并不遮挡无障碍**：自己 App 的 secure 窗口可读可写。⚠️ 但**其他 App 的 secure 窗口、锁屏/Keyguard** 本次**未验证**（见 §6） |
| 长文本截断 | **未测** | `AccessibilityNodeInfo` 对超长文本的截断上限本次没有测（见 §6） |
| 跨用户 / 工作资料 | **未测** | 本次只测了 user 0 |
| 分屏 / 多窗口模式 | **未测** | 未尝试进入分屏（见 §6） |

**文档依据（URL）**

- `getViewIdResourceName()`「客户端必须设置 `FLAG_REPORT_VIEW_IDS` 才能上报 source view id」：
  <https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getViewIdResourceName()>
  （javadoc 原文亦见 <https://learn.microsoft.com/en-us/dotnet/api/android.views.accessibility.accessibilitynodeinfo.viewidresourcename?view=net-android-35.0>）
- `findAccessibilityNodeInfosByViewId()`：同一条 flag 说明 + 「`SurfaceView.setChildSurfacePackage` 嵌入的子树找不到，需自行遍历子节点」：
  <https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#findAccessibilityNodeInfosByViewId(java.lang.String)>
  （javadoc 原文亦见 <https://learn.microsoft.com/en-us/dotnet/api/android.views.accessibility.accessibilitynodeinfo.findaccessibilitynodeinfosbyviewid?view=net-android-35.0>）
- `AccessibilityServiceInfo` 各 flag 语义：
  <https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo>
- `AccessibilityService`（`getWindows()` / `dispatchGesture()` / `performGlobalAction()` / `takeScreenshot()`）：
  <https://developer.android.com/reference/android/accessibilityservice/AccessibilityService>
- `AccessibilityNodeInfo` 动作常量（`ACTION_SET_TEXT` / `ACTION_PASTE` / `ACTION_SCROLL_*` …）：
  <https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo>

> 说明：`developer.android.com` 的参考页是 JS 渲染，本次只成功抓取到导航骨架；上表中所有**行为结论都来自 K40 实测**，
> 文档链接仅用于标注"平台公开了这一项"。其中 `findAccessibilityNodeInfosByViewId` 的 javadoc 原文已从上述镜像页逐字读到。

---

## 3. 可靠性与失败模式

### 3.1 「返回 true 但没生效」——实测到的确切例子

1. **应用把文字吞了**：`SURVEY_A09_SET_TEXT_REJECTING ret=true setTextCalls=1 readLen=11 equalsProbe=false`
   —— 探针夹具里那个"自己保管文本"的输入框（`performAccessibilityAction` 里对 `ACTION_SET_TEXT` 直接 `return true`）
   收到动作、返回成功，**文本一个字没变**。这正是 `AccessibilityExecutionBackend` 的 `NO_SCREEN_CHANGE` 拒绝码存在的原因。
   *（WeChat 的聊天输入框本次**没能测**：K40 上未安装 `com.tencent.mm`，见 §6。）*
2. **空框读出来是 hint**：`SURVEY_A06_SET_TEXT_CLEAR ret=true readLen=9 blank=false textEqualsHint=true`
   —— 「清空成功」这个判断，如果只看 `.text` 长度会得出**相反**结论。
3. **在 Settings 顶层按返回，1.5 s 后活跃包没变**：`SURVEY_G02_BACK_LEAVES_APP ret=true active=com.android.settings`
   —— `performGlobalAction` 返回 `true` 不代表界面动了；本次无法定论（该机任务栈里还压着另一个 Settings 页面）。

### 3.2 「返回 false」这一类（诚实失败，可当判据）

| 场景 | 实测 |
|---|---|
| 点不可用控件 | `SURVEY_A11_CLICK_DISABLED nodeClickable=true nodeEnabled=false ret=false hitsDelta=0` |
| 往非可编辑节点写文本 | `SURVEY_A07_SET_TEXT_NONEDITABLE nodeEditable=false ret=false` |
| 未聚焦时粘贴 | `SURVEY_A08_PASTE focusedBefore=null ret=false` |
| 滚到边界再滚 | `SURVEY_A17_SCROLL_TO_END … retAtEnd=false` |
| 窗口都换掉了还点旧节点 | `SURVEY_A24_STALE_NODE captured=true activeWhileAway=com.android.settings ret=false hitsDelta=0` |

> 结论：**"false" 是可信的；"true" 不是。** 成功判据必须是外部可观测状态（屏幕指纹 / 目标节点状态 / App 自身状态）。

### 3.3 界面没加载完就点

- 第一次 `actions` 跑的时候，`SURVEY_WINDOW pkg=com.android.settings isOwn=false` —— 探针 Activity 还没成为活跃窗口，
  `A01` 于是"找不到文字"，看起来像平台不支持。加 `warmUp()`（等"自己是活跃窗口 **且** 自己的树读得回来"）之后：
  `SURVEY_WARMUP ownWindow=true treeReadable=true waitedMs=125`，`A01` 立刻 1/1 成功。
- **这是本次最容易把探针缺陷误读成平台能力的坑**：任何"按文字点"的原语，在用之前都必须先确认目标窗口就是你以为的那个窗口。

### 3.4 本进程读自己的树会被自己的主线程拖住

- 第一次跑时活动窗口是自己的 App，`A16/A17/A18` 全部读到 `before=none`、`ret=false`，看起来像"不能滚动"；
  第二次加了显式高度后 `SURVEY_A15B_SCROLL_NODE … bounds=[0,1110][1080,2356]`、`A16 … ret=true moved=true`。
- 期间系统还打了 `InputDispatcher: Not sending touch gesture … because it is not responsive`。
- 机制：无障碍请求由**目标 App 的主线程**服务，活跃窗口如果是自己，主线程一忙，`getChild()` 就会返回 `null`，
  读到的树是**残缺**的。为此服务里加了 `nullChildren` 计数（空闲时 `nullChildren=0`）作为"这一遍读全了没有"的凭证。
- 边界：这只影响"读自己的 App"；跨应用读（Settings）不受影响（`X02 … truncated=false`）。

### 3.5 探针自身踩过的坑（用于说明数据的可信度，均已修复）

| 现象 | 根因 | 修复 |
|---|---|---|
| 进程启动即崩，`dumpsys accessibility` 显示 `Crashed services:{…}`、`accessibility_enabled` 被系统改回 0 | `RejectingEditText` 覆写了 `setText`，而 `TextView` 构造期就会调用它，此时 Kotlin 字段还是 null → NPE | 改为在 `performAccessibilityAction` 拦截；计数器放成静态 |
| 滚动/滑动/`SHOW_ON_SCREEN` 全部"无效" | 上面控件吃满整屏，`ScrollView` 高度为 0（`bounds=[0,2356][1080,2356]`） | 每个控件给显式像素高度，`ScrollView` 拿到 1370 px |
| 换 scenario 没反应 | `am start` 把 intent 投给已存在的实例（`onNewIntent`），`onCreate` 不再执行 | 实现 `onNewIntent` + 每次跑完 `finish()` |
| `secure` 场景 `SURVEY_EXCEPTION where=RuntimeException` | `Dialog` 构造会建 `Handler`，在工作线程上建会抛 `Looper.prepare()` | 把 `Dialog` 的构造搬进 `runOnUi` |
| 悬浮窗"遮挡测试"其实没遮住 | 按钮矩形在布局完成前量到 `[0,0][0,0]`；且 `gravity=TOP` 的窗口定位在**内容区**（比屏幕坐标低 80 px 状态栏） | 轮询到非空矩形；改用**全屏**悬浮窗 |
| WebView "没加载好" | 拿 HTML 的 `id` 当文本去找 | 改用 `isEditable` 判定就绪 |
| 事件直方图疑似翻倍 | 两个服务同时启用，但只有 `typeAllMask` 那个收得到 `CONTENT_CHANGED` | 无（解释而非修复）：`2048=8,1=5,8=1` 与 5 次点击自洽 |

---

## 4. 与本项目现有实现的对齐分析

### 4.1 重复（现有实现已经做对，不要重造）

| 现有 | 实测是否支持 |
|---|---|
| `clickText`：`findAccessibilityNodeInfosByText` + 爬最近 `isClickable` 祖先 + `ACTION_CLICK` | ✅ **完全被 A01 复现**：`carriedClickable=false ancestor=true ancestorClass=android.widget.LinearLayout ret=true` |
| `setText`：`ACTION_SET_TEXT` + `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` | ✅ 被 A05 复现（`ret=true equalsProbe=true`） |
| 「成功 = 屏幕指纹变了」而不是「动作返回 true」 | ✅ **被 A09 反向证明必要**：`ret=true` 而文本没变 |
| `AccessibilityUi` 端口 + `ExecutionRouter` 三通道合并 | ✅ 与本次结论一致：通道可用性必须每次现读（本次亲历"服务崩溃→`Crashed services`→`accessibility_enabled=0`"） |

### 4.2 新的能力（现有实现里没有，实测可用）

按"对 LLM agent 的价值/实现成本"排序：

1. **按 id 点击**（`A02`）：`resolvedId=…:id/survey_button`，是比文字更稳的锚点（文字会变、会重名、会子串误配）。
2. **长按**（`A04`）、**滚动前/后**（`A16/A17/A18`）、**把屏外节点拉进视野**（`A19`）：三个独立的动词，各自都有边界实测。
3. **读屏**（`T01/T04/X02/X05`）：现在完全没有。`rootInActiveWindow` 一次能给出节点数/文本/描述/类名/可点/可编/可滚/勾选/bounds/动作集。
4. **按 id 或按字段寻址后写文本**（`A05`）：`SET_TEXT` 不需要焦点也能生效。
5. **全局动作**：`HOME`/`BACK`/`NOTIFICATIONS`/`RECENTS`（`G03/G07/G06/G04`）。
6. **手势**：坐标点击、滑动、一次手势多击（`A03/A22/A21`）。
7. **`getWindows()` 读其它窗口**（`A_B_WINDOWS` / `O01` / `A23`）：悬浮窗、输入法、系统窗口。
8. **事件驱动的"等稳定/等出现"**（`E02/E03/X00`）。

### 4.3 现有实现里应该被替换 / 修正的地方

| 位置 | 问题（实测依据） | 建议 |
|---|---|---|
| `AccessibilityExecutionBackend` 只有 `tap_text` / `type_text` | 两个动作无法覆盖上面 8 类原语；LLM 拿不到"读"就只能盲点 | 扩动作集；但**不要一次全开**，见 §5 |
| `EdithAccessibilityService.clickText` 用 `findAccessibilityNodeInfosByText(...).firstOrNull()` | 该 API 是**子串**匹配（`T04 substring=1`），`firstOrNull()` 可能命中"包含该文字的另一个控件"（T3b 的第 2 个缺陷与 A1 的 `findExactNode` 都是这个坑） | 先精确匹配 `text ==` / `contentDescription ==`，失败再退回子串 |
| `EdithAccessibilityService.setText` 依赖 `findFocus(FOCUS_INPUT)` + `isEditable` | **A05 实测：未聚焦也能写**（`focusIsEdit=false ret=true`）。当前实现因此**只能写"当前聚焦的那个框"**，无法指定目标框 | 允许按 id/文字寻址到目标可编辑节点，去掉焦点前置条件（保留 `isEditable` 检查，它确实是硬门槛：`A07 ret=false`） |
| `screenFingerprint()`：BFS 上限 `MAX_NODES=400`，只取 `text`/`contentDescription` | ① 超限时指纹可能**不变**，`NO_SCREEN_CHANGE` 会变成假阴性；② `A06` 证明 `.text` 可能是 hint；③ 自绘列表页可能整页无文本（系统应用 136 节点里仅 19 个有文本） | 指纹里加入结构事实（节点数、可点节点数、bounds 摘要），并把"是否因为超限而截断"作为独立信号上报；或直接换成"关键节点状态"判据 |
| `onAccessibilityEvent` 空实现 | 事件能把"新界面出现"提前 **0.03–0.7 s** 报出来（`X00`），但**不是每变必有**（`E01 firstEventMs=-1`） | 用它当**触发器/加速器**，真值仍以有界轮询读树为准；不要用事件当成功判据 |
| 成功判据是"指纹变了" | 方向对，但**"变了但变错"仍然算成功**（T3b/A1 已记过这条） | 若上游能给出"意图标记"（A1 的做法：只有目的屏才有的字符串），应升级为"变了且命中意图标记" |
| 没有任何"读"的动作暴露给 LLM | 目前 LLM 只能盲动作 | 见 §5 |

---

## 5. 对「LLM agent 该拿哪些原语」的建议（≤10 条）

1. **保留语义点击，但把文字匹配改成"先精确、后子串"**——子串匹配已经实测会命中非目标节点，而 T3b/A1 两次踩过同一个坑。
2. **加"按 id 点击"**——`A02` 一次命中，且不受文字变化/重名影响；代价只是需要目标 App 有资源 id。
3. **加只读的"看屏幕"原语（节点数/文本/描述/id/类名/可点/可编辑/bounds，带上限与截断标记）**——没有读的动作，LLM 只能盲猜；这是当前最大的缺口。
4. **写文本用 `ACTION_SET_TEXT`，去掉焦点前置条件，允许按 id 指定目标框**——`A05` 实测未聚焦可写；`A07` 证明 `isEditable` 才是硬门槛。
5. **不要把 `ACTION_PASTE` 当输入原语**——它要有焦点（`A08 ret=false` → `A08B ret=true`）且依赖剪贴板，而后台服务读不到剪贴板（`X07 hasClip=false`）。
6. **把每个动作的返回值一律翻译成"已受理"，成功必须另找外部证据**——`A09` 给出了"返回 true 文本没变"的实测样本，`A17/A24/A11/A07` 给出了"返回 false"的可信样本。
7. **加"等界面稳定/等目标出现"，以 `TYPE_WINDOW_STATE_CHANGED` 为号、以有界读树为判**——事件领先 0.03–0.7 s 但会漏（`E01`），所以是加速器不是判据。
8. **有节点就绝不用坐标**——坐标点击会被遮挡吃掉（`O03 targetHitsDelta=0 overlayHitsDelta=1`），而同一时刻语义点击在遮挡下照样生效（`O02 hitsDelta=1`）；坐标只作为"没有节点可用"时的兜底。
9. **要读别的窗口（悬浮窗/输入法/系统弹窗）必须走 `getWindows()`，并且必须在服务配置里声明 `flagRetrieveInteractiveWindows`**——少了它 `getWindows()` 返回 0（`A_B_WINDOWS noFlag_windows=0`）。
10. **每个原语都要能诚实回答"我不知道"**——树读被截断（`truncated`）、`getChild` 返回 null（`nullChildren`）、空框读出 hint（`A06`）这三种情况下，不能把"没读到"当成"不存在/没变化"。

---

## 6. 未知与未验证清单

| # | 未验证项 | 为什么 | 现有证据边界 |
|---|---|---|---|
| 1 | **微信 / QQ 的聊天输入框能否被无障碍写入** | **K40 上未安装 `com.tencent.mm`**（`pm list packages` 里只有 `edith.phone` / 系统应用）。用"自己保管文本的输入框"做了**受控替代**（`A09`：`ret=true setTextCalls=1` 而文本不变），但那只是**同类机制**的建模，**不能当微信的实测结论** | 需要在装了微信的设备上重跑 `A09` 的等价场景 |
| 2 | `ACTION_PASTE` 为何在未聚焦时返回 `false`（是"必须聚焦"还是"该控件不支持"） | 只观察到 `focusedBefore=null → ret=false`、`focusIsEdit=true → ret=true` 的相关性 | 未做更细的因果分离 |
| 3 | **超长文本**的读取截断 | 本次没构造长文本 | `AccessibilityNodeInfo` 是否存在文本长度上限，本次**没有测** |
| 4 | **其他 App 的 `FLAG_SECURE` 窗口、锁屏 / Keyguard** | 只测了**自己 App** 的 secure 窗口（可读可写） | `S02` 只能说明 `FLAG_SECURE` 对无障碍**本身**不设障；系统级安全窗口另说 |
| 5 | **分屏 / 多窗口模式**下的窗口枚举与命中 | 没有进入分屏（怕在共享验收设备上留下异常状态） | 只测了单窗口 + 悬浮窗 + 输入法 + 通知栏 |
| 6 | `GLOBAL_ACTION_LOCK_SCREEN` / `QUICK_SETTINGS` / `POWER_DIALOG` / `takeScreenshot()` | **故意不执行**（会打断另一条工作线 / 留下打开的快捷设置） | `G08` 只记录了"未执行"与常数 |
| 7 | 「在 Settings 顶层按 BACK 是否真的返回」 | 返回 `true` 但 1.5 s 后活跃包仍是 `com.android.settings`（该机任务栈里另有 Settings 页面），无法定论 | `G02` 只能证明"返回值不可当判据" |
| 8 | `flagReportViewIds` 缺失时 `findAccessibilityNodeInfosByViewId` **仍能命中**的机制 | 实测两次（自己 App 1/1；系统应用 13/13），但**没有读 AOSP 实现确认原因** | 结论按"本机实测"记；换设备/换版本需复测 |
| 9 | 事件驱动等待是否**真的**比轮询快 | 只测了 2 次"启动另一个 App"（754 ms / 92 ms，方差很大） | 不足以做性能结论 |
| 10 | `dispatchGesture` 的 `onCancelled` 路径 | 全程只观察到 `onCompleted`（`completed=true`） | 未构造取消场景 |
| 11 | 跨用户 / 工作资料（user 10 等） | 只在 user 0 上测 | — |
| 12 | WebView 之外的其它虚拟节点树（Compose 语义树、Flutter、游戏自绘） | 未测 | `W01` 只覆盖 WebView |
| 13 | K70 / Android 16 | 按 `D46` 已取消适配，不在范围内 | — |

---

## 7. 复现步骤（可被他人重跑）

### 7.1 环境与构建

```powershell
cd D:\Project_new\EDITH
$env:JAVA_HOME="D:\Tools\JDK"
# 关键：探针用的是项目自己的调试签名（.local/p3/android-home/debug.keystore）。
# 不设这个变量会用 %USERPROFILE%\.android\debug.keystore，签名不符，install -r 会
# 报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
$env:ANDROID_USER_HOME="D:\Project_new\EDITH\.local\p3\android-home"
.\gradlew.bat :phone-accessibility-probe:assembleDebug --console=plain
```

产物：`phone-accessibility-probe\build\outputs\apk\debug\phone-accessibility-probe-debug.apk`
（3 163 960 B，SHA-256 `5784E146A1A08C947518ED4B57818729A1A06DC50A9171FDAC6E9EC240093E94`）

### 7.2 安装与授权（**每一步都带 `-s 2696813b`**）

```powershell
$d="2696813b"
adb -s $d install -r "D:\Project_new\EDITH\phone-accessibility-probe\build\outputs\apk\debug\phone-accessibility-probe-debug.apk"
adb -s $d shell settings put secure enabled_accessibility_services `
  "edith.phone.a11yprobe/edith.phone.a11yprobe.A11ySurveyService:edith.phone.a11yprobe/edith.phone.a11yprobe.A11ySurveyNoIdsService"
adb -s $d shell settings put secure accessibility_enabled 1
# 只有 overlay 场景需要（注意：每次 install -r 都会把这个 appop 打回 ignore）
adb -s $d shell appops set edith.phone.a11yprobe SYSTEM_ALERT_WINDOW allow
```

两个服务都连上后应看到（`adb -s $d logcat -d -s EdithA11ySurvey:I`）：

```
SURVEY_SERVICE_CONNECTED class=A11ySurveyService reportViewIds=true interactiveWindows=true includeNotImportant=true canRetrieveWindowContent=true canPerformGestures=true
SURVEY_SERVICE_CONNECTED class=A11ySurveyNoIdsService reportViewIds=false interactiveWindows=false includeNotImportant=false canRetrieveWindowContent=true canPerformGestures=true
```

### 7.3 跑每个场景

```powershell
function Run($scenario, $wait) {
  adb -s $d logcat -c
  adb -s $d shell am start -n edith.phone.a11yprobe/.A11ySurveyActivity --es scenario $scenario --ez confirm true
  Start-Sleep -Seconds $wait
  adb -s $d logcat -d -s EdithA11ySurvey:I
}
Run "actions" 80     # 动作类总表（含手势/IME/失效节点）
Run "tree"     15    # 树的形状/深度/子节点数/findByText/findByViewId A-B
Run "events"   25    # 事件直方图、事件 vs 树的首达时间、静默判定
Run "crossapp" 30    # 系统应用读屏 + 切屏延迟 + 后台剪贴板 + 系统应用 id A-B
Run "secure"   18    # FLAG_SECURE 窗口
Run "webview"  25    # WebView 虚拟节点：SET_TEXT / CLICK / findByText 失效
Run "overlay"  22    # 全屏悬浮窗：可见性 + 遮挡下的语义点击 vs 坐标点击
Run "globals"  30    # BACK / HOME / RECENTS / NOTIFICATIONS
```

### 7.4 收尾（把设备恢复原状）

```powershell
adb -s $d shell am force-stop edith.phone.a11yprobe
adb -s $d shell settings put secure enabled_accessibility_services null
adb -s $d shell settings put secure accessibility_enabled 0
adb -s $d shell appops set edith.phone.a11yprobe SYSTEM_ALERT_WINDOW default
adb -s $d uninstall edith.phone.a11yprobe
adb -s $d shell pm list packages | Select-String "edith"     # 只应剩下 edith.phone
adb -s $d shell "run-as edith.phone ls -l files"             # 9 个 provision 产物、尺寸/时间戳不变
```

本次收尾后的实测值：

```
enabled_accessibility_services=null
accessibility_enabled=0
appop=SYSTEM_ALERT_WINDOW: default
pm list packages | grep edith  ->  package:edith.phone
run-as edith.phone ls -l files ->  total 839140（9 项，含 token_embeddings_fp16.bin 393216000 B、du files = 819M）
ps -A | grep a11yprobe         ->  none
```

### 7.5 本次**没有**动过的东西

- `edith.phone`（生产 App）：**没有** uninstall、**没有** `pm clear`、**没有**写它的 `files/`；跑前跑后 `files/` 均为 9 项、819M，`embeddinggemma-*.tflite` / `laya_ml_*` / `token_embeddings_fp16.bin` / `tokenizer.json` 尺寸与 2026-10-15 的时间戳一一未变。
- 眼镜 `1901092544027662`：本次全程未对其执行任何命令。
- `phone-app`、`phone-accessibility-probe` 里**原有**的文件：只**新增**文件（`A11ySurveyService.kt`、`A11ySurveyActivity.kt`、`ids.xml`、两个 `*_config.xml`）与在 `AndroidManifest.xml` 中**追加**条目；未改动 `A11yProbeService.kt`、`A11yClickActivity.kt`、`A11yIntentActivity.kt`、`A11yProbeActivity.kt` 的任何现存代码。
- **未提交任何东西**：没有 `git add`、没有 `git commit`。
