**调查证据，未批准；不是设计。**

# D0b 无 PC 条件下唤醒并解除锁屏的能力边界（Android 13 / 红米 K40 / MIUI）

- 调查日期：2026-10-19（Asia/Shanghai）。
- 目标设备：红米 K40，MIUI / Android 13 / API 33，验收序列号 `2696813b`。本子代理**未连接、未操作设备**；本文所有 K40 结论均为外部证据 + 待实测，不是实机结论。
- 范围：只回答“应用自己能否在无 PC 时点亮屏幕、解除锁屏、以及边界在哪”；不写设计方案、不写选型建议。
- “无 PC”指**运行期**不通过 USB/ADB 连接 PC。一次性配对、首次授权、设备所有者配置等另注。
- “无安全锁屏”指 `KeyguardManager.isKeyguardSecure() == false`（Swipe/None）；有 PIN/图案/密码/指纹或 SIM 卡锁即视为 `true`，按业主口径直接判不可用，不尝试绕过。
- 证据级别：**官方文档 / AOSP 源码 / issue 报告 / 社区帖 / 推断**。不引用本仓库预研报告作为外部证据。

---

## 1. 结论摘要

### 1.1 生产可用性分级（能力判断，不是设计）

| 分级 | 路径 | 结论 |
|---|---|---|
| 有条件可用（无 Shizuku、无 PC） | `Notification` full-screen intent → Activity（`showWhenLocked` + `turnScreenOn`）→ `requestDismissKeyguard` | 官方文档与 AOSP 存在完整链路：非安全锁屏下无交互立即解除；AOSP 在 Activity 位于顶层且带 `turnScreenOn` 时先 `wakeUp` 再解除。**但 MIUI 有额外“锁屏显示”权限的实证报告，K40 未实测，不能先写成生产可用。** |
| 有条件可用（无 Shizuku、无 PC） | `SYSTEM_ALERT_WINDOW` → 后台 `startActivity` → 同上 Activity | `SYSTEM_ALERT_WINDOW` 是 Android 10+ 后台启动 Activity 的官方豁免之一；但端到端是否被 MIUI 额外拦截未实测。 |
| 条件可用（无 Shizuku、无 PC） | 应用内 `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` + Activity 路径 | 只能负责点亮屏幕，**本身不解除锁屏**；Android 13 普通应用默认可用（AOSP app-op 默认允许），但已废弃，且 K40 未实测。 |
| 不可靠/不宜作生产前提 | Shizuku 执行 `input keyevent 224` + `wm dismiss-keyguard` / `input swipe` | 技术链路存在（shell UID 持有 `INJECT_EVENTS`、`CONTROL_KEYGUARD`），但 MIUI 息屏会停 `shizuku_server`；官方 workaround 不保证，且重启后需重新启动 Shizuku。 |
| 不可用 | 只靠无障碍服务解除锁屏或操作锁屏后的目标 App | 锁屏时目标 App 的 Activity 通常进入 stopped，Accessibility 只暴露 SystemUI；issue #1035 有直接实证。 |
| 不可用 | 仅 `input keyevent 82` | AOSP 13 中未找到 `KEYCODE_MENU` 解除锁屏的处理；历史做法不能作为 Android 13 依据。 |
| 不可用 | 前台服务单独 `startActivity`（无豁免） | Android 10（API 29）起后台 Activity 启动被限制；前台服务本身不在官方豁免列表中。 |
| 需一次性配置，不是运行期无 PC 路径 | `DevicePolicyManager.setKeyguardDisabled` | 需 device owner；且“设置了密码/PIN/图案时无效”；获取 DO 本身通常要一次性 ADB/QR 配置。 |
| 无意义/不可用（无安全锁屏） | `createConfirmDeviceCredentialIntent` | 无安全锁屏时返回 `null`；有安全锁屏时是让用户输入凭证，不是自动解除。 |
| 废弃、待实测 | `exitKeyguardSecurely` / `KeyguardLock.disableKeyguard` | 官方已废弃；AOSP 13 仍有路径，但 K40/MIUI 行为无证据。 |

### 1.2 关键结论（每条带来源）

1. **`requestDismissKeyguard` 的官方语义是明确的**：非安全锁屏或可信状态会“立即解除锁屏且无任何用户交互”；安全锁屏会弹出凭证界面，**不是绕过**。调用它的 Activity 必须已经可见（`showWhenLocked`）或“在 Keyguard 不遮挡时本应可见”，否则立刻回调 `onDismissError`。见官方文档（API 26）与 AOSP `KeyguardController`。
2. **锁屏背后的 App Activity 默认不是“可见”的**：官方 `setShowWhenLocked` 文档写明，正常情况下，在锁屏升起时启动的 Activity 会进入 stopped；只有设置该标志才保持 resumed 并显示在锁屏之上。这解释了无障碍为何只看到 SystemUI（issue #1035）。
3. **`setTurnScreenOn(true)` 与解除锁屏是两件事**：官方文档说该标志使 Activity 在 resumed 时点亮屏幕，且“若设置该标志并调用 `requestDismissKeyguard`，屏幕会点亮”；AOSP `KeyguardController.dismissKeyguard` 在“带 `turnScreenOn` 且是顶层 Activity”时先 `mTaskSupervisor.wakeUp("dismissKeyguard")`，再请求 WindowManager 解除锁屏。
4. **`wm dismiss-keyguard` 在屏幕熄灭时不能单独完成唤醒+解锁**：AOSP 的 SystemUI 路径在 `mDozing` 时把解除动作存入 `mPendingWakeupAction` 并返回；只有 AOD pulse 且允许解除时才走 wake-and-unlock。命令本身只发解除请求，不负责点亮屏幕。见 `StatusBarKeyguardViewManager` / `CentralSurfacesImpl`。
5. **`wm dismiss-keyguard` 的权限来自 shell**：AOSP `WindowManagerService.dismissKeyguard` 要求签名级 `android.permission.CONTROL_KEYGUARD`；AOSP `packages/Shell/AndroidManifest.xml` 确实声明了该权限，因此 adb shell / Shizuku 的 shell UID 技术上够权限。命令帮助原文是“Dismiss the keyguard, prompting user for auth if necessary”，安全锁屏仍然要认证。
6. **MIUI 有额外的“锁屏显示”门**：`flutter_callkit_incoming` issue #667 报告 MIUI 12–14 / Android 11–13 上，`setShowWhenLocked(true)` + `setTurnScreenOn(true)` + `requestDismissKeyguard(...)` 仍不显示在锁屏上，必须在 MIUI 设置中手动打开“锁屏显示”（Settings → Apps → App → Other permissions → Show on Lock Screen）；维护者也确认部分中国厂商需要手动开启。Stack Overflow 上小米 full-screen intent 的答案也指向同一权限，并有一个“仍不亮屏、最后用 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 解决”的报告。**这些是 issue/社区证据，不是 K40 实测。**
7. **MIUI 息屏停 Shizuku 是官方指南承认的问题**：Shizuku issue #1426 报告小米设备息屏后 `shizuku_server` 停止；RikkaW 在另一个 issue #293 中直接链接官方用户手册的 “Shizuku randomly stops” 一节。官方建议包括保持 USB 调试/开发者选项、把默认 USB 配置设为 “No data transfer”、开启 “Disable adb authorization timeout”；MIUI 还要求不要用安全 App 的扫描功能。即便按此配置，仍有用户报告再次失效，且官方说明无线调试启动方式**每次重启后都要重做**。
8. **`input keyevent 82` 在 AOSP 13 未找到解除锁屏语义**：`KEYCODE_MENU` 的文档只有“Menu key”；AOSP 13 `PhoneWindowManager` 中 `KEYCODE_MENU` 仅用于 Shift+Menu 触发 bug report，未找到 keyguard dismiss 分支。历史上 Android 4.2 之前可用的社区答案不能外推到 Android 13。
9. **`input keyevent 224` 能点亮屏幕，但只在 shell/Shizuku 下可用**：`KEYCODE_WAKEUP`（API 20，值 224）文档说“Wakes up the device”；`input` 命令最终调用 `InputManager.injectInputEvent`，AOSP 要求 `INJECT_EVENTS` 权限，而 `com.android.shell` 持有该权限。普通应用无法注入；而且点亮屏幕不等于解除锁屏。
10. **Android 14+ 的 full-screen intent 规则会变化**：Android 14 行为变更文档写明，Android 11 起任意应用可声明 `USE_FULL_SCREEN_INTENT` 自动获得锁屏全屏 intent；target 34+ 的应用只限通话/闹钟类，`canUseFullScreenIntent()`（API 34）用于检查，用户可开关。K40 当前是 Android 13，暂不受该限制；若将来升级系统，边界会变。

