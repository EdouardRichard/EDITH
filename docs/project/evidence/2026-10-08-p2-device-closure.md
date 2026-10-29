# T18 设备闭环：交互、上屏、按需唤醒全部真机验证；三个真机专属缺陷已定位并修复

checkpoint: p2-exec-20261008-41
日期：2026-10-08（会话 18:20–19:5x）
设备：手机 `2696813b`（Redmi K40，调试机）、眼镜 `1901092544027662`（固件 `1.26.011-20260929-150201`）
原始痕迹：`.local/p2-t18-device-1/`（uiautomator 转储 ui-01..u24）+ 两端 logcat（下引均为原样行）
用户在场并配合（连接/授权、佩戴读数）——**无自动化替代的人工结论已标注来源**。

---

## 一、这一轮到底验证了什么（结论先行）

| 项 | 结论 | 证据 |
|---|---|---|
| 眼镜↔手机自定义命令**双向** | **真机通过** | 手机发 `HELLO/PING/SYNC/SHOW/CLEAR` → 眼镜收；眼镜回 `PONG/ACK` → 手机收（`EdithHint: inbound: Pong` / `inbound: Ack`） |
| 时钟握手与映射 | **真机通过** | `handshake: mapping live … syncSent=true`；眼镜 `Sync accepted: … validFor=29956ms`；区间宽度实测 31–98 ms（≤100 ms 约束内） |
| 提示上屏 | **真机通过** | 眼镜 `Show displayed: sequence=1..7 …`；手机每条后收 `inbound: Ack` |
| 佩戴可读性（B3） | **用户确认通过** | 用户原话：“可以看到短句了。” |
| 推送时**主动唤醒**面板 | **真机通过（客观）** | 见第三节 `dumpsys power` 前后对照 |
| 到期释放（非常亮） | **真机通过（客观）** | TTL 到期后 `mWakefulness=Asleep`、`Wake Locks: size=0` |

**注意**：D41 只要求“与官方 demo 一致即正确、不做实机验证”；本节是**额外的**真机实证，强度高于 D41 所需，因此 T18 的交互行可以从“按 D41 结清”升级为“真机已验证”。

## 二、三个**只在真机上暴露**的缺陷（都在 JVM 夹具里复现为 RED 后修复）

### 缺陷 1：`services()` 对每次激活都构造插件存储 → `UNKNOWN_PLUGIN_NAMESPACE`

- 现象：显示刚变活、重试激活，`host.activate` 仍返回 `Failed`，被 `HostRuntime` 吞掉。
- 定位：给 `HostRuntime` 加只读 `lastActivationCause` 后，日志给出真因 ——
  `activation refused result=Failed … cause=java.lang.IllegalArgumentException: UNKNOWN_PLUGIN_NAMESPACE`。
- 根因：`CxrHostEnvironment.services()` 无条件 `storageFor(pluginId)`；而白名单只有
  `smart-assistant / interview-assistant / p2-diagnostic`，探针插件 `p2-hint` **不申请 Storage** 却被要求建存储。
- 修复：**只在插件真的申请 `Storage` 时才解析**（`if (storageFor != null && Capability.Storage in granted)`）。
- 夹具坑：JVM rig 的 `storageFor` 原为 `null`，所以永远测不出来；现已按生产白名单**故意抛错**，测试可在 JVM 复现。
- 测试：`CxrHintLinkTest.aRefusalForTheMissingDisplayIsRetriedOnceTheDisplayBecomesReal`
  （修复前 `expected:<Ready> but was:<Paused>`）。

### 缺陷 2：`ClockMapping.fromSync` 把锚点写死为 0 → 每条 SHOW 都被判“映射已过期”

- 现象：眼镜日志 `Sync accepted: syncId=… validFor=29956ms`，紧接着 `Show refused: mapping expired for syncId …`。
- 根因：`isExpired(now) = now - establishedAtPhoneNanos >= validForNanos`，而 `fromSync` 传的是
  `establishedAtPhoneNanos = 0L`；真机 `nanoTime()` ≈ 145,000 s ≫ 30 s → **一建立即过期**。
  JVM 夹具的 `FakeClock` 从 0 开始，所以测试一直绿。
- 修复：`fromSync(..., establishedAtNanos, validForNanos)`（**必填**，不许再默认 0），眼镜端用本地接收时刻做锚点。
- 测试：`GlassHintControllerTest.aMappingFromSyncIsNotAlreadyExpiredOnADeviceWhoseClockIsHoursOld`
  （用真机数值：`glassUptime=145_533_601_387_345`、`phoneUptime=144_913_586_573_571`；修复前
  `expected: <[先讲结论]> but was: <[]>`）。

### 缺陷 3：会话死亡后 `connected` 不复位 → 连接按钮永久 no-op

- 现象：AI App 在底下结束了会话（日志 `State: Started -> Terminating -> Idle`、`gracePeriod 到期，自动断连`），
  此后每次发送 `sendCommand refused: no live session (ready=false)`，再点「连接眼镜」在 UI 上毫无作用，
  **只能重启 App** 才能恢复。
