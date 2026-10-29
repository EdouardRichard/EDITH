# 双端交互对齐官方 demo（D41）：改了什么、此前结论如何更正、TDD 如何简化

checkpoint: p2-exec-20261008-40
日期：2026-10-08
用户指示：「检查当前双端app代码，所有眼镜端与手机端的交互都百分百按照官方demo来实现，且不需要实机验证，
按官方demo的就是正确的原则简化TDD。」

---

## 一、逐点对照与对齐结果

官方样本：手机 `renew_cxrl`（`CustomCmdViewModel`、`SessionHubViewModel`）、眼镜 `cxrswithcxrl`（`MainViewModel`）。

| 交互点 | 官方 demo | 我们（改动前） | 现在 |
|---|---|---|---|
| 手机→眼镜 发送 | `cxrLink.sendCustomCmd("rk_custom_client", Caps{ write("rk_custom_key"); write(payload) })`（**client 层**；Caps 两个字符串：回包频道键 + 载荷） | `session.sendCustomCmd(cmd, Caps{ write(json) }, ByteArray(0))`（session 层；单字符串） | ✅ 与 demo 一致：client 层 + `[rk_custom_key, json]` |
| 手机 入站注册 | `cxrLink.setCXRCustomCmdCbk(ICustomCmdCbk)`（**client 层**） | `CxrSession.addCustomCmdCallback(ICustomCmdSessionCallback)`（session 层） | ✅ 与 demo 一致（session 层仅作取不到 client 时的回退，且不会重复注册） |
| 手机 入站解析 | `if (key == "rk_custom_key") Caps.fromBytes(payload)`（**序列化 Caps**） | 用"首字节是否 `{`"判 JSON → **丢了所有真实回包** | ✅ 与 demo 一致：解析序列化 Caps，取**第二个**字符串 |
| 眼镜 入站注册 | `subscribe("rk_custom_client", MsgCallback)`（**3 参**回调） | `MsgReplyCallback`（4 参） | ✅ 与 demo 一致 |
| 眼镜 回发 | `sendMessage("rk_custom_key", Caps{ write("message"); write(payload) })`（标签 + 载荷两字符串） | `sendMessage(key, Caps{ write(json) })`（单字符串） | ✅ 与 demo 一致 |
| 眼镜 状态回调 | `setStatusListener(...)` 先于 `subscribe` | 同 | ✅ 一致（未改） |
| 眼镜 不造解绑 | demo 不调用解绑 | 我们也不造（该 AAR 无 unsubscribe） | ✅ 一致 |

改动文件：`transport-rokid`（`CxrBackend.kt` 的 client 层发送 + `CxrCustomCmdSubscription.kt` 的 client 层入站 +
`CxrInboundPayload.kt` 的 Caps 解析）、`glasses-app`（`RokidBridgeEndpoint.kt` 的 `MsgCallback` 与回包 Caps 形状）。

## 二、更正：此前"眼镜→手机被否定"的结论作废（作为对厂商设计的判断）

`2026-10-08-p2-t18-return-channel-refuted.md` 记录的结论是"眼镜→手机自定义命令方向不可用"，并把 T17 的
self-built uplink 负结果当作同一件事的证据。**按 demo 逐行对照后，这个判断站不住**：

- T17 那次负结果，以及今天 PONG 的负结果，**都是用 demo 不使用的手机侧注册（session 层）+ 我们自创的载荷形状**
  测出来的。缺陷就在这两处，且都是**我们与 demo 的偏离**；
- 也就是说：**眼镜→手机方向从未用与 demo 一致的双端代码测过**。它不能被判为"厂商设计不可用"。
- 按 D41，该方向**不再需要实机验证**——demo 一致即认定正确。本文件第一节的对齐即为其正确性依据（结构一致性），
  而不是一次通道往返的观测。

**保留原文件不改**（证据不追改），本文件为其更正与替代说明。

## 三、TDD 简化（按 D41）

原则：**demo 定义的交互不再由本项目"重新推导"**。为此删掉了上一轮为"往返通道"搭的脚手架断言：

| 文件 | 前 | 后 | 说明 |
|---|---|---|---|
| `CxrInboundPayloadTest` | 5 | **3** | 只留路由真正需要的：音频帧 magic 识别、JSON 载荷接受、垃圾字节拒绝；Caps 分支是 demo 形状，按 D41 取为给定（且 `Caps.fromBytes` 是 native，JVM 测不到） |
| `CxrCommandDeliveryWiringTest` | 6 | **3** | 只留"装在哪一层"（装错是静默的）、取不到 client 时的回退、随连接释放；路由细节由既有 `BinaryChannelRoutingTest` 覆盖（其夹具现在通过 `rig.deliver` 走**实际生效的那一层**，覆盖没有减少） |

被删的 5 条（二进制投递、相关性命中、消费者异常等）并非不再覆盖：它们断言的是 `CxrBackend.handleInbound` 的既有规则，
而 `BinaryChannelRoutingTest` / `CxrBackendTest` 的 12 条用例已经从同一条路径驱动它们。

**保留**：`HintClockSync` 的测试（PING/PONG/SYNC 是**我们自己的协议词表**，不是 demo 的交互形式，D41 不改变它）、
以及握手诊断的 2 条断言（它们证明"失败不会静默"，与 demo 一致性无关）。

## 四、验证与设备状态

- 全量回归：**16 模块 / 760 测试 / 0 失败 / 0 错误 / 0 跳过**；
  `:phone-app:assembleDebug` 与 `:glasses-app:assembleDebug` 均 BUILD SUCCESSFUL。
  演示对齐后的 APK：phone `5DCD4BA2…`、glasses `794A272A…`（本地构建产物）。
- **本轮之后未再操作设备**（D41 明确不需要为此做实机验证）。手机上当前安装的是**上一版**（修复 client 层与 Caps 之前的
  对齐版）；如需在设备上使用最新构建，由操作者自行安装即可，不构成本阶段的验证前置。
- 仍未验证、仍按 D38 推迟的：佩戴可见性、设备端 TTL/断连表现、真实引擎与真实文本环等**产品级**验收（与"交互实现是否正确"是两件事）。

## 五、影响

- **P2 的 T18 交互实现**至此以"与 demo 结构一致"为完成标准，不再挂着"回传通道不可用"的阻塞。
- 之前为了让会话"能收到回包"而准备的替代方案（单向 TTL、声明式映射区间等）**不再需要**，未被实施。
- `P2_MANUAL_VERIFICATION.md` 中 C2/C3 的"握手/ACK 真机验证"一栏据此标注：**交互实现已由 D41 结清**，
  剩余人工/设备项只与产品级可见性有关。