---

## 2. 路径总表

| 路径 | 依赖 | 无 PC 可用? | 无安全锁屏? | 需 Shizuku? | 证据级别 | K40 适用性 | 待实测点 |
|---|---|---|---|---|---|---|---|
| P1. FSI 通知 → Activity(`showWhenLocked`+`turnScreenOn`) → `requestDismissKeyguard` | `POST_NOTIFICATIONS`(13)、高重要性 channel、`USE_FULL_SCREEN_INTENT`、Activity 可见 | 是（运行期）；首次授权需用户点 | 是（否则弹凭证） | 否 | 官方文档 + AOSP；MIUI 差异为 issue/社区 | 可能可用，**未实测**；MIUI“锁屏显示”权限是风险 | FSI 是否在息屏+锁屏时真的启动 Activity；屏幕是否亮；回调是否成功；MIUI 权限默认值 |
| P2. `SYSTEM_ALERT_WINDOW` → 后台 `startActivity` → 同上 Activity | 用户授予“显示在其他应用上层”；BAL 豁免 | 是（运行期） | 是 | 否 | 官方文档（BAL 豁免）+ 推断（端到端） | 可能可用，**未实测**；MIUI 可能另有后台弹窗限制 | 息屏锁屏时 `startActivity` 是否被拦截；Activity 是否显示在 keyguard 上 |
| P3. 应用内 `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` 点亮 → Activity 路径/人工滑动 | `WAKE_LOCK`；应用进程存活；Android 13 app-op | 是 | 只点亮，不解除 | 否 | 官方文档 + AOSP | 可能可用，**未实测** | K40 是否真的亮屏；亮屏后 keyguard 是否仍需要 swipe/解除；MIUI 是否限制 |
| P4. Shizuku：`input keyevent 224` 然后 `wm dismiss-keyguard` | Shizuku 存活 + 已授权 + shell 权限 | 否（需 Shizuku 常驻；MIUI 息屏会杀） | 是（安全锁屏会要求认证） | 是 | AOSP + issue 报告 + 官方指南 | 生产不可靠 | 息屏后 Shizuku 是否存活；`wm` 命令在 MIUI 是否存在；命令时序 |
| P5. Shizuku：`input keyevent 224` + `input swipe` | 同上；非安全锁屏；已知滑动坐标/方向 | 否（同上） | 是 | 是 | AOSP（输入注入）+ 社区帖 | 生产不可靠；坐标/手势未实测 | 屏幕尺寸/方向/导航方式变化下的滑动；MIUI 手势 |
| P6. Shizuku/ADB：仅 `input keyevent 82` | shell 输入注入 | 否 | 否（AOSP 13 无解锁语义） | 是 | AOSP（负面）+ 社区历史 | 不可用/最多仅唤醒 | K40 上实际效果（预期：无解除） |
| P7. 无障碍服务操作锁屏/目标 App | 无障碍已启用；锁屏 | 是 | 不适用 | 否 | issue 报告 + 官方文档 | 不可用（只能 SystemUI） | K40 上 `getWindows()` 内容、事件回调、是否可操作目标 App |
| P8. `DevicePolicyManager.setKeyguardDisabled(true)` | device owner / 关联资料所有者；无密码/PIN/图案 | 运行期是，但需一次性 DO 配置 | 是（有安全锁屏时无效） | 否 | 官方文档 | 未知（MIUI DO 支持未测） | K40 能否成为 DO；MIUI 是否支持；对锁屏的实际效果 |
| P9. `exitKeyguardSecurely` / `KeyguardLock.disableKeyguard` | `DISABLE_KEYGUARD`（normal）；无安全锁屏 | 是 | 是 | 否 | 官方文档（已废弃）+ AOSP | 废弃；K40/MIUI 未知 | 是否仍能解除；回调；息屏时序 |
| P10. `createConfirmDeviceCredentialIntent` | 安全锁屏 + 用户输入 | 是 | 反向依赖（无安全锁屏返回 null） | 否 | 官方文档 | 不可用于“自动解除” | 无（能力上不成立） |
| P11. 前台服务直接 `startActivity`（无 SAW/FSI） | 前台服务 | 是 | 是 | 否 | 官方文档（BAL 限制） | 不可用 | 仅用于确认系统是否打印 “Background activity launch blocked!” |
| P12. 用户点通知/点悬浮窗后启动 | 用户交互 | 是 | 是 | 否 | 官方文档 | 可用但**不是自动唤醒** | 不适用（有用户交互） |

> 表中“无 PC 可用?”指运行期；P4/P5 的 Shizuku 虽可用无线调试在无 PC 下启动，但**重启后要重做**，且 MIUI 息屏会杀，不能按生产常驻能力计。

---

## 3. 逐条详细证据

### 3.1 `KeyguardManager.requestDismissKeyguard(Activity, callback)`

#### 3.1.1 API level 与官方语义

官方文档（`KeyguardManager`，方法 Added in API level 26）：

- “Requests that the Keyguard (lock screen) be dismissed if it is currently showing.”
- “If the Keyguard is not secure or the device is currently in a trusted state, calling this method will immediately dismiss the Keyguard without any user interaction.”
- “If the Keyguard is secure and the device is not in a trusted state, this will bring up the UI so the user can enter their credentials.”
- “If the value set for the Activity attr `turnScreenOn` is true, the screen will turn on when the keyguard is dismissed.”
- Activity 参数：“The activity must either be visible by using `showWhenLocked` or `Activity.setShowWhenLocked(boolean)`, or must be in a state in which it would be visible if Keyguard would not be hiding it. If that's not the case, the request will fail immediately and `KeyguardDismissCallback.onDismissError` will be invoked.”

来源（官方文档）：
- <https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback)>
- `isKeyguardSecure()`（API 16）：同页，且文档明确“secure lock screen 或至少一个 SIM 卡被锁”都会返回 true。
- `isKeyguardLocked()`（API 16）：同页；文档说明屏幕熄灭时逻辑上 keyguard 仍可能处于 showing 状态，且不区分安全/非安全。

#### 3.1.2 AOSP 13 实际调用链（关键）

`Activity` 调用后，经 `ActivityClientController.dismissKeyguard` 进入 `KeyguardController.dismissKeyguard`。AOSP 13 源码（tag `android-13.0.0_r1`）：

```java
void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback, CharSequence message) {
    final ActivityRecord activityRecord = ActivityRecord.forTokenLocked(token);
    if (activityRecord == null || !activityRecord.visibleIgnoringKeyguard) {
        failCallback(callback);
        return;
    }
    ...
    if (activityRecord.getTurnScreenOnFlag() && activityRecord.isTopRunningActivity()) {
        mTaskSupervisor.wakeUp("dismissKeyguard");
    }
    mWindowManager.dismissKeyguard(callback, message);
}
```

这给出两个硬边界：
1. **Activity 必须 `visibleIgnoringKeyguard`**（与官方文档一致），否则 `onDismissError`。
2. **只有带 `turnScreenOn` 且是顶层 Activity 时，调用本身才先唤醒屏幕**；不带该标志时，屏幕熄灭状态下不要假设它会亮。

来源（AOSP 源码）：
- `KeyguardController.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/KeyguardController.java>
- `ActivityClientController.java` 的 `dismissKeyguard`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/ActivityClientController.java>

#### 3.1.3 `setShowWhenLocked` / `setTurnScreenOn` 的 API 与含义

- `Activity.setShowWhenLocked(boolean)`：Added in API level 27。
  - 官方原文：“Specifies whether an Activity should be shown on top of the lock screen whenever the lockscreen is up and the activity is resumed. Normally an activity will be transitioned to the stopped state if it is started while the lockscreen is up, but with this flag set the activity will remain in the resumed state visible on-top of the lock screen.”
- `Activity.setTurnScreenOn(boolean)`：Added in API level 27。
  - 官方原文：“Specifies whether the screen should be turned on when the Activity is resumed. Normally an activity will be transitioned to the stopped state if it is started while the screen if off, but with this flag set the activity will cause the screen to turn on if the activity will be visible and resumed due to the screen coming on. The screen will not be turned on if the activity won't be visible after the screen is turned on. This flag is normally used in conjunction with the `showWhenLocked` flag to make sure the activity is visible after the screen is turned on when the lockscreen is up. In addition, if this flag is set and the activity calls `KeyguardManager.requestDismissKeyguard(...)` the screen will turn on.”

来源（官方文档）：
- <https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean)>
- <https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean)>

AOSP `ActivityRecord.resume` 中也有对应行为：

```java
if (canTurnScreenOn()) {
    mTaskSupervisor.wakeUp("turnScreenOnFlag");
}
```

`canTurnScreenOn()` 要求 `getTurnScreenOnFlag()` 为真，且 `mTaskSupervisor.getKeyguardController().checkKeyguardVisibility(this)` 为真（即 Activity 在 keyguard 下可见）。

来源（AOSP 源码）：
- `ActivityRecord.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/ActivityRecord.java>

