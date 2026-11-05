package com.sheen.rokid.phone.ui.settings

/**
 * The 0.52 `(?)` explanations.
 *
 * **This is a migration, not a rewrite** (design §4.1). Every string below is lifted word for word from appendix
 * A1–A23 of the settings UI inventory, or — for the three entries the appendix does not cover, because 0.52 introduces
 * the model catalog — from design §5.6/§5.7. The qualifiers the appendix marks as un-droppable are kept verbatim:
 * 重启 (完全重启 App), 立即 (保存后立即生效 / 开启后立即生效), 下次会话/下次开始会话, and the risk boundaries (the vision
 * upload risk, the no-interception L0–L4 range, the phone-only upload-consent wording). No promise is added.
 *
 * The object is data only: no Compose, no Android, no host types. The surfaces consume it through [HelpIcon];
 * Tasks 9–14 are its only callers.
 */
object HelpContent {

    // ---------------------------------------------------------------------------------------------------------
    // Appendix A1 (restart semantics). The shared sentence is repeated verbatim in each affected option's body so
    // that a popover read in isolation still carries the restart qualifier. Task 15 aligned the named fields with the
    // ones that are really read at launch: the 主动辅助 switch is immediate and must not be listed here.
    // ---------------------------------------------------------------------------------------------------------
    private const val RESTART_SEMANTICS =
        "唤醒词、持续对话、权限上限、白名单、主动辅助冷却、敏感度等设置仅在 App 启动时读取一次（组合为进程级单例），" +
            "修改后需完全重启 App 才会生效，重新开始会话不会重新读取。"

    // ---------------------------------------------------------------------------------------------------------
    // 助手行为 (design §3.3)
    // ---------------------------------------------------------------------------------------------------------

    /** A1 + the wake-word format rules A22 enforces. */
    val WAKE_WORDS = HelpText(
        title = "唤醒词",
        body = RESTART_SEMANTICS +
            "唤醒词以逗号分隔，每个 1-16 个字符，需包含字母或汉字，不能为空；重复项会按不区分大小写去重。",
        impact = "影响：完全重启 App 后生效",
    )

    /** A2 + A3 (the phone-only note `SmartSettingsForm.CONTINUE_DIALOGUE_PHONE_ONLY_NOTE` keeps verbatim). */
    val CONTINUE_DIALOGUE = HelpText(
        title = "持续对话",
        body = "答案显示/播报结束后自动进入下一轮聆听；关闭后答案结束约 1 秒关闭面板，不发 status 5。" +
            "纯手机模式下不生效；下一句长按悬浮球。",
        impact = "影响：完全重启 App 后生效",
    )

    /** A4 (what the switch stops) + A5 (the upload risk boundary), kept in full. */
    val VISION = HelpText(
        title = "感知截图",
        body = "关闭后不再截取并随请求发送屏幕图片；保存后立即生效。它不停止屏幕文字、通知等文字内容的发送。" +
            "风险提示：开启后，屏幕与通知中的内容（可能包含密码、验证码、银行卡号等敏感信息）会随请求发送到你配置的模型服务；" +
            "本机日志与证据只记录计数和标识，不记录这些内容。关闭此开关只会停止发送截图图片，不会停止发送屏幕文字。",
        impact = "影响：保存后立即生效",
    )

    /** A13 (persistence) + A12 (the phone-only conditional note, verbatim). The switch itself is immediate. */
    val PROACTIVE = HelpText(
        title = "主动辅助",
        body = "一次配置、长期生效：跨会话、重连、重启 App 或手机都保持；关掉后不会被任何路径自动打开。" +
            "纯手机模式下不生效（主动辅助的持久值不变）。",
        impact = "影响：立即生效",
    )

    /** A1 restart qualifier + the 5–600 s range A22 rejects outside of. */
    val PROACTIVE_COOLDOWN = HelpText(
        title = "主动辅助冷却",
        body = RESTART_SEMANTICS + "冷却时间范围为 5-600 秒（UI 以秒编辑，存储为毫秒）。",
        impact = "影响：完全重启 App 后生效",
    )

    /** A1 restart qualifier + the LOW/MEDIUM/HIGH set. */
    val PROACTIVE_SENSITIVITY = HelpText(
        title = "主动辅助敏感度",
        body = RESTART_SEMANTICS + "敏感度可选 低 / 中 / 高（存储为 LOW / MEDIUM / HIGH）。",
        impact = "影响：完全重启 App 后生效",
    )

