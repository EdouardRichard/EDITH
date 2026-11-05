package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.HelpText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 design §4.1 / appendix A1–A23: the `(?)` copy is a **migration**, not a rewrite.
 *
 * The pages that render these popovers (Tasks 9–14) pass the constants through unchanged, so this JVM test is the only
 * place that can pin the words cheaply:
 *
 * - every one of the 22 explanations has an option-name title and a non-blank body;
 * - the qualifiers the appendix calls out as un-droppable survive verbatim (重启 / 立即 / 下次会话 / 风险边界);
 * - the old user-visible term 「档案」 is gone;
 * - the three post-0.52 model entries follow design §5.6/§5.7 (not the appendix), and `MODEL_THINKING` states the
 *   revised 0.53 contract (DeepSeek-only official protocol, official 64K/128K default, session-memory privacy).
 *
 * The comparisons against `DetachUi.SWITCH_SUMMARY`, `DetachUi.PROACTIVE_DETACHED_NOTE`,
 * `SmartSettingsForm.CONTINUE_DIALOGUE_PHONE_ONLY_NOTE` and `NoInterceptionUi.SWITCH_SUMMARY` are intentional: the
 * popover copy and the still-existing pure copy objects must not drift while both exist.
 */
class HelpContentTest {

    /** Every explanation the settings pages may open, by constant name so a missing one fails by name. */
    private val all: Map<String, HelpText> = linkedMapOf(
        "WAKE_WORDS" to HelpContent.WAKE_WORDS,
        "WHITELIST" to HelpContent.WHITELIST,
        "MAX_LEVEL" to HelpContent.MAX_LEVEL,
        "CONTINUE_DIALOGUE" to HelpContent.CONTINUE_DIALOGUE,
        "VISION" to HelpContent.VISION,
        "PROACTIVE" to HelpContent.PROACTIVE,
        "PROACTIVE_COOLDOWN" to HelpContent.PROACTIVE_COOLDOWN,
        "PROACTIVE_SENSITIVITY" to HelpContent.PROACTIVE_SENSITIVITY,
        "PHONE_ONLY" to HelpContent.PHONE_ONLY,
        "BALL_OPACITY" to HelpContent.BALL_OPACITY,
        "BUBBLE_OPACITY" to HelpContent.BUBBLE_OPACITY,
        "SPEECH" to HelpContent.SPEECH,
        "NO_INTERCEPTION" to HelpContent.NO_INTERCEPTION,
        "LOCK_PIN" to HelpContent.LOCK_PIN,
        "AUDIT" to HelpContent.AUDIT,
        "MODEL_CONTEXT_WINDOW" to HelpContent.MODEL_CONTEXT_WINDOW,
        "MODEL_OUTPUT" to HelpContent.MODEL_OUTPUT,
        "MODEL_INPUT_IMAGE" to HelpContent.MODEL_INPUT_IMAGE,
        "MODEL_THINKING" to HelpContent.MODEL_THINKING,
        "PROVIDER_PROTOCOL" to HelpContent.PROVIDER_PROTOCOL,
        "PROVIDER_TIMEOUT" to HelpContent.PROVIDER_TIMEOUT,
        "FETCH_MODELS" to HelpContent.FETCH_MODELS,
    )

    @Test
    fun allTwentyTwoExplanationsHaveAnOptionNameTitleAndABody() {
        assertEquals("HelpContent must expose the 22 briefed explanations", 22, all.size)
        all.forEach { (name, text) ->
            assertTrue("$name needs a title", text.title.isNotBlank())
            assertTrue("$name needs a body", text.body.isNotBlank())
        }
        assertEquals("an option title is the popover key, so titles must be unique", 22, all.values.map { it.title }.toSet().size)
    }

    @Test
    fun noUserVisibleExplanationUsesTheRetiredArchivedWording() {
        all.forEach { (name, text) ->
            listOfNotNull(text.title, text.body, text.impact).forEach { visible ->
                assertFalse("$name must not contain the retired wording: $visible", visible.contains("档案"))
            }
        }
    }