#### 3.1.4 从后台/前台服务拉起 Activity 的限制

官方 “Activity security / Background activity starts” 文档写明：自 Android 10（API level 29）起限制后台启动 Activity；允许的例外包括：

- The app has a visible window, such as an activity in the foreground.
- The app is the current Input Method Editor (IME).
- The activity is started from a PendingIntent that was sent by the system (for example, from a notification tap).
- The app has the `SYSTEM_ALERT_WINDOW` permission granted by the user.
- The app has been granted the `START_ACTIVITIES_FROM_BACKGROUND` permission.
- The app is bound by a service that has been granted permission to start background activities.
- The launch is initiated by the device's launcher app.
- The launch is from a core part of the operating system that must run at all times.

来源（官方文档）：
- <https://developer.android.com/guide/components/activities/background-starts?hl=en>（现重定向为 “Activity security” 页面）

由此：
- **前台服务本身不是豁免**；只有“前台服务 + 应用有可见窗口/SAW/系统 PendingIntent/其他豁免”才可能放行。文档明确把 foreground service 列为持续性任务的正确机制，但没有把它列为可随意启动 Activity 的豁免。
- **`SYSTEM_ALERT_WINDOW` 是官方豁免**，因此“SAW → startActivity”在框架层是成立的；但 MIUI 是否额外拦截、以及 SAW 的授权入口在 K40 上的具体名称，未实测。
- **full-screen intent 属于“system-sent PendingIntent”**：官方豁免列表包含 system-sent PendingIntent；`setFullScreenIntent` 的 intent 由系统（通知栈）在锁屏时发送，故在框架语义上应落入该豁免。这一句是**官方列表 + 推断**，不是官方逐字写明 “FSI 是 BAL 豁免”。

#### 3.1.5 有安全锁屏时的行为

- 官方：安全锁屏且非可信状态时，`requestDismissKeyguard` “will bring up the UI so the user can enter their credentials”。即**弹凭证界面**，不是自动解除，也不能绕过。
- `wm dismiss-keyguard` 的 AOSP 帮助原文也写“prompting user for auth if necessary”。
- 因此业主口径 `isKeyguardSecure()==true` 直接判不可用，与平台语义一致；没有找到任何官方 API 能在安全锁屏下无凭证自动解除（`setKeyguardDisabled` 也明确“有密码/PIN/图案时无效”）。

#### 3.1.6 MIUI 差异（关键风险，尚非 K40 实测）

**证据 A（issue 报告）**：`flutter_callkit_incoming` issue #667，标题 “Incoming Call UI Not Showing Over Lock Screen on MIUI Devices”。报告范围明确写“Xiaomi / POCO / Redmi (MIUI 12–14), Android 11–13”。报告者称 Activity 使用了：

```
setShowWhenLocked(true)
setTurnScreenOn(true)
keyguardManager.requestDismissKeyguard(...)
```

manifest 也设置了 `android:showWhenLocked="true"` / `android:turnScreenOn="true"`，但“MIUI ignores these flags unless the user manually enables ‘Show on Lock Screen’ permission in MIUI settings (Settings → Apps → [App] → Other Permissions → Show on Lock Screen)”。维护者回复“some chinese phone brands. need to enable manual show in lock screen in setting”。

来源（issue 报告）：<https://github.com/hiennguyen92/flutter_callkit_incoming/issues/667>

**证据 B（社区帖）**：Stack Overflow “FullScreenIntent not working on xiaomi devices”（2023-03，Android 13 时期）。提问者使用 `setFullScreenIntent` + `setShowWhenLocked` + `setTurnScreenOn` + `requestDismissKeyguard`，在小米设备上“Sometimes, the screen is not switched on”。一个回答明确指出小米有专门的 “Show on lock screen” 权限，必须手动开启：Settings → app → Other permissions → Show on lock screen → Always allow；并称“没有编程方式开启”，热门应用可能被厂商默认白名单。提问者反馈即使打开该权限仍不亮屏，最终用 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 的 wake lock 才解决。

来源（社区帖）：<https://stackoverflow.com/questions/75662575/fullscreenintent-not-working-on-xiaomi-devices>

**证据 C（旧 MIUI 差异，历史）**：Stack Overflow “FLAG_DISMISS_KEYGUARD not working on Xiaomi phone”（2018，Android 8 时期）报告小米上点击后没有弹出解锁提示。该证据年久，只说明 MIUI 历史上对 keyguard 标志有差异，不能外推到 Android 13。

来源（社区帖）：<https://stackoverflow.com/questions/49086391/flag-dismiss-keyguard-not-working-on-xiaomi-phone>

**结论**：`requestDismissKeyguard` 本身在 AOSP 语义上是“非安全锁屏无交互解除”，但 MIUI 上可能因为“锁屏显示”权限而使 Activity 不可见/不显示，从而走 `onDismissError` 或根本没有 UI。K40（MIUI 14 / Android 13）必须实测。

---

### 3.2 `wm dismiss-keyguard`（AOSP WindowManagerShellCommand）

#### 3.2.1 实际调用链

AOSP 13 `WindowManagerShellCommand`：

```java
case "dismiss-keyguard":
    return runDismissKeyguard(pw);
...
private int runDismissKeyguard(PrintWriter pw) throws RemoteException {
    mInterface.dismissKeyguard(null /* callback */, null /* message */);
    return 0;
}
```

帮助文本：

```
dismiss-keyguard
  Dismiss the keyguard, prompting user for auth if necessary.
```

来源（AOSP 源码）：
- `WindowManagerShellCommand.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/WindowManagerShellCommand.java>

#### 3.2.2 权限要求：shell UID 是否够

`WindowManagerService.dismissKeyguard`：

```java
public void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message) {
    if (!checkCallingPermission(permission.CONTROL_KEYGUARD, "dismissKeyguard")) {
        throw new SecurityException("Requires CONTROL_KEYGUARD permission");
    }
    ...
    mPolicy.dismissKeyguardLw(callback, message);
}
```

`CONTROL_KEYGUARD` 是签名级权限（AOSP `core/res/AndroidManifest.xml`）：

```xml
<!-- Allows an application to control keyguard. Only allowed for system processes. @hide -->
<permission android:name="android.permission.CONTROL_KEYGUARD"
    android:protectionLevel="signature" />
```

而 AOSP `packages/Shell/AndroidManifest.xml` 中声明了：

```xml
<uses-permission android:name="android.permission.CONTROL_KEYGUARD" />
```

因此：
- **adb shell / Shizuku 的 shell UID 技术上够权限**（前提是厂商没有改 shell 的权限集）。
- 普通第三方 App 不能直接调用 `IWindowManager.dismissKeyguard`（没有 `CONTROL_KEYGUARD`）。
- 若通过 Shizuku 执行 `cmd window dismiss-keyguard`，binder 调用者 UID 是 shell；这是 Shizuku “以 shell 身份执行系统命令”的常规能力，但**“Shizuku 一定能把该命令执行为 shell UID”属于其设计推断**，未在 K40 实测。

来源（AOSP 源码）：
- `WindowManagerService.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/WindowManagerService.java>
- `core/res/AndroidManifest.xml`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/core/res/AndroidManifest.xml>
- `packages/Shell/AndroidManifest.xml`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/Shell/AndroidManifest.xml>

#### 3.2.3 屏幕熄灭时是否可用（关键：不等于能唤醒）

`wm dismiss-keyguard` 只调用 `dismissKeyguard`，不做 `wakeUp`。AOSP SystemUI 的解除路径：

1. `PhoneWindowManager.dismissKeyguardLw`：只有 `mKeyguardDelegate.isShowing()` 时才调用 `mKeyguardDelegate.dismiss(...)`；否则（callback 为 null 时）什么也不做。
2. `KeyguardViewMediator.handleDismiss`：只有 `mShowing` 时才 `dismissAndCollapse()`；否则 callback 报错（这里 callback 为 null，直接无效果）。
3. `StatusBarKeyguardViewManager.dismissWithAction` 中：

```java
// If we're dozing, this needs to be delayed until after we wake up - unless we're
// wake-and-unlocking, because there dozing will last until the end of the transition.
if (mDozing && !isWakeAndUnlocking()) {
    mPendingWakeupAction = new DismissWithActionRequest(
            r, cancelAction, afterKeyguardGone, message);
    return;
}
```

