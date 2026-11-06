package com.sheen.rokid.phone.ui.settings

/**
 * 0.52 design §3.1: one level of the settings navigation. The home is the empty stack; every row pushes exactly one of
 * these routes, and the shell renders it as a [com.sheen.rokid.phone.ui.PhoneDetailPage].
 *
 * A route carries its own header title so the shell never has to duplicate the mapping, and the parameterised routes
 * carry the id their page will read (Task 13/14) rather than "the current one" - a stack entry must keep pointing at the
 * provider/model it was opened for.
 */
internal sealed interface SettingsRoute {
    val title: String

    data object ModelService : SettingsRoute {
        override val title = "模型服务"
    }

    data object Providers : SettingsRoute {
        override val title = "服务提供商"
    }

    data object AddProvider : SettingsRoute {
        override val title = "添加提供商"
    }

    data class ProviderEditor(val providerId: String) : SettingsRoute {
        override val title = "编辑提供商"
    }

    data class ModelDetail(val modelId: String) : SettingsRoute {
        override val title = "模型详情"
    }

    data object AssistantBehavior : SettingsRoute {
        override val title = "助手行为"
    }

    data object RuntimeAppearance : SettingsRoute {
        override val title = "运行与外观"
    }

    data object PermissionsSecurity : SettingsRoute {
        override val title = "权限与安全"
    }

    data object LocalCapabilities : SettingsRoute {
        override val title = "本地能力"
    }

    data object Audit : SettingsRoute {
        override val title = "审计记录"
    }

    data object About : SettingsRoute {
        override val title = "关于"
    }
}
