# 0.4 正式（release）APK 构建与安装冒烟（2026-10-27）

## 结论

- 0.4 **release 变体**已成功构建、zipalign、使用项目既有预览签名签署，并在 K40 上覆盖安装/启动；模型 readiness 到 `ready`，无 FATAL。
- 这是**预览签名**的 release 构建（证书与既有 preview/设备已装版本一致），不是商店/生产发布签名，也不是版本级验收通过。
- D5（KWS 切换后可靠性）仍为 **Partial**；其余未完成项见下。

## 构建

- 分支：`feat/0.4-detached-wake-bundled-models`
- 构建 HEAD：`e0fb11f7`（`build(0.4): enable the signed release APK`）
- 命令：
  - `.\gradlew.bat :phone-app:app:assembleRelease -PbundledModelsDir=E:\edith-0.4-models`
  - `.\gradlew.bat :glasses-app:app:assembleRelease`
- 版本：两端 `versionCode=4`、`versionName=0.4.0-preview`。
- phone release 资源审计：`BUNDLED_MODELS=true: packaged 15 model files and 15 license texts for release (bundle=f06c6a5beaa21ed39dd5ed189e77bc3b078796d53a74436ce549693127455565)`。
- 为让既有 release 构建可执行，本次提交 `e0fb11f7`：
  - 移除两个 main 源文件中**未使用**的 debug-only `DebugShellCapProbe` import（只在 KDoc 提及；debug/release 编译均不受行为影响）；
  - 更新 `phone-app/app/gradle.lockfile` 的 release 配置项，去掉因配置级 `exclude` 未解析的锁条目（debug 条目保留）；
  - 对 `InvalidFragmentVersionForActivityResult` 做 release lint 精确抑制并注释原因：`MainActivity` 是 `ComponentActivity`，无 Fragment/FragmentActivity，传递的 fragment 1.1.0 未使用。debug/lint 报告仍可见，未全局关闭 lint。

## 产物

| 文件 | 大小 | SHA-256 | 证书 SHA-256 |
|---|---:|---|---|
| `E:\edith-0.4-builds\EDITH-phone-0.4.0-preview-release.apk` | 1,090,267,283 B | `6B6B262176EA34CD2BE4A21A90B68606211FE9135F3D3D9D9E4C3B5D653DDA12` | `e4c5b0a4…d7e4`（Android Debug，项目预览签名） |
| `E:\edith-0.4-builds\EDITH-glasses-0.4.0-preview-release.apk` | 24,490,325 B | `C90DE62BA935F6FB48DBEABDA06F3863AC72AAD84A9D716A2A4E012967BEC6FE` | `7936eb47…3921`（Android Debug，眼镜预览签名） |

两个 APK 均为 release 变体 + zipalign + V3 签名；签名与既有预览/设备已装版本一致，可 `install -r` 覆盖安装（数据保留）。

## K40 安装冒烟（2026-10-27 09:58–10:00）

- `adb -s 2696813b install -r EDITH-phone-0.4.0-preview-release.apk`：**Success**；设备 `base.apk` 拉回后 SHA-256 与本机一致（`6B6B26…3DDA12`）。
- 启动后：`P4_MODELS state=ready stage=all bundle=f06c6a5b…5565`；logcat 无 `FATAL EXCEPTION`/`AndroidRuntime` 崩溃。
- UI：新 composer（「发消息…」胶囊、麦克风/发送动作位）正常绘制；截图 `assets/2026-10-27-release/phone-release-home.png`。
- 无障碍服务更新后被系统从 enabled 列表移除，已读→追加→验证，4 项 bound、Crashed services 空。
- 设备保持：release APK、detach OFF、session Idle、无 SessionService/FGS、原生 1080×2400/440；未 push/tag/merge。

## 未完成/未测（正式验收前需知）

1. **D5 KWS 切换后可靠性 Partial**：中文合成 `wake13g` 一次 5/5，但同 APK 冷启动脱离 0/3；弱素材 1/5；真实人声/召回率/多轮切换/长稳 `NotMeasured`。
2. **D11 Partial**：权限拒绝路径 Verified；live session + revoke 被平台杀进程 NM；mic busy/录音初始化失败 NM。
3. **D1/D2 Partial**：release APK 已构建/安装，但**干净安装**（清数据首启）、release provisioning、受控空间不足、APK 字节可复现仍未测；本次为覆盖安装，模型目录沿用既有 materialization。
4. **D5b keyguard NM**：只验证 screen-off；安全锁屏未验证。
5. **D6**：主动辅助抑制在更早构建 Verified，最终 release 未重跑。
6. **D12 NM**：真实人声/CXR 声学、长稳/功耗/RSS、多轮眼镜↔脱离切换。
7. **输入行**：实际视觉/键盘避让/软键盘交互只做了编译与安装冒烟，待人工验收。
8. **原生 1080×2400 开关视口**：探针无滚动原语，之前用临时逻辑显示触达；最终 release 未在原生视口验证。
9. **精确 ASR fault code**：`P3_PIPELINE` 已补，待下一次真实 fault 命名。
10. **签名**：当前是 preview debug 证书，不是生产/商店签名；如要对外发布需单独的生产签名与发布决策。
11. **KWS reset 并发**：`AudioIngressLoop.start` 的 reset 在 loop lock 外、scheduler 取消不 join in-flight tick；代码硬化项，未观测到设备故障，但建议真实长稳前修。
12. **未 push / 未 tag / 未 merge**；人工验收后再决定发布动作。
