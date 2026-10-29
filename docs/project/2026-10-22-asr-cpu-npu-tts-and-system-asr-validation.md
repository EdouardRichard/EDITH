# 端侧 ASR 独立实测：CPU vs NNAPI、设备 TTS 多语速、安卓系统 ASR（K40，2026-10-22）

> 一次性的独立验证记录。测试程序、APP、音频、JSON、日志等临时产物已全部删除，本文件是唯一保留物。
> 按业主本次要求**不更新** `STATUS.md` / `ACCEPTANCE.md` / `DECISIONS.md` / `AGENTS.md`。
> 测试语料是固定合成文本（设备 TTS 生成），不含用户数据；报告只保留延迟、字数、相似度与合成文本本身。

## 1. 目的与边界

1. **不依赖 EDITH 应用**：单独构建并安装一个一次性 Android 测试 APK（`com.sheen.rokid.asrpoc`），
   不 import EDITH 的任何代码；只复用两样「钉住的输入」：
   - `sherpa-onnx-1.13.8.aar`（与 EDITH 同一文件，SHA-256 `633C2432…3BD96`）；
   - EDITH 设备上 `no_backup/engine-models` 里已校验的 ASR 模型文件（哈希见 §2.3）。
2. **CPU vs NPU（NNAPI）延迟对比**：同一模型、同一 20 ms 分块喂入、同一解码配置
   （16 kHz / 80 维 fbank / 2 线程 / `greedy_search` / `enableEndpoint=false` / `modelType=zipformer`），
   只改 `provider`（`cpu` vs `nnapi`）。
3. **设备 TTS 多语速生成音频测 ASR**：用 K40 自带 TTS 引擎按不同 `setSpeechRate` 合成固定中文文本，
   再直接喂给 ASR（不经过眼镜/麦克风）。
4. **安卓系统自带 ASR 性能**：调用 `android.speech.SpeechRecognizer`，实际服务是小米的
   `com.xiaomi.mibrain.speech/.asr.AsrService`；分别尝试「文件喂入」与「扬声器→麦克风」两种方式。

## 2. 环境

### 2.1 设备

| 项 | 值 |
|---|---|
| 机型 | Redmi K40（`M2012K11AC` / `alioth`） |
| SoC | Qualcomm SM8250（骁龙 870，Hexagon NPU；无 MediaTek APU） |
| 系统 | Android 13 / API 33 / MIUI `V816.0.10.0.TKHCNXM` |
| ABI | `arm64-v8a, armeabi-v7a, armeabi` |
| 指纹 | `Redmi/alioth/alioth:13/TKQ1.221114.001/V816.0.10.0.TKHCNXM:user/release-keys` |
| NNAPI HAL（平台侧） | `android.hardware.neuralnetworks@1.0–1.3` 的 `qti-default / qti-dsp / qti-gpu / qti-hta` 均在 `lshal` 中注册 |
| TTS 引擎 | `com.xiaomi.mibrain.speech`（系统默认，zh-CN 可用；输出 16 kHz 单声道 PCM16 WAV） |
| 系统 ASR 服务 | `com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService`；`isRecognitionAvailable()=true`，`isOnDeviceRecognitionAvailable()=false`（无端侧识别声明） |

### 2.2 测试 APK

| 项 | 值 |
|---|---|
| 包名 | `com.sheen.rokid.asrpoc`（debug 构建，测试后已卸载） |
| APK SHA-256 | `F6D2291395123B594B11B8BE9EE3DB134DFAB5EBBE5B1DC7BDA2539E574986A3` |
| 运行时 | 钉住的 `sherpa-onnx-1.13.8.aar`（SHA-256 `633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96`） |
| 模型来源 | 从 K40 上 EDITH 已校验的 bundle 取回并复算哈希，与 `tools/scripts/p2/engine-manifest.json` 一致 |

### 2.3 模型（只用了 ASR 4 个文件；VAD/声纹与本次无关）

| 文件 | 字节 | SHA-256（取回后复算） |
|---|---:|---|
| `encoder-epoch-99-avg-1.int8.onnx` | 181,895,032 | `8FA764187A261844F859D7143EBAA563AF5D10ADFECE4C18A8F414C88CBA2A9B` |
| `decoder-epoch-99-avg-1.int8.onnx` | 13,091,040 | `1A70C593D71E53F023F5F55B0B4CFFF5055ABB786EE3992E5F63DC2E273CC4FA` |
| `joiner-epoch-99-avg-1.int8.onnx` | 3,228,404 | `1ED689C5ED19DBAA725D9D191BB4822B5F4855A39E1FFD28CBC1F340D25B2EE0` |
| `tokens.txt` | 56,317 | `A8E0E4EC53810E433789B54A5C0134A7EAA2FFCA595A6334D54C00DA858841D3` |

## 3. 方法

- **性能指标**：每个 WAV 先跑 1 次预热（不计），再测 5 次；报告 5 次的中位数。
  - `rtfDecode = 所有 decode() 调用耗时之和 / 音频时长`（计算量口径，与音频实时到达解耦）；
  - `wallMs = 从首个分块到最终结果的墙钟`；`cpuMs = Process.getElapsedCpuTime()` 差值；
  - 另外记录模型加载耗时（`loadMs`）。