    // ---------------------------------------------------------------------------------------------------------
    // 运行与外观 (design §3.4)
    // ---------------------------------------------------------------------------------------------------------

    /** A11, complete and verbatim (the same text `DetachUi.SWITCH_SUMMARY` still carries). */
    val PHONE_ONLY = HelpText(
        title = "纯手机模式",
        body = "开启后不连接眼镜（CXR），只用手机麦克风和手机屏幕；桌面显示可拖动的 EDITH 悬浮球（长按说话、气泡显示回复）；" +
            "主动辅助不生效（其持久值不变）；" +
            "这是采音与显示方式的选择，不是上传授权——会话运行时仍会按既有规则把转写与当回合上下文发送给所配置的模型服务；" +
            "会话运行中修改会先停止会话且不自动重启；下次开始会话生效。",
        impact = "影响：下次开始会话生效",
    )

    /** A14 (mode-off preview semantics) + the shipped 20–100% range and 82% default. */
    val BALL_OPACITY = HelpText(
        title = "悬浮球透明度",
        body = "悬浮球透明度范围为 20-100%，拖动预览、松手保存，默认 82%。" +
            "模式开启后生效：纯手机模式关闭中，滑块只预览数值并保存设置。",
        impact = "影响：保存后立即生效；纯手机模式关闭时仅存值。",
    )

    /** A15 (faded old messages) + A14 (mode-off preview semantics) + the shipped 30–100% range and 75% default. */
    val BUBBLE_OPACITY = HelpText(
        title = "气泡透明度（整泡含文字）",
        body = "气泡透明度范围为 30-100%，拖动预览、松手保存，默认 75%。" +
            "旧消息不单独配置：整条气泡（卡片+文字）按气泡透明度的 42% 再淡化，2s 后消失。" +
            "模式开启后生效：纯手机模式关闭中，滑块只预览数值并保存设置。",
        impact = "影响：保存后立即生效；纯手机模式关闭时仅存值。",
    )

    /** A16, complete and verbatim. */
    val SPEECH = HelpText(
        title = "语音朗读",
        body = "关闭后不会自动打开；引擎缺失时提示照常显示、只是不出声。唤醒后的短提示音也受这个开关控制。",
        impact = "影响：立即生效",
    )

    // ---------------------------------------------------------------------------------------------------------
    // 权限与安全 (design §3.5)
    // ---------------------------------------------------------------------------------------------------------

    /** A1 restart qualifier + the `action|scope` format A22 enforces and the L2-only grant rule. */
    val WHITELIST = HelpText(
        title = "白名单",
        body = RESTART_SEMANTICS +
            "白名单每条必须是 action|scope 两段（动作|目标范围），逗号分隔；格式不合规的条目不会保存。" +
            "白名单只用于 L2 动作的免确认，L3/L4 始终需要确认。",
        impact = "影响：完全重启 App 后生效",
    )

    /** A1 restart qualifier + A1's default sentence + the L0–L4 explanation design §3.5 asks for. */
    val MAX_LEVEL = HelpText(
        title = "权限上限",
        body = RESTART_SEMANTICS +
            "默认上限为 L1，如需 L3/L4 请在此明确选择。" +
            "L0 与 L1 无需确认直接执行；L2 默认需要确认，可加入白名单；L3 与 L4 始终需要确认，不能加入白名单。" +
            "权限上限是自动执行的边界：高于上限的动作会转为确认，而不是直接拒绝。",
        impact = "影响：完全重启 App 后生效",
    )

    /** A6, complete and verbatim (the same text `NoInterceptionUi.SWITCH_SUMMARY` still carries). */
    val NO_INTERCEPTION = HelpText(
        title = "不拦截",
        body = "开启后 L0–L4 全部工具调用不再确认，并绕过权限上限；关闭 App、被杀或重启后仍保持，只有手动关闭才退出。" +
            "卸载/清数据/删除文件等动作可能无法撤销。",
        impact = "影响：开启后立即生效；关闭 App、被杀或重启后仍保持",
    )

    /** A9, complete and verbatim. */
    val LOCK_PIN = HelpText(
        title = "锁屏 PIN",
        body = "工具调用需要手机处于解锁状态时会使用这里的 PIN。PIN 只保存在本机 Keystore（加密、不写日志、不上传）；" +
            "仅支持 4-16 位数字 PIN，不支持图案或中文密码。",
        impact = "影响：立即生效",
    )

