// SPDX-License-Identifier: Apache-2.0
package edith.phone.laya

import android.content.Context
import com.laya.LayaEngine
import edith.plugin.LayaInferenceEngine
import edith.plugin.SystemOneAnswer
import edith.plugin.SystemOneQuestion

/**
 * The production Laya engine: the publisher's host behind the contract's port.
 *
 * **Construction is deferred into [initialize].** Building the host parses the tokenizer, parses the calibration and
 * memory-maps the token table — about 3.3 s on the delivery target — and must not happen on the UI thread.
 * `SystemOneServiceImpl` runs [initialize] on its own loader thread, so building it there is what keeps the
 * constraint satisfied without this class having to own a thread.
 *
 * **One host per process.** Compiled graphs are cached per `(window, backend, storage)` and the K40 measured that a
 * handful of `CompiledModel` instantiations in one process makes `create` fail outright, so this holds exactly one
 * and [close] is the only thing that releases it. Spec §2.5's "load once, stay resident" is not a preference here,
 * it is the hardware's condition.
 *
 * **The backend defaults to CPU, and the delivery target should use GPU.** The A5 evidence measured 742 ms CPU
 * against 231 ms GPU on the K70 for this graph and concluded "use the GPU backend for the proactive gate on the
 * delivery target", with the GPU correct on LiteRT 2.2.0. CPU is the default here only because it is the path that
 * cannot fail for want of a vendor runtime on the first device run; switching is one constructor argument, and the
 * GPU path additionally costs ~3.4 s of compile at startup.
 */
class LayaInferenceEngineImpl(
    private val context: Context,
    private val backend: LayaEngine.Backend = LayaEngine.Backend.CPU,
) : LayaInferenceEngine {

    @Volatile
    private var host: LayaOfficialHost? = null

    override fun initialize(): Boolean = try {
        val created = LayaOfficialHost(context)
        created.initialize(backend, LayaOfficialHost.WINDOW)
        host = created
        true
    } catch (_: Throwable) {
        // `SystemOneServiceImpl` turns a false into UNAVAILABLE, which is exactly the honest answer: the plugin then
        // refuses service rather than answering from a model that never loaded (spec §1.3 C2).
        false
    }

    override fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer {
        val current = host ?: error("Laya is not initialized")
        return current.ask(state, question, backend)
    }

    override fun close() {
        host?.close()
        host = null
    }
}
