'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const isWebUrl = (u) => /^https?:\/\//i.test(String(u || ''));

const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
function decodeHtml(s) {
  return String(s || '')
    .replace(/&#x([0-9a-f]+);/gi, (_m, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_m, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&([a-z]+);/gi, (m, n) => (n.toLowerCase() in entities ? entities[n.toLowerCase()] : m));
}

// ---------------------------------------------------------------- bookmarks

// Netscape bookmark file: what Chrome, Vivaldi, Edge, Brave, Firefox and Safari all export as "bookmarks.html"
function parseNetscapeHtml(text) {
  const out = [];
  const stack = [];
  let pending = null;
  const re = /<DL\b[^>]*>|<\/DL>|<H3\b[^>]*>([\s\S]*?)<\/H3>|<A\b([^>]*)>([\s\S]*?)<\/A>/gi;
  let m;
  while ((m = re.exec(text))) {
    const tag = m[0];
    if (/^<DL/i.test(tag)) { stack.push(pending); pending = null; }
    else if (/^<\/DL/i.test(tag)) stack.pop();
    else if (/^<H3/i.test(tag)) pending = decodeHtml(m[1].replace(/<[^>]*>/g, '')).trim();
    else {
      const href = /\bHREF\s*=\s*"([^"]*)"/i.exec(m[2]) || /\bHREF\s*=\s*'([^']*)'/i.exec(m[2]);
      if (!href) continue;
      const url = decodeHtml(href[1]).trim();
      if (!isWebUrl(url)) continue;
      const added = /\bADD_DATE\s*=\s*"?(\d+)/i.exec(m[2]);
      out.push({
        url,
        title: decodeHtml(m[3].replace(/<[^>]*>/g, '')).trim(),
        folder: stack.filter(Boolean).join(' / '),
        added: added ? Number(added[1]) * 1000 : 0,
      });
    }
  }
  return out;
}

// Chrome / Chromium / Vivaldi / Brave / Edge / Opera: the "Bookmarks" file in the profile folder
function parseChromiumJson(obj) {
  const out = [];
  const names = { bookmark_bar: 'Bookmarks bar', other: 'Other bookmarks', synced: 'Mobile bookmarks' };
  const epoch = 11644473600000; // WebKit time starts in 1601
  const walk = (node, folder) => {
    if (!node) return;
    if (node.type === 'url') {
      if (isWebUrl(node.url)) {
        const t = Number(node.date_added) / 1000 - epoch;
        out.push({ url: node.url, title: String(node.name || ''), folder, added: Number.isFinite(t) && t > 0 ? Math.round(t) : 0 });
      }
    } else if (Array.isArray(node.children)) {
      for (const c of node.children) walk(c, c.type === 'folder' ? (folder ? folder + ' / ' : '') + (c.name || '') : folder);
    }
  };
  for (const [key, root] of Object.entries(obj.roots || {})) {
    if (!root || typeof root !== 'object') continue;
    // Vivaldi keeps its own "trash" inside the file; do not import deleted items
    if (key === 'trash') continue;
    walk(root, names[key] || root.name || '');
  }
  return out;
}

// Firefox: a bookmark backup (.json or .jsonlz4)
function parseFirefoxJson(obj) {
  const out = [];
  const roots = {
    bookmarksMenuFolder: 'Bookmarks menu', toolbarFolder: 'Bookmarks toolbar',
    unfiledBookmarksFolder: 'Other bookmarks', mobileFolder: 'Mobile bookmarks',
  };
  const walk = (node, folder) => {
    if (!node) return;
    if (node.type === 'text/x-moz-place') {
      if (isWebUrl(node.uri)) {
        out.push({ url: node.uri, title: String(node.title || ''), folder, added: node.dateAdded ? Math.round(Number(node.dateAdded) / 1000) : 0 });
      }
    } else if (Array.isArray(node.children)) {
      if (node.root === 'tagsFolder') return;
      for (const c of node.children) {
        if (c.type === 'text/x-moz-place-container') {
          const name = roots[c.root] || c.title || '';
          walk(c, (folder ? folder + ' / ' : '') + name);
        } else walk(c, folder);
      }
    }
  };
  walk(obj, '');
  return out;
}

