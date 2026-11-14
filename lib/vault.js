'use strict';
const crypto = require('crypto');
const fs = require('fs');

// An encrypted password store. The file holds only ciphertext (AES-256-GCM); the key is derived from a
// master password with scrypt and exists in memory only while the vault is unlocked.
const KDF = { N: 65536, r: 8, p: 1 }; // new vaults; existing ones keep the cost stored in their file

const derive = (password, salt, kdf) =>
  crypto.scryptSync(String(password), salt, 32, { N: kdf.N, r: kdf.r, p: kdf.p, maxmem: 256 * 1024 * 1024 });

const originOf = (url) => {
  try {
    const u = new URL(url);
    return /^https?:$/.test(u.protocol) ? u.origin : null;
  } catch { return null; }
};

function csvCell(v) {
  let s = String(v == null ? '' : v);
  // stop spreadsheet programs from treating a cell as a formula
  if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
  return '"' + s.replace(/"/g, '""') + '"';
}

const TOMBSTONE_MS = 90 * 864e5;

function decryptBlob(blob, k) {
  const d = crypto.createDecipheriv('aes-256-gcm', k, Buffer.from(blob.iv, 'base64'));
  d.setAuthTag(Buffer.from(blob.tag, 'base64'));
  const plain = JSON.parse(Buffer.concat([d.update(Buffer.from(blob.data, 'base64')), d.final()]).toString('utf8'));
  return { entries: plain.entries || [], folders: Array.isArray(plain.folders) ? plain.folders.filter((f) => typeof f === 'string') : [] };
}

const hostOfOrigin = (o) => { try { return new URL(o).hostname.replace(/^www\./, ''); } catch { return o || ''; } };
const cleanFolder = (f) => String(f || '').trim().replace(/\s+/g, ' ').slice(0, 80);

function createVault(file) {
  let key = null;
  let entries = null;
  let folders = [];   // folder names (so that empty folders survive)
  let meta = null; // { kdf, salt }

  const readFile = () => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };

  function persist() {
    const iv = crypto.randomBytes(12);
    const c = crypto.createCipheriv('aes-256-gcm', key, iv);
    const cutoff = Date.now() - TOMBSTONE_MS;
    entries = entries.filter((e) => !(e.deleted && e.updated < cutoff)); // forget old deletions
    const data = Buffer.concat([c.update(JSON.stringify({ entries, folders }), 'utf8'), c.final()]);
    const out = {
      v: 1, kdf: meta.kdf, salt: meta.salt,
      iv: iv.toString('base64'), tag: c.getAuthTag().toString('base64'), data: data.toString('base64'),
    };
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(out), { mode: 0o600 });
    fs.renameSync(tmp, file);
  }

  const must = () => { if (!key) throw new Error('locked'); };
  const live = () => entries.filter((e) => !e.deleted);
  const clean = (e) => ({
    id: e.id, name: e.name || hostOfOrigin(e.origin), origin: e.origin, username: e.username, note: e.note || '',
    folder: e.folder || '', created: e.created, updated: e.updated,
  });

  const api = {
    exists() { const f = readFile(); return !!(f && f.data && f.salt); },
    unlocked() { return key !== null; },

    create(master) {
      if (api.exists()) throw new Error('exists');
      const salt = crypto.randomBytes(16);
      key = derive(master, salt, KDF);
      meta = { kdf: { ...KDF }, salt: salt.toString('base64') };
      entries = [];
      folders = [];
      persist();
    },

    unlock(master) {
      const f = readFile();
      if (!f) return false;
      try {
        const k = derive(master, Buffer.from(f.salt, 'base64'), f.kdf);
        const dec = decryptBlob(f, k);
        entries = dec.entries;
        folders = dec.folders;
        key = k;
        meta = { kdf: f.kdf, salt: f.salt };
        return true;
      } catch { return false; } // wrong password or a damaged / modified file
    },

    lock() { if (key) key.fill(0); key = null; entries = null; folders = []; },

    count() { must(); return live().length; },
    list() { must(); return live().map(clean); },
    reveal(id) { must(); const e = live().find((x) => x.id === id); return e ? e.password : null; },
    get(id) { must(); const e = live().find((x) => x.id === id); return e ? clean(e) : null; },
    findByOrigin(origin) { must(); return live().filter((e) => e.origin === origin).map(clean); },

    // add or update. Returns the entry id.
    upsert({ id, url, origin, name, folder, username, password, note }) {
      must();
      const o = origin || originOf(url);
      if (!o) throw new Error('bad address');
      if (typeof password !== 'string' || !password || password.length > 1024) throw new Error('bad password');
      const user = String(username || '').slice(0, 512);
      const now = Date.now();
      let e = id ? live().find((x) => x.id === id) : null;
      if (!e) e = live().find((x) => x.origin === o && x.username === user);
      if (!e) {
        e = { id: 'p' + crypto.randomBytes(8).toString('hex'), created: now };
        entries.push(e);
      }
      const f = cleanFolder(folder);
      Object.assign(e, { origin: o, username: user, password, note: String(note || '').slice(0, 2000), updated: now });
      if (name !== undefined) e.name = String(name || '').trim().slice(0, 120);
      if (folder !== undefined) { e.folder = f; if (f && !folders.includes(f)) folders.push(f); }
      persist();
      return e.id;
    },

    // deletions are kept as empty "tombstones" so that they also reach your other devices when synced
    remove(id) {
      must();
      const e = entries.find((x) => x.id === id);
      if (!e) return;
      Object.assign(e, { deleted: true, updated: Date.now(), username: '', password: '', note: '', name: '', folder: '' });
      persist();
    },

    // ---- folders ----
    listFolders() {
      must();
      const counts = new Map(folders.map((f) => [f, 0]));
      for (const e of live()) if (e.folder) counts.set(e.folder, (counts.get(e.folder) || 0) + 1);
      return [...counts].map(([name, count]) => ({ name, count })).sort((a, b) => a.name.localeCompare(b.name));
    },
    addFolder(name) {
      must();
      const f = cleanFolder(name);
      if (!f) throw new Error('Enter a folder name.');
      if (!folders.includes(f)) { folders.push(f); persist(); }
      return f;
    },
    renameFolder(oldName, newName) {
      must();
      const nf = cleanFolder(newName);
      if (!nf) throw new Error('Enter a folder name.');
      const now = Date.now();
      folders = folders.filter((x) => x !== oldName);
      if (!folders.includes(nf)) folders.push(nf);
      for (const e of live()) if (e.folder === oldName) { e.folder = nf; e.updated = now; }
      persist();
    },
    // items in the folder move to "no folder"; the logins themselves are kept
    removeFolder(name) {
      must();
      const now = Date.now();
      folders = folders.filter((x) => x !== name);
      for (const e of live()) if (e.folder === name) { e.folder = ''; e.updated = now; }
      persist();
    },
    moveEntries(ids, folder) {
      must();
      const f = cleanFolder(folder);
      const set = new Set(ids);
      const now = Date.now();
      for (const e of live()) if (set.has(e.id)) { e.folder = f; e.updated = now; }
      if (f && !folders.includes(f)) folders.push(f);
      persist();
    },

    changeMaster(oldPw, newPw) {
      must();
      const f = readFile();
      const check = derive(oldPw, Buffer.from(f.salt, 'base64'), f.kdf);
      const same = check.length === key.length && crypto.timingSafeEqual(check, key);
      check.fill(0);
      if (!same) return false;
      const salt = crypto.randomBytes(16);
      key.fill(0);
      key = derive(newPw, salt, KDF);
      meta = { kdf: { ...KDF }, salt: salt.toString('base64') };
      persist();
      return true;
    },

    // ---- helpers for syncing the (already encrypted) file ----
    blobText() { try { return fs.readFileSync(file, 'utf8'); } catch { return null; } },
    backup() { try { fs.copyFileSync(file, file + '.local-backup'); } catch { /* nothing to back up */ } },

    // replace the local file with a blob from the server; stay unlocked only if the current key still opens it
    adopt(blob) {
      const text = JSON.stringify({ v: blob.v || 1, kdf: blob.kdf, salt: blob.salt, iv: blob.iv, tag: blob.tag, data: blob.data });
      const tmp = file + '.tmp';
      fs.writeFileSync(tmp, text, { mode: 0o600 });
      fs.renameSync(tmp, file);
      if (!key) return;
      try {
        const dec = decryptBlob(blob, key);
        entries = dec.entries;
        folders = dec.folders;
        meta = { kdf: blob.kdf, salt: blob.salt };
      } catch { api.lock(); }
    },

    // merge a blob from the server into the open vault. { ok:false } when it was made with another key.
    mergeRemote(blob) {
      must();
      let remote, remoteFolders;
      try { const dec = decryptBlob(blob, key); remote = dec.entries; remoteFolders = dec.folders; } catch { return { ok: false }; }
      const before = JSON.stringify({ entries, folders });
      const byId = new Map(entries.map((e) => [e.id, e]));
      for (const r of remote) {
        const l = byId.get(r.id);
        if (!l || (r.updated || 0) > (l.updated || 0)) byId.set(r.id, r);
      }
      const seen = new Set();
      const merged = [];
      for (const e of [...byId.values()].sort((a, b) => (b.updated || 0) - (a.updated || 0))) {
        if (!e.deleted) { // the same login created on two devices: keep the newest one
          const k = e.origin + '\n' + e.username;
          if (seen.has(k)) continue;
          seen.add(k);
        }
        merged.push(e);
      }
      entries = merged;
      folders = [...new Set([...folders, ...remoteFolders])];
      const changed = JSON.stringify({ entries, folders }) !== before;
      if (changed) persist();
      return { ok: true, changed };
    },

    exportCsv() {
      must();
      const rows = [['name', 'url', 'username', 'password', 'note', 'folder']];
      for (const e of live()) rows.push([e.name || hostOfOrigin(e.origin), e.origin, e.username, e.password, e.note || '', e.folder || '']);
      return rows.map((r) => r.map(csvCell).join(',')).join('\n') + '\n';
    },

    // rows: [{ url, username, password, note }]; returns { added, updated, skipped }
    importRows(rows) {
      must();
      let added = 0, updated = 0, skipped = 0;
      for (const r of rows.slice(0, 20000)) {
        const o = originOf(r.url);
        if (!o || !r.password || String(r.password).length > 1024) { skipped++; continue; }
        const user = String(r.username || '');
        const existing = live().find((e) => e.origin === o && e.username === user);
        if (existing && existing.password === r.password) { skipped++; continue; }
        const now = Date.now();
        if (existing) {
          Object.assign(existing, { password: r.password, updated: now });
          if (r.name && !existing.name) existing.name = String(r.name).slice(0, 120);
          if (r.folder && !existing.folder) { existing.folder = cleanFolder(r.folder); if (!folders.includes(existing.folder)) folders.push(existing.folder); }
          updated++;
        } else {
          const f = cleanFolder(r.folder);
          if (f && !folders.includes(f)) folders.push(f);
          entries.push({
            id: 'p' + crypto.randomBytes(8).toString('hex'), origin: o, username: user.slice(0, 512),
            name: String(r.name || '').trim().slice(0, 120), folder: f,
            password: String(r.password), note: String(r.note || '').slice(0, 2000), created: now, updated: now,
          });
          added++;
        }
      }
      persist();
      return { added, updated, skipped };
    },
  };
  return api;
}

module.exports = { createVault, originOf };
