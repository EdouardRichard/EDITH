# 官方 AI 助手的 VAD / ASR 到底在哪一侧完成（眼镜 ADB 实测）

- 日期：2026-10-15 22:43–22:55（Asia/Shanghai）
- 设备：眼镜 `1901092544027662`（`RG-glasses` / Android 12 / API 32 / `SKQ1.240613.001 release-keys` / 固件 `1.26.011-20260929-150201`）；
  手机 红米 K40 `2696813b`（MIUI / Android 13 / API 33），其上 `com.rokid.sprite.aiapp` `1.14.19.0920`（versionCode 10140019）
- 触发方式：**用户自己在 22:42 用了一次官方助手**（4 轮），本轮**未**通过 intent/广播自动拉起系统助手；全程只读（除拉取 APK 到本机分析）

## 0. 结论

**ASR 不在眼镜端侧完成；本机实测链路上它在手机侧（且手机侧带的是端侧 RFM ASR 模型栈）。
VAD 两端都有，但角色不同：眼镜端那个属于拾音前端，真正决定转写切分/端点的是手机侧那个。**

| 环节 | 结论 | 决定性证据 |
|---|---|---|
| 唤醒词 / 激活词（KWS） | **眼镜端侧**（低功耗域，无需网络） | `I/RT600( 2400): status=0: ~kws0`、`I/rt600_spi_boot spi0.0( 1708)`；`/vendor/lib64/libcapiv2svacnnvendor.so`、`libcapiv2svarnnvendor.so`、`libcapiv2uvvendor.so`（Qualcomm SVA 族） |
| 麦克风采音 + 音频前端（3 麦波束/AEC/ANS/AGC） | **眼镜端侧** | `/system/etc/rokid_audio1/{deepbf_3mic_near_xinyu2026_08_25,dtln_aec_128_1,dtln_aec_128_2,bleach_ans}.tflite`；`/system/lib64/librokid_agc.so`、`/vendor/lib64/libRokidAudioEffects.so`；运行时 `cxr-service: openAudioRecord2: codec 1, mode 2, intent AI_assistant, denoiseMode 2` |
| VAD（拾音前端） | **眼镜端侧有一份** | `librokid_agc.so` 导出符号 `_ZN3hbl17VadSpeechDetector*`（`hbl::VadSpeechDetector::Process(AudioFrame*)`） |
| VAD（转写端点判定） | **手机侧** | 手机 APK `assets/rfmasr/rfm-asr.conf`：`--vad_enabled=true`、`--vad_model_path=vad_model/rokid-vad.onnx`、`--vad_end_ms=400`、`--vad_min_silence_duration=2`、`--continuous_decoding=true` |
| **ASR（开放式转写）** | **不在眼镜端**：眼镜把音频上行给手机，识别文本由手机回推 | 见 §2：眼镜 APK 内**无任何 ASR 模型/引擎**；22:42 那一轮 `cxr-service` 从 socket 收到 `cmd Ai` 请求（`id 1509`）交给眼镜助手进程的 `AsrMessageHandler` → 变成 `ASR_Result` 上屏 |
| 手机侧 ASR | **手机端侧 RFM ASR**（非眼镜端） | 手机 APK：`assets/rfmasr/rfm_model/v1.4.3/encoder.onnx` **143,960,017 B**、`ctc.onnx`、`rfm_lm/lm_n3_251115.bin` **24,920,899 B**、`vad_model/rokid-vad.onnx`、`silero_vad_ncnn.bin`；`lib/arm64-v8a/{librfm-asr,librokid_rfm_asr,librokid_vad,librfm-vad,libspeechengine,libfunasrruntime,libwhisper,libonnxruntime,libncnn}.so` |
| TTS | **眼镜端侧** | `RokidSpriteAssistServer.apk` 内 `librfm-tts.so` + `assets/resource/models/{1,2,3}/rokid_tts_{acoustic,vocoder}_*_fp16.onnx` |

一句话：**"乐奇"这类唤醒词与拾音前端确实在眼镜本地跑；但"把话转成字"这一步，本机实测是"眼镜采音 → CXR 上行 → 手机端侧 RFM ASR → 文本回推眼镜"。眼镜端没有任何 ASR 模型。**

## 1. 眼镜端静态取证

### 1.1 相关包与进程

```
$ adb -s 1901092544027662 shell pm list packages | grep rokid
com.rokid.os.sprite.assistserver        <- 系统助手（system uid=1000, SharedUser android.uid.system）
com.rokid.os.sprite.launcher / .live / .record / com.rokid.cxrservice / com.rokid.sysconfig / ...

$ ps -A  (节选)
system  1578  com.rokid.cxrservice
system  1988  com.rokid.os.sprite.assistserver
system  2348  com.rokid.os.sprite.assistserver:tts
u0_a41  1970  .pasr        (com.qti.pasrservice)
```

