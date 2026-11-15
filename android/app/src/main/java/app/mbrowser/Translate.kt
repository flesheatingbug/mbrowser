package app.mbrowser

import android.net.Uri

/** Page and text translation through Google Translate (opened in a tab, so nothing is sent until you choose it). */
object Translate {
    val LANGS = listOf(
        "en" to "English", "ga" to "Irish", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "cs" to "Czech", "sv" to "Swedish", "da" to "Danish",
        "no" to "Norwegian", "fi" to "Finnish", "el" to "Greek", "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian",
        "ar" to "Arabic", "he" to "Hebrew", "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)", "id" to "Indonesian", "vi" to "Vietnamese", "th" to "Thai", "ro" to "Romanian", "hu" to "Hungarian",
    )

    fun pageUrl(url: String, lang: String) =
        "https://translate.google.com/translate?sl=auto&tl=" + lang + "&u=" + Uri.encode(url)

    fun textUrl(text: String, lang: String) =
        "https://translate.google.com/?sl=auto&tl=" + lang + "&op=translate&text=" + Uri.encode(text.take(4000))
}
