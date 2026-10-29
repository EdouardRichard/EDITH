# 以 shell UID（Shizuku 通道）能给 LLM agent 提供哪些原子操作 — K40 实测调研

- 日期：2026-10-16
- 设备：Redmi K40（`M2012K11AC` / alioth），序列号 `2696813b`，MIUI `V816.0.10.0.TKHCNXM`（`ro.miui.ui.version.name=V816`），Android 13 / API 33，构建 `user`，区域 `cn`
- 屏幕：1080×2400，密度 440；调研期间 `mWakefulness=Awake`、无锁屏（`mIsShowing=false`）
- 仓库：`D:\Project_new\EDITH`，分支 `feat/0.21-agentloop-context-redesign`，HEAD `f1102c8`
- 已装 Shizuku：**App 13.6.0**（`versionCode=1086`，`moe.shizuku.privileged.api`）；项目 pin 的 API/provider 是 **13.1.5**
- 记账口径：**实测** = 本次在 K40 上亲自跑通并贴出真实输出；**文档** = 官方/厂商文档陈述（给 URL）；**未验证推断** = 无实测支撑，明确标注
- 本报告**不含**任何短信/联系人/账号正文，只记行数与枚举

---

## 1. 一句话结论

**以 shell UID（Shizuku 通道）能提供的原子操作，粒度等于"你在 PC 上敲 `adb shell` 能做的事"** —— 输入注入（`input tap/swipe/keyevent/keycombination`）、跨 App 界面读取（`uiautomator dump` / `screencap`）、包与应用管理（`pm`/`am`）、系统设置（`settings get/put`、`cmd`、`appops`）、内容提供者读取（`content query` 可读短信/联系人/日历/通话记录）、诊断（`dumpsys` 286 个服务、`logcat`、`netstat`/`ip`/`ss`/`iptables`/`nc`/`curl`）**全部可用**；但有**三条硬边界**：① `input text` **只能 ASCII**（中文实测必失败，且补救方案在一台被 MIUI 禁止安装应用的设备上装不进去）；② shell UID **不能安装/卸载应用**、**不能读别的 App 私有数据**（`/data/data/...` → `Permission denied`）；③ `svc power reboot|shutdown` 这类**不可逆**能力确实在 shell UID 手里，若整条 shell 透传给模型即为越权。

> **⚠ 上一段的证据范围（2026-10-16 首轮）**：那张能力表是在 **PC 侧 `adb shell`** 上实测的，首轮**未能**在设备内 Shizuku 路径上复跑。
> **同日补测（§3.3）**已把设备内路径打通，实测到：远端 `sh -c` 子进程身份 **`uid=2000(shell)` / `u:r:shell:s0`**（与 PC 逐字节相同）、
> 单次 `sh -c` 往返 **26–37 ms**、**1 228 800 B 的单次 binder 回包完整成功**（原"~1 MB 上限"的推断**被证伪**）、
> 以及**跨 App `uiautomator dump` 成功（100 节点 / 34 943 B）**。
> **但这仍不等于"表里每一行都在设备内复跑过"** —— 未复跑的部分明确列在 **§3.3.8**，不得读成已验证。

---

## 2. 能力清单表

> 时延口径：从开始到本地拿到结果的**端到端墙钟**（含 USB/adb 传输）。`adb shell true` 的传输开销实测 **≈52 ms/次**（5 次共 259 ms），已含在下列数字里。除注明外 n=1 或 n=10。

### 2.1 输入注入

| 能力 | 命令 / API | 前提 | 证据（实测输出 / 文档） | 可靠性边界 | 耗时量级 | 现有代码是否已用 |
|---|---|---|---|---|---|---|
| 文本输入（ASCII） | `input text <ascii>` | 有焦点输入框 | **实测 OK**：`input text edith` → `exit=0`，随后 dump 的 `ClearableEditText` 文本变为 `ediedith`（该次 dump 读到的是上一次操作后的状态，见 §7 注） | 只能 ASCII；**退出码只证明事件被 InputManager 接受，不证明屏幕真的变了**（T3a 结论） | 40–150 ms | **已用**：`tools/p3-agent-probe` 的 `type_text`；K40 端到端实测通过（`2026-10-08-p3-agent-end-to-end.md` 第 8 步 `type_text "wifi" ok`） |
| 文本输入（**非 ASCII / 中文**） | `input text 你好世界` | — | **实测失败**：`exit=255`，`java.lang.NullPointerException: Attempt to get length of null array at com.android.server.input.InputShellCommand.sendText(InputShellCommand.java:325)` | **不可用**。根因已取证：`/system/usr/keychars/Virtual.kcm` 共 631 行、**含非 ASCII 的行数 = 0**（该文件是 `input text` 用来把字符映射成按键事件的虚拟键盘字符映射） | — | **已按"不可用"处理**：生产探针显式拒绝 `value.any { it.code > 127 }` |
| 点击 | `input tap <x> <y>` | 屏幕亮 | **实测 exit=0**（命令被接受）；**效果**在 P3.0/T3a 已复核：`tap_element` 按 `uiautomator dump` 的 `bounds` 中心点击，Settings 搜索框真的拿到焦点（`2026-10-08-p3-agent-end-to-end.md` 第 4/7 步） | 坐标是**盲点**：dump 与 tap 之间有窗口变化就会点错。`tools/p3-agent-probe` 因此每次 tap 前重读 dump，节点不在就拒绝 | ~50–150 ms | **已用**（探针 `tap_element`）；**生产 `phone-app` 未用** |
| 滑动 | `input swipe <x1> <y1> <x2> <y2> [ms]` | 屏幕亮 | **实测**：`input` 帮助列出该子命令（见 §9 复现第 1 条）；T3a 判为 `INCONCLUSIVE`（退出码不能证明屏幕变化） | 同上，效果需状态复核 | ~50–200 ms + 时长 | 未用 |
| 拖拽 | `input draganddrop <x1> <y1> <x2> <y2> [ms]` | — | **实测**：出现在 `input` 帮助里（API 33 新子命令） | 同上 | 未单独计时 | 未用 |
| 按键 | `input keyevent <KEYCODE_*>` | — | **实测全部 exit=0**：`KEYCODE_HOME` 150 ms、`APP_SWITCH` 56 ms、`BACK` 91 ms、`WAKEUP` 85 ms、`VOLUME_UP` 252 ms、`VOLUME_DOWN` 236 ms、`MUTE` 102 ms、`BRIGHTNESS_UP` 111 ms、`MEDIA_PLAY_PAUSE` 52 ms、`CAMERA` 95 ms、`SLEEP` 92 ms；`KEYCODE_PASTE` exit=0（110 ms） | `KEYCODE_SLEEP` 实测**真的关了屏**（随后 `mCurrentFocus=null`），必须配 `KEYCODE_WAKEUP` 才回得来。`CAMERA` 退出 0 但仍停在 Settings —— **退出码不等于动作发生**，这条在按键上同样成立 | 52–252 ms | **已用**：探针 `press_key`（HOME/BACK/ENTER/DEL）；生产未用 |
| 长按 / 双击变体 | `input keyevent --longpress <code>` / `--doubletap` | — | **实测**：`--longpress KEYCODE_DEL` → `exit=0` | — | ~90 ms | 未用 |
| 组合键 | `input keycombination [-t ms] <code1> <code2>` | — | **实测**：`input keycombination KEYCODE_CTRL_LEFT KEYCODE_A` → `exit=0`（API 33 起支持） | — | ~90 ms | 未用 |
| 原始触摸事件 | `input motionevent <DOWN\|UP\|MOVE\|CANCEL> <x> <y>`、`input press`、`input roll` | — | **实测**：出现在 `input` 帮助里 | — | 未计时 | 未用 |
| 指定显示器 | `input -d <DISPLAY_ID> <cmd>` | — | **实测**：出现在 `input` 帮助里 | — | — | 未用 |
| 输入来源 | `input <source> <cmd>`（touchscreen/keyboard/mouse/stylus/gamepad/dpad/…） | — | **实测**：帮助列出 10 种 source | — | — | 未用 |

### 2.2 界面读取

| 能力 | 命令 / API | 前提 | 证据（实测输出 / 文档） | 可靠性边界 | 耗时量级 | 现有代码是否已用 |
|---|---|---|---|---|---|---|
| 读界面结构 | `uiautomator dump <path>` + `cat` | 屏幕亮；**串行** | **实测**：Settings 主页面 39 节点 / 13 912 B XML；"所有应用"页面 96 节点；搜索页含 `miuix.androidbasewidget.widget.ClearableEditText text='开发' focused=true bounds=[190,91][861,214]`。**同一能力在设备内路径上也已实测成功**（`com.android.settings/.MainSettings` → **100 节点 / 34 943 B**，§3.3.5） | **能读别的 App 的界面**（实测读到 `com.android.settings` 与 `com.miui.home`）。⚠ **但 `uiautomator` 拒绝读"驱动它的那个窗口"**（`null root node returned by UiTestAutomationBridge`，§3.3.5）——探针读不了自己。stderr 恒定有 MIUI 噪声（`theme_config` ENOENT 栈），**但 XML 正常产出**；`dump` 的退出码与 stderr **不能**用来判成败，必须看文件是否存在/可解析 | **2 283 ms**（写远端）+ 69 ms（cat） | **已用**：探针 `screen_text`/`find_elements`；**生产 `phone-app` 未用**（grep 命中 0） |
| **并发 dump** | 两个 `uiautomator dump` 同时跑 | — | **实测失败**：两个并发调用总耗时 2 417 ms，结果**只有第一个产出文件**（`/sdcard/conc2.xml: No such file or directory`，第一个 12 590 B 正常） | **uiautomator 是单例**：并发 dump 会互相吞掉。任务循环里必须串行化，且不能靠"命令返回了"判断成功 | — | 未处理 |
| 截屏 | `screencap -p <path>`（或 `exec-out screencap -p`） | — | **实测**：写文件 321–398 ms；`adb pull` 得到 **203 804 B** 的合法 PNG（魔数 `89 50 4E 47 0D 0A 1A 0A`），`read_image` 成功解析为 1080×2400 | 快，但**二进制必须走 `exec-out` + 二进制安全重定向**：本次用 PowerShell `> file` 得到的是 `FF FE FD FF …`（UTF-16LE BOM 污染），文件损坏。正确路径是 `adb pull` 或 `exec-out` + 原始字节写 | 321–398 ms | 未用（生产 grep 命中 0） |
| 无障碍读屏（对照项） | `AccessibilityService.getRootInActiveWindow()` | **用户必须在系统设置里手开无障碍** | **实测状态**：`settings get secure accessibility_enabled = 0`、`enabled_accessibility_services = null` ⇒ 本机当前**没有**可用的无障碍通道 | 结构比 uiautomator 丰富（能语义点、能滚动、能读 Compose 树），但**前提是用户手动开启**；shell UID 能**写**这个白名单（见 2.4），但那是**替用户打开一个能读全屏的通道**，属高危 | 未测（未开） | 生产侧有 a11y 通道设计；探针 `phone-accessibility-probe` 已存在但**当前未启用** |

### 2.3 应用与包管理

