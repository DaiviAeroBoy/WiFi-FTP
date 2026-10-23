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
import kotlinx.coroutines.flow.update
import java.io.File

class FtpService : Service() {

    companion object {
        const val ACTION_START = "com.daivik.wififtp.START"
        const val ACTION_PAUSE = "com.daivik.wififtp.PAUSE"
        const val ACTION_RESUME = "com.daivik.wififtp.RESUME"
        const val ACTION_STOP = "com.daivik.wififtp.STOP"
        const val ACTION_ALLOW = "com.daivik.wififtp.ALLOW"
        const val ACTION_BLOCK = "com.daivik.wififtp.BLOCK"
        private const val CH = "ftp_server"
        private const val CH_REQ = "ftp_request"
        private const val CH_ALERT = "ftp_alert"
        private const val NID = 1
        private const val NID_REQ = 2
        private const val NID_ALERT = 3
        private const val BASE_PORT = 2121

        fun send(ctx: Context, action: String, ip: String? = null) {
            val i = Intent(ctx, FtpService::class.java).setAction(action)
            if (ip != null) i.putExtra("ip", ip)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var watchJob: Job? = null
    private var server: FtpServer? = null
    private var fs: VirtualFs? = null
    private var pin: String? = null
    private var servingIp: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "FTP server", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REQ, "Connection requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Server stopped", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent
        if (i == null) {
            if (server == null) stopSelf()
            return START_NOT_STICKY
        }
        when (i.action) {
            ACTION_START -> { goForeground(); begin(i) }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> startServer()
            ACTION_STOP -> stopAll(null, false)
            ACTION_ALLOW -> i.getStringExtra("ip")?.let { server?.decide(it, true) }
            ACTION_BLOCK -> i.getStringExtra("ip")?.let { server?.decide(it, false) }
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
        Access.reset()
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NID_ALERT)
        job?.cancel()
        job = scope.launch {
            ServerState.state.collect { s ->
                if (s.running) nm.notify(NID, build(s))
                val p = s.pending.firstOrNull()
                if (p != null) nm.notify(NID_REQ, requestNotification(p)) else nm.cancel(NID_REQ)
                // CPU stays awake only while files are actually moving
                if (s.transfers.isNotEmpty()) acquireCpu() else releaseCpu()
            }
        }
        startServer()
    }

    private fun startServer() {
        val f = fs ?: return
        val ip = localIp()
        if (ip == null) { stopAll("Connect to Wi-Fi or turn on Hotspot first", false); return }
        server?.stop()
        var started: FtpServer? = null
        var port = BASE_PORT
        while (started == null && port < BASE_PORT + 20) {
            try {
                val s = FtpServer(port, f, pin)
                s.start()
                started = s
            } catch (e: Exception) { port++ }
        }
        if (started == null) { stopAll("Could not open a network port", false); return }
        server = started
        servingIp = ip
        acquireWifi()
        startWatcher()
        ServerState.state.value = UiState(running = true, paused = false, url = "ftp://$ip:$port", pin = pin)
    }

    /** Stops everything if the Wi-Fi / hotspot address we're serving on disappears. */
    private fun startWatcher() {
        watchJob?.cancel()
        watchJob = scope.launch {
            var miss = 0
            while (isActive) {
                delay(2000)
                val ip = servingIp ?: continue
                val present = withContext(Dispatchers.IO) { hasIp(ip) }
                miss = if (present) 0 else miss + 1
                if (miss >= 2) {
                    stopAll("Wi-Fi disconnected — server stopped", true)
                    break
                }
            }
        }
    }

    private fun pause() {
        server?.stop()
        server = null
        releaseLocks()
        ServerState.state.update { it.copy(paused = true, clients = emptyList(), pending = emptyList()) }
    }

    private fun stopAll(error: String?, alert: Boolean) {
        job?.cancel()
        watchJob?.cancel()
        server?.stop()
        server = null
        releaseLocks()
        Access.reset()
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NID_REQ)
        ServerState.state.value = UiState(error = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (alert && error != null) alertStopped(error)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop()
        releaseLocks()
        scope.cancel()
        ServerState.state.value = UiState()
        super.onDestroy()
    }

    private fun acquireWifi() {
        if (wifiLock != null) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wififtp:wifi").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun acquireCpu() {
        val w = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wififtp:transfer")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        w.acquire(10 * 60 * 1000L) // refreshed every second while a transfer is active
    }

    private fun releaseCpu() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun releaseLocks() {
        try { releaseCpu() } catch (e: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        wifiLock = null
        wakeLock = null
    }

    private fun pi(action: String, code: Int, ip: String? = null): PendingIntent {
        val i = Intent(this, FtpService::class.java).setAction(action)
        if (ip != null) i.putExtra("ip", ip)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun requestNotification(p: Pending): Notification {
        val who = if (p.host != p.ip) "${p.host} (${p.ip})" else p.ip
        val code = 100 + (p.ip.hashCode() and 0xffff) * 2
        return NotificationCompat.Builder(this, CH_REQ)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Allow connection?")
            .setContentText("$who wants to access your files")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(45_000)
            .setContentIntent(openApp())
            .addAction(0, "Allow", pi(ACTION_ALLOW, code, p.ip))
            .addAction(0, "Block", pi(ACTION_BLOCK, code + 1, p.ip))
            .build()
    }

    private fun alertStopped(text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("WiFi FTP stopped")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(NID_ALERT, n)
    }

    private fun build(s: UiState): Notification {
        val devices = s.clients.size
        val base = "${s.url}  •  $devices device${if (devices == 1) "" else "s"} connected"
        val text = when {
            s.paused -> "Paused — tap Resume to share again"
            s.transfers.isNotEmpty() -> "$base  •  ↑ ${fmtSpeed(s.upSpeed)}  ↓ ${fmtSpeed(s.downSpeed)}"
            else -> base
        }
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (s.paused) "WiFi FTP paused" else "WiFi FTP is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (s.paused) "Resume" else "Pause", pi(if (s.paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Disconnect", pi(ACTION_STOP, 2))
        if (!s.paused && (s.clients.isNotEmpty() || s.transfers.isNotEmpty())) {
            val lines = s.transfers + s.clients.map { if (it.host != it.ip) "${it.ip} (${it.host})" else it.ip }
            b.setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + lines.joinToString("\n")))
        }
        return b.build()
    }
}
