package com.sheen.rokid.phone.session
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.app.EdithApplication

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
    private val root get() = (application as EdithApplication).composition
    private var ownsForeground = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            root.state.collect {
                if (ownsForeground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val redelivered = flags and (START_FLAG_REDELIVERY or START_FLAG_RETRY) != 0
        if (action == START) {
            try {
                val admitted = root.dispatch(action, intent.getStringExtra(TOKEN), redelivered, bluetoothGranted(this), beforeStart = {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW))
                    ServiceCompat.startForeground(this, NOTIFICATION, notification(),
                        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                    ownsForeground = true
                })
                if (!admitted) { if (!ownsForeground) stopSelf(startId); return START_NOT_STICKY }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
            } catch (_: RuntimeException) {
                root.stop()
                root.error("FOREGROUND_START_DENIED")
                if (ownsForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                ownsForeground = false
                stopSelf(startId)
            }
        } else {
            root.dispatch(action, redelivered = redelivered, permissionGranted = bluetoothGranted(this), foregroundActive = ownsForeground)
            if (action == STOP || (!ownsForeground && action != START)) {
                if (ownsForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                ownsForeground = false
                stopSelf(startId)
            } else if (ownsForeground) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
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
        if (ownsForeground) root.stop()
        ownsForeground = false
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
            if (!activity.hasWindowFocus()) {
                (activity.application as EdithApplication).composition.error("FOREGROUND_UI_REQUIRED")
                return
            }
            val root = (activity.application as EdithApplication).composition
            val intent = Intent(activity, SessionService::class.java).setAction(START)
                .putExtra(TOKEN, root.authorizeStart(pluginId))
            try { ContextCompat.startForegroundService(activity, intent) } catch (_: RuntimeException) {
                root.stop(); root.error("FOREGROUND_START_DENIED")
            }
        }

        fun commandFromUi(context: Context, action: String) {
            val root = (context.applicationContext as EdithApplication).composition
            // Apply teardown immediately even if the OS refuses a service command.
            if (action == PAUSE || action == STOP) root.dispatch(action, permissionGranted = bluetoothGranted(context))
            try { context.startService(Intent(context, SessionService::class.java).setAction(action)) } catch (_: RuntimeException) {
                if (action == STOP) context.stopService(Intent(context, SessionService::class.java))
            }
        }
    }
}
