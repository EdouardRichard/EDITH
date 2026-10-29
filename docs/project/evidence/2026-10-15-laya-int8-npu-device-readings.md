# 微调后 Laya 的 INT8 / NPU 真机读数（K70 Ultra + K40），2026-10-15

- **会话**：Laya 微调线（降延迟方向）
- **设备**：K70 Ultra `PBR8U48PWKNREUMR`（MT6989 / APU 790，Android 16）；K40 `2696813b`（骁龙 870 / Adreno 650，MIUI V816）
- **图**：全部量化自**微调后**的 fp32 图（bnd seed20261014），不是官方基座图
- **未做**：AOT 编译（WSL2 现已可用但未执行）；int16 激活真机读数（宿主 Kotlin API 无 int16 读写）

## 1. 源图是怎么来的（可复算）

```
微调 checkpoint  E:\layaft\runs\edith-gate-full-bnd-seed20261014
       ↓  patch_fp32_graph.py（原地改写 99 个 fp32 常量；基座往返 99/99 定位成功）
E:\layaft\int8\gate_bnd_14_fp32.tflite      500,970,372 B
       ↓  quantize_gate_int8.py（ai-edge-quantizer 0.10.0，校准=官方 201 行 fixture）
E:\layaft\int8\gate_bnd_14_<recipe>.tflite
```

- 直方校验：`gate_bnd_14_fp32.tflite` 的 99 个常量与**微调折叠**逐位相同（`verify_fp32_patch.py`）。
- 交付件图 `laya_ml_s256_embeds_wfp16.tflite` 的 SHA-256 = `f19f70f6309264f93d337ca68d385a3b9673238e1e6e083e4d6c339dee8f1771`（主机与设备逐位一致，本轮两次核对）。

## 2. 编译矩阵（K70，同一进程、只换加速器）

| 图 | NPU | GPU | CPU |
|---|---|---|---|
| 微调 wfp16 float | ✅ 1736 ms 编译 | ✅ 3788 ms | ✅ 1091 ms |
| 微调 全 int8（int8 I/O） | ✅ 607–871 ms | ❌ 编译失败 | ✅ 193 ms |
| 微调 weight-only **per-channel** | ❌ 编译失败 | ❌ 失败 | ✅ 1507 ms |
| 微调 weight-only **per-tensor** | ✅ 566 ms | ❌ 失败 | ✅ 571 ms |
| 微调 weight-only **blockwise-32** | ✅ 28.1 s 冷 | — | ✅ 345 ms |
| 微调 dynamic_wi8 | ✅ 377 ms | — | ✅ 227 ms |
| **基座** weight-only per-channel（对照） | ❌ 编译失败 | ❌ 失败 | ✅ |

⇒ **所有带量化权重的图在 GPU 上都编译失败**；GPU 只接受未量化的 wfp16 图。

## 3. NPU 运行读数（K70，201 行 fixture，同一 host 输入构造）

| 图 | 逐行中位 | 输出 | 判决翻转 vs 微调 float |
|---|---:|---|---:|
| **微调 全 int8（权+激活 int8）** | **96.8–112.5 ms** | **有限** | **103/201 = 51%** |
| 微调 wfp16 float（对照） | 241.2 ms | **201/201 行全 +Inf** | 不可比 |
| 微调 weight-only per-tensor | 457.8 ms | 201/201 全非有限 | 不可比 |
| 微调 blockwise-32 | 240.4 ms | 201/201 全非有限 | 不可比 |

`wfp16` 图的逐值证据（本会话自己测的，非引用）：
```
ROW0       token_logits n=256 nan=0 +Inf=256 finite=0
           pooled_cls   n=768 nan=0 +Inf=768 finite=0
ZERO_INPUT token_logits n=256 nan=0 +Inf=256 finite=0      ← 全零输入同样全 Inf
           pooled_cls   n=768 nan=0 +Inf=768 finite=0
```
（"与输入幅度无关"此前为上一会话的结论；本会话已自行复现，故可引用。）

## 4. 量化精度阶梯（宿主侧 201 行，同温度口径）

| 配方 | argmax 翻转 | 中位 \|dprob\| | 体积 |
|---|---:|---:|---:|
| weight-only 权 int8 **per-channel** + fp32 激活 | **3/201 = 1.5%** | 2.5e-03 | 121.7 MiB |
| weight-only **per-tensor** + fp32 激活 | 29/201 = 14.4% | 4.06e-02 | 121.7 MiB |
| int8 权 + **int16 激活**（ai16） | **9/201 = 4.5%** | 6.7e-03 | 121.8 MiB |
| int8 权 + **int8 激活**（ai8） | 79/201 = 39.3% | 2.6e-01 | 121.7 MiB |
| blockwise-32 权 + fp32 激活 | 未测（设备全 Inf，不可比） | — | **477 MiB** |

