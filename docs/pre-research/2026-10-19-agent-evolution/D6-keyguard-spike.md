**真机 spike 证据，未批准；不是设计，不是验收基线。**

# D6 滑动锁屏无交互解除 + MIUI 锁屏显示/后台触发链 真机 spike（红米 K40 / Android 13 / MIUI）

- 日期：2026-10-19（Asia/Shanghai）。
- 设备：红米 K40 `2696813b`（`M2012K11AC` / `alioth`），Android 13 / API 33，MIUI `V816.0.10.0.TKHCNXM`；USB 连接、充电中（`USB powered: true`、电量 100%）。
- 范围：验证「App 自己从滑动锁屏（无密码/图案）无交互解除 keyguard」、MIUI 是否让 `showWhenLocked` Activity 显示在锁屏上、以及通知 FSI / BroadcastReceiver 后台拉起 Activity 是否可行。**不是设计，不是验收基线**；不改生产代码、生产 a11y、Shizuku 设置，不安装到眼镜。
- 探针：一次性 `edith.phone.wakelockprobe`（D5 源码上扩展；测试后已卸载）。源码/APK 见 §9。
- 证据级别：每个路径 n=1–4 的单次真机读数；`PASS` 只指本探针在本机观测到的行为，不等于生产触发链或验收通过。
- 本轮**没有**改 MIUI 的「锁屏显示 / 显示在锁屏上」权限；需要用户授权的部分已停在报告里。

## 1. 结论（逐项）

| # | 问题 | verdict | 关键读数 |
|---|---|---|---|
| 1 | 非安全滑动锁屏下，`requestDismissKeyguard(activity, callback)` 能否**无用户交互**解除？ | **PASS** | 3 次 `am start` + 1 次 broadcast 触发均 `onDismissSucceeded`；`dismissElapsedMs` **700 / 715 / 776 / 712 ms**；`keyguard true→false`；`interactive false→true`；PC 未发唤醒/解屏命令 |
| 2 | MIUI 是否让 `setShowWhenLocked(true)` 的 Activity 显示在锁屏上？ | **FAIL（默认被拦）** | 屏幕已亮 + keyguard showing 时：Activity `Resumed`、`mFocusedApp=ProbeActivity`，但窗口 `mViewVisibility=0x4`、`isVisible=false`，`mCurrentFocus=NotificationShade`；a11y 只看到 `TYPE_SYSTEM` 的 NotificationShade |
| 2b | 仅 `setTurnScreenOn(true)`（无 dismiss）能否从 keyguard + 息屏点亮？ | **FAIL** | 屏幕保持 `Dozing`、`interactive=false`（3 s 窗口）；`dumpsys window policy` 记录 `TurnScreenOnActivity=ProbeActivity` 但没有 `Waking up from Dozing`；App 侧 summary `interactiveAfter=false` |
| 3a | `BroadcastReceiver → startActivity(ProbeActivity)`（无 SAW） | **FAIL（被 BAL 拦）** | `ActivityTaskManager: Abort background activity starts from 10282`；无 ProbeActivity `onCreate`；屏幕保持 `Dozing`、keyguard 保持 showing |
| 3b | 同上，但授予 `SYSTEM_ALERT_WINDOW` appop | **PASS** | 无 Abort；`onCreate` 出现（`trigger=broadcast`）；`onDismissSucceeded`，`dismissElapsedMs=712 ms`；keyguard `true→false` |
| 3c | Notification FSI（ForegroundService + `setFullScreenIntent`，含 SAW） | **FAIL（MIUI 拒绝 Activity）** | FGS 启动被允许（`Background started FGS: Allowed … SYSTEM_ALERT_WINDOW_PERMISSION`），高优先级通知已 post，但 `NotificationManagerServiceImpl: MIUILOG- Permission Denied Activity … uid=10282`；屏幕保持 `Dozing`，无 Activity `onCreate` |
| 3d | Notification FSI（无 SAW，receiver fallback 直接 post） | **FAIL（MIUI 拒绝 Activity）** | FGS 启动被拒（`ForegroundServiceStartNotAllowedException`），fallback 直接 post FSI；MIUI 仍报 `MIUILOG- Permission Denied Activity`，且 PendingIntent 带 `allowlist … NOTIFICATION_SERVICE`；无 Activity `onCreate` |
| 4 | 唤醒/解锁后 a11y 看到的是目标 App 还是 SystemUI；Shizuku server 是否存活 | **OBSERVED（a11y）/ Shizuku: 进程 OBSERVED、binder NOT_MEASURED** | 锁屏时 a11y 仅 `TYPE_SYSTEM` NotificationShade（focused=true）；解锁后 `TYPE_APPLICATION`「EDITH D6 keyguard probe」focused=true、active=true；15:54:34 只读时 Shizuku 只有 manager 进程 `moe.shizuku.privileged.api`、无 `shizuku_server`；**15:58:04 事后**出现 `shizuku_server`（PID 25073，shell UID，非本轮启动），`service list` 仍为空，可用性未测 |
| 5 | 前置/前后值/耗时/失败日志 | **已记录** | 见 §4–§6；设置前后一致（§10）；无探针崩溃；唯一被捕获的异常是预期的 FGS 后台启动拒绝 |

**必须一起读的限定**：