| 能力 | 命令 / API | 前提 | 证据（实测输出 / 文档） | 可靠性边界 | 耗时量级 | 现有代码是否已用 |
|---|---|---|---|---|---|---|
| 列出包 | `pm list packages [-d\|-e\|-s\|-3\|-u\|-f\|-U] [filter]` | — | **实测**：全量 58 ms；单包 48 ms；`pm list packages -d`、`-3`、`-u` 均在 `cmd package` 帮助中 | 稳定，是可靠的状态读取手段 | 48–58 ms | **已用**：`ShizukuTargetState.installed()` / `.disabled()` |
| 启动 Activity | `am start [-W] [-n comp] [-a action] [-d data] [-e/-es/-ei/-el/-ez/-ef/-eu extras] [-f flags] [-p pkg] [-S] [-D]` | — | **实测**：`am start -W -n com.android.settings/.Settings` → 203 ms，输出 `Status: ok / LaunchState: WARM / TotalTime: 117 / WaitTime: 118` | `-W` 让命令**等到启动完成**并把 `TotalTime/WaitTime` 打出来 —— 这是"启动成功了吗"的一个可用信号，比裸 `am start` 强。但 `am start` 退出 0 不代表目标页面就是你想要的那个（MIUI Settings 实际落到 `.MiuiSettings`） | 105–203 ms（+ 应用冷启时间） | **已用**：`IntentActionExecutor` 走 intent 通道；探针 `open_app` |
| 强制停止 | `am force-stop [--user U\|all\|current] <pkg>` | — | **实测**：`cmd activity` 帮助列出；T3a 实测 `OK`（`2026-10-08-p3-t3a-shell-uid-matrix.md`） | 可逆（用户可再启动），但**会丢未保存状态** | ~100 ms | 未用（生产 grep 命中 0） |
| 杀进程 | `am kill [--user …] <pkg>`、`am kill-all` | — | **实测**：`cmd activity` 帮助列出 | `kill-all` 会杀**所有**可杀进程，影响面大 | — | 未用 |
| 清除数据 | `pm clear [--user U] [--cache-only] <pkg>` | — | **实测**：`cmd package` 帮助列出；生产代码在用 | **不可逆**。生产代码的正确做法：执行后重读"包还在不在"+"stdout 含 `Success`"才判成功 | ~150 ms | **已用**（生产 L3） |
| 卸载 | `pm uninstall [-k] [--user U] <pkg>` | — | **实测**：帮助列出 | **不可逆**。生产用 `--user 0` | ~200 ms | **已用**（生产 L3） |
| 停用 / 启用 | `pm disable-user`、`pm disable`、`pm disable-until-used`、`pm enable` | — | **实测**：帮助列出 | 可逆（`enable`）但影响应用可用性 | ~150 ms | **已用**（生产 L4 `disable_app`） |
| 隐藏应用 | `pm hide` / `pm unhide` | — | **实测**：帮助列出 | `hide` 比 `disable-user` 更彻底，恢复要 `unhide` | — | 未用 |
| 挂起 | `pm suspend` / `pm unsuspend` | — | **实测**：帮助列出 | 可逆，但用户可见度低（无提示的"应用打不开"） | — | 未用 |
| 授权 / 撤权 | `pm grant` / `pm revoke <pkg> <permission>` | — | **实测**：帮助列出 `grant [--user USER_ID] PACKAGE PERMISSION`。**退出码在这一对命令上无意义**（T3a：对已默认授予的权限 `pm grant` 仍 exit 0） | **必须读状态复核**：生产用 `dumpsys package <pkg>` 找 `<perm>: granted=true` | ~150 ms + 93 ms 复核 | **已用**（生产 L4 `set_permission`） |
| 安装 | `pm install` / `install-create/write/commit` | — | **实测被拒**：`adb install` → `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]`；改走 `pm install -r --user 0 /data/local/tmp/probe.apk` → **同样失败** | **shell UID 在本机装不了任何 APK**（MIUI 层限制，见 §5）。这同时否掉了"用辅助 IME 解决中文输入"的路 | — | 不在工具面内（**应保持在外**） |
| 只安装给某用户 | `pm install-existing [--user U]` | — | **实测**：帮助列出 | 只对**已存在**的包生效 | — | 未用 |
| 权限标志 | `pm set-permission-flags` / `clear-permission-flags`（`review-required`、`revoked-compat`、`revoke-when-requested`、`user-fixed`、`user-set`） | — | **实测**：帮助列出 | 改 `user-set`/`user-fixed` 会让权限对用户"锁死"，比 grant/revoke 更隐蔽 | — | 未用 |
| 读包详情 | `dumpsys package <pkg>`、`pm dump`、`pm path` | — | **实测**：`dumpsys package edith.phone.a11yprobe` 93 ms | 输出很大，是 **binder 回包上限的风险点**（见 §3） | 93 ms | **已用**：`ShizukuTargetState.permissionGranted()` |
| 广播 | `am broadcast [--user …] -a <action> [-e/-es/--es …]` | — | **实测**：`am broadcast -a edith.survey.NOOP --es k v` → 105 ms，输出 `Broadcast completed: result=0` | 可被任何 App 的静态/动态接收器接走。**是"让某个 App 替我做事"的通用通路**，也是越权面 | 105 ms | 未用 |
| 启动/停止 Service | `am start-service` / `start-foreground-service` / `stop-service` | — | **实测**：`cmd activity` 帮助列出 | `start-foreground-service` 能凭空拉起前台服务 | — | 未用 |
| Activity 栈/任务 | `cmd activity stack …`、`cmd activity task …`、`cmd activity display …` | — | **实测**：`cmd activity` 帮助列出 `stack [COMMAND]`、`task [COMMAND]`、`display [COMMAND]` | 能移动/结束别人的任务栈 —— 界面控制力比 `input` 更"底层" | — | 未用 |
| 重启用户空间 | `am restart` | — | **实测**：`cmd activity` 帮助列出 | **后果极大**（system_server 重启 = 软重启） | — | 未用 |
| 用户管理 | `am get-current-user`、`start-user`、`stop-user`、`pm list users` | — | **实测**：帮助列出 | 多用户场景下可切换/停止用户 | — | 未用 |

### 2.4 系统设置类

| 能力 | 命令 / API | 前提 | 证据（实测输出 / 文档） | 可靠性边界 | 耗时量级 | 现有代码是否已用 |
|---|---|---|---|---|---|---|
| 读设置 | `settings get <system\|secure\|global> <key>` | — | **实测**：40 ms；本次拷回 `screen_brightness=50`、`screen_brightness_mode=0`、`screen_off_timeout=600000`、`accelerometer_rotation=1`、`user_rotation=0`、`volume_ring=5`、`volume_music=5`、`vibrate_when_ringing=1`、`zen_mode=0`、`default_input_method=com.baidu.input_mi/.ImeService` | 稳定。生产把它当"写入后的状态复核"依据 | 40 ms | **已用**：`ShizukuTargetState.setting()` |
| 写设置 | `settings put <ns> <key> <value>` / `delete` / `reset` | — | **实测写入并读回**：`user_rotation 1` → 95 ms 后读回 `1`（已还原 `0`）；`screen_brightness 51` → 106 ms 后读回 `51`（已还原 `50`）；`secure` 命名空间同样可写 | **写入面极大**：`screen_off_timeout`、`accelerometer_rotation`、`zen_mode`（免打扰）、`default_input_method`（换输入法）、`volume_*`、`screen_brightness` 全在其中。`sec` 里还有无障碍白名单（见下） | 95–106 ms | **已用**：生产 L2 `set_brightness`（`settings put system screen_brightness`） |
| **写无障碍白名单** | `settings put secure enabled_accessibility_services <component>` + `accessibility_enabled 1` | — | **实测**：写入 `com.example.nonexistent/.Svc` 后读回**完全一致** ⇒ **shell UID 确实能写这个 key**（随后已还原为 `null`，实测 `after = 'null'`、`accessibility_enabled='0'`） | ⚠ **不是"命令可用"，是"能替用户打开一个读全屏的通道"**。注意：光写 key 还**不够**——被写进去的组件必须真实存在且是可绑定的 `AccessibilityService`，而本机装不了新 APK（§5），所以**这条在本机无法构成可用的读屏方案**；但它对**已装**应用（如仓库里那个探针）是可行的 | ~90 ms | 未用（**建议永不暴露**） |
| `cmd` 子服务 | `cmd <service> <sub>` | — | **实测**：`cmd -l` 列出 **286+** 个可寻址服务（`dumpsys -l` 同为 286）。逐个验证可用性见下 | `cmd <svc>` 无子命令时打印帮助并 **exit 1** ⇒ **"打印帮助 + exit 1" 是 T3a 记录过的假阴性陷阱**（`cmd statusbar` 因此被误判为不可用） | 视子命令 | 部分（生产注释里提到过 `cmd statusbar` 的假阴性） |
| `cmd notification` | `list` / `get` / `post` / `set_dnd` / `snooze` / `allow_listener` … | — | **实测**：帮助全文（`list`、`get <key>`、`post [--help\|flags] TAG TEXT`、`set_dnd [on\|off\|priority\|alarms\|all]`、`snooze`、`allow_listener`）。T3a 实测 `cmd notification list` = `OK` | `set_dnd` 改免打扰、`allow_listener` 改通知监听白名单 —— 前者扰民、后者是**隐私通道** | ~100 ms | 未用 |
| `cmd statusbar` | `expand-notifications` / `collapse` / … | — | **实测**：`cmd statusbar expand-notifications; cmd statusbar collapse` → 122 ms，两次 exit=0（与 T3a 一致） | 可展开/收起状态栏；是"用户可见"的提示手段之一 | 122 ms | 未用 |
| `cmd wifi` | `status` / … | — | **实测**：`cmd wifi status` → `Wifi is enabled` / `Wifi scanning is always available` / `Wifi is connected to "<SSID>"` | ⚠ **`cmd wifi status` 会打印当前 SSID**，属位置/网络隐私。`cmd wifi` 还有连接/断开类子命令（未逐一验证，未执行） | ~120 ms | 未用 |
| `cmd connectivity` | `airplane-mode` 等 | — | **实测**：`cmd connectivity airplane-mode` → `disabled` | 飞行模式可读**也可写**（未执行写）。改写会断网、断眼镜链路 | ~110 ms | 未用 |
| `cmd appops` | `get` / `set <pkg> <op> <mode>` | — | **实测**：`cmd appops get <pkg>` 打印 `READ_CALENDAR: ignore`、`POST_NOTIFICATION: ignore`、`READ_CLIPBOARD: foreground`、`WRITE_CLIPBOARD: foreground`…；`cmd appops set <pkg> VIBRATE ignore` → exit 0，读回 `VIBRATE: ignore`，**已还原为 `default`** | 这是"比权限更细一层"的开关（`ignore`/`allow`/`foreground`/`default`）。能静默剥夺或放开能力，且**用户界面上看不到** | ~90 ms | 未用 |
| `cmd deviceidle` | — | — | **实测**：`Device idle controller (deviceidle) commands:`（有实现） | 可强制进入/退出 doze，影响后台与唤醒 | — | 未用 |
| `cmd jobscheduler` | — | — | **实测**：`Job scheduler (jobscheduler) commands:`（有实现） | 可强制跑/取消别人的 job | — | 未用 |
| **无 shell 实现**的 cmd 服务 | `cmd clipboard` / `cmd aiinputtext` / `cmd netstats` / `cmd package_native` / `cmd uri_grants` / `cmd textclassification` | — | **实测**：全部 `No shell command implementation.`（exit 1） | 这些服务存在但**没有 shell 入口** ⇒ 别把"服务在 `cmd -l` 里"读成"能用" | — | 未用 |
| `svc`（被 MIUI 裁剪） | `svc power` / `usb` / `nfc` / `system-server` | — | **实测**：`svc` 只列出 **4** 个子命令。`svc power` 帮助实测含 `stayon [true\|false\|usb\|ac\|wireless]`、**`reboot [reason]`**、**`shutdown`**、`forcesuspend [t]` | ⚠ **`svc power reboot` / `shutdown` 在 shell UID 下就存在**（本次**未执行**，属不可逆，明确不做）。注意：本机 `svc` **没有** `wifi`/`data`/`bluetooth`/`location` 等常见子命令 —— 与 AOSP/most 设备不同 | ~100 ms | 未用 |

