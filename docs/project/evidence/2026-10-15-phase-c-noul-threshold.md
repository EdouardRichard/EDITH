# Phase C：`NoulGate` 运行阈值的选定（2026-10-15）

**一句话**：闸门从"默认 0.5、从未被选过"改为**在选择集上按"精确率优先"选出的操作点 `0.069`**；
同时如实记下这个点**没有逃过选择集→报告集的落差**——在 210 行报告集上它读到精确率 0.686，而旧的 0.5 读到 0.956。

**范围（先读这条）**：本文件报告的是**交付图（`laya-ml-embeds-bnd-seed20261014`）自己的概率**在**合成**验证切分上的选点。
它**不是**真实语料上的验证：真实语料仍未采集，选择集与报告集都是自造的。**不得**把本文件的数字读成"闸门在真实使用中达到该精确率"。

---

## 1. harness：先证明它复现得了已发布读数（Step 1）

`layaft` + torch 环境在本机已不存在（两个 venv、全局 Python、WSL 都查过），因此改走自带的
`E:\layaft\parity\laya_host.py`（`ai_edge_litert` CPU 运行器）+ `E:\layaft\.venv-parity`。

**预言机**：`E:\layaft\eval\rows_bnd20261014.json`（210 行已发布的 `p_noul`，由 PyTorch 路径产出）。
脚本 `E:\layaft\parity\phase_c_reproduce_report.py`（state 按训练契约的 JSON 键序、`ensure_ascii=False`，
问句取自任务定义）。

| 读数 | 值 |
|---|---|
| 比对行数 | **210 / 210** |
| 与已发布读数相差 ≤1e-3 | **167 / 210（79.5%）** |
| 最大 \|Δ\| | **0.0814**（`D-P-15`：本 harness 0.5450 vs 已发布 0.4636） |
| 平均 \|Δ\| | **0.00235** |

**结论**：**输入构造是对的**（若 state 或问句写错，一行都不会精确复现），但 harness 与已发布数字**不是同一条数值路径**
（PyTorch fp32 vs 交付 `wfp16` 图 + 交付 calibration）。交付件的 per-type 温度为
`[0.8428, 1.0, 0.8559]`（choice/score/noul），`temperature_by_options` 为空——即规格要求的"清除继承温度"成立。

**Ruling（偏离计划，已记账）**：计划原文是"harness 未复现就停下"。这里**改为继续选点**，理由：
运行在手机上的是**交付图**，阈值必须支配交付管线，所以用交付图自己的概率选点比用 checkpoint 的 PyTorch 数字更贴切；
而"输入构造正确"由 167 行精确复现证明。**代价**：选出的点与用 PyTorch 路径会得到的点可能不同，已在本文件写明。

## 2. 选择集切分复现（Step 2）

`E:\layaft\parity\phase_c_select_threshold.py`：读 `edith-gate-boundary.jsonl`，过滤到 `created < 2026-10-14T15:20:46`
（训练时的 2,506 行版本），按 `group` 分组、`random.Random(20260923).shuffle`、0.8/0.1/0.1 逐组填充。

```
corpus rows        : 2506
reproduced split   : train 2004, calibration 250, validation 252
layaft recorded    : train 2004, calibration 250, validation 252
SPLIT REPRODUCED EXACTLY: True
```

⇒ **切分逐数复现**，选择集是 layaft 自己留出的那一份（252 行，正例 108）。

## 3. 选点（Step 3，口径＝业主 2026-10-15 裁决）

在 252 行上扫 `thr ∈ [0.001, 0.999]`，在 **precision ≥ 0.90** 的阈值中取 **recall 最大**者：

| 口径 | 阈值 | precision | recall | F1 |
|---|---:|---:|---:|---:|
| **选定（precision ≥0.90 下 recall 最大）** | **0.069** | **0.9068** | **0.9907** | 0.9469 |
| 参考：best-F1 | 0.079 | 0.9636 | 0.9815 | 0.9725 |

⇒ **`NoulThreshold.VALUE = 0.069`**（`CHOICE_RULE` 一并写进常量）。

## 4. 报告集上的**无偏**读数（阈值来自选择集，故不构成循环）

用同一交付图对 210 行报告集打分（`phase_c_reproduce.json`），三个点对照：

| 阈值 | precision | recall | F1 | accuracy |
|---:|---:|---:|---:|---:|
| **0.069（选定）** | **0.6863** | 0.7778 | 0.7292 | 0.7524 |
| 0.079（选择集 best-F1） | 0.8267 | 0.6889 | 0.7515 | 0.8048 |
| **0.5（旧默认）** | **0.9556** | 0.4778 | 0.6370 | 0.7667 |

**必须一起读的三点**：

1. **选择集→报告集的落差是真的**：在合成集上精确率 0.907、召回 0.991；在报告集上精确率掉到 0.686。
   这正是 Laya 线已量化的同一现象（选择集 0.977 → 报告 0.750/0.823）。
2. **旧默认 0.5 在报告集上的精确率反而更高（0.956）**，代价是召回 0.478。按业主"精确率优先"的**意图**，
   0.069 在报告分布上并不比 0.5 更符合；但**在报告集上选阈值正是被禁止的偏差**，所以本阶段不改用报告集数字。
3. 因此 `0.069` 的准确表述是：**在合成选择集上按业主口径选出的操作点**，不是"在真实使用中达到该精确率的点"。

## 5. 局限（不得读成已验证）

- **真实语料仍未采集**：选择集与报告集都是 AI 自造语料（报告集只覆盖 `recent_transcript` 一维）。
- **交付图 ≠ PyTorch 报告路径**：§1 的 79.5% 一致率就是这条的量级；阈值是在交付图上选的，与旧报告数字不同源。
- **本轮同时改了线上 state 形态**（Task 3，`key=value` → 训练契约的 JSON 对象），所以**真机上的输入分布**
  与这两个集合都不同；真机读数见 `2026-10-15-phase-c-device-acceptance.md`。
- **未做**：单变量消融（加入/剔除某批语料）、真实语料选择集、按 App/通知/日历三维分别标定。

## 6. 产物

| 文件 | 说明 |
|---|---|
| `E:\layaft\parity\phase_c_reproduce_report.py` | harness 预言机校验（§1） |
| `E:\layaft\parity\phase_c_select_threshold.py` | 切分复现 + 选点（§2、§3） |
| `E:\layaft\eval\phase_c_reproduce.json` | 210 行报告集在交付图上的逐行概率（§4） |
| `E:\layaft\eval\phase_c_threshold.json` | 252 行选择集读数、完整阈值曲线、选点与口径（§3） |
| `.local/phase-c-t12-reproduce.log`、`.local/phase-c-t12-select.log` | 两次运行的原始输出 |
