package com.sheen.rokid.phone.session
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.model.ModelStartGate

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sheen.rokid.contracts.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class SessionService : Service() {
    private val app get() = application as EdithApplication

    /**
     * The composition **only if it was already built**. The service must never construct it before the bundled
     * models are ready (0.4 Task 3 / spec §6.3); the start path asks [EdithApplication.modelReadiness] first.
     */
    private val liveRoot get() = app.liveComposition
    private var ownsForeground = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // The notification's text follows the session state. The service is only started from the ready UI, so the
        // composition normally exists; when it does not, there is no session to reflect and the command path below
        // stops the service rather than building a composition early.
        val root = liveRoot ?: return
        serviceScope.launch {
            root.state.collect {
                if (ownsForeground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(root))
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val redelivered = flags and (START_FLAG_REDELIVERY or START_FLAG_RETRY) != 0
        if (action == START) {
            // 0.4 Task 3 / spec §6.3: the gate runs **before** `beforeStart`/`startForeground`, so a start while the
            // models are preparing (or after a failed preparation) leaves no foreground service behind.
            ModelStartGate.refusal(app.modelReadiness.state.value)?.let { refusal ->
                liveRoot?.error(refusal)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            // Building the composition can still fail (a broken asset, an internal wiring fault). That is no reason to
            // crash the service: stop without a foreground notification and let the page surface the readiness state.
            val root = try {
                app.composition
            } catch (_: RuntimeException) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            try {
                // 0.4 Task 6 (`D199`): the foreground service type and the permission gate both follow the detach
                // gate. A detached session captures the phone microphone, so it starts as `microphone` and requires
                // `RECORD_AUDIO` (never Bluetooth); glasses mode keeps `connectedDevice` and the existing Bluetooth
                // gate exactly. The type is chosen inside `beforeStart`, immediately before `startForeground`, so the
                // declaration and the running service cannot disagree about the mode.
                val detached = root.detachGate.enabled.value
                val admitted = root.dispatch(action, intent.getStringExtra(TOKEN), redelivered,
                    SessionPermissions.grantedForStart(this, detached), beforeStart = {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW))
                    ServiceCompat.startForeground(this, NOTIFICATION, notification(root),
                        if (Build.VERSION.SDK_INT >= 29) {
                            if (detached) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        } else 0)
                    ownsForeground = true
                })
                if (!admitted) { if (!ownsForeground) stopSelf(startId); return START_NOT_STICKY }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(root))
            } catch (_: RuntimeException) {
                root.stop()
                root.error("FOREGROUND_START_DENIED")
                if (ownsForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                ownsForeground = false
                stopSelf(startId)
            }
        } else {
            // Commands never build the composition: with no composition there is no running session to control.
            val root = liveRoot
            if (root == null) { stopSelf(startId); return START_NOT_STICKY }
            // The mode is a property of the running session, so it is read from the live gate. A pause/stop passes the
            // flag through unchanged; RESUME is the command that re-arms capture, so it must ask the same question a
            // start does (a revoked microphone must not resume into a silent session).
            root.dispatch(action, redelivered = redelivered,
                permissionGranted = SessionPermissions.grantedForStart(this, root.detachGate.enabled.value),
                foregroundActive = ownsForeground)
            if (action == STOP || (!ownsForeground && action != START)) {
                if (ownsForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                ownsForeground = false
                stopSelf(startId)
            } else if (ownsForeground) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(root))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(root: HostCompositionRoot): Notification {
        val paused = root.state.value.session == SessionState.Paused
        fun command(action: String, requestCode: Int) = PendingIntent.getService(this, requestCode,
            Intent(this, SessionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.paused else R.string.session_active))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (paused) R.string.resume else R.string.pause), command(if (paused) RESUME else PAUSE, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), command(STOP, 2)).build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        val wasForeground = ownsForeground
        ownsForeground = false
        if (wasForeground) {
            // 0.4 Task 9 fix round 2: a destroyed foreground service must not leave its notification behind. The detach
            // gate's stop path now destroys the service directly (`Context.stopService`) instead of sending the STOP
            // command, so this destroy path must remove the notification itself. Device evidence caught two reasons it
            // cannot be left to the platform: a state change queued on the collector below re-posted 「会话已开启」
            // just after the gate's stop, and by the time `onDestroy` runs the system has already detached the
            // foreground state, so `stopForeground(REMOVE)` alone did not remove that later re-post (K40,
            // 2026-10-26: service gone from `dumpsys activity services` while the notification record survived).
            // Cancelling the scope first stops further updates; the direct notification cancel is what removes a
            // record that was posted after the system's own foreground cancel.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            // A service destroyed while it still owned the foreground is the only destroy path that did not already
            // tear the host session down; a STOP command sets `ownsForeground` false before `stopSelf`, so it does not
            // run `stop()` a second time.
            liveRoot?.stop()
        }
        super.onDestroy()
    }

    companion object {
        const val START = "edith.START"
        const val PAUSE = "edith.PAUSE"
        const val RESUME = "edith.RESUME"
        const val STOP = "edith.STOP"
        private const val TOKEN = "edith.explicitStart"
        private const val CHANNEL = "edith.session"
        private const val NOTIFICATION = 14

        internal fun bluetoothGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

        fun startFromUi(activity: Activity, pluginId: String) {
            val app = activity.application as EdithApplication
            // 0.4 Task 3: this path must neither build the composition nor start the service while models are not
            // ready. The UI only offers 开始 on the ready screen, so this is the backstop for a stale tap.
            ModelStartGate.refusal(app.modelReadiness.state.value)?.let { refusal ->
                app.liveComposition?.error(refusal)
                return
            }
            if (!activity.hasWindowFocus()) {
                app.liveComposition?.error("FOREGROUND_UI_REQUIRED")
                return
            }
            val root = app.composition
            val intent = Intent(activity, SessionService::class.java).setAction(START)
                .putExtra(TOKEN, root.authorizeStart(pluginId))
            try { ContextCompat.startForegroundService(activity, intent) } catch (_: RuntimeException) {
                root.stop(); root.error("FOREGROUND_START_DENIED")
            }
        }

        fun commandFromUi(context: Context, action: String) {
            val app = context.applicationContext as EdithApplication
            val root = app.liveComposition
            // Apply teardown immediately even if the OS refuses a service command.
            if (action == PAUSE || action == STOP) root?.dispatch(action, permissionGranted = bluetoothGranted(context))
            try { context.startService(Intent(context, SessionService::class.java).setAction(action)) } catch (_: RuntimeException) {
                if (action == STOP) context.stopService(Intent(context, SessionService::class.java))
            }
        }
    }
}
