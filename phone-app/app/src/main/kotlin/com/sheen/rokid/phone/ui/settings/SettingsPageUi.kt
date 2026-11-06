package com.sheen.rokid.phone.ui.settings

/**
 * 0.52 design §4.4: the copy shared by every settings **form** page.
 *
 * The restart sentence and the 「放弃修改？」 dialog are page-level conventions, not one page's wording. Task 9 introduced
 * them on 助手行为; Task 11's 权限与安全 uses the same two exits and the same restart semantics, so they live here once
 * and both pages reference them. [AssistantBehaviorUi] keeps its existing constant names as aliases so Task 9's tests
 * and call sites stay source-compatible.
 *
 * Copy only: no Compose, no host types.
 */
internal object SettingsPageUi {

    /** The persistent 「需重启」 note at the top of a form page (design §3.3/§4.2). */
    const val RESTART_NOTE = "带「需重启」的项会在完全关闭并重新打开 App 后生效。"

    /** The row badge on a restart field. */
    const val RESTART_CHIP = "需重启"

    /** The dirty-back confirmation's title, body and two answers (design §4.4). */
    const val DISCARD_TITLE = "放弃修改？"
    const val DISCARD_BODY = "当前页面有未保存的修改。"
    const val DISCARD_CONFIRM = "放弃"
    const val DISCARD_KEEP = "继续编辑"

    /** The form's explicit save button. */
    const val SAVE = "保存"
}