4. `CentralSurfacesImpl.dismissKeyguardThenExecute` 只有在以下条件全部满足时才启动 wake-and-unlock：
   - `mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP`
   - `mKeyguardStateController.canDismissLockScreen()`
   - `!mStatusBarStateController.leaveOpenOnKeyguardHide()`
   - `mDozeServiceHost.isPulsing()`

  否则走 `dismissWithAction`，在 dozing 时被排队。

来源（AOSP 源码）：
- `PhoneWindowManager.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java>
- `KeyguardViewMediator.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java>
- `StatusBarKeyguardViewManager.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarKeyguardViewManager.java>
- `CentralSurfacesImpl.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java>

**结论**：
- 屏幕亮着且 keyguard 非安全：`wm dismiss-keyguard` 可以解除。
- 屏幕熄灭（普通 doze/off，不是 AOD pulse）：命令可以执行，但解除动作被**延迟到下次唤醒**；它**不会把屏幕点亮**。因此“只发 `wm dismiss-keyguard` 就完成唤醒+解锁”不成立。
- 安全锁屏：帮助文本明确可能要求认证，不能无凭证解除。

#### 3.2.4 MIUI 上是否有已知失效/差异

**未找到来源**。检索了 `wm dismiss-keyguard` + MIUI/HyperOS/小米/Shizuku 的中英文组合，没有找到可信的 MIUI 专门报告。能说的只有：
- 该命令属于 AOSP WindowManagerShellCommand；MIUI 基于 AOSP，通常保留，但**未找到 K40/MIUI 14 的实证**。
- 即使命令存在，Shizuku 在 MIUI 息屏后被停（见 3.2.5），执行前提不成立。

**结论**：MIUI 差异 = 未找到来源；K40 必须实测命令是否存在、是否需要额外权限、息屏时行为。

#### 3.2.5 通过 Shizuku 执行的前置条件（与 #1426 冲突）

技术前置：
1. Shizuku 服务 `shizuku_server` 存活；
2. 应用已获 Shizuku 授权；
3. shell UID 在该 ROM 上仍持有 `CONTROL_KEYGUARD`（AOSP 默认持有）。

现实冲突：
- Shizuku issue #1426：“Shizuku stops when switching screen off on Xiaomi devices and on some other devices”。报告设备包括 Redmi Note 9 Pro（MIUI 14 / Android 12）、Poco X5 5G（HyperOS / Android 14）；描述“As soon as I switch off the screen ... the Shizuku service stops”，并称 `shizuku_server` 是息屏时停止的进程。Issue 中给出的解决方案是：开启“USB debugging (Security settings)”（需要小米账号）、把默认 USB 配置设为 “No data transfer”（部分用户用 “Charging only”），并在 PC/无线调试下启动 Shizuku；但后来仍有用户反馈“today it stopped working”。
- Shizuku 官方用户手册 FAQ “Shizuku randomly stops” 给出同样方向：保持后台运行、不要关闭 USB 调试/开发者选项、Android 9+ 把 “Default USB configuration” 设为 “No data transfer”、Android 11+ 开启 “Disable adb authorization timeout”；MIUI 额外要求不要使用安全 App 的扫描功能。官方同时说明无线调试启动方式“每次重启后都要重做”，而 root 方式才能开机自启。
- Shizuku issue #293（OnePlus 6T，Android 11）同样报告息屏后被杀，RikkaW 直接链接上述官方 FAQ；提问者按 FAQ 的 “charge only” 设置后解决。另一个用户报告 Android 13 平板也需 “charge only”。

来源（issue/官方指南）：
- <https://github.com/RikkaApps/Shizuku/issues/1426>
- <https://github.com/RikkaApps/Shizuku/issues/293>
- <https://shizuku.rikka.app/guide/setup/>（FAQ: “Start via wireless debugging/Start by connecting to a computer: Shizuku randomly stops”）

**结论**：Shizuku 路径的“无 PC”只在“Shizuku 已通过无线调试启动且未被 MIUI 杀”的窗口内成立；不能把它当作稳定的生产唤醒/解屏通道。K40 上必须实测息屏后存活时间，以及官方 workaround 是否有效。

---

### 3.3 点亮屏幕的路径

#### 3.3.1 `Activity.setTurnScreenOn(true)`

- API 27；官方文档见 3.1.3。
- 关键限制：Activity 必须“visible and resumed”；“The screen will not be turned on if the activity won't be visible after the screen is turned on.” 因此要与 `showWhenLocked` 联用。
- 与 `requestDismissKeyguard` 的关系：设置该标志后调用 `requestDismissKeyguard`，屏幕会点亮。AOSP 在 `KeyguardController.dismissKeyguard` 中显式 `wakeUp("dismissKeyguard")`。
- 是否需要前台 Activity：需要 Activity 为顶层且 keyguard 下可见；仅后台服务不行。
- 权限：不需要特殊权限；但 Activity 本身要能被合法启动（BAL 限制见 3.1.4）。

**证据级别**：官方文档 + AOSP 源码。**K40 未实测**。

#### 3.3.2 `PowerManager` 的 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`

官方文档：

- `SCREEN_BRIGHT_WAKE_LOCK`：Added in API level 1，**Deprecated in API level 15**。原文：“Ensures that the screen is on at full brightness; the keyboard backlight will be allowed to go off. If the user presses the power button, then the SCREEN_BRIGHT_WAKE_LOCK will be implicitly released by the system...”。
- `ACQUIRE_CAUSES_WAKEUP`：Added in API level 1，**Deprecated in API level 33**。原文：“Wake lock flag: Turn the screen on when the wake lock is acquired. This flag will require `Manifest.permission.TURN_SCREEN_ON` in future releases. Normally wake locks don't actually wake the device... This flag will cause the device to wake up when the wake lock is acquired. ... Cannot be used with PARTIAL_WAKE_LOCK.”
- 获取 wake lock 需要 `WAKE_LOCK` 权限（官方 `newWakeLock` 文档：“Although a wake lock can be created without special permissions, the `Manifest.permission.WAKE_LOCK` permission is required to actually acquire or release the wake lock”）。

AOSP 13 的实际限制（比“已废弃但可用”更细）：

`PowerManagerService.applyWakeLockFlagsOnAcquireLocked` 只有在 `ACQUIRE_CAUSES_WAKEUP` + 屏幕级 wake lock 时，才检查 `isAcquireCausesWakeupFlagAllowed`：

- 调用者是 privileged → 允许；
- 否则 `AppOpsManager.checkOpNoThrow(AppOpsManager.OP_TURN_SCREEN_ON, opUid, opPackageName) == MODE_ALLOWED` → 允许；
- 否则不亮屏。

`AppOpsManager` 的 `sOpDefaultMode` 中 `OP_TURN_SCREEN_ON` 默认是 `MODE_ALLOWED`。因此 **Android 13 普通应用默认可以用该 wake lock 点亮屏幕**（除非用户/厂商撤销 app-op）；但 `TURN_SCREEN_ON` 权限在 Android 14（API 34）才正式加入，未来版本会收紧。

来源（官方文档 + AOSP 源码）：
- <https://developer.android.com/reference/android/os/PowerManager#SCREEN_BRIGHT_WAKE_LOCK>
- <https://developer.android.com/reference/android/os/PowerManager#ACQUIRE_CAUSES_WAKEUP>
- `PowerManagerService.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java>
- `AppOpsManager.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/core/java/android/app/AppOpsManager.java>

限制：
- **只亮屏，不解除 keyguard**；锁屏仍然在，需要后续 `requestDismissKeyguard` / 滑动 / shell 解除。
- 已废弃；Android 14+ 可能要求 `TURN_SCREEN_ON` 权限（官方文档说“will require ... in future releases”）。
- MIUI 是否允许、是否会被“后台限制”拦截，**未找到来源**；Stack Overflow 小米 FSI 帖中有人用它解决亮屏，属于社区帖。

#### 3.3.3 shell `input keyevent KEYCODE_WAKEUP`（224）

- `KeyEvent.KEYCODE_WAKEUP`：Added in API level 20，常量值 224。官方原文：“Key code constant: Wakeup key. Wakes up the device. Behaves somewhat like KEYCODE_POWER but it has no effect if the device is already awake.”
- AOSP `PhoneWindowManager` 对注入的 `KEYCODE_WAKEUP` 有显式处理：`else if (down && (isWakeKey || keyCode == KeyEvent.KEYCODE_WAKEUP) && isWakeKeyWhenScreenOff(keyCode)) { wakeUpFromWakeKey(event); }`。
- `input` 命令在 Android 13 中是一个 shell wrapper（`/system/bin/input` → `cmd input "$@"`），实现 `InputShellCommand` 调用 `InputManager.getInstance().injectInputEvent(...)`；AOSP `InputManagerService.injectInputEvent` 要求 `android.permission.INJECT_EVENTS`，而 `com.android.shell` 声明了该权限。
- 因此：**adb shell / Shizuku shell 可用；普通 App 不可用**；**只亮屏，不解除锁屏**。
- MIUI：未找到专门来源；需实测 `input keyevent 224` 是否被允许、是否点亮。

