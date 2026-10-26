package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.storage.AndroidProfileRepository
import com.sheen.rokid.phone.app.EdithApplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.model.ProfileForm
import com.sheen.rokid.plugin.*
import java.io.File
import java.util.Properties

/**
 * Debug-only receiver that creates a model profile from `.local/p5-omni.properties` and optionally runs a probe.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a edith.DEBUG_CREATE_PROFILE -n com.sheen.rokid.phone/.DebugProfileCreator
 * ```
 *
 * Reads the properties file from the app's files directory (pushed there beforehand) or from the path in EXTRA_PATH.
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
            // 0.25 R74: the assistant is TEXT+IMAGE. This debug creator must not seed a profile the session gate would
            // refuse; the Chat protocol keeps its pre-existing AUDIO input as an extra, it no longer substitutes for IMAGE.
            val inputs = if (protocolId == "OPENAI_CHAT_COMPLETIONS")
                setOf(InputModality.TEXT, InputModality.IMAGE, InputModality.AUDIO)
            else setOf(InputModality.TEXT, InputModality.IMAGE)

            val apiKey = prop("apiKey")
            require(apiKey.all { it.code in 33..126 }) { "apiKey contains non-printable chars" }

            val usableWindow = prop("usableWindowTokens").toInt()
            val inputBudget = prop("inputBudgetTokens").toInt()
            val maxOutput = prop("maxOutputTokens").toInt()
            val form = ProfileForm(
                name = "QwenOmni",
                service = prop("service"),
                baseUrl = prop("baseUrl"),
                model = prop("model"),
                protocolId = protocolId,
                maxInputK = (inputBudget / 1024).toString(),
                maxOutputK = (maxOutput / 1024).coerceAtLeast(1).toString(),
                timeoutMillis = prop("timeoutMillis"),
                inputs = inputs,
                outputs = setOf(OutputModality.TEXT),
            )
            val draft = form.draft("qwen-omni-dev", null, true)
            val repo = root.profiles as? com.sheen.rokid.phone.storage.AndroidProfileRepository
                ?: throw IllegalStateException("profiles is not AndroidProfileRepository")
            val saved = repo.saveWithSecret(draft, apiKey.toCharArray())

            android.util.Log.i("EdithP5", "DEBUG_CREATE_PROFILE: created profile id=${saved.id} protocol=$protocolId inputs=$inputs revision=${saved.revision}")

            // 0.4 Task 9: the profile list's own 「选择」 control is a Compose tap the installed probe cannot reach
            // either, so the debug creator can make the same repository selection the page makes (`ProfileViewModel
            // .select` -> `AndroidProfileRepository.select`). Optional, so every existing caller is unchanged; the
            // log names the id only, never the secret.
            if (intent.getBooleanExtra(EXTRA_SELECT, false)) {
                repo.select(PLUGIN_ID, saved.id)
                android.util.Log.i("EdithP5", "DEBUG_CREATE_PROFILE: selected id=${saved.id} plugin=$PLUGIN_ID")
            }

            // Optionally run a text probe to verify connectivity
            if (intent.getBooleanExtra(EXTRA_PROBE, false)) {
                root.profileTester?.let { tester ->
                    var result = "pending"
                    val handle = tester.testText(saved.id) { event ->
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
        /** 0.4 Task 9: also select the saved profile for the smart assistant (`--ez select true`). */
        const val EXTRA_SELECT = "select"
        private const val PLUGIN_ID = "smart-assistant"
    }
}
