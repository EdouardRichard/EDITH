**调查证据，未批准；不是设计，不是验收基线。**

# D0a 解锁/点亮历史调查（只读）

- 调查日期：2026-10-19
- 调查范围：工作树 `docs/project/evidence/`、`docs/project/{STATUS,ACCEPTANCE,CONTRACTS,DECISIONS}.md`、`docs/superpowers/{specs,plans}/`、`tools/`、`AGENTS.md`、`.local/`（只读）、git 历史。
- 一手来源口径：只采信设备证据、代码、脚本、命令原文与 git 提交；`docs/pre-research/` 的既有预研报告不作为证据（文末仅标注一处“预研声称、无一手读回”的项）。
- 关键区分：`KEYCODE_WAKEUP` / `setTurnScreenOn` 是**点亮/唤醒屏幕**；`wm dismiss-keyguard` / `KEYCODE_MENU` / `input swipe` 是**试图解除 keyguard**。两者不是同一件事，本文分开记。
- “未测”与“未找到证据”分开写：前者是有人尝试但没测到，后者是本仓库范围内没有记录。

---

## 1. 结论摘要（只写有证据的）

1. **PC adb 的“唤醒 + 关 keyguard”命令确实被写进过设备测试流程，并在 0.23 P1 被记录为实际修复动作（证据把它归类为“环境操作，不是 UI 操作模拟”）。** `docs/project/evidence/2026-10-18-0.23-p1-phone-assistant.md:34-52` 记录手机当时 `mWakefulness=Dozing`，随后列出 `input keyevent KEYCODE_WAKEUP`、`wm dismiss-keyguard`、`svc power stayon true`，再安装即成功。该段没有给出 keyguard 状态读回，也没有逐命令回显。
2. **更早的 P3.1（2026-10-09）也用过 PC adb 的 `KEYCODE_WAKEUP` + `KEYCODE_MENU`（注释写明 “dismiss keyguard”）+ `input swipe` + `input tap`。** 但同一份证据同时写明当时 `isKeyguardShowing=false already`（`git 8da375d:docs/project/evidence/2026-10-09-p3-1-passive-loop-e2e.md:29-32,336`）。因此这条只能证明“命令被用过”，不能证明“真的解开了一个锁屏”。
3. **P2 时期（2026-10-06 至 2026-10-13）的成文做法是人工解锁。** 交接文档反复要求“设备测试前先唤醒解锁手机”（`git a0dd455:docs/project/HANDOFF.md:274` 等），`D32` 记录用户原话“若要解锁手机或者其他操作需要我协助，及时通知我”（`git a0dd455:docs/project/DECISIONS.md:138`），P2 T17 证据写明“因用户手机已解锁且我主动唤醒屏幕”（`git 8da375d:docs/project/evidence/2026-10-08-p2-t17-glasses-audiostream-run.md:152`）。
4. **没有任何一手证据记录过“锁屏起始状态”。** 全仓库与 git 历史中 `isKeyguardShowing=true` 命中 0 次（只有 `isKeyguardShowing=false` 2 个提交），取证证据中也没有 `mIsShowing=true` / `mShowingLockscreen=true` / `mDreamingLockscreen=true`；同样未找到任何输入 PIN/图案的命令或步骤。**“PC adb 解开过安全锁屏”按本任务口径记为未找到证据，不能写成已发生。**
5. **手机生产 App 没有应用内解锁代码；点亮也只有确认路径。** `phone-app` 生产源码中 `PowerManager` / `KeyguardManager` / `WakeLock` / `newWakeLock` / `requestDismissKeyguard` 命中为 0；只有 `MainActivity` 两处 `setShowWhenLocked(true)` / `setTurnScreenOn(true)`（`MainActivity.kt:73-74,181-182`），配合通知的 `setFullScreenIntent`（`AndroidNotificationSurface.kt:64`）。眼镜端生产 overlay 有真实唤醒锁并被设备证据证明（`GlassesOverlayService.kt:492-507,639`；`ACCEPTANCE.md:126` U-13）。`edith.phone.a11yprobe` 无唤醒/解锁能力。当前证据集里的手机 UI 测试要么由 `adb shell am start` 启动 a11yprobe，要么由 PC `input tap` 驱动（`AGENTS.md:83-84`；P3.1 命令见 `git 8da375d:...:29-34`），**没有一次是 PC-free 的**。

---

## 2. 证据表

> “执行者”按题设四类标注；跨类的写组合。“生产可用?”一列只回答“这段做法/代码能否在无 PC 的生产环境直接使用”，不回答验收结论。