- **TTS 语料**：固定文本「今天天气怎么样」（7 字）与「请帮我打开设置，然后把屏幕亮度调到最高，再回到桌面」（去标点 23 字），
  短句按 6 档语速合成，长句只用 1.0；WAV 以 20 ms 分块直接喂 ASR。
- **系统 ASR**：
  1. 「文件喂入」：按公开 API `RecognizerIntent.EXTRA_AUDIO_SOURCE`（ParcelFileDescriptor，16 kHz 单声道 PCM16）
     尝试喂同一批 WAV；
  2. 「扬声器→麦克风」：手机媒体音量调到最大播放 WAV，系统识别器听麦克风（存在声学耦合，结果按此标注）。

## 4. 结果

### 4.1 sherpa-onnx ASR：CPU vs NNAPI

模型加载：CPU **760.6 ms** / nnapi **783.0 ms**（nnapi 只跑到了回退，见 §4.2）。

| provider | WAV（TTS 语速档） | 音频 s | med RTF | med wall ms | med cpu ms | 识别文本（前 5 次一致） |
|---|---|---:|---:|---:|---:|---|
| cpu | `zh-s-r10`（0.1） | 1.224 | 0.0647 | 81.5 | 239 | 体验原因天天天气什 |
| cpu | `zh-s-r50`（0.5） | 1.152 | 0.0691 | 81.5 | 240 | 体验体验原因天天天天天天气睡醒 |
| cpu | `zh-s-r100`（1.0） | 1.080 | 0.0740 | 82.0 | 247 | 今天天天天天天天气什么 |
| cpu | `zh-s-r250`（2.5） | 0.936 | 0.0562 | 54.2 | 164 | 时间天天 |
| cpu | `zh-s-r500`（5.0） | 0.756 | 0.0701 | 54.4 | 164 | 时间天天天气睡 |
| cpu | `zh-s-r1000`（10.0） | 0.648 | 0.0406 | 27.4 | 81 | 你 |
| cpu | `zh-l-r100`（1.0） | 4.572 | 0.0814 | 379.3 | 1,124 | 请你帮我打开试试去试试然后到屏屏幕的亮亮亮度调到最高方回到桌界桌面 |
| nnapi | 同 7 条 | 同 | 与 CPU 差 <0.002 | 与 CPU 差 <3 ms | 与 CPU 差 <10 ms | 与 CPU 相同文本 |

**判读**：

1. CPU 上 RTF 中位数 **0.04–0.08**（即 12–25 倍实时），ASR 计算不是当前链路的延迟瓶颈。
2. nnapi 组与 CPU 组在毫秒级噪声内一致；结合 §4.2 的 native 日志，**nnapi 没有真正启用**，
   所以这不是「NPU 更慢」的读数，而是「这条 NPU 路径根本没跑起来」。
3. 所有 TTS 音频上都出现了**识别复读**（`天天天天天天`、`体验体验`）。这与 `STATUS.md` 记录的
   “ASR 复读”问题一致，且本轮输入是**干净的 TTS WAV（不经过眼镜/CXR）**——说明该现象不是 CXR
   链路独有；本轮不诊断根因，如实记录。

### 4.2 NNAPI：配置被接受，但运行时回退 CPU

native 日志（`sherpa-onnx` tag，同一 APK、同一模型）：

```
OnlineRecognizerConfig(... provider_config=ProviderConfig(device=0, provider="nnapi", ...))
Android NNAPI requires API level >= 27. Current API level 21 Fallback to cpu!   ×3（encoder/decoder/joiner 各一次）
```

- 配置确实按 `provider="nnapi"` 传入并被解析，但 AAR 内的 NNAPI 分支读到的平台 API level 是 **21**
  （低于 27），直接回退 CPU，因此三份 session 全部是 CPU 执行。
