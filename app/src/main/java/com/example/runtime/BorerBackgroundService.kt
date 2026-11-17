package com.example.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BackgroundSessionSnapshot(
    val keepAliveEnabled: Boolean = true,
    val persistentWakeLock: Boolean = false,
    val busy: Boolean = false,
    val huntRunning: Boolean = false,
    val modeLabel: String = "Chat",
    val activeModel: String = "unconfigured",
    val turn: Int = 0,
    val activeToolLabel: String? = null,
    val startedAtMs: Long = 0L
) {
    val shouldKeepServiceRunning: Boolean
        get() = persistentWakeLock || (keepAliveEnabled && (busy || huntRunning))
}

object BackgroundKeepAliveController {
    private val _snapshot = MutableStateFlow(BackgroundSessionSnapshot())
    val snapshot: StateFlow<BackgroundSessionSnapshot> = _snapshot.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    @Volatile
    var onStopRun: (() -> Unit)? = null

    @Volatile
    var onReleaseWakeLock: (() -> Unit)? = null

    fun updateSnapshot(context: Context, next: BackgroundSessionSnapshot) {
        val prev = _snapshot.value
        val activeRun = next.busy || next.huntRunning
        val prevRun = prev.busy || prev.huntRunning
        val startedAt = when {
            !next.shouldKeepServiceRunning -> 0L
            prev.startedAtMs > 0L && (activeRun == prevRun || prev.shouldKeepServiceRunning) -> prev.startedAtMs
            else -> System.currentTimeMillis()
        }
        val normalized = next.copy(startedAtMs = startedAt)
        _snapshot.value = normalized

        val appCtx = context.applicationContext
        if (normalized.shouldKeepServiceRunning && !_serviceRunning.value) {
            runCatching {
                ContextCompat.startForegroundService(
                    appCtx,
                    Intent(appCtx, BorerBackgroundService::class.java).apply {
                        action = BorerBackgroundService.ACTION_START_OR_SYNC
                    }
                )
            }
        } else if (!normalized.shouldKeepServiceRunning && _serviceRunning.value) {
            runCatching {
                appCtx.stopService(Intent(appCtx, BorerBackgroundService::class.java))
            }
        }
    }

    internal fun markServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val attempts = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class BorerBackgroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastWakeRefreshMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        BackgroundKeepAliveController.markServiceRunning(true)
        if (BackgroundKeepAliveController.snapshot.value.shouldKeepServiceRunning) {
            acquireLocks(forceRefresh = true)
        }
        collectorJob = serviceScope.launch {
            launch {
                BackgroundKeepAliveController.snapshot.collect { snap ->
                    if (!snap.shouldKeepServiceRunning) {
                        stopForegroundAndSelf()
                    } else {
                        updateNotification(snap)
                    }
                }
            }
            launch {
                while (isActive) {
                    delay(15_000L)
                    val snap = BackgroundKeepAliveController.snapshot.value
                    if (!snap.shouldKeepServiceRunning) {
                        stopForegroundAndSelf()
                        break
                    }
                    // Refresh wakelock every 4 hours so 8-9+ hour sessions never lapse
                    if (System.currentTimeMillis() - lastWakeRefreshMs > 4 * 60 * 60 * 1000L) {
                        acquireLocks(forceRefresh = true)
                    }
                    updateNotification(snap)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snap = BackgroundKeepAliveController.snapshot.value
        val startedFg = startForegroundWithType(buildNotification(snap))
        if (!startedFg) {
            releaseLocks()
            BackgroundKeepAliveController.markServiceRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP_RUN -> {
                BackgroundKeepAliveController.onStopRun?.invoke()
                if (!BackgroundKeepAliveController.snapshot.value.shouldKeepServiceRunning) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_RELEASE_WAKELOCK -> {
                BackgroundKeepAliveController.onReleaseWakeLock?.invoke()
                if (!BackgroundKeepAliveController.snapshot.value.shouldKeepServiceRunning) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                if (!snap.shouldKeepServiceRunning) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                acquireLocks(forceRefresh = false)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        BackgroundKeepAliveController.markServiceRunning(false)
        collectorJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundWithType(notification: Notification): Boolean {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        }.getOrDefault(false)
    }

    private fun stopForegroundAndSelf() {
        releaseLocks()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps long-running autonomous sessions and WakeLock active."
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(snap: BackgroundSessionSnapshot) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(snap)) }
    }

    private fun buildNotification(snap: BackgroundSessionSnapshot): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isWorking = snap.busy || snap.huntRunning
        val elapsedStr = if (snap.startedAtMs > 0L) formatElapsed(System.currentTimeMillis() - snap.startedAtMs) else "00:00"
        val title = when {
            snap.huntRunning -> "Borer · Hunt · ${snap.activeModel}"
            snap.busy && snap.turn > 0 -> "Borer · ${snap.modeLabel} · Turn ${snap.turn}"
            snap.busy -> "Borer · ${snap.modeLabel} · Running"
            snap.persistentWakeLock -> "Borer · WakeLock active"
            else -> "Borer · Background session"
        }
        val body = when {
            isWorking && !snap.activeToolLabel.isNullOrBlank() -> "${snap.activeToolLabel} · $elapsedStr"
            isWorking -> "${snap.activeModel} · $elapsedStr"
            snap.persistentWakeLock -> "CPU & Wi-Fi kept awake · $elapsedStr"
            else -> "Ready · $elapsedStr"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_borer)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isWorking) {
            val stopPi = PendingIntent.getService(
                this,
                101,
                Intent(this, BorerBackgroundService::class.java).apply { action = ACTION_STOP_RUN },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Stop", stopPi)
        }
        if (snap.persistentWakeLock) {
            val releasePi = PendingIntent.getService(
                this,
                102,
                Intent(this, BorerBackgroundService::class.java).apply { action = ACTION_RELEASE_WAKELOCK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Release", releasePi)
        }

        return builder.build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks(forceRefresh: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "borer:SessionWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (forceRefresh || wakeLock?.isHeld == false) {
                runCatching {
                    wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                    lastWakeRefreshMs = System.currentTimeMillis()
                }
            }
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null) {
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "borer:SessionWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                runCatching { wifiLock?.acquire() }
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }

    companion object {
        private const val CHANNEL_ID = "borer_background_session"
        private const val NOTIFICATION_ID = 4201
        private const val WAKELOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L // 12 hours, auto-refreshed

        const val ACTION_START_OR_SYNC = "com.example.runtime.ACTION_START_OR_SYNC"
        const val ACTION_STOP_RUN = "com.example.runtime.ACTION_STOP_RUN"
        const val ACTION_RELEASE_WAKELOCK = "com.example.runtime.ACTION_RELEASE_WAKELOCK"
    }
}