来源：
- 官方文档：<https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_WAKEUP>
- AOSP `PhoneWindowManager.java`、`InputShellCommand.java`、`InputManagerService.java`、`packages/Shell/AndroidManifest.xml`（URL 见第 6 节）。

#### 3.3.4 shell `input keyevent 82`（KEYCODE_MENU）

- `KEYCODE_MENU`：Added in API level 1，值 82；官方文档只有“Key code constant: Menu key.”，**没有解锁语义**。
- AOSP 13 `PhoneWindowManager` 的 `KEYCODE_MENU` 分支只处理 “Shift + Menu 发 bug report”，没有 keyguard dismiss。
- 历史社区答案（Stack Overflow 2015）称 `input keyevent 82` 可在“first boot”时解锁，但同一答案把 Android 4.2.2 当作分界，且建议改用 `keyevent 26` 唤醒；这不能作为 Android 13 依据。
- 未找到 AOSP 13 中 `KEYCODE_MENU` 解除 keyguard 的代码路径。

**结论**：`input keyevent 82` 不是 Android 13 的可靠解锁路径；最多作为“可能唤醒/无效果”的待测项。**证据级别：AOSP 负面证据 + 社区历史**。

#### 3.3.5 shell `input swipe`

- 注入滑动同样走 `InputManager.injectInputEvent`，需要 `INJECT_EVENTS`；因此只有 shell/Shizuku 能做。
- 社区资料（awesome-adb）：“If no password lock screen is unlocked by sliding gestures, so you can input swipe to unlock”，示例 `adb shell input swipe 300 1000 300 500`（Nexus 5 上滑）。这说明：**仅非安全锁屏**、需要先亮屏、坐标/方向依设备而变。
- AOSP 没有为“滑动解锁”提供公开 API 或固定坐标；这是对 SystemUI 手势的模拟，不是稳定契约。
- MIUI 手势/导航方式是否一致：**未找到来源**。

来源（社区帖）：<https://github.com/lstarboy/awesome-adb/blob/master/README.en.md>（“On / Off screen”“Slide to unlock”两节）

---

### 3.4 `USE_FULL_SCREEN_INTENT` 拉起 Activity

#### 3.4.1 官方语义与触发条件

`Notification.Builder.setFullScreenIntent(PendingIntent, boolean)`：Added in API level 11。

官方原文要点：
- “An intent to launch instead of posting the notification to the status bar. Only for use with extremely high-priority notifications demanding the user's immediate attention, such as an incoming phone call or alarm clock...”
- “Prior to `Build.VERSION_CODES.TIRAMISU`, the system may display a heads up notification ... instead of launching the intent, while the user is using the device. From `Build.VERSION_CODES.TIRAMISU`, the system UI will display a heads up notification, instead of launching this intent, while the user is using the device.”
- “To be launched as a full screen intent, the notification must also be posted to a channel with importance level set to `IMPORTANCE_HIGH` or higher.”
- “Requires `Manifest.permission.USE_FULL_SCREEN_INTENT`.”

因此：**当用户没有在使用设备（锁屏/息屏）时，FSI 才会启动 Activity；用户正在使用时是 heads-up**。这与“锁屏自动拉起”目标一致，但它是为通话/闹钟设计的机制。

来源（官方文档）：
- <https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,%20boolean)>

`USE_FULL_SCREEN_INTENT`：Added in API level 29，**Protection level: normal**。Android 13 上声明即自动授予（Android 11 起的行为，见 Android 14 行为变更文档的回顾）。

来源（官方文档）：
- <https://developer.android.com/reference/android/Manifest.permission#USE_FULL_SCREEN_INTENT>

#### 3.4.2 Android 14+ 的差异

Android 14 行为变更文档 “Secure full-screen Intent notifications” 原文：

- “With Android 11 (API level 30), it was possible for any app to use `Notification.Builder.setFullScreenIntent` to send full-screen intents while the phone is locked. You could auto-grant this on app install by declaring `USE_FULL_SCREEN_INTENT` permission in the AndroidManifest.”
- “For apps targeting Android 14 (API level 34) or higher, apps that are allowed to use this permission are limited to those that provide calling and alarms only. The Google Play Store revokes default `USE_FULL_SCREEN_INTENT` permissions for any apps that don't fit this profile.”
- “This permission remains enabled for apps installed on the phone before the user updates to Android 14. Users can turn this permission on and off.”
- “You can use the new API `NotificationManager.canUseFullScreenIntent` to check if your app has the permission; if not, your app can use the new intent `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` to launch the settings page where users can grant the permission.”

`NotificationManager.canUseFullScreenIntent()`：Added in API level 34。官方文档：“Returns whether the calling app can send fullscreen intents. From Android `UPSIDE_DOWN_CAKE`, apps may not have permission ... If permission is denied, notification will show up as an expanded heads up notification on lockscreen.”

来源（官方文档）：
- <https://developer.android.com/about/versions/14/behavior-changes-14>
- <https://developer.android.com/reference/android/app/NotificationManager#canUseFullScreenIntent()>

#### 3.4.3 K40 上是否可用

- K40 是 Android 13（API 33），不是 Android 14：`USE_FULL_SCREEN_INTENT` 是 normal 权限，声明即授予；`canUseFullScreenIntent()` 在 API 33 上不存在，代码必须做版本判断（否则 `NoSuchMethodError`）。
- Android 13 还需要 `POST_NOTIFICATIONS` 运行时权限和 `IMPORTANCE_HIGH` 通知 channel。通知权限的官方说明：<https://developer.android.com/develop/ui/views/notifications/notification-permission>。
- MIUI 额外差异：Stack Overflow 小米 FSI 帖（见 3.1.6）报告必须手动开 “Show on lock screen”，且即使开启仍可能不亮屏；一个回答用 wake lock 解决。**这些是社区帖，不是 K40 实测。**
- 因此：**框架层可用；K40/MIUI 实际可用性未知，必须实测**。

#### 3.4.4 FSI 与后台 Activity 启动豁免

- 官方 BAL 例外包含 “The activity is started from a PendingIntent that was sent by the system (for example, from a notification tap).”
- FSI 的 `PendingIntent` 由系统通知栈在锁屏时发送，语义上属于 system-sent PendingIntent；同时 FSI 官方文档直接承诺“launch instead of posting ... while the user is using the device”之外的情况会启动。**这是官方文档 + 推断**，不是 AOSP 中逐行标注“FSI 豁免”的注释。
- 需要注意：FSI 的 `PendingIntent` 仍需应用自己创建，且 Android 14+ 对 target 34 的 BAL/PendingIntent 选择加入规则更严格；K40 A13 不受影响，但未来升级要重测。

---

### 3.5 无障碍与锁屏的官方/实证边界

#### 3.5.1 服务是否仍绑定、事件是否仍回调

**未找到官方说法**说“锁屏时系统会暂停/解绑 AccessibilityService”。检索 developer.android.com 的无障碍文档、`AccessibilityService` 参考页、AOSP `AccessibilityManagerService`，没有发现“锁屏暂停全部无障碍服务”的官方声明。

可确认的是：
- Android 的 TalkBack（Google 的无障碍服务）在锁屏上仍可工作；例如 9to5Google 2024-04 报道 Android 15 Beta 1 为 TalkBack 增加锁屏指纹解锁的音频提示，说明无障碍服务在锁屏场景仍活跃。该证据是**新闻/社区**，且不是 K40/Android 13。
- Issue #1035 的场景中，无障碍服务仍能返回 SystemUI 窗口；说明服务至少仍在运行并响应窗口查询。

**结论**：没有官方“锁屏暂停无障碍”说法；从 issue/产品行为看，服务大概率仍绑定，但**只能看到 SystemUI**。K40 上必须实测服务连接和回调。

#### 3.5.2 `getWindows()` 看到什么

Issue #1035（ouroboros，2026 年，Pixel 设备 dogfood）原文：

> “In PR #873 dogfood, locking the Pixel left the core, bridge, notification listener and files available, but Accessibility exposed only SystemUI. App-interface automation behind keyguard was unavailable. Successful background checks did not prove that the agent could continue operating another app's UI.”

> “Do not represent an Accessibility tree containing only SystemUI as proof that the target app has no controls or that its operation completed.”

来源（issue 报告）：<https://github.com/razzant/ouroboros/issues/1035>

