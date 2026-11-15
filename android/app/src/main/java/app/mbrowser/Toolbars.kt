package app.mbrowser

/** The buttons of the bottom bar. Pick a ready-made layout or choose your own set. */
object Toolbars {
    val NAMES = linkedMapOf(
        "back" to "Back", "forward" to "Forward", "home" to "Home", "tabs" to "Tabs", "downloads" to "Downloads",
        "search" to "Search", "newtab" to "New tab", "reload" to "Reload", "bookmarks" to "Bookmarks", "menu" to "Menu",
    )

    class Preset(val id: String, val label: String, val keys: List<String>)

    val PRESETS = listOf(
        Preset("classic", "Classic", listOf("downloads", "back", "home", "forward", "tabs")),
        Preset("modern", "Modern", listOf("back", "forward", "search", "tabs", "menu")),
        Preset("compact", "Compact", listOf("back", "home", "tabs")),
        Preset("browse", "Browse", listOf("back", "forward", "reload", "tabs", "menu")),
        Preset("shortcuts", "Shortcuts", listOf("back", "home", "newtab", "bookmarks", "tabs")),
    )

    fun custom(p: Prefs): List<String> = p.toolbarCustom.split(',').filter { it in NAMES }.ifEmpty { listOf("back", "home", "tabs") }

    fun keys(p: Prefs): List<String> =
        if (p.toolbarPreset == "custom") custom(p) else (PRESETS.firstOrNull { it.id == p.toolbarPreset } ?: PRESETS[0]).keys
}
