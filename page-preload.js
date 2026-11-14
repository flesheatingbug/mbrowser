// Preload for every web page tab: mouse gestures, and the `browser` API for my:// pages
const { contextBridge, ipcRenderer } = require('electron');

if (location.protocol === 'my:') {
  const invoke = (ch) => (...a) => ipcRenderer.invoke(ch, ...a);
  contextBridge.exposeInMainWorld('browser', {
    getSettings: invoke('settings:get'),
    setSetting: invoke('settings:set'),
    getCommands: invoke('commands:list'),
    getThemes: invoke('themes:list'),
    go: (text) => ipcRenderer.send('nav:go', text),
    getLibrary: invoke('library:get'),
    historyQuery: invoke('history:query'),
    historyDelete: invoke('history:delete'),
    removeBookmark: invoke('bookmark:remove'),
    clearData: invoke('data:clear'),
    getDownloads: invoke('downloads:list'),
    downloadAction: invoke('downloads:action'),
    syncNow: invoke('sync:now'),
    syncStatus: invoke('sync:status'),
    allowCert: invoke('cert:allow'),
    syncLogin: invoke('sync:login'),
    syncLogout: invoke('sync:logout'),
    applyIcon: invoke('icon:apply'),
    bookmarksAll: invoke('bookmarks:all'),
    bookmarksSave: invoke('bookmarks:save'),
    bookmarksMove: invoke('bookmarks:move'),
    bookmarksRemove: invoke('bookmarks:remove'),
    bookmarksFolderAdd: invoke('bookmarks:folderAdd'),
    bookmarksFolderRename: invoke('bookmarks:folderRename'),
    bookmarksFolderDelete: invoke('bookmarks:folderDelete'),
    vaultFolders: invoke('vault:folders'),
    vaultFolderAdd: invoke('vault:folderAdd'),
    vaultFolderRename: invoke('vault:folderRename'),
    vaultFolderRemove: invoke('vault:folderRemove'),
    vaultMove: invoke('vault:move'),
    syncTest: invoke('sync:test'),
    importDetect: invoke('import:detect'),
    importRun: invoke('import:run'),
    importFile: invoke('import:file'),
    vaultImportFile: invoke('vault:importFile'),
    profilesList: invoke('profiles:list'),
    profilesCreate: invoke('profiles:create'),
    profilesUpdate: invoke('profiles:update'),
    profilesSetPassword: invoke('profiles:setPassword'),
    profilesRemove: invoke('profiles:remove'),
    profilesOpen: invoke('profiles:open'),
    profilesAskAtStart: invoke('profiles:askAtStart'),
    panelState: invoke('panel:state'),
    panelSelect: (id) => ipcRenderer.send('panel:select', id),
    panelClose: () => ipcRenderer.send('panel:close'),
    openUrl: (url, mode) => ipcRenderer.send('panel:open', url, mode),
    onPanelChanged: (cb) => ipcRenderer.on('panel:changed', () => cb()),
    vaultStatus: invoke('vault:status'),
    vaultSyncStatus: invoke('vault:syncStatus'),
    vaultSyncResolve: invoke('vault:syncResolve'),
    vaultCreate: invoke('vault:create'),
    vaultUnlock: invoke('vault:unlock'),
    vaultLock: invoke('vault:lock'),
    vaultList: invoke('vault:list'),
    vaultReveal: invoke('vault:reveal'),
    vaultCopy: invoke('vault:copy'),
    vaultSave: invoke('vault:save'),
    vaultDelete: invoke('vault:delete'),
    vaultChangeMaster: invoke('vault:changeMaster'),
    vaultExport: invoke('vault:export'),
    vaultImport: invoke('vault:import'),
    notesList: invoke('notes:list'),
    notesSave: invoke('notes:save'),
    notesDelete: invoke('notes:delete'),
  });
}

// ---- right-button-drag mouse gestures ----
let enabled = false;
ipcRenderer.on('gestures:enabled', (_e, v) => { enabled = !!v; });

let tracking = false, moved = false, suppress = false, deferred = false, allowCtx = false;
let pts = [];

function recognize(points) {
  const MIN = 30;
  const dirs = [];
  let [lx, ly] = points[0];
  for (const [x, y] of points) {
    const dx = x - lx, dy = y - ly;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < MIN) continue;
    const d = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'R' : 'L') : (dy > 0 ? 'D' : 'U');
    if (dirs[dirs.length - 1] !== d) dirs.push(d);
    lx = x; ly = y;
  }
  return dirs.slice(0, 4).join('');
}

window.addEventListener('mousedown', (e) => {
  if (!enabled || e.button !== 2) return;
  tracking = true; moved = false; suppress = false; deferred = false;
  pts = [[e.clientX, e.clientY]];
}, true);

