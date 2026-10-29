# Phase C 真机验收（K40 `2696813b` + 眼镜 `1901092544027662`，2026-10-15）

**一句话**：眼镜端透明背景（C1）与手机端外壳/菜单/四页签/上下文切换/CXR 迁移（C6–C10）**已在真机截图验证**；
眼镜端的**带内容的左右分栏、加载动画、唤醒应答、主动介入**（C2 的内容半边、C3、C4、C5）**本轮未取得端到端真机读数**，
按项目规则如实记为未实测，不写成通过。

**两端同一次构建**：手机与眼镜的 APK 都来自 `52d398b` 之后同一棵树的 `assembleDebug`，同一次会话内 `install -r` 安装成功。
**手机 provision 未动**（只用 `install -r`）。眼镜端两次都是 `install -r` 直接成功，本轮**没有** uninstall。

---

## 1. 复现命令

```powershell
$env:JAVA_HOME="D:\Tools\JDK"
.\gradlew.bat :phone-app:assembleDebug :glasses-app:assembleDebug
& $adb -s 2696813b install -r phone-app\build\outputs\apk\debug\phone-app-debug.apk
& $adb -s 1901092544027662 install -r glasses-app\build\outputs\apk\debug\glasses-app-debug.apk
```

截图一律 `adb shell screencap -p /sdcard/x.png` + `adb pull`（**不能用 PowerShell `>` 重定向**：它按文本处理，
产出的 PNG 无法解码；本轮实测过一次，已改）。

---

## 2. 逐项读数

| 项 | 状态 | 证据 / 范围 |
|---|---|---|
| **C1 透明背景** | **Verified（K40+眼镜截图）** | 修复前 `glasses-c1-after.png`：会话窗口上有一块**均匀 +16/255 的白覆盖**（`#101010` on black，采样自 `x=36..443, y≈258..428`）。根因是 **`ScrollView` 的 default focus highlight**（视图可获焦 ⇒ API 26+ 在它的边界上画高亮），与背景色、滚动条、窗口背景都无关（四次二分构建逐一排除）。修复 `isFocusable=false` + `defaultFocusHighlightEnabled=false` 后 `glasses-c1-final.png`：整行 `y=340` 全 `#000000`，只剩两行灰字。**绿色噪点不可见**。 |
| **C2 上下文展示** | **ImplementedUnverified（拆分见下）** | **已验证**：会话视图本身（`WAKE 上下文` 头部 + 会话窗口 + `等待提示`）在 `glasses-c1-final.png` 里渲染正常；左右分栏与滚动条显隐由 JVM 测试钉住（`ConversationViewTest` 6、`HintAutoScrollTest` 9、`HintVisibilityTest` 修复后可编译）。**未验证**：带真实内容的两侧对齐、滚动条在滑动时出现/静止时消失的**真机观感**、侧边触控（`KEYCODE_DPAD_*`）**是否真的到达本页**——本轮没有跑到"有内容在屏"的那一步。 |
| **C3 加载动画** | **ImplementedUnverified** | `BusyStateTest` 7 + `LoadingIndicatorTest` 4 + `LoadingIndicatorView`；**真机动画未取证**（需要一次真实唤醒/主动回合，见 §3）。 |
| **C4 唤醒响应** | **ImplementedUnverified** | 「在的」由 `WakeAcknowledgementTest` 5 项钉住（固定短语、只出声不上屏、快速过期、开关关闭时静默、引擎抛错不拖垮唤醒）；**真机未听到**，且**语音朗读开关默认关闭**，取证前必须先打开。 |
| **C5 主动辅助介入** | **ImplementedUnverified** | 提示携带 `optionalSpeech`（P5 已交付）＋ `publishBusy` 电平（`ConversationDisplayCallsTest` 11 项）；**真机未跑**。 |
| **C6 菜单** | **Verified（手机截图）** | `phone-drawer.png`：会话管理（开始/暂停/停止 + 手动求助）、选择插件、诊断三块都在抽屉里。诊断页按业主裁决搬进菜单。 |
| **C7 主界面** | **Verified（手机截图）** | `phone-shell.png`：顶部栏＝插件名「智能助手」＋右上角会话状态指示器「未开始」＋左上角菜单；主体是激活插件的专属 UI。 |
| **C8 底部四页签** | **Verified（手机截图，技能页未截）** | `phone-shell.png` / `phone-skills.png`（实际落在「记忆」）/ `phone-settings.png`：助手、技能、记忆、设置四个页签在底部，顺序与 §6.7 的表一致；记忆页渲染真实记忆条目，设置页渲染模型档案与本地模型状态。**技能页截图未取**，其"空目录 vs 不可读"的区分由 `SkillDirectoryViewTest` 6 项钉住。 |
| **C9 上下文切换** | **Verified（手机截图）** | `phone-shell.png`：助手页顶部「唤醒 / 主动」分段切换，页面写明"只读视图…不影响正在运行的会话"，与眼镜读同一份 `ConversationLog`。 |
| **C10 CXR 会话迁移** | **Verified（手机截图）** | 抽屉里「已连接眼镜」状态行（`cxrStatusLabel` + `cxrAuthorizationLabel`），与旧「会话」页底部同一批事实、同一份实现。 |

