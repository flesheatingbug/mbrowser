package app.mbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps a page's audio (radio, podcasts, music) playing when the screen turns off or you switch apps.
 * Without a foreground service Android stops the app's audio and network after about a minute.
 * It stops itself shortly after the sound stops.
 */
class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var quiet = 0

    private val check = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isMusicActive) quiet = 0 else quiet++
            if (quiet >= 6) { stopSelf(); return }   // about 30 seconds of silence
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopper?.invoke(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing audio", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_m).setContentTitle("mbrowser is playing audio")
            .setContentText("Tap to go back to the page").setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mbrowser:audio").also { it.acquire(6 * 60 * 60 * 1000L) }
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mbrowser:audio")
                .also { it.acquire() }
        }
        quiet = 0
        handler.removeCallbacks(check); handler.postDelayed(check, 5000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 4107
        private const val ACTION_STOP = "app.mbrowser.STOP_AUDIO"
        /** Set by the main screen: pauses whatever is playing in the pages. */
        var stopper: (() -> Unit)? = null

        fun start(ctx: Context) {
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) {}
        }
    }
}