window.addEventListener('mousemove', (e) => {
  if (!tracking) return;
  pts.push([e.clientX, e.clientY]);
  const [sx, sy] = pts[0];
  if (Math.abs(e.clientX - sx) > 8 || Math.abs(e.clientY - sy) > 8) moved = true;
}, true);

window.addEventListener('mouseup', (e) => {
  if (!tracking || e.button !== 2) return;
  tracking = false;
  if (moved) {
    suppress = true;
    const g = recognize(pts);
    if (g) ipcRenderer.send('gesture', g);
  } else if (deferred) {
    // Linux fires "contextmenu" on press; we blocked it, so ask for it back now
    deferred = false;
    allowCtx = true;
    setTimeout(() => { allowCtx = false; }, 500);
    ipcRenderer.send('ctxmenu', e.clientX, e.clientY);
  }
}, true);

window.addEventListener('contextmenu', (e) => {
  if (!enabled || allowCtx) return;
  if (tracking) { e.preventDefault(); deferred = true; return; }
  if (suppress) { e.preventDefault(); suppress = false; }
}, true);

// ---- password capture and autofill (web pages only; nothing is filled until the user picks a login) ----
if (/^https?:$/.test(location.protocol)) {
  const isVisible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const visibleInputs = () => [...document.querySelectorAll('input')].filter(isVisible);
  const pwFields = (root) => [...(root || document).querySelectorAll('input[type=password]')].filter(isVisible);
  const textual = (el) => ['text', 'email', 'tel', ''].includes((el.type || 'text').toLowerCase());
  const userFieldFor = (pw) => {
    const all = visibleInputs();
    for (let k = all.indexOf(pw) - 1; k >= 0; k--) if (textual(all[k])) return all[k];
    return null;
  };

  function capture(scope) {
    const pws = pwFields(scope).filter((p) => p.value);
    let pw = null;
    if (pws.length === 1) pw = pws[0];
    else if (pws.length === 2 && pws[0].value === pws[1].value) pw = pws[0]; // sign-up: password + confirmation
    else if (pws.length === 3) pw = pws[1];                                   // change password: old, new, confirm
    if (!pw) return;
    const u = userFieldFor(pw);
    ipcRenderer.send('pw:captured', { username: u ? u.value : '', password: pw.value });
  }
  window.addEventListener('submit', (e) => capture(e.target instanceof HTMLFormElement ? e.target : document), true);
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target instanceof HTMLInputElement && e.target.type === 'password') capture(e.target.form || document);
  }, true);
  window.addEventListener('click', (e) => {
    const b = e.target && e.target.closest && e.target.closest('button, input[type=submit], [role=button]');
    if (b && pwFields(b.form || document).some((p) => p.value)) capture(b.form || document);
  }, true);

  // tell the browser when this page has a visible password field (it then shows a key icon in the address bar)
  let hadForm = null, formTimer = null;
  const checkForm = () => {
    clearTimeout(formTimer);
    formTimer = setTimeout(() => {
      const has = pwFields(document).length > 0;
      if (has !== hadForm) { hadForm = has; ipcRenderer.send('pw:form', has); }
    }, 300);
  };
  window.addEventListener('DOMContentLoaded', () => {
    checkForm();
    try { new MutationObserver(checkForm).observe(document.documentElement, { childList: true, subtree: true }); } catch { /* ignore */ }
  });
  window.addEventListener('load', checkForm);

  let lastField = null;
  window.addEventListener('focusin', (e) => {
    const el = e.target;
    if (!(el instanceof HTMLInputElement) || !isVisible(el)) return;
    let login = el.type === 'password';
    if (!login && textual(el)) {
      const all = visibleInputs();
      login = all.slice(all.indexOf(el) + 1, all.indexOf(el) + 3).some((n) => n.type === 'password');
    }
    if (!login) return;
    lastField = el;
    const r = el.getBoundingClientRect();
    ipcRenderer.send('pw:focus', { left: r.left, bottom: r.bottom, width: r.width });
  }, true);
  window.addEventListener('focusout', () => ipcRenderer.send('pw:blur'), true);

  const setValue = (el, v) => {
    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  ipcRenderer.on('pw:fill', (_e, d) => {
    if (!d || typeof d.password !== 'string') return;
    const pws = pwFields(document);
    let pw = null;
    if (lastField && lastField.type === 'password') pw = lastField;
    else if (lastField) pw = pws.find((p) => lastField.compareDocumentPosition(p) & Node.DOCUMENT_POSITION_FOLLOWING) || null;
    if (!pw) pw = pws[0] || null;
    let user = lastField && textual(lastField) ? lastField : (pw ? userFieldFor(pw) : null);
    if (user && d.username) setValue(user, d.username);
    if (pw) setValue(pw, d.password);
  });
}
