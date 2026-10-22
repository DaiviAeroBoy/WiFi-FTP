package com.daivik.wififtp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import java.io.File

class FtpService : Service() {

    companion object {
        const val ACTION_START = "com.daivik.wififtp.START"
        const val ACTION_PAUSE = "com.daivik.wififtp.PAUSE"
        const val ACTION_RESUME = "com.daivik.wififtp.RESUME"
        const val ACTION_STOP = "com.daivik.wififtp.STOP"
        private const val CH = "ftp_server"
        private const val NID = 1
        private const val BASE_PORT = 2121

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, FtpService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var server: FtpServer? = null
    private var fs: VirtualFs? = null
    private var pin: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { goForeground(); begin(intent) }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> startServer()
            ACTION_STOP -> stopAll(null)
            else -> if (server == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = build(UiState(running = true, url = "Starting…"))
        ServiceCompat.startForeground(
            this, NID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun begin(i: Intent) {
        val names = i.getStringArrayListExtra("names") ?: arrayListOf()
        val paths = i.getStringArrayListExtra("paths") ?: arrayListOf()
        val mounts = LinkedHashMap<String, File>()
        names.forEachIndexed { idx, n -> mounts[n] = File(paths[idx]) }
        fs = VirtualFs(mounts)
        pin = i.getStringExtra("pin")
        job?.cancel()
        job = scope.launch {
            ServerState.state.collect { s ->
                if (s.running) getSystemService(NotificationManager::class.java).notify(NID, build(s))
            }
        }
        startServer()
    }

    private fun startServer() {
        val ip = localIp()
        if (ip == null) { stopAll("Connect to Wi-Fi or turn on Hotspot first"); return }
        server?.stop()
        var started: FtpServer? = null
        var port = BASE_PORT
        while (started == null && port < BASE_PORT + 20) {
            try {
                val s = FtpServer(port, fs!!, pin)
                s.start()
                started = s
            } catch (e: Exception) { port++ }
        }
        if (started == null) { stopAll("Could not open a network port"); return }
        server = started
        acquireLocks()
        ServerState.state.value = UiState(running = true, paused = false, url = "ftp://$ip:$port", pin = pin)
    }

    private fun pause() {
        server?.stop()
        server = null
        releaseLocks()
        ServerState.state.update { it.copy(paused = true, clients = emptyList()) }
    }

    private fun stopAll(error: String?) {
        job?.cancel()
        server?.stop()
        server = null
        releaseLocks()
        ServerState.state.value = UiState(error = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop()
        releaseLocks()
        scope.cancel()
        ServerState.state.value = UiState()
        super.onDestroy()
    }

    @Suppress("WakelockTimeout")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wififtp:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wififtp:cpu").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        wifiLock = null
        wakeLock = null
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, FtpService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun build(s: UiState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "FTP server", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val devices = s.clients.size
        val text = if (s.paused) "Paused — tap Resume to share again"
        else "${s.url}  •  $devices device${if (devices == 1) "" else "s"} connected"
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (s.paused) "WiFi FTP paused" else "WiFi FTP is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (s.paused) "Resume" else "Pause", pi(if (s.paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Disconnect", pi(ACTION_STOP, 2))
        if (!s.paused && s.clients.isNotEmpty()) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(
                text + "\n" + s.clients.joinToString("\n") { if (it.host != it.ip) "${it.ip} (${it.host})" else it.ip }
            ))
        }
        return b.build()
    }
}