- 根因：`RokidSessionTransport.connect()` 里 `if (connected) return`，而 `connected` 只在第 92 行置 true、
  从不复位。
- 修复：`interrupt()` 中复位 `connected = false`（会话没了就不能再自称已连接）。
- 测试：`TransportConnectIdempotencyTest.aSessionThatDiedCanBeConnectedAgain`（修复前 `expected:<2> but was:<1>`）。
- 真机复验：强杀眼镜 App 断会话后，再点连接出现新的
  `installed client custom-command callback: CXRLink` + `inbound custom commands are served by the client callback`，
  随后 `ping … sent=true`、`display became live` —— 自愈成功。

## 三、按需唤醒（用户指示 + 方案 A 批准）

**用户指示**：“推送屏幕消息时，才需要唤醒”（语音不需要）；并选定**方案 A**（按需唤醒，接受新增 `WAKE_LOCK`）。

**官方依据**（按铁律先查 SKILL 与其自带参考）：厂商 SDK **没有**主动亮屏 API；官方 FAQ 只给
`FLAG_KEEP_SCREEN_ON`（“如何保持屏幕常亮？”），并明确“指环中间键长按触发 `KEY_POWER` 用于亮屏/灭屏，**应用层无法拦截**”。
因此主动点亮只能是标准 Android 屏幕唤醒锁：`SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`，
**只在该条提示上屏期间持有**，到期/清屏/断连/onDestroy 立即释放，并有 15 s 上限兜底（不是常亮）。

客观证据（`adb shell dumpsys power`）：

| 时刻 | 面板 | 唤醒锁 |
|---|---|---|
| 推送前（用 `KEYCODE_POWER` 睡下） | `mWakefulness=Asleep` | `Wake Locks: size=0` |
| 推送一条提示后 | **`mWakefulness=Awake`** | `SCREEN_BRIGHT_WAKE_LOCK 'edith:hint' ACQUIRE_CAUSES_WAKEUP isSystem=false (uid=10066)` + `WindowManager ON_AFTER_RELEASE` |
| TTL 到期后 | **`mWakefulness=Asleep`** | **`Wake Locks: size=0`** |
| 再推一条 | `Awake` | 再次持有 |

权限面变更（已批准）：眼镜端新增 `android.permission.WAKE_LOCK` —— 此前 D40 的“零权限”基线**就此变更**，
范围仅此一项，无录音/存储/网络权限。另按官方 FAQ 的适配建议把提示文字整体下移 40px（顶部倒影区）。

## 四、为了能定位而加的诊断（都保留，均有 Test 兜底或与测试互补）

| 位置 | 日志 tag | 作用 |
|---|---|---|
| 手机 握手 | `EdithHint` | `outbound: Show sequence/ttl/syncId`、`inbound: X`、映射建立/拒绝原因、`display became live` |
| 手机 宿主 | `EdithHost` | `activation refused result=… available=… state=… cause=…`、`display-live retry: armed=…` |
| 手机 传输 | `EdithCxrCommand` | 客户端回调装在**哪一层**、`sendCommand refused: no live session` |
| 眼镜 桥 | `EdithGlassesBridge` | `inbound <命令名>`、`subscribed result=…` |
| 眼镜 控制器 | `EdithGlasses` | `Show refused: <具体门控>`、`Sync accepted: … validFor=…`、`Show displayed: …` |

原则同前：**只记录本来会静默失败的路径**（频率低、不刷屏），这是这三条缺陷能被找到的唯一原因。

## 五、仍未解决 / 已记录的限制（不得当成已通过）

1. **诊断页能力表是构造期快照**：`HostCompositionRoot.reasons` 在构造时算一次，所以显示已活时页面仍写
   “眼镜显示 尚不可用”。UI 真话问题，未修。
2. **快速连推会排队**：我以 1.7 s/条连点 10 次时，后几条的 `remainingTtl` 降到 ~1.8 s（仲裁器排队所致）；
   按正常阅读节奏（≥3 s/条）不会发生。属行为观察，未改。
3. **`hintId`/revision 复用**：探针固定 10 句轮转，`hintId=hint-probe-N` 递增，未触发去重门控。
4. **产品级验收仍按 D38 推迟**：真实引擎/ASR/模型（T16 数字）、真实文本环、语音输出——与本次体验证无关。
5. 提示 TTL 由发送方决定，当前探针为 10 s；本轮未测 >15 s 的提示（唤醒锁上限也是 15 s）。

## 六、APK 与设备状态

- 本轮最后构建：phone `69DE7E5625B9…`、glasses `E8817B3979AC…`（**均已安装到设备**）。
- 全量回归：**16 模块 / 764 测试 / 0 失败 / 0 错误 / 0 跳过**。
- 两个钉住的 AAR 哈希未动；无提交/推送/合并；无凭证读取；无上传。