    /** A10, complete and verbatim. Read-only page: no effect line. */
    val AUDIT = HelpText(
        title = "审计记录",
        body = "本地加密保存；只记录动作、风险级别、裁决、确认来源与执行结果，" +
            "不含通知正文、屏幕文字、转写内容或电话号码。最多保留最近 200 条。",
    )

    // ---------------------------------------------------------------------------------------------------------
    // 模型服务 (design §5.6/§5.7; the appendix predates the catalog, so these are the design's own limits)
    // ---------------------------------------------------------------------------------------------------------

    /** §5.6 + §5.2: the window/output budget (`窗口 − 输出 − 安全余量 256 > 0`). */
    val MODEL_CONTEXT_WINDOW = HelpText(
        title = "上下文窗口",
        body = "上下文窗口是模型一次请求能接收的最大输入，按 K tokens 显示。" +
            "输入预算 = 上下文窗口 − 最大输出 − 256（安全余量），保存时会校验输入预算必须大于 0。",
        impact = "影响：保存后立即生效",
    )

    /** §5.6 + §5.2: the output cap and the same budget rule. */
    val MODEL_OUTPUT = HelpText(
        title = "最大输出",
        body = "最大输出是单次回复的输出上限，按 K tokens 显示，必须小于「上下文窗口 − 安全余量（256）」；" +
            "保存时会校验输入预算必须大于 0。",
        impact = "影响：保存后立即生效",
    )

    /** §5.6: text locked, image optional, audio reserved; the image-off and unverified consequences. */
    val MODEL_INPUT_IMAGE = HelpText(
        title = "支持的输入",
        body = "文本是必选输入；图片可开关；音频预留但 0.52 未接入，0.53 开放。" +
            "关闭图片后，该模型不可用于助手会话，模型列表的该行显示「不支持图片，助手不可用」，单选禁用。" +
            "图片未测试时显示「未验证」，不影响选择，但会在首页/模型页提示风险。",
        impact = "影响：保存后立即生效",
    )

    /** 0.53 design §5.7/T9 (ruling A): the notice replaces family detection, plus the budget default and caveat. */
    val MODEL_THINKING = HelpText(
        title = "深度思考",
        body = "仅支持 DeepSeek 官方 thinking 协议（OpenAI Responses 与 Chat Completions 都可用）；" +
            "其他模型开启后可能无效或报错。" +
            "开启后工具回合保留思考，输出预算按官方默认 64K/128K。" +
            "关闭表示不主动请求深度思考；DeepSeek 服务端可能仍有默认思考，应用会保留并回传其思考内容。" +
            "思考内容只存本次会话内存、不写日志。" +
            "开启更稳但更慢、消耗更多思考 token；默认关闭以保持当前速度；" +
            "开启后默认「低」，可改为「高 / 最高」。",
        impact = "影响：保存后立即生效",
    )

    /** §5.2: the advanced protocol switch and its default. */
    val PROVIDER_PROTOCOL = HelpText(
        title = "协议",
        body = "协议是高级项，默认使用 OpenAI Responses；如果网关只支持 Chat Completions，可改为 OpenAI Chat Completions。" +
            "两种协议的思考字段映射不同，切换后按所选协议发送；DeepSeek 模型的思考在两条协议下都支持。",
        impact = "影响：保存后立即生效",
    )

    /** §5.2 default timeout + §5.4's fixed gateway-fetch timeout. */
    val PROVIDER_TIMEOUT = HelpText(
        title = "超时",
        body = "超时是高级项，默认 30000 毫秒（30 秒），用于模型请求；拉取模型列表另用固定的 10 秒超时。",
        impact = "影响：保存后立即生效",
    )

    /** §5.4/§5.5: the `GET {baseUrl}/models` fetch, its 10 s timeout, the merge rule, and the manual-add fallback. */
    val FETCH_MODELS = HelpText(
        title = "获取模型",
        body = "从网关的 /models 拉取模型列表，超时 10 秒；成功后按网关元数据合并，用户改过的字段不会被覆盖。" +
            "网关不支持模型列表（404/405）、返回格式无法识别或列表为空时，请手动新增模型；连接超时请检查 Base URL 与网络。",
    )
}
