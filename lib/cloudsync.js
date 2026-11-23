'use strict';
// Sync through the user's own cloud drive (Google Drive or OneDrive) instead of the self-hosted server.
//
// The browser's sync code only ever talks to the server through call(method, path, body, { allow409 }).
// This module offers the same call() on top of a few encrypted files in the app's private folder of the drive
// (Drive "appDataFolder", OneDrive "approot"), so bookmarks, history, tabs, the vault and settings use the same
// merge code for both backends. Everything is encrypted (AES-256-GCM) with a key derived from a sync passphrase;
// the drive provider only ever sees opaque files. The key never leaves the device (it is kept with safeStorage).
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');

const MAX_HISTORY = 20000;
const PAGE = 5000;

const PROVIDERS = {
  gdrive: {
    name: 'Google Drive',
    authUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
    tokenUrl: 'https://oauth2.googleapis.com/token',
    scope: 'https://www.googleapis.com/auth/drive.appdata',
    extraAuth: { access_type: 'offline', prompt: 'consent' },
    redirectHost: '127.0.0.1',
  },
  onedrive: {
    name: 'OneDrive',
    authUrl: 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize',
    tokenUrl: 'https://login.microsoftonline.com/common/oauth2/v2.0/token',
    scope: 'Files.ReadWrite.AppFolder offline_access',
    extraAuth: {},
    redirectHost: 'localhost',
  },
};

const b64u = (b) => Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const form = (o) => new URLSearchParams(o).toString();
const scrypt = (pass, salt) => new Promise((res, rej) => crypto.scrypt(pass, salt, 32, { N: 1 << 15, r: 8, p: 1, maxmem: 128 * 1024 * 1024 }, (e, k) => (e ? rej(e) : res(k))));

function seal(key, obj) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const data = Buffer.concat([c.update(JSON.stringify(obj), 'utf8'), c.final()]);
  return JSON.stringify({ v: 1, iv: iv.toString('base64'), tag: c.getAuthTag().toString('base64'), data: data.toString('base64') });
}
function open(key, text) {
  const j = JSON.parse(text);
  const d = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(j.iv, 'base64'));
  d.setAuthTag(Buffer.from(j.tag, 'base64'));
  return JSON.parse(Buffer.concat([d.update(Buffer.from(j.data, 'base64')), d.final()]).toString('utf8'));
}

// ---- sign in: OAuth 2 with PKCE, answered on a loopback address ----
function authorize(provider, { clientId, clientSecret }, openExternal) {
  const P = PROVIDERS[provider];
  const verifier = b64u(crypto.randomBytes(48));
  const challenge = b64u(crypto.createHash('sha256').update(verifier).digest());
  const state = b64u(crypto.randomBytes(16));
  return new Promise((resolve, reject) => {
    let done = false;
    const server = http.createServer();
    const finish = (err, val) => { if (done) return; done = true; clearTimeout(timer); try { server.close(); } catch { /* closed */ } err ? reject(err) : resolve(val); };
    const timer = setTimeout(() => finish(new Error('Sign-in timed out. Try again.')), 5 * 60 * 1000);
    server.on('request', async (req, res) => {
      const u = new URL(req.url, 'http://x');
      if (u.pathname !== '/') { res.writeHead(404); res.end(); return; }
      const page = (t) => { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); res.end('<!doctype html><meta charset="utf-8"><title>mbrowser</title><body style="font:16px system-ui;padding:40px">' + t + '</body>'); };
      if (u.searchParams.get('state') !== state) { page('Sign-in failed (state mismatch). You can close this tab.'); return finish(new Error('Sign-in failed (state mismatch).')); }
      const err = u.searchParams.get('error');
      if (err) { page('Sign-in was cancelled. You can close this tab.'); return finish(new Error('Sign-in was cancelled (' + err + ').')); }
      try {
        const redirect = 'http://' + P.redirectHost + ':' + server.address().port;
        const body = { client_id: clientId, code: u.searchParams.get('code'), code_verifier: verifier, grant_type: 'authorization_code', redirect_uri: redirect };
        if (clientSecret) body.client_secret = clientSecret;
        const r = await fetch(P.tokenUrl, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: form(body) });
        const j = await r.json();
        if (!r.ok || !j.access_token) throw new Error(j.error_description || j.error || ('HTTP ' + r.status));
        page('Signed in. You can close this tab and go back to the browser.');
        finish(null, { access: j.access_token, refresh: j.refresh_token || '', expires: Date.now() + (j.expires_in || 3600) * 1000 });
      } catch (e) { page('Sign-in failed. You can close this tab.'); finish(new Error('Sign-in failed: ' + e.message)); }
    });
    server.on('error', (e) => finish(e));
    server.listen(0, '127.0.0.1', () => {
      const redirect = 'http://' + P.redirectHost + ':' + server.address().port;
      const q = { client_id: clientId, redirect_uri: redirect, response_type: 'code', scope: P.scope, code_challenge: challenge, code_challenge_method: 'S256', state, ...P.extraAuth };
      Promise.resolve(openExternal(P.authUrl + '?' + form(q))).catch((e) => finish(e));
    });
  });
}

