'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const COLORS = ['#5b8cff', '#2ec4b6', '#f4a261', '#e76f51', '#c77dff', '#ef476f', '#8ac926', '#ffd166'];

const hashPw = (pw, salt) => crypto.scryptSync(String(pw), salt, 32, { N: 16384, r: 8, p: 1 });
const validId = (id) => id === 'default' || /^[0-9a-f]{8}$/.test(String(id));

function createProfiles(baseDir) {
  const file = path.join(baseDir, 'mbrowser-profiles.json');
  let data = null;

  function load() {
    try { data = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { data = null; }
    if (!data || !Array.isArray(data.profiles)) data = { askAtStart: false, profiles: [] };
    if (!data.profiles.some((p) => p.id === 'default')) data.profiles.unshift({ id: 'default', name: 'Default', color: COLORS[0] });
  }
  function save() {
    fs.mkdirSync(baseDir, { recursive: true });
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, file);
  }
  load();

  const find = (id) => data.profiles.find((p) => p.id === id);
  const pub = (p) => ({ id: p.id, name: p.name, color: p.color, locked: !!p.pw, avatar: p.avatar || '' });
  const dirFor = (id) => (id === 'default' ? path.join(baseDir, 'mbrowser') : path.join(baseDir, 'mbrowser-profile-' + id));
  const cleanName = (n) => String(n || '').trim().replace(/\s+/g, ' ').slice(0, 40);

  function verify(id, pw) {
    const p = find(id);
    if (!p) return false;
    if (!p.pw) return true;
    const h = hashPw(pw, Buffer.from(p.pw.salt, 'base64'));
    const want = Buffer.from(p.pw.hash, 'base64');
    return h.length === want.length && crypto.timingSafeEqual(h, want);
  }
  const makePw = (pw) => {
    const salt = crypto.randomBytes(16);
    return { salt: salt.toString('base64'), hash: hashPw(pw, salt).toString('base64') };
  };

  return {
    reload: load,
    list: () => data.profiles.map(pub),
    get: (id) => { const p = find(id); return p ? pub(p) : null; },
    dirFor,
    askAtStart: () => !!data.askAtStart,
    setAskAtStart(v) { data.askAtStart = !!v; save(); },

    // --- first-run wizard ---
    setupDone: () => !!data.setupDone,
    setSetupDone() { data.setupDone = true; save(); },
    clearSetupDone() { data.setupDone = false; save(); },

    // --- default profile ---
    getDefaultProfile: () => data.defaultProfile || 'default',
    setDefaultProfile(id) {
      if (!validId(id) || !find(id)) return { error: 'Unknown profile.' };
      data.defaultProfile = id; save(); return { ok: true };
    },

    // --- open last profile on launch ---
    getOpenLastProfile: () => !!data.openLastProfile,
    setOpenLastProfile(v) { data.openLastProfile = !!v; save(); },
    getLastProfile: () => data.lastProfile || null,
    setLastProfile(id) { if (validId(id)) { data.lastProfile = id; save(); } },

    verify,

    create({ name, color, avatar, password }) {
      const n = cleanName(name);
      if (!n) return { error: 'Enter a name for the profile.' };
      if (data.profiles.some((p) => p.name.toLowerCase() === n.toLowerCase())) return { error: 'A profile with that name already exists.' };
      const p = {
        id: crypto.randomBytes(4).toString('hex'), name: n,
        color: /^#[0-9a-f]{6}$/i.test(color || '') ? color : COLORS[data.profiles.length % COLORS.length],
      };
      if (avatar) p.avatar = String(avatar).slice(0, 200000);
      if (password) p.pw = makePw(password);
      data.profiles.push(p);
      save();
      return { ok: true, id: p.id };
    },

    update(id, { name, color, avatar }) {
      const p = find(id);
      if (!p) return { error: 'Unknown profile.' };
      if (name !== undefined) {
        const n = cleanName(name);
        if (!n) return { error: 'Enter a name for the profile.' };
        if (data.profiles.some((x) => x.id !== id && x.name.toLowerCase() === n.toLowerCase())) return { error: 'A profile with that name already exists.' };
        p.name = n;
      }
      if (color !== undefined && /^#[0-9a-f]{6}$/i.test(color)) p.color = color;
      if (avatar !== undefined) p.avatar = String(avatar).slice(0, 200000); // allow data URLs
      save();
      return { ok: true };
    },

    setPassword(id, newPw, oldPw) {
      const p = find(id);
      if (!p) return { error: 'Unknown profile.' };
      if (p.pw && !verify(id, oldPw || '')) return { error: 'The current password is wrong.' };
      if (newPw) p.pw = makePw(newPw); else delete p.pw;
      save();
      return { ok: true };
    },

    remove(id, pw) {
      if (id === 'default') return { error: 'The default profile cannot be deleted.' };
      if (!validId(id)) return { error: 'Unknown profile.' };
      const p = find(id);
      if (!p) return { error: 'Unknown profile.' };
      if (p.pw && !verify(id, pw || '')) return { error: 'The password is wrong.' };
      data.profiles = data.profiles.filter((x) => x.id !== id);
      if (data.defaultProfile === id) delete data.defaultProfile;
      if (data.lastProfile === id) delete data.lastProfile;
      save();
      const dir = dirFor(id);
      if (path.dirname(dir) === baseDir && /^mbrowser-profile-[0-9a-f]{8}$/.test(path.basename(dir))) {
        try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* may be in use */ }
      }
      return { ok: true };
    },

    // Clear a profile's local data files but keep the profile entry
    resetProfile(id) {
      if (!validId(id)) return { error: 'Unknown profile.' };
      if (!find(id)) return { error: 'Unknown profile.' };
      const dir = dirFor(id);
      try {
        if (fs.existsSync(dir)) {
          for (const f of fs.readdirSync(dir)) {
            try { fs.rmSync(path.join(dir, f), { recursive: true, force: true }); } catch { /* skip locked files */ }
          }
        }
      } catch (e) { return { error: 'Could not reset: ' + e.message }; }
      return { ok: true };
    },

    // Reset everything: wipe all data, return to factory state
    resetAll() {
      for (const p of data.profiles) {
        const dir = dirFor(p.id);
        try { if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true, force: true }); } catch { /* skip */ }
      }
      data = { askAtStart: false, profiles: [{ id: 'default', name: 'Default', color: COLORS[0] }] };
      save();
      return { ok: true };
    },
  };
}

module.exports = { createProfiles, COLORS };
