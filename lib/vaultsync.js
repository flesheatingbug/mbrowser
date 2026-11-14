'use strict';
const crypto = require('crypto');

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const sameBlob = (a, b) => !!a && !!b && a.salt === b.salt && a.iv === b.iv && a.tag === b.tag && a.data === b.data;

// Keeps the encrypted password vault in step with the server. The server only ever sees ciphertext.
// state: { vaultVer, vaultHash } is remembered between runs by the caller.
// call(method, path, body, { allow409 }) talks to the server (returns { conflict: true } on HTTP 409 when allowed).
async function syncVault({ vault, call, state }) {
  const res = (st, message) => ({ state: st, message });
  const localText = vault.blobText();
  const local = localText ? JSON.parse(localText) : null;
  const remote = await call('GET', '/api/vault');

  const upload = async (base) => {
    const text = vault.blobText();
    const r = await call('PUT', '/api/vault', { base, blob: JSON.parse(text) }, { allow409: true });
    if (r && r.conflict) return null;
    state.vaultVer = r.version;
    state.vaultHash = sha(text);
    return r;
  };
  const take = (blob, version) => {
    vault.adopt(blob);
    state.vaultVer = version;
    state.vaultHash = sha(vault.blobText());
  };

  if (!remote.exists && !local) return res('ok', 'No password vault yet');
  if (!remote.exists) return (await upload(0)) ? res('ok', 'Vault uploaded (encrypted)') : res('retry', 'The server changed, trying again soon');
  if (!local) { take(remote.blob, remote.version); return res('ok', 'Vault downloaded from the server. Unlock it with your master password.'); }

  if (sameBlob(local, remote.blob)) {
    state.vaultVer = remote.version; state.vaultHash = sha(localText);
    return res('ok', 'Vault is up to date');
  }
  const localChanged = sha(localText) !== state.vaultHash;
  const remoteChanged = remote.version !== state.vaultVer;
  if (!localChanged && !remoteChanged) return res('ok', 'Vault is up to date');
  if (remoteChanged && !localChanged) { take(remote.blob, remote.version); return res('ok', 'Vault updated from the server'); }
  if (!remoteChanged && localChanged) {
    return (await upload(remote.version)) ? res('ok', 'Vault changes uploaded (encrypted)') : res('retry', 'The server changed, trying again soon');
  }

  // changed on both sides (or two separate vaults meet for the first time): merge, which needs the key
  if (!vault.unlocked()) return res('needs-unlock', 'Unlock the password vault to finish syncing');
  const m = vault.mergeRemote(remote.blob);
  if (!m.ok) {
    return state.vaultVer
      ? res('different-key', 'The vault on the server now uses a different master password.')
      : res('different-vault', 'The server already has a different password vault.');
  }
  return (await upload(remote.version)) ? res('ok', 'Vault merged and uploaded (encrypted)') : res('retry', 'The server changed, trying again soon');
}

// choice 'server': keep the server's vault (the local file is backed up); 'local': overwrite the server's with this one
async function resolveVault({ vault, call, state, choice }) {
  const remote = await call('GET', '/api/vault');
  if (choice === 'server') {
    if (!remote.exists) return { state: 'ok', message: 'The server has no vault.' };
    vault.backup();
    vault.adopt(remote.blob);
    state.vaultVer = remote.version;
    state.vaultHash = sha(vault.blobText());
    return { state: 'ok', message: 'Now using the server’s vault. Unlock it with its master password. Your old local vault is saved as vault.json.local-backup.' };
  }
  const r = await call('PUT', '/api/vault', { base: remote.exists ? remote.version : 0, blob: JSON.parse(vault.blobText()) }, { allow409: true });
  if (r && r.conflict) return { state: 'retry', message: 'The server changed, try again.' };
  state.vaultVer = r.version;
  state.vaultHash = sha(vault.blobText());
  return { state: 'ok', message: 'The server’s vault was replaced with this one.' };
}

module.exports = { syncVault, resolveVault };