// ---- the two drives: read and write one small file in the app's private folder ----
function drive(provider, authed) {
  if (provider === 'gdrive') {
    const ids = new Map();
    const find = async (name) => {
      if (ids.has(name)) return ids.get(name);
      const q = encodeURIComponent("name='" + name + "' and trashed=false");
      const r = await authed('https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id)&q=' + q);
      if (!r.ok) throw new Error('Google Drive: HTTP ' + r.status);
      const f = (await r.json()).files || [];
      if (f[0]) ids.set(name, f[0].id);
      return f[0] ? f[0].id : null;
    };
    return {
      async read(name) {
        const id = await find(name); if (!id) return null;
        const r = await authed('https://www.googleapis.com/drive/v3/files/' + id + '?alt=media');
        if (r.status === 404) { ids.delete(name); return null; }
        if (!r.ok) throw new Error('Google Drive: HTTP ' + r.status);
        return r.text();
      },
      async write(name, text) {
        const id = await find(name);
        let r;
        if (id) {
          r = await authed('https://www.googleapis.com/upload/drive/v3/files/' + id + '?uploadType=media', { method: 'PATCH', headers: { 'content-type': 'application/json' }, body: text });
        } else {
          const b = 'mb' + crypto.randomBytes(8).toString('hex');
          const body = '--' + b + '\r\ncontent-type: application/json; charset=UTF-8\r\n\r\n' + JSON.stringify({ name, parents: ['appDataFolder'] })
            + '\r\n--' + b + '\r\ncontent-type: application/json\r\n\r\n' + text + '\r\n--' + b + '--';
          r = await authed('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id', { method: 'POST', headers: { 'content-type': 'multipart/related; boundary=' + b }, body });
          if (r.ok) { try { ids.set(name, (await r.clone().json()).id); } catch { /* ignore */ } }
        }
        if (!r.ok) throw new Error('Google Drive: could not save ' + name + ' (HTTP ' + r.status + ')');
      },
    };
  }
  const url = (name) => 'https://graph.microsoft.com/v1.0/me/drive/special/approot:/' + encodeURIComponent(name) + ':/content';
  return {
    async read(name) {
      const r = await authed(url(name));
      if (r.status === 404) return null;
      if (!r.ok) throw new Error('OneDrive: HTTP ' + r.status);
      return r.text();
    },
    async write(name, text) {
      const r = await authed(url(name), { method: 'PUT', headers: { 'content-type': 'text/plain' }, body: text });
      if (!r.ok) throw new Error('OneDrive: could not save ' + name + ' (HTTP ' + r.status + ')');
    },
  };
}

// ---- the server's API, rebuilt on top of encrypted files ----
const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
const good = (u) => typeof u === 'string' && /^https?:\/\//i.test(u.trim()) && u.length <= 8000;
const clip = (s, n = 900) => String(s == null ? '' : s).slice(0, n);

const FILES = { bookmarks: 'bookmarks.enc', history: 'history.enc', tabs: 'tabs.enc', vault: 'vault.enc', settings: 'settings.enc' };
const EMPTY = { bookmarks: () => ({ rev: 0, items: {} }), history: () => ({ rev: 0, items: [] }), tabs: () => ({ devices: {} }), vault: () => ({ version: 0, blob: null }), settings: () => ({ version: 0, doc: null }) };