助手 APK：`/product/app/RokidSpriteAssistServer/RokidSpriteAssistServer.apk`
= **310,326,959 B**，`sha256 ccdc7084e81af4a1bd7aa24b01983016e86477c8be0e59b537c30e211631b76d`，
`versionName 0.3.8 / versionCode 3821 / targetSdk 34`，`primaryCpuAbi=arm64-v8a`，权限含 `RECORD_AUDIO`、`INTERNET`、`CAMERA`（均 `granted=true`）。

### 1.2 APK 里有什么（`unzip -l`，共 1420 条）

- **模型资产只有 TTS**：`assets/resource/models/{1,2,3}/rokid_tts_acoustic_*_fp16.onnx`、`rokid_tts_vocoder_*_fp16.onnx`、
  `assets/resource/tn/{zh,en}_tn_*.fst`、`word2phone.dict_*`、jieba 词典、`assets/model/best_sz256.mnn`（3 MB，非语音）、
  `assets/aiui_builtin_agents/builtin.rokid.ride.aix`。
- **全部 `*.onnx|*.mnn|*.pmdl|*.aix` 清单里没有任何 ASR 声学模型**。
- native 库：`librfm-tts.so`、`libonnxruntime.so`、`libMNN.so`、`libAPSE_1.9.41.so`、`libaudigent.so`、
  `libflora-cli.so`、`libink_android_api.so`、`libcaps.so`、`libcxr-*.so` —— 无 `librfm-asr.so` / `librokid_vad.so` / whisper / funasr。
- `libaudigent.so` 含字符串 `IPC connected as role=kws`（佐证唤起/前端是与 KWS 角色协作的一侧）。

### 1.3 眼镜端确实存在"云 ASR 能力"，但本轮未走到

APK 的 5 个 dex 字符串里含：

```
wss://ai-cloud.rokid.com/open/asr/v1/asr/stream        (+ -dev / -test 两个环境)
https://ai-cloud.rokid.com/prism/api
https://ai-gateway.rokid.com/v1/tts/stream
com/rokid/os/sprite/jsai/capability/InkSpeechCapability$createCloudAsrListener$1
CloudASRService / CloudAsrSession / CLOUD_ASR_SAMPLE_RATE / CLOUD_ASR_CHANNELS / CLOUD_ASR_MAX_CHUNK_BYTES
com/rokid/jsui/ink/ipc/SpeechRecognitionVadSilenceDurationCapability（MIN/MAX_VAD_SILENCE_DURATION_MS）
```

**但在 22:05–22:54 的完整日志缓冲（55,452 行）里，`CloudASR` / `ai-cloud` / `wss://` 命中数 = 0** ——
即"眼镜直连云端 ASR"这条代码路径在这次抓到的 4 轮助手会话中**没有运行**。

## 2. 眼镜端运行时取证（22:42 的四轮助手）

### 2.1 每一轮都是"眼镜起流上行 → 文本回推"

```
22:42:15.651 AssistServer_Log: AIAssistUtils -> interruptWakeUp cmdId: 1
22:42:15.652 AssistServer_Log: AIAssist -> AIAssistUtils    openAIFunction wakeup type: 4   <- 语音唤醒（非按键）
22:42:15.660 I/RT600( 2400): status=0: ~kws0                                                   <- 唤醒侧状态行
22:42:15.992 AssistServer_Log: CXRServiceManager -> startAudioStream  function: AI_assistant
22:42:15.993 cxr-service: openAudioRecord2: codec 1, mode 2, intent AI_assistant, denoiseMode 2
22:42:22.887 cxr-service: closeAudioRecord: intent AI_assistant
（同样模式在 22:42:26.13 / 22:42:31.67 / 22:42:46.69 重复出现；22:42:46 那轮是按键
 `FunctionKeyReceiver -> onReceive -> com.android.action.ACTION_AI_START`，`wakeup type: 1`）
```

> 注：两台设备的时钟相差约 0.8 s（同一 `reqId` 在手机上记 22:42:27.262、眼镜上记 22:42:28.080），
> 因此**只用 reqId 对齐两侧事件，不用时间戳算时延**。

### 2.2 ASR 文本是从眼镜**外面**进来的（决定性）

眼镜侧日志（原文）：

