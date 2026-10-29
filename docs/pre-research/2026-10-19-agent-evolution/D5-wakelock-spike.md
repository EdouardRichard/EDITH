**真机 spike 证据，未批准；不是设计，不是验收基线。**

# D5 息屏/锁屏主动点亮真机 spike（红米 K40 / Android 13 / MIUI）

- 日期：2026-10-19（Asia/Shanghai）。
- 设备：红米 K40 `2696813b`（`M2012K11AC` / `alioth`），Android 13 / API 33，MIUI `V816`；USB 连接，充电中（`USB powered: true`，电量 100%）。
- 范围：只回答“App 自己能否从屏幕熄灭状态点亮屏幕；若存在滑动锁屏，能否无用户交互解除”。**不是设计，不是验收基线**；不修改生产代码、生产 a11y/Shizuku 设置，不安装到眼镜。
- 探针：一次性 `edith.phone.wakelockprobe`（throwaway），测试后已从设备卸载；源码/APK 见 §9。
- 证据级别：每个模式 **n=1** 的单次真机读数；`PASS` 只指本探针在本机把 `isInteractive` 从 `false` 变成 `true`，不等于生产触发链、解锁或验收通过。

## 1. 结论（逐项）

| # | 问题 | verdict | 关键读数 |
|---|---|---|---|
| 1 | `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` 能否从屏幕熄灭状态点亮？ | **PASS（从 Dozing / 面板 OFF 起，非 Asleep）** | `interactiveBefore=false → interactiveAfter=true`；App 侧 onCreate→interactive **206 ms**；系统侧 `Waking up from Dozing … details=edith:wakelock-probe (uid=10281)`，`Screen on took 266 ms` |
| 2 | `setShowWhenLocked(true)` + `setTurnScreenOn(true)` 能否点亮并显示 Activity？ | **PASS（从 Dozing / 面板 OFF 起，非 Asleep）** | `interactiveBefore=false → interactiveAfter=true`；onCreate→interactive **447 ms**；系统侧 `Waking up from Dozing … uid=1000 details=android.server.wm:SCREEN_ON_FLAG`，`Screen on took 269 ms` |
| 3 | `requestDismissKeyguard(activity, callback)` 能否无交互解除滑动锁屏？ | **NOT_MEASURED** | 设备无 keyguard：`locksettings get-disabled=true`、`isKeyguardShowing=false`、`secure=false`；探针内 `isKeyguardLocked=false`、`isKeyguardSecure=false`。探针按设计跳过并记录 `dismissCallback=not_attempted_keyguard_not_locked`；未改 `locksettings`，未尝试绕过 |
| 4 | 唤醒后无障碍看到的是目标 App 还是 SystemUI？Shizuku server 是否存活？ | **OBSERVED（无 keyguard 场景）/ Shizuku server: NOT_MEASURED** | 唤醒后 `dumpsys accessibility`：focused/active window 是 `TYPE_APPLICATION`（标题 `EDITH P3 click`，当时是邻居工作线遗留的 a11yprobe Activity），另有系统栏窗口；**没有 keyguard/SystemUI 锁屏窗口**。`ps -A \| grep -i shizuku` 只有 `moe.shizuku.privileged.api`（manager 进程），无 `shizuku_server`；`service list \| grep -i shizuku` 为空；未启动 Shizuku |
| 5 | 前后值、时间戳、失败异常 | **已记录** | 每轮 `interactive` / `keyguard` 前后值、App 与系统时间戳见 §4/§5；无异常；WakeLock 均正常释放；设置前后一致（§10） |

**必须一起读的限定**：本轮的前置状态是 `mWakefulness=Dozing`（`Display State=OFF`、`interactiveState=INTERACTIVE_STATE_SLEEP`），**不是 `Asleep`**。原因是该 MIUI + USB 充电 + AOD 组合下 `KEYCODE_SLEEP` 只会进入 `Dozing`；第一次息屏后约 67 s、结束前又单独观察 120 s，均保持 `Dozing`，从未出现 `Asleep`（§3）。因此上表的 PASS 严格含义是“从 Dozing/面板熄灭状态由 App 点亮”，不能外推为“从深度 `Asleep` 也一定同样快/成功”。

