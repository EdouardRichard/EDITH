package edith.survey.shizukuprobe

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * TEMPORARY survey probe (2026-10-16).
 *
 * It answers one question that the PC's `adb shell` cannot answer about itself: when a command is executed **inside the
 * device** by the privileged process Shizuku provides, what user, what SELinux context and what latency does it
 * actually have? The delivery app's `ShizukuUserServiceShell` cannot be asked — the device is the acceptance device and
 * its provisioned state is not ours to disturb — so this throwaway app asks the same question with the same pinned
 * `dev.rikka.shizuku` artifacts.
 *
 * Every step is logged under one tag, and the full result is written to the app's external files dir so `adb pull` can
 * read it without root. Uninstalled after the survey.
 */
class MainActivity : Activity() {

    private var remote: IProbeShell? = null
    private var boundBinder: IBinder? = null
    private lateinit var status: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Probe.log("USER_SERVICE_CONNECTED binder=${binder != null} ping=${binder?.pingBinder()}")
            boundBinder = binder
            remote = binder?.takeIf { it.pingBinder() }?.let { IProbeShell.Stub.asInterface(it) }
            status.text = "user service connected"
            runMeasurements()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Probe.log("USER_SERVICE_DISCONNECTED")
            remote = null
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Probe.log("PERMISSION_RESULT requestCode=$requestCode grantResult=$grantResult " +
            "(0=GRANTED, -1=DENIED)")
        if (grantResult == PackageManager.PERMISSION_GRANTED) bindUserService() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f; setPadding(48, 96, 48, 48) }
        setContentView(LinearLayout(this).apply { addView(status) })

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky { Probe.log("BINDER_RECEIVED") }
        Shizuku.addBinderDeadListener { Probe.log("BINDER_DEAD") }

        Probe.log("PROBE_START sdk=${android.os.Build.VERSION.SDK_INT} " +
            "model=${android.os.Build.MODEL} shizukuApi=${Shizuku.getVersion()}")

        if (!Shizuku.pingBinder()) {
            // The server is not up: nothing can be measured until Shizuku's own process is running.
            Probe.log("SERVER_NOT_RUNNING pingBinder=false")
            status.text = "Shizuku server is not running.\nStart Shizuku, then relaunch this app."
            finish()
            return
        }
        when {
            Shizuku.isPreV11() -> { Probe.log("PRE_V11"); finish() }
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                Probe.log("PERMISSION_ALREADY_GRANTED")
                bindUserService()
            }
            else -> {
                Probe.log("REQUESTING_PERMISSION — a dialog should now be on screen")
                status.text = "Requesting Shizuku permission…\nTap Allow."
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        runCatching {
            Shizuku.unbindUserService(args, connection, true)
        }
        super.onDestroy()
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ProbeShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("probe")
            .debuggable(false)
            .version(1)
    }

    private fun bindUserService() {
        Probe.log("BINDING_USER_SERVICE")
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Probe.log("BIND_FAILED ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Runs once the UserService is connected. Every block is wrapped: a block that throws must still leave the others'
     * evidence behind, because a half-measured run is worth more than no run when each needs a live device.
     */
    private fun runMeasurements() {
        val binder = remote
        if (binder == null) {
            Probe.log("NO_REMOTE_AT_MEASURE_TIME")
            finish()
            return
        }
        val probe = ProbeShell(binder)
        val report = StringBuilder()

        fun block(title: String, body: () -> String) {
            val text = runCatching(body).getOrElse { "BLOCK_THREW ${it.javaClass.simpleName}: ${it.message}" }
            report.appendLine().appendLine("##### $title #####").appendLine(text)
            text.lineSequence().forEach { Probe.log("[$title] $it") }
        }
        Probe.log("SERVER_REPORTED_API_VERSION=${Shizuku.getVersion()} SERVER_UID=${Shizuku.getUid()}")
        block("IDENTITY") { Probe.identityBlock(probe) }
        block("LATENCY") { Probe.latencyBlock(probe, items = 10) }
        block("PAYLOAD") { Probe.payloadBlock(probe, sizesKb = listOf(16, 256, 512, 768, 900, 1024, 1200)) }
        block("UIREAD") { Probe.uiReadBlock(probe) }
        block("UIREAD_FOREGROUND") { Probe.uiReadForegroundBlock(probe) }

        Probe.writeResult("survey.txt", report.toString())
        Probe.log("RESULT_WRITTEN ${Probe.RESULT_DIR}/survey.txt")
        status.text = "done — see logcat tag ShizukuSurvey"
        finish()
    }

    private companion object {
        const val REQUEST_CODE = 4213
    }
}