这与官方 `setShowWhenLocked` 文档一致：锁屏升起时，普通 Activity 会进入 stopped；因此目标 App 的窗口不在“可见窗口”集合里，无障碍只能看到 SystemUI/Keyguard 相关窗口。AOSP 的无障碍窗口列表基于可见窗口；具体过滤逻辑不在本调查展开。

**结论**：锁屏时 `getWindows()` 预期只有 SystemUI（以及可能的系统窗口），**看不到目标 App 的可操作节点**；因此无障碍不能用来“绕过锁屏操作目标 App”。K40 上必须实测 `getWindows()` 的窗口数量/类型/标题。

#### 3.5.3 是否存在“锁屏时无障碍被系统暂停”的官方说法

**未找到官方说法。** 相关的 AOSP 代码只有系统级的 `temporaryEnableAccessibilityStateUntilKeyguardRemoved`（需要 `TEMPORARY_ENABLE_ACCESSIBILITY` 权限，只有系统能用），它是“临时启用无障碍状态直到 keyguard 移除”，不是“锁屏时暂停所有服务”的通用机制。不能把该 API 当作“系统会暂停无障碍”的证据。

来源（AOSP 源码）：
- `AccessibilityManagerService.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java>

#### 3.5.4 MIUI 的无障碍额外风险

- HyperOS（Android 14+）社区项目 `hyperos-accessibility-fix` 报告：HyperOS 在进入“Ultra Battery Saver”等场景会 force-stop 应用，而 AOSP 在 force-stop 时会移除该应用的无障碍授权；项目用后台脚本监控并恢复。该证据针对 HyperOS 1.0+/A14+，**不是 K40 的 MIUI 14/A13**。
- 未找到 K40/MIUI 14 上“锁屏暂停无障碍”的直接来源；只能作为待实测风险。

来源（社区项目）：<https://github.com/chkndrp/hyperos-accessibility-fix>

---

### 3.6 其他可能路径（只核实能力）

#### 3.6.1 `DevicePolicyManager.setKeyguardDisabled`

官方文档（Added in API level 23）：

- “Called by a device owner or profile owner of secondary users that is affiliated with the device to disable the keyguard altogether.”
- “Setting the keyguard to disabled has the same effect as choosing ‘None’ as the screen lock type. However, this call has no effect if a password, pin or pattern is currently set. If a password, pin or pattern is set after the keyguard was disabled, the keyguard stops being disabled.”
- “As of `Build.VERSION_CODES.P`, this call also dismisses the keyguard if it is currently shown.”
- `SecurityException` if admin is not device owner / affiliated profile owner.

能力边界：
- 需要 **device owner**（或关联资料所有者）；普通 App 拿不到。
- 有安全锁屏（PIN/图案/密码）时**无效**；这与业主“无安全锁屏”前提一致，但也意味着它不能绕过安全锁屏。
- 获取 device owner 通常需要一次性 ADB/QR 配置（`dpm set-device-owner` 之类），不是运行期无 PC 路径；K40/MIUI 是否支持、是否会与小米账号/查找手机冲突，**未找到来源，未实测**。
- 即使 keyguard 被禁用，**点亮屏幕仍需另一条路径**（Activity `turnScreenOn` 或 wake lock）。

`setKeyguardDisabledFeatures`：只能禁用锁屏自定义功能（widgets、camera 等），**不是解除锁屏**；“A calling device admin must have requested `USES_POLICY_DISABLE_KEYGUARD_FEATURES`”。

来源（官方文档）：
- <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setKeyguardDisabled(android.content.ComponentName,%20boolean)>
- <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setKeyguardDisabledFeatures(android.content.ComponentName,%20int)>

#### 3.6.2 `createConfirmDeviceCredentialIntent` / `exitKeyguardSecurely`

`createConfirmDeviceCredentialIntent`：
- Added in API level 21，Deprecated in API level 29。
- 官方：“Get an intent to prompt the user to confirm credentials (pin, pattern, password or biometrics if enrolled) for the current user... Returns the intent for launching this activity or **null if no password is required**.”
- 需要 `PackageManager.FEATURE_SECURE_LOCK_SCREEN`。
- 能力边界：无安全锁屏时返回 null；有安全锁屏时是**让用户输入凭证**，不是自动解除；因此不是“无交互解除”路径。

`exitKeyguardSecurely`：
- Added in API level 1，Deprecated in API level 15；官方建议改用 `setShowWhenLocked` / `requestDismissKeyguard`。
- 官方：“Exit the keyguard securely... This will, if the keyguard is secure, bring up the unlock screen of the keyguard. Requires `Manifest.permission.DISABLE_KEYGUARD`.”
- `DISABLE_KEYGUARD` 是 **normal** 权限（官方 Manifest.permission 文档：“Allows applications to disable the keyguard if it is not secure.”）。
- AOSP 13 仍保留调用链：`WindowManagerService.exitKeyguardSecurely` → `PhoneWindowManager.exitKeyguardSecurely` → `mKeyguardDelegate.verifyUnlock(callback)`；`KeyguardViewMediator.handleVerifyUnlock` 会 `setShowingLocked(true)` 然后 `dismissAndCollapse()`。
- 但它是**废弃 API**，且 AOSP 路径在屏幕熄灭时同样会走 dozing 排队；没有 K40/MIUI 13 的实证。不能算生产可用，只能列为“待实测的遗留路径”。

`KeyguardLock.disableKeyguard()`（`newKeyguardLock`）：
- Added API 1，Deprecated API 15；同样需要 `DISABLE_KEYGUARD`；官方建议改用 `setShowWhenLocked`。
- AOSP 仍有 `WindowManagerService.disableKeyguard` → `KeyguardDisableHandler` 路径；但未找到 Android 13/MIUI 上“仍能无交互解除非安全锁屏”的实证。**未验证**。

来源（官方文档）：
- <https://developer.android.com/reference/android/app/KeyguardManager#createConfirmDeviceCredentialIntent(CharSequence,%20CharSequence)>
- <https://developer.android.com/reference/android/app/KeyguardManager#exitKeyguardSecurely(android.app.KeyguardManager.OnKeyguardExitResult)>
- <https://developer.android.com/reference/android/Manifest.permission#DISABLE_KEYGUARD>
- AOSP `WindowManagerService.java` / `PhoneWindowManager.java` / `KeyguardViewMediator.java`（URL 见第 6 节）。

#### 3.6.3 `input keyevent 82` / `input swipe`

见 3.3.4 / 3.3.5。结论：
- 82：AOSP 13 无解锁语义，不可靠。
- swipe：仅非安全锁屏、需先亮屏、需 shell、坐标/手势依设备；MIUI 未找到来源。
- 两者都不是“无 PC”的稳定路径，因为都依赖 shell/Shizuku；而 MIUI 息屏会杀 Shizuku。

#### 3.6.4 `requestDismissKeyguard` 在 MIUI 上的已知 bug/差异

已找到的直接证据是 3.1.6 的 `flutter_callkit_incoming` issue #667（MIUI 12–14 / Android 11–13）和 Stack Overflow 小米 FSI 帖（2023，Android 13 时期）：**MIUI 需要手动开启“锁屏显示”权限，否则 `showWhenLocked` / `turnScreenOn` / `requestDismissKeyguard` 的效果不出现**。

未找到：
- MIUI 上 `requestDismissKeyguard` 返回 `onDismissError` 的专门日志/issue；
- K40 / MIUI 14 的专门复现；
- 该 MIUI 权限能否通过 adb/Shizuku 授予的官方或可信来源（Stack Overflow 回答称“没有编程方式开启”，但这是 2023 年社区回答，未在 K40 验证）。

#### 3.6.5 前台服务直接启动 Activity

见 3.1.4。官方 BAL 例外不包含“仅前台服务”；前台服务必须配合可见窗口、SAW、系统 PendingIntent 等。故“前台服务里直接 `startActivity` 拉起锁屏 Activity”在 Android 10+ 默认会被拦截（Logcat 关键词 “Background activity launch blocked!”）。**这是官方文档结论；K40 上具体日志/行为未实测。**

---

### 3.7 依赖分类（明确回答“哪些不依赖 PC / 依赖 shell / 依赖无安全锁屏 / 必须实测”）

#### 3.7.1 不依赖 PC（运行期）的路径

- P1：FSI → Activity（`showWhenLocked` + `turnScreenOn`）→ `requestDismissKeyguard`。前提：一次性授予通知权限、`USE_FULL_SCREEN_INTENT`（A13 自动）、高重要性 channel；MIUI “锁屏显示”权限是否默认开启未知。
- P2：`SYSTEM_ALERT_WINDOW` → 后台 `startActivity` → 同上。前提：用户授予“显示在其他应用上层”；MIUI 可能另有弹窗权限。
- P3：应用内 wake lock 只负责亮屏，之后仍需 P1/P2 的 Activity 路径或人工滑动；不是完整闭环。
- P7：无障碍（不依赖 PC，但**不能解除锁屏/操作目标 App**）。
- P8：DPM `setKeyguardDisabled`（运行期不依赖 PC；但需要一次性 device owner 配置，且不是“自动唤醒”）。

#### 3.7.2 依赖 shell / Shizuku 的路径

- P4：`input keyevent 224` + `wm dismiss-keyguard`。
- P5：`input keyevent 224` + `input swipe`。
- P6：`input keyevent 82`（即便可用也仅 shell）。
- 3.6.2 的 `exitKeyguardSecurely` / `KeyguardLock` **不依赖 shell**（`DISABLE_KEYGUARD` 是 normal 权限），但已废弃，不能与 shell 路径混为一谈；其 K40 行为未知。

#### 3.7.3 依赖“无安全锁屏”的路径

- P1/P2/P3/P4/P5/P6/P9 的“无交互解除”都依赖 `isKeyguardSecure()==false`；有安全锁屏时：
  - `requestDismissKeyguard` 弹出凭证 UI（官方文档）；
  - `wm dismiss-keyguard` “prompting user for auth if necessary”（AOSP help）；
  - `input swipe` 无法通过安全锁屏；
  - `setKeyguardDisabled` 在已设 PIN/图案/密码时无效（官方文档）。
- 没有任何已核实路径能在安全锁屏下无凭证自动解除；`isKeyguardSecure()==true` 判不可用与平台边界一致。

#### 3.7.4 必须 K40 真机实测的路径

见第 4 节。

---

## 4. 必须 K40 实测清单

> 以下均为“问题”，不是方案；全部未测。

### 4.1 基础状态

1. K40 当前锁屏类型：None / Swipe / PIN/图案/密码/指纹；`KeyguardManager.isKeyguardSecure()`、`isKeyguardLocked()`、`isDeviceLocked()` 的实际返回值。
2. `dumpsys window policy` 中 keyguard 的 `showing / occluded / secure` 字段在息屏、亮屏、解锁后分别是什么。
3. `dumpsys deviceidle` / MIUI 电池优化 / 自启动设置对应用进程和 Shizuku 的影响。

### 4.2 P1（FSI + Activity）

4. K40 息屏+锁屏时，发出 `IMPORTANCE_HIGH` + `setFullScreenIntent` 通知，Activity 是否**无用户交互**启动。
5. 该 Activity 是否在 MIUI 上真正显示在 keyguard 之上；还是被“锁屏显示”权限拦截。
6. MIUI 的 “Show on Lock Screen / 锁屏显示” 默认是否开启；关闭时 FSI/showWhenLocked 的实际表现；能否用 adb/Shizuku 授予（不要假设可以）。
7. `setTurnScreenOn(true)` 是否点亮屏幕；点亮的延迟、是否先闪一下前一个前台应用（官方文档说 `ACQUIRE_CAUSES_WAKEUP` 可能先恢复前一个前台应用，`setTurnScreenOn` 的设计目标正是避免这一点）。
8. `requestDismissKeyguard` 的 `onDismissSucceeded` / `onDismissError` / `onDismissCancelled` 实际回调；从调用到解锁的耗时。
9. 在锁屏上显示 Activity 后，按 Home/Back/切换应用，keyguard 是否重新出现；是否有“occluded 但未真正解除”的状态。
10. FSI 在“屏幕亮着但锁屏”和“屏幕熄灭”两种状态下行为是否不同；在用户正在使用手机时是否只显示 heads-up（官方预期）。

### 4.3 P2（SAW + 直接 startActivity）

11. 授予“显示在其他应用上层”后，息屏+锁屏时从后台服务 `startActivity` 是否被拦截；Logcat 是否出现 “Background activity launch blocked!”。
12. MIUI 是否还有额外的“后台弹出界面/后台显示”权限；默认值是什么。
13. 该路径是否比 FSI 更少受 MIUI 通知/锁屏权限影响（仅能力对比，不做选型）。

### 4.4 P3（wake lock）

14. `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 在 K40 息屏时是否真的点亮；锁屏是否仍显示；是否被 MIUI 拦截。
15. 应用退到后台/被冻结时，wake lock 是否仍有效；MIUI 是否需要“自启动/无限制电池”。
16. 连续使用是否触发 Android 13 的 app-op `TURN_SCREEN_ON` 限制或厂商限制。