## 2. 设备基线状态（只读，2026-10-19 15:33:25）

| 项 | 读数 | 来源 |
|---|---|---|
| 系统 | Android 13 / API 33；MIUI `V816`；`M2012K11AC` / `alioth` | `getprop` |
| 锁屏是否禁用 | `locksettings get-disabled` = **true** | adb shell |
| keyguard 状态 | `deviceHasKeyguard=true`；`isKeyguardShowing=false`；policy `showing=false`、`secure=false`、`mIsShowing=false` | `dumpsys window` / `dumpsys window policy` |
| App 侧 keyguard 读数 | 探针 `onCreate` 读到 `isKeyguardSecure=false`、`isKeyguardLocked=false` | 探针 logcat（Java API，shell 不能直接调用） |
| 保持常亮 | `stay_on_while_plugged_in` = **7** | `settings get global` |
| 息屏超时 | `screen_off_timeout` = **600000**（10 min） | `settings get system` |
| 锁屏超时 | `lock_screen_lock_after_timeout` = `null` | `settings get secure` |
| 电源 | `mWakefulness=Awake`；`mStayOn=true`；`mHoldingDisplaySuspendBlocker=true`；USB powered=true；电量 100% | `dumpsys power` / `dumpsys battery` |
| 无障碍 | `accessibility_enabled=1`；`enabled_accessibility_services=edith.phone.a11yprobe/.A11yProbeService`（**探针，不是生产 a11y**） | `settings get secure` |
| Shizuku | `u0_a270 5581 … moe.shizuku.privileged.api`；`ps -A` 无 `shizuku_server`；`service list \| grep -i shizuku` 为空 | `ps -A` / `service list` |

**基线未改动任何设置**：`locksettings`、`stay_on_while_plugged_in`、`screen_off_timeout`、`enabled_accessibility_services` 在测试结束时均与上面的读数一致（§10）。

## 3. 测试口径：PC 只负责触发/造前置，点亮由 App 完成