    @Test
    fun theRestartSentenceIsVerbatimOnEverySettingThatOnlyAppliesAtLaunch() {
        // Appendix A1: the shared restart qualifier. The per-option texts repeat it word for word.
        val restartSentence =
            "仅在 App 启动时读取一次（组合为进程级单例），修改后需完全重启 App 才会生效，重新开始会话不会重新读取"
        listOf(
            HelpContent.WAKE_WORDS,
            HelpContent.WHITELIST,
            HelpContent.MAX_LEVEL,
            HelpContent.PROACTIVE_COOLDOWN,
            HelpContent.PROACTIVE_SENSITIVITY,
        ).forEach { text ->
            assertTrue("${text.title} must keep the appendix A1 restart sentence", text.body.contains(restartSentence))
            assertTrue(
                "${text.title} must say the restart is what makes it apply, was ${text.impact}",
                text.impact.orEmpty().contains("重启"),
            )
        }
        assertTrue(
            "持续对话 also only applies after a restart",
            HelpContent.CONTINUE_DIALOGUE.impact.orEmpty().contains("重启"),
        )
    }

    @Test
    fun theMigratedAssistantAndSecurityLimitsSurvive() {
        assertTrue(HelpContent.CONTINUE_DIALOGUE.body.contains("不发 status 5"))
        assertTrue(
            "A3's phone-only note is kept word for word",
            HelpContent.CONTINUE_DIALOGUE.body.contains(SmartSettingsForm.CONTINUE_DIALOGUE_PHONE_ONLY_NOTE),
        )

        assertTrue(HelpContent.VISION.body.contains("保存后立即生效"))
        assertTrue(HelpContent.VISION.body.contains("风险提示："))
        assertTrue("A5's examples are the risk boundary", HelpContent.VISION.body.contains("密码、验证码、银行卡号"))
        assertTrue(HelpContent.VISION.body.contains("只记录计数和标识"))
        assertTrue(HelpContent.VISION.body.contains("不会停止发送屏幕文字"))
        assertTrue(HelpContent.VISION.impact.orEmpty().contains("立即"))

        assertEquals("A6 moves into the popover verbatim", NoInterceptionUi.SWITCH_SUMMARY, HelpContent.NO_INTERCEPTION.body)
        assertTrue(HelpContent.NO_INTERCEPTION.body.contains("重启后仍保持"))
        assertTrue(HelpContent.NO_INTERCEPTION.body.contains("只有手动关闭才退出"))
        assertTrue(HelpContent.NO_INTERCEPTION.body.contains("可能无法撤销"))

        assertEquals("A11 moves into the popover verbatim", DetachUi.SWITCH_SUMMARY, HelpContent.PHONE_ONLY.body)
        assertTrue(HelpContent.PHONE_ONLY.body.contains("不是上传授权"))
        assertTrue(HelpContent.PHONE_ONLY.impact.orEmpty().contains("下次开始会话"))

        assertTrue(HelpContent.PROACTIVE.body.contains(DetachUi.PROACTIVE_DETACHED_NOTE))
        assertTrue(HelpContent.PROACTIVE.body.contains("一次配置、长期生效"))
        assertTrue(HelpContent.PROACTIVE.body.contains("不会被任何路径自动打开"))
        assertTrue(HelpContent.PROACTIVE.impact.orEmpty().contains("立即"))

        assertTrue(HelpContent.SPEECH.body.contains("关闭后不会自动打开"))
        assertTrue(HelpContent.SPEECH.body.contains("唤醒后的短提示音"))

        assertTrue(HelpContent.LOCK_PIN.body.contains("Keystore"))
        assertTrue(HelpContent.LOCK_PIN.body.contains("不写日志、不上传"))
        assertTrue(HelpContent.LOCK_PIN.body.contains("4-16 位数字"))

        assertTrue(HelpContent.AUDIT.body.contains("不含通知正文、屏幕文字、转写内容或电话号码"))
        assertTrue(HelpContent.AUDIT.body.contains("最多保留最近 200 条"))

        listOf(HelpContent.BALL_OPACITY, HelpContent.BUBBLE_OPACITY).forEach { text ->
            assertTrue(
                "${text.title} keeps A14's mode-off qualifier",
                text.body.contains("模式开启后生效：纯手机模式关闭中，滑块只预览数值并保存设置"),
            )
            assertTrue(text.impact.orEmpty().contains("立即"))
        }
        assertTrue(HelpContent.BUBBLE_OPACITY.body.contains("42%"))
        assertTrue(HelpContent.BUBBLE_OPACITY.body.contains("2s 后消失"))

        assertTrue(HelpContent.MAX_LEVEL.body.contains("默认上限为 L1，如需 L3/L4 请在此明确选择"))
        listOf("L0", "L2", "L3", "L4").forEach { level ->
            assertTrue("the L0–L4 explanation names $level", HelpContent.MAX_LEVEL.body.contains(level))
        }
        assertTrue(HelpContent.WHITELIST.body.contains("action|scope"))
    }