| 时间/阶段 | 证据文件:行 | 命令/做法原文 | 执行者 | 生产可用? | 置信度 |
|---|---|---|---|---|---|
| 2026-10-08 P2 T17（0.2，已退役） | `git 8da375d:docs/project/evidence/2026-10-08-p2-t17-glasses-audiostream-run.md:152` | “因用户手机已解锁且我主动唤醒屏幕，本次手机侧焦点前置条件曾失败一次（`mWakefulness=Dozing`）” | 人工手点（用户已解锁）＋唤醒方法未记录 | 否 | 高（原文）/中（唤醒方式） |
| 2026-10-06..13 P2 交接与证据 | `git a0dd455:docs/project/HANDOFF.md:274`；`git a0dd455:docs/project/evidence/2026-10-08-p2-t17-audio-unblocked.md:158`；`git a0dd455:docs/project/DECISIONS.md:138` | “设备测试前先唤醒解锁手机”；“设备操作前置：先唤醒解锁手机”；用户 D32 原话“若要解锁手机或者其他操作需要我协助，及时通知我” | 人工手点（要求用户协助） | 否 | 高 |
| 2026-10-09 P3.1 | `git 8da375d:docs/project/evidence/2026-10-09-p3-1-passive-loop-e2e.md:29-34` | `adb -s 2696813b shell input keyevent KEYCODE_WAKEUP`；`adb -s 2696813b shell input keyevent KEYCODE_MENU          # dismiss keyguard (isKeyguardShowing=false already)`；`adb -s 2696813b shell input swipe 540 1800 540 600`；`adb -s 2696813b shell input tap 237 953` | PC adb | 否 | 高（命令与执行记录）；但 keyguard 当时未显示 |
| 2026-10-09 P3.1（doze 事故） | `git 8da375d:docs/project/evidence/2026-10-09-p3-1-passive-loop-e2e.md:993-1008` | `adb -s 2696813b shell input keyevent KEYCODE_WAKEUP          # the panel had dozed`；原文：“After `input keyevent KEYCODE_WAKEUP` … the identical taps worked, and the screen was re-woken every few seconds for the rest of the run.” | PC adb | 否 | 高 |
| 2026-10-10 P3.3 交接 | `git aa9cc78:docs/project/RESUME_PROMPT.md:78-80` | “`dumpsys power` 里 `mWakefulness=Dozing` 时 `uiautomator dump` 会报 … ⇒ `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard`” | PC adb（操作指示，未见执行回显） | 否 | 中 |
| 2026-10-13 Phase B 交接 | `git 7dd87d0:docs/project/2026-10-13-phase-b-session-prompt.md:114-119` | “屏幕必须在 `Awake` … 解决：`input keyevent KEYCODE_WAKEUP` + `svc power stayon true`” | PC adb | 否 | 高（成文做法）/中（未给回显） |
| 2026-10-16 无障碍能力调研 | `docs/project/evidence/2026-10-16-a11y-capability-survey.md:52,93,233` | `SURVEY_G08_NOT_EXECUTED quickSettings=5 lockScreen=8 powerDialog=6 reason=state_on_shared_acceptance_device`；“其他 App 的 secure 窗口、锁屏/Keyguard 本次未验证” | 探针（该动作未执行） | 否 | 高 |
| 2026-10-16 Shizuku 能力调研 | `docs/project/evidence/2026-10-16-shizuku-shell-capability-survey.md:5,302,434-436` | “调研期间 `mWakefulness=Awake`、无锁屏（`mIsShowing=false`）”；设备内路径只测只读 + `am start`；生产 shell 不注入输入 | PC shell / 设备内 shell（未测 keyguard） | 否 | 高 |
| 2026-10-17 0.23 P1（唯一记录“PC 命令修复 doze→安装成功”的手机案例） | `docs/project/evidence/2026-10-18-0.23-p1-phone-assistant.md:34-52` | 失败态 `mWakefulness=Dozing`、`INSTALL_FAILED_USER_RESTRICTED`；随后（证据自称“环境操作，不是 UI 操作模拟”）：`adb -s 2696813b shell input keyevent KEYCODE_WAKEUP`；`adb -s 2696813b shell wm dismiss-keyguard`；`adb -s 2696813b shell svc power stayon true`；再安装 `Success` | PC adb | 否 | 高（前后状态与结果）；中（无逐命令回显） |
| 0.23 P2 计划 | `docs/superpowers/plans/2026-10-18-edith-0.23-p2-phone-skills-memory-detailed-plan.md:40,446-448` | “安装前若设备 doze：先 `input keyevent KEYCODE_WAKEUP`、`wm dismiss-keyguard`、`svc power stayon true`”；实际 Run 块同样三条 | PC adb（计划） | 否 | 中（P2 证据未记录执行） |
| 0.23 P3/P5 计划 | `docs/superpowers/plans/2026-10-18-edith-0.23-p3-p5-glasses-ui-stop-detailed-plan.md:41` | “手机操作前若 doze：`adb -s 2696813b shell input keyevent KEYCODE_WAKEUP`、`wm dismiss-keyguard`、`svc power stayon true`” | PC adb（计划） | 否 | 中 |
| 0.23 P6 计划与实际 | `docs/superpowers/plans/2026-10-18-edith-0.23-p6-regression-acceptance-detailed-plan.md:52,386-390`；`docs/project/evidence/2026-10-18-0.23-p6-device-walkthrough.md:34,346` | 计划：“先唤醒、保持常亮”三条命令；实际证据只记录**眼镜** `input keyevent KEYCODE_WAKEUP`，清理时记录“眼镜/手机 `svc power stayon false`” | 计划 PC adb；手机实际唤醒方式未记录 | 否 | 中 |
| 2026-10-17 0.21 Phase 7 F4 | `docs/project/evidence/2026-10-17-phase-7-f4-measurement.md:215,358,385`；`tools/scripts/p7-f4/Invoke-F4Campaign.ps1:390-394,1052-1056` | 脚本 `Assert-DeviceAwake`：`mWakefulness=(Asleep\|Dozing)` ⇒ `DEVICE_ASLEEP wake the phone (and keep it awake) before running; this script never wakes it`；每个 slot 前再查一次；证据：“设备全程 `mWakefulness=Awake`，未发唤醒输入”；`stay_on_while_plugged_in=7` 前后 7→0 | 外部前置（谁唤醒未记录）；脚本只检查、不唤醒 | 否 | 高（依赖存在）；中（唤醒方法） |
| 2026-10-18 0.24 S1 | `docs/project/evidence/2026-10-18-0.24-s1-overlay-spike.md:74-78,152-205` | 眼镜 `adb -s 1901092544027662 shell input keyevent 224  # KEYCODE_WAKEUP（装置准备）`；`adb -s 1901092544027662 shell input keyevent 26  # KEYCODE_POWER` 睡下；随后 `am start` → 服务 `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP` → `mWakefulness=Awake` | PC adb（准备/制睡）＋应用唤醒锁（点亮） | 眼镜端：是（设备实测）；PC 部分否 | 高 |
| 2026-10-19 0.24 S5 生产走查 | `docs/project/evidence/2026-10-19-0.24-s5-production-walkthrough.md:74,163-174,217-230` | 眼镜 `adb -s 1901092544027662 shell input keyevent KEYCODE_SLEEP   # 需要“从 Asleep 点亮”的轮次`；注入后 `PowerManagerService: Powering on display group fromAsleep … details=edith:overlay`；`WAKE_LOCK acquire held=true interactive=true timeoutMs=600000` | PC adb（制睡/注入）＋应用唤醒锁 | 眼镜端：是；整轮“无 PC”否 | 高 |
| 2026-10-19 0.24 S5 CXR 冷启动 | `docs/project/evidence/2026-10-19-0.24-s5-cxr-scene-fix.md:104-112,133-145` | 眼镜睡下后只启动手机：`Powering on display group fromAsleep … details=wakeUp`；“vendor 栈在拉起 scene 时把显示点亮” | PC adb（制睡/驱动）＋厂商栈/应用 | 眼镜端：是（厂商栈点亮） | 高 |
| 2026-10-08 P2 提示上屏 | `docs/project/evidence/2026-10-08-p2-device-closure.md:62-78` | PC `KEYCODE_POWER` 睡下；提示上屏后 `SCREEN_BRIGHT_WAKE_LOCK 'edith:hint' ACQUIRE_CAUSES_WAKEUP`；TTL 到期释放 | PC（制睡）＋应用（点亮） | 眼镜端：是 | 高 |
| 2026-10-08 P3.0-T2 30 分钟长稳（已退役证据） | `git 8da375d:docs/project/evidence/2026-10-08-p3-t2-soak.md:21,43` | “第 15 分钟注入 `KEYCODE_POWER`”后 16–30 窗口照常上行；“锁屏不打断上行” | PC adb（注入锁屏） | 否（PC 驱动；只证明音频路径） | 高 |
| 当前生产代码（手机，确认路径） | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/MainActivity.kt:69-76,173-185`；`phone-app/app/src/main/kotlin/com/sheen/rokid/phone/session/confirmation/AndroidNotificationSurface.kt:64`；`phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/HostCompositionRoot.kt:2149-2171` | `setShowWhenLocked(true)`；`setTurnScreenOn(true)`；`setFullScreenIntent(openApp, true)`；后台 `startActivity(ACTION_SHOW_CONFIRMATION)` | 应用内代码 | **未测**（锁屏/息屏行为无设备证据） | 高（代码事实）/未测（行为） |
| 当前生产代码（手机，其余路径） | `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/session/SessionService.kt:105-109`；`phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/intent/SystemActionExecutor.kt:31-36`；`phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/shizuku/ShizukuExecutionBackend.kt:155-162`；`phone-app/app/src/main/AndroidManifest.xml:70-100` | 无 `PowerManager` / `KeyguardManager` / `WakeLock`；Shizuku 系统动作只有 `back/home/recents/notifications`；Shizuku 后端只有 `clear_data/uninstall/disable_app/set_permission/set_brightness`；无 BootReceiver、无手机 overlay service | 应用内代码 | 否（无此能力） | 高 |
| 当前生产代码（眼镜） | `glasses-app/app/src/main/kotlin/com/sheen/rokid/glasses/app/GlassesOverlayService.kt:492-507,639`；`glasses-app/app/src/main/AndroidManifest.xml:14` | `powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "edith:overlay")`，`acquire(WAKE_LOCK_TIMEOUT_MS)`，`WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L` | 应用内代码 | 是（眼镜；0.24 设备实测） | 高 |
| `edith.phone.a11yprobe` | `tools/probes/phone-accessibility-probe/src/main/AndroidManifest.xml:52-97`；`.../A11yIntentActivity.kt:466-481`；`.../A11yClickActivity.kt:144-156`；`.../A11ySurveyActivity.kt:74,85,895-900,1434-1435` | 三个 intent action：`edith.phone.a11yprobe.intent.A1_RUN`、`.CLICK_NOW`、`.SURVEY`；extras 只有 `confirm/scenario/permission/targetLabels/markerLabels/maxScrolls/dialogWaitMillis/affirmativeLabels`（另有 click 的 `labels/waitMillis`）；无 wake/unlock；`GLOBAL_ACTION_LOCK_SCREEN` 明确未执行；全目录 `PowerManager/KeyguardManager/KEYCODE_WAKEUP/WakeLock` 无命中 | 探针 | 否（无此能力） | 高 |
| 调试仪器（非生产） | `phone-app/app/src/debug/kotlin/com/sheen/rokid/phone/debug/DebugShellCapProbe.kt:20-39,60-79` | `adb shell am broadcast -a edith.DEBUG_SHELLCAP -n com.sheen.rokid.phone/.DebugShellCapProbe --es command '<command>'`，经生产 `ShizukuUserServiceShell` 执行任意命令 | 调试接收器（PC 触发） | 否（release 无此 receiver；无证据用于解锁） | 高 |

---

## 3. 受影响的历史证据与验收行清单

> “受影响”指该证据/验收行是在“手机已被外部唤醒/解锁”的装置上取得的；不表示读数错误，但**不能据此声称生产无 PC 时同样成立**。

### 3.1 有明确 PC 唤醒/解屏记录的

| 阶段 | 证据 | 依赖方式 | 受影响验收行 |
|---|---|---|---|
| 0.23 P1（2026-10-17 23:14–23:46） | `docs/project/evidence/2026-10-18-0.23-p1-phone-assistant.md` §1.1–1.2（:17-54） | 手机 `mWakefulness=Dozing`；PC adb 执行 `KEYCODE_WAKEUP` + `wm dismiss-keyguard` + `svc power stayon true` 后安装成功；此后全部截图/verdict 都在该装置上取得 | `ACCEPTANCE.md:60` **U-1**、`:61` **U-2** 引用 P1 证据；P1 文件内的截图与 verdict 全部下游 |
| 0.23 P6（2026-10-18） | `docs/project/evidence/2026-10-18-0.23-p6-device-walkthrough.md`；计划 `...p6...:386-390` | 计划把三条 PC 命令写成安装 Run 块；实际证据只记录了眼镜 `KEYCODE_WAKEUP`（:34）与清理时 `svc power stayon false`（:346）；**手机起始状态未记录** | `ACCEPTANCE.md:60` U-1（刷新按钮由 P6 验证）、`:92` U-8、`:101` U-10、`:111` U-11 引用 P6 |
| 2026-10-09 P3.1（已退役证据） | `git 8da375d:docs/project/evidence/2026-10-09-p3-1-passive-loop-e2e.md:29-34,336,993-1008` | PC adb `KEYCODE_WAKEUP` + `KEYCODE_MENU`（注释“dismiss keyguard”）+ `input swipe` + `input tap`；但同一文件记录 `isKeyguardShowing=false`；doze 时 `KEYCODE_WAKEUP` 后才点得动 | 0.2/P3.1 已退役，不在当前 31 份证据集；无当前 `ACCEPTANCE.md` 行 |
| 2026-10-10 P3.3（操作指示） | `git aa9cc78:docs/project/RESUME_PROMPT.md:78-80` | “Dozing ⇒ `KEYCODE_WAKEUP` + `wm dismiss-keyguard`”的成文指示 | 属已退役流程；当前 `2026-10-11-p3-3-layout-i1-i3.md` 本身未记录锁屏状态 |

### 3.2 有“计划前置”但没有执行记录的

| 阶段 | 证据 | 依赖方式 | 受影响验收行 |
|---|---|---|---|
| 0.23 P2（2026-10-17 23:53–2026-10-18 00:22） | 计划 `...p2...:40,446-448`；证据 `docs/project/evidence/2026-10-18-0.23-p2-phone-skills-memory.md` | 计划把三条 PC 命令写成安装前置；P2 证据全文没有 wake/stayon/keyguard 记录 | `ACCEPTANCE.md:71` U-3、`:72` U-4 |
| 0.23 P3/P5（2026-10-18） | 计划 `...p3-p5...:41`；证据 `2026-10-18-0.23-p3-glasses-visual.md`、`2026-10-18-0.23-p5-wake-chime.md`、`2026-10-18-0.23-p5-stop-back.md` | 计划有“手机操作前若 doze”三条；证据未记录手机唤醒/解锁 | `ACCEPTANCE.md:82` U-5（P3 证据）、`:100` U-9（P5 stop-back）、`:101` U-10（P5 wake-chime） |

### 3.3 依赖“设备已醒”但唤醒方式未记录的

| 阶段 | 证据 | 依赖方式 | 受影响验收行 |
|---|---|---|---|
| 0.21 Phase 7 F4/M2（2026-10-17 16:10–16:39） | `docs/project/evidence/2026-10-17-phase-7-f4-measurement.md:215,358,385`；`tools/scripts/p7-f4/Invoke-F4Campaign.ps1:390-394,1052-1056` | 脚本只要 `Asleep/Dozing` 就 fatal，且明写 “this script never wakes it”；证据记录全程 `mWakefulness=Awake`、未发唤醒输入、`stay_on_while_plugged_in=7`。**是谁/用什么把手机弄醒并保持常亮，证据未记录** | `ACCEPTANCE.md:45` **AC-13**（设备行为失败）、`:48` **AC-16**（设备行为失败）、`:184-190` **M2**（0/10） |
| 0.21 Phase 5（2026-10-16） | `docs/project/evidence/2026-10-16-phase-5-device-verification.md:41-60` | 手机 UI 全部经 `a11yprobe`；全文没有唤醒/解锁/起始 keyguard 记录 | **无直接验收行引用**；`ACCEPTANCE.md:278` 仅把它列入证据索引 |
| 0.24 S1/S5（2026-10-18/19） | `2026-10-18-0.24-s1-overlay-spike.md`、`2026-10-19-0.24-s5-production-walkthrough.md`、`2026-10-19-0.24-s5-cxr-scene-fix.md` | 手机 UI 经 a11yprobe（adb 启动）；这些文件只记录眼镜 `KEYCODE_SLEEP/WAKEUP` 与眼镜 Asleep→亮，**没有记录手机起始锁屏/唤醒方式** | `ACCEPTANCE.md:126` **U-13**、`:132` **U-19**（均为眼镜端） |

**0.21 P0/P3 的补充**：当前证据集中的 2026-10-14/15/16 系列文件（如 `2026-10-14-phase-b-device-acceptance.md`、`2026-10-15-phase-c-device-acceptance.md`、`2026-10-16-phase-3-device-verification.md`）**没有出现** `KEYCODE_WAKEUP`、`wm dismiss-keyguard`、`isKeyguard*` 或手机 `mWakefulness` 记录；因此它们是否依赖 PC 解锁/唤醒**无法判定**，只能写“未记录/未找到证据”，不得写成“无依赖”或“已可用”。0.21 P7 见上表 F4。

### 3.4 已确认“人工解锁”的

| 阶段 | 证据 | 依赖方式 | 受影响验收行 |
|---|---|---|---|
| 0.2 P2（2026-10-06..13） | `git a0dd455:docs/project/HANDOFF.md:274`；`git a0dd455:docs/project/evidence/2026-10-08-p2-t17-audio-unblocked.md:158`；`git 8da375d:docs/project/evidence/2026-10-08-p2-t17-glasses-audiostream-run.md:152` | 交接要求“先唤醒解锁手机”；D32 用户承诺解锁协助；P2 T17 证据写明“用户手机已解锁” | 0.2/P2 已退役，无当前 `ACCEPTANCE.md` 行 |

---

## 4. 明确「未找到证据」的项

> 下列 1–14 只能写 **未测** 或 **未找到证据**，不得写成“可用”。其中 #3/#4/#5/#6/#8/#14 若将来要声称可用，必须先补对应设备读数（见 §5），不能靠代码注释或平台常识替代。

| # | 项 | 检索范围与结果 |
|---|---|---|
| 1 | 任何一次测试的**起始状态**是锁屏（`isKeyguardShowing=true` / `mIsShowing=true` / `mShowingLockscreen=true` / `mDreamingLockscreen=true`） | 工作树证据、`.local` 日志、git 历史均无 `true` 的记录；`isKeyguardShowing=true` 在 git 历史命中 0 次。`.local/settings-underlay/46-phone-logcat.txt:810` 等 2026-10-19 11:25 的 MIUI `RecentsImpl` 行显示 `isKeyguardLocked=false`，但这是**运行中日志**，不是测试起始状态读回，不能反推“测试从解锁态开始” |
| 2 | 任何一次输入 PIN / 图案 / 密码来解锁 | 无命令、无步骤、无截图描述；未找到 `input text` 输入凭证或锁屏键盘点击的证据 |
| 3 | PC adb **成功解除安全 keyguard** | 只有“发过 `wm dismiss-keyguard` / `KEYCODE_MENU`”的记录；没有解除前后的 keyguard 状态读回；P3.1 反而记录当时 `isKeyguardShowing=false`（该文件 :30 注释写 “already”） |
| 4 | 应用内解锁手机（任何路径） | `phone-app/src/main` 无 `KeyguardManager`、`requestDismissKeyguard`、`WakeLock`、`PowerManager`；无 BootReceiver；无手机 overlay service |
| 5 | `setShowWhenLocked` / `setTurnScreenOn` 确认页在**锁屏/息屏**上真的出现并点亮 | P3.3 证据只有亮屏下的 `P3_CONFIRM_RAISE raised=true`（`2026-10-11-p3-3-layout-i1-i3.md:21,37`）；无锁屏/息屏 screenshot 或 logcat |
| 6 | 通知 `setFullScreenIntent` 在锁屏/息屏上拉起确认页 | 只有代码（`AndroidNotificationSurface.kt:64`）与亮屏下的 raise 记录；无锁屏/息屏设备读数 |
| 7 | `a11yprobe` 在锁屏上读/点 | 无障碍调研明确“锁屏/Keyguard 未验证”（`2026-10-16-a11y-capability-survey.md:93,233`）；后续证据未补 |
| 8 | 设备内（Shizuku）执行 `wm dismiss-keyguard` | Shizuku 调研的设备内路径只测“只读 + `am start -W`”，破坏性/写操作未在设备内执行（`2026-10-16-shizuku-shell-capability-survey.md:302`）；未测 keyguard |
| 9 | 0.24 S1/S5 的手机起始锁屏/唤醒状态 | 两份证据只写眼镜 `KEYCODE_SLEEP/WAKEUP`；手机侧只有 a11yprobe/`am start`，无 `mWakefulness`、无 keyguard 读回 |
| 10 | “无 PC 完成”的手机 UI 测试 | `AGENTS.md:83-84` 明写 a11yprobe 由 adb intent 驱动；当前证据集（0.21 Phase 5 以后）的手机 UI 证据都由 `adb shell am start` 启动探针；更早 P3.1 由 PC `input tap` 驱动。0.21 F4 脚本明写 “this script never wakes it” |
| 11 | 重启手机后的行为（无 PC） | 无重启测试记录；`phone-app` 无 `BOOT_COMPLETED` / `RECEIVE_BOOT_COMPLETED` / BootReceiver；Shizuku 非 root 重启后需重开（调研未重启验证，`2026-10-16-shizuku-shell-capability-survey.md:301,469`） |
| 12 | 眼镜的 keyguard/锁屏状态 | 所有眼镜证据只记 `mWakefulness=Asleep/Awake`，从未记 `isKeyguard*`；眼镜是否有锁屏未测 |
| 13 | K40 的锁屏配置（是否禁用锁屏、是否安全锁） | 一手证据中**没有** `locksettings get-disabled` / `dumpsys window policy` 的读回。仅 `docs/pre-research/2026-10-19-agent-evolution/D4-acceptance-corpus.md:508` 声称 `locksettings get-disabled=true`，按本任务纪律**不计为证据**，且未给命令输出 |
| 14 | 生产确认的**全屏意图路径**在锁屏/息屏上可用 | 历史验收行已把“锁屏/息屏的全屏意图路径”明确记为 `NotMeasured`（`git 07af2a0:docs/project/ACCEPTANCE.md:2070`）；同一时期另一份验收行也写明“通知动作的真机验收（后台/锁屏点按）尚未做”（`git f8f6053:docs/project/ACCEPTANCE.md:2068`）。当前证据集仍无补测 |

**唯一有锁屏参与的测试**：P3.0-T2 30 分钟长稳在第 15 分钟由 PC 注入 `KEYCODE_POWER` 锁屏，随后 16–30 窗口音频上行照常（`git 8da375d:docs/project/evidence/2026-10-08-p3-t2-soak.md:21,43`）。它证明的是**音频路径不被锁屏打断**；不证明 UI/解锁，既不是“从锁屏开始”，也不是 PC-free。

---

## 5. 必须设备实测的清单（只列问题，不写方案）

1. **K40 当前锁屏配置**：`locksettings get-disabled`、`dumpsys window` 的 keyguard 状态、是否存在 PIN/图案/密码；先确认“锁屏”到底是安全锁还是无安全锁。
2. **锁屏 + 生产确认路径**：手机锁屏、屏幕熄灭后，由生产 App 的确认通知/`ACTION_SHOW_CONFIRMATION` 触发；屏幕是否点亮、Activity 是否出现在 keyguard 之上、用户能否看到并操作「允许/拒绝」。需要截图 + `dumpsys window` + logcat，三者同时取。
3. **锁屏 + a11yprobe**：手机锁屏时，`edith.phone.a11yprobe/.A11yClickActivity` 能否读到窗口、能否点到目标；若不能，记录失败 verdict，不换 `input tap`。
4. **锁屏 + 设备内 Shizuku**：手机锁屏时 Shizuku 通道是否仍 `ready`；设备内 shell 执行 `wm dismiss-keyguard` 前后的 keyguard 状态读回（不是只看 exit code）。
5. **息屏 + 应用内点亮**：手机 `Asleep/Dozing` 时，除确认通知外是否还有任何生产路径能点亮屏幕；`setTurnScreenOn` 单独是否足够，需要设备读数。
6. **无 PC 全链路**：手机重启/长时间息屏后，不接 adb，仅靠手机与眼镜的自然操作（点 App、说话、确认）能否建立会话并完成一次动作；记录第一步就失败的位置。
7. **眼镜 Asleep + 无 adb 触发**：现有 `Asleep` 点亮证据由 adb `KEYCODE_SLEEP` 制造、由 adb 注入 WAV 触发；需要一次不注入、不 adb 触发的真实唤醒（或明确记为 `NotMeasured`）。
8. **重新评估所有手机 UI 验收行**：在“无 PC、屏幕会自然熄灭、可能锁屏”的装置上重跑 U-1/U-2/U-3/U-4 等依赖手机 UI 的行；旧读数只保留为“PC 装置下”的历史读数。

---

## 6. 参考来源

### 6.1 当前工作树证据与文档

- `docs/project/evidence/2026-10-18-0.23-p1-phone-assistant.md`（0.23 P1；:17-54 为 PC 唤醒/解屏与安装）
- `docs/project/evidence/2026-10-18-0.23-p2-phone-skills-memory.md`、`2026-10-18-0.23-p6-device-walkthrough.md`、`2026-10-18-0.23-p3-glasses-visual.md`、`2026-10-18-0.23-p5-wake-chime.md`、`2026-10-18-0.23-p5-stop-back.md`
- `docs/project/evidence/2026-10-16-a11y-capability-survey.md`（:52、:93、:233）
- `docs/project/evidence/2026-10-16-shizuku-shell-capability-survey.md`（:5、:301-302、:434-436、:469）
- `docs/project/evidence/2026-10-17-phase-7-f4-measurement.md`（:215、:358、:385）
- `docs/project/evidence/2026-10-16-phase-5-device-verification.md`（:41-60）
- `docs/project/evidence/2026-10-11-p3-3-layout-i1-i3.md`（:8、:21、:37）
- `docs/project/evidence/2026-10-08-p2-device-closure.md`（:62-78）
- `docs/project/evidence/2026-10-18-0.24-s1-overlay-spike.md`（:74-78、:152-205）
- `docs/project/evidence/2026-10-19-0.24-s5-production-walkthrough.md`（:74、:163-174、:217-230）
- `docs/project/evidence/2026-10-19-0.24-s5-cxr-scene-fix.md`（:104-112、:133-145）
- `docs/project/evidence/2026-10-18-0.24-final.md`（:78-93、:139、:194）
- `docs/project/ACCEPTANCE.md`（:45 AC-13、:48 AC-16、:60-61 U-1/U-2、:71-72 U-3/U-4、:82 U-5、:92 U-8、:100 U-9、:101 U-10、:111 U-11、:126 U-13、:132 U-19、:184-190 M2）
- `docs/project/DECISIONS.md`（D95 :56、D136 :172、D42 :46、D163 :206）
- `docs/project/CONTRACTS.md`（:128 overlay 所有者）
- `docs/superpowers/plans/2026-10-18-edith-0.23-p2-phone-skills-memory-detailed-plan.md`（:40、:446-448）
- `docs/superpowers/plans/2026-10-18-edith-0.23-p3-p5-glasses-ui-stop-detailed-plan.md`（:41）
- `docs/superpowers/plans/2026-10-18-edith-0.23-p6-regression-acceptance-detailed-plan.md`（:52、:386-390）
- `AGENTS.md`（:81-90，尤其 :83-84“由 adb intent 驱动”）

### 6.2 当前工作树代码

- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/MainActivity.kt`（:69-76、:173-185）
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/session/confirmation/AndroidNotificationSurface.kt`（:53-64）
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/app/HostCompositionRoot.kt`（:2149-2171）
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/session/SessionService.kt`（:105-109）
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/intent/SystemActionExecutor.kt`（:31-36）
- `phone-app/app/src/main/kotlin/com/sheen/rokid/phone/actions/shizuku/ShizukuExecutionBackend.kt`（:155-162、:222-230）
- `phone-app/app/src/debug/kotlin/com/sheen/rokid/phone/debug/DebugShellCapProbe.kt`（:20-39、:60-79）
- `glasses-app/app/src/main/kotlin/com/sheen/rokid/glasses/app/GlassesOverlayService.kt`（:161-172、:492-507、:639）
- `glasses-app/app/src/main/AndroidManifest.xml`（:14 WAKE_LOCK）
- `tools/probes/phone-accessibility-probe/src/main/AndroidManifest.xml`（:52-97）
- `tools/probes/phone-accessibility-probe/src/main/kotlin/edith/phone/a11yprobe/A11yIntentActivity.kt`（:466-481）
- `tools/probes/phone-accessibility-probe/src/main/kotlin/edith/phone/a11yprobe/A11yClickActivity.kt`（:144-156）
- `tools/probes/phone-accessibility-probe/src/main/kotlin/edith/phone/a11yprobe/A11ySurveyActivity.kt`（:74、:85、:895-900、:1434-1435）
- `tools/scripts/p7-f4/Invoke-F4Campaign.ps1`（:390-394、:1052-1056）
- `tools/scripts/p3/soak-uplink.ps1`（:206-212，PC `KEYCODE_POWER` 锁屏后不唤醒；这是 PC 驱动的后台测试，不是手机 UI 测试）

