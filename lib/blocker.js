'use strict';

// A small built-in list of well-known ad / tracking domains. Not a replacement
// for uBlock-class filter lists; add your own domains in Settings > Privacy.
const BUILTIN = `
doubleclick.net googlesyndication.com googleadservices.com google-analytics.com
googletagmanager.com googletagservices.com adservice.google.com adnxs.com adsrvr.org
advertising.com amazon-adsystem.com criteo.com criteo.net taboola.com outbrain.com
scorecardresearch.com quantserve.com moatads.com rubiconproject.com pubmatic.com
openx.net casalemedia.com smartadserver.com 2mdn.net serving-sys.com adform.net
bidswitch.net sharethrough.com teads.tv zedo.com yieldmo.com facebook.net
hotjar.com mixpanel.com segment.io fullstory.com crazyegg.com mouseflow.com
demdex.net omtrdc.net everesttech.net bluekai.com krxd.net exelator.com rlcdn.com
ads-twitter.com ads.linkedin.com snap.licdn.com analytics.tiktok.com amplitude.com
parsely.com chartbeat.net mc.yandex.ru adsymptotic.com media.net adition.com
revcontent.com mgid.com zemanta.com 33across.com lijit.com sovrn.com indexww.com
`.split(/\s+/).filter(Boolean);

function baseDomain(host) {
  const p = host.split('.');
  return p.slice(-2).join('.');
}

function hostOf(url) {
  try { return new URL(url).hostname; } catch { return ''; }
}

function createBlocker(getSettings) {
  let blocked = new Set(BUILTIN);

  function rebuild() {
    const custom = String(getSettings().privacy.customBlocklist || '')
      .split(/\s+/).map((s) => s.trim().toLowerCase()).filter((s) => s && !s.startsWith('#'));
    blocked = new Set([...BUILTIN, ...custom]);
  }

  function hostBlocked(host) {
    const labels = host.toLowerCase().split('.');
    for (let i = 0; i < labels.length - 1; i++) {
      if (blocked.has(labels.slice(i).join('.'))) return true;
    }
    return false;
  }

  function siteAllowed(pageHost) {
    const list = getSettings().privacy.allowSites || [];
    return list.some((h) => pageHost === h || pageHost.endsWith('.' + h));
  }

  // onBlocked(webContentsId) is called for each blocked request
  function attach(ses, onBlocked) {
    ses.webRequest.onBeforeRequest((details, cb) => {
      const s = getSettings().privacy;
      if (!s.blockTrackers || details.resourceType === 'mainFrame' || !/^https?:/i.test(details.url)) {
        return cb({});
      }
      const reqHost = hostOf(details.url);
      const pageHost = hostOf(details.webContents ? details.webContents.getURL() : '');
      if (!reqHost || (pageHost && baseDomain(reqHost) === baseDomain(pageHost))) return cb({});
      if (pageHost && siteAllowed(pageHost)) return cb({});
      if (hostBlocked(reqHost)) {
        if (details.webContents) onBlocked(details.webContents.id);
        return cb({ cancel: true });
      }
      cb({});
    });

    ses.webRequest.onBeforeSendHeaders((details, cb) => {
      const headers = details.requestHeaders;
      if (getSettings().privacy.doNotTrack) {
        headers['DNT'] = '1';
        headers['Sec-GPC'] = '1';
      }
      cb({ requestHeaders: headers });
    });
  }

  rebuild();
  return { attach, rebuild, hostOf };
}

module.exports = { createBlocker, hostOf };
