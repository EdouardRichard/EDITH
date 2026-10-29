# EDITH 0.3 Phase 3 → Phase 4 交接

- **交接时间**：2026-10-21
- **分支**：`feat/0.3-harness`
- **最终 HEAD**：`05c36138`（`docs(0.3): align the Phase 3 closeout with the final R-04 and build basis`）
- **代码 HEAD**：`35d9378e`（`fix(proactive): close the wake-final and continuation-busy races`）
- **Phase 3 判词**：**实施与收口完成；不是版本级全部通过。** `Partial` 不是 pass，`NotMeasured` 不是 pass。
- **Phase 4**：spec 已于 2026-10-22 批准（[设计](superpowers/specs/2026-10-22-edith-0.3-phase-4-tool-face-and-permission-design.md)）；**实施计划未批准、实现未启动**。下一会话先读 spec 并写 plan，不要直接实现。
- **未 push / 未 tag / 未 merge。**

## 1. Phase 3 交付与最终读数

### 1.1 构建与门禁

- 最终 JVM 门禁（8 模块 `--rerun-tasks`）：**319 suites / 3474 tests / 0 failures / 0 errors / 2 skipped**（`BUILD SUCCESSFUL in 1m`，100 tasks；2 个 skip 为既有宿主假设）。
- 逐模块：`plugin-api` 10/58；`host-runtime` 19/134；`device-control` 12/134；`agent-runtime` 39/511；`model-runtime` 17/207；`output-runtime` 16/175；`plugin-smart-helper` 88/921；`app` 118/1334（2 skipped）。
- **已安装设备基线**：build `8d075f40`；APK **102,985,202 B**，SHA-256 `c3ff29bb074839968583f3730a833f5af5b6c3be0b30783e9b866d7cab16925e`；`lastUpdateTime=2026-10-21 21:56:29`。
- **最终代码新 APK（只构建、未安装）**：**102,985,418 B**，SHA-256 `2c0ba0e4e57b330534e133f3db47d97579f85e27be24c063ca1cddd581cf3f9d`。
- K40 `2696813b` + 眼镜 `1901092544027662`。设备读数来自已安装 `8d075f40`，除非另注；最终代码 `35d9378e` 的 final-review 修复**没有设备重跑**。

### 1.2 Phase 3 状态总表

| 范围 | 实际状态 | 证据/边界 |
|---|---|---|
| 主动 gate / hint | A-01 `Partial`；A-01b/A-02/A-07 `Verified (Device)` | A-01b/A-02 来自 r3 build `09ccc93d`/APK `583d2b7c…85d1`；r4 在 `8d075f40` 上自然 hint 0/3，未重新建立 |
| 主动 follow-up | A-03..A-06 + panel 设备证明 `NotMeasured` | hint 模型 r4 0/7 沉默，panel 路径未被触发；panel fix 仅 JVM/compile 证 |
| A-08 / A-09 | `Partial` | JVM Verified；WAKE 回归/并发设备侧未重跑或 `NotMeasured` |
| 结果残留 | R-01/R-02、R-06/P-08、per-call images `Verified (JVM+Device)` | R-04 `Partial`（device 仅 R07/R05 `refused` 行，不是 literal failed/uncertain） |
| R-03/R-05/R-07/brightness | `Partial` | R03D 为 test-case/instrument-invalid（`NotMeasured`）；物理亮度 `NotMeasured` |
| M1–M5 | M1/M2/M3/M5 `NotMeasured`；M4 `Partial` | Q3=最简；loop 250 ms/图设备值 `NotMeasured` |
| 0.25 corpus / P-07 | P-07 `NotMeasured`/deferred | Q7=C 未跑；旧 **3 PASS / 15 FAIL / 9 NOT_MEASURED** 原样不变 |
| M6 / M7 | M6 docs-only resolved；M7 JVM Verified | M7 +1 test-only 用例 |

**R03D 特别说明**：R03D-1 是 **test-case/instrument defect，不是 EDITH defect**；`frozenUtteranceMatch=false`（BOM-less wrapper → 3-CJK mojibake，9 字符/`929ed58b…` vs frozen 8 字符/`14321a16…`）。按业主决定**不重跑、不引用 owner 接受**；R03D `NotMeasured`/test-case-invalid，R-03 保持 `Partial`。

### 1.3 Phase 3 代码/文档入口