- **PC 只做四件事**：① `input keyevent KEYCODE_SLEEP` 造“屏幕熄灭”前置；② `am force-stop` 清进程；③ `am start -n edith.phone.wakelockprobe/.ProbeActivity --es mode <mode>` 触发探针；④ 只读 `dumpsys` / `logcat` / `settings get`。
- **PC 没有做**：没有发 `KEYCODE_WAKEUP`；没有发 `wm dismiss-keyguard`；没有用 `input tap` / `input text`；没有改 `locksettings`；没有启动 Shizuku；没有启用生产 a11y。全部 adb 操作均带 `-s 2696813b`（唯一例外是开头一次只读的 `adb devices -l`），眼镜 `1901092544027662` 未被操作。
- **App 自己做**：`mode` 含 `wakelock`/`both` 时获取 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`（30 s 超时）；`mode` 含 `activity`/`both` 时调用 `setShowWhenLocked(true)` + `setTurnScreenOn(true)`；`mode` 含 `dismiss` 且 `isKeyguardLocked==true` 时才调用 `requestDismissKeyguard`。
- **计时常量**：
  - App `elapsedMs`：从 `onCreate()` 开头（`SystemClock.elapsedRealtime()`）到 10 ms 轮询首次看到 `PowerManager.isInteractive()==true`；**不包含** shell `am start` 到进程/Activity 启动的开销。
  - 系统 `Screen on took`：`PowerManagerService` 在 `Waking up from Dozing` 之后打印的显示点亮完成耗时；它比 `isInteractive=true` 更接近“屏幕真正亮起”。
  - PC 轮询耗时：`dumpsys` 轮询（约 150–300 ms 分辨率）观测到的 `mWakefulness=Awake` / `INTERACTIVE_STATE_WAKING`，只作交叉检查，**不作为 verdict 依据**。更准确地说，480/733/652 ms 是从 `am start` 到 PC **首次观测到 `mWakefulness=Awake`** 的粗测上界，不是 interactive 的精确耗时；`am start`→`onCreate` 的启动延迟本轮没有单独埋点。

**前置状态偏差（如实记录）**：任务要求等待 `mWakefulness=Asleep`。实测 `KEYCODE_SLEEP` 后系统日志为 `Going to sleep due to sleep_button` → `Dozing...`，`dumpsys display` 为 `Display State=OFF`、`mState=OFF`，`dumpsys window policy` 为 `interactiveState=INTERACTIVE_STATE_SLEEP`。第一次息屏后保持 `Dozing` 约 67 s；三轮结束后又单独观察 **120 s**，仍为 `Dozing`，从未出现 `Asleep`。故本轮统一以 **`Dozing` + 面板 OFF + `isInteractive=false`** 作为“屏幕熄灭”的实测前置，并在每个 verdict 上标注；没有为了凑 `Asleep` 改 AOD/充电设置。

## 4. 每轮测试表

| 模式 | 前置状态（只读） | PC 动作 | App 动作 | interactive 前→后 | keyguard 前→后 | dismissCallback | 耗时 | verdict |
|---|---|---|---|---|---|---|---|---|
| `wakelock` | `Dozing`；`INTERACTIVE_STATE_SLEEP`；`Display State=OFF`；keyguardShowing=false / secure=false | `force-stop`；`am start … --es mode wakelock`（15:39:45） | 获取 `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP`（30 s）；约 200 ms 后释放 | **false → true** | **false → false**（`isKeyguardLocked` / `isKeyguardSecure` 均 false） | `not_requested` | App onCreate→interactive **206 ms**；系统 wake→screen-on **266 ms**；PC 轮询 480 ms（WAKING） | **PASS**（Dozing 起） |
| `activity` | `Dozing`；`INTERACTIVE_STATE_SLEEP`；`Display State=OFF`；keyguardShowing=false / secure=false | `force-stop`；`am start … --es mode activity`（15:39:56） | `setShowWhenLocked(true)` + `setTurnScreenOn(true)`；不取 WakeLock | **false → true** | **false → false** | `not_requested` | onCreate→interactive **447 ms**；系统 wake→screen-on **269 ms**；PC 轮询 733 ms（WAKING） | **PASS**（Dozing 起） |
| `both` | `Dozing`；`INTERACTIVE_STATE_SLEEP`；`Display State=OFF`；keyguardShowing=false / secure=false | `force-stop`；`am start … --es mode both`（15:40:05） | 两者同时使用；WakeLock 30 s，约 200 ms 后释放 | **false → true** | **false → false** | `not_requested` | onCreate→interactive **195 ms**；系统 wake→screen-on **267 ms**；PC 轮询 652 ms（AWAKE） | **PASS**（Dozing 起） |
| `activity+dismiss`（负对照，屏幕已亮） | `Awake`；keyguardShowing=false / secure=false | `force-stop`；`am start … --es mode activity+dismiss`（15:40:21） | `setShowWhenLocked` + `setTurnScreenOn`；`dismiss` 守卫检查 | true → true | false → false | **`not_attempted_keyguard_not_locked`** | 屏幕原已亮，elapsed 47 ms（无意义） | **NOT_MEASURED**（无 keyguard 可解除） |

**轮间隔离**：每轮开始前均 `force-stop` 探针，并确认 `mWakefulness=Dozing` / `INTERACTIVE_STATE_SLEEP`；第 2、3 轮的 pre 行即上一轮结束后的再次息屏确认（§5）。`Dozing` 下屏幕 `Display State=OFF`。

## 5. 时间戳与系统侧唤醒归因（原始读数）

### 5.1 轮 1：`mode=wakelock`

```text
App  (tag WAKELOCK_PROBE, pid 20804)
15:39:45.357  event=onCreate interactiveBefore=false keyguardBefore=false keyguardSecure=false elapsedSinceBootMs=697423324
15:39:45.372  event=wakeLockAcquired flags=SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP timeoutMs=30000
15:39:45.426  event=onResume
15:39:45.501  event=interactiveOn elapsedMs=206
15:39:45.702  WAKELOCK_PROBE mode=wakelock interactiveBefore=false interactiveAfter=true keyguardBefore=false keyguardAfter=false dismissCallback=not_requested elapsedMs=206 elapsedSinceCreateMs=406 keyguardSecure=false wakeLockHeld=true
15:39:45.703  event=wakeLockReleased