### 2.5 文件与内容

| 能力 | 命令 / API | 前提 | 证据（实测输出 / 文档） | 可靠性边界 | 耗时量级 | 现有代码是否已用 |
|---|---|---|---|---|---|---|
| 读短信 | `content query --uri content://sms --projection _id` | — | **实测**：`exit=0`，**3 行**（只数行数，未读任何内容） | 🔴 **隐私敏感**：能读短信。若把 `content query` 泛化暴露给模型，等于把短信/通话记录交给它（与 D53"只记计数/id、不记正文"的约束直接冲突） | 1 145 ms（主要是每次都要起一个 JVM） | 未用 |
| 读会话 | `content://mms-sms/conversations` | — | **实测**：`exit=0`，**3 行** | 同上 | 1 119 ms | 未用 |
| 读联系人 | `content://com.android.contacts/contacts` | — | **实测**：`exit=0`，**0 行**（本机该 URI 无记录，provider 本身可访问） | 🔴 隐私敏感（本次为空不代表不可读） | 1 167 ms | 未用 |
| 读日历 | `content://com.android.calendar/calendars` | — | **实测**：`exit=0`，**2 行** | 🔴 隐私敏感 | 1 154 ms | 未用 |
| 读通话记录 | `content://call_log/calls` | — | **实测**：`exit=0`，**1 行** | 🔴 隐私敏感 | 1 151 ms | 未用 |
| 写内容提供者 | `content insert / update / delete` | — | **文档 + 命令存在**（`content` 命令的帮助列出；本次**只读了 `--projection _id`，未执行任何写**） | 🔴 写别人的数据库同样不可逆。**未实测** | — | 未用 |
| `content` 的 where 语法 | `--where` | — | **实测**：`content query --uri content://settings/system --where "name=screen_brightness"` → **报错** `IllegalArgumentException: Supported SQL: uri …/some_property with null where and where args; uri …/some_table with query name=? and single name as arg; uri …/some_table with query name=some_name and null args` | `content` 的 `--where` **不是 SQL**，只支持上述三种形状。别按 SQL 直觉写 | — | — |
| `run-as` 调试包 | `run-as <pkg> <cmd>` | 目标必须是 **debuggable** | **实测**：`run-as edith.phone id` → `uid=10276(u0_a276) … context=u:r:runas_app:s0:c20,c257,c512,c768`（成功，因为 `edith.phone` 是 debug 包）；`run-as com.android.contacts id` → `run-as: package not debuggable: com.android.contacts`（exit 1）；`run-as com.android.settings id` → `package not an application`（exit 1） | **不是通用提权**：只对 debuggable 包生效，且 SELinux 进 `runas_app` 域（带 per-app category），读不到别的 App 数据 | ~60 ms | 未用 |
| 读别 App 私有数据 | `cat /data/data/<pkg>/…` | — | **实测被拒**：`cat /data/data/com.android.settings/shared_prefs/*.xml` → `exit=1`，`Permission denied`（与 T3a 一致） | 与官方文档一致：Shell 不能访问 `/data/user/0/<package>`（见 §9 文档链接） | — | 未用 |
| `/sdcard` 读写 | `echo > /sdcard/…` | — | **实测**：写+读+删一轮 158 ms，`ls` 得 `-rw-rw---- 1 root everybody 6` | 可读写；**但这是全局共享存储**，别把用户数据落这儿 | 158 ms / 轮 | 探针用它放 dump（`/sdcard/edith_agent_dump.xml`） |
| 读 `/data/local/tmp` | `ls` / 写入并 `sh` 执行 | — | **实测**：目录 `drwxrwx--x shell shell` 可写；`sh /data/local/tmp/t.sh` → 实际执行成功 | 可执行自己写进去的脚本（**本机装不了 APK，但能跑脚本**） | — | 未用 |

### 2.6 网络与诊断

| 能力 | 命令 / API | 前提 | 证据（实测输出 / 文档） | 可靠性边界 | 耗时量级 | 现有代码是否已用 |
|---|---|---|---|---|---|---|
| `dumpsys` | `dumpsys <service>` / `dumpsys -l` | — | **实测**：`dumpsys -l` 列出 **286** 个服务；`dumpsys package` 93 ms；`dumpsys window`（读焦点）~200 ms；`dumpsys power`、`dumpsys window policy` 均可用 | 输出体量巨大（`dumpsys power` 一次刷出数万行）⇒ **必须子串过滤**，否则一次调用就能吃光上下文；也是 binder 回包上限的风险点 | 93–300 ms（未过滤时更久） | **已用**：生产 `dumpsys package` 读权限状态 |
| 读前台窗口 | `dumpsys window \| grep mCurrentFocus` | — | **实测**：`mCurrentFocus=Window{… edith.phone/edith.phone.MainActivity}`、`mFocusedApp=ActivityRecord{…}` | 判断"现在在哪个 App"的可靠手段（比截图便宜） | ~200 ms | 未用（生产 grep 命中 0） |
| `logcat` | `logcat -d -t <n>` | — | **实测**：`logcat -d -t 200` → **1 253 ms / 204 行** | 慢且**内容含其他应用文本**（隐私面）。必须严格限制行数/标签/时间窗 | 1 253 ms / 200 行 | 生产有 4 处 `logcat` 字样（读自身日志），无 shell 通道使用 |
| 套接字/连接 | `netstat -an` | — | **实测**：`netstat` 存在，`-an` 输出 1 545 行 | 行数极大；含连接对端 IP，属隐私 | ~200 ms+ | 未用 |
| `ss` | `ss -lnp` | — | **实测**：`ss` 存在；但 `ss -lnp \| grep …` 在本机**段错误**（`Segmentation fault`） | ⚠ 该二进制在本机不稳，不建议依赖 | — | 未用 |
| `ip` / `ifconfig` | — | — | **实测**：两者均 `PRESENT` | — | — | 未用 |
| `ping` | — | — | **实测**：`PRESENT` | — | — | 未用 |
| `nc` | — | — | **实测**：`PRESENT`（可开 TCP/UDP 连接 —— **对外发数据的通路**） | ⚠ 越权面：可绕过 App 的网络策略直接发字节 | — | 未用 |
| `curl` | — | — | **实测**：`PRESENT` | ⚠ 同上：**能直接把数据 POST 到任意地址**，是"零网络请求"类约束的绕过口 | — | 未用 |
| `iptables` | — | — | **实测**：`PRESENT`（JNI 能力是否够未测；**未执行任何规则变更**） | ⚠ 改防火墙 = 影响全机连通性 | — | 未用 |
| **抓包** | `tcpdump` | — | **实测不存在**：`tcpdump: inaccessible or not found` | **本机无抓包工具**。`nc`/`curl` 在，但"抓别人的流量"这条路在本机不通（VPN/`cmd netstats` 也不给包内容） | — | 未用 |
| `nslookup` / `wget` | — | — | **实测**：均 absent | 用 `ping`/`curl`/`nc` 替代 | — | 未用 |

---

## 3. "PC 上 `adb shell`" 与"设备内 Shizuku（shell UID）"的差异

### 3.1 先说结论：**能做什么，两者一致；差的是通道形态与三个工程细节**

**已核实的等价性（文档级，权威）**：Shizuku 官方 README 明确写：

> "User Service is like Bound services … the service runs in a different process and **as the identity (Linux UID) of root (UID 0) or shell (UID 2000, if the backend is Shizuku and user starts Shizuku with adb)**."
> "You can use `Shizuku#getUid()` to check your privilege, **for ROOT it returns 0, for ADB is 2000**."
> — [Shizuku-API README](https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md)（**文档**）

配合本次**实测**的 PC 侧身份：`adb shell id` → `uid=2000(shell) gid=2000(shell) … context=u:r:shell:s0`。

⇒ **同为 uid 2000 + `u:r:shell:s0`，因此 §2 里"实测可用"的每一条命令，在设备内 shell UID 下应当同样可用**。这是**推断**（基于 uid/SELinux 等价 + 官方文档），**不是**在设备内实测出来的 —— 见下。
> **2026-10-16 补测更新**：该推断的**身份前提**已在设备内实测证实（`sh -c` 子进程 = `uid=2000(shell)` / `u:r:shell:s0`，§3.3.2），
> 且跨 App 读屏、`am start`、截屏、base64 等**具体能力**也已在设备内跑通（§3.3.4/3.5.5）。
> 但**不是**"§2 每一行都已逐条在设备内复跑"——**逐条复跑未做**，仍属未实测，见 §3.3.8。

### 3.2 ⚠ **本次（2026-10-16 首轮）未能在设备内实测，原因如下（重要）**

> **补测状态（同日 2026-10-16 01:26–01:29）**：本节记录的三条阻塞中，**前两条已完全解除**（server 在运行、探针装上并跑通），**第三条已部分解除**（回包上限**已实测到 ≥ 1.2 MB 未截断**，但**具体上限值仍未探到**）。结论见新增的 **§3.3**。本表**保留为首轮当时的实测快照**，不得据此认为设备内路径"仍不可测"；同样，§3.3 的实测也**不推翻**本表——它记录的是首轮执行当时的真实状态。