- 本轮所有 dismiss 成功都发生在 **`isKeyguardSecure=false` 的非安全 keyguard**；安全锁屏（PIN/图案/密码）按平台语义本来就需要凭证，未测、也不应外推。
- **“dismiss 成功”不等于“锁屏上的 UI 可见”**：Q2 显示同一 Activity 在 keyguard 上不可见。`requestDismissKeyguard` 的 callback 成功只说明 keyguard 被解除；锁屏确认页仍会被 MIUI 拦（§6）。
- 后台触发里只有 **SAW + BroadcastReceiver** 这一条走通；**通知 FSI 两条子路径都被 MIUI 拒绝**。因此本轮**不是生产触发链的完整证明**（§7）。

## 2. 设备基线（只读，2026-10-19 15:48:44）与变更/还原

| 项 | 基线读数 | 本轮变更 | 结束还原/读数 |
|---|---|---|---|
| `locksettings get-disabled` | **true** | 15:50:57 `set-disabled false`（读回 false） | 15:56:25 `set-disabled true`，读回 **true** |
| keyguard policy | `showing=false`、`secure=false`、`deviceHasKeyguard=true`、`mIsShowing=false` | 启用滑动锁屏 + `KEYCODE_SLEEP` 后 `showing=true`、`mIsShowing=true` | 清理阶段 `showing=false`、`mIsShowing=false`（§10） |
| `isKeyguardSecure`（App 侧） | 设备基线时无 keyguard 可读；启用后探针读到 **false** | — | 卸载后不再读 |
| `stay_on_while_plugged_in` | **7** | 未改 | **7** |
| `screen_off_timeout` | **600000** | 未改 | **600000** |
| `enabled_accessibility_services` | `edith.phone.a11yprobe/.A11yProbeService` | 未改（探针未新增 a11y） | 同左 |
| `accessibility_enabled` | **1** | 未改 | **1** |
| Shizuku | 只有 `u0_a270 … moe.shizuku.privileged.api`；`ps -A` 无 `shizuku_server`；`service list \| grep -i shizuku` 为空 | 本轮未启动/未改 | **15:58:04 外部观察到 `shizuku_server`（PID 25073，shell UID，PPID 1）**，非本轮启动；`service list` 仍空；与基线不同（未归因） |
| 电源 | `mWakefulness=Dozing`、`mStayOn=true`、Display OFF | 多轮由 App 点亮/PC 造前置 | 结束 `Dozing`、Display OFF（与基线一致） |
| 探针包 | **未安装** | 15:50:34 安装 | 15:56:31 `force-stop` + `uninstall`，`pm list packages` 无 |
| `POST_NOTIFICATIONS` | 包未安装 | 安装后 `pm grant`（granted=true） | 随卸载移除 |
| `SYSTEM_ALERT_WINDOW` appop | 包未安装 | 15:53:08 `appops set … allow`；随后 `ignore` | 随卸载移除（无 UID） |
| MIUI 锁屏显示权限 | 未读/未知（未改） | 未改 | 未改 |

**PC 动作边界**：

- 测量轮内 PC 只做：`am force-stop`、`am start`、`am broadcast`、`input keyevent KEYCODE_SLEEP`（造前置）与 `dumpsys`/`logcat`/`settings get` 只读。
- **安装环境动作（非测量）**：第一次 `adb install` 时屏幕处于 `Dozing`，MIUI 安装确认框被取消（`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`）；随后用一次 `input keyevent KEYCODE_WAKEUP` 点亮屏幕，再以 a11y 探针点击「继续安装」重试，安装成功。该唤醒**只用于安装**，不参与任何 verdict。
- **清理动作（已标注）**：恢复 `locksettings set-disabled true` 后 keyguard 仍显示，清理阶段用 `KEYCODE_WAKEUP` + `wm dismiss-keyguard` 清掉残留 keyguard，再 `KEYCODE_SLEEP` 回到 `Dozing`。
- 未用 `input tap` / `input text`；MIUI 安装确认由 `edith.phone.a11yprobe` 的 `CLICK_NOW` 点击（logcat：`A1CLICK_MATCH tick=28 label='继续安装' clicked=true package=com.miui.securitycenter`）。
- 除只读的 `adb devices -l` 外，所有 adb 均带 `-s 2696813b`；眼镜 `1901092544027662` 只在 `adb devices -l` 中列出，未执行任何 shell/install/input。

**前置状态偏差（如实记录）**：`KEYCODE_SLEEP` 只到 `Dozing`（Display OFF、`INTERACTIVE_STATE_SLEEP`），未出现 `Asleep`；与 D5 在该 MIUI/充电/AOD 组合上的观察一致。因此本轮的“息屏”= **Dozing + 面板 OFF + `isInteractive=false`**，不是深度 Asleep。

## 3. 测试口径与计时常量

