# ASR 复读根因定位与修复方案（独立验证，未改 EDITH 代码）

> 一次性独立诊断记录。测试 APK、模型副本、音频、JSON、日志等临时产物已全部删除，本文件是唯一保留物。
> **本轮没有修改 EDITH 的任何代码 / 配置 / 模型清单**；第 5 节给出“不改 EDITH 代码”的修复方案。
> 测试语料是设备 TTS 合成的固定文本，不含用户数据。

## 1. 问题与约束

- 现象（`STATUS.md` 已记录）：正常语速下同一句话反复解码出重复字/词（“今天天天天天天…”、“播放周杰伦的稻香”复读），
  慢速说话可得到干净转写；此前有一次较干净样本被误读为通过。
- 本轮要求：**不经过 EDITH**（不运行 EDITH App、不复用其代码），独立复现并定位；只输出修复方案，不动 EDITH 代码。
- 方法：与 EDITH 使用**同一份钉住的** `sherpa-onnx-1.13.8.aar`（SHA-256 `633C2432…3BD96`）与同一套模型文件，
  在 K40（Android 13 / API 33）上用一个一次性独立 APK（`com.sheen.rokid.asrrep`，已卸载）做受控实验；
  语料是小米 TTS 合成的固定中文句子（16 kHz 单声道 PCM16）。

## 2. 实验设计

### 2.1 语料（设备 TTS，8 条）

| 文件 | rate | 音频 s | 文本 |
|---|---:|---:|---|
| dev-s1 | 1.0 | 1.080 | 今天天气怎么样 |
| dev-s2 | 1.0 | 1.584 | 播放周杰伦的稻香 |
| dev-s3 | 1.0 | 2.232 | 帮我把屏幕亮度调到最高 |
| dev-s4 | 1.0 | 2.016 | 打开设置然后回到桌面 |
| dev-s5 | 1.0 | 2.268 | 明天北京天气怎么样需要带伞吗 |
| dev-s6 | 1.0 | 2.520 | 请帮我订一个明天上午十点的闹钟 |
| dev-s1-slow | 0.1 | 1.224 | 今天天气怎么样 |
| dev-s1-fast | 5.0 | 0.756 | 今天天气怎么样 |

### 2.2 变量

1. **量化组合**（同一模型、同一 greedy 配置）：
   - `int8` = 当前 EDITH bundle（int8 encoder + int8 decoder + int8 joiner）
   - `dec` = int8 encoder + **fp32 decoder** + int8 joiner
   - `joi` = int8 encoder + int8 decoder + **fp32 joiner**
   - `hybrid` = int8 encoder + fp32 decoder + fp32 joiner
   - `fp32` = 全 fp32
2. **解码策略**（int8）：`greedy_search`、`modified_beam_search`（maxActivePaths 4/8）、
   `blankPenalty=+1/-1`、实时节奏喂帧（20 ms/块）、首尾静音裁剪。