System (tag PowerManagerService / PowerManagerServiceImpl)
15:39:45.359  Acquire wakelock: SCREEN_BRIGHT_WAKE_LOCK 'edith:wakelock-probe' ACQUIRE_CAUSES_WAKEUP (uid=10281 pid=20804 pkg=edith.phone.wakelockprobe)
15:39:45.360  Waking up from Dozing (uid=10281, reason=WAKE_REASON_APPLICATION, details=edith:wakelock-probe)...
15:39:45.625  Screen on took 266 ms
15:39:45.702  Release wakelock: ... ACQ=-332ms
```

- 系统把唤醒归因到 **探针 uid=10281 + `details=edith:wakelock-probe`**；不是 PC 的 `KEYCODE_WAKEUP`。
- 从系统 `Acquire wakelock`（15:39:45.359）到 App 看到 `interactive=true`（15:39:45.501）约 **142 ms**；从 App 获取 WakeLock 的日志（.372）算约 **129 ms**。
- `isInteractive=true` 发生在 `Screen on took` 完成（15:39:45.625）之前；**“isInteractive 变 true”不等于“面板已经亮完”**。

### 5.2 轮 2：`mode=activity`

```text
App  (pid 21039)
15:39:56.693  event=onCreate interactiveBefore=false keyguardBefore=false keyguardSecure=false elapsedSinceBootMs=697434660
15:39:56.731  event=onResume
15:39:57.078  event=interactiveOn elapsedMs=447
15:39:57.080  event=onResume
15:39:57.279  WAKELOCK_PROBE mode=activity interactiveBefore=false interactiveAfter=true keyguardBefore=false keyguardAfter=false dismissCallback=not_requested elapsedMs=447 elapsedSinceCreateMs=648 keyguardSecure=false wakeLockHeld=false
15:39:57.279  event=finish

System
15:39:56.856  Waking up from Dozing (uid=1000, reason=WAKE_REASON_APPLICATION, details=android.server.wm:SCREEN_ON_FLAG)...
15:39:57.124  Screen on took 269 ms
```

- 没有 Acquire WakeLock 行；唤醒由 **Activity 的 turnScreenOn 标志**经 system_server（uid=1000）以 `SCREEN_ON_FLAG` 触发。
- 从 `onResume`（.731）到 `interactive=true`（57.078）约 **347 ms**；从系统 wake（56.856）到 interactive 约 **222 ms**；系统 screen-on 完成在 57.124。
- 注意：`onResume` 在该轮出现两次（.731 与 .080），是亮屏过渡中的重复 resume；`dismiss` 守卫只在第一次执行且只执行一次，未影响读数。

### 5.3 轮 3：`mode=both`

```text
App  (pid 21312)
15:40:05.331  event=onCreate interactiveBefore=false keyguardBefore=false keyguardSecure=false elapsedSinceBootMs=697443293
15:40:05.354  event=wakeLockAcquired flags=SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP timeoutMs=30000
15:40:05.397  event=onResume
15:40:05.459  event=interactiveOn elapsedMs=195
15:40:05.660  WAKELOCK_PROBE mode=both interactiveBefore=false interactiveAfter=true keyguardBefore=false keyguardAfter=false dismissCallback=not_requested elapsedMs=195 elapsedSinceCreateMs=396 keyguardSecure=false wakeLockHeld=true
15:40:05.661  event=wakeLockReleased

