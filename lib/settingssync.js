'use strict';
const { SYNC_KEYS } = require('./settings');

// Keeps settings (themes, search engines, speed dials, shortcuts, panel…) in step between devices.
// Every group has its own "last changed" time, so changes made to different groups on different
// devices are both kept, and for the same group the newest change wins.
// call(method, path, body, { allow409 }) talks to the server.
async function syncSettings({ store, call }) {
  const remote = await call('GET', '/api/settings');
  const rgroups = remote.doc && remote.doc.groups && typeof remote.doc.groups === 'object' ? remote.doc.groups : {};
  const merged = {};
  const applied = [];
  let push = !remote.doc;

  for (const key of SYNC_KEYS) {
    const local = { updated: store.updatedOf(key), value: store.syncable(key) };
    const r = rgroups[key];
    const valid = r && typeof r.updated === 'number' && r.value && typeof r.value === 'object' && !Array.isArray(r.value);
    if (!valid) { merged[key] = local; push = true; continue; }
    if (r.updated > local.updated) { store.applyGroup(key, r.value, r.updated); merged[key] = r; applied.push(key); }
    else if (r.updated < local.updated) { merged[key] = local; push = true; }
    else merged[key] = r;
  }

  if (push) {
    const res = await call('PUT', '/api/settings', { base: remote.version, doc: { groups: merged } }, { allow409: true });
    if (res && res.conflict) return { state: 'retry', message: 'The server changed, trying again soon', applied };
  }
  return {
    state: 'ok', applied,
    message: applied.length ? 'Settings updated from another device (' + applied.join(', ') + ')' : (push ? 'Settings uploaded' : 'Settings up to date'),
  };
}

module.exports = { syncSettings };