// LZ4 block decoder (Firefox's "mozlz4": 8 byte magic, 4 byte size, then one LZ4 block)
function lz4Block(src, start, size) {
  const dst = Buffer.alloc(size);
  let si = start, di = 0;
  while (si < src.length) {
    const token = src[si++];
    let lit = token >> 4;
    if (lit === 15) { let b; do { b = src[si++]; lit += b; } while (b === 255); }
    if (di + lit > size || si + lit > src.length) throw new Error('corrupt lz4 data');
    src.copy(dst, di, si, si + lit); si += lit; di += lit;
    if (si >= src.length) break;
    const offset = src[si] | (src[si + 1] << 8); si += 2;
    let len = token & 15;
    if (len === 15) { let b; do { b = src[si++]; len += b; } while (b === 255); }
    len += 4;
    if (offset === 0 || offset > di || di + len > size) throw new Error('corrupt lz4 data');
    for (let i = 0; i < len; i++) dst[di + i] = dst[di - offset + i]; // may overlap, so byte by byte
    di += len;
  }
  return dst.subarray(0, di);
}
function decodeMozLz4(buf) {
  if (buf.length < 12 || buf.subarray(0, 8).toString('latin1') !== 'mozLz40\0') throw new Error('not a Firefox backup');
  const size = buf.readUInt32LE(8);
  if (size > 256 * 1024 * 1024) throw new Error('file too large');
  return lz4Block(buf, 12, size);
}

// Works out what kind of file it is. Returns { format, items }.
function parseBookmarkFile(buf) {
  if (!Buffer.isBuffer(buf)) buf = Buffer.from(buf);
  if (buf.length > 100 * 1024 * 1024) throw new Error('file too large');
  if (buf.subarray(0, 7).toString('latin1') === 'mozLz40') {
    return { format: 'Firefox backup', items: parseFirefoxJson(JSON.parse(decodeMozLz4(buf).toString('utf8'))) };
  }
  const text = buf.toString('utf8').replace(/^\uFEFF/, '');
  const head = text.slice(0, 400).trim();
  if (head.startsWith('{')) {
    const obj = JSON.parse(text);
    if (obj.roots) return { format: 'Chrome / Chromium / Vivaldi / Edge', items: parseChromiumJson(obj) };
    if (obj.children || obj.root) return { format: 'Firefox backup', items: parseFirefoxJson(obj) };
    throw new Error('this JSON file is not a bookmarks file');
  }
  if (/<DT>|NETSCAPE-Bookmark|<A\s+HREF/i.test(text)) return { format: 'Bookmarks HTML file', items: parseNetscapeHtml(text) };
  throw new Error('unrecognised bookmarks file');
}

// ---------------------------------------------------------------- installed browsers

const exists = (p) => { try { return fs.existsSync(p); } catch { return false; } };
const dirs = (p) => { try { return fs.readdirSync(p, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name); } catch { return []; } };

// finds bookmark sources of other browsers installed for the current user
function detectBrowsers(env = process.env, platform = process.platform, home = os.homedir()) {
  const found = [];
  const chromium = [];
  if (platform === 'win32') {
    const local = env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    const roam = env.APPDATA || path.join(home, 'AppData', 'Roaming');
    chromium.push(
      ['Google Chrome', path.join(local, 'Google', 'Chrome', 'User Data')],
      ['Chromium', path.join(local, 'Chromium', 'User Data')],
      ['Vivaldi', path.join(local, 'Vivaldi', 'User Data')],
      ['Brave', path.join(local, 'BraveSoftware', 'Brave-Browser', 'User Data')],
      ['Microsoft Edge', path.join(local, 'Microsoft', 'Edge', 'User Data')],
      ['Opera', path.join(roam, 'Opera Software', 'Opera Stable')],
    );
  } else if (platform === 'darwin') {
    const s = path.join(home, 'Library', 'Application Support');
    chromium.push(
      ['Google Chrome', path.join(s, 'Google', 'Chrome')], ['Chromium', path.join(s, 'Chromium')],
      ['Vivaldi', path.join(s, 'Vivaldi')], ['Brave', path.join(s, 'BraveSoftware', 'Brave-Browser')],
      ['Microsoft Edge', path.join(s, 'Microsoft Edge')],
    );
  } else {
    const c = env.XDG_CONFIG_HOME || path.join(home, '.config');
    chromium.push(
      ['Google Chrome', path.join(c, 'google-chrome')], ['Chromium', path.join(c, 'chromium')],
      ['Vivaldi', path.join(c, 'vivaldi')], ['Brave', path.join(c, 'BraveSoftware', 'Brave-Browser')],
      ['Microsoft Edge', path.join(c, 'microsoft-edge')], ['Opera', path.join(c, 'opera')],
    );
  }
  for (const [name, base] of chromium) {
    if (!exists(base)) continue;
    const profiles = ['Default', ...dirs(base).filter((d) => /^Profile \d+$/.test(d))];
    for (const p of profiles) {
      const file = path.join(base, p, 'Bookmarks');
      if (exists(file)) found.push({ name: profiles.length > 1 || p !== 'Default' ? `${name} (${p})` : name, file, kind: 'chromium' });
    }
    const operaFile = path.join(base, 'Bookmarks'); // Opera keeps it at the top level
    if (name === 'Opera' && exists(operaFile)) found.push({ name, file: operaFile, kind: 'chromium' });
  }

  const ffRoots = platform === 'win32'
    ? [['Firefox', path.join(env.APPDATA || path.join(home, 'AppData', 'Roaming'), 'Mozilla', 'Firefox', 'Profiles')]]
    : platform === 'darwin'
      ? [['Firefox', path.join(home, 'Library', 'Application Support', 'Firefox', 'Profiles')]]
      : [
        ['Firefox', path.join(home, '.mozilla', 'firefox')],
        ['Firefox (Snap)', path.join(home, 'snap', 'firefox', 'common', '.mozilla', 'firefox')],
        ['Firefox (Flatpak)', path.join(home, '.var', 'app', 'org.mozilla.firefox', '.mozilla', 'firefox')],
        ['LibreWolf', path.join(home, '.librewolf')],
      ];
  for (const [name, base] of ffRoots) {
    for (const prof of dirs(base)) {
      const bdir = path.join(base, prof, 'bookmarkbackups');
      let files = [];
      try { files = fs.readdirSync(bdir).filter((f) => /^bookmarks-.*\.(jsonlz4|json)$/.test(f)).sort(); } catch { /* none */ }
      if (files.length) found.push({ name: `${name} (${prof.replace(/^[^.]+\./, '')})`, file: path.join(bdir, files[files.length - 1]), kind: 'firefox' });
    }
  }
  return found;
}