    @Test
    fun theModelExplanationsFollowTheDesignLimits() {
        assertTrue(HelpContent.MODEL_CONTEXT_WINDOW.body.contains("输入预算"))
        assertTrue(HelpContent.MODEL_CONTEXT_WINDOW.body.contains("安全余量"))
        assertTrue("§5.6 requires the budget to be positive", HelpContent.MODEL_CONTEXT_WINDOW.body.contains("必须大于 0"))

        assertTrue(HelpContent.MODEL_OUTPUT.body.contains("最大输出"))
        assertTrue(HelpContent.MODEL_OUTPUT.body.contains("输入预算"))

        assertTrue(HelpContent.MODEL_INPUT_IMAGE.body.contains("未接入（后续版本评估开放）"))
        assertTrue(HelpContent.MODEL_INPUT_IMAGE.body.contains("不支持图片，助手不可用"))
        assertTrue(HelpContent.MODEL_INPUT_IMAGE.body.contains("未验证"))

        assertFalse(
            "0.53 removed the 0.52 tool-turn fallback sentence",
            HelpContent.MODEL_THINKING.body.contains("工具回合会自动关闭思考"),
        )
        assertTrue(
            "the revised design names the DeepSeek-only official protocol",
            HelpContent.MODEL_THINKING.body.contains("仅支持 DeepSeek 官方 thinking 协议"),
        )
        assertTrue("the official output default is named", HelpContent.MODEL_THINKING.body.contains("64K/128K"))
        assertTrue("the tool turn keeps thinking", HelpContent.MODEL_THINKING.body.contains("工具回合保留思考"))
        assertTrue("thinking stays in session memory", HelpContent.MODEL_THINKING.body.contains("会话内存"))
        assertTrue("thinking is never logged", HelpContent.MODEL_THINKING.body.contains("不写日志"))
        assertTrue("the closed-state caveat is stated", HelpContent.MODEL_THINKING.body.contains("不主动请求"))
        assertTrue(
            "a server-side default thinking is kept and replayed",
            HelpContent.MODEL_THINKING.body.contains("保留并回传其思考内容"),
        )
        assertTrue(HelpContent.MODEL_THINKING.body.contains("开启更稳但更慢、消耗更多思考 token"))
        assertTrue(HelpContent.MODEL_THINKING.body.contains("默认关闭以保持当前速度"))

        assertTrue(HelpContent.PROVIDER_PROTOCOL.body.contains("OpenAI Responses"))
        assertTrue(HelpContent.PROVIDER_PROTOCOL.body.contains("高级"))
        assertTrue("both protocols support DeepSeek thinking", HelpContent.PROVIDER_PROTOCOL.body.contains("思考"))
        assertTrue(HelpContent.PROVIDER_TIMEOUT.body.contains("30000"))
        assertTrue("the gateway fetch timeout is a different, fixed 10 seconds", HelpContent.PROVIDER_TIMEOUT.body.contains("10 秒"))

        assertTrue(HelpContent.FETCH_MODELS.body.contains("/models"))
        assertTrue(HelpContent.FETCH_MODELS.body.contains("10 秒"))
        assertTrue(HelpContent.FETCH_MODELS.body.contains("手动新增"))
    }

    @Test
    fun impactLinesFollowTheSaveSemantics() {
        listOf(
            HelpContent.WAKE_WORDS,
            HelpContent.WHITELIST,
            HelpContent.MAX_LEVEL,
            HelpContent.CONTINUE_DIALOGUE,
            HelpContent.PROACTIVE_COOLDOWN,
            HelpContent.PROACTIVE_SENSITIVITY,
        ).forEach { text ->
            assertTrue("${text.title} is a restart field", text.impact.orEmpty().contains("重启"))
        }
        listOf(
            HelpContent.VISION,
            HelpContent.PROACTIVE,
            HelpContent.BALL_OPACITY,
            HelpContent.BUBBLE_OPACITY,
            HelpContent.SPEECH,
            HelpContent.NO_INTERCEPTION,
            HelpContent.LOCK_PIN,
        ).forEach { text ->
            assertTrue("${text.title} applies immediately", text.impact.orEmpty().contains("立即"))
        }
        assertNull("the audit page is read-only, it has no effect line", HelpContent.AUDIT.impact)
        assertNull("fetching a catalog is an action, not a saved setting", HelpContent.FETCH_MODELS.impact)
    }
}
