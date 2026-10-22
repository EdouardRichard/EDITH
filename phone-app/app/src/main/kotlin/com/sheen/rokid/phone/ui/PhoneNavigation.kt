package com.sheen.rokid.phone.ui

/**
 * Phase C (spec §6.7 智能助手底部页签): the four pages of the assistant, in the order the spec fixes.
 *
 * The spec's table is the authority — 助手 / 技能 / 记忆 / 插件设置 — and the order is part of the requirement rather
 * than a layout choice: the assistant page is where the wearer lands, so it must be the first tab.
 */
enum class PhoneTab(val label: String) {
    ASSISTANT("助手"),
    SKILLS("技能"),
    MEMORY("记忆"),
    SETTINGS("设置"),
    ;

    companion object {
        /**
         * The plugin these four tabs belong to.
         *
         * Another plugin gets a body that says what it is rather than these tabs: the pages below read the smart
         * assistant's conversation, SKILLs and settings, and showing them under a different plugin would be the App
         * presenting one plugin's state as another's (spec §6.7 「主体：当前激活插件的专属 UI」).
         */
        const val SMART_ASSISTANT_PLUGIN_ID = "smart-assistant"
    }
}

/**
 * The tab for a remembered index, tolerating anything.
 *
 * The index survives process death in the saved state, so it can name a tab this build no longer has; falling back to
 * the assistant page keeps the screen usable instead of blank.
 */
fun phoneTabFor(index: Int): PhoneTab = PhoneTab.entries.getOrElse(index) { PhoneTab.ASSISTANT }

/**
 * What the top bar names (spec §6.7 顶部栏：当前激活插件名称).
 *
 * The selected plugin's own label when the picker knows it, its id when it does not, and an empty string when nothing
 * is selected. The id is deliberately preferred over a placeholder: it says which plugin is actually running, which is
 * the one fact this line exists to carry.
 */
fun pluginTitle(selectedPluginId: String?, plugins: List<Pair<String, String>>): String {
    val id = selectedPluginId ?: return ""
    return plugins.firstOrNull { it.first == id }?.second ?: id
}
