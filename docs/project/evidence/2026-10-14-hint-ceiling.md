# 2026-10-14 长提示上限的真机实测（120 / 200 / 400）+ 一个"旧眼镜构建"陷阱

- **checkpoint**：`edith-0.2-phase-b-exec-20261014-09`
- **设备**：K40 `2696813b`（Android 13）+ 眼镜 `1901092544027662`（固件 `1.26.011-20260929-150201`）
- **授权**：业主 2026-10-14 授权 ADB/无障碍代操作；本轮为"逐项无人介入"推进
- **要回答的问题**：`HintPolicy.DEFAULT_MAX_CODE_POINTS` / `HintWireCodec.MAX_TEXT_CODEPOINTS` /
  `ReadableHintAssembler.MAX_CODEPOINTS` 三个常量在 2026-10-14 被改成 **400**，但那是**估的值**——
  `2026-10-09-p3-2-hint-capacity.md:36` 明写"探针测不到 120 以上，因为 >120 会被我们自己的线缆拒绝"。
  **120 以上从来没有被测过。** 本轮实测三档并把结论写回常量。
- **隐私**：只推**合成文本**（`测` 重复 N 次）；所有日志只记**字符数/行数/时长**，不含正文。

## 1. 仪器与前置（两处必须先修，否则测不出来）

1. **`DebugHintInjector.MAX_CHARS` 120 → 400**（TDD：`DebugHintInjectorTest` 新增
   `theInjectorsBoundIsTheWiresBoundSoTheProbeCanReachTheRealLimit`，先红后绿）。
   它就是 2026-10-09 那条"线缆"：`payload()` 对合成填充与外部文本都 `.take(MAX_CHARS)`，
   **>120 是注入器自己夹掉的**，链路根本没被问过。
2. **当前插件必须切到「提示探针（调试）」(`p2-hint`)**：探针走
   `DiagnosticHintPlugin.submitText` → `pluginContext.services.require(Capability.TextOutput)`，
   插件未激活时 `submitText` 直接 `false`。本轮用 `uiautomator dump` 定位下拉项并用
   `input tap` 选中（坐标见 §5）。
3. **必须等手机真的把这条 SHOW 发出去再读眼镜**：宿主按**阅读预算**排队，上一条提示还在阅读窗口里时，
   这一条不会发出去（`P3_... accepted=true` 只是被仲裁器收下，不等于已发出）。
   探针脚本因此改为轮询 `EdithHint: outbound: Show` 计数（`tools/p3/glasses-hint-capacity.ps1`，已更新）。
   旧版本固定 `Start-Sleep 2500`，第 2 档起读到的是上一档的旧行——那些数字是**假的**。

## 2. 实测表（权威读数，脚本一次跑完）

```
tools/p3/glasses-hint-capacity.ps1 -PhoneSerial 2696813b -GlassesSerial 1901092544027662 -Chars 120,200,400
```

| chars | accepted | sent | lines | maxLines | clipped | scrollMillis | chars/line |
|---|---|---|---|---|---|---|---|
| 120 | True | True | 10 | 6 | True | 5600 | 12 |
| 200 | True | True | 17 | 6 | True | 15400 | 11.8 |
| 400 | True | True | 34 | 6 | True | 39200 | 11.8 |

**眼镜侧原始行**（`logcat -s EdithGlasses`，只记数）：

```
17:10:50.101  hint rendered lines=34 maxLines=6 chars=400 clipped=true scrollMillis=39200
```

**眼镜侧收到的报文长度**（本轮新增的计数仪器 `hint rendered` 的姊妹行，见 §3）：

```
17:08:02.930  I/EdithGlassesBridge: inbound Show chars=399     <- 手机发 120（JSON 403 字符；ttl 位数不同故此处 399）
17:08:34.675  I/EdithGlassesBridge: inbound Show chars=479     <- 手机发 200
17:10:50.072  I/EdithGlassesBridge: inbound Show chars=679     <- 手机发 400
```

⇒ **三档全部被策略收下、真的发到眼镜、被完整解码、按 34 行布局、并滚动 39.2 秒**。
`clipped=true` 在这里**不再是"被裁掉"**：它的定义就是"高于 6 行窗口"，而 P3.2-CLIP 起窗口外的行由
`HintAutoScroll` 滑过去（`scrollMillis` 就是这次滑动的时长）。佩戴者可读性未由本轮评估。

## 3. 一个真陷阱：旧眼镜 APK 会把 >120 的提示静默丢成 `undecodable`

第一次跑探针时，**120 正常、121 以上全部失败**（`inbound undecodable`，眼镜什么都不显示）。逐步收窄到
**120 通过 / 121 失败**，看上去像"链路字符上限 ~403"。随后（为了加 §4 的计数仪器）重建并重装了眼镜 APK，
同样的 121 **立刻正常**（`inbound Show chars=399` + `hint rendered lines=11 chars=121 scrollMillis=7000`）。