3. **其他模型版本**：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`（中文 WenetSpeech 14M，int8/fp32）、
   `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`（int8）。

每个 (配置, WAV) 预热 1 次 + 测量 3 次；喂帧为 320 样本/20 ms 块；统计 **最大同字连跑长度（maxRun）**、
**重复 trigram 数（dupTrigrams）**、**相对固定文本的字错误率（CER）**、**RTF（decode 时间/音频时长）**。

## 3. 结果（全部为设备实测，每格 24 次解码：8 条 × 3 次）

| 模型组合 | 解码 | dupTrigrams 中位 | maxRun 中位 | CER 中位 | RTF 中位 | 模型加载 ms |
|---|---|---:|---:|---:|---:|---:|
| **int8（当前 EDITH）** | greedy | 1 | 4 | 1.00 | 0.070 | 848 |
| int8 | greedy-realtime | 1 | 4 | 1.00 | 0.126 | 624 |
| int8 | greedy-trim | 1 | 4 | 1.00 | 0.076 | 616 |
| int8 | greedy + blankPenalty=+1 | 1 | 4 | 1.00 | 0.072 | 611 |
| int8 | greedy + blankPenalty=-1 | 1 | 4 | 1.00 | 0.075 | 603 |
| int8 | modified_beam_search(4) | 1 | 4 | 0.90 | 0.084 | 610 |
| int8 | modified_beam_search(8) | 1 | 4 | 0.82 | 0.094 | 610 |
| **dec（fp32 decoder）** | greedy | **0** | **1** | **0.43** | **0.074** | 827 |
| joi（fp32 joiner） | greedy | 0 | 2 | 0.73 | 0.081 | 810 |
| hybrid（fp32 dec+joi） | greedy | 0 | 1 | 0.43 | 0.077 | 899 |
| fp32 全量 | greedy | 0 | 1 | 0.43 | 0.113 | 1,216 |
| fp32 全量 | beam4 | 0 | 1 | 0.43 | 0.124 | 1,164 |
| alt zh-14M int8 | greedy | 0 | 1 | 0.70 | 0.026 | 403 |
| alt zh-14M fp32 | greedy | 0 | 1 | 0.86 | 0.035 | 570 |
| alt small-bilingual int8 | greedy | 0 | 1 | 0.86 | 0.035 | 552 |

### 3.1 关键对照（同一 WAV 的实测文本）

| WAV | int8（当前） | fp32 decoder（只换 decoder） |
|---|---|---|
| dev-s1 今天天气怎么样 | 优先天天天天天气是什么 | 天气怎么 |
| dev-s3 帮我把屏幕亮度调到最高 | 把屏幕亮亮亮亮度调调 | 把屏幕亮度调 |
| dev-s5 明天北京天气怎么样需要带伞吗 | 明天明天天天天天动北北京天天气什么要需要 | 明天北京天气怎么样需要 |
| dev-s6 请帮我订一个明天上午十点的闹钟 | 请帮我听听听听你一个明天下午十点的 | 请帮我听一个明天下午十点的 |

int8 的重复是**确定性**的（同一 WAV 的 3 次运行文本完全相同），换实时喂帧、裁静音、blankPenalty、
beam4/beam8 都不能消除；例如 int8+beam4 的 dev-s1 仍为 `时间人天津天天天是吃什么`。

### 3.2 隔离结论

- **只把 decoder 换成 fp32**（encoder 仍 int8、joiner 仍 int8）：重复消失（dupTri 中位 0、maxRun 中位 1），
  CER 中位从 1.00 降到 0.43，RTF 从 0.070 升到 0.074（+5.7%）。
- **只把 joiner 换成 fp32、decoder 仍 int8**：仍然复读（dev-s6 `请你妈妈我听听听听你一个明天下午十点的`，
  dev-s1-fast `今天天天天天气`），CER 中位 0.73。
- **encoder 保持 int8 没有问题**：`dec` 组合（int8 encoder + fp32 decoder + int8 joiner）与全 fp32 的复读/CER 指标一致。
- **`modified_beam_search` 不是修复**：int8 上 beam4/beam8 仍复读（maxRun 中位 4）。因此未合并分支
  `fix/asr-beam-search`（只把 `decodingMethod` 改为 `modified_beam_search`）**不能解决本问题**，不应作为修复合并。
- **实时节奏、静音裁剪、blank penalty（±1）均无关**：各配置的复读指标与 greedy 相同。
- **其他模型版本不能作为 drop-in 替代**：zh-14M 虽不复读，但在这批中文指令上 CER 0.70–0.86（明显变差）；
  small-bilingual-2023-02-16 输出大量截断词（CER 0.80–0.86），需要另外的 chunk 配置，不属于本问题的最小修复。

## 4. 根因

**当前 EDITH bundle 的 int8 量化 decoder（`decoder-epoch-99-avg-1.int8.onnx`）是复读的直接原因。**
int8 encoder 与 int8 joiner 均不是充分原因（只换 decoder 即修复；只换 joiner 不修复）。
机制上可解释为：decoder 隐藏状态在 int8 量化后偏移，使 joiner 在若干帧持续偏好同一非 blank token，
greedy 解码就形成确定性字级循环；这类循环不会因“更好的搜索策略”消失（beam/blank penalty 实测无效），
也不受喂帧节奏/静音影响。

## 5. 修复方案（不改 EDITH 代码）

### 5.1 推荐方案：只替换 bundle 里的 decoder 为 fp32（数据/模型清单变更，零 Kotlin 代码变更）

- 文件：用 `decoder-epoch-99-avg-1.onnx`（fp32，**13,876,452 B**，SHA-256
  `2E3B5EC371F8899EE6ACD829FD753BA45772DF57A91BDF37CDE3136354E7DB7D`）替换
  `decoder-epoch-99-avg-1.int8.onnx`（13,091,040 B）；encoder/joiner/VAD/speaker 不动。
- 来源：与现有模型同仓库同 revision 的 fp32 版
  `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/98590b7ed6443e77b714204da2757d75e1a642f4/decoder-epoch-99-avg-1.onnx`
  （Apache-2.0，model card 不变）。
- 需要改的是**数据**：`tools/scripts/p2/engine-manifest.json` 中 `decoder` 项的 `path` / `bytes` / `sha256` /
  `sourceUrl`（`revision` 与 `licensePath`/`declarationPath` 不变），然后重新构建 APK 资产并重新 provision 设备
  `no_backup/engine-models` bundle（模型不随 APK 打包，见 `NOTICE.md`）。
- `EngineManifest.validate()` 的 6 角色/大小/64-hex/HTTPS/license 约束全部满足；`ValidatedModelBundle` 只是按清单
  校验大小与 SHA-256，**不需要动 `SherpaNativeConfiguration` 或任何 Kotlin 文件**；`decodingMethod` 继续用
  `greedy_search`（本项目实测 greedy+fp32 decoder 已无复读）。
- 代价（设备实测）：bundle **+785,412 B（+0.77 MB）**；RTF 中位 0.070 → 0.074（+5.7%，仍约 13× 实时）；
  模型加载 848 → 827 ms（噪声范围内）。内存增量与文件增量同量级，可忽略。
- 收益：复读消失（dupTri 中位 0、maxRun 中位 1），固定语料 CER 中位 1.00 → 0.43。

### 5.2 若 must 保留全 int8（备选）

- 只换 joiner 无效；`beam_search`/blank penalty 无效。
- 可行备选是重新量化 decoder（per-channel / 更好校准集）或改用 fp16 decoder（需运行支持），这属于模型侧工作，
  成本高于 5.1，且需要重新验证；不建议作为第一选择。

### 5.3 明确不建议

- **不要**把 `fix/asr-beam-search`（`decodingMethod=modified_beam_search`）当成复读修复——本机实测 int8 上
  beam4/beam8 仍复读（maxRun 中位 4），它改变文本但不消除循环。
- 不要换 zh-14M / small-bilingual 作为“顺手修复”：复读虽没了，但识别质量明显下降（CER 0.70–0.86）。

### 5.4 后续验证（需要 EDITH 侧执行时才动代码/产物）

1. 生成 fp32-decoder bundle，按 5.1 更新清单数据并 provision 到 K40；
2. 设备 A/B：同一批设备 TTS 固定句 + 真实 CXR 语速，比较复读（肉眼/显示文本）、CER、`P3_ASR` 时延、内存；
3. 验收线：固定语料 8/8 无复读；RTF P95（T16 口径）≤ 0.7；不引入新失败；
4. 回滚：恢复 int8 decoder 文件 + 清单哈希即可（纯数据回滚）。

## 6. 边界与未测

- 复读复现于**合成 TTS 音频**（不经过眼镜/CXR）；这与 `STATUS.md` 中真实用户语音复读的记录一致，
  但真实 CXR 语音下的修复效果需按 5.4 在 EDITH 内验证，本轮不声称已修复。
- 其他模型版本的 chunk/left-context 配置未做完整调优（small-bilingual 的截断可能与之有关），本轮只判断
  “不能作为 drop-in 修复”。
- RTF P95、长稳、功耗未测。

## 7. 证据与清理

- 测试 APK：`com.sheen.rokid.asrrep`，SHA-256 `5D9D24B42ED13EE125BC7B5C2956489CA88627964E36D6B455BF7070A1407930`（已卸载）。
- 运行时：`sherpa-onnx-1.13.8.aar` SHA-256 `633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96`。
- fp32 decoder：13,876,452 B，SHA-256 `2E3B5EC371F8899EE6ACD829FD753BA45772DF57A91BDF37CDE3136354E7DB7D`。
- 其他模型 tar 包（下载后仅用于诊断，已删除）：
  zh-14M `2CBD71B640D9C37D3784F29367333A4577B0398B62E9DEEED418170B081CBA8B`、
  small-bilingual `2B7C63322B32E5E0F2526043A1103366119CA58DD615CD7105A37C01DB9553D7`。
- 设备/本机临时文件（APK、模型副本、语料、JSON、日志、临时 Gradle 工程）已全部删除；未改 EDITH 代码/清单/设备数据。