### 4.5 P4/P5/P6（Shizuku/shell）

17. K40 息屏后 `shizuku_server` 是否存活：息屏后 1 min / 5 min / 30 min；按官方 FAQ 设置（USB debugging security settings + No data transfer + 关闭授权超时 + 自启动/无限制电池）后是否改善。
18. 若存活，`wm dismiss-keyguard` 在 K40 上是否存在、是否返回错误；`cmd window dismiss-keyguard` 的退出码/输出。
19. `input keyevent 224` 是否能点亮；点亮后 `wm dismiss-keyguard` 是否能解除非安全锁屏；时序（是否需要等待 screen on）。
20. `input swipe` 的坐标/方向在 K40 的屏幕分辨率、手势导航/三键导航下是否有效。
21. `input keyevent 82` 的实际效果（预期：不解除；记录是否唤醒/是否无效果）。
22. Shizuku 无线调试启动在 K40 重启后的可用性；是否需要 PC/第二次设备配对；是否与 MIUI 开发者选项限制冲突。

### 4.6 P7（无障碍）

23. 锁屏时无障碍服务是否仍连接（服务实例/连接日志）；`onAccessibilityEvent` 是否仍回调（哪些事件类型）。
24. `getWindows()` 返回的窗口数量、类型、标题；是否只有 SystemUI/Keyguard；目标 App 的窗口是否完全不可见。
25. 是否存在 MIUI 在息屏/省电时禁用无障碍服务的现象；HyperOS 的 force-stop 问题在 K40 MIUI 14 上是否存在（未找到来源，需实测）。
26. 锁屏时对 SystemUI 节点执行点击/滑动是否有效；能否通过 SystemUI 间接解锁非安全锁屏（这是“操作 SystemUI”而非“操作目标 App”，能力上另说）。

### 4.7 P8/P9（DPM/遗留 API）

27. K40 能否成为 device owner（是否需要恢复出厂/清账号）；MIUI 是否允许；`setKeyguardDisabled(true)` 对 Swipe 锁屏的实际效果；重启后是否保持。
28. `exitKeyguardSecurely` / `KeyguardLock.disableKeyguard()` 在 K40 Android 13 上是否仍能解除非安全锁屏；回调/异常；息屏时是否只是排队。
29. `createConfirmDeviceCredentialIntent` 在无安全锁屏时是否返回 null（预期是），有安全锁屏时是否弹凭证。

### 4.8 其他

30. MIUI 的“锁屏显示”权限与 Android 13 通知权限的交互：关闭通知权限时 FSI 是否完全不可用；`POST_NOTIFICATIONS` 被拒后的行为。
31. K40 若升级到 Android 14/HyperOS：重新验证 `USE_FULL_SCREEN_INTENT` 的 targetSdk 限制、`canUseFullScreenIntent()`、`TURN_SCREEN_ON` 权限。当前 K40 是 Android 13，但这是未来边界。

---

## 5. 明确「未找到证据 / 未验证」的项