| 事实 | 实测证据 |
|---|---|
| Shizuku **App 已装**（13.6.0），但 **server 未运行** | `service list` 里**没有任何** shizuku/rikka binder（总 293 个 binder）；`ps -A` 只有 App 进程 `u0_a270 moe.shizuku.privileged.api`，**没有 uid 2000 的 shizuku server 进程** |
| 没有 start 脚本可代跑 | `/data/local/tmp/` 下**无** `shizuku*`、无 `start.sh`；App 私有目录 `/data/data/moe.shizuku.privileged.api/` → `Permission denied` |
| 非 root 设备上 server 必须**启动一次**（重启后失效） | 官方 README：**"On non-rooted devices, Shizuku needs to be manually restarted with adb every time on boot."**（文档） |
| 我写的探针**装不上去** | `adb install` → `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；`pm install --user 0` → **同样失败**（详见 §5） |
| `Shizuku.newProcess` 在 pin 的 13.1.5 里**不存在** | `javap` 实测：`rikka.shizuku.Shizuku` 的公开方法里**没有** `newProcess`（只有 `getBinder`/`transactRemote`/`getUid`/`getVersion`/`getSELinuxContext`/`bindUserService`/`peekUserService`/`unbindUserService`/`checkSelfPermission`/`requestPermission`/`exit`…）。jar 里**有** `rikka/shizuku/ShizukuRemoteProcess.class`，但**没有**公开工厂方法。与项目注释一致："the API that used to expose a remote process is **private** in 13.1.5 (verified with `javap` on the pinned artifact)" |

⇒ **"设备内 Shizuku 路径"在本报告里归入"未实测"。** 仓库里已有的 `tools/p3-shizuku-shell-probe`（临时、未提交）已经写好并在本机 `assembleDebug` 成功，一旦 Shizuku server 被启动且安装通道打开即可直接跑出数据；本轮不具备该前提。

### 3.3 设备内 Shizuku 路径实测（2026-10-16 补测）

> **这一节就是首轮 §3.2 缺的那块。** 前提变化：① Shizuku **server 已在运行**；② MIUI **放行了探针安装**（首轮被 `INSTALL_FAILED_USER_RESTRICTED` 拒装）。
> 探针：`tools/shizuku-shell-probe/`（untracked、临时，**测毕已卸载**），包名 `edith.survey.shizukuprobe`，pin 的 `dev.rikka.shizuku` 与生产同源。
> 记账口径同全文：下面每个数字都是**实测**；凡"没测到"的，写在 §3.3.8 里并标未实测。

#### 3.3.1 结论一句话

> **设备内 Shizuku 通道可用，且比 PC 的 `adb shell` 更省一次 USB 往返**：远端命令身份是 `uid=2000(shell)` / `u:r:shell:s0`（与 PC 完全一致）；
> 一次 `sh -c` 往返实测 **26–37 ms**（PC 侧固定传输开销约 52 ms）；**单次 binder 回包在 1.2 MB 时仍然完整成功**——即"1 MB 上限"这个担忧在本机上**被实测证伪**，`screencap`/大 `dumpsys` 的体积不走被截断的那条路。

#### 3.3.2 远端进程身份（`IDENTITY` 组，实测）

跑在 Shizuku 起的 UserService 里、由 `sh -c` 子进程回答：

```
Process.myUid()  = 10278  (2000 = shell, 0 = root, 10xxx = app)
Process.myPid()  = 22929
Process.myUserHandle() = UserHandle{0}
--- id ---
uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log),1011(adb),1015(sdcard_rw),
1028(sdcard_r),1078(ext_data_rw),1079(ext_obb_rw),3001(net_bt_admin),3002(net_bt),3003(inet),
3006(net_bw_stats),3009(readproc),3011(uhid),3012(readtracefs) context=u:r:shell:s0
--- /proc/self/status ---
Uid:	2000	2000	2000	2000
Gid:	2000	2000	2000	2000
CapPrm:	0000000000000000
CapEff:	0000000000000000
CapBnd:	00000000000000c0
Seccomp:	0
--- SELinux ---
Enforcing
u:r:shell:s0
```

**两条要分开读，这是本节最容易读错的地方：**

| 层次 | 实测值 | 含义 |
|---|---|---|
| UserService **进程**自身（`Process.myUid()`） | **10278**（= 该 App 的 uid，`u0_a278`） | Shizuku 把 UserService 作为一个**应用进程**拉起来；它的 uid **不是** 2000 |
| 该进程 **`sh -c` 出来的子进程** | **2000(shell) / `u:r:shell:s0`** | **命令真正跑在什么身份下** —— 与 PC `adb shell` **逐字节相同** |

⇒ 所以 §2 里在 PC 侧实测可用的整张能力表，在设备内路径下**有相同的执行身份作担保**；而"服务类自己不是 shell 进程"这一点，意味着**不能用 `Process.myUid()` 去猜命令权限**，也不能指望服务类里直接调用的 Android API 拥有 shell 权限（`Context` 相关限制见 §3.5）。

#### 3.3.3 往返延迟分布（`LATENCY` 组，实测，n=10 × 3 轮）

每轮都是 `shell.run("id > /dev/null 2>&1")` 的**调用侧墙钟**（含一次 binder 往返 + 远端 `sh` 进程创建）：

| 轮次 | 10 次样本（ms） | min | median | max |
|---|---|---|---|---|
| 第 1 轮 01:26:13 | 0, 29, 33, 0, 29, 29, 21, 0, 0, 0 | 0 | 17 | 33 |
| 第 2 轮 01:28:09 | 31, 32, 32, 28, 31, 29, 26, 28, 37, 26 | 26 | 31 | 37 |
| 第 3 轮 01:29:04 | 36, 29, 37, 34, 27, 28, 34, 37, 26, 35 | 26 | 34 | 37 |

⚠ **第 1 轮那 5 个 `0` 是测量伪影，不是"0 毫秒"**：该轮发生在 App 冷启后立刻，且宿主当时正挂着 `logcat -s ShizukuSurvey:*` 的实时流；
`System.nanoTime()` 差值被截断到毫秒以下。**只有第 2/3 轮的 26–37 ms 可以当作稳定读数**，第 1 轮只用于说明"App 刚起来时会有几毫秒级的抖动"。

**与 PC 侧对比（同为实测，但两次测量的时点/负载不同，只作量级参考）**：PC `adb shell true` ≈ **52 ms/次**（首轮，5 次共 259 ms，含 USB/adb 传输）。设备内路径**省掉 USB 一跳**，26–37 ms 与"更省"的预期一致；但要注意这**不是**同一次实验里的 A/B，见 §3.3.6 第 4 条。

远端环境（同轮实测）：`Linux localhost 4.19.157-perf-g92c089fc2d37 … aarch64 Toybox`，`TMPDIR=/data/local/tmp`。

#### 3.3.4 单次 binder 回包上限（`PAYLOAD` 组，实测）—— **本节最有价值的一条**

命令串很小（`head -c <N> /dev/zero | tr '\0' 'A' | wc -c`），**大输出在远端生成**，整个 stdout 经**一次** binder 事务以单个 `String` 回到调用方：

| 请求体量 | 结果 | 回包证据 |
|---|---|---|
| 16 KB (16 384 B) | **OK** | `exit=0` … `16384` |
| 256 KB (262 144 B) | **OK** | `exit=0` … `262144` |
| 512 KB (524 288 B) | **OK** | `exit=0` … `524288` |
| 768 KB (786 432 B) | **OK** | `exit=0` … `786432` |
| 900 KB (921 600 B) | **OK** | `exit=0` … `921600` |
| **1 MB** (1 048 576 B) | **OK** | `exit=0` … `1048576` |
| **1.2 MB** (1 228 800 B) | **OK** | `exit=0` … `1228800` |

**怎么读这张表（严格口径）：**
- ✅ **实测结论**：**1 228 800 B 的单次回包在本机完整成功**（`exit=0` 且回来的字符串长度恰好等于请求字节数，无截断、无异常）。第 2、3 两轮独立复现同一结果。
- ⚠ **这不是"上限 = 1.2 MB"**：1.2 MB 是本次试探的**最大档**，**再往上没有测**。正确表述是"**上限 ≥ 1.2 MB，且未探到**"。
- ⚠ **不能靠"再调大"来测上限**：探针只把 `wc -c` 的**计数**写进日志，1.2 MB 的正文**从不进 logcat**（进了也必被单条 4 KB 的日志上限截断，反而会得出假结论）。

**"真实大输出"这一组（同轮实测）——结论比预想复杂，务必照读：**

| 命令 | 实测结果 | 说明 |
|---|---|---|
| `screencap -p /sdcard/tmp-probe-shot.png` | **`exit=0`，文件 24 209 / 24 853 B** | **能截屏**。但体积远小于首轮 PC 侧那张 203 804 B 的 PNG —— 因为本次屏幕内容大片纯色（探针自己的白底界面 + 部分 Settings），PNG 压缩后很小。**别把 24 KB 当"截屏就是 24 KB"** |
| `base64 <那张 PNG> \| wc -c` | **`exit=0`，33 577 B**（≈ PNG 的 1.33 倍，与 base64 的 +33% 一致） | base64 通道可用，膨胀比例符合预期。按首轮 PC 侧 203 804 B 的全屏 PNG 折算，base64 后约 **271 KB** —— **离 1.2 MB 还有 4 倍余量** ⇒ 生产若要传截图，base64 不是瓶颈 |
| `uiautomator dump` 后再 `wc -c` | **失败**：`exit=1`，`wc: /sdcard/tmp-probe-ui.xml: No such file or directory` | 见 §3.3.5 —— 该轮 dump 的目标是**探针自己的窗口**，`uiautomator` 拒绝读，所以文件压根没生成 |

#### 3.3.5 界面读取（`UIREAD` / `UIREAD_FOREGROUND` 组，实测）

**先说一个反直觉的实测结果：`uiautomator dump` 读不到"驱动它的那个窗口"。**

| 组 | 前台是谁 | 实测结果 | 判定 |
|---|---|---|---|
| `UIREAD` | **探针自己**（`edith.survey.shizukuprobe/.MainActivity`） | `ERROR: null root node returned by UiTestAutomationBridge.`，`exit=2`；随后 `grep`/`wc` 均 `No such file or directory` | **失败**——但**不构成"读不到别的 App"的证据**，这是"自己读自己"的固有拒绝 |
| `UIREAD_FOREGROUND` | **`com.android.settings/.MainSettings`**（先 `am start -W -n com.android.settings/.Settings` 顶到前台） | **成功**：`dump_nodes=100`、`dump_bytes=34 943`；`mCurrentFocus` 复核确认前台确为 Settings | ✅ **实测：能读别的 App 的界面** |

⇒ **设备内 shell UID 具备跨 App 读屏能力，与 PC 侧一致**（首轮 PC 侧实测 Settings 39 节点 / 13 912 B）。节点数与体积不同，是因为**两次读的不是同一个页面状态**（本次含更多条目），不是通道差异。
（`am start -W` 同轮实测：`TotalTime: 686 / WaitTime: 688 / Complete`，第 2 轮为 766/769 —— 这条也顺带证明 `am` 在设备内可用。）

🔒 **隐私执行情况（D53）**：`UIREAD` 两组**只记"能否读到 / 节点数 / XML 字节数"**，**读到的一个字都没有写进日志、结果文件或本报告**；探针源码里原本会把 dump 的**前 300 字节 XML 原文**写进证据文件，本次**已改为只记字节数**（改动见 §3.3.7）。

#### 3.3.6 "与 PC 上 adb shell 的差异"—— 哪些被实测证实、哪些没有

| §3.4 的原判断 | 补测判定 | 依据 |
|---|---|---|
| 身份同为 **uid 2000 + `u:r:shell:s0`** | ✅ **实测证实** | `id` 与 `/proc/self/status` 逐字输出（§3.3.2），与首轮 PC 侧 `adb shell id` **完全一致** |
| **每命令延迟**：设备内省掉 USB 一跳、但多一次 binder 往返 ⇒ "理论更省" | ✅ **实测支持，但非同场 A/B** | 设备内 26–37 ms vs PC 侧 ≈52 ms（两次测量时点/负载不同，**不是**受控对比；只能说与预期方向一致） |
| **单次回包受 Binder ~1 MB 上限约束**（原为**推断**） | ⚠ **实测不成立（至少在本机 1.2 MB 以内）** | 1 228 800 B 单次回包完整成功（§3.3.4）。原判断是把"Binder 常见限制"直接外推，**实测证伪** |
| **二进制输出**：`exec-out` 不可用，需 base64（+33%）或写文件再读 | ✅ **实测证实** | base64 33 577 B / PNG 24 853 B ≈ 1.35 倍；且**二进制确实没法直接回**——`IRemoteShell.run()` 只有 `String` 一条路 |
| **大输出（`dumpsys power` 数万行）整段回来很可能撞上限** | ⚠ **实测不成立** | 1.2 MB 未撞上限。但"**数万行到底多大**"本次**没测**（未执行未过滤的 `dumpsys power`）；生产"把命令写成已过滤的小输出"仍是**正确做法**，只是**理由从"怕撞上限"变成"省时延与上下文"** |
| **读不到 `/data/user/0/<pkg>`** | ⚪ **本次未复测** | 身份等价（同为 shell/SELinux）⇒ 沿用首轮 PC 侧 `Permission denied`，但**这一条在设备内没有新的实测** |
| UserService **生命周期**需自己 `System.exit()`、**重启后要重开 Shizuku** | ⚪ **部分实测** | 每轮跑完都实测到 `USER_SERVICE_DISCONNECTED`；"重启后失效"沿用官方文档，**本次未重启设备验证** |
| **UserService 里 `Context` 不工作**（`registerReceiver`/`getContentResolver`） | ⚪ **本次未测** | 探针只用了 `Runtime.exec` 与 `Process`，没有触碰这条 |
| 生产 App **自己写 `/sdcard/Android/data/<pkg>/`**（首轮未列入 §3.4） | 🆕 **实测发现的新差异** | **App 写不了、shell 能写**：App 侧 `File.writeText` → `FileNotFoundException`；同一路径下 shell `mkdir -p` + 写入 + 读回**成功**（§3.3.7）。原因是 App 目标 API 33 且未声明 legacy external storage |

#### 3.3.7 探针源码的两处改动（untracked 临时文件，未提交）

补测**没有**改动产品代码，只动了那个一次性探针（`tools/shizuku-shell-probe/`，untracked）。两处改动**都必须报备**，因为它们影响"这份数据是怎么来的"：

1. **隐私修复（D53 硬约束，必要）**：`ProbeShellService.uiReadBlock()` 原本有一行 `head -c 300 /sdcard/tmp-probe-ui.xml`，会把**前台界面的 XML 原文**写进 logcat 和落盘文件。已改为 `wc -c`（只留字节数）。**若不改，本节就会成为"把屏幕正文写进证据"的违例。**
2. **新增 `UIREAD_FOREGROUND` 组**：原 `UIREAD` 只能读探针自己的窗口，而 `uiautomator` 恰好拒绝读自己的驱动窗口 ⇒ 原设计**无法回答**"能不能读别的 App"。新增一组：先把 Settings 顶到前台，再 dump、计数、删文件。此改动**只增加测量，不改动任何原有组的语义**。
   - 该组第一版有两个计数缺陷并**已在第 3 轮修正**：`grep -c '<node '` 数的是**行**而整份 XML 只有一行（第 2 轮误报 `nodes=1`）；用 `date +%s%N` 算耗时会因**设备墙钟被调整**而得到负值（第 2 轮 `dump_ms=-1649`）。第 3 轮改为 `grep -o … | wc -l`，并**删去**该耗时字段（不计入结论）。
3. **落盘路径的一个实测坑（未改代码，仅记录）**：探针把结果写到 `/sdcard/Android/data/edith.survey.shizukuprobe/files/survey.txt`，靠 `File.mkdirs()` 建目录。**第 2 轮实测失败**：`RESULT_WRITE_FAILED survey.txt FileNotFoundException`。原因是 **App 无法创建 `/sdcard/Android/data/` 下的目录**；而**同一路径 shell 可以**——
   ```
   adb -s 2696813b shell "mkdir -p /sdcard/Android/data/edith.survey.shizukuprobe/files && echo MKDIR_OK"   → MKDIR_OK
   adb -s 2696813b shell "echo x > …/files/t.txt && cat …/files/t.txt"                                      → x  (WRITE_OK)
   ```
   目录由 shell 先建好后，**第 3 轮** `RESULT_WRITTEN` 成功，落盘 **3 478 B** 的 `survey.txt`（`adb pull` 取回，含全部 5 个 block）。
   ⇒ **工程含义**：设备内路径**可以**把结果落盘，但**建目录这一步不能交给 App**；要么用 shell 建，要么走 App 自己的 getExternalFilesDir + `FileProvider`。

#### 3.3.8 本节仍属未实测的部分（**不得读成已验证**）

1. **1.2 MB 以上的回包上限**：未探到；"上限 ≥ 1.2 MB"是实测，**具体数值是未知**。
2. **并发/连续大回包的稳定性**：每轮只按 16 KB→1.2 MB 顺序各跑一次，**没有**做背靠背压力或并发测试；是否会 OOM/被 LMK 杀，**未测**。
3. **`dumpsys power` 这类"数万行"的真实体积与耗时**：**未执行**（会刷巨量文本、且与其它工作线抢设备）。
4. **与 PC 的受控 A/B 延迟对比**：两次读数**不同时点、不同负载**；没有做"同一秒内交替执行"的对照。PC 的 ≈52 ms 是首轮数字。
5. **读别的 App 时的节点覆盖率**（Flutter/游戏/自绘界面、密码框 `password="true"` 是否在树里）：只测了 MIUI Settings，**未测**自绘界面。
6. **被别的窗口遮挡 / 分屏 / 弹窗时的 dump 行为**：未测（本次 `mCurrentFocus` 输出里可见**两个** window 行，与该机多 display 有关，未深究）。
7. **重启设备后设备内路径是否失效**：官方文档说非 root 设备重启后要重新启动 Shizuku；**本次未重启设备验证**（设备正在被另一条工作线使用，重启会打扰它）。
8. **同一通道下的写操作**（`pm clear`/`uninstall`/`settings put` 等由 UserService 执行）：本次**只测了只读命令 + `am start`**，破坏性动作一律没在设备内路径上执行。


### 3.4 两者**确实不同**的地方（逐条）

| 维度 | PC `adb shell` | 设备内 Shizuku（UserService / shell UID） | 依据 |
|---|---|---|---|
| 身份 | uid 2000 / `u:r:shell:s0` | **同** | 官方 README（文档）+ 本次实测 PC 侧 `id` |
| 是否 root | 否 | **否**（`Shizuku.getUid()` 为 2000 时不是 root） | 官方 README（文档） |
| 每命令延迟 | 实测 `adb shell true` **≈52 ms** 传输固定开销 | **实测 26–37 ms**（n=10，第 2/3 轮；§3.3.3）—— 无 USB/adb 一跳，但多一次 binder 往返，**净结果仍是更省**。⚠ 两次测量非同场 A/B | 实测（PC 侧首轮 + 设备内补测） |
| 单次回包体量 | 流式，**无 1 MB 级限制**（`exec-out` 可直出 390 KB PNG） | **全部 stdout+stderr 塞进一个 `String` 跨一次 binder**：实测 **1 228 800 B 完整成功** ⇒ 原"~(Binder ~1 MB) 上限"的推断**在本机被证伪**；**真实上限未探到（仅知 ≥ 1.2 MB）**。⚠ 且二进制**不能**直接回，只能 base64 或写文件再读 | 代码：`IRemoteShell` 只有 `String run(String)`；**上限已实测**，见 §3.3.4 |
| 二进制输出 | `exec-out screencap -p > f.png` 可用 | 需要走 base64（+33% 体积）或写文件再读 —— 实测 base64 膨胀 1.33–1.35 倍，全屏 PNG 折合约 271 KB，**离 1.2 MB 尚有 4 倍余量** | 实测体积（§3.3.4）+ 代码（String 通道） |
| 大输出（`dumpsys power` 数万行） | 直接管道过滤 | 整段回来再过滤 ⇒ 原判断"很可能撞上限"**未获实测支持**（1.2 MB 没撞）；但过滤后的小输出仍是正确姿势（省时延/上下文）。**"数万行"的真实体积本次未测** | 实测（§3.3.4）+ 未测项见 §3.3.8 第 3 条 |
| 流式 / 交互 shell | `adb shell` 是 tty 语义（`logcat` 跟随、`top` 刷新） | **做不到**：官方 README 明确 `newProcess` "lacks tty support, it is not possible to implement an interactive shell with it"；`UserService` 也只是请求-应答 | 官方 README（文档） |
| 生命周期 | 每次 `adb shell` 全新进程 | 需要 `bindUserService`，且 **"the user service process will NOT be killed automatically"** —— 必须在 `destroy()`（事务码 `16777114`）里自己 `System.exit()` | 官方 README（文档）+ 生产代码 `EdithShellService.destroy()` |
| 可用性前提 | PC 连着、USB 调试开着 | **Shizuku server 活着 + 应用已被授权**（`checkSelfPermission`）；非 root 设备**每次重启后要重新用 adb 启动 Shizuku** | 官方 README（文档）+ 补测实测：server 在跑时 `PERMISSION_ALREADY_GRANTED`、`SERVER_REPORTED_API_VERSION=13 SERVER_UID=2000`（§3.3.2） |
| 能否用 Android API | 不能（只有 shell 命令） | **能**：UserService 里可以跑自己的 Java/JNI，且**不受非 SDK API 限制**；但**它不是一个合法的 Android 应用进程**：`Context#registerReceiver`、`Context#getContentResolver` 等**不工作** | 官方 README（文档） |
| SELinux / 应用数据 | 读不到 `/data/user/0/<pkg>` | **同**（同 uid + 同 context） | 官方 README（文档）+ 本次实测 PC 侧 `Permission denied` |
| `settings put secure` 等 | 可用（shell 在允许名单里） | 同 | §2.4 实测（PC 侧）+ uid 等价推断 |
| 与应用安装 | 可用（正常 `adb install`） | **本机上同样被 MIUI 拒**（`pm install` 实测失败） | §5 实测 |
| 多窗口/多屏 | `input -d`、`am display` | 同 | §2.1/2.3 实测（PC 侧） |

