package app.mbrowser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val server = findViewById<EditText>(R.id.server)
        val token = findViewById<EditText>(R.id.token)
        val device = findViewById<EditText>(R.id.device)
        val search = findViewById<EditText>(R.id.search)
        val status = findViewById<TextView>(R.id.status)
        server.setText(prefs.server)
        token.setText(prefs.token)
        device.setText(prefs.device)
        search.setText(prefs.searchUrl)

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.server = server.text.toString()
            prefs.token = token.text.toString()
            prefs.device = device.text.toString().ifBlank { prefs.device }
            prefs.searchUrl = search.text.toString().ifBlank { "https://duckduckgo.com/?q=" }
            if (!prefs.syncConfigured) {
                status.text = "Saved. Sync is off (no server set)."
                return@setOnClickListener
            }
            status.text = "Testing…"
            val ui = Handler(Looper.getMainLooper())
            Executors.newSingleThreadExecutor().execute {
                val r = Sync.test(applicationContext)
                ui.post { status.text = r }
            }
        }
    }
}
