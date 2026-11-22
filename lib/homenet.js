'use strict';
// Home network servers for THIS profile. Each profile has its own list, its own sign-ins and its own on/off switch,
// so enabling it in one profile never enables it in another. Sign-in tokens are kept encrypted (OS keychain) when possible.
const fs = require('fs');
const crypto = require('crypto');

function create({ file, encrypt, decrypt }) {
  const load = () => { try { const j = JSON.parse(fs.readFileSync(file, 'utf8')); return { servers: Array.isArray(j.servers) ? j.servers : [], active: j.active || '', ranges: typeof j.ranges === 'string' ? j.ranges : '' }; } catch { return { servers: [], active: '', ranges: '' }; } };
  const save = (d) => { try { fs.writeFileSync(file, JSON.stringify(d), { mode: 0o600 }); } catch { /* ignore */ } };
  const clean = (u) => String(u || '').trim().replace(/\/+$/, '');
  const tok = (s) => { try { return s.token ? decrypt(s.token) : ''; } catch { return ''; } };
  const view = (d) => ({
    active: d.active,
    ranges: d.ranges || '',
    servers: d.servers.map((s) => ({ id: s.id, name: s.name, url: s.url, key: !!s.key, user: s.user || '', signedIn: !!s.token })),
  });
  return {
    view: () => view(load()),
    /** Which addresses go through the tunnel, one per line (empty = the usual private ranges). */
    getRanges: () => load().ranges || '',
    setRanges(text) { const d = load(); d.ranges = String(text || '').slice(0, 2000); save(d); },
    add(name, url) {
      const u = clean(url);
      try { const p = new URL(u); if (!/^https?:$/.test(p.protocol)) throw new Error('x'); } catch { return { error: 'That is not a valid server address (it should start with https:// or http://).' }; }
      const d = load();
      if (d.servers.some((s) => s.url === u)) return { error: 'That server is already in the list.' };
      const s = { id: crypto.randomBytes(6).toString('hex'), name: String(name || '').trim().slice(0, 40) || new URL(u).host, url: u, key: false, user: '', token: '' };
      d.servers.push(s);
      if (!d.active) d.active = s.id;
      save(d);
      return { id: s.id };
    },
    remove(id) { const d = load(); d.servers = d.servers.filter((s) => s.id !== id); if (d.active === id) d.active = d.servers[0] ? d.servers[0].id : ''; save(d); },
    select(id) { const d = load(); if (d.servers.some((s) => s.id === id)) { d.active = id; save(d); } },
    get(id) { const s = load().servers.find((v) => v.id === id); return s ? { ...s, token: tok(s) } : null; },
    setToken(id, token, user) { const d = load(); const s = d.servers.find((v) => v.id === id); if (s) { s.token = token ? encrypt(token) : ''; s.user = token ? (user || '') : ''; save(d); } },
    setKey(id, on) { const d = load(); const s = d.servers.find((v) => v.id === id); if (s) { s.key = !!on; save(d); } },
    /** The server requests go through: { id, name, server, token, key } or null. */
    activeCfg() { const d = load(); const s = d.servers.find((v) => v.id === d.active) || d.servers[0]; return s ? { id: s.id, name: s.name, server: s.url, token: tok(s), key: !!s.key } : null; },
  };
}

const call = async (url, path, opts = {}) => {
  const r = await fetch(url + path, { ...opts, signal: AbortSignal.timeout(12000) });
  let j = {}; try { j = await r.json(); } catch { /* not JSON */ }
  return { status: r.status, body: j };
};

/** Account sign-in (password + one-time code when the account has one). Resolves { token, user } or { error, needTotp }. */
async function login(url, { username, password, otp, device }) {
  try {
    const body = { username, password, device };
    const code = String(otp || '').trim();
    if (code) { if (/^\d{6}$/.test(code)) body.totp = code; else body.recovery = code; }
    const r = await call(url, '/api/tunnel/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    if (r.status === 200 && r.body.token) return { token: r.body.token, user: (r.body.user || {}).username || username };
    if (r.status === 404) return { error: 'This server is too old for home network sign-in. Update the server image.' };
    return { error: r.body.error || ('The server answered HTTP ' + r.status + '.'), needTotp: !!r.body.needTotp };
  } catch (e) { return { error: 'Could not reach the server: ' + e.message }; }
}

async function logout(url, token) { try { await call(url, '/api/tunnel/logout', { method: 'POST', headers: { authorization: 'Bearer ' + token } }); } catch { /* best effort */ } }

/** Checks the server is ready for this sign-in. Resolves null when fine, otherwise a readable problem. */
async function check(cfg) {
  try {
    if (!cfg.token) return 'Sign in first.';
    const r = await call(cfg.server, '/api/tunnel/status', { headers: { authorization: 'Bearer ' + cfg.token } });
    if (r.status === 404) return 'This server is too old for this. Update it, then try again.';
    if (r.status === 401) return cfg.key ? 'The server rejected the sync key, or a proxy in front of it answered 401.' : 'Your home network sign-in expired. Sign in again under Settings → Home network.';
    if (r.status === 403) return r.body.error || 'The server refused the sign-in.';
    if (r.status !== 200) return 'The server answered HTTP ' + r.status + '.';
    if (!r.body.enabled) return 'The tunnel is switched off on this server. Add ALLOW_TUNNEL=true to its settings and restart it.';
    return null;
  } catch (e) { return 'Could not reach the server: ' + e.message; }
}

module.exports = { create, login, logout, check };