// ---------------------------------------------------------------- passwords

function parseCsv(text) {
  const rows = [];
  let row = [], cell = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; }
      else if (c === '"') q = false;
      else cell += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else cell += c;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  return rows.filter((r) => r.some((x) => x.trim()));
}

// Chrome / Vivaldi / Edge / Brave, Firefox, Bitwarden (CSV or JSON), LastPass, 1Password, KeePass(XC) and similar.
// Returns { format, rows: [{ url, username, password, note }] }
function parsePasswords(input) {
  const text = String(input || '').replace(/^\uFEFF/, '');
  if (text.trim().startsWith('{')) {
    let obj;
    try { obj = JSON.parse(text); } catch { throw new Error('This file is not valid JSON.'); }
    if (obj.encrypted) throw new Error('This Bitwarden export is encrypted. Export it again as an unencrypted .json or .csv file.');
    if (Array.isArray(obj.items)) {
      const rows = [];
      const folderName = new Map((obj.folders || []).map((f) => [f.id, f.name]));
      for (const it of obj.items) {
        const l = it && it.login;
        if (!l || !l.password) continue;
        const uri = (l.uris || []).map((u) => u && u.uri).find(isWebUrl) || '';
        rows.push({
          url: uri, username: l.username || '', password: l.password, note: it.notes || '',
          name: it.name || '', folder: folderName.get(it.folderId) || '',
        });
      }
      return { format: 'Bitwarden JSON', rows };
    }
    throw new Error('This JSON file does not look like a password export.');
  }
  const table = parseCsv(text);
  if (table.length < 2) throw new Error('That file has no rows.');
  const head = table[0].map((h) => h.trim().toLowerCase());
  const col = (...names) => head.findIndex((h) => names.includes(h));
  const iu = col('url', 'login_uri', 'origin', 'website', 'web site', 'uri', 'address', 'login url');
  const ip = col('password', 'login_password', 'pass');
  const iUser = col('username', 'login_username', 'user', 'login', 'user name', 'email', 'login name');
  const iNote = col('note', 'notes', 'extra', 'comments');
  const iName = col('name', 'title', 'account');
  const iFolder = col('folder', 'grouping', 'group');
  if (iu < 0 || ip < 0) throw new Error('Could not find “url” and “password” columns in that file.');
  let format = 'CSV file';
  if (head.includes('login_uri')) format = 'Bitwarden CSV';
  else if (head.includes('httprealm') || head.includes('formactionorigin')) format = 'Firefox';
  else if (head.includes('grouping')) format = 'LastPass';
  else if (head.includes('website') && head.includes('title')) format = '1Password';
  else if (head.includes('name') && head.includes('url') && head.includes('username')) format = 'Chrome / Vivaldi / Edge / Brave';
  const rows = table.slice(1).map((r) => ({
    url: (r[iu] || '').trim(), username: iUser >= 0 ? (r[iUser] || '') : '',
    password: r[ip] || '', note: iNote >= 0 ? (r[iNote] || '') : '',
    name: iName >= 0 ? (r[iName] || '').trim() : '', folder: iFolder >= 0 ? (r[iFolder] || '').trim() : '',
  }));
  return { format, rows };
}

module.exports = {
  parseBookmarkFile, parseNetscapeHtml, parseChromiumJson, parseFirefoxJson, decodeMozLz4, lz4Block,
  detectBrowsers, parsePasswords, parseCsv,
};
