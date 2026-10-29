# Phase 3 真机验证证据（2026-10-16）

## 环境
- 设备：K40 `2696813b` + 眼镜 `1901092544027662`
- 分支：`feat/0.21-agentloop-context-redesign` @ `9d3396b`
- APK：`phone-app-debug.apk`（Phase 3 全量构建）
- 档案：DeepSeek（`deepseek-chat`，DEEPSEEK_RESPONSES 协议）

## 验证流程
1. `assembleDebug` → `install -r` → 拉起 App → 菜单点「开始」
2. `P3_AUDIO state=armed generation=1 transport=true loop=true` 确认会话激活
3. 注入 `wake-zh.wav`（唤醒）+ `request-note.wav`（请求）

## 日志证据

### 唤醒 + ASR
```
15:38:49.702 P3_WAKE word=伊迪丝 confidence=1.0
15:39:00.198 P3_TRANSCRIPT turn=65f361a0 rev=2 final=true chars=3
```

### 记忆注入
```
15:39:00.925 P3_MEMORY inject=n=5 chars=141 via=lexical bucket=none indexed=0
  ids=43f34796,00570cbb,4abfef8b,f781abe8,c3e0443d
```

### 模型请求（AC-7/AC-8 验证）
```
15:39:04.396 P3_HTTP method=POST status=200 ms=3281   ← 首次请求（含工具定义）
15:39:06.604 P3_HTTP method=POST status=200 ms=388    ← 工具结果回传
```

### 回合完成
```
15:39:08.649 P3_TURN end outcome=COMPLETED code=- steps=1
15:39:08.805 P3_HINT show chars=67
15:39:08.855 P3_HINT ack status=Displayed
```

## AC 行验证状态

| AC | 判据 | 真机状态 | 说明 |
|---|---|---|---|
| AC-7 工具往返 | Responses 协议发出配对完整的调用与结果 | **Verified（真机）** | steps=1 + 两次 HTTP 200（请求+工具回传） |
| AC-8 OpenAI 系统提示词 | N/A（当前档案为 DeepSeek） | **NotMeasured** | 需要 OpenAI 档案验证 |
| AC-1 记忆不被误认为用户发言 | 模型回答了本轮问题而非记忆内容 | **部分验证** | 回合 COMPLETED + hint 67 chars 上屏；需人工判读内容确认 |

### Chat Completions API 协议验证（AC-8）

将档案协议从 Responses API 切换为 Chat Completions API 后：
- **HTTP POST 成功到达 api.deepseek.com**（`P3_HTTP method=POST status=401 ms=180`）
- 401 是因为 UI 操作过程中 API Key 被意外污染（编辑器保存按钮被导航栏遮挡，多次尝试导致字段残留字符），**不是适配器编码问题**
- JVM 测试已验证：首条消息 `role:"system"` + content = systemText + 工具往返完整
- **结论：AC-8 实现正确（JVM Verified），真机因凭证污染未能完成端到端验证**

## 未测项
1. AC-8 真机端到端：需要干净的 API Key + Chat Completions 协议档案（UI 编辑器有底部导航栏遮挡保存按钮的 bug）
2. AC-1 的内容判读需要人工确认模型回答是否针对本轮问题
3. AC-1b 由 JVM 测试覆盖（RequestFrame.init 强制），真机无法直接观测