- **本轮要回答的是平台行为**：PC 造“息屏 + keyguard showing”前置并触发探针；点亮与解除必须由 App 完成。
- App 动作：`setShowWhenLocked(true)`（所有模式）；`mode` 含 `activity`/`both` 时 `setTurnScreenOn(true)`；`mode` 含 `wakelock`/`both` 时获取 30 s 的 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`；`mode` 含 `dismiss` 且 `isKeyguardLocked==true` 时调用 `requestDismissKeyguard`。
- 计时：
  - `dismissElapsedMs`：`requestDismissKeyguard` 调用 → `onDismissSucceeded/Error/Cancelled`。
  - `interactiveElapsedMs`：`onCreate` 开头 → 首次轮询到 `PowerManager.isInteractive()==true`（10 ms 轮询）。
  - `elapsedMs`（summary）：`onCreate` → summary 行；dismiss 轮包含 300 ms 的 callback settle（用于读 `keyguardAfter`）。
- 探针 summary 行固定前缀：`KEYGUARD_PROBE mode=… keyguardBefore=… secure=… interactiveBefore=… dismiss=… keyguardAfter=… elapsedMs=… trigger=…`；`dismiss` 取值 `not_requested|not_attempted|requested|succeeded|error|cancelled|exception:…`。
- `trigger` 只标注启动来源（`am_start|broadcast|fsi`），不改变行为；用于把 PASS/FAIL 归因到正确路径。

## 4. 每轮测试表

| # | 触发方式 | 前置（keyguard / power） | PC 动作 | App 动作 | callback | keyguard 前→后 | interactive 前→后 | 耗时 | verdict |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `am start mode=activity+dismiss`（15:51:06） | `showing=true`、`secure=false`；`Dozing`、Display OFF | `force-stop`；`am start`（**无**唤醒/解屏） | `showWhenLocked` + `turnScreenOn`；`requestDismissKeyguard` | `onDismissSucceeded` | **true → false** | **false → true** | dismiss **700 ms**；interactive **117 ms**；total **1046 ms** | **PASS** |
| 2 | 同上 #2（15:54:09） | 同上（再次 sleep 造前置） | `am start` | 同上 | `onDismissSucceeded` | **true → false** | **false → true** | dismiss **715 ms**；interactive **42 ms**；total **1037 ms** | **PASS** |
| 3 | 同上 #3（15:54:52） | 同上 | `am start` | 同上 | `onDismissSucceeded` | **true → false** | **false → true** | dismiss **776 ms**；interactive **54 ms**；total **1173 ms** | **PASS** |
| 4 | `am broadcast`（显式 receiver，SAW=allow，15:53:08） | 同上 | `am broadcast -n …/.WakeTriggerReceiver --ez confirm true` | receiver → `startActivity`；Activity 同上 | `onDismissSucceeded`（`trigger=broadcast`） | **true → false** | **false → true** | dismiss **712 ms**；interactive **37 ms**；total **1026 ms** | **PASS** |
| 5 | `am broadcast`（显式 receiver，SAW=ignore，15:53:00） | 同上 | 同上 | receiver 收到；`startActivity` 被系统拒绝 | 未请求（Activity 未启动） | **true → true** | **false → false** | n/a | **FAIL（BAL 拦截，符合 Android 10+ 预期）** |
| 5b | 同上，间隔 30 s（15:56:05） | 同上 | 同上 | 同上 | 未请求 | **true → true** | **false → false** | n/a | **FAIL（再次被 `Abort background activity starts` 拦截）** |
| 5c | 同上，但在前一个 Activity 结束 8 s 后（15:55:02，SAW=ignore） | 同上 | 同上 | receiver → `startActivity`，系统放行 | `onDismissSucceeded` | **true → false** | **false → true** | dismiss **714 ms**；total **1030 ms** | **OBSERVED（近前台窗口内放行；不作为可依赖路径）** |
| 6 | FSI：receiver → ForegroundService（SAW=allow，15:53:20） | 同上 | `am broadcast … --es trigger fsi` | FGS `startForeground` + `setFullScreenIntent` | Activity 未启动，无 callback | **true → true** | **false → false** | n/a（通知 post 于 15:53:20.556） | **FAIL（MIUI `Permission Denied Activity`）** |
| 7 | FSI：receiver fallback 直接 post（SAW=ignore，15:53:54 / 15:56:12） | 同上 | 同上 | FGS 启动被拒 → receiver 直接 post 同一 FSI 通知 | Activity 未启动，无 callback | **true → true** | **false → false** | n/a | **FAIL（FGS 被 Android 12+ 限制；FSI 又被 MIUI 拒绝）** |
| 8 | `am start mode=activity`（只显示，不 dismiss；屏幕已亮 + keyguard，15:52:18） | `showing=true`、`secure=false`；`Awake`（此前由 App WakeLock 点亮） | `am start` | `showWhenLocked` + `turnScreenOn`；hold 12 s | `not_requested` | **true → true** | **true → true** | hold **12020 ms** | **FAIL（MIUI 不显示在锁屏上）** |
| 9 | `am start mode=activity`（只显示，屏幕 OFF + keyguard，15:51:29） | `showing=true`、`secure=false`；`Dozing` | `am start` | `showWhenLocked` + `turnScreenOn` | `not_requested` | **true → true** | **false → false**（未点亮） | 3 s 观测窗口 | **FAIL（turnScreenOn 单独未唤醒）** |
| 10 | 解锁后 a11y 观测（15:54:18） | 屏幕 `Awake`、keyguard 已由 #2 dismiss | `am start mode=activity`（hold 9 s） | 前台 Activity | n/a | already false | true → true | n/a | **OBSERVED（a11y 看到目标 App）** |

> 表 #5/#5b 的 FAIL 是“该触发路径不可用”，不是“测量失败”；系统日志给出了明确的拒绝行。#5c 是同一配置下的例外，单独列出且不计入 PASS 结论。

## 5. 关键原始读数（按轮）

### 5.1 Dismiss：`am start` 第 1 轮（15:51:06，n=1）

```text
15:51:06.493 I/KEYGUARD_PROBE: KEYGUARD_PROBE_EVENT mode=activity+dismiss event=onCreate trigger=am_start
              interactiveBefore=false keyguardBefore=true keyguardSecure=false