### 3.5 一句话差异总结

> **能力面相同（同 uid / 同 SELinux context），通道面不同**：设备内路径省掉 USB 一跳、能跑自己的 Java/JNI、但把"流式大输出"换成了"一次 binder 回包"，并且多了一个**必须在重启后重新启动 Shizuku** 的可用性前提。
>
> **补测修正（2026-10-16，见 §3.3）**：上句里"把流式大输出换成一次 binder 回包"**已实测不是主要代价** —— 单次回包在 **1.2 MB** 时仍完整成功；
> 真正的差异是 ① **二进制必须 base64（×1.33）或写文件再读**、② **设备内那次回包必须是"一次性"的**（没有 `exec-out` 的流式边读边处理）。

---

## 4. 中文输入方案 —— 实测结论

### 4.1 结论：**在当前 K40 上，"以 shell UID 输入中文"这条能力是缺失的**

不是因为命令被禁，而是因为**平台层没有这个能力**，且**补救方案在一台被 MIUI 禁止安装应用的设备上装不进去**。

### 4.2 逐条实测

| 方案 | 结论 | 证据 |
|---|---|---|
| `input text <中文>` | ❌ **不可用** | `exit=255`，`NullPointerException: Attempt to get length of null array at InputShellCommand.sendText(InputShellCommand.java:325)`。根因：`/system/usr/keychars/Virtual.kcm` 631 行、非 ASCII 行数 **0** —— `input text` 是把字符经虚拟键盘字符映射翻成按键事件，映射表里根本没有 CJK |
| `input text` 传 16 进制转义 / percent-encoding | ❌ **无用** | **实测**：`input text '%E4%BD%A0%E5%A5%BD'` → `exit=0`，但那是把 **11 个字面 ASCII 字符**打进去，不是中文。`input text` 不做解码。**别把 exit=0 读成成功** |
| `input keyevent` 组合出中文 | ❌ **不可行** | 按键码是**键位**不是字符；中文需要 IME 的候选/编码流程。`KEYCODE_*` 表里没有任何 CJK 字符键。**文档级/机制级判断**（未逐码穷举——见 §8） |
| **辅助 IME**（ADBKeyboard 一类：广播 `ADB_INPUT_TEXT` + `commitText`） | ⚠ **机制上正确，但本机装不上** | 机制是唯一正解：只有持有 `InputConnection` 的组件能 `commitText` 任意 Unicode。参考 [senzhk/ADBKeyBoard](https://github.com/senzhk/ADBKeyBoard)（第三方项目，**文档**）。**但**：① 本机 `adb install`/`pm install` **都被 MIUI 拒**（§5）；② 即使装上，还要**改 `default_input_method`**（实测可改：`settings get secure default_input_method` = `com.baidu.input_mi/.ImeService`，`ime list -s` 列 3 个可用 IME）。我为此写好的探针 IME（`ProbeImeService`，广播 → `commitText`）**已编译成功但未能安装验证** ⇒ **未实测** |
| 剪贴板 + `KEYCODE_PASTE` | ⚠ **半条线索，未完成** | **实测**：`KEYCODE_PASTE` 存在（exit=0，110 ms）。**但**：`cmd clipboard` → `No shell command implementation.`，即**没有 shell 入口去写剪贴板**；剪贴板写入需要 App 侧 API（`ClipboardManager`），而 `WRITE_CLIPBOARD` appop 是 `foreground`（实测读到）。⇒ 需要**一个已装的应用**当剪贴板写入器。**未实测** |
| 无障碍 `ACTION_SET_TEXT` / `ACTION_PASTE` | ⚠ **理论可行，本机不可用** | 无障碍节点支持 `ACTION_SET_TEXT`（任意 Unicode）。**但本机 `accessibility_enabled=0`、白名单为 `null`**（实测）⇒ 当前没有无障碍服务在跑；且启用它需要用户手动开（或由 shell 写白名单 + 目标组件已安装——见 §2.4，本机装不了新组件） |
| `am broadcast` 给某个已装 IME 下指令 | ⚠ **依赖具体 IME 是否实现** | `am broadcast` 本身**实测可用**（105 ms）。但**本机三个 IME（百度/讯飞/搜狗）都不实现"广播注入文本"协议** —— 这一点是**未验证推断**（未去逆向它们；没有文档说明它们提供该接口） |

### 4.3 代价（如果将来要补上）

要走辅助 IME 这条路，需要同时满足：

1. **设备允许安装应用**（本机当前不允许 —— 见 §5）；且该 IME 要**常驻**（不能每次任务装一次又卸）；
2. **切换默认输入法**（可脚本化，实测 `ime set` / `settings put secure default_input_method` 的写入能力在 shell UID 手里），但**会改变用户可见的输入体验**，属"用户可感知的状态变更"；
3. 一个**广播接收器**作为"注入通道"——即把"给某个 App 发一个广播"变成产品能力的一部分，**扩大了工具面**；
4. 每个字的注入都要**额外的 App 间跳转**，是 `input text` 之外的**新增信任边界**。

**建议**：把"中文输入"从"原子操作"降级为**明确不支持的能力**，并在工具描述里写清楚（现在探针的 `type_text` 描述已经是 `Type ASCII text … Non-ASCII input is rejected by the platform.` —— 这是**诚实且正确**的做法，应保留）。若产品确实需要"让助手替用户在中文字段里打字"，那是一个**需要单独批准的产品决策**（要引入常驻 IME 组件 + 用户可见的输入法切换），不是本调研能顺手带出来的能力。

---

## 5. MIUI 特有的限制（逐条，带证据）

> 全部在 K40 / MIUI `V816.0.10.0.TKHCNXM`（Android 13）+ shell UID 下实测。凡"未测"的都写明。

| # | MIUI 上的实际行为 | 与 AOSP / 常见设备的不同 | 实测证据 |
|---|---|---|---|
| 1 | **`adb install` 与 `pm install` 都被拒** | AOSP 上 shell UID 可 `pm install` | `adb install -r x.apk` → `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]`；`pm install -r --user 0 /data/local/tmp/probe.apk` → **同样** `INSTALL_FAILED_USER_RESTRICTED`（exit 1） |
| 2 | **`svc` 被裁剪到只剩 4 个子命令** | AOSP/most 设备还有 `wifi`/`data`/`bluetooth`/`location`/`nfc` 等 | `svc` → `Available commands: help / power / usb / nfc / system-server`（仅 4 条） |
| 3 | **`uiautomator dump` 的 stderr 恒定刷 MIUI 异常栈** | AOSP 该命令 stderr 干净 | 每次 dump 都打 `java.io.FileNotFoundException: /data/system/theme_config/theme_compatibility.xml … at miui.content.res.ThemeCompatibilityLoader.getVersion` —— **但 XML 正常产出**（39–96 节点可解析）。**教训：不能靠退出码/stderr 判 dump 成败** |
| 4 | **`cmd clipboard`、`cmd aiinputtext` 无 shell 实现** | AOSP 无 `clipboard` 子命令（这点一致）；但 MIUI 额外暴露了 `aiinputtext` 服务却**不给 shell 入口** | 两者均 `No shell command implementation.`（exit 1）。`aiinputtext` 出现在 `cmd -l` 里，容易被误读成"可用" |
| 5 | **`ss -lnp` 段错误** | 通常可用 | `ss -lnp 2>/dev/null \| grep …` → `Segmentation fault` |
| 6 | **无 `tcpdump`** | 部分 ROM 自带 | `tcpdump: inaccessible or not found` |
| 7 | **无 `nslookup` / `wget`** | 部分 ROM 自带 | `absent nslookup` / `absent wget`（`ip`/`ifconfig`/`ping`/`nc`/`ss`/`iptables`/`curl` 在） |
| 8 | **读别 App 私有数据被 SELinux 拒** | 与 AOSP 一致（非 MIUI 特有） | `cat /data/data/com.android.settings/shared_prefs/*.xml` → `Permission denied`（exit 1） |
| 9 | **MIUI 有 `com.miui.securitycenter` / `com.miui.securitycore` / `com.miui.securityadd` / `com.miui.powerkeeper` 等自研常驻组件** | AOSP 无 | `pm list packages` 实测枚举到；它们是"设置类写入"最可能被 MIUI 二次拦截的地方（**未逐项验证**，见 §8） |
| 10 | **`default_input_method` 可被 shell 改写** | — | 实测 `settings get secure default_input_method` = `com.baidu.input_mi/.ImeService`；`enabled_input_methods` 含 3 个 IME。**改写会改变用户可见的输入体验**（本次**未改**） |
| 11 | **`settings put` 对"看起来敏感"的 key 并不拦截**（至少 `enabled_accessibility_services` 不拦） | — | 写入 `com.example.nonexistent/.Svc` 后**读回一致** ⇒ 写入真的发生了（已还原为 `null`）。**注意**：写进去**不等于**有服务会被绑上（组件必须真实存在且声明了 `BIND_ACCESSIBILITY_SERVICE`），本机又装不了新包 ⇒ 该能力在本机**不构成实际读屏方案** |
| 12 | **`am start -W` 可用且会给 `TotalTime`** | 与 AOSP 一致 | `am start -W -n com.android.settings/.Settings` → `Status: ok / LaunchState: WARM / TotalTime: 117 / WaitTime: 118` |

---

## 6. 风险清单

### 6.1 不可逆 / 高破坏力能力（shell UID 手里**确实有**）

| 能力 | 命令 | 为什么不可逆 | 建议 |
|---|---|---|---|
| **关机** | `svc power shutdown` | 设备直接断电，进行中的一切丢失 | ❌ **绝不进工具面** |
| **重启** | `svc power reboot [reason]` | 断网、断眼镜链路、需重新用 adb 启动 Shizuku（非 root 设备） | ❌ **绝不进工具面** |
| **强制挂起** | `svc power forcesuspend [t]` | 忽略所有 wakelock 强制休眠，可能丢后台工作 | ❌ **绝不进工具面** |
| **重启 system_server** | `am restart` | 软重启，全机应用被杀 | ❌ **绝不进工具面** |
| **清除应用数据** | `pm clear <pkg>` | 用户数据永久消失，**无回收站** | ⚠ 现有 L3，**必须**先有 `WhitelistActionPolicy` 的强制询问（生产注释已说明 L3/L4 永远走询问） |
| **卸载应用** | `pm uninstall <pkg>` | 同上 | ⚠ 现有 L3，同上 |
| **杀全部可杀进程** | `am kill-all` | 影响面是整个系统 | ❌ 不进 |
| **装/卸应用**（若将来放开） | `pm install` / `install-write`+`commit` | 引入新代码 = 引入新的攻击面 | ❌ 不进（且**本机装不了**） |
| **改防火墙** | `iptables …` | 可断掉全机网络 | ❌ 不进 |
| **改无障碍白名单** | `settings put secure enabled_accessibility_services` | 打开"读全屏"通道；**且用户界面上不显眼** | ❌ **绝不进**。这是"用 shell 命令替用户开一个隐私通道"，与 §2.2 的无障碍读屏合起来就是完整的"偷看屏幕"能力 |
| **改 appops** | `cmd appops set <pkg> <op> ignore\|allow` | 静默剥夺/放开别人的权限，**用户看不到** | ❌ 不进 |
| **改飞行模式 / 断开 WiFi** | `cmd connectivity airplane-mode …` | 断网即断眼镜链路（本项目里眼镜链路是交付面） | ⚠ 若要进，必须 L4 + 询问 |
| **免打扰** | `cmd notification set_dnd …` | 用户可能漏接电话/提醒 | ⚠ 若要进，必须 L3/L4 + 询问 |
| **通知监听白名单** | `cmd notification allow_listener <component>` | 把某个组件提成"能读所有通知"= 读短信验证码级别的隐私通道 | ❌ 不进 |
| **`content insert/update/delete`** | 任意 provider | 直接改别人数据库（含短信/联系人/日历） | ❌ 不进（写方向一律不做） |

### 6.2 若暴露给模型即构成越权的"读"能力

| 能力 | 命令 | 越权性质 | 建议 |
|---|---|---|---|
| **读短信 / 通话记录** | `content query --uri content://sms` / `content://call_log/calls` | 实测可读（本次只数了行数）。与 **D53**（日志/证据只记计数与 id、不记正文）**直接冲突** | ❌ 不进工具面。若某天要用，必须是**结构化、窄接口**（如"有没有未读短信"布尔），**永不给任意 URI** |
| **读联系人 / 日历** | `content://com.android.contacts/…` / `content://com.android.calendar/…` | 同上 | ❌ 同上 |
| **`cmd wifi status` 带出 SSID** | `cmd wifi status` | 位置/网络隐私（实测输出含 `Wifi is connected to "<SSID>"`） | ⚠ 若要进，输出必须**在设备侧就脱敏**（只回 `enabled/disabled`） |
| **读别的 App 界面** | `uiautomator dump` / `screencap` | **能读到任何前台 App 的内容**，包括聊天窗口、密码框（`password="true"` 节点仍在树里）、通知 | ⚠ **这是本调研里最有价值也最危险的一条**。建议：① 只在**用户当前可见的那个前台窗口**上读；② 输出必须过**结构化过滤器**（只回文本 + bounds + clickable，**永不回 password 字段/图片**）；③ 截图**不进**证据、不落盘（与 D53 一致） |
| **`logcat`** | `logcat -d` | 含其他应用的输出文本 | ⚠ 必须严格按 tag/行数限制；**默认不要给模型** |
| **`dumpsys` 泛化** | `dumpsys <any>` | `dumpsys power` 会带出唤醒历史与网络统计（实测刷出上千行，含 `NetworkStats`、`TPNS:VIP` 等） | ⚠ **只给白名单子服务**（`window` 读焦点、`package` 读权限、`telephony.registry` 读通话态），**不给任意 service** |
| **`curl` / `nc`** | 任意 | 能把设备上的任何数据 POST 到任意地址，**绕过应用自身的网络策略与"零网络请求"承诺** | ❌ 不进。这是"上传授权"约束的绕过口 |
| **`run-as`** | `run-as <debuggable pkg>` | 只对 debuggable 包生效（实测），但可读其数据 | ⚠ 不进（本项目 debug 包很多，等于读自己人数据，收益低风险中） |

### 6.3 我建议的**工具面收窄边界**（一句话）

> **给"能达成用户可见目标"的窄动作，不给"通用 shell"。** 具体：输入只给 `tap/swipe/keyevent` + ASCII `text`（**不给**中文，不解释原因）、界面读取只给"前台窗口 → 结构化文本"、包管理只给现有 5 个 L3/L4（含强制询问）、设置只给现有 L2 亮度 + 少数几个可逆项、`content`/`logcat`/`dumpsys` 泛化/`svc`/`appops`/`iptables`/`curl`/`nc`/`install` **一律不进**。

---

## 7. 与本项目现有实现的对齐分析

### 7.1 现有实现的实际边界（读代码 + grep 实测）

- `ShizukuExecutionBackend`（245 行）**只声明 5 个动作**：`clear_data`(L3)、`uninstall`(L3)、`disable_app`(L4)、`set_permission`(L4)、`set_brightness`(L2)。
- `ShizukuTargetState` 只发 **4 条读命令**：`pm list packages $pkg`、`pm list packages -d $pkg`、`dumpsys package $pkg`、`settings get system $name`。
- `EdithShellService.run()` = `Runtime.exec(["sh","-c", command])`。
- `IntentExecutionBackend`（另一通道）声明 6 个动作：`open_app`、`open_url`、`open_settings`、`set_alarm`、`compose_note`、`dial`。
- **grep 实测**（`phone-app/src/main/kotlin` 全量）：`uiautomator`、`screencap`、`input text`、`input tap`、`input keyevent`、`am start`、`am force-stop`、`cmd notification`、`cmd statusbar`、`content query`、`dumpsys window`、`wm size` —— **命中全部为 0**。

⇒ **本项目目前的 shell 通道是"只写 + 只做状态复核"，既不注入输入、也不读界面、也不做内容查询。** 也就是说：本调研里 §2.1/2.2/2.5/2.6 那几大块能力，**在生产代码里一条都没用上**，只在 `tools/p3-agent-probe`（JVM 侧、走 adb）里用过 `uiautomator dump` + `input tap/text/keyevent` + `am start`。

### 7.2 与实现对齐的结论

| 调研发现 | 与现有实现的关系 | 判定 |
|---|---|---|
| shell UID 能做输入注入 | 生产**未声明**这类动作 | 一致（现状是"没做"，不是"做错了"） |
| shell UID 能读别的 App 界面 | 生产**未用**；仓库里有一个**未启用的**无障碍探针 | 一致；但这是**能力缺口**，不是缺陷 |
| `pm grant` 退出码无意义 | 生产**已正确处理**（`readable()` + 读回状态 + `STATE_UNCHANGED`） | ✅ 对齐，且是本报告最能站住的一条工程实践 |
| `pm clear` 退出码不可信 | 生产**已**同时看 `exit==0` + stdout 含 `Success` + 包仍存在 | ✅ 对齐 |
| shell UID **不能**装应用 | — | ✅ 与"不做 install 动作"的现状一致；且本机实测证明了这条路本来就不可用 |
| `content query` 可读短信/联系人 | 生产**未**暴露任何 content 能力 | ✅ 对齐（且**应当继续保持**） |
| `svc power reboot/shutdown` 在 shell 手里 | 生产**未**暴露 `svc` | ✅ 对齐（且**应当继续保持**） |
| `dumpsys` 泛化会刷出巨量文本 | 生产只用了**窄**的 `dumpsys package <pkg>`，并用 `.stdout.contains("$permission: granted=true")` 做子串判定 | ✅ 对齐，且这是**规避 binder 回包上限**的正确姿势 |
| `IRemoteShell.run()` 把 stdout+stderr 打包成一个 String | 生产**已知**并做了两件事：① 并发 drain 两个管道避免死锁；② 解析失败 → `SHELL_REPLY_INVALID`，**永不**当成空成功 | ✅ 对齐。~~**但"单次回包上限"在生产代码里没有显式处理**~~ → **2026-10-16 补测：上限 ≥ 1.2 MB 且未探到（§3.3.4）**，所以"没有显式处理"**不构成现实风险**；真正需要显式处理的是**二进制**（要 base64 或落盘再读）与**结果落盘时 App 建不了目录**（§3.3.7 第 3 条） |
| 非 ASCII 输入被平台拒绝 | 生产探针**已**显式拒绝（`value.any { it.code > 127 }`） | ✅ 对齐，且本报告为它补上了**根因证据**（`Virtual.kcm` 无 CJK） |

### 7.3 需要提请的 drift / 待决事项（不是缺陷，是决策）

1. **"界面读取"能力缺口**：Agent 若要做"看一眼屏幕再决定"，目前生产端没有工具。本报告证明 `uiautomator dump` 在 shell UID 下**可用且便宜（2.3 s / 13.9 KB）**，但它是**全屏可读**的隐私通道 ⇒ 要进工具面必须先定"读什么、怎么脱敏、密码框怎么处理"。
2. **中文输入缺失**：若某条产品路径依赖"在中文字段里打字"，本报告说明它**当前不可达**，且补救需要常驻 IME + 用户可见的输入法切换。
3. **`IRemoteShell` 单次回包上限**（~~未定义~~ → **2026-10-16 补测：≥ 1.2 MB 完整成功**，见 §3.3.4）。原建议"在加'大输出'动作之前先实测上限"**已完成到 1.2 MB**；结论是**不必再为"大输出撞上限"而收紧工具面**，但产品若要传**二进制**（截图），仍需按 **base64 ×1.33** 计算体积并**在设备侧先降采样**（全屏 PNG base64 后约 271 KB，离实测到的 1.2 MB 有余量，但屏幕内容复杂时会显著变大）。
4. **Shizuku 通道的可用性前提没有产品级提示**：非 root 设备**每次重启后要用 adb 重启 Shizuku**（官方文档）。生产代码已有 `ACTION_CHANNEL_UNAVAILABLE` 的降级设计（好），但用户可能不知道"要去重开 Shizuku"。

---

## 8. 未知与未验证清单（**明确不得读成已验证**）

| # | 未知项 | 为什么没测 | 怎么补 |
|---|---|---|---|
| 1 | ~~**设备内 Shizuku shell UID 的实际 uid/SELinux/latency**~~ **⇒ 已实测（2026-10-16 补测）** | 首轮：Shizuku **server 未运行**，且**本机装不了探针 APK**（MIUI 拒装） | ✅ **已补**：`uid=2000(shell)` / `u:r:shell:s0`、往返 26–37 ms —— 见 **§3.3.2 / §3.3.3** |
| 2 | ~~**`IRemoteShell` 单次 binder 回包上限的具体字节数**~~ **⇒ 部分实测（2026-10-16 补测）** | 首轮：同上（只能在设备内测） | ✅ **已补到 1.2 MB**：1 228 800 B 完整成功 ⇒ 原"~1 MB 上限"**证伪**；**上限本身仍未探到**（≥ 1.2 MB）—— 见 **§3.3.4 / §3.3.8 第 1 条** |
| 1b | 🆕 **设备内路径下"写操作"与"跨 App 读屏"以外还有哪些 §2 能力逐条成立** | 补测只跑了 IDENTITY/LATENCY/PAYLOAD/UIREAD 四组 + `am start`；**没有**逐条复跑 §2 的整张表 | 按需逐条复跑（同一探针加 block 即可） |
| 1c | 🆕 **设备内路径重启后是否失效** | 官方文档说非 root 设备每次重启要用 adb 重启 Shizuku；**本次未重启设备**（另一条工作线在用同一台 K40） | 需在设备空闲窗口重启一次验证 |
| 3 | **`input tap`/`swipe` 的成功率** | 本次只验证了 exit code 与"其他方式复核过效果"；未做本次自己的成功率测量 | 沿用 T3b 的方法（语义点击 vs 坐标点击对照） |
| 4 | **`KEYCODE_PASTE` 端到端是否真能粘出内容** | 需要剪贴板里先有内容，而 `cmd clipboard` 无 shell 实现、本机又装不了写剪贴板的探针。**（补测后前提已变：安装通道已开——见 §3.3；但仍未写剪贴板探针）** | 装一个能写剪贴板的探针后再测 |
| 5 | **辅助 IME 方案（广播 → `commitText`）是否真的能注入中文** | 探针 IME 已写好、编译通过，首轮**装不上**（MIUI 拒装）。**⚠ 补测后前提已变：2026-10-16 探针 APK 实测装上了**（`adb install -r` → `Success`，§3.3），因此"装不上"**不再是**这条的阻塞理由；**但 IME 注入本身仍完全没测**（缺"切默认输入法"这一步，且那会打扰另一条工作线） | 安装通道已具备；仍需单独批准"切默认输入法"后重跑 |
| 6 | **`input keyevent` 能否组合出任何 CJK 字符** | 只做了机制判断（键位≠字符），未逐码穷举 | 不需要补：机制上不可能 |
| 7 | **`content insert/update/delete` 是否可用** | 属写方向、有隐私风险，**故意未执行** | 若将来要测，只在自建 provider 上测 |
| 8 | **MIUI 是否对 `settings put` 的特定 key 有二次拦截** | 只测了 4 个 key（`user_rotation`、`screen_brightness`、`enabled_accessibility_services`、一个自定义 key），**没有**穷举 `screen_off_timeout`/`zen_mode`/`volume_*` 等 | 逐 key 写入并读回（**只读回，不改真实值**） |
| 9 | **`iptables` 能否真的改规则（JNI 权限是否够）** | 会断全机网络，**故意未执行** | 不补 |
| 10 | **`cmd wifi` / `cmd connectivity` 的写子命令行为** | 会断网/断眼镜链路，**故意未执行** | 需要单独批准后再测 |
| 11 | **`svc power reboot/shutdown` 的真实行为** | **不可逆，故意未执行** | 不补 |
| 12 | **`am force-stop` / `pm disable-user` / `pm clear` 在 MIUI 上是否有额外拦截** | 这些是**破坏性**动作，且生产代码已实现，本次未对真实 App 执行 | 在自家可重装的探针 App 上测（`edith.phone.a11yprobe` 就是为此存在的 L3/L4 靶子） |
| 13 | **MIUI 自有组件（securitycenter/powerkeeper）对上述命令的二次拦截** | 未逐项验证 | 需要针对每个动作单独设计实验 |
| 14 | **`uiautomator dump` 在 Flutter/游戏/自绘界面上的节点覆盖** | 只测了 Settings 与 launcher | 按需测 |
| 15 | **并发用多个 shell 命令（非 uiautomator）是否安全** | 只测了 `uiautomator dump` 并发（失败）与 `adb shell true` 串行开销 | 按需测 |

---

## 9. 复现步骤

> 全部命令以 `adb -s 2696813b …` 指定设备。**带 ⚠ 的是本次故意未执行或需还原的动作。**

### 9.0 环境与身份

```powershell
adb devices -l
adb -s 2696813b shell 'id'                                     # uid=2000(shell) … context=u:r:shell:s0
adb -s 2696813b shell 'getprop ro.build.version.sdk; getprop ro.miui.ui.version.name; getprop ro.build.characteristics'
adb -s 2696813b shell dumpsys package moe.shizuku.privileged.api | Select-String versionName   # 13.6.0
adb -s 2696813b shell service list | Select-String shizuku     # 空 ⇒ server 未运行
adb -s 2696813b shell ps -A | Select-String shizuku            # 只有 App 进程 u0_a270，无 uid 2000 server
```

### 9.1 输入注入

```powershell
adb -s 2696813b shell input                                    # 子命令清单
adb -s 2696813b shell input text edith                         # ASCII：exit=0
adb -s 2696813b shell input text 你好世界                        # 非 ASCII：exit=255 + InputShellCommand NPE
adb -s 2696813b shell cat /system/usr/keychars/Virtual.kcm | Measure-Object -Line   # 631 行；非 ASCII 行 0
foreach ($k in 'KEYCODE_HOME','KEYCODE_APP_SWITCH','KEYCODE_BACK','KEYCODE_WAKEUP','KEYCODE_VOLUME_UP','KEYCODE_VOLUME_DOWN','KEYCODE_MUTE','KEYCODE_BRIGHTNESS_UP','KEYCODE_MEDIA_PLAY_PAUSE','KEYCODE_CAMERA','KEYCODE_SLEEP','KEYCODE_PASTE') { adb -s 2696813b shell input keyevent $k }
adb -s 2696813b shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
adb -s 2696813b shell input keyevent --longpress KEYCODE_DEL
```
（`KEYCODE_SLEEP` 会关屏，随后用 `input keyevent KEYCODE_WAKEUP` 唤回。）

### 9.2 界面读取

```powershell
$dev = '/sdcard/tmp-shizuku-survey.xml'
Measure-Command { adb -s 2696813b shell uiautomator dump $dev }        # ≈2283 ms
adb -s 2696813b shell cat $dev                                          # 13.9 KB / 39 nodes
adb -s 2696813b shell screencap -p /sdcard/tmp-survey-correct.png       # ≈320–400 ms
adb -s 2696813b pull /sdcard/tmp-survey-correct.png .\pulled.png        # 203 804 B, 魔数 89 50 4E 47
# 并发（会失败，这是证据）：两个 uiautomator dump 同时跑，只有一个产出文件
```

### 9.3 包与应用

```powershell
adb -s 2696813b shell cmd package | Select-String 'grant|revoke|clear|disable-user|uninstall'
adb -s 2696813b shell am start -W -n com.android.settings/.Settings    # TotalTime/WaitTime
adb -s 2696813b shell am broadcast -a edith.survey.NOOP --es k v       # Broadcast completed: result=0
adb -s 2696813b shell pm list packages | Measure-Object -Line
# ⚠ 破坏性动作（本次未执行）：pm clear / pm uninstall / pm disable-user —— 只在 edith.phone.a11yprobe 上测
```

### 9.4 设置 / cmd / svc / appops

```powershell
adb -s 2696813b shell svc                                              # 只有 help/power/usb/nfc/system-server
adb -s 2696813b shell svc power                                        # 含 reboot / shutdown（⚠ 未执行）
adb -s 2696813b shell cmd -l | Measure-Object -Line                    # 286+ 服务
adb -s 2696813b shell cmd statusbar expand-notifications; adb -s 2696813b shell cmd statusbar collapse
adb -s 2696813b shell cmd wifi status                                  # ⚠ 输出含 SSID
adb -s 2696813b shell cmd connectivity airplane-mode
adb -s 2696813b shell cmd notification                                 # 子命令清单
adb -s 2696813b shell cmd appops get edith.phone.a11yprobe
adb -s 2696813b shell cmd appops set edith.phone.a11yprobe VIBRATE ignore ; adb -s 2696813b shell cmd appops get edith.phone.a11yprobe VIBRATE ; adb -s 2696813b shell cmd appops set edith.phone.a11yprobe VIBRATE default   # 还原
adb -s 2696813b shell settings get system screen_brightness
adb -s 2696813b shell settings put system user_rotation 1 ; adb -s 2696813b shell settings get system user_rotation ; adb -s 2696813b shell settings put system user_rotation 0   # 还原
```

### 9.5 内容 / 文件 / 诊断

```powershell
adb -s 2696813b shell 'content query --uri content://sms --projection _id'                     # 只数行数
adb -s 2696813b shell 'content query --uri content://settings/system --where "name=x"'          # 报错：--where 不是 SQL
adb -s 2696813b shell run-as edith.phone id                                                     # 成功（debug 包）
adb -s 2696813b shell run-as com.android.contacts id                                            # package not debuggable
adb -s 2696813b shell 'cat /data/data/com.android.settings/shared_prefs/*.xml'                  # Permission denied
adb -s 2696813b shell 'echo probe > /sdcard/t.json; cat /sdcard/t.json; rm /sdcard/t.json'
adb -s 2696813b shell dumpsys -l | Measure-Object -Line
adb -s 2696813b shell dumpsys window | Select-String mCurrentFocus
adb -s 2696813b shell logcat -d -t 200 | Measure-Object -Line
adb -s 2696813b shell 'for b in netstat ip ifconfig ping tcpdump nc ss iptables nslookup curl wget; do which $b >/dev/null 2>&1 && echo PRESENT $b || echo absent $b; done'
```

### 9.6 安装限制与 Shizuku 路径

**首轮（阻塞点，实测失败）**：

```powershell
adb -s 2696813b install -r <probe.apk>                                  # INSTALL_FAILED_USER_RESTRICTED
adb -s 2696813b push <probe.apk> /data/local/tmp/probe.apk
adb -s 2696813b shell pm install -r --user 0 /data/local/tmp/probe.apk  # 同样 USER_RESTRICTED
adb -s 2696813b shell rm -f /data/local/tmp/probe.apk                   # 清理
```

**同日补测（2026-10-16 01:25–01:29，实测跑通，已全部清理）**：

```powershell
# 0) 前置：确认 Shizuku server 在跑（只读；不要自己去启动它）
adb -s 2696813b shell "ps -A | grep -i shizuku"          # → shell 20096 1 … shizuku_server
# 1) settings.gradle.kts 加一行 include（临时，测毕删回）
# 2) 构建（JAVA_HOME 不设会直接失败）
$env:JAVA_HOME="D:\Tools\JDK"; .\gradlew.bat :tools:shizuku-shell-probe:assembleDebug
# 3) 安装：⚠ 用 adb -s 显式指定设备，不要用 installDebug —— 它会装到【所有】已连接设备
adb -s 2696813b install -r tools\shizuku-shell-probe\build\outputs\apk\debug\shizuku-shell-probe-debug.apk
# 4) 拉起 + 收结果（tag: ShizukuSurvey）
adb -s 2696813b shell am start -n edith.survey.shizukuprobe/.MainActivity
adb -s 2696813b logcat -s ShizukuSurvey:* *:S > <local>.log      # 边跑边落盘，别等跑完再读（buffer 会转掉）
# 5) 结果文件：⚠ App 自己建不了目录，先由 shell 建（见 §3.3.7 第 3 条）
adb -s 2696813b shell mkdir -p /sdcard/Android/data/edith.survey.shizukuprobe/files
adb -s 2696813b pull /sdcard/Android/data/edith.survey.shizukuprobe/files/survey.txt
# 6) 清理
adb -s 2696813b shell rm -f /sdcard/tmp-probe-shot.png
adb -s 2696813b uninstall edith.survey.shizukuprobe
# settings.gradle.kts 那一行 include 删回，git diff -- settings.gradle.kts 必须为 0
```

### 9.7 文档来源

- Shizuku 以 shell(2000)/root(0) 身份运行、ADB 与 ROOT 权限差异、Shell 的 AndroidManifest 权限清单、UserService 的 `Context` 限制、非 root 设备每次重启需用 adb 重启 Shizuku、`newProcess` 将被移除且无 tty：
  [Shizuku-API README](https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md)
- 辅助 IME（广播注入 Unicode）的第三方参考实现：[senzhk/ADBKeyBoard](https://github.com/senzhk/ADBKeyBoard)

---

## 附：本次调研对设备做过的一切状态改动（全部已还原）

> **时点声明**：下列"还原后"的读数取自本调研结束时的实测。本仓库同时存在**另一位 agent 在同一台 K40 上作业**（`phone-accessibility-probe` 的 `A11ySurveyService` / `A11ySurveyNoIdsService`），因此**设备状态与 `uiautomator` 输出可能随后被其改变**。本报告 §2–§5 的每条读数都是本调研执行当时的快照；其中与"无障碍/输入法/亮度/旋转"相关的读数，若与后来看到的不一致，以**当时快照 + 各自的实测记录**并列为准，不得互相覆盖。

| 改动 | 还原情况 |
|---|---|
| `settings put system user_rotation 1` | → 已还原为 `0`（读回确认） |
| `settings put system screen_brightness 51` | → 已还原为 `50`（读回确认） |
| `settings put secure enabled_accessibility_services com.example.nonexistent/.Svc` | → 已还原为 `null`；`accessibility_enabled` 仍为 `0` |
| `settings put secure edith_probe_key 1` | → 已 `settings delete`，读回 `null` |
| `cmd appops set edith.phone.a11yprobe VIBRATE ignore` | → 已还原为 `default`（读回确认） |
| `input keyevent KEYCODE_SLEEP` | → 已 `KEYCODE_WAKEUP` 唤回 |
| `/sdcard` 与 `/data/local/tmp` 临时文件 | → 已逐个 `rm`，`ls` 复核为空 |
| 安装临时探针 APK | → **未安装成功**（MIUI 拒装），无需卸载；`pm list packages` 复核无 `shizukuprobe` |
| 默认输入法 | → **未改动**，仍为 `com.baidu.input_mi/.ImeService`；`enabled_input_methods` 三项未变 |
| 生产 App `edith.phone` | → **未触碰**（无 uninstall / 无 pm clear / 未读写其 `files/`） |

**未提交任何东西**：本次未执行 `git add` / `git commit`。新写的临时探针位于 `tools/shizuku-shell-probe/`（untracked），另在 `settings.gradle.kts` 加了一行 include（亦为临时）。

---

## 附二：同日补测（2026-10-16 01:25–01:29）对设备做过的一切改动与清理

> 这一节只记 **§3.3 那次补测**的动作；上面"附"一节是首轮的，两者**分开记账**，不合并不覆盖。

| 改动 | 还原 / 复核情况 |
|---|---|
| 安装探针 `edith.survey.shizukuprobe`（K40） | → 已 `adb -s 2696813b uninstall edith.survey.shizukuprobe`（`Success`）；`pm list packages` 复核 **无** shizukuprobe |
| ⚠ **`gradlew installDebug` 把探针同时装到了眼镜 `1901092544027662`** | → 已 `adb -s 1901092544027662 uninstall edith.survey.shizukuprobe`（`Success`）并复核为空。**眼镜上从未拉起过它**；后续安装/卸载一律显式 `-s <serial>` |
| `/sdcard/tmp-probe-shot.png`（截屏临时文件） | → 已 `rm`；`ls /sdcard/tmp-probe-*` 复核 `No such file or directory` |
| `/sdcard/Android/data/edith.survey.shizukuprobe/`（含 `files/survey.txt`、`tmp-probe-fg.xml`） | → 随卸载由系统一并清除；复核同路径 `No such file or directory` |
| 探针在设备内发的命令 | **全部为只读 + `am start -W` 打开 Settings**；**未**执行任何 `pm`/`settings put`/`svc`/`content` 写操作，**未**改无障碍白名单、输入法、亮度、旋转 |
| 生产 App `edith.phone` / `edith.phone.a11yprobe` | → **未触碰**（无 uninstall、无 `pm clear`、未读写其 `files/`）；复核 `pm list packages` 两个包都在 |
| `settings.gradle.kts` 的临时 include | → 已删回；`git diff -- settings.gradle.kts` 复核 **为 0（空 diff）** |
| 🆕 **复核时发现的状态变化（不是我改的）** | 补测收尾读数：`accessibility_enabled=1`、`enabled_accessibility_services=edith.phone.a11yprobe/.A11ySurveyService:.A11ySurveyNoIdsService` —— 这是**另一位 agent 的探针已启用**（首轮快照里该值为 `0` / `null`）。默认输入法仍为 `com.baidu.input_mi/.ImeService`、`user_rotation=0`、`screen_brightness=50`，**未被本补测改动** |
| 本机残留临时文件 | → 原始日志已收进 gitignored 的 `.local/`（`p3-shizuku-survey-result.txt` = 取回的 `survey.txt`，3 478 B）；不留在仓库根目录 |

**补测同样未提交任何东西**：未执行 `git add` / `git commit`。探针源码的 3 处改动（隐私修复 + 新增 `UIREAD_FOREGROUND` 组 + 计数修正，见 §3.3.7）都在那个 **untracked** 的 `tools/shizuku-shell-probe/` 里。