### 6.3 git 历史（已退役文件，不恢复）

- `8da375d`（2026-10-09）`docs/project/evidence/2026-10-09-p3-1-passive-loop-e2e.md`：:29-34（PC wake+KEYCODE_MENU+swipe）、:336（`isKeyguardShowing=false already`）、:993-1008（doze 吞输入）
- `8da375d`（2026-10-09）`docs/project/evidence/2026-10-08-p3-t2-soak.md`：:21、:43（第 15 分钟 PC `KEYCODE_POWER` 锁屏，音频上行继续；不是 UI/解锁证据）
- `07af2a0`（2026-10-11）`docs/project/ACCEPTANCE.md`：:2070（“锁屏/息屏的全屏意图路径” = `NotMeasured`）；`f8f6053` `docs/project/ACCEPTANCE.md`：:2068（通知动作“后台/锁屏点按”未做）
- `8da375d`（2026-10-09）`docs/project/evidence/2026-10-08-p2-t17-glasses-audiostream-run.md`：:152（“用户手机已解锁且我主动唤醒屏幕”）
- `a0dd455`（2026-10-08）`docs/project/HANDOFF.md`：:274 等（“设备测试前先唤醒解锁手机”）；`docs/project/DECISIONS.md`：:138（D32 用户原话）；`docs/project/evidence/2026-10-08-p2-t17-audio-unblocked.md`：:158
- `aa9cc78`（2026-10-10）`docs/project/RESUME_PROMPT.md`：:78-80（Dozing ⇒ `KEYCODE_WAKEUP` + `wm dismiss-keyguard`）
- `7dd87d0`（2026-10-13）`docs/project/2026-10-13-phase-b-session-prompt.md`：:114-119（`KEYCODE_WAKEUP` + `svc power stayon true`）；`docs/project/PROJECT_STATE.md`：:17（K70 同类做法，K70 已按 D46 退役，不作 K40 证据）
- `53b0701`（2026-10-17）：0.21 权威文档收口，上述退役文件自此不在工作树；本文只用 git 读，不恢复

### 6.4 不计为证据的预研声称

- `docs/pre-research/2026-10-19-agent-evolution/D4-acceptance-corpus.md:508` 声称“K40 `locksettings get-disabled=true`”。该文件是预研报告，且未附命令输出；本次在 `docs/project/evidence/`、`.local/`、git 历史中均未找到该读回，故**不作为证据**，列入 §4#13“未找到证据”。