System
15:40:05.333  Acquire wakelock: SCREEN_BRIGHT_WAKE_LOCK 'edith:wakelock-probe' ACQUIRE_CAUSES_WAKEUP (uid=10281 pid=21312 pkg=edith.phone.wakelockprobe)
15:40:05.333  Waking up from Dozing (uid=10281, reason=WAKE_REASON_APPLICATION, details=edith:wakelock-probe)...
15:40:05.600  Screen on took 267 ms
15:40:05.660  Release wakelock: ... ACQ=-307ms
```

- 系统 wake 归因到探针 WakeLock；`activity` 标志同时存在，但实际最早触发源是 WakeLock。

### 5.4 轮 4：`mode=activity+dismiss`（负对照）

```text
15:40:21.299  event=onCreate interactiveBefore=true keyguardBefore=false keyguardSecure=false
15:40:21.314  event=onResume
15:40:21.314  event=dismissSkipped reason=keyguard_not_locked
15:40:21.330  event=interactiveOn elapsedMs=47
15:40:21.532  WAKELOCK_PROBE mode=activity+dismiss interactiveBefore=true interactiveAfter=true keyguardBefore=false keyguardAfter=false dismissCallback=not_attempted_keyguard_not_locked elapsedMs=47 elapsedSinceCreateMs=248 keyguardSecure=false wakeLockHeld=false
```

- 本轮屏幕原本就是 `Awake`，只用于验证“无 keyguard 时探针确实不会调用 `requestDismissKeyguard`”，**不构成解锁测量**。

### 5.5 唤醒后的无障碍窗口与 Shizuku（只读）

在最后一轮结束后（15:40:32，`mWakefulness=Awake`、`Display State=ON`）读取：

- `dumpsys accessibility`：`Enabled services: edith.phone.a11yprobe/.A11yProbeService`（仅此一个；生产 a11y 未启用）。活动/聚焦窗口为 `TYPE_APPLICATION`、`title=EDITH P3 click`、`focused=true`、`active=true`（id 3159，task 1126）；另有 `TYPE_SYSTEM` 的状态栏/导航栏窗口（id 3039、3057）。**没有 keyguard 窗口**。
- 当前焦点 `mCurrentFocus=edith.phone.a11yprobe/.A11yClickActivity`：这是邻居工作线遗留的探针 Activity（探针结束后焦点回落到它），不是生产 App，也不是 SystemUI 锁屏。该结论只覆盖“无 keyguard 的唤醒后”；**锁屏时 a11y 是否只看到 SystemUI 未测**（无 keyguard 无法复现）。
- Shizuku：`ps -A \| grep -i shizuku` 只有 `moe.shizuku.privileged.api`（manager 进程）；没有 `shizuku_server` 进程；`service list \| grep -i shizuku` 无输出。**不能据此声称 Shizuku server 存活**；本轮未启动 Shizuku，也未调用生产 Shizuku 通道。

## 6. 这证明了什么 / 没证明什么

**证明了：**

1. 在 K40（Android 13 / MIUI）上，App 自己获取 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 可以把它从 `Dozing`（面板 OFF、`isInteractive=false`）点亮；系统 power 日志明确归因到 `edith:wakelock-probe` 的 uid/包名。
2. App 自己设置 `setShowWhenLocked(true)` + `setTurnScreenOn(true)` 也能把它从 `Dozing` 点亮；系统 power 日志归因到 `android.server.wm:SCREEN_ON_FLAG`（Activity 标志路径），不需要 WakeLock。
3. 两者同时使用时同样可用；本轮首次观测到 interactive 的 App 内部耗时约 195–447 ms，系统“screen on took”约 266–269 ms。
4. 该设备当前确实没有 keyguard（`locksettings get-disabled=true`，`isKeyguardShowing=false`，`secure=false`），探针不会误调 `requestDismissKeyguard`（负对照记录 `not_attempted_keyguard_not_locked`）。
5. “PC 造前置/触发”与“App 点亮”是分开的：PC 没有发任何唤醒/解锁命令；系统唤醒归因指向 App 的 WakeLock 或 Activity flag。

**没证明：**

1. **没有证明任何“解锁”**：设备无滑动锁屏，`requestDismissKeyguard` 未执行；非安全锁屏/安全锁屏的行为都未测。
2. **没有证明生产触发链**：本轮用 `am start` 从 shell 触发 Activity；这不等于通知 FSI、`SYSTEM_ALERT_WINDOW`、前台服务 `startActivity` 或生产 `MainActivity` 路径能通过 BAL/MIUI 后台限制。
3. **没有证明从 `Asleep`（深度睡眠）点亮**：只测了 `Dozing`。两者在电源管理上不同；`Asleep` 下的时延和成功与否未知。
4. **没有证明 MIUI “锁屏显示”权限路径**：`showWhenLocked` 在无 keyguard 时没有“盖在锁屏上”可验证；MIUI 的 “Show on lock screen” 风险未触发。
5. **没有证明屏幕内容对用户可见**：只测 `isInteractive` 和系统 screen-on 日志；没有截图/没有验证 200 ms 内画面是否已可读。
6. **没有证明无 PC 的生产闭环**：PC 仍然是触发者；App 只是被触发后完成了点亮。生产环境中谁触发、是否被系统拦截，未测。
7. **没有证明 Shizuku 可用**：只读检查看不到 `shizuku_server`；息屏后是否被杀、rebind 是否成功均未测。
8. **没有证明长期/重复稳定性**：每模式 n=1；没有重复次数、方差、异常率。

## 7. 与 D0a / D0b 的一致性、差异

- **与 D0a（解锁历史调查）**：
  - D0a 的结论“手机生产 App 没有应用内唤醒/解锁代码，只有 `MainActivity` 的 `setShowWhenLocked`/`setTurnScreenOn` 与 FSI 通知代码；手机 UI 测试都依赖 PC/人工”在本轮没有被推翻。本轮补的是**平台机制**的真机读数，不是生产集成。
  - D0a §4 #13 说“K40 的锁屏配置无一手读回”。本轮补上了直接读数：`locksettings get-disabled=true`、`deviceHasKeyguard=true`、`isKeyguardShowing=false`、`secure=false`；因此 D4 预研里“get-disabled=true”的声称现在有一条一手读回，但 D4 本身仍不作为证据。
  - D0a §4 #3/#4/#5/#6（App 解锁、生产确认页在锁屏/息屏上出现）**仍未测**：本轮没有 keyguard，也没有走生产确认页/FSI。
- **与 D0b（能力边界调查）**：
  - D0b 预测 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 在 Android 13 普通 App 默认可用（`OP_TURN_SCREEN_ON` 默认 allowed）；本轮 K40 实测 **PASS**，与预测一致。
  - D0b 预测 `setTurnScreenOn` + `setShowWhenLocked` 可以让 Activity 唤醒屏幕；本轮 K40 实测 **PASS**，系统归因到 `SCREEN_ON_FLAG`。
  - D0b 对 `requestDismissKeyguard` 的“非安全锁屏可无交互解除”结论本轮 **无法验证**：设备 `locksettings get-disabled=true`，没有 keyguard。D0b 的 MIUI “锁屏显示”风险也未被触发。
  - D0b 关于 `wm dismiss-keyguard` 在 dozing 时会排队、不负责唤醒的论断，本轮没有测试（未发该命令）。
  - **差异**：D0b 的待测清单假设可以从 `Asleep` 开始；实测该 MIUI/充电/AOD 组合只能到 `Dozing`（约 67 s 与 120 s 两段观察合计约 187 s，均未到 `Asleep`）。因此本轮是“从 Dozing 点亮”，D0b 中“Asleep 下行为”仍待测。

## 8. 未测与风险

1. **滑动锁屏/安全锁屏**：无 keyguard，`requestDismissKeyguard` 的 `onDismissSucceeded/onDismissError/onDismissCancelled` 均未观测；安全锁屏（PIN/图案/密码）按 D0b 语义本来就需要凭证，不属于“无交互解除”。
2. **MIUI “锁屏显示 / Show on lock screen”权限**：未触发；若生产要在真实锁屏上显示，仍需单独真机验证（D0b 的 issue/社区证据风险）。
3. **FSI / 通知 / SAW / 前台服务 startActivity**：未测；生产触发链是否被 MIUI 后台限制拦截未知。
4. **深度 `Asleep`**：未达到；只测 `Dozing`。AOD 关闭、非充电、长时间待机后的行为未测。
5. **充电/常亮条件**：测试时 USB 充电、`stay_on_while_plugged_in=7`、电量 100%；拔线、Doze/App Standby 下的时延和成功率未测。
6. **重启与 Shizuku**：未重启；无 BootReceiver 验证；Shizuku server 未观察到，息屏杀/rebind 未测。
7. **生产 a11y 与锁屏**：未启用生产 a11y；锁屏时 a11y 是否只能看到 SystemUI 未测。
8. **用户可见性**：没有截图/ScreenRecord；`isInteractive=true` 早于 `Screen on took` 完成，不能把 206/447/195 ms 当作“用户已经看到画面”的保证。
9. **单次样本**：每个模式只跑一次；没有重复、方差、异常率；WakeLock 的 30 s 超时未到就被主动释放，长持有行为未测。
10. **Android 版本风险**：`ACQUIRE_CAUSES_WAKEUP` 已废弃，官方文档提示未来版本可能要求 `TURN_SCREEN_ON`；K40 是 API 33，未来升级到 Android 14+（尤其 FSI 规则）需重测。
11. **安装门**：MIUI 首次 `adb install -r` 会弹「USB安装提示 / 继续安装 / 拒绝（n）」；不点会在倒计时后自动拒绝，`PackageManager` 记为 `INSTALL_CANCELED_BY_USER`。第 4 次尝试在 15:37:51 返回 `Success`；「继续安装」由 `edith.phone.a11yprobe` 的 `CLICK_NOW` 场景点击（logcat：`A1CLICK_MATCH tick=57 label='继续安装' clicked=true package=com.miui.securitycenter`），未用 `input tap`，也未请用户手点。这是环境/操作前置，不是测试结果。

## 9. 探针源码与 APK（复现）

一次性模块（已加入根 `settings.gradle.kts` 的 `include`，仅新增该模块条目与两行注释，未改生产模块构建配置）：

- `tools/probes/wakelock-unlock-probe/build.gradle.kts`
- `tools/probes/wakelock-unlock-probe/src/main/AndroidManifest.xml`（`WAKE_LOCK`；Activity 无 manifest 级 `turnScreenOn`，避免污染 `mode=wakelock` 的测量）
- `tools/probes/wakelock-unlock-probe/src/main/kotlin/edith/phone/wakelockprobe/ProbeActivity.kt`
- APK：`tools/probes/wakelock-unlock-probe/build/outputs/apk/debug/wakelock-unlock-probe-debug.apk`
  - SHA-256：`4B3FEDC5B98295FEE7140EE901A6E85084ADBBE0E7ABAE2FB4E716B4506C308A`
  - `applicationId=edith.phone.wakelockprobe`，`minSdk=28`，`targetSdk=33`，`versionName=0.1.0`

复现命令（红米 K40，显式 `-s 2696813b`）：

```powershell
$env:ANDROID_USER_HOME = "D:\Project_new\EDITH\.local\p3\android-home"
.\gradlew.bat :tools:probes:wakelock-unlock-probe:assembleDebug --console=plain
adb -s 2696813b install -r .\tools\probes\wakelock-unlock-probe\build\outputs\apk\debug\wakelock-unlock-probe-debug.apk
adb -s 2696813b shell input keyevent KEYCODE_SLEEP          # 只造前置；等待 Dozing / Display OFF
adb -s 2696813b shell am start -n edith.phone.wakelockprobe/.ProbeActivity --es mode wakelock
adb -s 2696813b logcat -d -b all -v time | Select-String "WAKELOCK_PROBE"
adb -s 2696813b shell dumpsys power   | Select-String "mWakefulness="
adb -s 2696813b shell dumpsys window policy | Select-String "interactiveState="
```

- `mode` 取值：`wakelock`、`activity`、`both`；可附加 `+dismiss`（如 `both+dismiss`）测试解锁回调；`mode` 含 `wakelock`/`both` 才取 WakeLock，含 `activity`/`both` 才调 `setTurnScreenOn`。
- 探针输出汇总行固定前缀：`WAKELOCK_PROBE mode=... interactiveBefore=... interactiveAfter=... keyguardBefore=... keyguardAfter=... dismissCallback=... elapsedMs=...`。
- 测试完成后已 `force-stop` 并 `uninstall edith.phone.wakelockprobe`；APK 仍保留在构建目录，便于复现。

## 10. 清理与前后一致性（2026-10-19 15:41:07）

| 项 | 测试后 | 与基线一致？ |
|---|---|---|
| 探针包 | `pm list packages` 无 `edith.phone.wakelockprobe`；无残留进程 | 已卸载 |
| 探针 WakeLock | `dumpsys power`：`Wake Locks: size=0`；历史里只有成对的 `ACQ/REL edith:wakelock-probe` | 无持有 |
| `locksettings get-disabled` | `true` | 是 |
| `stay_on_while_plugged_in` | `7` | 是 |
| `screen_off_timeout` | `600000` | 是 |
| `enabled_accessibility_services` | `edith.phone.a11yprobe/.A11yProbeService` | 是（未新增生产 a11y） |
| keyguard | `isKeyguardShowing=false`；`showing=false`；`secure=false` | 是 |
| 屏幕最终状态 | `Dozing`（15:43:40 结束的 120 s 观察仍为 Dozing）；基线是 `Awake` | 否（见下） |

**屏幕最终状态说明**：基线时屏幕是 `Awake`；实测结束时设备处于 `Dozing`。没有在测试后补发 `KEYCODE_WAKEUP` 去“恢复屏幕”，以避免任何 PC 唤醒动作与测量记录混淆；用户按电源键即可点亮。除屏幕状态外，所有设置/锁屏/常亮配置均未被改动。

**眼镜**：全部设备操作都带 `-s 2696813b`；唯一一次全局命令是开头的 `adb devices -l`（只读列举），没有对 `1901092544027662` 执行任何 shell/install/input。

## 11. 原始证据（本地，未入库）

- `.local/d5-wakelock-spike/baseline.txt`：基线只读读数。
- `.local/d5-wakelock-spike/rounds.log`：三轮唤醒 + 一次负对照的 pre/trigger/post 与 logcat 摘录。
- `.local/d5-wakelock-spike/post-wake-state.txt`：唤醒后的 a11y / Shizuku / 设置只读检查。
- `.local/d5-wakelock-spike/cleanup.txt`：卸载与设置前后一致性检查。
- `.local/d5-wakelock-spike/harness.ps1`：本地一次性运行脚本（不进入产品代码）。

> 本文只记状态、计数、耗时、包名与窗口类型；没有记录屏幕正文、截图或个人内容。
