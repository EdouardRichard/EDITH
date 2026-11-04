package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.ModelProfileProjection
import com.sheen.rokid.phone.model.catalog.Provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import java.io.File
import java.util.Properties

/**
 * Debug-only receiver that seeds one catalog provider/model from `.local/p5-omni.properties` and optionally probes it.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a edith.DEBUG_CREATE_PROFILE -n com.sheen.rokid.phone/.DebugProfileCreator
 * ```
 *
 * Reads the properties file from the app's files directory (pushed there beforehand) or from the path in EXTRA_PATH.
 *
 * 0.52: the persisted model configuration is the encrypted catalog, so this instrument creates a **provider with one
 * model** rather than a profile. The provider/model ids are logged and the API key is only handed to the catalog
 * repository, which stages it into the Keystore; no log line carries it. `--ez select true` makes the new provider the
 * globally active one, exactly the state the settings UI would produce.
 */
class DebugProfileCreator : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? EdithApplication ?: return
        val root = app.composition

        try {
            // Read properties from the path specified or the default location
            val path = intent.getStringExtra(EXTRA_PATH)
                ?: "/data/data/com.sheen.rokid.phone/files/p5-omni.properties"
            val file = File(path)
            if (!file.exists()) {
                android.util.Log.w("EdithP5", "DEBUG_CREATE_PROFILE: file not found: $path")
                return
            }
            val props = Properties().apply { file.inputStream().use { load(it) } }

            fun prop(name: String): String = props.getProperty(name)?.trim()
                ?: throw IllegalArgumentException("missing property: $name")

            val protocolId = prop("protocolId")
            // 0.25 R74: the assistant is TEXT+IMAGE. This debug creator must not seed a provider the session gate would
            // refuse; the Chat protocol keeps its pre-existing AUDIO input as an extra, it no longer substitutes for IMAGE.
            val inputs = if (protocolId == "OPENAI_CHAT_COMPLETIONS") {
                setOf(InputModality.TEXT, InputModality.IMAGE, InputModality.AUDIO)
            } else {
                setOf(InputModality.TEXT, InputModality.IMAGE)
            }

            val apiKey = prop("apiKey")
            require(apiKey.all { it.code in 33..126 }) { "apiKey contains non-printable chars" }

            // The old form's "max input K" was derived from inputBudgetTokens, and its capacity became the profile's
            // usable window; passing the same numbers keeps the debug route's budget identical to the pre-catalog one.
            val contextTokens = prop("inputBudgetTokens").toInt()
            val maxOutput = prop("maxOutputTokens").toInt()
            val modelId = prop("model")
            val providerId = "qwen-omni-dev"
            val provider = Provider(
                id = providerId,
                name = "QwenOmni",
                baseUrl = prop("baseUrl"),
                protocolId = protocolId,
                timeoutMillis = prop("timeoutMillis").toLong(),
                models = listOf(
                    ModelEntry(
                        id = modelId,
                        displayName = modelId,
                        contextWindowTokens = contextTokens,
                        maxOutputTokens = maxOutput,
                        inputs = inputs,
                        outputs = setOf(OutputModality.TEXT),
                        origin = ModelOrigin.MANUAL,
                    ),
                ),
            )
            val catalog = root.modelCatalogRepository
                ?: throw IllegalStateException("model catalog unavailable")
            val saved = catalog.saveProvider(provider, apiKey.toCharArray())

            android.util.Log.i(
                "EdithP5",
                "DEBUG_CREATE_PROFILE: created provider id=${saved.id} model=$modelId " +
                    "protocol=$protocolId inputs=$inputs revision=${saved.revision}",
            )

            if (intent.getBooleanExtra(EXTRA_SELECT, false)) {
                catalog.setActiveProvider(saved.id)
                catalog.selectModel(modelId)
                android.util.Log.i("EdithP5", "DEBUG_CREATE_PROFILE: activated provider=${saved.id} model=$modelId")
            }

            // Optionally run a text probe to verify connectivity.
            if (intent.getBooleanExtra(EXTRA_PROBE, false)) {
                root.profileTester?.let { tester ->
                    var result = "pending"
                    val profileId = ModelProfileProjection.profileId(saved.id, modelId)
                    val handle = tester.testText(profileId) { event ->
                        when (event) {
                            is ModelStreamEvent.Terminal -> {
                                result = "terminal:${event.end}"
                                android.util.Log.i("EdithP5", "DEBUG_CREATE_PROFILE probe: $result")
                            }
                            else -> {}
                        }
                    }
                    // Wait up to 30s for the probe to complete
                    Thread.sleep(30_000)
                    handle.cancel()
                    android.util.Log.i("EdithP5", "DEBUG_CREATE_PROFILE probe result: $result")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EdithP5", "DEBUG_CREATE_PROFILE failed: ${e.message}", e)
        }
    }

    companion object {
        const val ACTION = "edith.DEBUG_CREATE_PROFILE"
        const val EXTRA_PATH = "path"
        const val EXTRA_PROBE = "probe"
        /** 0.4 Task 9: also activate the saved provider/model (`--ez select true`). */
        const val EXTRA_SELECT = "select"
    }
}