15:51:06.519 I/KEYGUARD_PROBE: KEYGUARD_PROBE_EVENT mode=activity+dismiss event=dismissRequested
              trigger=am_start keyguardLocked=true
15:51:06.591 I/KEYGUARD_PROBE: KEYGUARD_PROBE_EVENT mode=activity+dismiss event=interactiveOn
              trigger=am_start interactiveElapsedMs=117
15:51:07.219 I/KEYGUARD_PROBE: KEYGUARD_PROBE_EVENT mode=activity+dismiss event=dismissSucceeded
              trigger=am_start dismissCallback=succeeded dismissElapsedMs=700
15:51:07.520 I/KEYGUARD_PROBE: KEYGUARD_PROBE mode=activity+dismiss keyguardBefore=true secure=false
              interactiveBefore=false dismiss=succeeded keyguardAfter=false elapsedMs=1046
              trigger=am_start interactiveAfter=true interactiveElapsedMs=117 dismissElapsedMs=700
```

- 前置（15:51:03 只读）：`mWakefulness=Dozing`、`showing=true`、`secure=false`、`interactiveState=INTERACTIVE_STATE_SLEEP`、`Display State=OFF`。
- PC 在触发后、App 报告前只做 `dumpsys` 只读；没有 `KEYCODE_WAKEUP`、没有 `wm dismiss-keyguard`。

### 5.2 Dismiss：`am start` 第 2/3 轮（n=2、n=3）

```text
15:54:09.554 dismissRequested / 15:54:10.269 dismissSucceeded  dismissElapsedMs=715
15:54:10.570 summary: keyguardBefore=true keyguardAfter=false dismiss=succeeded
              elapsedMs=1037 interactiveBefore=false interactiveAfter=true
15:54:53.009 dismissRequested / 15:54:53.785 dismissSucceeded  dismissElapsedMs=776
15:54:54.165 summary: keyguardBefore=true keyguardAfter=false dismiss=succeeded
              elapsedMs=1173 interactiveBefore=false interactiveAfter=true
```

- 三轮 `am_start` 的 dismiss 延迟 700/715/776 ms，总 1037–1173 ms；同一路径重复性良好。

### 5.3 Broadcast + SAW（15:53:08）

```text
15:53:08.701 I/KEYGUARD_PROBE: KEYGUARD_PROBE_TRIGGER event=receiver trigger=broadcast
              mode=activity+dismiss pkg=edith.phone.wakelockprobe
15:53:08.738 I/KEYGUARD_PROBE: KEYGUARD_PROBE_TRIGGER event=startActivityIssued trigger=broadcast
15:53:08.748 I/KEYGUARD_PROBE: ... event=onCreate trigger=broadcast interactiveBefore=false
              keyguardBefore=true keyguardSecure=false