- 原因与上游一致：预编译 AAR 构建时没有用 `-DANDROID_PLATFORM=android-27`；sherpa-onnx 上游
  [issue #895](https://github.com/k2-fsa/sherpa-onnx/issues/895) 明确要求用 android-27 或更高平台构建
  并对 ASR 使用 `provider=nnapi`。**要用 NNAPI 必须自己重编 AAR**，不是改一个参数就行。
- 结论：**在 EDITH 当前钉住的预编译 sherpa-onnx 1.13.8 AAR 上，K40 的 NNAPI/NPU 路径不可用（回退 CPU）**。
  本轮无法给出“NPU 更快/更慢”的数字；这是 `NotMeasured`，不是通过。

### 4.3 设备 TTS 不同语速

`com.xiaomi.mibrain.speech` 的 `setSpeechRate` 返回值均为 `SUCCESS`，但实际语速变化被严重压缩且分档：

| 请求 rate | 音频时长 s（7 字短句） | 相对 1.0 时长 |
|---:|---:|---:|
| 0.1 | 1.224 | 1.13× |
| 0.5 | 1.152 | 1.07× |
| 1.0 | 1.080 | 1.00× |
| 2.5 | 0.936 | 0.87× |
| 5.0 | 0.756 | 0.70× |
| 10.0 | 0.648 | 0.60× |

- 慢速侧饱和：0.1 与 0.25 输出完全相同（1.224 s）；1.0–1.5 档也相同（1.08 s），
  所以最终选用上表 6 档，覆盖约 **1.9×** 的实际时长范围。
- 音色/采样率固定 16 kHz 单声道 PCM16，适合直接评测 ASR。

### 4.4 安卓系统 ASR（小米引擎）

**A. 文件喂入（`EXTRA_AUDIO_SOURCE`）——不可用**

- 28 次会话（7 WAV × 2 配置 × 2 次）全部 `ERROR_CLIENT`。
- 框架日志：`startListening() failed / android.os.DeadObjectException: Transaction failed on small parcel;
  remote process probably died…` —— Android 13 平台/小米服务不接受该音频注入 extra（该 API 在
  API 36 才公开），服务进程在事务中死亡。

**B. 扬声器→麦克风——可调用但不可用（网络依赖 + 命令式识别）**

| 会话类型 | 次数 | 结果 |
|---|---:|---|
| 首次尝试（旧授权状态下） | 2 | 1 次成功：短句识别为「是啥」，`results@1919 ms`；1 次 `ERROR_NO_MATCH@4171 ms` |
| 本轮完整 14 次（权限修复后） | 14 | 1 次成功（长句→「打开设置」，`results@3441 ms`）；**6 次 `ERROR_NETWORK_TIMEOUT`（约 5.0 s）**；7 次 `ERROR_NO_MATCH`（约 4.2–4.3 s）；0 次 partial |

- 全部 14 次会话都有 `readyForSpeech`（47–118 ms）与 `beginningOfSpeech`（12–27 ms），但**没有一次 partial**。
- `ERROR_NETWORK_TIMEOUT` 说明该引擎的识别结果**依赖网络**（`isOnDeviceRecognitionAvailable()=false` 也一致）；
  本环境网络条件下约一半会话 5 s 超时。
- 长句能出「打开设置」并带「小爱」类前缀/命令化倾向，说明这是**命令式语音助手识别器**，不是通用转写器。
- 权限是另一个坑：重装后 MIUI 把 `pm grant RECORD_AUDIO` 标成 `REVOKED_COMPAT`，服务报
  `caller doesn't have permission:android.permission.RECORD_AUDIO` 并拒绝；必须
  `appops set … RECORD_AUDIO allow` + `pm grant` 才进入可调用状态。无人值守第三方调用在 MIUI 上不可靠。

**系统 ASR 结论**：本机可用，但**不可控、依赖网络、无端侧声明、命令式、对第三方无人值守调用不友好**；
无法作为 EDITH 的可审计端侧 ASR 后端。其干净延迟/准确率表格判为 `NotMeasured`（样本仅 1 次成功）。

## 5. 结论

1. **CPU vs NPU**：EDITH 钉住的 sherpa-onnx 1.13.8 预编译 AAR 在 K40 上请求 `provider=nnapi` 会
   **回退 CPU**（API level 判定 21 < 27），因此本轮无法测到 NPU 加速；CPU 侧 RTF 中位数 0.04–0.08，
   计算不是瓶颈。要让 NPU 真正参与，需要重编 AAR（`ANDROID_PLATFORM=android-27+`）并重新做精度/性能验收。
2. **设备 TTS 语速**：小米 TTS 接受 `setSpeechRate` 但压缩严重（0.1→1.224 s，10.0→0.648 s，约 1.9× 范围）；
   6 档音频均成功用于 ASR 测试。
3. **ASR 复读**：在**纯 TTS WAV、不经过眼镜/CXR** 的条件下同样出现，确认不是 CXR 链路专属；与
   `STATUS.md` 记录的既有问题一致，根因未在本轮定位。
4. **系统 ASR**：文件注入不可用；麦克风路径依赖网络且大量 `ERROR_NETWORK_TIMEOUT`/`ERROR_NO_MATCH`，
   仅 1/14 成功且为零碎短句；不建议作为 EDITH 端侧 ASR 方案。

## 6. 未测 / 不可读成通过

- NPU 真实加速（NNAPI/QNN）性能：`NotMeasured`；本轮只有“回退 CPU”的证据。
- 系统 ASR 的稳定延迟/准确率：`NotMeasured`（1 次成功，其余全错误；无 partial）。
- 真实人声、眼镜麦克风、CXR 链路、KWS 唤醒、VAD 端点：均不在本轮范围。
- 长稳/功耗/发热：未测。

## 7. 临时产物清理

- 设备：测试 APK 已 `adb uninstall com.sheen.rokid.asrpoc`（含其外部存储目录）；未改动 EDITH 应用与数据。
- 本机：临时 Gradle 工程、APK、取回的模型副本、TTS WAV、JSON、日志已全部删除；
  本文件是唯一留存物。仓库只新增本报告（未提交）。
