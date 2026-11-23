/* mbrowser reader view. One copy for the desktop app (assets/reader.js) and one for Android
   (android/app/src/main/assets/reader.js): keep them identical. It is injected into the page; it defines
   window.__mbReader with check() (does this look like an article?) and toggle() (show/hide the reader). */
(function () {
  if (window.__mbReader) return;
  var POS = /article|body|content|entry|post|story|text|main|page|blog|news/i;
  var NEG = /comment|sidebar|side-bar|footer|foot|nav|share|sharing|related|promo|advert|(^|[^a-z])ad([^a-z]|$)|banner|social|menu|widget|popup|modal|cookie|subscribe|newsletter|recommend|outbrain|taboola|sponsor|breadcrumb|toolbar|tags|author-box/i;
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, IFRAME: 1, FORM: 1, BUTTON: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, NAV: 1, ASIDE: 1, FOOTER: 1, HEADER: 0, SVG: 1, CANVAS: 1, VIDEO: 1, AUDIO: 1, OBJECT: 1, EMBED: 1, DIALOG: 1, TEMPLATE: 1 };
  var KEEP = { P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, PRE: 1, CODE: 1, EM: 1, I: 1, STRONG: 1, B: 1, A: 1, BR: 1, FIGURE: 1, FIGCAPTION: 1, IMG: 1, TABLE: 1, THEAD: 1, TBODY: 1, TR: 1, TD: 1, TH: 1, SUB: 1, SUP: 1, HR: 1, DL: 1, DT: 1, DD: 1, MARK: 1, SMALL: 1, U: 1, S: 1, DEL: 1, INS: 1, Q: 1, CITE: 1, ABBR: 1, TIME: 1, PICTURE: 1 };

  function txt(n) { return (n.textContent || '').replace(/\s+/g, ' ').trim(); }
  function linkDensity(n) {
    var t = txt(n).length; if (!t) return 0;
    var l = 0, as = n.getElementsByTagName('a');
    for (var i = 0; i < as.length; i++) l += txt(as[i]).length;
    return l / t;
  }
  function hidden(n) {
    try { var cs = getComputedStyle(n); return cs.display === 'none' || cs.visibility === 'hidden'; } catch (e) { return false; }
  }
  function tagScore(n) {
    var s = (typeof n.className === 'string' ? n.className : '') + ' ' + (n.id || '');
    var v = 0; if (NEG.test(s)) v -= 25; if (POS.test(s)) v += 25; return v;
  }

  function findRoot() {
    var scores = new Map(), best = null, bs = 0;
    var ps = document.querySelectorAll('p, pre, td');
    for (var i = 0; i < ps.length; i++) {
      var p = ps[i], t = txt(p);
      if (t.length < 50) continue;
      var par = p.parentElement; if (!par) continue;
      var gp = par.parentElement;
      var add = 1 + (t.split(',').length - 1) + Math.min(Math.floor(t.length / 100), 3);
      [[par, 1], [gp, 0.5]].forEach(function (x) {
        var el = x[0]; if (!el || el === document.documentElement) return;
        if (!scores.has(el)) scores.set(el, tagScore(el));
        scores.set(el, scores.get(el) + add * x[1]);
      });
    }
    scores.forEach(function (v, el) {
      v = v * (1 - linkDensity(el));
      if (v > bs) { bs = v; best = el; }
    });
    // a semantic <article> that holds most of that text wins
    var arts = document.querySelectorAll('article, [itemprop="articleBody"], [role="article"]');
    for (var a = 0; a < arts.length; a++) {
      if (txt(arts[a]).length > 500 && (!best || best.contains(arts[a]) || arts[a].contains(best) || txt(arts[a]).length > txt(best).length * 0.6)) { best = arts[a]; break; }
    }
    return best ? { el: best, score: bs } : null;
  }

  function absUrl(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }
  function imgSrc(im) {
    var s = im.currentSrc || im.getAttribute('src') || im.getAttribute('data-src') || im.getAttribute('data-original') || im.getAttribute('data-lazy-src') || '';
    if (!s || /^data:image\/(gif|svg)/.test(s) && s.length < 200) { var ss = im.getAttribute('data-srcset') || im.getAttribute('srcset') || ''; if (ss) s = ss.split(',').pop().trim().split(/\s+/)[0]; }
    return s;
  }

  // copies only safe, readable elements; drops scripts, ads, share bars, hidden and link-heavy blocks
  function clean(src, dst, depth) {
    for (var n = src.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3) { dst.appendChild(document.createTextNode(n.nodeValue)); continue; }
      if (n.nodeType !== 1) continue;
      var tag = n.tagName.toUpperCase();
      if (SKIP[tag] || hidden(n)) continue;
      if (depth > 0 && /^(DIV|SECTION|UL|OL|SPAN|P|FIGURE|TABLE)$/.test(tag)) {
        var sc = tagScore(n);
        if (sc < 0 && txt(n).length < 600) continue;
        if (/^(DIV|SECTION|UL|OL)$/.test(tag) && linkDensity(n) > 0.55 && txt(n).length < 800) continue;
      }
      if (tag === 'IMG') {
        var s = absUrl(imgSrc(n)); if (!/^https?:/i.test(s)) continue;
        var w = n.naturalWidth || parseInt(n.getAttribute('width'), 10) || 0;
        if (w && w < 80) continue;
        var im = document.createElement('img'); im.src = s; im.alt = n.getAttribute('alt') || ''; im.loading = 'lazy'; im.referrerPolicy = 'no-referrer';
        dst.appendChild(im); continue;
      }
      if (tag === 'PICTURE') { clean(n, dst, depth + 1); continue; }
      if (KEEP[tag]) {
        var el = document.createElement(tag.toLowerCase());
        if (tag === 'A') {
          var h = absUrl(n.getAttribute('href') || '');
          if (/^https?:/i.test(h)) { el.href = h; el.rel = 'noopener noreferrer'; }
        }
        if (tag === 'TD' || tag === 'TH') { var cs = n.getAttribute('colspan'); if (cs) el.setAttribute('colspan', parseInt(cs, 10) || 1); }
        clean(n, el, depth + 1);
        if (/^(P|LI|BLOCKQUOTE|H[1-6]|FIGCAPTION)$/.test(tag) && !txt(el) && !el.querySelector('img')) continue;
        dst.appendChild(el);
      } else {
        // div, section, span and friends: keep the content, drop the wrapper
        clean(n, dst, depth + 1);
      }
    }
  }

  function meta(names) {
    for (var i = 0; i < names.length; i++) {
      var m = document.querySelector('meta[property="' + names[i] + '"], meta[name="' + names[i] + '"]');
      if (m && m.content) return m.content.trim();
    }
    return '';
  }

  function extract() {
    var r = findRoot(); if (!r) return null;
    var out = document.createElement('div');
    clean(r.el, out, 0);
    var title = meta(['og:title', 'twitter:title']) || '';
    var h1 = (r.el.querySelector('h1') || document.querySelector('article h1') || document.querySelector('h1'));
    if (h1 && txt(h1).length > 8) title = txt(h1);
    if (!title) title = document.title;
    // the title is shown in the header, so drop a leading duplicate
    var first = out.querySelector('h1');
    if (first && txt(first) === title) first.remove();
    var by = meta(['author', 'article:author', 'og:article:author']);
    if (!by) { var b = document.querySelector('[rel="author"], .byline, [itemprop="author"], .author'); if (b && txt(b).length < 80) by = txt(b); }
    var words = txt(out).split(' ').length;
    return { title: title, byline: by, site: meta(['og:site_name']) || location.hostname.replace(/^www\./, ''), node: out, words: words };
  }

  function check() {
    try {
      if (!/^https?:/.test(location.protocol)) return false;
      var r = findRoot();
      if (!r) return false;
      var ps = r.el.querySelectorAll('p'), long = 0;
      for (var i = 0; i < ps.length; i++) if (txt(ps[i]).length > 80) long++;
      return txt(r.el).length >= 700 && long >= 3 && linkDensity(r.el) < 0.4;
    } catch (e) { return false; }
  }

  var CSS = ':host{all:initial}*{box-sizing:border-box}' +
    '.wrap{position:fixed;inset:0;overflow:auto;-webkit-overflow-scrolling:touch;font-family:var(--ff);color:var(--fg);background:var(--bg);z-index:2147483647}' +
    '.bar{position:sticky;top:0;display:flex;gap:6px;align-items:center;justify-content:flex-end;padding:8px 12px;background:var(--bg);border-bottom:1px solid var(--line);font-family:system-ui,sans-serif}' +
    '.bar span{margin-right:auto;font-size:13px;opacity:.65;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '.bar button{font:600 14px system-ui,sans-serif;min-width:36px;height:34px;border-radius:8px;border:1px solid var(--line);background:transparent;color:var(--fg);cursor:pointer;padding:0 10px}' +
    '.bar button:active{opacity:.6}' +
    'article{max-width:' + '680px' + ';margin:0 auto;padding:28px 20px 90px;font-size:var(--fs);line-height:1.7}' +
    'h1.t{font-size:1.75em;line-height:1.25;margin:.2em 0 .3em}' +
    '.meta{font:14px system-ui,sans-serif;opacity:.65;margin-bottom:1.6em}' +
    'article img{max-width:100%;height:auto;border-radius:6px;display:block;margin:1em auto}' +
    'article a{color:var(--ac)}article p{margin:0 0 1.05em}article h2,article h3,article h4{line-height:1.3;margin:1.5em 0 .5em}' +
    'article blockquote{margin:1em 0;padding:.1em 1em;border-left:3px solid var(--line);opacity:.85}' +
    'article pre{overflow:auto;padding:12px;border-radius:8px;background:rgba(127,127,127,.15);font-size:.85em}' +
    'article code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em}' +
    'article table{border-collapse:collapse;max-width:100%;display:block;overflow:auto}article td,article th{border:1px solid var(--line);padding:6px 10px}' +
    'figure{margin:1em 0}figcaption{font:13px system-ui,sans-serif;opacity:.65;text-align:center}';
  var THEMES = {
    light: '--bg:#ffffff;--fg:#1d1d1f;--line:#e2e2e5;--ac:#1a56c4',
    sepia: '--bg:#f4ecd8;--fg:#3b3226;--line:#d9ccae;--ac:#8a4b12',
    dark: '--bg:#16171a;--fg:#d9d9dc;--line:#33353b;--ac:#7fb1ff'
  };

  function store(k, v) { try { if (v === undefined) return localStorage.getItem('mbReader.' + k); localStorage.setItem('mbReader.' + k, v); } catch (e) { return null; } }

  var host = null, prevOverflow = '';
  function close() {
    if (!host) return false;
    host.remove(); host = null;
    document.documentElement.style.overflow = prevOverflow;
    document.removeEventListener('keydown', onKey, true);
    return true;
  }
  function onKey(e) { if (e.key === 'Escape') { e.stopPropagation(); close(); } }

  function open() {
    var x = extract();
    if (!x || x.words < 40) return false;
    host = document.createElement('div');
    host.setAttribute('data-mb-reader', '1');
    host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647';
    var root = host.attachShadow({ mode: 'open' });
    // a constructed stylesheet is not blocked by a page's content-security-policy the way an inline <style> can be
    var sheet = null, style = null;
    try { sheet = new CSSStyleSheet(); root.adoptedStyleSheets = [sheet]; } catch (e) { sheet = null; style = document.createElement('style'); root.appendChild(style); }
    var theme = store('theme') || ((window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light');
    var size = parseInt(store('size'), 10) || 19, serif = store('serif') !== '0';
    function apply() {
      var css = CSS + '.wrap{' + THEMES[theme] + ';--fs:' + size + 'px;--ff:' + (serif ? 'Georgia,"Times New Roman",serif' : 'system-ui,-apple-system,"Segoe UI",Roboto,sans-serif') + '}';
      if (sheet) sheet.replaceSync(css); else style.textContent = css;
    }
    apply();
    var wrap = document.createElement('div'); wrap.className = 'wrap';
    var bar = document.createElement('div'); bar.className = 'bar';
    var lbl = document.createElement('span'); lbl.textContent = x.site + ' · ' + Math.max(1, Math.round(x.words / 230)) + ' min read';
    bar.appendChild(lbl);
    function btn(t, title, fn) { var b = document.createElement('button'); b.textContent = t; b.title = title; b.onclick = fn; bar.appendChild(b); return b; }
    btn('A−', 'Smaller text', function () { size = Math.max(13, size - 1); store('size', String(size)); apply(); });
    btn('A+', 'Larger text', function () { size = Math.min(32, size + 1); store('size', String(size)); apply(); });
    btn('Aa', 'Serif or sans-serif', function () { serif = !serif; store('serif', serif ? '1' : '0'); apply(); });
    btn('◐', 'Light, sepia or dark', function () { theme = theme === 'light' ? 'sepia' : theme === 'sepia' ? 'dark' : 'light'; store('theme', theme); apply(); });
    btn('✕', 'Close reader view (Esc)', close);
    var art = document.createElement('article');
    var h = document.createElement('h1'); h.className = 't'; h.textContent = x.title; art.appendChild(h);
    var m = document.createElement('div'); m.className = 'meta'; m.textContent = x.byline ? 'By ' + x.byline : ''; if (x.byline) art.appendChild(m);
    art.appendChild(x.node);
    wrap.appendChild(bar); wrap.appendChild(art); root.appendChild(wrap);
    prevOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';
    document.documentElement.appendChild(host);
    document.addEventListener('keydown', onKey, true);
    return true;
  }

  window.__mbReader = {
    check: check,
    active: function () { return !!host; },
    toggle: function () { if (host) { close(); return 'off'; } return open() ? 'on' : 'none'; },
    close: close
  };
})();
