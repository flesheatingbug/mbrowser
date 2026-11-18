'use strict';
// Optional password for the "Lock profile" screen. Stored per profile on this computer only (never synced).
// This keeps casual onlookers out; it does not encrypt your data on disk.
const fs = require('fs');
const crypto = require('crypto');

function createLock(file) {
  const read = () => { try { const j = JSON.parse(fs.readFileSync(file, 'utf8')); return j && j.salt && j.hash ? j : null; } catch { return null; } };
  const derive = (pw, salt) => crypto.scryptSync(String(pw), Buffer.from(salt, 'base64'), 32, { N: 16384, r: 8, p: 1 });
  return {
    has: () => !!read(),
    set(pw) {
      const salt = crypto.randomBytes(16);
      fs.writeFileSync(file, JSON.stringify({ salt: salt.toString('base64'), hash: derive(pw, salt.toString('base64')).toString('base64') }), { mode: 0o600 });
    },
    clear() { try { fs.unlinkSync(file); } catch { /* none */ } },
    verify(pw) {
      const j = read();
      if (!j) return true;                       // no password set: anyone can unlock
      const a = derive(pw || '', j.salt), b = Buffer.from(j.hash, 'base64');
      return a.length === b.length && crypto.timingSafeEqual(a, b);
    },
  };
}
module.exports = { createLock };
