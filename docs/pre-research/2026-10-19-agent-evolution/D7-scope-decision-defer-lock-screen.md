**范围决定记录，未批准；不是 spec，不是验收基线。**

# D7 范围决定：0.25 不解决锁屏（业主 2026-10-19）

## 决定

- **0.25 不做锁屏/息屏的解锁预检**。本版本的运行前提是：**手机已解锁、屏幕亮**。
- 锁屏问题**下个版本**解决；本文件只把调查结果与证据索引记录在案，不改 `DECISIONS.md` / `CONTRACTS.md` / `ACCEPTANCE.md` / `STATUS.md`。
- 因此方向 1（口袋场景优先）的「阶段 0 解锁」从 0.25 移出；0.25 按方向 2 的节奏做 Agent 能力（缺陷修复 → 内核/工具 → Loop/上下文/多模态），但**不改变**已定的其他决策（语料锚点、确认策略、多模态、薄工具、X-OmniClaw 视觉策略）。

## 调查证据索引（均已落盘，未提交）

| 文件 | 结论摘要 | 对下版本的价值 |
|---|---|---|
| `D0a-unlock-history.md` | 历史解锁靠 PC adb/人工；全历史 `isKeyguardShowing=true` 命中 0；生产无解锁代码；无一次 PC-free 手机 UI 测试 | 证明"口袋场景"是零证据的新能力，而非回归 |
| `D0b-unlock-paths.md` | 官方闭环 FSI/SAW→Activity(`showWhenLocked`+`turnScreenOn`)→`requestDismissKeyguard`；MIUI「锁屏显示」门；`wm dismiss-keyguard` 仅 shell 且息屏不亮；Shizuku 息屏会死 | 下版本实现路径与外部边界 |
| `D5-wakelock-spike.md` | `WakeLock` 从 Dozing/面板 OFF 点亮 **PASS**（206 ms）；`setTurnScreenOn`+`setShowWhenLocked` **PASS**（447 ms）；both 195 ms | 点亮半边已验证 |
| `D6-keyguard-spike.md` | `requestDismissKeyguard` 非安全滑动锁屏 **PASS**（700/715/776/712 ms）；MIUI `showWhenLocked` 默认 **FAIL**；SAW+显式 broadcast 后台拉起 **PASS**；通知 FSI **FAIL**；锁屏时 a11y 只见 SystemUI | 解锁半边已验证；生产触发必须用 SAW+显式 `startActivity`，不能用 FSI |

探针源码/APK：`tools/probes/wakelock-unlock-probe/`（一次性，已卸载；根 `settings.gradle.kts` 有 3 行 include）。若下版本不再需要，可连同 include 一起回退；本版本保留为证据与复现材料。

## 0.25 的范围（据此调整）

保留：

1. **P0 缺陷修复**：Chat Completions 工具历史、生产 store 工具轮/观察、`Ambiguous` 不得报 `done`、`Terminal.usage` 计数接缝。
2. **DeviceControl + 薄工具 + 自动观察**：6 工具（device/shell/open/dial/notify_user/load_skill）；富观察、ref/revision/diff/settle；动作后自动观察；应用目录走 shell；确认只覆盖不可逆/花钱。
3. **Loop + 上下文 + 多模态**：4 类 detector、错误追踪、nudge、工具超时；D81 统一预算 + 类型感知省略；图片进历史；档案 IMAGE 门控。
4. **验收语料**：从**已解锁 + 亮屏**状态起跑；跨 App 音乐（M2 复测）、设置、消息、应用目录、视觉兜底、多步组合、歧义负例；M2/AC-13/16 并列复测。
5. **临时保护**：任务期间持有 `FLAG_KEEP_SCREEN_ON`，只防屏幕超时，不做解锁（业主 2026-10-19 确认；下版本替换为正确的解锁方案）。

移出 0.25：

- 解锁预检、WakeLock/`UnlockActivity`/`requestDismissKeyguard` 的生产接线；
- MIUI「锁屏显示」权限、FSI 锁屏路径、Shizuku 息屏 rebind 的产品处理；
- Asleep/拔线/重启后的解锁行为。

## 风险与已知前提（必须在 spec 里写明）

1. **屏幕超时**：业主 2026-10-19 决定：0.25 加一个**任务期间 `FLAG_KEEP_SCREEN_ON`** 的临时保护（只防超时，不涉及解锁）。下版本解决锁屏时，正确方式不是会话常驻解锁，而是**在工具执行时触发解锁并做防误触**；该要求记入下版本。
2. **生产 a11y 未启用**：K40 当前只启用 `edith.phone.a11yprobe`；0.25 验收前必须先启用生产 `EdithAccessibilityService` 并验证绑定。
3. **Shizuku 未运行**：K40 当前只有 manager，`shizuku_server` 是 15:58 外部启动的；`shell` 工具在 Shizuku 不可用时必须不广告/明确降级。
4. **设备测试环境**：当前 K40 `locksettings get-disabled=true`、`stay_on_while_plugged_in=7`、`screen_off_timeout=600000`。0.25 的语料在「已解锁 + 亮屏」前提下设这些值为可接受；但必须在证据里记录，不能把“锁屏被禁用”当成生产默认。

## 下版本 pickup 清单

- 正确处理方式（业主 2026-10-19）：**工具执行时触发解锁 + 防误触**；不是会话常驻解锁，也不是只靠 `FLAG_KEEP_SCREEN_ON`。
- D6 的 A 配方：`WakeLock` + 透明 `UnlockActivity`(`turnScreenOn`/`showWhenLocked`) + `requestDismissKeyguard`；触发用 SAW + 显式 `startActivity`，不用 FSI。
- 需实测：MIUI「锁屏显示」开启后 `showWhenLocked`/FSI 是否恢复；真实用户滑动手势；Asleep/拔线/重启；Shizuku rebind；生产 a11y。
- 待产品决定：是否在 onboarding 要求用户打开 MIUI「锁屏显示」/关闭安全锁屏；是否接受 SAW 作为解锁触发的前提权限。

## 修订

- 2026-10-19：初稿，依据业主「锁屏问题这个版本先不考虑，调查结果记录在案，下个版本再解决」。
