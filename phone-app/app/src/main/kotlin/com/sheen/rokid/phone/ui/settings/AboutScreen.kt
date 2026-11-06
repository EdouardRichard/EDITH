package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.BuildConfig

/**
 * 0.52 design §3.8/§4.3: the About page's copy and its build-version line.
 *
 * [versionLine] takes the version as an argument; the page passes `BuildConfig.VERSION_NAME`, so bumping the build
 * version updates About and the home summary together and no literal can go stale. The privacy statement uses the
 * 0.52 product term 模型服务 instead of the retired 模型配置 wording.
 */
internal object AboutUi {
    const val MODEL_SERVICE = "模型服务"
    const val MODEL_SERVICE_DETAIL = "配置提供商与模型"
    const val OPEN_MODEL_SERVICE = "打开模型服务"
    const val PRIVACY_TITLE = "隐私说明（只读）"
    const val PRIVACY_BODY =
        "模型服务配置与 API Key 只保存在本机加密存储中；屏幕与通知内容只在会话运行时随请求发送到你配置的模型服务。" +
            "本页只展示信息，不收集、不上传任何数据，也没有任何开关。"

    /** `EDITH 0.5.3 · 预览版`: the same line the home's About row shows. */
    fun versionLine(versionName: String): String = "EDITH $versionName · 预览版"

    fun buildTypeLine(debug: Boolean): String = "构建类型：${if (debug) "调试版" else "发布版"}"
}

/**
 * 0.52 design §3.8/§6.3: the About page - version, build type, the model-service entry and the read-only privacy
 * statement. It introduces no setting of its own and does not repeat the home's other entries.
 *
 * The version is read from `BuildConfig.VERSION_NAME` (never a literal), so bumping the build version updates this page
 * and the home's summary together; the build type is the variant's own `DEBUG` flag.
 */
@Composable
internal fun AboutScreen(onOpenModelService: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(AboutUi.versionLine(BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleLarge)
        Text(
            AboutUi.buildTypeLine(BuildConfig.DEBUG),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            ListItem(
                headlineContent = { Text(AboutUi.MODEL_SERVICE) },
                supportingContent = { Text(AboutUi.MODEL_SERVICE_DETAIL) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = AboutUi.OPEN_MODEL_SERVICE, onClick = onOpenModelService)
                    .semantics(mergeDescendants = true) {},
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text(AboutUi.PRIVACY_TITLE, style = MaterialTheme.typography.titleMedium)
        Text(AboutUi.PRIVACY_BODY, style = MaterialTheme.typography.bodySmall)
    }
}