**眼镜清理（业主 2026-10-15 授权）**：`edith.glasses.probe`、`edith.glasses.coexistence` 已卸载成功；
`pm list packages -3` 只剩 `edith.glasses` 与两个厂商件（保留）。

---

## 3. 本轮**未**取得的读数，以及为什么

1. **一次完整的唤醒回合（C2 内容 / C3 / C4 / C5）**：需要"手机侧会话已就绪 + 注入唤醒音频 + 说一句请求"三步都成功。
   本轮把会话起点跑通了（见 §4），但没有走到注入成功那一步；**没有 P3_WAKE 行**，因此不写任何关于动画或「在的」的结论。
2. **侧边触控是否到达页面**：官方文档说明那是 `KeyEvent`，且注明"系统保留事件不一定允许应用拦截"。
   本轮的实现是 `View.addOnUnhandledKeyEventListener`（minSdk 28 可用平台 API，样本的 `ViewCompat` 版本是为 API<28）。
   **没有真机按键事件的读数**，所以"左右滑切换"只能算实现完成。
3. **滚动条显隐的人眼观感**：依赖 §1 的端到端回合。

## 4. 本轮顺带发现并记录的一条既有行为（不是本轮引入）

启动会话时日志先出现：

```
activation refused result=Failed selected=smart-assistant available=[…TextOutput 不在列表里…]
  cause=CapabilityMissing: required provider unavailable
display retry: syncId=… state=Paused activated=null available=[…TextOutput 在了…]
```

即：**眼镜显示链路在时钟交换完成前不提供 `TextOutput`，插件的硬依赖因此第一次激活被拒**，随后由
`display retry` 拉起来（18:27 那一轮 UI 最终显示「已就绪」）。这是 P3.3 起的既有设计，本轮只是第一次在
**新 UI** 上看到它；记录在此，因为它会让"点开始后先看到未开始/已暂停"看起来像回归。

## 5. 全量 JVM（收尾，`--rerun-tasks`）

见 `ACCEPTANCE.md` 的 Phase C 段与 `.local/phase-c-final.log`：**2306 / 0 / 0 / 0**（基线 2197，本轮 +105）。
眼镜端 androidTest **编译通过**（基线时编译不过，见 Task 0 的对照日志）。

## 6. 证据文件清单

| 文件 | 内容 |
|---|---|
| `.local/phase-c-probe/glasses-c1-after.png` | 修复前：会话窗口上的 +16/255 白覆盖（焦点高亮） |
| `.local/phase-c-probe/glasses-c1-final.png` | 修复后：`y=340` 全 `#000000`，无绿色噪点 |
| `.local/phase-c-probe/glasses-red.png` | 决定性实验：把容器涂红，白覆盖仍在 ⇒ 不是我们的背景色 |
| `.local/phase-c-probe/phone-shell.png` | C7/C8/C9：顶栏、四页签、助手页与只读切换 |
| `.local/phase-c-probe/phone-drawer.png` | C6/C10：会话管理 + 选择插件 + 诊断 |
| `.local/phase-c-probe/phone-skills.png` | C8 记忆页（真实记忆条目） |
| `.local/phase-c-probe/phone-settings.png` | C8 设置页（模型档案、本地模型状态） |
| `.local/phase-c-glasses-androidtest-before.log` / `-after.log` | androidTest 从"编译不过"到"编译通过"的对照 |
| `.local/phase-c-baseline2.log` / `.local/phase-c-final.log` | 开工基线与收尾的全量计数 |
