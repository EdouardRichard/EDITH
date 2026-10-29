# EmbeddingGemma 真机语义冒烟（K40，task 6b 步骤 ④）

- **日期**：2026-10-14（设备时间 2026-10-13T16:42 UTC = 2026-10-14 00:42 +08）
- **设备**：K40 `2696813b`（`M2012K11AC`、alioth、Android 13 / API 33）——本仓库当前唯一调试与验收载体
- **被测代码**：`phone-app/src/main/kotlin/edith/phone/litert/EmbeddingGemmaLiteRTEngine.kt`（真分词 + 前向，返回原始 768）、
  `phone-app/src/main/kotlin/edith/phone/HostCompositionRoot.kt` 的 `EmbeddingModelDirectory`（路径）
- **测试**：`phone-app/src/androidTest/kotlin/edith/phone/litert/EmbeddingDepthSmokeTest.kt`（4 条）

## 这一步回答的问题

在此之前，**“向量前后是否一致”＝`NotMeasured`**：`embed()` 是 `text.hashCode()` 占位，真图从未被喂过真 token。
本文件是第一次把真 token 喂进真图，并把读数写下来。

## 命令与结果

```
JAVA_HOME=D:\Tools\JDK；ANDROID_SERIAL=2696813b
.\gradlew.bat :phone-app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=edith.phone.litert.EmbeddingDepthSmokeTest" --console=plain
```

- `EXIT=0`，`BUILD SUCCESSFUL in 1m 39s`（`.local/t6b2-device2.log`）
- `Starting 4 tests on M2012K11AC - 13` / `Finished 4 tests on M2012K11AC - 13`
- JUnit XML：`phone-app/build/outputs/androidTest-results/connected/debug/TEST-M2012K11AC - 13-_phone-app-.xml`
  → **`tests="4" failures="0" errors="0" skipped="0"`**（`skipped=0` 证明 4 条真跑了，没有被 gitignored 模型的 `assumeTrue` 跳过）

## 实测读数（原始，来自设备 logcat，未做任何换算）

| 读数 | 值 | 来源 |
|---|---|---|
| `SMOKE_COSINE paraphrase`（“我不吃香菜” vs “我不喜欢香菜”） | **0.8586166** | `logcat-…-aParaphraseIsCloserThanAnUnrelatedSentence.txt` |
| `SMOKE_COSINE unrelated`（“我不吃香菜” vs “明天下午三点开会”） | **0.37010357** | 同上 |
| margin | **0.48851302** | 同上 |
| `SMOKE_VECTOR` “我不吃香菜” | size=256 norm=**1.0000001** | `logcat-…-everyEmbeddingIs256DimensionalUnitNormAndFreeOfNaN.txt` |
| `SMOKE_VECTOR` “我不喜欢香菜” | size=256 norm=**1.0** | 同上 |
| `SMOKE_VECTOR` “明天下午三点开会” | size=256 norm=**1.0000001** | 同上 |
| `SMOKE_MODEL_INFO` | `embeddinggemma` … dimensions=**256** | 同上 |
| `SMOKE_DETERMINISM`（同句两次） | maxAbsDiff=**0.0**、cosine=**1.0** | `logcat-…-theSameTextTwiceProducesTheSameVector.txt` |
| `SMOKE_RAW768_NORM`（engine 原始输出） | **0.99999994** | `logcat-…-theEngineReturnsTheGraphsOwn768Floats.txt` |
| `SMOKE_TRUNC256_NORM`（同一向量前 256 维） | **0.62863094** | 同上 |

无 NaN、无全零；四条测试全部断言通过，且断言的是行为而不是“跑通了”：
`near > far`、`|norm-1| ≤ 1e-3`、`maxAbsDiff ≤ 1e-4`、`|rawNorm-1| ≤ 2e-2`。

## 结论（只写这份证据支持的部分）

1. **向量确实带语义**：改写句（0.8586）比无关句（0.3701）近 **0.4885**，不是噪声级差距。
   `text.hashCode()` 占位实现同样满足“256 维、单位范数、无 NaN”，所以**只有这一条**能区分两者。
2. **向量前后一致**：同一句两次，逐元素最大差 **0.0**、余弦 **1.0**。之前记为 `NotMeasured` 的这一问题，现在有读数。
   （断言用的是 1e-4 容差而不是位相等：XNNPACK 多线程归约顺序不保证位级可复现，见 `LiteRTPerformanceTest.runUntilSettled`；本次实测恰好为 0.0。）
3. **分层按裁决成立**：engine 返回 **768**（norm 0.99999994），service 截 256 后重新 L2 → **1.0000001**。
   截断不归一的话余弦会被 **0.6286** 这个系数带偏——与 Phase A 记录的 0.643 同一现象（不同输入，两个数不可互相替代）。
4. **B5 的“语义检索”不再是 `NotMeasured`**：模型侧已实测；**但**“记忆检索排序在真机上按语义命中”仍未跑（见下）。

## 未测项（不得读成通过）

- **只测了 embedding 模型本身**：插件回合、`MemoryModule` 检索、`SemanticRetriever` 的排序**都没有在设备上跑过**。
  本文件证明的是“向量有语义且可复现”，不是“记忆检索在真机上按语义工作”。
- **只有 K40、只有三句中文**；无英文/长文本/跨语言对照。
- **延迟未测且已知不达标**：Phase A 记录 embedding 前向 ~584 ms（门线 150 ms），本文件未重新测延迟。
- **provisioning 仍是手工 adb 推送**（`getExternalFilesDir(null)/models/embeddinggemma/`，179,131,736 B + 4,683,319 B），
  仓库内没有任何代码下载/导入这两个文件；外部存储未挂载时 `EmbeddingModelDirectory.locate` 指向一个**不可能存在**的路径
  ⇒ 服务 `isAvailable=false` ⇒ 按 B9 硬依赖**显式拒绝**（而不是拿别处的目录当模型用）。
- **APK 身份**：调试构建，未记录 APK 哈希，也未做发布包（release）验证。

## 复现该次的产物清单

- `.local/t6b2-device2.log`（Gradle 输出，`EXIT=0`）
- `phone-app/build/outputs/androidTest-results/connected/debug/` 下的 XML 与 4 份 `logcat-*.txt`
- 单测（JVM）读数：`.local/t6b2-red1.log`（RED：2 条驱动失败）、`.local/t6b2-red2.log`（RED：`Unresolved reference`）、
  `.local/t6b2-green1.log`、`.local/t6b2-c4red.log`、`.local/t6b2-c4green.log`