15:53:08.759 dismissRequested / 15:53:09.471 dismissSucceeded dismissElapsedMs=712
15:53:09.772 summary: dismiss=succeeded keyguardAfter=false interactiveAfter=true elapsedMs=1026
```

- 无 `Abort background activity starts` 行；SAW appop 由 `ignore` 改为 `allow` 后该路径成立。

### 5.4 Broadcast 无 SAW（15:53:00 与 15:56:05）

```text
15:53:00.873 I/KEYGUARD_PROBE: KEYGUARD_PROBE_TRIGGER event=receiver trigger=broadcast
15:53:00.881 E/ActivityTaskManager: Abort background activity starts from 10282
15:53:00.882 I/KEYGUARD_PROBE: KEYGUARD_PROBE_TRIGGER event=startActivityIssued trigger=broadcast
（无 ProbeActivity onCreate）
```

```text
15:56:05.571 I/KEYGUARD_PROBE: KEYGUARD_PROBE_TRIGGER event=receiver trigger=broadcast
15:56:05.579 E/ActivityTaskManager: Abort background activity starts from 10282
15:56:05.579 I/KEYGUARD_PROBE: KEYGUARD_PROBE_TRIGGER event=startActivityIssued trigger=broadcast
```

- 触发命令若**不带** `-n` 显式组件，receiver 本身收不到广播：
  `15:52:48.100 W/BroadcastQueue: Background execution not allowed: receiving Intent { act=edith.phone.wakelockprobe.WAKE … } to edith.phone.wakelockprobe/.WakeTriggerReceiver`。
  因此后台路径测试统一改用 `-n edith.phone.wakelockprobe/.WakeTriggerReceiver`。
- #5c（15:55:02，Activity 结束 8 s 后、SAW=ignore）出现一次放行；30 s 后同配置再次被 Abort。该“近前台窗口”不作为可用路径。

### 5.5 FSI（15:53:20 与 15:56:12）

```text
15:53:20.531 I/ActivityManager: Background started FGS: Allowed [callingPackage: edith.phone.wakelockprobe;
              code:SYSTEM_ALERT_WINDOW_PERMISSION; …
15:53:20.548 I/KEYGUARD_PROBE: ... event=wakeServiceStart mode=activity+dismiss
15:53:20.556 I/KEYGUARD_PROBE: ... event=fsiPosted source=foreground_service mode=activity+dismiss
15:53:20.556 I/NotificationManagerServiceImpl: MIUILOG- Permission Denied Activity :
              PendingIntent{906364b: PendingIntentRecord{e5f1c0e edith.phone.wakelockprobe startActivity}}
              pkg : edith.phone.wakelockprobe uid : 10282
（无 ProbeActivity onCreate；屏幕保持 Dozing）
```

```text
15:56:12.273 I/KEYGUARD_PROBE: ... event=fsiServiceStartBlocked
              error=ForegroundServiceStartNotAllowedException:startForegroundService() not allowed
              due to mAllowStartForeground false: service edith.phone.wakelockprobe/.WakeService
15:56:12.279 I/NotificationManagerServiceImpl: MIUILOG- Permission Denied Activity :
              PendingIntent{df4919e: ... (allowlist: 7f684ee:+30s0ms/0/NOTIFICATION_SERVICE/
              NotificationManagerService)} pkg : edith.phone.wakelockprobe uid : 10282
15:56:12.281 I/KEYGUARD_PROBE: ... event=fsiPosted source=receiver_fallback mode=activity+dismiss
```

- 第二次的 PendingIntent 已被 NotificationManagerService 放入 allowlist，MIUI 仍拒绝 Activity；这不是 Android BAL 问题，而是 MIUI 层 `Permission Denied Activity`。
- `dumpsys notification --noredact` 中该通知为 `importance=4 / mImportance=HIGH / category=call / vis=PUBLIC`；记录里 `fullscreenIntent=null`（MIUI 的 dump 口径，未据此推断，主证据是上面的 MIUILOG）。

### 5.6 `showWhenLocked` 在 keyguard 上的可见性（15:52:18，屏幕已亮）

```text
mCurrentFocus=Window{d77ceb3 u0 NotificationShade}
mFocusedApp=ActivityRecord{556308e u0 edith.phone.wakelockprobe/.ProbeActivity}
ResumedActivity: ActivityRecord{556308e u0 edith.phone.wakelockprobe/.ProbeActivity}

Window #9  Window{d77ceb3 u0 NotificationShade}:
Window #13 Window{2805fc1 u0 edith.phone.wakelockprobe/…ProbeActivity}:
    mViewVisibility=0x4 mHaveFrame=true mObscured=false
（ProbeActivity 窗口 mViewVisibility=0x4，即 INVISIBLE；焦点仍在 NotificationShade）
```

- 同一次 hold（12 s）内 a11y 只返回一个窗口：
  `type=TYPE_SYSTEM, focused=true, active=true`（NotificationShade）；没有 ProbeActivity 的 `TYPE_APPLICATION`。
- 该轮屏幕由 App 的 `mode=wakelock` 点亮（15:52:13，`interactiveElapsedMs=77`），**不是 PC 唤醒**；因此“屏幕已亮”不构成 PC 帮助。

### 5.7 `turnScreenOn` 单独在 keyguard + 息屏下（15:51:29）

```text
15:51:29.663 onCreate interactiveBefore=false keyguardBefore=true keyguardSecure=false
15:51:29.672 onResume interactive=false
（无 Waking up from Dozing / Screen on 日志）
15:51:32.661 summary: keyguardBefore=true keyguardAfter=true dismiss=not_requested
              interactiveBefore=false interactiveAfter=false elapsedMs=3004
```

- `dumpsys window policy` 同时记录 `TurnScreenOnActivity=ActivityRecord{…ProbeActivity}`，说明 Activity 的 turnScreenOn 标志已被识别；但屏幕没有点亮。
- 对比 D5（无 keyguard）：`mode=activity` 曾从 Dozing 点亮（447 ms）。本轮差异是 **keyguard showing**。

### 5.8 解锁后的 a11y（15:54:18）

```text
mCurrentFocus=Window{89213dd u0 edith.phone.wakelockprobe/…ProbeActivity}
A11yWindow[AccessibilityWindowInfo[... type=TYPE_SYSTEM, focused=false ...]]
A11yWindow[AccessibilityWindowInfo[title=EDITH D6 keyguard probe, taskId=1167,
            type=TYPE_APPLICATION, focused=true, active=true]]
```

- 锁屏时 a11y 只看到 SystemUI；解锁后看到目标探针 Activity。与 D0b 的预期一致。
- 生产 a11y 未启用，因此这只证明平台行为，不证明生产无障碍路径。

### 5.9 Shizuku（只读，15:54:34；事后 15:58:04 出现 server）

```text
15:54:34  u0_a270  5581  905  ...  S  moe.shizuku.privileged.api
          （ps -A 无 shizuku_server；service list | grep -i shizuku 为空）
15:58:04  shell     25073   1  ...  S  shizuku_server   （UID 2000，PPID 1）
          （service list | grep -i shizuku 仍为空；dumpsys -l | grep -i shizuku 为空）
```

- 15:54:34 的测试窗口内没有 `shizuku_server`；**15:58:04 事后**它出现（PID 25073，shell UID，PPID 1）。本轮没有执行任何启动 Shizuku 的命令，也没有调用其通道；无法归因（可能是 Shizuku manager 或其它工作线），未终止、未改动。
- `service list` 为空**不能**据此断言 binder 不存在（Shizuku 的 binder 不一定注册到 servicemanager）；server 是否可用、能否 rebind 均未测。

## 6. MIUI 锁屏显示观察（本机实测）

1. **keyguard 状态**：启用后 `showing=true`、`mIsShowing=true`、`secure=false`、`deviceHasKeyguard=true`；App 侧 `keyguardBefore=true`、`keyguardSecure=false`。这是非安全 keyguard（无 PIN/图案/密码）。
2. **`showWhenLocked` 被拦**：屏幕已亮 + keyguard showing 时，探针 Activity 确实处于 `Resumed`/`mFocusedApp`，但窗口 `mViewVisibility=0x4`、`isVisible=false`，焦点停在 `NotificationShade`；a11y 只有 SystemUI。**没有看到“Activity 盖在锁屏上”**。
3. **FSI 被 MIUI 拒绝**：两条 FSI 子路径都出现 `MIUILOG- Permission Denied Activity`；其中一条的 PendingIntent 已被 `NOTIFICATION_SERVICE` allowlist。拒绝发生在 MIUI 的 `NotificationManagerServiceImpl`，不是 Activity 自身的 BAL 检查。
4. **MIUI 私有 appop**：`cmd appops get edith.phone.wakelockprobe` 可见 `MIUIOP(10008/10017/10020/10021/10022/10032/10045): ignore` 等；本轮**未改名、未修改**这些项，也未据此下结论（10020 等代码与本轮 FSI/显示失败的时间相关性不足以命名）。
5. **结论口径**：社区关于“MIUI 需要手动开启『锁屏显示 / Show on lock screen』，否则 `showWhenLocked` 不生效”的说法，与本机默认状态下的观察一致；但本轮**没有**打开该权限，也没有证明打开后一定可用。按任务纪律，未擅自改 MIUI 权限设置——**需要用户授权/手动开启后再测一次**。

## 7. 这证明了什么 / 没证明什么

**证明了：**

1. 在这台 K40（Android 13 / MIUI）上，非安全 keyguard 存在时，App 自己调用 `requestDismissKeyguard` 可以**无用户交互**解除：3 次 `am_start` 和 1 次 `SAW` 背景触发都是 `onDismissSucceeded`，keyguard `true→false`，dismiss 延迟约 **700–776 ms**。
2. `turnScreenOn` 与 dismiss 是两件事：在 **keyguard + 息屏**下，单独 `setTurnScreenOn(true)` 没有点亮屏幕；`requestDismissKeyguard`（带 `turnScreenOn`、Activity 为顶层）触发了点亮（interactive 首轮 117 ms，后续 37–54 ms）。WakeLock 路径也能独立点亮（77 ms，D5 已证）。
3. `SYSTEM_ALERT_WINDOW` + `BroadcastReceiver → startActivity` 在这台设备上通过，随后 dismiss 成功（712 ms）；无 SAW 时同一路径被 `Abort background activity starts` 拦截。
4. 通知 FSI 对这台设备上的本探针**不可用（未授予 MIUI「锁屏显示」）**：FGS 可以启动、通知可以 post，但 MIUI 拒绝 FSI 的 Activity；无 SAW 时 FGS 还被 Android 12+ 后台启动限制拒绝，fallback 直接 post 仍被 MIUI 拒绝。
5. `showWhenLocked` 在这台 MIUI 上**默认不把 Activity 显示在滑动锁屏上**；锁屏时 a11y 只看得到 SystemUI，解锁后才看到目标 App。
6. 测试窗口（15:54:34）内 Shizuku 只有 manager 进程；15:58:04 事后出现 `shizuku_server`（非本轮启动，未测其 binder/可用性）。本轮没有制造“Shizuku 可用”的假象。

**没证明：**

1. **没有证明生产触发链**：本轮用 `am start` / `am broadcast` 触发；生产是通知 FSI、前台服务或其它真实事件。FSI 恰恰是失败项。
2. **没有证明 MIUI「锁屏显示」打开后的行为**：本机默认拦；用户开启该权限后是否能让 `showWhenLocked`/FSI 工作，未测。
3. **没有证明真实用户滑动锁屏**：滑动锁屏是用 `locksettings set-disabled false` 临时启用的；用户实际锁屏设置（以及将来是否设 PIN/图案）未知。
4. **没有证明安全锁屏**：`secure=false`；PIN/图案/密码不可无凭证解除，按口径不测。
5. **没有证明 `Asleep`（深度睡眠）**：只测到 `Dozing`；Asleep、拔线、Doze/App Standby 下的时延与成功率未知。
6. **没有证明 FSI 在“FGS 早已运行”的生产形态**：本轮试过 FGS 从 broadcast 启动（SAW 下允许、无 SAW 下被拒），但 FSI 的 Activity 两次都被 MIUI 拒绝；没有在“用户手动开锁屏显示”后复测。
7. **没有证明 Shizuku server 存活/rebind**：测试窗口内 server 不存在；15:58:04 事后出现的 server 不是本轮启动，未测其 binder、授权或 rebind。
8. **没有证明生产 a11y**：只启用 `edith.phone.a11yprobe`；锁屏/解锁的窗口观测是探针读数。
9. **没有证明稳定性/用户可见性**：dismiss n=4、FSI n=3、showWhenLocked n=2；没有方差/异常率；没有截图/ScreenRecord，`isInteractive=true` 不等于用户已看清画面。
10. **没有证明 MIUI 对系统/通话类应用的 FSI 白名单行为**：探针是第三方调试包，不能外推。

## 8. 未测与风险

1. **Asleep / 拔线 / 重启**：本机在 USB 充电 + AOD 下只到 Dozing；深度睡眠、非充电、重启后的 Shizuku、锁屏状态均未测。
2. **Shizuku rebind**：测试窗口内 `shizuku_server` 未运行；15:58:04 事后出现的 server 非本轮启动，其可用性/rebind 未测；MIUI 息屏是否杀、无线调试重启后是否需要重做，也未测。
3. **生产 a11y**：未启用；锁屏时生产服务是否仍绑定、能看到什么，未测。
4. **真实用户滑动锁屏**：临时启用的非安全 keyguard 不等同于用户的实际配置；真实用户滑动（Swipe）未由真人/真实手势验证。
5. **MIUI「锁屏显示」**：默认关闭（推断）；开启路径、开启后的 `showWhenLocked` + FSI 行为需要用户授权后复测。这是本轮最关键的未决前置。
6. **FGS 生产形态**：生产确认页如果依赖 FSI，必须解决 MIUI 的 FSI 拒绝；如果退到 SAW，则需要用户授予悬浮窗权限，且本轮只测了探针的 appop，不证明生产签名/商店安装后的权限形态。
7. **Android 14+ 规则**：K40 是 API 33；FSI、`USE_FULL_SCREEN_INTENT`、`TURN_SCREEN_ON` 的未来收紧未覆盖。
8. **锁屏显示与 wake 的因果**：本轮观察到 keyguard 下 `turnScreenOn` 单独不唤醒、Activity 窗口不可见；无法仅凭 App 侧数据区分是 MIUI 的「锁屏显示」权限、还是 MIUI 对第三方 App 的 keyguard 策略。需要打开权限后的对照实验。
9. **BAL 的“近前台窗口”例外**：#5c 在 Activity 结束 8 s 后可后台启动，30 s 后被拦；这不是可依赖的生产路径，具体窗口/条件未测。
10. **单次样本**：没有重复压力、没有不同电量/温度/后台杀手状态；MIUI 私有 appop 的具体含义未映射。

## 9. 探针源码 / APK / 复现

一次性模块（D5 已把模块加入根 `settings.gradle.kts` 的 include；本轮只改探针目录，未改生产代码）：

- `tools/probes/wakelock-unlock-probe/build.gradle.kts`
- `tools/probes/wakelock-unlock-probe/src/main/AndroidManifest.xml`
  - `WAKE_LOCK`、`POST_NOTIFICATIONS`、`USE_FULL_SCREEN_INTENT`、`FOREGROUND_SERVICE`、`SYSTEM_ALERT_WINDOW`
  - Activity `ProbeActivity`（保留 `mode=wakelock/activity/both` 和 `+dismiss`）
  - Receiver `WakeTriggerReceiver`（action `edith.phone.wakelockprobe.WAKE`，要求 `confirm=true`）
  - Service `WakeService`（FGS + FSI；代码在 15 s 后 `stopSelf`）
- `…/kotlin/edith/phone/wakelockprobe/ProbeActivity.kt`
- `…/kotlin/edith/phone/wakelockprobe/FsiNotifier.kt`
- `…/kotlin/edith/phone/wakelockprobe/WakeService.kt`
- `…/kotlin/edith/phone/wakelockprobe/WakeTriggerReceiver.kt`

APK：

- 路径：`tools/probes/wakelock-unlock-probe/build/outputs/apk/debug/wakelock-unlock-probe-debug.apk`
- SHA-256：`1B3737B5A33CD99F685B9EC49E2229399B933782AB5E042785BD48212047F47E`
- `applicationId=edith.phone.wakelockprobe`，`minSdk=28`，`targetSdk=33`，`versionName=0.1.0`
- 签名：项目 debug 签名；`ANDROID_USER_HOME="D:\Project_new\EDITH\.local\p3\android-home"`。
- 构建时的 `JAVA_HOME` 在本 shell 未设置；使用 `C:\Users\Richard\.gradle\sheen-jdk21`（Temurin 21.0.7）。

复现命令（红米 K40，显式 `-s 2696813b`）：

```powershell
$env:JAVA_HOME = "C:\Users\Richard\.gradle\sheen-jdk21"
$env:ANDROID_USER_HOME = "D:\Project_new\EDITH\.local\p3\android-home"
.\gradlew.bat :tools:probes:wakelock-unlock-probe:assembleDebug --console=plain
adb -s 2696813b install -r .\tools\probes\wakelock-unlock-probe\build\outputs\apk\debug\wakelock-unlock-probe-debug.apk
adb -s 2696813b shell pm grant edith.phone.wakelockprobe android.permission.POST_NOTIFICATIONS

# 启用滑动锁屏（先读原值；结束还原 true）
adb -s 2696813b shell locksettings get-disabled
adb -s 2696813b shell locksettings set-disabled false
adb -s 2696813b shell input keyevent KEYCODE_SLEEP          # 只造前置
adb -s 2696813b shell am start -n edith.phone.wakelockprobe/.ProbeActivity `
  --es mode activity+dismiss --es trigger am_start

# 后台触发（无 SAW / 有 SAW / FSI）
adb -s 2696813b shell am broadcast -a edith.phone.wakelockprobe.WAKE `
  -n edith.phone.wakelockprobe/.WakeTriggerReceiver --ez confirm true
adb -s 2696813b shell appops set edith.phone.wakelockprobe SYSTEM_ALERT_WINDOW allow
adb -s 2696813b shell am broadcast -a edith.phone.wakelockprobe.WAKE `
  -n edith.phone.wakelockprobe/.WakeTriggerReceiver --ez confirm true --es trigger fsi

adb -s 2696813b logcat -d -v time | Select-String "KEYGUARD_PROBE|MIUILOG- Permission Denied"
```

- 安装时 MIUI 的「继续安装」由 `edith.phone.a11yprobe` 的 `CLICK_NOW` 点击；未用 `input tap`。
- 测试后 `force-stop` + `uninstall edith.phone.wakelockprobe`。

## 10. 清理与前后一致性（2026-10-19 15:56:25–15:57:22）

| 项 | 结束读数 | 与基线一致？ |
|---|---|---|
| `locksettings get-disabled` | **true** | 是（15:56:25 写入并读回；15:56:55、15:57:19 复读仍 true） |
| keyguard | `showing=false`、`secure=false`、`mIsShowing=false` | 是（清理阶段 `wm dismiss-keyguard` 清掉残留 keyguard） |
| 屏幕/电源 | `Dozing`、`interactiveState=INTERACTIVE_STATE_SLEEP`、Display OFF | 是 |
| `stay_on_while_plugged_in` | **7** | 是 |
| `screen_off_timeout` | **600000** | 是 |
| `enabled_accessibility_services` | `edith.phone.a11yprobe/.A11yProbeService` | 是 |
| `accessibility_enabled` | **1** | 是 |
| 探针包 | `pm list packages` 无；`dumpsys activity services` 无残留；appop 报 `No UID` | 已卸载 |
| Shizuku | 15:54:34 只读时只有 manager、无 `shizuku_server`；**15:58:04 事后**出现 `shizuku_server`（PID 25073，shell UID，PPID 1；非本轮启动，未终止） | **否（外部变化，未归因）**；本轮未启动/未改 Shizuku |
| 眼镜 | `adb devices -l` 仅列出 `1901092544027662`，未执行任何操作 | 未触碰 |
| 通知 | 无活动通知；`dumpsys notification` 仍有 6 行提及该包（统计/zen 规则/条件提供者枚举，非活动通知） | 残留记录，非活动通知 |

- **清理阶段的 PC 动作**：`KEYCODE_WAKEUP` + `wm dismiss-keyguard`（仅用于清掉恢复 `locksettings` 后残留的 keyguard）以及 `KEYCODE_SLEEP` 回到 Dozing；均发生在所有测量结束之后，已在本节标注。

## 11. 原始证据（本地，未入库）

`.local/d6-keyguard-spike/`：

- `baseline.txt`：15:48:44 基线只读。
- `enable-keyguard.txt`：`get-disabled true → set false → false`。
- `precondition-a/b/c/d.txt`：每轮 keyguard/power 前置。
- `round1-during.txt`、`round0b-showwhenlocked.txt`、`round0b-windows.txt`、`round0b-detail.txt`、`round0c-showwhenlocked-on.txt`：`showWhenLocked`/turnScreenOn 的窗口与 a11y 证据。
- `round1b-dismiss.txt`、`final-a-dismiss.logcat.txt`：dismiss 日志。
- `round2-broadcast-cmd.txt`、`round2-after.txt`、`round2-saw-appop.txt`、`round2b-after.txt`、`round2b-saw-after.txt`、`final-b-broadcast.logcat.txt`、`final-b2-broadcast-30s.logcat.txt`：broadcast 的 blocked / SAW 放行 / 30 s 重测。
- `round3-fsi-after.txt`、`round3-notification.txt`、`round3-fsi-evidence.txt`、`round3b-fsi-nosaw-after.txt`、`final-c-fsi.logcat.txt`、`final-c-notification.txt`：FSI 证据。
- `round4-a11y-after-unlock.txt`：解锁后 a11y。
- `shizuku-and-appops.txt`、`implicit-broadcast-blocked.txt`：测试窗口内的 Shizuku / appop 只读；`rounds.logcat.txt` 仅保留最后一次缓冲里的隐式广播拒绝行（前序轮次因 `logcat -c` 已清空，前序证据见各自的轮次文件）；`shizuku-appeared-1558.txt`：15:58:55 事后复核的 `shizuku_server`（PID 25073，UID 2000，PPID 1；非本轮启动）。
- `restore-keyguard.txt`、`cleanup-uninstall.txt`、`cleanup-after-wake.txt`、`cleanup-dismiss.txt`、`cleanup-final.txt`、`final-state.txt`、`final-verify.txt`、`notification-leftovers.txt`：清理与最终一致性。

> 本文只记状态、计数、耗时、包名与窗口类型；没有记录屏幕正文、截图或个人内容。