```
22:42:28.080 I/cxr-service( 1578): read from socket 52 bytes
22:42:28.080 I/cxr-service( 1578): recv Request: id 1509, cmd Ai
22:42:28.081 I/cxr-service-bridge-jni( 1988): onFloraInstantCallback: Ai
22:42:28.081 I/cxr-service-bridge-jni( 1988): processMessages: process messages 1
22:42:28.081 D/AssistServer_Log( 1988): AIAssist -> AsrMessageHandler    handle
22:42:28.082 D/AssistServer_Log( 1988): AIAssist -> AIAssistUtils    assemblyDataToLauncher key： ASR_Result
22:42:28.082 ... sendMsgToClient unityMsgJson:{"type":"cmd_bluetooth_ai_result","data":{"key":"ASR_Result","data":"现在几点了"}}
22:42:28.651 ... {"key":"ASR_Result","data":"现在几点了？"}          <- 0.57 s 后再来一次（partial → final）
22:42:28.669 D/AssistServer_Log( 1988): AIAssist -> AsrEndHandler    handle
22:42:28.670 ... {"key":"ASR_End"}
```

对应的手机侧日志（同一会话，`com.rokid.sprite.aiapp` pid 8320）：

```
22:42:25.326 I/cxr-service-jni( 8320): START_AUDIO_STREAM: remoteProtoMinorVersion 13
22:42:25.326 I/cxr-service-jni( 8320): recv StartAudioStream, codec 2, originCodec 1, channels 1, samplerate 16000
22:42:25.326 I/cxr-service-jni( 8320): RecvAudioStream auto decode ogg-opus: originCodec 1, transferCodec 2
22:42:25.326 I/BluetoothController( 8320): onStartAudioStream codec:1,intent:AI_assistant
22:42:27.262 I/CxrController( 8320): request reqId:1509,cmd:Ai,callback:null     <- 与眼镜收到的 id 1509 同一请求
22:42:27.366 I/CxrController( 8320): request reqId:1510,cmd:Ai,callback:null
```

⇒ 链路是：**眼镜采音（16 kHz mono，ogg-opus 编码）→ CXR 上行 → 手机 → 手机把识别文本以 `cmd Ai` 请求（reqId 1509/1510）推回眼镜 → 眼镜 `AsrMessageHandler` 转成 `ASR_Result`/`ASR_End` 上屏**。
消息形状与官方 CXR‑M 文档一致（`ASR_Result | Phone -> Glasses`；`sendAsrContent()/notifyAsrEnd()`），见 [ai-integration.md](D:/Project_new/rokid-docs/cxr-m/ai-integration.md)。

### 2.3 眼镜端音频前端与本地 VAD 的实证

- `/system/etc/rokid_audio1/`（4 个端侧模型，均已记 SHA-256）：

| 文件 | 字节 | 推断作用 |
|---|---|---|
| `deepbf_3mic_near_xinyu2026_08_25.tflite` | — | 3 麦深度波束成形（近场） |
| `dtln_aec_128_1.tflite` / `_2.tflite` | — | DTLN 深度学习回声消除 |
| `bleach_ans.tflite` | — | 环境噪声抑制 |

- `librokid_agc.so`（`sha256 3ae1a4ed…7a5f`）导出 `hbl::VadSpeechDetector`（构造/`Process(AudioFrame*)`/析构）⇒ **眼镜端有本地 VAD 实现**。
- 运行期采音链路：`cxr-service: AudioCapture.newRecord(AI_assistant)`、`AudioRecord8.pcm: 2000 milliseconds record 32000 samples`、`CXRProto: … sent … audio data … bytes`（≈2 s/包）。

## 3. 手机侧取证（ASR 真正所在）

`/data/app/~~E3AiDCVilhBGU0VBS0Tslg==/com.rokid.sprite.aiapp-moj6s0jK3w7J0vlMajLtjQ==/base.apk`
= **717,881,209 B**，`sha256 c4ecc13167cce803cf35f2dfef850971b6ca83cadea1776dd14521dfa447c38e`。

端侧 ASR/VAD 资产（节选）：

```
143,960,017  assets/rfmasr/rfm_model/v1.4.3/encoder.onnx
  6,412,024  assets/rfmasr/rfm_model/v1.4.3/ctc.onnx
 24,920,899  assets/rfmasr/rfm_lm/lm_n3_251115.bin
    315,449  assets/rfmasr/vad_model/rokid-vad.onnx
    621,680  assets/silero_vad_ncnn.bin  (+ .param 5,168)
    114,603  assets/rfmasr/rfm_model/words.txt
     27,170  assets/rfmasr/itn/zh_itn_tagger.fst
lib/arm64-v8a/{librfm-asr,librokid_rfm_asr,librokid_vad,librfm-vad,libspeechengine,
               libfunasrruntime,libwhisper,libonnxruntime,libncnn,libllama,
               libMicrosoft.CognitiveServices.Speech.core}.so
```

`assets/rfmasr/rfm-asr.conf`（原文节选）：