function handlers() {
  return {
    bookmarks: (d, body) => {
      const since = num(body.since);
      for (const it of Array.isArray(body.items) ? body.items : []) {
        if (!it || !good(it.url) || !('updated' in it)) continue;
        const url = String(it.url).trim(), cur = d.items[url];
        if (cur && !(num(it.updated) > cur.updated)) continue;
        d.items[url] = { title: clip(it.title), folder: clip(it.folder, 300), added: num(it.added), updated: num(it.updated), deleted: !!it.deleted, rev: ++d.rev };
      }
      const items = Object.entries(d.items).filter(([, v]) => v.rev > since).map(([url, v]) => ({ url, title: v.title, folder: v.folder, added: v.added, updated: v.updated, deleted: v.deleted }));
      return { rev: d.rev, items };
    },
    history: (d, body) => {
      const since = num(body.since);
      const seen = new Set(d.items.map((h) => h.url + '|' + h.time));
      for (const it of Array.isArray(body.items) ? body.items : []) {
        if (!it || !good(it.url) || !('time' in it)) continue;
        const k = String(it.url).trim() + '|' + num(it.time);
        if (seen.has(k)) continue;
        seen.add(k);
        d.items.push({ url: String(it.url).trim(), title: clip(it.title), time: num(it.time), rev: ++d.rev });
      }
      if (d.items.length > MAX_HISTORY) d.items = d.items.slice(-MAX_HISTORY);
      const rows = d.items.filter((h) => h.rev > since).slice(0, PAGE);
      const rev = rows.length === PAGE ? rows[rows.length - 1].rev : d.rev;
      return { rev, items: rows.map((h) => ({ url: h.url, title: h.title, time: h.time })) };
    },
    tabsPut: (d, body) => {
      const device = clip(body.device, 64).trim();
      if (!device) throw new Error('device is required');
      d.devices[device] = { updated: Date.now(), tabs: (Array.isArray(body.tabs) ? body.tabs : []).slice(0, 200).filter((t) => t && good(t.url)).map((t) => ({ url: String(t.url).trim(), title: clip(t.title) })) };
      return { ok: true };
    },
    tabsGet: (d, exclude) => ({ devices: Object.entries(d.devices).filter(([n]) => n !== exclude).map(([device, v]) => ({ device, updated: v.updated, tabs: v.tabs })).sort((a, b) => b.updated - a.updated) }),
    vaultGet: (d) => (d.blob ? { exists: true, version: d.version, blob: d.blob } : { exists: false, version: 0, blob: null }),
    vaultPut: (d, body) => {
      const blob = body.blob;
      if (!blob || typeof blob !== 'object' || !['salt', 'iv', 'tag', 'data'].every((k) => typeof blob[k] === 'string') || typeof blob.kdf !== 'object') throw new Error('invalid vault');
      if (num(body.base) !== d.version) return { conflict: true };
      d.version += 1; d.blob = blob;
      return { version: d.version };
    },
    settingsGet: (d) => ({ version: d.version, doc: d.doc }),
    settingsPut: (d, body) => {
      if (!body.doc || typeof body.doc !== 'object') throw new Error('invalid settings document');
      if (num(body.base) !== d.version) return { conflict: true };
      d.version += 1; d.doc = body.doc;
      return { version: d.version };
    },
  };
}

