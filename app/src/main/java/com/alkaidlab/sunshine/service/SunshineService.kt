package com.alkaidlab.sunshine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.alkaidlab.sunshine.R
import com.alkaidlab.sunshine.api.SunshineApiServer
import com.alkaidlab.sunshine.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface

class SunshineService : Service() {

    companion object {
        const val ACTION_START = "com.alkaidlab.sunshine.ACTION_START"
        const val ACTION_STOP = "com.alkaidlab.sunshine.ACTION_STOP"
        const val ACTION_EXIT_DESKTOP = "com.alkaidlab.sunshine.ACTION_EXIT_DESKTOP"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_AUTOMATIC_CAPTURE = "automatic_capture"

        private const val CHANNEL_ID = "sunshine_service_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentIp: String = "127.0.0.1"
            private set

        @Volatile
        var currentVpnIp: String? = null
            private set

        @Volatile
        var currentIpv6: String? = null
            private set

        data class NetworkSnapshot(
            val lanIp: String,
            val vpnIp: String?,
            val ipv6: String?
        )

        fun refreshNetworkAddresses(): NetworkSnapshot {
            var detectedLan: String? = null
            var detectedVpn: String? = null
            var detectedIpv6: String? = null

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    val isVpnIface = name.startsWith("tun") || name.startsWith("tailscale") ||
                            name.startsWith("wg") || name.startsWith("utun") || name.startsWith("ppp")

                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr.isLoopbackAddress) continue

                        if (addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            val isCgnat = isTailscaleCgnat(ip)
                            if (isVpnIface || isCgnat) {
                                if (detectedVpn == null) detectedVpn = ip
                            } else {
                                if (name.contains("wlan")) {
                                    detectedLan = ip
                                } else if (detectedLan == null) {
                                    detectedLan = ip
                                }
                            }
                        } else if (addr is java.net.Inet6Address) {
                            if (!addr.isLinkLocalAddress && !addr.isSiteLocalAddress && !addr.isMulticastAddress) {
                                val host = (addr.hostAddress ?: "").split("%")[0]
                                if (host.isNotBlank()) {
                                    if (detectedIpv6 == null || host.startsWith("2") || host.startsWith("3")) {
                                        detectedIpv6 = host
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                SunshineLogManager.log("NET_ERR", "Error detecting network: ${e.message}")
            }

            val finalLan = detectedLan ?: "127.0.0.1"
            currentIp = finalLan
            currentVpnIp = detectedVpn
            currentIpv6 = detectedIpv6
            return NetworkSnapshot(finalLan, detectedVpn, detectedIpv6)
        }

        private fun isTailscaleCgnat(ip: String): Boolean {
            val parts = ip.split(".")
            if (parts.size == 4 && parts[0] == "100") {
                val second = parts[1].toIntOrNull() ?: return false
                return second in 64..127
            }
            return false
        }

        fun start(context: Context, resultData: Intent? = null, automaticCapture: Boolean = false) {
            val intent = Intent(context, SunshineService::class.java).apply {
                action = ACTION_START
                if (resultData != null) putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_AUTOMATIC_CAPTURE, automaticCapture)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SunshineService::class.java))
        }

    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apiServer: SunshineApiServer? = null
    private val jmDnsList = mutableListOf<JmDNS>()
    private val streamingPower by lazy { StreamingPowerManager(this) }
    private val engineClient by lazy { SunshineEngineClient(this) }
    private val bootCapture by lazy { BootCaptureBootstrap(this, serviceScope) }
    private val startupWake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sunshine:CaptureStartup").apply {
            setReferenceCounted(false)
        }
    }

    private fun releaseStartupWake() { if (startupWake.isHeld) startupWake.release() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        SunshineRuntimeManager.getInstance().initialize(this)
        SunshineRuntimeManager.getInstance().engineClient = engineClient
        SunshineRuntimeManager.getInstance().captureRecovery = bootCapture
        val runtime = SunshineRuntimeManager.getInstance()
        serviceScope.launch(Dispatchers.Main.immediate) {
            combine(runtime.isStreaming, runtime.streamingSettings) { active, settings -> active to settings.wifiLowLatency }
                .collect { (active, lowLatency) -> streamingPower.update(active, lowLatency) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_EXIT_DESKTOP -> {
                serviceScope.launch { SunshineRuntimeManager.getInstance().exitVirtualScreen() }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val automatic = resultData == null && (intent == null || intent.getBooleanExtra(EXTRA_AUTOMATIC_CAPTURE, false))
                if (automatic && ServiceStartupSettings.read(this).instantConnect &&
                    SunshineRuntimeManager.getInstance().getMediaProjection() == null) startupWake.acquire(90000)
                // Android 14 requires consent before a mediaProjection foreground service.
                val foregroundType = if (resultData != null || SunshineRuntimeManager.getInstance().getMediaProjection() != null) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, buildNotification("正在初始化推流服务..."), foregroundType)
                com.alkaidlab.sunshine.controller.ControllerAdapter.start(this)
                if (resultData != null && SunshineRuntimeManager.getInstance().getMediaProjection() == null) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val projection = mpm.getMediaProjection(-1, resultData)
                        projection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                SunshineLogManager.log("MEDIA_PROJ", "MediaProjection stopped by system")
                                SunshineRuntimeManager.getInstance().onProjectionStopped(projection)
                            }
                        }, null)
                        SunshineRuntimeManager.getInstance().setMediaProjection(projection)
                    } catch (e: Exception) {
                        SunshineLogManager.log("MEDIA_PROJ_ERR", "Error setting MediaProjection: ${e.message}")
                    }
                }

                if (!isRunning) {
                    initAndStartServers()
                } else {
                    val net = refreshNetworkAddresses()
                    updateNotification(buildStatusString(net))
                }
                if (automatic) {
                    bootCapture.prepare(::releaseStartupWake)
                }
            }
        }
        return if (ServiceStartupSettings.read(this).keepAlive) START_STICKY else START_NOT_STICKY
    }

    private fun buildStatusString(net: NetworkSnapshot): String {
        val sb = StringBuilder("服务运行中: ").append(net.lanIp)
        if (net.vpnIp != null) sb.append(" | VPN: ").append(net.vpnIp)
        if (net.ipv6 != null) sb.append(" | IPv6")
        return sb.toString()
    }

    private fun initAndStartServers() {
        isRunning = true
        val net = refreshNetworkAddresses()
        updateNotification(buildStatusString(net))
        SunshineLogManager.log("NETWORK", "Detected LAN IPv4: ${net.lanIp}, VPN: ${net.vpnIp ?: "None"}, IPv6: ${net.ipv6 ?: "None"}")

        serviceScope.launch {
            try {
                // 1. Copy CA certificates from assets to filesDir
                val certFile = copyAssetToFile("cacert.pem", "cacert.pem")
                val keyFile = copyAssetToFile("cakey.pem", "cakey.pem")
                val stateFile = File(filesDir, "sunshine_state.json")

                // 2. Start Embedded REST API Server on port 47990
                apiServer?.stop()
                apiServer = SunshineApiServer(this@SunshineService, 47990).apply {
                    start()
                }
                SunshineLogManager.log("HTTP_SERVER", "REST API server listening on http://0.0.0.0:47990")

                // 3. Configure Native Sunshine Engine from sunshine.conf
                SunshineRuntimeManager.getInstance().reloadConfig(this@SunshineService)
                val serverName = "Sunshine-${Build.MANUFACTURER}-${Build.MODEL}"
                // Discovery may wait on multicast for seconds at boot. It must
                // never gate engine startup or capture readiness.
                ensureActive()
                engineClient.start(serverName, certFile.absolutePath, keyFile.absolutePath, stateFile.absolutePath)
                serviceScope.launch { registerMdns(serverName, net.lanIp, net.vpnIp, net.ipv6) }

            } catch (e: Exception) {
                SunshineLogManager.log("INIT_ERR", "Error initializing SunshineService: ${e.message}")
            }
        }
    }

    private fun registerMdns(serverName: String, ip4: String, vpnIp: String?, ip6: String?) {
        try {
            val inetAddress = InetAddress.getByName(ip4)
            val jmdns = JmDNS.create(inetAddress)
            val serviceInfo = ServiceInfo.create(
                "_nvstream._tcp.local.",
                serverName,
                47989,
                "Foundation-Sunshine on Android"
            )
            jmdns.registerService(serviceInfo)
            rememberMdns(jmdns)
            SunshineLogManager.log("MDNS", "Registered mDNS service _nvstream._tcp.local. on IPv4 $ip4:47989 as $serverName")
        } catch (e: Exception) {
            SunshineLogManager.log("MDNS_ERR", "IPv4 mDNS registration failed on $ip4: ${e.message}")
        }

        if (vpnIp != null && vpnIp != ip4) {
            try {
                val inetVpn = InetAddress.getByName(vpnIp)
                val jmdnsVpn = JmDNS.create(inetVpn)
                val serviceInfoVpn = ServiceInfo.create(
                    "_nvstream._tcp.local.",
                    "$serverName-VPN",
                    47989,
                    "Foundation-Sunshine on Android (VPN)"
                )
                jmdnsVpn.registerService(serviceInfoVpn)
                rememberMdns(jmdnsVpn)
                SunshineLogManager.log("MDNS", "Registered mDNS service on VPN $vpnIp:47989 as $serverName-VPN")
            } catch (e: Exception) {
                SunshineLogManager.log("MDNS_INFO", "VPN mDNS note: ${e.message}")
            }
        }

        if (ip6 != null) {
            try {
                val inet6 = InetAddress.getByName(ip6)
                val jmdns6 = JmDNS.create(inet6)
                val serviceInfo6 = ServiceInfo.create(
                    "_nvstream._tcp.local.",
                    "$serverName-IPv6",
                    47989,
                    "Foundation-Sunshine on Android (IPv6)"
                )
                jmdns6.registerService(serviceInfo6)
                rememberMdns(jmdns6)
                SunshineLogManager.log("MDNS", "Registered mDNS service on IPv6 $ip6:47989 as $serverName-IPv6")
            } catch (e: Exception) {
                SunshineLogManager.log("MDNS_INFO", "IPv6 mDNS note: ${e.message}")
            }
        }
    }

    private fun rememberMdns(instance: JmDNS) {
        synchronized(jmDnsList) {
            if (serviceScope.isActive) jmDnsList.add(instance) else instance.close()
        }
    }

    private fun copyAssetToFile(assetName: String, fileName: String): File {
        val dest = File(filesDir, fileName)
        if (!dest.exists()) {
            assets.open(assetName).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return dest
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sunshine Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active Sunshine streaming server status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sunshine 串流服务")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "退出桌面 / 恢复主屏", PendingIntent.getService(this, 2,
                Intent(this, SunshineService::class.java).setAction(ACTION_EXIT_DESKTOP), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        releaseStartupWake()
        com.alkaidlab.sunshine.controller.ControllerAdapter.stop()
        engineClient.stop()
        SunshineRuntimeManager.getInstance().engineClient = null
        SunshineRuntimeManager.getInstance().captureRecovery = null
        SunshineRuntimeManager.getInstance().releaseAll()

        try {
            synchronized(jmDnsList) {
                jmDnsList.forEach { it.close() }
                jmDnsList.clear()
            }
        } catch (_: Exception) {}

        try {
            apiServer?.stop()
        } catch (_: Exception) {}
        serviceScope.cancel()
        streamingPower.release()
        SunshineLogManager.log("SERVICE", "SunshineService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