1. **未找到官方说法**：锁屏时系统暂停/解绑所有 AccessibilityService。只找到 AOSP 系统级的 `temporaryEnableAccessibilityStateUntilKeyguardRemoved`（系统权限），不能据此推断“系统暂停无障碍”。
2. **未找到 K40 / MIUI 14 的专门实测报告**：`requestDismissKeyguard`、`setShowWhenLocked`、`setTurnScreenOn` 在该机型上的实际行为；现有 MIUI 证据是 MIUI 12–14 / Android 11–13 的 issue/社区帖，不特指 K40。
3. **未找到来源**：MIUI 上 `wm dismiss-keyguard` 的专门失效/差异报告；只能确认 AOSP 命令存在、shell 有权限，MIUI 是否保留/是否额外限制未知。
4. **未找到来源**：MIUI 的“锁屏显示”权限能否通过 adb/Shizuku 程序化授予。Stack Overflow 回答称“没有编程方式”，但未在 K40 验证；不要把它写成已确认能力。
5. **未找到来源**：MIUI 14 上 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 是否会被电池/后台策略拦截；只有 Stack Overflow 小米 FSI 帖中的一个成功报告。
6. **未验证**：`exitKeyguardSecurely` / `KeyguardLock.disableKeyguard()` 在 Android 13 / MIUI 上是否仍能无交互解除非安全锁屏。AOSP 13 仍保留代码路径，但官方已废弃，且没有 K40 证据。
7. **未验证**：device owner 的 `setKeyguardDisabled` 在 K40/MIUI 上是否可用、是否需要恢复出厂、是否与小米账号/查找手机冲突。
8. **未验证**：`START_ACTIVITIES_FROM_BACKGROUND` 对普通第三方应用是否可获取（本调查未核实其 protection level；不把它当作可用路径）。
9. **未验证**：K40 上 FSI 的 BAL 豁免是否在 MIUI 的通知栈实现中被完整保留；AOSP 语义与 MIUI 实现可能不同。
10. **未验证**：K40 上无障碍 `getWindows()` 的具体窗口列表、事件回调类型、以及 MIUI 是否在息屏时冻结无障碍。issue #1035 是 Pixel 设备，不是 K40。
11. **未验证**：HyperOS 的 force-stop/无障碍授权移除问题是否也存在于 K40 的 MIUI 14（现有 hyperos-accessibility-fix 证据是 A14+）。
12. **未验证**：Shizuku 官方 FAQ 的 workaround 在 K40 + MIUI 14 上是否长期有效；issue #1426 中有用户按方案成功后再次失效的报告。

---

## 6. 来源清单

### 6.1 官方文档（developer.android.com）

1. `KeyguardManager`（`requestDismissKeyguard`、`isKeyguardSecure`、`isKeyguardLocked`、`createConfirmDeviceCredentialIntent`、`exitKeyguardSecurely`）：
   <https://developer.android.com/reference/android/app/KeyguardManager>
2. `Activity.setShowWhenLocked(boolean)` / `setTurnScreenOn(boolean)`：
   <https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean)>
   <https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean)>
3. 后台 Activity 启动限制 / Activity security：
   <https://developer.android.com/guide/components/activities/background-starts?hl=en>
4. `Notification.Builder.setFullScreenIntent`：
   <https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,%20boolean)>
5. Android 14 行为变更（Secure full-screen Intent notifications）：
   <https://developer.android.com/about/versions/14/behavior-changes-14>
6. `NotificationManager.canUseFullScreenIntent()`：
   <https://developer.android.com/reference/android/app/NotificationManager#canUseFullScreenIntent()>
7. `PowerManager`（`SCREEN_BRIGHT_WAKE_LOCK`、`ACQUIRE_CAUSES_WAKEUP`、`newWakeLock`）：
   <https://developer.android.com/reference/android/os/PowerManager>
8. `DevicePolicyManager.setKeyguardDisabled` / `setKeyguardDisabledFeatures`：
   <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setKeyguardDisabled(android.content.ComponentName,%20boolean)>
   <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setKeyguardDisabledFeatures(android.content.ComponentName,%20int)>
9. `Manifest.permission`（`USE_FULL_SCREEN_INTENT`、`DISABLE_KEYGUARD`、`SYSTEM_ALERT_WINDOW`）：
   <https://developer.android.com/reference/android/Manifest.permission>
10. `KeyEvent`（`KEYCODE_WAKEUP`、`KEYCODE_MENU`）：
    <https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_WAKEUP>
    <https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_MENU>

### 6.2 AOSP 源码（tag `android-13.0.0_r1`，aosp-mirror）

1. `WindowManagerShellCommand.java`（`wm dismiss-keyguard`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/WindowManagerShellCommand.java>
2. `WindowManagerService.java`（`dismissKeyguard` 权限、`exitKeyguardSecurely`、`disableKeyguard`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/WindowManagerService.java>
3. `KeyguardController.java`（调用链、`visibleIgnoringKeyguard`、`wakeUp("dismissKeyguard")`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/KeyguardController.java>
4. `ActivityClientController.java`（`dismissKeyguard`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/ActivityClientController.java>
5. `ActivityRecord.java`（`canTurnScreenOn` / `wakeUp("turnScreenOnFlag")`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/ActivityRecord.java>
6. `PhoneWindowManager.java`（`dismissKeyguardLw`、`KEYCODE_WAKEUP`、`KEYCODE_MENU`、`exitKeyguardSecurely`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java>
7. `KeyguardViewMediator.java`（`handleDismiss`、`dismissAndCollapse`、`handleVerifyUnlock`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java>
8. `StatusBarKeyguardViewManager.java`（dozing 时 `mPendingWakeupAction`）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarKeyguardViewManager.java>
9. `CentralSurfacesImpl.java`（`dismissKeyguardThenExecute` 的 wake-and-unlock 条件）：
   <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java>
10. `PowerManagerService.java`（`ACQUIRE_CAUSES_WAKEUP` 的 app-op 检查）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java>
11. `AppOpsManager.java`（`OP_TURN_SCREEN_ON` 默认 `MODE_ALLOWED`）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/core/java/android/app/AppOpsManager.java>
12. `InputShellCommand.java`（`input` 的 `injectInputEvent`）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/input/InputShellCommand.java>
13. `InputManagerService.java`（`INJECT_EVENTS` 权限）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/input/InputManagerService.java>
14. `packages/Shell/AndroidManifest.xml`（`CONTROL_KEYGUARD`、`INJECT_EVENTS`）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/Shell/AndroidManifest.xml>
15. `core/res/AndroidManifest.xml`（`CONTROL_KEYGUARD` 为 signature）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/core/res/AndroidManifest.xml>
16. `AccessibilityManagerService.java`（`temporaryEnableAccessibilityStateUntilKeyguardRemoved`）：
    <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java>

### 6.3 issue / 社区 / 厂商项目

1. ouroboros #1035（锁屏时 Accessibility 只看到 SystemUI）：
   <https://github.com/razzant/ouroboros/issues/1035>
2. Shizuku #1426（小米息屏后 Shizuku 停止）：
   <https://github.com/RikkaApps/Shizuku/issues/1426>
3. Shizuku #293（息屏后被杀，官方 FAQ 链接）：
   <https://github.com/RikkaApps/Shizuku/issues/293>
4. Shizuku 官方用户手册（“Shizuku randomly stops” FAQ）：
   <https://shizuku.rikka.app/guide/setup/>
5. Shizuku README（ADB 权限有限、shell/root 进程模型）：
   <https://github.com/RikkaApps/Shizuku>
6. `flutter_callkit_incoming` #667（MIUI 12–14 / Android 11–13：showWhenLocked/turnScreenOn/requestDismissKeyguard 被“锁屏显示”权限拦截）：
   <https://github.com/hiennguyen92/flutter_callkit_incoming/issues/667>
7. Stack Overflow（小米 full-screen intent + “Show on lock screen” + wake lock workaround）：
   <https://stackoverflow.com/questions/75662575/fullscreenintent-not-working-on-xiaomi-devices>
8. Stack Overflow（旧 MIUI FLAG_DISMISS_KEYGUARD 不弹解锁，2018）：
   <https://stackoverflow.com/questions/49086391/flag-dismiss-keyguard-not-working-on-xiaomi-phone>
9. awesome-adb（`input keyevent 224` 亮屏、`input swipe` 非安全锁屏解锁）：
   <https://github.com/lstarboy/awesome-adb/blob/master/README.en.md>
10. auto-mobile PR #10354（CI 中 `wm dismiss-keyguard` + `input keyevent 82` 的 emulator 用法；仅说明命令存在，不是 K40 证据）：
    <https://github.com/kaeawc/auto-mobile/pull/10354>
11. HyperOS 无障碍修复项目（A14+ force-stop 导致无障碍授权移除；不是 K40/MIUI 14 证据）：
    <https://github.com/chkndrp/hyperos-accessibility-fix>
12. 9to5Google（Android 15 Beta 1 TalkBack 锁屏指纹提示；说明无障碍服务锁屏仍活跃，但不是 K40 证据）：
    <https://9to5google.com/2024/04/15/fingerprint-talkback-android-15/>

---

## 7. 一句话边界

在 K40 / Android 13 / MIUI 上，**官方 API 层面存在“FSI/SAW → Activity(showWhenLocked+turnScreenOn) → requestDismissKeyguard”的无 PC、无 Shizuku 闭环，且非安全锁屏下官方承诺无交互立即解除**；但 MIUI 的“锁屏显示”权限、FSI 亮屏差异和 Shizuku 息屏被杀使“生产可用”仍未被证实。**`wm dismiss-keyguard`、`input keyevent 224/82/swipe` 都依赖 shell/Shizuku，且 `wm dismiss-keyguard` 在息屏时只排队、不点亮屏幕；无障碍无法绕过锁屏操作目标 App；安全锁屏下没有任何已核实路径可无凭证自动解除。**