- [STATUS.md](STATUS.md) — checkpoint、最终读数、停止线
- [ACCEPTANCE.md](ACCEPTANCE.md) — §2.12 实际状态、边界、残留
- [DECISIONS.md](DECISIONS.md) — D188（P3-1..P3-13、Q1–Q8、final-review fix、R6/R7/残留）
- [CONTRACTS.md](CONTRACTS.md) — §2.5 prompt routes、`ToolObservationCapture`/`observeWithImages`、`P3_TOOLRESULT`/`P3_OBSERVE`、shape digest、debug seam、P3-13
- 证据：[r3](evidence/2026-10-21-0.3-phase-3-proactive-r3.md) / [r4](evidence/2026-10-21-0.3-phase-3-proactive-r4.md) / [result residuals](evidence/2026-10-21-0.3-phase-3-result-residuals.md)
- SDD 账本（gitignored）：`.superpowers/sdd/2026-10-21-edith-0.3-phase-3-proactive-and-result-residuals/progress.md`

## 2. Phase 4 范围状态（spec 已批准；plan 未批准）

**Phase 4 spec 已于 2026-10-22 批准；实施计划未批准、实现未启动。** 批准范围 = 0.3 design §11 的工具面/规范/延迟/旧语料 + 权限上限/确认交互/“不拦截”/shell 分类器。0.3 design 的候选条目已由该 spec 收口；不得把未写入 spec 的方向当成批准范围。

**下一会话必须先做**：`writing-plans` → 业主批准计划并选择执行方式 → 再实现。不得顺手改 Phase 3 已收口的行为。

## 3. Phase 4 入口检查清单

1. 读 `AGENTS.md`、`STATUS.md`、本 handoff、0.21 基线架构规格、`DECISIONS.md`、`CONTRACTS.md`、`ACCEPTANCE.md`。
2. 核对 git：分支 `feat/0.3-harness`、HEAD `05c36138`（或其后仅 docs 提交）、工作树干净；确认没有他人未提交改动。
3. 核对设备/构建基线：K40 `2696813b`、眼镜 `1901092544027662`；已安装 `8d075f40`/`c3ff29bb…`；最终代码 `35d9378e` 的新 APK `2c0ba0e4…` 未安装。若 Phase 4 要设备验收，先明确“装哪个 build、是否重跑、证据对应哪个 hash”。
4. 不重做 Phase 3、不改 0.21/0.25/0.26/§2.11 旧判词、不把 `Partial`/`NotMeasured` 读成通过。
5. 不 push/tag/merge；不安装 APK、不启动设备操作，直到业主明确批准 Phase 4 计划并给出设备操作授权。
6. 设备操作规则不变：显式 `adb -s 2696813b`；禁用 `gradlew installDebug`；UI 只用 `edith.phone.a11yprobe`；a11y 读→保留→追加→验证；证据只记计数/id/hash/coverage，不记正文。

## 4. 交接时仍开放的 Phase 3 事项（Phase 4 需决定是否先关）

- **A-03..A-06 + panel 设备证明** `NotMeasured`：r4 hint 模型 0/7 沉默，未进入 panel 路径；若要补设备证据，需要新的、预注册的矩阵与稳定的 hint 触发策略，不能重试旧矩阵。
- **A-01b/A-02** 是 r3 `09ccc93d` carry；最终代码 `35d9378e` 未安装、未设备重跑。若 Phase 4 要“最终 build 的设备证据”，需重新安装/验收并明确 provenance。
- **R-04** `Partial`：device 只有 R07/R05 的 `refused` 行；literal failed/uncertain 未产生。
- **R-03/R-05/R-07/brightness** `Partial`；**M1/M2/M3/M5** `NotMeasured`；**M4** `Partial`。
- **P-07 / 0.25**：Q7=C deferred；旧 3/15/9 不变；若 Phase 4 要重判，需新批准与新证据，不能改写旧读数。
- **已知 narrow residuals**：`P3_PERCEPTION` 无 `route=`；9d `UI_CLOSED` acquire→submit 未复查；P-08 `leftActive` 是 posting-time read；`frameBuilderFor` route 参数未使用；`handleHelp` 不 detach `followupTurn`（窄窗口 busy 残留）。
- **R03D**：test-case-invalid/`NotMeasured`；不要把它读成 EDITH bug 或通过。

## 5. 不要把聊天当交接

本文件是 Phase 3 → Phase 4 的持久交接入口；聊天记录、任务提示词、SDD 临时账本都不作为唯一依据。发现文档与代码/证据不一致时，先记录 drift，再核对代码与原始证据，不互相覆盖。