**根因**：`HintWireCodec` 活在 `output-runtime` 里，**手机与眼镜各带一份**。装着的眼镜 APK 是
**2026-10-09 的旧构建**（`lastUpdateTime 2026-10-09 11:51:11`，早于 I3 的 `b0868c2`），它的
`MAX_TEXT_CODEPOINTS` 还是 **120** ⇒ `decode()` 对 >120 的 SHOW 一律返回 null ⇒
`inbound undecodable` ⇒ **该轮无声**。也就是说：

- 业主 I3 报的"超长通知让整轮无声"，**在真机上并没有被手机侧的三处修复单独治好**——
  眼镜端旧构建的 120 上限同样会把它吃掉，而且是**同一种症状**；
- 2026-10-09 那条"探针测不到 120 以上"因此有**两个各自独立的 120**：注入器的 `MAX_CHARS`，
  和眼镜 APK 里那份旧的 `MAX_TEXT_CODEPOINTS`。两者现在都修掉/重装了。

**操作记录（偏差，已记账）**：眼镜端 `install -r` 失败
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`），按仓库既有先例
（`2026-10-09-p3-1-passive-loop-e2e.md:2108` 同一现象）执行
`adb -s 1901092544027662 uninstall edith.glasses` + `install -t glasses-app-debug.apk`（均 `Success`）。
眼镜端没有 1.03 GiB 那类手工 provision，但这是对"重装只用 install -r"的**显式偏离**：
眼镜 App 的数据被清空并重新拉起（`am start -n edith.glasses/.GlassesActivity`，pid 23919，
`subscribed to rk_custom_client result=0`）。
**手机侧的 1.03 GiB provision 全程未动**（手机只做了 `install -r`）。

## 4. 为区分"传输截断"与"解码器拒收"新增的计数仪器

眼镜侧原先只打 `inbound Show` / `inbound undecodable`，**无法区分**"报文在路上被截断"与"报文本地合法但被
解码器拒收"——这正是 §3 一开始误判成"链路字符上限"的原因。新增
`HintPresentation.inboundReport(json, decoded)`（纯函数，TDD：`HintPresentationTest` 先红后绿），
行内加 `chars=<收到的长度>`：**只记计数，不记正文**。有了它，§3 的翻案是一次读数就能看出的事。

## 5. 复现步骤（本轮的精确操作）

```
# 1) 手机：装带新注入器的构建
adb -s 2696813b install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
# 2) 眼镜：**必须是同一次构建**（否则见 §3）
adb -s 1901092544027662 install -t glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
# 3) 手机：拉起会话，插件下拉切到「提示探针（调试）」，再点「开始」
adb -s 2696813b shell am start -n edith.phone/.MainActivity
adb -s 2696813b shell input tap 540 667      # 打开插件下拉
adb -s 2696813b shell input tap 240 1107     # 选「提示探针（调试）」
adb -s 2696813b shell input tap 197 855      # 开始
# 4) 逐档推合成文本并读眼镜（脚本自己会等 SHOW 真的发出去）
powershell -File tools/p3/glasses-hint-capacity.ps1 -PhoneSerial 2696813b `
    -GlassesSerial 1901092544027662 -Chars 120,200,400
```

## 6. 三个常量的结论

**保持 400，但它现在是"实测可交付"的，不再是估的。**

- 120 / 200 / 400 三档在真机上全部**收到、解码、布局、滚动**（§2）。
- **401 及以上无法用本仪器测**：注入器按线缆上限夹取（两个常量相等由测试钉住），401 会被静默变成 400。
  "超限即拒收"由 JVM 的 `HintPolicyTest`（`accepts(401) == false`）与 `HintWireCodecTest` 钉住，
  **不是**本轮真机读数。⇒ 本文件**不声称 400 是链路的物理上限**，它声称的是：
  **400 这个值本身在真机上被证明是可交付的**（而 2026-10-09 时它在 120 处就没有证据）。
- **仍未测**：>400 的任何值（被我们自己的策略挡住）；非 3 字节字符（emoji/混排）在同样码点数下的表现
  （收到的 JSON 长度已按字符计过：400 个 CJK 字符 = 679 字符报文，链路明明搬得动 ≈1.5 KB UTF-8）；
  佩戴距离可读性；滚动帧率。
- **量这条链路的前置条件（写给下一次）**：**手机与眼镜必须是同一次构建**。两侧各带一份
  `HintWireCodec`，任何一侧是旧构建都会在"上限"上给出**看起来像链路极限**的假读数。
