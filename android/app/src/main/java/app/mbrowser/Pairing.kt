package app.mbrowser

import android.content.Context
import android.util.Base64
import androidx.appcompat.app.AlertDialog
import com.journeyapps.barcodescanner.ScanOptions

/** Connects this phone from a QR code shown by another device (mbp1|server|key|vault key). */
object Pairing {
    class Result(val server: String, val token: String, val key: ByteArray?)

    fun options(): ScanOptions = ScanOptions().setBeepEnabled(false).setOrientationLocked(false)
        .setCaptureActivity(PortraitCapture::class.java).setPrompt("Scan the QR code on your other device")
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)

    fun parse(text: String?): Result? {
        val p = (text ?: return null).trim().split("|")
        if (p.size < 4 || p[0] != "mbp1" || !p[1].startsWith("http") || p[2].isEmpty()) return null
        val k = if (p[3].isNotEmpty()) try { Base64.decode(p[3], Base64.DEFAULT).takeIf { it.size == 32 } } catch (_: Exception) { null } else null
        return Result(p[1].trimEnd('/'), p[2], k)
    }

    /** Signs this phone in to the server and, if the code carried it, gives it the vault key. */
    fun apply(ctx: Context, r: Result) {
        val prefs = Prefs(ctx)
        prefs.server = r.server; prefs.token = r.token
        if (r.key != null) {
            prefs.syncVault = true
            Vault.pendingKey = r.key
            if (prefs.vaultKeep != "app") DeviceKey.save(ctx, r.key)
        }
    }
}

/** The "keep passwords unlocked" choice. */
object VaultPolicy {
    private val IDS = listOf("always", "app", "reboot", "time")
    private val LABELS = listOf("Always (until I lock them)", "Until the app is closed", "Until the phone restarts", "For a set time")
    private val MINS = listOf(5, 15, 60, 240, 1440)
    private val MIN_LABELS = listOf("5 minutes", "15 minutes", "1 hour", "4 hours", "24 hours")

    fun describe(p: Prefs): String = when (p.vaultKeep) {
        "app" -> "Until the app is closed"
        "reboot" -> "Until the phone restarts"
        "time" -> "For " + (MIN_LABELS.getOrNull(MINS.indexOf(p.vaultKeepMin)) ?: "${p.vaultKeepMin} minutes")
        else -> "Always (until I lock them)"
    }

    fun show(act: androidx.appcompat.app.AppCompatActivity, onDone: () -> Unit) {
        val p = Prefs(act)
        AlertDialog.Builder(act).setTitle("Keep passwords unlocked")
            .setSingleChoiceItems(LABELS.toTypedArray(), IDS.indexOf(p.vaultKeep).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                p.vaultKeep = IDS[i]
                if (IDS[i] == "time") {
                    AlertDialog.Builder(act).setTitle("Lock the passwords after")
                        .setSingleChoiceItems(MIN_LABELS.toTypedArray(), MINS.indexOf(p.vaultKeepMin).coerceAtLeast(0)) { d2, j ->
                            d2.dismiss(); p.vaultKeepMin = MINS[j]; Vault.reapplyPolicy(act); onDone()
                        }.show()
                } else { Vault.reapplyPolicy(act); onDone() }
            }.show()
    }
}