// ---- the object the browser uses ----
// file: where the sign-in (tokens + key) is kept. safe: Electron's safeStorage (or a stand-in in tests).
function createCloud({ file, safe, openExternal, fetchImpl }) {
  const doFetch = fetchImpl || fetch;
  let st = { provider: '', clientId: '', clientSecret: '', token: null, key: '', account: '' };
  try {
    const raw = fs.readFileSync(file);
    const text = safe && safe.isEncryptionAvailable() && raw[0] !== 0x7b ? safe.decryptString(raw) : raw.toString('utf8');
    st = { ...st, ...JSON.parse(text) };
  } catch { /* not signed in yet */ }
  const persist = () => {
    const text = JSON.stringify(st);
    try { fs.writeFileSync(file, safe && safe.isEncryptionAvailable() ? safe.encryptString(text) : text, { mode: 0o600 }); } catch { /* best effort */ }
  };
  let chain = Promise.resolve();
  const H = handlers();

  async function refresh() {
    const P = PROVIDERS[st.provider];
    if (!st.token || !st.token.refresh) throw new Error('Signed out of ' + P.name + '. Sign in again in Settings > Sync.');
    const body = { client_id: st.clientId, grant_type: 'refresh_token', refresh_token: st.token.refresh };
    if (st.clientSecret) body.client_secret = st.clientSecret;
    const r = await doFetch(P.tokenUrl, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: form(body) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.access_token) throw new Error('Could not renew the ' + P.name + ' sign-in (' + (j.error_description || j.error || 'HTTP ' + r.status) + '). Sign in again in Settings > Sync.');
    st.token = { access: j.access_token, refresh: j.refresh_token || st.token.refresh, expires: Date.now() + (j.expires_in || 3600) * 1000 };
    persist();
  }
  async function authed(url, opts = {}) {
    if (!st.token) throw new Error('Not signed in to ' + PROVIDERS[st.provider].name);
    if (Date.now() > st.token.expires - 60000) await refresh();
    const go = () => doFetch(url, { ...opts, headers: { ...(opts.headers || {}), authorization: 'Bearer ' + st.token.access }, signal: AbortSignal.timeout(30000) });
    let r = await go();
    if (r.status === 401) { await refresh(); r = await go(); }
    return r;
  }
  const dr = () => drive(st.provider, authed);
  const keyBuf = () => Buffer.from(st.key, 'hex');

  async function readFile(name, kind) {
    const text = await dr().read(FILES[name]);
    if (!text) return EMPTY[kind || name]();
    try { return open(keyBuf(), text); } catch { throw new Error('Could not decrypt ' + FILES[name] + ': the sync passphrase on this device does not match the one used by your other devices.'); }
  }
  const writeFile = (name, obj) => dr().write(FILES[name], seal(keyBuf(), obj));

  // the same entry point as the server API
  async function run(method, path, body) {
    const [p, qs] = path.split('?');
    const q = new URLSearchParams(qs || '');
    const key = p.replace('/api/', '');
    const mutate = async (name, fn) => { const d = await readFile(name); const r = fn(d); if (!r.conflict) await writeFile(name, d); return r; };
    if (key === 'bookmarks' && method === 'POST') return mutate('bookmarks', (d) => H.bookmarks(d, body || {}));
    if (key === 'history' && method === 'POST') return mutate('history', (d) => H.history(d, body || {}));
    if (key === 'tabs' && method === 'PUT') return mutate('tabs', (d) => H.tabsPut(d, body || {}));
    if (key === 'tabs' && method === 'GET') return H.tabsGet(await readFile('tabs'), q.get('exclude') || '');
    if (key === 'vault' && method === 'GET') return H.vaultGet(await readFile('vault'));
    if (key === 'vault' && method === 'PUT') return mutate('vault', (d) => H.vaultPut(d, body || {}));
    if (key === 'settings' && method === 'GET') return H.settingsGet(await readFile('settings'));
    if (key === 'settings' && method === 'PUT') return mutate('settings', (d) => H.settingsPut(d, body || {}));
    throw new Error(path + ' is not available with ' + PROVIDERS[st.provider].name + ' sync');
  }

  return {
    providers: () => Object.keys(PROVIDERS),
    label: (p) => (PROVIDERS[p] || {}).name || '',
    status: () => ({ provider: st.provider, connected: !!(st.provider && st.token), hasKey: !!st.key, ready: !!(st.provider && st.token && st.key), account: st.account }),
    ready: () => !!(st.provider && st.token && st.key),
    async connect(provider, { clientId, clientSecret }) {
      if (!PROVIDERS[provider]) throw new Error('Unknown provider');
      if (!clientId) throw new Error('Enter the ' + PROVIDERS[provider].name + ' client ID first (see the steps below).');
      const token = await authorize(provider, { clientId, clientSecret }, openExternal);
      if (!token.refresh) throw new Error('The provider did not return a refresh token, so sync could not stay signed in. Try again.');
      st = { provider, clientId, clientSecret: clientSecret || '', token, key: '', account: PROVIDERS[provider].name };
      persist();
      return this.status();
    },
    // first device: creates the passphrase check in the drive. Other devices: verifies against it.
    async setPassphrase(pass) {
      if (!st.token) throw new Error('Sign in first.');
      if (String(pass || '').length < 8) throw new Error('Use at least 8 characters for the sync passphrase.');
      const d = dr();
      const text = await d.read('mbrowser-meta.json');
      if (text) {
        const meta = JSON.parse(text);
        const key = await scrypt(pass, Buffer.from(meta.salt, 'base64'));
        let ok = false;
        try { ok = open(key, JSON.stringify(meta.check)).ok === 'mbrowser'; } catch { ok = false; }
        if (!ok) throw new Error('That is not the sync passphrase used by your other devices.');
        st.key = key.toString('hex'); persist();
        return { joined: true };
      }
      const salt = crypto.randomBytes(16);
      const key = await scrypt(pass, salt);
      await d.write('mbrowser-meta.json', JSON.stringify({ v: 1, kdf: 'scrypt-N32768-r8-p1', salt: salt.toString('base64'), check: JSON.parse(seal(key, { ok: 'mbrowser' })) }));
      st.key = key.toString('hex'); persist();
      return { joined: false };
    },
    disconnect() { st = { provider: '', clientId: '', clientSecret: '', token: null, key: '', account: '' }; try { fs.rmSync(file, { force: true }); } catch { /* gone */ } },
    // one sync request at a time, so two overlapping syncs cannot overwrite each other's files
    call(method, path, body, opts = {}) {
      if (!this.ready()) return Promise.reject(new Error('Cloud sync is not set up yet (Settings > Sync).'));
      const job = chain.then(() => run(method, path, body)).then((r) => (r && r.conflict && !opts.allow409 ? Promise.reject(new Error(path + ' changed, try again')) : r));
      chain = job.catch(() => {});
      return job;
    },
  };
}

module.exports = { createCloud, PROVIDERS, seal, open };
