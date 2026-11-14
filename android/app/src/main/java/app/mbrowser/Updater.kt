package app.mbrowser

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Finds new builds on the GitHub release "latest-apk", tells the user, and downloads and installs them. */
object Updater {
    const val ACTION_UPDATE = "app.mbrowser.UPDATE"
    class Info(val build: Int, val notes: String, val apiUrl: String, val publicUrl: String, val error: String? = null)

    @Suppress("DEPRECATION")
    fun current(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "mbrowser-android")
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** Blocking: run it off the main thread. */
    fun check(ctx: Context): Info {
        val prefs = Prefs(ctx)
        val repo = prefs.updateRepo.trim()
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(repo)) return Info(0, "", "", "", "The update source must look like owner/repository.")
        return try {
            val c = open("https://api.github.com/repos/$repo/releases/tags/latest-apk", prefs.updateToken, "application/vnd.github+json")
            val code = c.responseCode
            if (code == 404 || code == 401 || code == 403) {
                return Info(0, "", "", "", if (prefs.updateToken.isEmpty())
                    "Cannot read the releases. If the repository is private, add an access token under Update source."
                else "GitHub refused the access token or the repository was not found.")
            }
            if (code !in 200..299) return Info(0, "", "", "", "GitHub answered HTTP $code")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val notes = j.optString("body")
            val build = Regex("[Bb]uild (\\d+)").find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets")
            val a = if (assets != null && assets.length() > 0) assets.getJSONObject(0) else null
            if (a == null || build == 0) Info(0, "", "", "", "No downloadable build found.")
            else Info(build, notes, a.optString("url"), a.optString("browser_download_url"))
        } catch (e: Exception) { Info(0, "", "", "", "Cannot reach GitHub: ${e.message}") }
    }

    /** Checks and, if there is a newer build, asks. [manual] also reports "up to date" and errors. */
    fun checkAndPrompt(act: Activity, manual: Boolean) {
        val prefs = Prefs(act)
        Thread {
            val r = check(act)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (r.error != null) { if (manual) toast(act, r.error); return@runOnUiThread }
                prefs.updateLast = System.currentTimeMillis()
                val have = current(act)
                if (r.build <= have) { if (manual) toast(act, "You have the latest version (build $have)."); return@runOnUiThread }
                if (!manual && prefs.updateSkipped == r.build) return@runOnUiThread
                AlertDialog.Builder(act).setTitle("Update available")
                    .setMessage("Build ${r.build} is ready. You have build $have.")
                    .setPositiveButton("Download and install") { _, _ -> download(act, r) }
                    .setNegativeButton("Later") { _, _ -> prefs.updateSkipped = r.build }
                    .show()
            }
        }.start()
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    private fun download(act: Activity, r: Info) {
        val prefs = Prefs(act)
        val dlg = AlertDialog.Builder(act).setTitle("Downloading update").setMessage("Starting…").setCancelable(false).create()
        dlg.show()
        Thread {
            try {
                val dir = File(act.cacheDir, "updates").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
                val out = File(dir, "mbrowser.apk")
                var c: HttpURLConnection
                if (prefs.updateToken.isNotEmpty() && r.apiUrl.isNotEmpty()) {
                    // a private repository: the API sends us on to a signed address, which must not get the token
                    c = open(r.apiUrl, prefs.updateToken, "application/octet-stream")
                    c.instanceFollowRedirects = false
                    if (c.responseCode in 300..399) {
                        val loc = c.getHeaderField("Location"); c.disconnect()
                        c = open(loc, "", "application/octet-stream")
                    }
                } else {
                    c = open(r.publicUrl, "", "application/octet-stream")
                }
                if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                var done = 0L; var shown = -1
                c.inputStream.use { input -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != shown) { shown = pct; act.runOnUiThread { dlg.setMessage(if (pct >= 0) "$pct%" else "${done / 1024} KB") } }
                    }
                } }
                act.runOnUiThread { dlg.dismiss(); install(act, out) }
            } catch (e: Exception) {
                act.runOnUiThread { dlg.dismiss(); toast(act, "Download failed: ${e.message}") }
            }
        }.start()
    }

    private fun install(act: Activity, f: File) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            toast(act, "Allow mbrowser to install apps, then check for updates again")
            try { act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${act.packageName}"))) } catch (_: Exception) { }
            return
        }
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast(act, "Could not start the installer") }
    }

    fun notifyAvailable(ctx: Context, build: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java).setAction(ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(43, android.app.Notification.Builder(ctx, "updates")
            .setSmallIcon(R.drawable.ic_stat_m)
            .setContentTitle("mbrowser update available")
            .setContentText("Build $build is ready. Tap to install.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}

/** Runs about twice a day in the background and posts a notification when a newer build exists. */
class UpdateWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.updateAuto) return Result.success()
        val r = Updater.check(applicationContext)
        if (r.error == null && r.build > Updater.current(applicationContext) && prefs.updateNotified != r.build) {
            Updater.notifyAvailable(applicationContext, r.build)
            prefs.updateNotified = r.build
        }
        return Result.success()
    }
}
