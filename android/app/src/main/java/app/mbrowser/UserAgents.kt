package app.mbrowser

/** What the browser tells websites it is. Many sites show "unsupported browser" banners to the stock Android WebView. */
object UserAgents {
    val MODES = listOf("chrome" to "Chrome (recommended)", "firefox" to "Firefox", "safari" to "Safari", "webview" to "Android WebView (no spoofing)")

    fun mobile(mode: String, stock: String): String = when (mode) {
        "firefox" -> "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0"
        "safari" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        "webview" -> stock
        // a normal Chrome for Android: the stock string minus the "; wv" and "Version/4.0" markers of an embedded view
        else -> stock.replace("; wv", "").replace(" Version/4.0", "")
    }

    fun desktop(mode: String): String = when (mode) {
        "firefox" -> "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0"
        "safari" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"
        else -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
