package app.mbrowser

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider

/** Tells the Cast framework to use Google's standard media receiver, which plays ordinary video and audio streams. */
class McCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Sends the video or audio a page is playing to a Chromecast or Google TV. */
class Caster(private val act: AppCompatActivity) {
    class Media(val url: String, val title: String, val type: String, val positionMs: Long, val live: Boolean)

    // null when Google Play Services is missing on the phone
    private val cast: CastContext? = try { CastContext.getSharedInstance(act) } catch (_: Throwable) { null }
    private var pending: Media? = null
    var last: Media? = null
        private set
    private var watching: RemoteMediaClient? = null
    private val watcher = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = watching?.mediaStatus ?: return
            if (st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_ERROR)
                Toast.makeText(act, "The device started loading it but couldn't play the stream. Tap the cast row, then \"Show what was sent\".", Toast.LENGTH_LONG).show()
        }
    }

    val available: Boolean get() = cast != null
    private val session: CastSession? get() = cast?.sessionManager?.currentCastSession
    val connected: Boolean get() = session?.isConnected == true
    val deviceName: String get() = session?.castDevice?.friendlyName ?: "your device"

    private val listener = object : SessionManagerListener<CastSession> {
        private fun ready(s: CastSession) { pending?.let { load(s, it); pending = null } }
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStarted(s: CastSession, sessionId: String) = ready(s)
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            pending = null; Toast.makeText(act, "Couldn't connect to the device", Toast.LENGTH_SHORT).show()
        }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, error: Int) {}
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = ready(s)
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    init { cast?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java) }

    fun release() { cast?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java) }

    /** Casts [m]: straight away if a device is connected, otherwise after the user picks one. */
    fun send(m: Media) {
        val c = cast ?: return
        val s = session
        if (s != null && s.isConnected) { load(s, m); return }
        pending = m
        val d = androidx.mediarouter.app.MediaRouteChooserDialog(act)
        c.mergedSelector?.let { d.routeSelector = it }
        d.show()
    }

    fun stop() { cast?.sessionManager?.endCurrentSession(true) }

    private fun load(s: CastSession, m: Media) {
        val rc = s.remoteMediaClient ?: return
        val audio = m.type.startsWith("audio")
        val md = MediaMetadata(if (audio) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE)
        md.putString(MediaMetadata.KEY_TITLE, m.title)
        val info = MediaInfo.Builder(m.url).setContentType(m.type)
            .setStreamType(if (m.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(md).build()
        last = m
        watching?.unregisterCallback(watcher); watching = rc; rc.registerCallback(watcher)
        val name = s.castDevice?.friendlyName ?: "your device"
        Toast.makeText(act, "Sending to $name…", Toast.LENGTH_SHORT).show()
        rc.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(if (m.live) 0 else m.positionMs).build())
            .setResultCallback { r ->
                if (!r.status.isSuccess) {
                    val host = try { android.net.Uri.parse(m.url).host } catch (_: Exception) { null } ?: m.url
                    Toast.makeText(act, "The device couldn't play it (code ${r.status.statusCode}) from $host as ${m.type}. The site may block other devices or need a login.", Toast.LENGTH_LONG).show()
                }
            }
    }

    companion object {
        private val EXT = mapOf(
            "m3u8" to "application/x-mpegURL", "mpd" to "application/dash+xml", "mp3" to "audio/mpeg", "aac" to "audio/aac",
            "m4a" to "audio/mp4", "ogg" to "audio/ogg", "webm" to "video/webm", "mp4" to "video/mp4",
        )

        /** A request that looks like a playable stream or file (not a small piece of one). */
        fun looksLikeMedia(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return EXT.keys.any { path.endsWith(".$it") }
        }

        fun typeFor(url: String, isVideo: Boolean): String {
            val ext = url.substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.', "")
            return EXT[ext] ?: if (isVideo) "video/mp4" else "audio/mpeg"
        }
    }
}