```
--onnx_dir=rfm_model/v1.4.3
--unit_path=rfm_model/words.txt
--itn_model_dir=itn
--vad_end_ms=400
--continuous_decoding=true
--lm_weight=0.5
# VAD 配置（基于 TenVAD ONNX 模型做前置语音检测）
--vad_enabled=true
--vad_model_path=vad_model/rokid-vad.onnx
--vad_threshold=0.4
--vad_min_silence_duration=2
--vad_min_speech_duration=0.25
--vad_window_size=256
--vad_max_speech_duration=20.0
# VAD 参数（针对 AISHELL4 多人会议调优）
--vad_hysteresis_margin=0.1
--vad_onset_confirm_frames=3
```

另有一份 LiteRT 变体 `assets/litert_resources/asr_resource/rfm-asr.conf`（`rfm_model/v1.3.1`，LM 未注释）。

## 4. 复现命令

```powershell
$G='1901092544027662'; $P='2696813b'
adb -s $G shell "pm list packages | grep rokid; pm path com.rokid.os.sprite.assistserver"
adb -s $G shell "unzip -l /product/app/RokidSpriteAssistServer/RokidSpriteAssistServer.apk"
adb -s $G shell "sha256sum /system/lib64/librokid_agc.so /system/etc/rokid_audio1/*.tflite"
adb -s $G pull /system/lib64/librokid_agc.so .            # nm/objdump 看 hbl::VadSpeechDetector
adb -s $G logcat -d -v time > glasses.txt                 # 找 RT600/kws、startAudioStream、recv Request: id …, cmd Ai、ASR_Result
$apk = (adb -s $P shell pm path com.rokid.sprite.aiapp) -replace 'package:',''
adb -s $P shell "unzip -l '$apk' | grep rfmasr"
adb -s $P shell "unzip -p '$apk' assets/rfmasr/rfm-asr.conf"
adb -s $P logcat -d -v time > phone.txt                   # 找 recv StartAudioStream / request reqId:…,cmd:Ai
```

本轮抓取的原始日志（55,452 行眼镜 / 717,266 行手机）与拉取产物在 `.local/adb-assist-evidence/`（工作区临时区，非交付物）。

## 5. 诚实边界（未测 / 不可断言）

1. **手机侧 ASR 是"纯本地"还是"本地引擎 + 云端兜底"未证死**。已证：APK 内含完整本地 RFM ASR/VAD 模型与本地 conf；app 的 Java 层（唯一 `classes.dex`）里**没有**任何 `open/asr` / `asr/stream` / `ai-cloud` 字符串。未证：那次会话里实际加载的是哪个 so、有没有并发走云。手机当前有一条到 `121.40.237.216` 的 TLS 连接，但 `ai-cloud.rokid.com` 解析到 Akamai（`2.16.10.167/182`），**该连接与 ai-cloud 不是同一目标**，不足以说明云 ASR。
2. **眼镜的 `CloudASR` 路径只是"本次未观测到"**，不是"不存在"：APK 内有 URL 与 `CloudASRService`，可能在无手机/其它场景启用。日志是环形缓冲，只覆盖 22:05–22:54。
3. **`RT600` 的厂商归属未在本机证实**（日志只给出 `RT600` / `rt600_spi_boot` 与 `~kws0` 状态；"NXP RT600"属于外部资料）。
4. **未做控制变量实验**：没有断网复测、没有单独量 KWS/VAD/ASR 各自的延迟与召回。四轮会话是**用户自发使用**时抓到的，不是受控注入。
5. 本轮**没有**自动触发系统助手、没有改任何设备设置、没有上传任何音频；手机侧 AI App 的 `versionName/Code` 与眼镜固件均已如实记录。

## 6. 对 EDITH 的口径影响

- 我们的分工（眼镜 PCM 上行 → **手机端** VAD/KWS/ASR）**与官方在本机的实际分工方向一致**，不是偏离厂商设计。
- 但要注意一处**差异**：官方把**微端音频前端（3 麦波束/AEC/ANS/AGC）与唤醒放在眼镜端**，我们目前是"眼镜只做 CXR 采音上行、手机端跑 KWS"。若后续要提升唤醒召回，官方这条"眼镜端前端 + 低功耗域 KWS"是可比对的参照（本机证据见 §1.2/§2.3），但**不得**据此声称我们已具备同等前端能力。
- 与本项目既有结论的关系：`2026-10-08-p3-t0-t1-glasses-sdk-unavailable.md` 判的是 **Glass3 开放 SDK** 在本固件不可用；
  本条证据补的是**系统自带助手**（`com.rokid.os.sprite.assistserver`）的端/云分工，两者互不矛盾。