**读法**：损伤主因是**激活走 int8（+35 点）**，次因是 **per-tensor 粒度（+13 点）**；权重掉到 8 位本身只值 **1.5 点**。

## 5. 已确证的边界（不要再重复试）

1. **`LayaEngine.Storage` 只有 `WFP16` / `FP32`** ⇒ 发布方宿主**不支持 int8 图**（int8 I/O 需要量化/反量化写入路径，宿主没有）。int8 图只能由 `CompiledModel` 直接驱动（`LayaQuantDeviceTest` 的做法）。
2. **per-channel weight-only 在 MT6989 plugin 上编译失败**，报错可复现：
   `MediaTek dispatch API returned contradictory buffer requirements … 1024.0 bytes per element instead of 4`。
   二分结果：**encoder-only 与 attn-only 通过；head-only 失败**；**另一张小图（act head）同配方通过** ⇒ 不是"per-channel 被拒"，是这张图的 head 部分 × per-channel + 显式 DEQUANTIZE 过不了。
3. **ai-edge-quantizer 的 `TensorDataType` 只有 INT/UINT/FLOAT**，没有 FLOAT16；把 activation dtype 设成 `"FLOAT16"` 会**静默跳过**（产出"什么都没量化"的 fp32 图）。⇒ "int8 权 + fp16 激活"在图层不是可导出组合。
4. **int16 激活真机测不了**：Kotlin `TensorBuffer` 只有 int/float/int8/bool/long（javap 对 litert-api 2.3.0 核实），无 int16 读写。
5. **AOT 未做**：WSL2（Ubuntu-24.04）本轮已确认可用，脚本 `aot_compile_mt6989.sh` 就绪但从未执行；AOT 的预期已下调（它改编译路径，不改图的数值范围）。

## 6. 未测 / 不得读成完成

- **int8 图（微调后）在 MT6989 上"是否仍全 Inf"**：本会话用 app 探针尝试时，进程在 `initialize()` 阶段崩溃（CPU 与 NPU 都未打印），**该次读数无效**。有效数据来自 `LayaQuantDeviceTest`（第 3 节表）。
- **权重微调模型从未在任何设备上跑过推理**（七件套只做过文件推送/哈希核对）。
- **纯 FP16（W16A16）图从未做过**：这台机器没有"把已有 tflite 转 fp16"的入口（`TFLiteConverter` 只接受 SavedModel/Keras），且导出链在 Windows 上不存在。
- **未做**：AOT、QAT、混合精度（按算子）、真实语料。

## 7. 复算入口

| 脚本（`E:\layaft\parity\`） | 作用 |
|---|---|
| `patch_fp32_graph.py` | 把 checkpoint 权重原地写进官方 fp32 图（基座往返自证） |
| `quantize_gate_int8.py` | 量化（recipe 名或 recipe.json；`SRC_FP32` / `OUT_NAME` 可覆盖） |
| `eval_int8_vs_float.py` | 宿主 201 行：翻转 / \|dprob\| / KL / per-qtype |
| `compare_graph_constants.py`、`verify_graph_fold.py`、`measure_norm_effect.py` | fp32/LayerNorm 悬案的判定链 |
| `gen_device_spec.py` | 生成设备侧 201 行规格（`device_spec.json`，212 KB） |

真机测试类（`phone-app/src/androidTest/.../laya/`）：`LayaQuantDeviceTest`（201 行 NPU 评分）、`LayaQuantCompileProbe`、`LayaAcceleratorMatrix`。

## 8. 设备现状（交接时）

| | |
|---|---|
| K70 `PBR8U48PWKNREUMR` | `filesDir` = **基座**七件套（2026-10-13）＋ 5 个诊断用图；app 为含 `DebugLayaProbe` 的 debug 构建（该探针在 `initialize()` 处会崩，**建议下一会话移除**） |
| K40 `2696813b` | `filesDir` = **微调**七件套（哈希与交付件一致）；app 为同一 debug 构建；`a11yprobe` 已装回 |
| MIUI 限制（K40） | `edith.phone.test`（androidTest APK）**装不上**：MIUI 的 `AdbInstallActivity` 画完约 30 ms 即 `INSTALL_CANCELED_BY_USER`，用户点不到；主包与普通包可装。⇒ K40 上的验证必须走"主包 + debug receiver"，不能依赖 instrumentation |
| 本会话新增的 debug 钩子 | `phone-app/src/debug/.../DebugLayaProbe.kt` + `src/debug/AndroidManifest.xml` 的 `edith.DEBUG_LAYA` receiver（debug 源集，不进 release） |

