package app.mbrowser

/** Small built-in list of well-known ad and tracker domains. Third-party requests to these are dropped. */
object Blocker {
    private val blocked = HashSet(
        ("doubleclick.net googlesyndication.com googleadservices.com google-analytics.com googletagmanager.com " +
            "googletagservices.com adservice.google.com adnxs.com adsrvr.org advertising.com amazon-adsystem.com " +
            "criteo.com criteo.net taboola.com outbrain.com scorecardresearch.com quantserve.com moatads.com " +
            "rubiconproject.com pubmatic.com openx.net casalemedia.com smartadserver.com 2mdn.net serving-sys.com " +
            "adform.net bidswitch.net sharethrough.com teads.tv zedo.com yieldmo.com facebook.net hotjar.com " +
            "mixpanel.com segment.io fullstory.com crazyegg.com mouseflow.com demdex.net omtrdc.net everesttech.net " +
            "bluekai.com krxd.net exelator.com rlcdn.com ads-twitter.com ads.linkedin.com snap.licdn.com " +
            "analytics.tiktok.com amplitude.com parsely.com chartbeat.net mc.yandex.ru adsymptotic.com media.net " +
            "adition.com revcontent.com mgid.com zemanta.com 33across.com lijit.com sovrn.com indexww.com")
            .split(' ').filter { it.isNotEmpty() }
    )

    private fun base(host: String): String = host.split('.').takeLast(2).joinToString(".")

    fun shouldBlock(requestHost: String?, pageHost: String?): Boolean {
        val h = requestHost?.lowercase() ?: return false
        if (!pageHost.isNullOrEmpty() && base(h) == base(pageHost.lowercase())) return false
        val labels = h.split('.')
        for (i in 0 until labels.size - 1) if (labels.subList(i, labels.size).joinToString(".") in blocked) return true
        return false
    }
}
