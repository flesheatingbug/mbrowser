'use strict';
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const LISTS = [
  { url: 'https://easylist.to/easylist/easylist.txt', name: 'easylist.txt' },
  { url: 'https://easylist.to/easylist/easyprivacy.txt', name: 'easyprivacy.txt' },
  { url: 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt', name: 'ublock.txt' },
];
const MAX_AGE_MS = 3 * 24 * 3600 * 1000;

// Small fallback used until filter lists are loaded
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

function hostOf(url) {
  try { return new URL(url).hostname.toLowerCase(); } catch { return ''; }
}

function baseDomain(host) {
  const p = host.split('.');
  return p.slice(-2).join('.');
}

function regDomain(host) {
  const l = host.split('.');
  if (l.length <= 2) return host;
  const tld = l[l.length - 1], sld = l[l.length - 2];
  const n = (tld.length === 2 && ['co','com','org','net','gov','ac','edu'].includes(sld)) ? 3 : 2;
  return l.slice(-n).join('.');
}

// Same company, different domain (ebay.ie and ebaystatic.com, amazon.co.uk and media-amazon.com): treat as first party
function sameOrg(a, b) {
  const name = (h) => regDomain(h).split('.')[0];
  const x = name(a), y = name(b);
  if (x.length < 4 || y.length < 4) return false;
  return x === y || x.includes(y) || y.includes(x);
}

function suffixes(host) {
  const out = [];
  let h = host;
  while (true) {
    out.push(h);
    const i = h.indexOf('.');
    if (i < 0 || i === h.length - 1) break;
    h = h.slice(i + 1);
  }
  return out;
}

// ---- rule parser (ABP / uBlock filter list format) ----
const TYPE = { script: 1, image: 2, stylesheet: 4, css: 4, xmlhttprequest: 8, xhr: 8, subdocument: 16, frame: 16, media: 32, font: 64, object: 128, ping: 256, websocket: 512, other: 1024 };
const TOKEN_RE = /[a-z0-9]{3,}/g;
const BAD_PSEUDO = [':-abp', ':has-text', ':xpath', ':matches', ':upward', ':remove', ':style', ':nth-ancestor', ':min-text', ':watch', ':contains('];
const OPT_OK = /^[~a-zA-Z0-9_,=|.\-]+$/;

function parseNetwork(raw) {
  let l = raw, exc = false;
  if (l.startsWith('@@')) { exc = true; l = l.slice(2); }
  if (l.startsWith('/') && l.endsWith('/')) return null; // regex rules: skip
  let third = 0, types = 0, notTypes = 0;
  const domIn = [], domOut = [];
  const dollar = l.lastIndexOf('$');
  if (dollar >= 0) {
    const opts = l.slice(dollar + 1);
    if (OPT_OK.test(opts)) {
      l = l.slice(0, dollar);
      for (const o of opts.split(',')) {
        const neg = o.startsWith('~'), n = neg ? o.slice(1) : o;
        if (n === 'third-party' || n === '3p') { third = neg ? 2 : 1; }
        else if (n === 'first-party' || n === '1p') { third = neg ? 1 : 2; }
        else if (n.startsWith('domain=')) {
          for (const d of n.slice(7).split('|')) {
            if (!d) continue;
            if (d.startsWith('~')) domOut.push(d.slice(1).toLowerCase()); else domIn.push(d.toLowerCase());
          }
        } else if (n === 'important' || n === 'match-case' || n === 'collapse') {
          // allowed, ignore
        } else if (TYPE[n]) {
          if (neg) notTypes |= TYPE[n]; else types |= TYPE[n];
        } else return null; // unknown option: skip rule
      }
    }
  }
  let hostAnchor = false, startAnchor = false, endAnchor = false;
  if (l.startsWith('||')) { hostAnchor = true; l = l.slice(2); }
  else if (l.startsWith('|')) { startAnchor = true; l = l.slice(1); }
  if (l.endsWith('|')) { endAnchor = true; l = l.slice(0, -1); }
  l = l.toLowerCase();
  if (!l) return null;
  const pure = hostAnchor && /^[a-z0-9.-]+(\^|$)/.test(l) && !endAnchor;
  const hostMatch = hostAnchor ? (l.match(/^[a-z0-9.-]+/) || [])[0] : null;
  return { exc, pattern: l, hostAnchor, startAnchor, endAnchor, third, types, notTypes, domIn, domOut, pure, hostMatch };
}

function ruleRegex(r) {
  if (r._rx) return r._rx;
  let sb = '';
  if (r.hostAnchor) sb += '^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?'; else if (r.startAnchor) sb += '^';
  for (const ch of r.pattern) {
    if (ch === '*') sb += '.*';
    else if (ch === '^') sb += '(?:[^a-z0-9_.%-]|$)';
    else if (/[a-z0-9]/.test(ch)) sb += ch;
    else sb += '\\' + ch;
  }
  if (r.endAnchor) sb += '$';
  r._rx = new RegExp(sb);
  return r._rx;
}

function addToIndex(ix, r) {
  if (r.hostAnchor && r.hostMatch) {
    (ix.byHost[r.hostMatch] = ix.byHost[r.hostMatch] || []).push(r);
    return;
  }
  TOKEN_RE.lastIndex = 0;
  let best = null;
  for (const m of r.pattern.matchAll(TOKEN_RE)) {
    const s = m.index, e = s + m[0].length;
    const startOk = s === 0 ? (r.hostAnchor || r.startAnchor) : r.pattern[s - 1] !== '*';
    const endOk = e >= r.pattern.length ? r.endAnchor : r.pattern[e] !== '*';
    if (startOk && endOk && (!best || m[0].length > best.length)) best = m[0];
  }
  if (best) (ix.byToken[best] = ix.byToken[best] || []).push(r); else ix.loose.push(r);
}

function matchesDomains(r, pageHost) {
  if (!r.domIn.length && !r.domOut.length) return true;
  const sfx = suffixes(pageHost);
  if (r.domOut.some((d) => sfx.includes(d))) return false;
  return !r.domIn.length || r.domIn.some((d) => sfx.includes(d));
}

function applies(r, type, third, pageHost) {
  if (r.third === 1 && !third) return false;
  if (r.third === 2 && third) return false;
  if (r.types && (!type || !(r.types & type))) return false;
  if (r.notTypes && type && (r.notTypes & type)) return false;
  return matchesDomains(r, pageHost);
}

function matchIndex(ix, url, host, type, third, pageHost) {
  for (const s of suffixes(host)) {
    for (const r of (ix.byHost[s] || [])) {
      if (applies(r, type, third, pageHost) && (r.pure || ruleRegex(r).test(url))) return true;
    }
  }
  TOKEN_RE.lastIndex = 0;
  const seen = new Set();
  for (const m of url.matchAll(TOKEN_RE)) {
    if (seen.has(m[0])) continue; seen.add(m[0]);
    for (const r of (ix.byToken[m[0]] || [])) {
      if (applies(r, type, third, pageHost) && ruleRegex(r).test(url)) return true;
    }
  }
  for (const r of ix.loose) if (applies(r, type, third, pageHost) && ruleRegex(r).test(url)) return true;
  return false;
}

// ---- engine builder ----
function buildEngine(texts) {
  const block = { byHost: {}, byToken: {}, loose: [], count: 0 };
  const allow = { byHost: {}, byToken: {}, loose: [], count: 0 };
  const generic = {}; // token → [selectors]
  const always = []; // global hide selectors
  const byDomain = {}; // host → [{sel, neg}]
  const exceptions = {}; // sel → [domains]
  let hides = 0;

  for (const text of texts) {
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (!line || line[0] === '!' || line[0] === '[') continue;

      // element hiding exception: #@#
      const exH = line.indexOf('#@#');
      if (exH >= 0 && !line.includes('#@#+js')) {
        const sel = line.slice(exH + 3);
        const doms = line.slice(0, exH).split(',').filter(Boolean).map((d) => d.toLowerCase());
        (exceptions[sel] = exceptions[sel] || []).push(...(doms.length ? doms : ['*']));
        continue;
      }
      if (line.includes('#?#') || line.includes('#$#') || line.includes('#%#')) continue;

      // element hiding: ##
      const h = line.indexOf('##');
      if (h >= 0) {
        const sel = line.slice(h + 2);
        if (!sel || sel.startsWith('+js') || BAD_PSEUDO.some((p) => sel.includes(p)) || sel.includes('{')) continue;
        const doms = line.slice(0, h).split(',').filter(Boolean).map((d) => d.toLowerCase());
        const pos = doms.filter((d) => !d.startsWith('~'));
        const neg = doms.filter((d) => d.startsWith('~')).map((d) => d.slice(1));
        hides++;
        if (!pos.length) {
          const m = sel.match(/^[#.][A-Za-z0-9_-]+/);
          if (m && !neg.length) (generic[m[0]] = generic[m[0]] || []).push(sel);
          else if (always.length < 2000 && !neg.length) always.push(sel);
        } else {
          for (const d of pos) (byDomain[d] = byDomain[d] || []).push({ sel, neg });
        }
        continue;
      }

      const r = parseNetwork(line);
      if (!r) continue;
      const ix = r.exc ? allow : block;
      addToIndex(ix, r);
      ix.count++;
    }
  }
  return { block, allow, generic, always, byDomain, exceptions, hides, blockCount: block.count, allowCount: allow.count };
}

// ---- resource type mapping ----
function electronTypeToInt(t) {
  switch (t) {
    case 'script': return 1;
    case 'image': return 2;
    case 'stylesheet': return 4;
    case 'xhr': return 8;
    case 'subFrame': return 16;
    case 'media': return 32;
    case 'font': return 64;
    case 'object': return 128;
    case 'ping': return 256;
    case 'webSocket': return 512;
    default: return 0;
  }
}

function createBlocker(getSettings, cacheDir) {
  let engine = null;
  let statusText = 'Using the built-in domain list';
  let busy = false;
  let customBlocked = new Set(BUILTIN);

  if (cacheDir) {
    try { fs.mkdirSync(cacheDir, { recursive: true }); } catch { /* ignore */ }
    setImmediate(() => { loadEngine(); checkStale(); });
  }

  function rebuildCustom() {
    const custom = String(getSettings().privacy.customBlocklist || '')
      .split(/\s+/).map((s) => s.trim().toLowerCase()).filter((s) => s && !s.startsWith('#'));
    customBlocked = new Set([...BUILTIN, ...custom]);
  }

  function siteAllowed(pageHost) {
    const list = getSettings().privacy.allowSites || [];
    return list.some((h) => pageHost === h || pageHost.endsWith('.' + h));
  }

  function hostAllowed(host) {
    const list = getSettings().privacy.allowHosts || [];
    return list.some((h) => host === h || host.endsWith('.' + h));
  }

  function hostBlockedBuiltin(host) {
    const labels = host.toLowerCase().split('.');
    for (let i = 0; i < labels.length - 1; i++) {
      if (customBlocked.has(labels.slice(i).join('.'))) return true;
    }
    return false;
  }

  function shouldBlock(url, pageUrl, resourceType) {
    const reqHost = hostOf(url);
    if (!reqHost) return false;
    if (hostAllowed(reqHost)) return false;
    const pageHost = hostOf(pageUrl || '');
    if (pageHost && baseDomain(reqHost) === baseDomain(pageHost)) return false;
    // Styles and fonts are what make a page look right, and they are not how trackers work: never block them
    if (resourceType === 'stylesheet' || resourceType === 'font') return false;
    if (pageHost && sameOrg(reqHost, pageHost) && !hostBlockedBuiltin(reqHost)) return false;
    if (!engine) return hostBlockedBuiltin(reqHost);
    const third = !!pageHost && regDomain(reqHost) !== regDomain(pageHost);
    const type = electronTypeToInt(resourceType || '');
    const u = url.toLowerCase();
    if (engine.allowCount > 0 && matchIndex(engine.allow, u, reqHost, type, third, pageHost)) return false;
    return matchIndex(engine.block, u, reqHost, type, third, pageHost);
  }

  function cosmeticCss(host) {
    if (!engine) return '';
    const h = host.toLowerCase();
    const sfx = suffixes(h);
    const out = new Set();
    const allowed = (sel) => {
      const ex = engine.exceptions[sel];
      if (!ex) return true;
      return !(ex.includes('*') || ex.some((d) => sfx.includes(d)));
    };
    for (const s of sfx) {
      for (const { sel, neg } of (engine.byDomain[s] || [])) {
        if (!neg.some((d) => sfx.includes(d)) && allowed(sel)) out.add(sel);
      }
    }
    for (const sel of engine.always) if (allowed(sel)) out.add(sel);
    if (!out.size) return '';
    return [...out].map((sel) => sel + '{display:none!important}').join('\n');
  }

  function loadEngine() {
    if (!cacheDir) return;
    try {
      const texts = LISTS.map(({ name }) => {
        const f = path.join(cacheDir, name);
        return fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null;
      }).filter(Boolean);
      if (!texts.length) return;
      engine = buildEngine(texts);
      const date = (() => { try { return new Date(fs.statSync(path.join(cacheDir, LISTS[0].name)).mtime).toLocaleDateString(); } catch { return ''; } })();
      statusText = `${engine.blockCount + engine.allowCount} blocking rules, ${engine.hides} element-hiding rules${date ? ' · updated ' + date : ''}`;
    } catch (e) {
      statusText = 'Could not load filter lists: ' + e.message;
    }
  }

  function checkStale() {
    if (!cacheDir) return;
    try {
      const f = path.join(cacheDir, LISTS[0].name);
      if (!fs.existsSync(f) || Date.now() - fs.statSync(f).mtimeMs > MAX_AGE_MS) download();
    } catch { download(); }
  }

  function fetchUrl(url) {
    return new Promise((resolve, reject) => {
      const follow = (u, depth) => {
        if (depth > 5) return reject(new Error('Too many redirects'));
        const mod = u.startsWith('https') ? https : http;
        mod.get(u, { headers: { 'User-Agent': 'mbrowser-desktop' } }, (res) => {
          if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
            res.resume(); return follow(res.headers.location, depth + 1);
          }
          if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
          res.on('error', reject);
        }).on('error', reject);
      };
      follow(url, 0);
    });
  }

  async function download() {
    if (busy) return 'Already updating';
    busy = true;
    statusText = 'Downloading filter lists…';
    try {
      let ok = 0;
      for (const { url, name } of LISTS) {
        try {
          const text = await fetchUrl(url);
          if (text.length > 10000) {
            fs.writeFileSync(path.join(cacheDir, name), text, 'utf8');
            ok++;
          }
        } catch { /* one list failed, continue */ }
      }
      if (ok === 0) { statusText = 'Could not download filter lists. Check your connection.'; return statusText; }
      loadEngine();
      return 'Filter lists updated (' + ok + '/' + LISTS.length + '). ' + statusText;
    } finally { busy = false; }
  }

  function rebuild() {
    rebuildCustom();
    loadEngine();
  }

  function attach(ses, onBlocked) {
    ses.webRequest.onBeforeRequest((details, cb) => {
      const s = getSettings().privacy;
      if (!s.blockTrackers || details.resourceType === 'mainFrame' || !/^https?:/i.test(details.url)) {
        return cb({});
      }
      const pageHost = hostOf(details.webContents ? details.webContents.getURL() : '');
      if (pageHost && siteAllowed(pageHost)) return cb({});
      if (shouldBlock(details.url, details.webContents ? details.webContents.getURL() : '', details.resourceType)) {
        if (details.webContents) onBlocked(details.webContents.id, details.url, details.resourceType);
        return cb({ cancel: true });
      }
      cb({});
    });

    // DNT is now handled by setupSession's combined onBeforeSendHeaders (which also owns the slot).
  }

  rebuild();
  return { attach, rebuild, shouldBlock, cosmeticCss, getStatus: () => statusText, download, hostOf };
}

module.exports = { createBlocker, hostOf };
