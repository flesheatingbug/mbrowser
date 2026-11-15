'use strict';
const {
  app, BaseWindow, BrowserWindow, WebContentsView, ipcMain, protocol, session, Menu, MenuItem, shell, nativeTheme, clipboard,
  screen, nativeImage, safeStorage, dialog, net,
} = require('electron');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { createStore } = require('./lib/settings');
const { createBlocker, hostOf } = require('./lib/blocker');
const { PRESETS, findTheme, onColor, isColor } = require('./lib/themes');
const { createVault, originOf } = require('./lib/vault');
const { syncVault, resolveVault } = require('./lib/vaultsync');
const { createProfiles } = require('./lib/profiles');
const { syncSettings } = require('./lib/settingssync');
const { parseBookmarkFile, detectBrowsers, parsePasswords } = require('./lib/importers');
const { spawn } = require('child_process');

// Icons live in "assets". When installed they are also copied next to the program (extraResources) so that
// the operating system, which cannot read inside the app archive, can load the window icon.
const ASSETS = fs.existsSync(path.join(__dirname, 'assets'))
  ? path.join(__dirname, 'assets')
  : path.join(process.resourcesPath, 'assets');
const LOGO = path.join(ASSETS, 'icon.png');
const ICON_KINDS = ['classic', 'color', 'blue', 'white', 'blue-m', 'white-m'];
// the program icon: the classic rounded square, or one of the transparent logo variants
function iconFile(kind, forPreview = false) {
  const k = ICON_KINDS.includes(kind) ? kind : 'classic';
  if (k === 'classic') return path.join(ASSETS, process.platform === 'win32' && !forPreview ? 'icon.ico' : 'icon.png');
  return path.join(ASSETS, 'icons', k + '.png');
}
const appIconPath = () => iconFile(store ? S().appearance.icon : 'white-m');
// read through fs (works inside the app archive) instead of letting the system read the path
const iconImage = (kind) => {
  try { return nativeImage.createFromBuffer(fs.readFileSync(iconFile(kind, true))); } catch { return undefined; }
};
const appIconImage = () => iconImage(store ? S().appearance.icon : 'white-m');

// The taskbar icon on Linux comes from the installed launcher, so update that too when one exists
function updateLauncherIcon(kind) {
  if (process.platform !== 'linux') return 'n/a';
  const home = os.homedir();
  if (!fs.existsSync(path.join(home, '.local', 'share', 'applications', 'mbrowser.desktop'))) return 'no-launcher';
  try {
    for (const size of [512, 128]) {
      const rel = kind === 'classic' ? (size === 512 ? 'icon.png' : 'icon-128.png') : path.join('icons', kind + (size === 512 ? '.png' : '-128.png'));
      const dir = path.join(home, '.local', 'share', 'icons', 'hicolor', size + 'x' + size, 'apps');
      fs.mkdirSync(dir, { recursive: true });
      fs.copyFileSync(path.join(ASSETS, rel), path.join(dir, 'mbrowser.png'));
    }
    const c = spawn('gtk-update-icon-cache', ['-q', path.join(home, '.local', 'share', 'icons', 'hicolor')], { stdio: 'ignore' });
    c.on('error', () => { /* tool not installed */ });
    return 'updated';
  } catch { return 'failed'; }
}

function applyIcon(kind) {
  if (!ICON_KINDS.includes(kind)) return { error: 'unknown icon' };
  const img = iconImage(kind);
  let windowOk = false;
  for (const w of windows) { try { w.win.setIcon(img); windowOk = true; } catch { /* not supported here */ } }
  return { window: windowOk, launcher: updateLauncherIcon(kind) };
}

app.setName('mbrowser');
if (process.platform === 'linux') app.setDesktopName('mbrowser.desktop');

// Factory reset — must run HERE, before Chromium opens any files in userData.
// By the time whenReady() fires, LevelDB files are locked and rmSync fails silently.
if (process.argv.includes('--reset-on-start')) {
  // Delete the whole userData dir (new layout: everything lives here).
  try { fs.rmSync(app.getPath('userData'), { recursive: true, force: true }); } catch { /* ignore */ }
  // Also sweep the parent appData dir for old-layout scattered files/dirs.
  try {
    const ad = app.getPath('appData');
    for (const entry of fs.readdirSync(ad)) {
      if (entry.startsWith('mbrowser-profile-') || entry === 'mbrowser-profiles.json') {
        try { fs.rmSync(path.join(ad, entry), { recursive: true, force: true }); } catch { /* skip */ }
      }
    }
  } catch { /* ignore */ }
}

// The app used to be called "mybrowser": copy that profile to the new folder once so nothing is lost.
try {
  const base = app.getPath('appData');
  const oldDir = path.join(base, 'mybrowser');
  const newDir = path.join(base, 'mbrowser');
  if (!fs.existsSync(newDir) && fs.existsSync(oldDir)) fs.cpSync(oldDir, newDir, { recursive: true });
} catch { /* nothing to migrate */ }

// Profiles: every profile has its own data folder and runs as its own program instance (--profile=<id>).
const profiles = createProfiles(app.getPath('userData'));
const requestedProfile = (process.argv.find((a) => a.startsWith('--profile=')) || '').slice(10);
const profileExplicit = !!(requestedProfile && profiles.get(requestedProfile));
let profileId;
if (profileExplicit) {
  profileId = requestedProfile;
} else if (profiles.getOpenLastProfile()) {
  const last = profiles.getLastProfile();
  profileId = (last && profiles.get(last)) ? last : profiles.getDefaultProfile();
} else {
  profileId = profiles.getDefaultProfile();
}
const unlockedByParent = process.env.MB_UNLOCKED_PROFILE === profileId;
try { app.setPath('userData', profiles.dirFor(profileId)); } catch { /* keep the default folder */ }
const gotLock = app.requestSingleInstanceLock(); // one running instance per profile
if (!gotLock) app.quit();

// Look like plain Chrome to websites: Google sign-in refuses user agents that say "Electron".
if (app.userAgentFallback) {
  app.userAgentFallback = app.userAgentFallback.replace(/\s*Electron\/\S+/, '').replace(/\s*(mybrowser|mbrowser)\/\S+/i, '');
}
// Set the global Chromium UA before anything else starts. This is the lowest-level override
// and affects every request that bypasses the per-session setUserAgent() call (service workers,
// background fetches, auth popups before our CDP mask runs, etc.). ses.setUserAgent() still
// overrides this for user-settable browserId modes (firefox, safari, none).
// The identity chosen in Settings, read now because Chromium switches must be set before it starts.
const EARLY_ID = (() => {
  try { return JSON.parse(fs.readFileSync(path.join(app.getPath('userData'), 'settings.json'), 'utf8')).general?.browserId || 'chrome'; } catch { return 'chrome'; }
})();
{
  const _win = process.platform === 'win32';
  const _uaPlat = _win ? '(Windows NT 10.0; Win64; x64)' : '(X11; Linux x86_64)';
  const _uaChr = process.versions.chrome || '130.0.0.0';
  let _ua = `Mozilla/5.0 ${_uaPlat} AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${_uaChr} Safari/537.36`;
  if (EARLY_ID === 'firefox') _ua = `Mozilla/5.0 (${_win ? 'Windows NT 10.0; Win64; x64' : 'X11; Linux x86_64'}; rv:140.0) Gecko/20100101 Firefox/140.0`;
  else if (EARLY_ID === 'safari') _ua = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15';
  if (EARLY_ID !== 'none') app.commandLine.appendSwitch('user-agent', _ua);
}
// silences the harmless "--ozone-platform=wayland is not compatible with Vulkan" message.
// Firefox and Safari have no "client hints" (navigator.userAgentData, Sec-CH-UA headers), so for those identities the feature is switched off
// instead of faked: a faked one is easy to detect, a missing one is exactly what a real Firefox looks like.
app.commandLine.appendSwitch('disable-features', EARLY_ID === 'firefox' || EARLY_ID === 'safari' ? 'Vulkan,UserAgentClientHint' : 'Vulkan');
// prevent navigator.webdriver from being set (some Chromium builds set it when DevTools attaches)
app.commandLine.appendSwitch('disable-blink-features', 'AutomationControlled');

protocol.registerSchemesAsPrivileged([
  { scheme: 'my', privileges: { standard: true, secure: true, corsEnabled: true, supportFetchAPI: true } },
]);

const START = 'my://start';
const TAB_H = 38, TOOL_H = 50, TRANSLATE_H = 34, SIDE_W = 220, STATUS_H = 26, PANEL_HEAD = 46;
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

let store, blocker, vault;
let resetInProgress = false;
let vaultTimer = null;
let vaultSyncInfo = { state: 'idle', message: 'Not synced yet' };
let settingsSyncNote = '';
let nextId = 1;
const closedTabs = [];
const blockedCounts = new Map(); // webContents id -> count
const chromeIds = new Set();
const certInfo = new Map();  // hostname -> certificate details seen during verification

// ---- multi-window state ----
// Every browser window is a "Win" object. `cur` is the window the code is currently
// operating on; every entry point (IPC, tab events, window events) calls use(w) first.
const windows = new Set();
const chromeOwner = new Map(); // chrome webContents id -> Win
const tabByWc = new Map();     // tab webContents id -> tab
const tabById = new Map();     // tab id -> tab
let cur = null;
const use = (w) => { if (w) cur = w; };
app.on('second-instance', () => {
  const w = [...windows][0];
  if (w && w.win) { try { if (w.win.isMinimized()) w.win.restore(); w.win.focus(); } catch { /* ignore */ } }
});

const S = () => store.get();

// ---------- build / update ----------
const BUILD_NUMBER = 0; // replaced by CI — do not edit
const UPDATE_REPO = 'flesheatingbug/mbrowser';
const RELEASE_URL = `https://github.com/${UPDATE_REPO}/releases`;

let _euInfo = null;      // UpdateInfo from electron-updater when an update is found
let _euDownloaded = false;

function broadcastUpd(ch, data) {
  for (const w of windows) { try { w.statusView.webContents.send(ch, data); } catch { /* ignore */ } }
}

function getAutoUpdater() {
  // app.isPackaged can be false when electron-builder skips ASAR.
  // process.env.APPIMAGE is set by the AppImage runtime on Linux.
  // Either indicator means we're running a packaged build.
  const packaged = app.isPackaged || !!process.env.APPIMAGE;
  if (!packaged) return null;
  try { return require('electron-updater').autoUpdater; } catch { return null; }
}

// Convert electron-updater's UpdateInfo to the info shape the UI expects
function euToInfo(info, downloading = false) {
  const patch = String(info.version || '').split('.')[2] || '';
  const build = /^\d+$/.test(patch) ? parseInt(patch, 10) : info.version;
  return { build, currentBuild: BUILD_NUMBER, version: info.version, releaseUrl: RELEASE_URL, canInstall: true, downloading };
}

function initAutoUpdater() {
  const au = getAutoUpdater();
  if (!au) return;
  au.logger = null;
  au.autoDownload = false;
  au.autoInstallOnAppQuit = false;

  au.on('update-available', (info) => {
    _euInfo = info;
    broadcastUpd('app:update-available', euToInfo(info, true));
    au.downloadUpdate().catch(() => {});
  });
  au.on('download-progress', ({ percent }) => {
    broadcastUpd('app:update-downloading', percent / 100);
  });
  au.on('update-downloaded', (info) => {
    _euInfo = info; _euDownloaded = true;
    broadcastUpd('app:update-ready', euToInfo(info));
  });
  au.on('error', (err) => { console.error('[updater]', err.message); });
}

async function checkForUpdates(manual = false) {
  const au = getAutoUpdater();
  if (!au) {
    if (manual) dialog.showMessageBox({ type: 'info', title: 'mbrowser', message: 'Running from source — updates apply to packaged builds only.' });
    return;
  }
  try {
    const r = await au.checkForUpdates();
    if (manual && !r?.updateInfo) {
      dialog.showMessageBox({ type: 'info', title: 'mbrowser', message: `You are up to date (build ${BUILD_NUMBER}).` });
    }
  } catch (e) {
    if (manual) dialog.showErrorBox('Update check failed', e.message);
  }
}

// ---------- local data files ----------
const file = (name) => path.join(app.getPath('userData'), name);
const load = (name, fallback) => {
  try { return JSON.parse(fs.readFileSync(file(name), 'utf8')); } catch { return fallback; }
};
const save = (name, data) => fs.writeFileSync(file(name), JSON.stringify(data, null, 2));

let bookmarks = [];  // { url, title, added, updated, deleted }
let history = [];    // { url, title, time }
let remoteTabs = [];
let notes = [];     // { id, title, text, updated }
let bookmarkFolders = []; // folder paths such as "Work / Dev" (so empty folders exist too)
let syncState = { bRev: 0, hRev: 0, hPushed: 0 };
let syncStatus = 'Sync not configured';
let syncing = false;
let syncTimer = null;

const isWeb = (u) => /^https?:\/\//i.test(u);
const isMy = (u) => /^my:\/\//i.test(u);

// ---------- theme ----------
function resolveTheme() {
  const a = S().appearance;
  let id = a.themeId;
  if (id === 'system') id = nativeTheme.shouldUseDarkColors ? 'mbrowser' : 'daylight';
  return findTheme(id, a.customThemes) || findTheme('mbrowser');
}

// "system" follows the OS; every other theme tells web pages whether to use their dark or light style
function applyThemeSource() {
  const a = S().appearance;
  nativeTheme.themeSource = a.themeId === 'system' ? 'system' : (resolveTheme().dark ? 'dark' : 'light');
}

function themeVars() {
  const t = resolveTheme();
  const accent = isColor(S().appearance.accentOverride) ? S().appearance.accentOverride : t.accent;
  return {
    bg: t.bg, tab: t.tab, active: t.active, fg: t.fg, muted: t.muted, line: t.line,
    accent, onaccent: onColor(accent), dark: !!t.dark,
  };
}
const themeCss = () => {
  const t = themeVars();
  const vars = Object.entries(t).filter(([k]) => k !== 'dark').map(([k, v]) => `--${k}:${v}`).join(';');
  return `:root{${vars};color-scheme:${t.dark ? 'dark' : 'light'}}`;
};

// ---------- search / url ----------
function engineById(id) {
  const list = S().search.engines;
  return list.find((e) => e.id === id) || list[0];
}
const searchUrl = (engine, q) => engine.url.replace('%s', encodeURIComponent(q));

const isLocalHost = (h) => /^(localhost|.+\.(local|lan|internal|home\.arpa))$/i.test(h)
  || /^(127|10)\.\d+\.\d+\.\d+$/.test(h)
  || /^192\.168\.\d+\.\d+$/.test(h)
  || /^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$/.test(h);

function normalize(input) {
  input = String(input || '').trim();
  if (!input) return S().general.homepage;
  if (/^(about:blank|view-source:)/i.test(input)) return input;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(input)) return input;
  if (/^[a-z0-9-]+:\d{2,5}(\/\S*)?$/i.test(input)) return 'http://' + input; // e.g. fileserver:3000
  if (/^localhost(:\d+)?(\/|$)/i.test(input) || /^[^\s/]+\.[^\s/]{2,}(\/\S*)?$/.test(input)) {
    const host = input.split(/[/?#]/)[0].replace(/:\d+$/, '');
    return (isLocalHost(host) ? 'http://' : 'https://') + input;
  }
  const m = input.match(/^(\S+)\s+(.+)$/);
  if (m) {
    const e = S().search.engines.find((x) => x.nick && x.nick === m[1]);
    if (e) return searchUrl(e, m[2]);
  }
  return searchUrl(engineById(S().search.default), input);
}

// Load what the user typed. If we had to guess "https://", remember it so we can fall back to http.
function go(tab, text) {
  const typed = String(text || '').trim();
  const url = normalize(typed);
  tab.autoHttps = url === 'https://' + typed ? url : null;
  tab.wc.loadURL(url);
}

function newTabUrl() {
  const g = S().general;
  if (g.newTab === 'blank') return 'about:blank';
  if (g.newTab === 'home') return g.homepage;
  return START;
}

// ---------- layout ----------
function layout() {
  if (!cur || !cur.win || !cur.toolbarView) return;
  const { width, height } = cur.win.getContentBounds();
  const zero = { x: 0, y: 0, width: 0, height: 0 };
  const full = cur.win.isFullScreen();
  const bottom = !full && S().appearance.statusBar ? STATUS_H : 0;
  const t = activeTab();
  const tlH = !full && t && t.detectedLang && t.detectedLang !== app.getLocale().split('-')[0].toLowerCase() && !cur.translateDismissed ? TRANSLATE_H : 0;
  const toolH = TOOL_H + tlH;
  let c;
  if (full) {
    cur.toolbarView.setBounds(zero); cur.tabbarView.setBounds(zero);
    c = { x: 0, y: 0, width, height };
  } else if (S().appearance.tabBar === 'left') {
    cur.toolbarView.setBounds({ x: 0, y: 0, width, height: toolH });
    cur.tabbarView.setBounds({ x: 0, y: toolH, width: SIDE_W, height: Math.max(0, height - toolH - bottom) });
    c = { x: SIDE_W, y: toolH, width: Math.max(0, width - SIDE_W), height: Math.max(0, height - toolH - bottom) };
  } else {
    cur.tabbarView.setBounds({ x: 0, y: 0, width, height: TAB_H });
    cur.toolbarView.setBounds({ x: 0, y: TAB_H, width, height: toolH });
    c = { x: 0, y: TAB_H + toolH, width, height: Math.max(0, height - TAB_H - toolH - bottom) };
  }
  // side panel (bookmarks / history / downloads / notes / web panels)
  const noBounds = { x: 0, y: 0, width: 0, height: 0 };
  if (cur.panelOpen && !full) {
    const maxW = Math.max(240, Math.min(600, width - 320));
    const W = Math.max(240, Math.min(maxW, Number(cur.panelWidthLive || S().panel.width) || 320));
    const onLeft = S().panel.side !== 'right';
    const pb = onLeft
      ? { x: c.x, y: c.y, width: Math.min(W, c.width), height: c.height }
      : { x: c.x + Math.max(0, c.width - W), y: c.y, width: Math.min(W, c.width), height: c.height };
    c = onLeft ? { ...c, x: c.x + pb.width, width: Math.max(0, c.width - pb.width) } : { ...c, width: Math.max(0, c.width - pb.width) };
    cur.panelResizer.setBounds(onLeft ? { x: pb.x + pb.width - 3, y: pb.y, width: 7, height: pb.height } : { x: pb.x - 4, y: pb.y, width: 7, height: pb.height });
    const isWebPanel = cur.panelTab.startsWith('web:');
    const isBmPanel = cur.panelTab === 'bookmarks';
    const inlinePanel = !isWebPanel && !isBmPanel;
    cur.panelView.setBounds(inlinePanel ? pb : { ...pb, height: Math.min(PANEL_HEAD, pb.height) });
    if (cur.bmView) {
      cur.bmView.setBounds(isBmPanel
        ? { x: pb.x, y: pb.y + PANEL_HEAD, width: pb.width, height: Math.max(0, pb.height - PANEL_HEAD) }
        : noBounds);
    }
    if (cur.webPanel) {
      cur.webPanel.setBounds(isWebPanel
        ? { x: pb.x, y: pb.y + PANEL_HEAD, width: pb.width, height: Math.max(0, pb.height - PANEL_HEAD) }
        : noBounds);
    }
  } else {
    cur.panelView.setBounds(noBounds);
    cur.panelResizer.setBounds(noBounds);
    if (cur.webPanel) cur.webPanel.setBounds(noBounds);
  }
  cur.statusView.setBounds(bottom ? { x: 0, y: height - STATUS_H, width, height: STATUS_H } : zero);
  cur.contentRect = c;
  const activeT = cur.tabs.get(cur.activeId);
  if (activeT) activeT.view.setBounds(c);
  if (cur.overlayMode) cur.overlay.setBounds(overlayBounds(cur.overlayMode));
}

function securityLevel(url, host) {
  if (!url || /^(my|about|view-source|file|data|devtools|chrome-error):/i.test(url)) return { level: 'internal', label: '' };
  if (/^https:/i.test(url)) {
    if (S().privacy.certExceptions.includes(host)) return { level: 'warning', label: 'Certificate not trusted' };
    return { level: 'secure', label: '' };
  }
  if (/^http:/i.test(url)) return { level: 'insecure', label: 'Not secure' };
  return { level: 'internal', label: '' };
}

// ---------- state sent to the chrome views ----------
function stateFor() {
  const t = cur.tabs.get(cur.activeId);
  const wc = t && t.wc;
  const url = wc ? wc.getURL() : '';
  const host = hostOf(url);
  const allow = S().privacy.allowSites;
  return {
    activeId: cur.activeId,
    url,
    canBack: wc ? wc.navigationHistory.canGoBack() : false,
    canForward: wc ? wc.navigationHistory.canGoForward() : false,
    bookmarked: bookmarks.some((b) => b.url === url && !b.deleted && !b.recycleBin),
    tabBar: S().appearance.tabBar,
    security: securityLevel(url, host),
    frameless: !!cur.frameless,
    logins: (() => {
      if (!t || !vault || !vault.exists() || !isWeb(url)) return null;
      const origin = originOf(url);
      return { form: !!t.loginForm, count: vault.unlocked() && origin ? vault.findByOrigin(origin).length : null, locked: !vault.unlocked() };
    })(),
    profile: { ...(profiles.get(profileId) || { id: profileId, name: 'Default', color: '#5b8cff' }), count: profiles.list().length },
    maximized: cur.win.isMaximized(),
    zoom: wc ? Math.round(wc.getZoomFactor() * 100) : 100,
    statusText: t ? t.status || '' : '',
    hover: t ? t.hover || '' : '',
    loading: wc ? wc.isLoading() : false,
    tabCount: cur.order.length,
    wheelScrollTabs: S().tabs.wheelScrolls !== false,
    clickTabScrollsTop: S().tabs.clickTabScrollsTop !== false,
    toolbarButtons: S().appearance.toolbar || {},
    detectedLang: (t && t.detectedLang) || '',
    systemLang: app.getLocale().split('-')[0].toLowerCase(),
    translateBarVisible: !!(t && t.detectedLang && t.detectedLang !== app.getLocale().split('-')[0].toLowerCase() && !cur.translateDismissed),
    theme: themeVars(),
    blocked: wc ? blockedCounts.get(wc.id) || 0 : 0,
    blockerOn: S().privacy.blockTrackers,
    siteAllowed: !!host && allow.some((h) => host === h || host.endsWith('.' + h)),
    private: !!(t && t.private),
    downloads: downloads.filter((d) => !d.winUid || d.winUid === cur.uid).slice(0, 5).map((d) => ({ id: d.id, name: d.name, url: d.url, path: d.path, state: d.state, received: d.received, total: d.total })),
    tabs: cur.order.map((id) => {
      const x = cur.tabs.get(id);
      return {
        id,
        title: x.deferredUrl ? (hostOf(x.deferredUrl) || x.deferredUrl) : (x.wc.getTitle() || 'New Tab'),
        favicon: x.favicon,
        pinned: x.pinned,
        private: x.private,
        audible: x.wc.isCurrentlyAudible(),
        muted: x.wc.audioMuted,
        loading: x.deferredUrl ? false : x.wc.isLoading(),
      };
    }),
  };
}

function pushState() {
  if (!cur || !cur.toolbarView) return;
  const s = stateFor();
  for (const v of [cur.toolbarView, cur.tabbarView, cur.statusView, cur.panelResizer, cur.overlay]) {
    if (v && !v.webContents.isDestroyed()) v.webContents.send('state', s);
  }
  scheduleSessionSave();
}

let pushTimer = null;
function pushSoon() {
  if (pushTimer) return;
  pushTimer = setTimeout(() => { pushTimer = null; pushAll(); }, 250);
}

// refresh the chrome of every window (optionally re-running layout first)
function pushAll(relayout = false) {
  const keep = cur;
  for (const w of windows) { use(w); if (relayout) layout(); pushState(); }
  use(keep);
}

let sessionTimer = null;
function scheduleSessionSave() {
  clearTimeout(sessionTimer);
  sessionTimer = setTimeout(() => {
    const list = [...windows].flatMap((w) => w.order.map((id) => w.tabs.get(id)))
      .filter((t) => t && !t.private && /^https?:\/\//i.test(t.wc.getURL()))
      .map((t) => ({ url: t.wc.getURL(), pinned: t.pinned }));
    try { save('session.json', { tabs: list }); } catch { /* ignore */ }
  }, 1500);
}

// ---------- auth-popup allowlist ----------
// For sign-in popups from these domains: skip our preload + CDP debugger.
// A foreign preload and an attached debugger are detectable signals; the
// session-level Chrome UA (set in setupSession) is enough for clean auth.
const AUTH_POPUP_HOSTS = new Set([
  'accounts.google.com', 'accounts.youtube.com', 'myaccount.google.com',
  'login.microsoftonline.com', 'login.live.com', 'login.microsoft.com',
  'appleid.apple.com',
  'github.com', 'gitlab.com',
  'auth.openai.com', 'chat.openai.com',
  'accounts.firefox.com',
]);
function isAuthHost(url) {
  try { return AUTH_POPUP_HOSTS.has(new URL(url).hostname); } catch { return false; }
}

// ---------- tabs ----------
function maskUserAgentData(wc) {
  // Override navigator.userAgentData via CDP so Google sign-in doesn't see "Electron" in the brand list.
  // ses.setUserAgent() patches navigator.userAgent but not the Client Hints API.
  if (S().general.browserId !== 'chrome') return;   // only the Chrome identity is faked; the others are left exactly as they are
  // Don't bail on attach failure (e.g. debugger already attached by DevTools) — just send the command anyway.
  try { wc.debugger.attach('1.3'); } catch { /* already attached is fine */ }
  const plat = process.platform === 'win32' ? 'Windows' : 'Linux';
  const ua = `Mozilla/5.0 ${plat === 'Windows' ? '(Windows NT 10.0; Win64; x64)' : '(X11; Linux x86_64)'} AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VER} Safari/537.36`;
  const platVer = chromePlatformVersion();
  wc.debugger.sendCommand('Emulation.setUserAgentOverride', {
    userAgent: ua,
    userAgentMetadata: {
      brands: [
        { brand: 'Not?A_Brand', version: '8' },
        { brand: 'Chromium', version: CHROME_MAJOR },
        { brand: 'Google Chrome', version: CHROME_MAJOR },
      ],
      fullVersionList: [
        { brand: 'Not?A_Brand', version: '8.0.0.0' },
        { brand: 'Chromium', version: CHROME_VER },
        { brand: 'Google Chrome', version: CHROME_VER },
      ],
      platform: plat,
      platformVersion: platVer,
      architecture: 'x86',
      model: '',
      mobile: false,
    },
  }).catch(() => {});
  // Inject window.chrome shim before any page scripts. Real Chrome exposes
  // window.chrome.runtime (a stub) even in non-extension tabs; Google sign-in
  // checks for its presence. Page.addScriptToEvaluateOnNewDocument runs in
  // the main world before the page's own JavaScript.
  wc.debugger.sendCommand('Page.addScriptToEvaluateOnNewDocument', {
    source: `(function(){
      var c = window.chrome || (window.chrome = {});
      if (!c.runtime) c.runtime = {
        id: undefined,
        connect: function(){},
        sendMessage: function(){},
        onMessage: { addListener: function(){}, removeListener: function(){}, hasListener: function(){ return false; } },
        onConnect: { addListener: function(){}, removeListener: function(){} },
      };
      if (!c.loadTimes) c.loadTimes = function(){ return {
        requestTime: performance.timing ? performance.timing.navigationStart/1000 : 0,
        startLoadTime: performance.timing ? performance.timing.navigationStart/1000 : 0,
        commitLoadTime: performance.timing ? performance.timing.responseEnd/1000 : 0,
        finishDocumentLoadTime: 0, finishLoadTime: 0,
        firstPaintTime: 0, firstPaintAfterLoadTime: 0,
        navigationType: 'Other', wasFetchedViaSpdy: false,
        wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2',
        wasAlternateProtocolAvailable: false, connectionInfo: 'h2',
      }; };
      if (!c.csi) c.csi = function(){ return {
        startE: performance.timing ? performance.timing.navigationStart : 0,
        onloadT: performance.timing ? performance.timing.loadEventEnd : 0,
        pageT: performance.now(), tran: 15,
      }; };
      if (!c.app) c.app = {
        isInstalled: false,
        getDetails: function(){ return null; },
        getIsInstalled: function(){ return false; },
        installState: function(cb){ if (cb) cb('disabled'); },
        runningState: function(){ return 'cannot_run'; },
      };
      if (!c.webstore) c.webstore = {
        install: function(url, success, failure){ if (failure) failure('User cancelled install'); },
        onInstallStageChanged: { addListener: function(){}, removeListener: function(){}, hasListener: function(){ return false; } },
        onDownloadProgress: { addListener: function(){}, removeListener: function(){}, hasListener: function(){ return false; } },
      };
    })();`,
  }).catch(() => {});
}

function createView(isPrivate) {
  const v = new WebContentsView({
    webPreferences: {
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
      nodeIntegrationInSubFrames: true,
      webviewTag: true,
      preload: path.join(__dirname, 'page-preload.js'),
      partition: isPrivate ? 'private' : undefined,
    },
  });
  maskUserAgentData(v.webContents);
  return v;
}

function insertOrder(id, tab, opts) {
  const pinnedCount = cur.order.filter((i) => cur.tabs.get(i).pinned).length;
  let idx;
  if (tab.pinned) {
    idx = pinnedCount;
  } else {
    const afterId = opts.after != null ? opts.after
      : (S().tabs.newTabPosition === 'afterCurrent' ? activeId : null);
    const ai = afterId != null ? cur.order.indexOf(afterId) : -1;
    idx = ai >= 0 ? Math.max(ai + 1, pinnedCount) : cur.order.length;
  }
  cur.order.splice(idx, 0, id);
}

function newTab(target, opts = {}) {
  const id = nextId++;
  const view = createView(!!opts.private);
  const tab = {
    id, view, wc: view.webContents, pinned: !!opts.pinned, private: !!opts.private, favicon: '', owner: cur,
  };
  cur.tabs.set(id, tab);
  tabById.set(id, tab);
  tabByWc.set(tab.wc.id, tab);
  insertOrder(id, tab, opts);
  wire(tab);
  const url = target === undefined ? newTabUrl() : normalize(target);
  if (opts.background && S().tabs.lazyBackground !== false) {
    tab.deferredUrl = url;
    pushState();
  } else {
    tab.wc.loadURL(url);
    if (opts.background) pushState(); else switchTab(id);
    if (!opts.background && (target === undefined || target === START) && S().tabs.newTabFocus === 'address') {
      tab.focusUrlOnLoad = true;
      focusUrl();
    }
  }
  return id;
}

function wire(tab) {
  const wc = tab.wc;
  // every event runs in the context of the window that currently owns the tab
  const h = (fn) => (...a) => { use(tab.owner); fn(...a); };
  wc.on('page-title-updated', h(() => pushState()));
  wc.on('did-start-loading', h(() => { tab.status = 'Connecting…'; tab.detectedLang = ''; cur.translateDismissed = false; pushState(); }));
  wc.on('did-stop-loading', h(() => { tab.status = ''; pushState(); maybeThumb(tab); }));
  wc.on('did-finish-load', h(() => {
    if (tab.focusUrlOnLoad) {
      tab.focusUrlOnLoad = false;
      if (cur.activeId === tab.id && !cur.overlayMode) focusUrl();
    }
    // cosmetic ad hiding (element-hiding rules from filter lists)
    if (S().privacy.blockTrackers && isWeb(wc.getURL()) && !tab.private) {
      const pageHost = hostOf(wc.getURL());
      if (pageHost && !((S().privacy.allowSites || []).some((h) => pageHost === h || pageHost.endsWith('.' + h)))) {
        const css = blocker.cosmeticCss(pageHost);
        if (css) wc.insertCSS(css).catch(() => { /* ignore */ });
      }
    }
  }));
  wc.on('update-target-url', h((_e, url) => { tab.hover = url; pushState(); }));
  wc.on('did-navigate-in-page', h(() => pushState()));
  wc.on('audio-state-changed', h(() => pushState()));
  wc.on('page-favicon-updated', h((_e, favs) => { tab.favicon = favs[0] || ''; pushState(); }));
  wc.on('did-start-navigation', (details) => {
    if (details && details.isMainFrame && !details.isSameDocument) {
      blockedCounts.set(wc.id, 0);
      tab.loginForm = false;
      tab.status = 'Connecting to ' + (hostOf(details.url) || 'site') + '…';
    }
  });
  wc.on('did-navigate', h((_e, url) => {
    if (tab.owner.pwPending && !tab.owner.pwPending.shown && Date.now() - tab.owner.pwPending.at < 20000) maybeShowPwSave(tab.owner);
    tab.status = 'Loading ' + (hostOf(url) || 'page') + '…';
    tab.favicon = isWeb(url) ? tab.favicon : '';
    if (!tab.private && isWeb(url)) {
      history.push({ url, title: wc.getTitle(), time: Date.now() });
      if (history.length > 5000) history = history.slice(-5000);
      save('history.json', history);
    }
    pushState();
  }));
  wc.on('did-fail-load', h((_e, code, desc, url, isMain) => {
    if (!isMain || code === -3 || /^my:/i.test(url)) return; // -3 = navigation superseded
    // typed "192.168.1.50:3003" and we guessed https, but the server only speaks http
    if (tab.autoHttps === url && (code === -107 || code === -113)) {
      tab.autoHttps = null;
      wc.loadURL(url.replace(/^https:/i, 'http:'));
      return;
    }
    wc.loadURL('my://error?' + new URLSearchParams({ url, code: String(code), desc: desc || '' }));
  }));
  wc.on('dom-ready', () => {
    wc.send('gestures:enabled', S().mouse.gestures);
    use(tab.owner);
    tab.status = 'Loading resources…';
    pushState();
    if (tab.focusUrlOnLoad && cur.activeId === tab.id && !cur.overlayMode) focusUrl();
    if (!tab.zoomed && S().pages.defaultZoom !== 100) {
      tab.zoomed = true;
      wc.setZoomFactor(S().pages.defaultZoom / 100);
    }
  });
  // Track whether the most-recently allowed popup is an auth domain.
  // setWindowOpenHandler and did-create-window fire synchronously in sequence
  // for the same popup, so a simple flag is reliable here.
  let lastPopupIsAuth = false;
  wc.setWindowOpenHandler(({ url, disposition, features }) => {
    use(tab.owner);
    // Sign-in flows (Google, etc.) open a real popup that talks back to the page that opened it.
    // That only works as an actual window with an opener, so allow those; links become tabs.
    if (disposition === 'new-window' && features) {
      lastPopupIsAuth = isAuthHost(url);
      return { action: 'allow', overrideBrowserWindowOptions: {
        autoHideMenuBar: true, title: 'Sign in',
        // Auth-domain popups get no preload and no CDP (both are detectable by
        // Google's trusted-platform check). Session UA is already Chrome.
        webPreferences: lastPopupIsAuth
          ? { sandbox: true, contextIsolation: true }
          : { sandbox: true, contextIsolation: true, preload: path.join(__dirname, 'page-preload.js') },
      } };
    }
    newTab(url, { after: tab.id, private: tab.private, background: disposition === 'background-tab' });
    return { action: 'deny' };
  });
  wc.on('did-create-window', (newWin) => {
    const isAuth = lastPopupIsAuth;
    lastPopupIsAuth = false;
    // Auth popups: session UA is already Chrome — no CDP needed, and attaching a
    // debugger is itself a detectable signal. Mask everything else as before.
    if (!isAuth) maskUserAgentData(newWin.webContents);
  });
  wc.on('context-menu', h((_e, params) => {
    tab.ctxParams = params;
    if (!S().mouse.gestures) {
      showContextMenu(tab, params);
      tab.ctxParams = null;
    } else {
      // Gestures enabled: ctxmenu IPC should arrive from the renderer on mouseup.
      // If it doesn't (Wayland timing: contextmenu can fire after mouseup, leaving
      // the preload deferred=false before our fix, or IPC races), show after 250ms.
      clearTimeout(tab._ctxFallback);
      tab._ctxFallback = setTimeout(() => {
        tab._ctxFallback = null;
        if (tab.ctxParams === params) { showContextMenu(tab, params); tab.ctxParams = null; }
      }, 250);
    }
  }));
  wc.on('found-in-page', h((_e, result) => {
    if (cur.overlayMode === 'find' && tab.id === cur.activeId) cur.overlay.webContents.send('find:result', result);
  }));
  wc.on('before-input-event', (event, input) => { use(tab.owner); handleShortcut(event, input); });
}

function switchTab(id) {
  const next = cur.tabs.get(id);
  if (!next) return;
  if (next.deferredUrl) {
    const u = next.deferredUrl;
    delete next.deferredUrl;
    next.wc.loadURL(u);
  }
  if (cur.overlayMode && cur.overlayMode !== 'suggest') hideOverlay(false);
  const prev = cur.tabs.get(cur.activeId);
  if (prev && prev !== next) cur.win.contentView.removeChildView(prev.view);
  const changed = cur.activeId !== id;
  cur.activeId = id;
  if (changed || !cur.win.contentView.children.includes(next.view)) cur.win.contentView.addChildView(next.view);
  layout();
  next.wc.focus();
  pushState();
}

function closeTab(id) {
  const t = cur.tabs.get(id);
  if (!t) return;
  const wcId = t.wc.id;
  const idx = cur.order.indexOf(id);
  if (!t.private) {
    const u = t.wc.getURL();
    if (u && u !== 'about:blank') {
      closedTabs.push({ url: u, title: t.wc.getTitle(), pinned: t.pinned });
      if (closedTabs.length > 25) closedTabs.shift();
    }
  }
  if (id === cur.activeId) cur.win.contentView.removeChildView(t.view);
  t.wc.close();
  cur.tabs.delete(id);
  cur.order.splice(idx, 1);
  tabById.delete(id);
  tabByWc.delete(wcId);
  blockedCounts.delete(wcId);
  if (t.private) {
    const anyPrivate = [...windows].some((w) => [...w.tabs.values()].some((x) => x.private));
    if (!anyPrivate) {
      session.fromPartition('private').clearStorageData().catch(() => {});
    }
  }
  if (cur.order.length === 0) {
    cur.activeId = null;
    if (windows.size > 1) { cur.win.close(); return; } // last tab of an extra window closes it
    newTab();
    return;
  }
  if (id === cur.activeId) {
    cur.activeId = null;
    switchTab(cur.order[Math.min(idx, cur.order.length - 1)]);
  } else {
    pushState();
  }
}

function reopenTab() {
  const c = closedTabs.pop();
  if (c) newTab(c.url, { pinned: c.pinned });
}

function cycleTab(d) {
  if (cur.order.length < 2) return;
  const i = cur.order.indexOf(cur.activeId);
  switchTab(cur.order[(i + d + cur.order.length) % cur.order.length]);
}

function duplicateTab(id) {
  const t = cur.tabs.get(id);
  if (t) newTab(t.wc.getURL(), { after: id, private: t.private });
}

function togglePin(id) {
  const t = cur.tabs.get(id);
  if (!t) return;
  t.pinned = !t.pinned;
  cur.order = cur.order.filter((i) => i !== id);
  insertOrder(id, t, { after: null });
  pushState();
}

function toggleMute(id) {
  const t = cur.tabs.get(id);
  if (!t) return;
  t.wc.setAudioMuted(!t.wc.audioMuted);
  pushState();
}

function closeOthers(id) {
  for (const i of [...cur.order]) {
    const t = cur.tabs.get(i);
    if (i !== id && t && !t.pinned) closeTab(i);
  }
}

function openInternal(host) {
  const url = 'my://' + host;
  const existing = cur.order.find((id) => cur.tabs.get(id).wc.getURL().startsWith(url));
  if (existing != null) switchTab(existing); else newTab(url);
}

const activeTab = () => cur.tabs.get(cur.activeId);
const activeWc = () => (activeTab() ? activeTab().wc : null);

// ---------- bookmarks ----------
function toggleBookmark() {
  const wc = activeWc();
  if (!wc) return;
  const url = wc.getURL();
  if (!isWeb(url)) return;
  // Toggle: close the popup if already open, otherwise open it
  if (cur.overlayMode === 'bookmark') { hideOverlay(); return; }
  const b = bookmarks.find((x) => x.url === url && !x.deleted && !x.recycleBin);
  const allFolders = [...new Set([
    ...bookmarks.filter((x) => !x.deleted && !x.recycleBin && x.folder).map((x) => x.folder),
    ...bookmarkFolders,
  ])].sort();
  const key = thumbKey(url);
  const inSpeedDial = (S().startPage.groups || []).some((g) => g.dials.some((d) => thumbKey(d.url) === key));
  showOverlay('bookmark', {
    url, title: wc.getTitle(),
    bookmarked: !!b, folder: b ? (b.folder || '') : '',
    folders: allFolders, inSpeedDial,
  });
}

// ---------- translate ----------
function translateTab(tab) {
  const wc = tab ? tab.wc : activeWc();
  if (!wc) return;
  let url;
  try { url = wc.getURL(); } catch { return; }
  if (!isWeb(url)) return;
  const tl = app.getLocale().split('-')[0] || 'en';
  wc.loadURL('https://translate.google.com/translate?sl=auto&tl=' + encodeURIComponent(tl) + '&u=' + encodeURIComponent(url));
}

// ---------- page actions ----------
function zoom(delta) {
  const wc = activeWc();
  if (!wc) return;
  const f = delta === 0 ? S().pages.defaultZoom / 100
    : Math.min(5, Math.max(0.25, wc.getZoomFactor() + delta));
  wc.setZoomFactor(f);
  pushState();
}

async function screenshot() {
  const wc = activeWc();
  if (!wc) return;
  const img = await wc.capturePage();
  const dir = downloadDir();
  fs.mkdirSync(dir, { recursive: true });
  const p = uniquePath(dir, `screenshot-${new Date().toISOString().replace(/[:.]/g, '-')}.png`);
  fs.writeFileSync(p, img.toPNG());
  downloads.unshift({
    id: ++dlCounter, name: path.basename(p), url: wc.getURL(), total: 0, received: 0, state: 'completed', path: p,
  });
}

function toggleSiteBlocking() {
  const wc = activeWc();
  if (!wc) return;
  const host = hostOf(wc.getURL());
  if (!host) return;
  const list = S().privacy.allowSites.slice();
  const i = list.indexOf(host);
  if (i >= 0) list.splice(i, 1); else list.push(host);
  applySetting('privacy.allowSites', list);
  wc.reload();
}

function focusUrl() {
  const tv = cur.toolbarView;
  tv.webContents.focus();
  tv.webContents.send('focus-url');
  // once more shortly after, in case the page grabbed focus while it was starting to load
  setTimeout(() => { try { if (!tv.webContents.isDestroyed()) tv.webContents.send('focus-url'); } catch { /* closed */ } }, 120);
}

// ---------- commands & shortcuts ----------
const COMMANDS = [
  { id: 'tab-new', label: 'New Tab', keys: ['Ctrl+T'], run: () => newTab() },
  { id: 'tab-new-private', label: 'New Private Tab', keys: ['Ctrl+Shift+N'], run: () => newTab(undefined, { private: true }) },
  { id: 'window-new', label: 'New Window', keys: ['Ctrl+N'], run: () => createWindow() },
  { id: 'window-new-private', label: 'New Private Window', keys: ['Ctrl+Shift+P'], run: () => createWindow({ private: true }) },
  { id: 'tab-new-window', label: 'Move Tab to New Window', keys: [], run: () => { const t = cur.tabs.get(cur.activeId); if (t) tearOffTab(t); } },
  { id: 'tab-close', label: 'Close Tab', keys: ['Ctrl+W'], run: () => closeTab(cur.activeId) },
  { id: 'tab-reopen', label: 'Reopen Closed Tab', keys: ['Ctrl+Shift+T'], run: reopenTab },
  { id: 'tab-next', label: 'Next Tab', keys: ['Ctrl+Tab'], run: () => cycleTab(1) },
  { id: 'tab-prev', label: 'Previous Tab', keys: ['Ctrl+Shift+Tab'], run: () => cycleTab(-1) },
  { id: 'tab-duplicate', label: 'Duplicate Tab', keys: [], run: () => duplicateTab(cur.activeId) },
  { id: 'tab-pin', label: 'Pin / Unpin Tab', keys: [], run: () => togglePin(cur.activeId) },
  { id: 'tab-mute', label: 'Mute / Unmute Tab', keys: [], run: () => toggleMute(cur.activeId) },
  { id: 'tab-close-others', label: 'Close Other Tabs', keys: [], run: () => closeOthers(cur.activeId) },
  { id: 'nav-back', label: 'Back', keys: ['Alt+Left'], run: () => activeWc()?.navigationHistory.goBack() },
  { id: 'nav-forward', label: 'Forward', keys: ['Alt+Right'], run: () => activeWc()?.navigationHistory.goForward() },
  { id: 'nav-reload', label: 'Reload', keys: ['Ctrl+R', 'F5'], run: () => activeWc()?.reload() },
  { id: 'nav-reload-hard', label: 'Reload (Ignore Cache)', keys: ['Ctrl+Shift+R', 'Ctrl+F5'], run: () => activeWc()?.reloadIgnoringCache() },
  { id: 'nav-home', label: 'Go to Homepage', keys: ['Alt+Home'], run: () => activeWc()?.loadURL(normalize(S().general.homepage)) },
  { id: 'nav-focus-url', label: 'Focus Address Bar', keys: ['Ctrl+L', 'F6'], run: focusUrl },
  { id: 'page-find', label: 'Find in Page', keys: ['Ctrl+F'], run: () => showOverlay('find') },
  { id: 'page-zoom-in', label: 'Zoom In', keys: ['Ctrl+='], run: () => zoom(0.1) },
  { id: 'page-zoom-out', label: 'Zoom Out', keys: ['Ctrl+-'], run: () => zoom(-0.1) },
  { id: 'page-zoom-reset', label: 'Reset Zoom', keys: ['Ctrl+0'], run: () => zoom(0) },
  { id: 'page-fullscreen', label: 'Toggle Fullscreen', keys: ['F11'], run: () => { cur.win.setFullScreen(!cur.win.isFullScreen()); } },
  { id: 'page-print', label: 'Print', keys: ['Ctrl+P'], run: () => activeWc()?.print() },
  { id: 'page-translate', label: 'Translate Page', keys: [], run: () => translateTab(null) },
  { id: 'page-source', label: 'View Page Source', keys: ['Ctrl+U'], run: () => { const u = activeWc()?.getURL(); if (u && isWeb(u)) newTab('view-source:' + u, { after: cur.activeId }); } },
  { id: 'page-devtools', label: 'Developer Tools', keys: ['F12'], run: () => activeWc()?.toggleDevTools() },
  { id: 'page-screenshot', label: 'Capture Page Screenshot', keys: ['Ctrl+Shift+S'], run: screenshot },
  { id: 'page-bookmark', label: 'Bookmark / Unbookmark Page', keys: ['Ctrl+D'], run: toggleBookmark },
  { id: 'page-add-speeddial', label: 'Add Page to Speed Dial', keys: [], run: addCurrentToSpeedDial },
  { id: 'page-copy-url', label: 'Copy Page Address', keys: [], run: () => { const u = activeWc()?.getURL(); if (u) clipboard.writeText(u); } },
  { id: 'app-quick-commands', label: 'Quick Commands', keys: ['F2', 'Ctrl+E'], run: () => showOverlay('palette') },
  { id: 'app-bookmarks', label: 'Bookmark Manager', keys: ['Ctrl+Shift+O'], run: () => openInternal('bookmarks') },
  { id: 'app-library', label: 'Synced Tabs & Library', keys: [], run: () => openInternal('library') },
  { id: 'app-history', label: 'History', keys: ['Ctrl+H'], run: () => openInternal('history') },
  { id: 'app-downloads', label: 'Downloads', keys: ['Ctrl+J'], run: () => openInternal('downloads') },
  { id: 'app-settings', label: 'Settings', keys: ['Ctrl+,'], run: () => openInternal('settings') },
  { id: 'app-start', label: 'Start Page', keys: [], run: () => openInternal('start') },
  { id: 'toggle-blocker', label: 'Toggle Tracker Blocking for This Site', keys: [], run: toggleSiteBlocking },
  { id: 'panel-toggle', label: 'Show / Hide Side Panel', keys: ['F4'], run: togglePanel },
  { id: 'panel-bookmarks', label: 'Panel: Bookmarks', keys: [], run: () => setPanel(true, 'bookmarks') },
  { id: 'panel-history', label: 'Panel: History', keys: [], run: () => setPanel(true, 'history') },
  { id: 'panel-downloads', label: 'Panel: Downloads', keys: [], run: () => setPanel(true, 'downloads') },
  { id: 'panel-notes', label: 'Panel: Notes', keys: [], run: () => setPanel(true, 'notes') },
  { id: 'app-notes', label: 'Notes', keys: [], run: () => openInternal('notes') },
  { id: 'app-import', label: 'Import Bookmarks & Passwords', keys: [], run: () => openInternal('import') },
  { id: 'app-profiles', label: 'Profiles', keys: [], run: () => openInternal('profiles') },
  { id: 'app-passwords', label: 'Password Manager', keys: [], run: () => openInternal('passwords') },
  { id: 'app-server-admin', label: 'Server Admin', keys: [], run: () => openInternal('server-admin') },
  { id: 'sync-now', label: 'Sync Now', keys: [], run: syncAll },
];

const GESTURES = {
  L: 'nav-back', R: 'nav-forward', D: 'tab-new', DR: 'tab-close', UD: 'nav-reload',
  UL: 'tab-prev', UR: 'tab-next', DL: 'tab-reopen',
};

const shortcutMap = new Map();
function effectiveKeys(c) {
  const ov = S().keyboard.overrides;
  if (Object.prototype.hasOwnProperty.call(ov, c.id)) return ov[c.id] ? [ov[c.id]] : [];
  return c.keys;
}
function rebuildShortcuts() {
  shortcutMap.clear();
  for (const c of COMMANDS) for (const k of effectiveKeys(c)) shortcutMap.set(k, c.id);
}

function accelOf(input) {
  const k = input.key;
  if (['Control', 'Shift', 'Alt', 'Meta'].includes(k)) return null;
  const parts = [];
  if (input.control) parts.push('Ctrl');
  if (input.alt) parts.push('Alt');
  if (input.shift) parts.push('Shift');
  if (input.meta) parts.push('Meta');
  let name = k.length === 1 ? k.toUpperCase() : k.replace('Arrow', '');
  if (k === ' ') name = 'Space';
  parts.push(name);
  return parts.join('+');
}

function runCommand(id) {
  const c = COMMANDS.find((x) => x.id === id);
  if (c) c.run();
}

function handleShortcut(event, input) {
  if (input.type !== 'keyDown') return;
  if (!(input.control || input.alt || input.meta || /^F\d+$/.test(input.key))) return;
  const a = accelOf(input);
  const id = a && shortcutMap.get(a);
  if (!id) return;
  event.preventDefault();
  runCommand(id);
}

// ---------- overlay (quick commands, find bar) ----------
function overlayBounds(mode) {
  const { width, height } = cur.win.getContentBounds();
  if (mode === 'find') {
    return { x: Math.max(0, width - 400), y: cur.contentRect.y + 6, width: 390, height: 48 };
  }
  if (mode === 'suggest') {
    return { x: 150, y: cur.contentRect.y - 2, width: Math.max(200, Math.min(760, width - 190)), height: 40 };
  }
  if (mode === 'card' || mode === 'addr') {
    const d = cur.overlayData || {};
    const rows = (d.items ? d.items.length : 0) + 1;
    const x = Math.max(0, Math.min(width - 310, Math.round(cur.contentRect.x + (d.x || 0))));
    return { x, y: Math.round(cur.contentRect.y + (d.y || 0)), width: 300, height: rows * 40 + 12 };
  }
  if (mode === 'pw') {
    const d = cur.overlayData || {};
    if (d.view === 'fill') {
      const rows = (d.items ? d.items.length : 0) + (d.locked ? 1 : 0) + (d.empty ? 1 : 0) + 1;
      const x = Math.max(0, Math.min(width - 330, Math.round(cur.contentRect.x + (d.x || 0))));
      return { x, y: Math.round(cur.contentRect.y + (d.y || 0)), width: 320, height: rows * 40 + 12 };
    }
    const h = d.view === 'save' ? 196 + (d.needsCreate ? 100 : d.needsUnlock ? 56 : 0) : 186;
    return { x: Math.max(0, width - 392), y: cur.contentRect.y, width: 384, height: h };
  }
  if (mode === 'tablist') {
    const w = Math.min(420, width - 16);
    return { x: width - w - 8, y: S().appearance.tabBar === 'left' ? TOOL_H : TAB_H, width: w, height: Math.min(540, Math.max(160, height - 90)) };
  }
  if (mode === 'security') {
    return { x: 70, y: cur.contentRect.y, width: Math.min(400, width - 90), height: 380 };
  }
  if (mode === 'bookmark') {
    return { x: Math.max(0, width - 332), y: cur.contentRect.y, width: 320, height: 220 };
  }
  const w = Math.min(680, width - 40);
  return { x: Math.round((width - w) / 2), y: 60, width: w, height: Math.min(470, Math.max(120, height - 90)) };
}

function showOverlay(mode, data = {}) {
  if (!activeTab()) return;
  cur.overlayMode = mode;
  cur.overlayData = data;
  cur.win.contentView.addChildView(cur.overlay); // (re)adding moves it to the top
  cur.overlay.setBounds(overlayBounds(mode));
  cur.overlay.webContents.send('overlay:open', mode, data);
  // suggestions and the password fill list must not steal focus from the field being typed in
  if (mode !== 'suggest' && !(mode === 'pw' && data && data.view === 'fill')) cur.overlay.webContents.focus();
}

function hideOverlay(refocus = true) {
  if (!cur.overlayMode) return;
  if (cur.overlayMode === 'find') activeWc()?.stopFindInPage('clearSelection');
  cur.overlayMode = null;
  cur.win.contentView.removeChildView(cur.overlay);
  if (refocus) activeWc()?.focus();
}

// ---------- address bar suggestions ----------
function suggestItems(q) {
  const text = q.trim();
  if (!text) return [];
  const terms = text.toLowerCase().split(/\s+/);
  const match = (...f) => { const s = f.join(' ').toLowerCase(); return terms.every((t) => s.includes(t)); };
  const out = [];
  const seen = new Set();
  const push = (it) => {
    const k = it.kind === 'search' ? 'q:' + it.text : it.url;
    if (seen.has(k)) return;
    seen.add(k);
    out.push(it);
  };

  const urlish = /^[a-z][a-z0-9+.-]*:\/\//i.test(text) || /^[^\s/]+\.[^\s/]{2,}(\/\S*)?$/.test(text)
    || /^localhost(:\d+)?/i.test(text) || /^[a-z0-9-]+:\d{2,5}(\/|$)/i.test(text);
  const nm = text.match(/^(\S+)\s+(.+)$/);
  const eng = nm && S().search.engines.find((e) => e.nick && e.nick === nm[1]);
  if (urlish) push({ kind: 'search', text, label: normalize(text), detail: 'Go to address', icon: '→' });
  else if (eng) push({ kind: 'search', text, label: `Search ${eng.name} for “${nm[2]}”`, detail: eng.name, icon: '🔍' });
  else push({ kind: 'search', text, label: `Search for “${text}”`, detail: engineById(S().search.default).name, icon: '🔍' });

  cur.order.map((id) => cur.tabs.get(id))
    .filter((t) => t.id !== cur.activeId && match(t.wc.getTitle(), t.wc.getURL())).slice(0, 3)
    .forEach((t) => push({ kind: 'tab', id: t.id, url: t.wc.getURL(), label: t.wc.getTitle() || t.wc.getURL(), detail: 'Switch to tab', icon: '▭' }));

  bookmarks.filter((b) => !b.deleted && match(b.title, b.url)).slice(0, 4)
    .forEach((b) => push({ kind: 'url', url: b.url, label: b.title || b.url, detail: b.url, icon: '★' }));

  // history ranked by how often and how recently it was visited
  const now = Date.now();
  const agg = new Map();
  for (const h of history) {
    if (!match(h.title, h.url)) continue;
    const a = agg.get(h.url) || { url: h.url, title: h.title, score: 0 };
    a.score += Math.exp(-(now - h.time) / (14 * 864e5)) + 0.2;
    if (h.title) a.title = h.title;
    agg.set(h.url, a);
  }
  [...agg.values()].sort((a, b) => b.score - a.score).slice(0, 5)
    .forEach((h) => push({ kind: 'url', url: h.url, label: h.title || h.url, detail: h.url, icon: '🕘' }));

  return out.slice(0, 8);
}

function showSuggestions(items, sel) {
  cur.overlay.setBounds({ ...overlayBounds('suggest'), height: items.length * 36 + 12 });
  cur.overlay.webContents.send('suggest:items', items, sel);
}

function paletteItems(q) {
  const terms = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const match = (...f) => { const s = f.join(' ').toLowerCase(); return terms.every((t) => s.includes(t)); };
  const out = [];
  const keysText = (c) => effectiveKeys(c).join(' / ');

  cur.order.map((id) => cur.tabs.get(id)).filter((t) => match(t.wc.getTitle(), t.wc.getURL())).slice(0, 8)
    .forEach((t) => out.push({ kind: 'tab', id: t.id, label: t.wc.getTitle() || t.wc.getURL(), detail: 'Open tab · ' + t.wc.getURL() }));

  if (terms.length) {
    bookmarks.filter((b) => !b.deleted && match(b.title, b.url)).slice(0, 8)
      .forEach((b) => out.push({ kind: 'url', url: b.url, label: b.title || b.url, detail: '★ ' + b.url }));
  }

  COMMANDS.filter((c) => match(c.label)).slice(0, terms.length ? 12 : 40)
    .forEach((c) => out.push({ kind: 'cmd', id: c.id, label: c.label, detail: keysText(c) }));

  if (terms.length) {
    const seen = new Set();
    for (let i = history.length - 1; i >= 0 && seen.size < 8; i--) {
      const h = history[i];
      if (seen.has(h.url) || !match(h.title, h.url)) continue;
      seen.add(h.url);
      out.push({ kind: 'url', url: h.url, label: h.title || h.url, detail: h.url });
    }
    const looksLikeUrl = /\.|:\/\//.test(q) && !/\s/.test(q.trim());
    const item = { kind: 'search', text: q, label: `${looksLikeUrl ? 'Open' : 'Search'} “${q.trim()}”`, detail: looksLikeUrl ? '' : engineById(S().search.default).name };
    if (looksLikeUrl) out.unshift(item); else out.push(item);
  }
  return out.slice(0, 40);
}

function paletteRun(item) {
  if (!item || typeof item !== 'object') return;
  hideOverlay(false);
  if (item.kind === 'cmd') runCommand(item.id);
  else if (item.kind === 'tab') switchTab(item.id);
  else if (item.kind === 'url' && (isWeb(item.url) || isMy(item.url))) { activeWc()?.loadURL(item.url); activeWc()?.focus(); }
  else if (item.kind === 'search' && typeof item.text === 'string' && activeTab()) { go(activeTab(), item.text); activeWc().focus(); }
}

// ---------- password manager ----------
// Keys kept on this computer, protected by the OS keychain (Electron safeStorage) when there is one.
//   vault-device.key   the key of a "device" vault (no master password)
//   vault-remember.key the key derived from the master password, so that it is only typed once on this computer
const KEYFILES = { device: 'vault-device.key', remember: 'vault-remember.key' };
function readKey(name) {
  try {
    const f = file(name), plain = file(name + '.plain');
    if (safeStorage.isEncryptionAvailable() && fs.existsSync(f)) return safeStorage.decryptString(Buffer.from(fs.readFileSync(f, 'utf8'), 'base64'));
    if (fs.existsSync(plain)) return fs.readFileSync(plain, 'utf8').trim();
  } catch { /* unreadable: treat as missing */ }
  return null;
}
function writeKey(name, b64) {
  delKey(name);
  if (safeStorage.isEncryptionAvailable()) fs.writeFileSync(file(name), safeStorage.encryptString(b64).toString('base64'), { mode: 0o600 });
  else fs.writeFileSync(file(name + '.plain'), b64, { mode: 0o600 });
}
function delKey(name) { for (const f of [file(name), file(name + '.plain')]) { try { fs.unlinkSync(f); } catch { /* not there */ } } }

// open the vault without asking, when this computer holds its key
function autoUnlockVault() {
  if (!vault.exists() || vault.unlocked()) return;
  const b = vault.mode() === 'device' ? readKey(KEYFILES.device) : (S().passwords.remember ? readKey(KEYFILES.remember) : null);
  if (b) vault.unlockWithKey(Buffer.from(b, 'base64'));
}
function createDeviceVault() {
  const rawKey = crypto.randomBytes(32);
  vault.createWithKey(rawKey);
  writeKey(KEYFILES.device, rawKey.toString('base64'));
  rawKey.fill(0);
}
// after the master password was typed (or set): remember it on this computer unless that is switched off
function afterUnlock() {
  if (vault.unlocked() && vault.mode() === 'master' && S().passwords.remember) { try { writeKey(KEYFILES.remember, vault.exportKey()); } catch { /* ignore */ } }
  touchVault();
}
const keepsUnlocked = () => vault.isDevice() || !!S().passwords.remember;

function touchVault() {
  clearTimeout(vaultTimer);
  if (keepsUnlocked()) return; // device vaults and "remember on this computer" stay unlocked
  const m = Number(S().passwords.autoLockMinutes);
  if (vault.unlocked() && m > 0) vaultTimer = setTimeout(() => vault.lock(), m * 60000);
}

const tabOrigin = (t) => { try { return originOf(t.wc.getURL()); } catch { return null; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function maybeShowPwSave(w) {
  const p = w.pwPending;
  if (!p || p.shown || !windows.has(w)) return;
  p.shown = true;
  use(w);
  showOverlay('pw', {
    view: 'save', host: p.host, username: p.username, mode: p.mode,
    needsCreate: !vault.exists() && syncReady(), needsUnlock: vault.exists() && !vault.unlocked(),
  });
}

function showPwFill(w, tab, rect, force = false) {
  use(w);
  if (w.pwSuppressUntil && Date.now() < w.pwSuppressUntil) return;
  if (!vault.exists() || !S().passwords.enabled) return;
  const origin = tabOrigin(tab);
  if (!origin) return;
  if (cur.overlayMode && !(cur.overlayMode === 'pw' && cur.overlayData && cur.overlayData.view === 'fill')) return;
  if (!vault.unlocked()) {
    // vault locked — don't show unlock prompt on field focus; user can unlock via key icon
    if (force) showOverlay('pw', { view: 'fill', items: [], locked: true, x: 0, y: 0 });
    return;
  }
  const z = tab.wc.getZoomFactor() || 1;
  const pos = { x: (rect.left || 0) * z, y: (rect.bottom || 0) * z + 4 };
  cur.pwField = pos;
  const entries = vault.findByOrigin(origin);
  const items = entries.map((e) => ({ id: e.id, name: e.name, username: e.username || '(no username)' }));
  if (!items.length && !force) { if (cur.overlayMode === 'pw') hideOverlay(false); return; }
  // auto-fill when exactly one login saved and not forcing (key-icon click)
  if (items.length === 1 && !force) {
    const pw = vault.reveal(entries[0].id);
    if (pw) {
      w.pwSuppressUntil = Date.now() + 3000;
      tab.wc.send('pw:fill', { username: entries[0].username, password: pw });
      return;
    }
  }
  showOverlay('pw', { view: 'fill', items, empty: !items.length, ...pos });
  if (cur.overlayMode === 'pw') cur.overlay.setBounds(overlayBounds('pw'));
}

// ---------- side panel ----------
function syncWebPanel() {
  if (!cur.panelOpen || !cur.panelTab.startsWith('web:')) return;
  const def = S().panel.webPanels.find((p) => 'web:' + p.id === cur.panelTab);
  if (!def || !isWeb(def.url)) { cur.panelTab = 'bookmarks'; return; }
  if (!cur.webPanel) {
    const w = cur;
    cur.webPanel = new WebContentsView({ webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } });
    const wc = cur.webPanel.webContents;
    wc.setUserAgent(MOBILE_UA);
    wc.setWindowOpenHandler(({ url }) => { use(w); newTab(url, { after: w.activeId }); return { action: 'deny' }; });
    cur.win.contentView.addChildView(cur.webPanel);
  }
  if (cur.webPanelUrl !== def.url) {
    cur.webPanelUrl = def.url;
    cur.webPanel.webContents.loadURL(def.url);
  }
}

function setPanel(open, tabId) {
  if (tabId) cur.panelTab = tabId;
  cur.panelOpen = open;
  try { store.set('panel.open', open); if (tabId) store.set('panel.last', tabId); } catch { /* ignore */ }
  syncWebPanel();
  layout();
  pushState();
  if (open) cur.panelView.webContents.send('panel:changed');
}

function togglePanel() { setPanel(!cur.panelOpen); }

function saveNotes() { try { save('notes.json', notes); } catch { /* ignore */ } }

function tablistData() {
  const wins = [...windows];
  const open = [];
  wins.forEach((w, wi) => {
    for (const id of w.order) {
      const t = w.tabs.get(id);
      open.push({
        id, title: t.wc.getTitle() || t.wc.getURL(), url: t.wc.getURL(), favicon: t.favicon, pinned: t.pinned,
        active: w === cur && id === w.activeId, window: wins.length > 1 ? wi + 1 : 0, private: t.private,
      });
    }
  });
  const closed = closedTabs.map((c, i) => ({ idx: i, title: c.title || c.url, url: c.url })).reverse().slice(0, 20);
  return { open, closed };
}

function securityInfo() {
  const wc = activeWc();
  const url = wc ? wc.getURL() : '';
  const host = hostOf(url);
  const sec = securityLevel(url, host);
  const allow = S().privacy.allowSites;
  return {
    url, host, level: sec.level, label: sec.label,
    cert: certInfo.get(host) || null,
    certException: S().privacy.certExceptions.includes(host),
    blocked: wc ? blockedCounts.get(wc.id) || 0 : 0,
    blockerOn: S().privacy.blockTrackers,
    siteAllowed: !!host && allow.some((x) => host === x || host.endsWith('.' + x)),
  };
}

// ---------- context menu ----------
function showContextMenu(tab, params) {
  const wc = tab.wc;
  const m = [];
  const sep = () => { if (m.length && m[m.length - 1].type !== 'separator') m.push({ type: 'separator' }); };
  const add = (label, click, extra = {}) => m.push({ label, click, ...extra });

  if (params.misspelledWord && params.dictionarySuggestions.length) {
    for (const s of params.dictionarySuggestions.slice(0, 5)) add(s, () => wc.replaceMisspelling(s));
    sep();
  }
  if (params.linkURL) {
    add('Open Link in New Tab', () => newTab(params.linkURL, { after: tab.id, private: tab.private, background: true }));
    add('Open Link in New Private Tab', () => newTab(params.linkURL, { private: true }));
    add('Copy Link Address', () => clipboard.writeText(params.linkURL));
    add('Add Link to Speed Dial', () => { const ok = addSpeedDial(params.linkURL, params.linkText); flashStatus(tab, ok ? 'Added to Speed Dial ✓' : 'Already in Speed Dial'); });
    sep();
  }
  if (params.mediaType === 'image' && params.srcURL) {
    add('Open Image in New Tab', () => newTab(params.srcURL, { after: tab.id, private: tab.private }));
    add('Copy Image', () => wc.copyImageAt(params.x, params.y));
    add('Copy Image Address', () => clipboard.writeText(params.srcURL));
    sep();
  }
  if (params.isEditable) {
    m.push({ role: 'cut', enabled: params.editFlags.canCut });
    m.push({ role: 'copy', enabled: params.editFlags.canCopy });
    m.push({ role: 'paste', enabled: params.editFlags.canPaste });
    m.push({ role: 'selectAll' });
    sep();
  } else if (params.selectionText) {
    m.push({ role: 'copy' });
    const t = params.selectionText.trim();
    add(`Search for “${t.length > 30 ? t.slice(0, 30) + '…' : t}”`, () => newTab(searchUrl(engineById(S().search.default), t), { after: tab.id, private: tab.private }));
    add(`Translate “${t.length > 30 ? t.slice(0, 30) + '…' : t}”`, () => newTab('https://translate.google.com/?sl=auto&tl=' + encodeURIComponent(app.getLocale().split('-')[0]) + '&text=' + encodeURIComponent(t) + '&op=translate', { after: tab.id, private: tab.private }));
    sep();
  }
  add('Back', () => wc.navigationHistory.goBack(), { enabled: wc.navigationHistory.canGoBack() });
  add('Forward', () => wc.navigationHistory.goForward(), { enabled: wc.navigationHistory.canGoForward() });
  add('Reload', () => wc.reload());
  sep();
  add('Bookmark This Page', toggleBookmark);
  add('Add Page to Speed Dial', () => runCommand('page-add-speeddial'));
  add('Translate Page', () => translateTab(tab));
  add('View Page Source', () => runCommand('page-source'));
  add('Inspect Element', () => wc.inspectElement(params.x, params.y));
  popup(Menu.buildFromTemplate(m));
}

function popup(menu) {
  try { menu.popup({ window: cur.win }); } catch { menu.popup(); }
}

function launchProfile(id, unlocked) {
  // Start the same program the same way this instance was started (same Electron, same app folder, same flags),
  // only with another profile. This avoids Electron's default "welcome" page, which appears when the app path is missing.
  const args = process.argv.slice(1).filter((a) => !a.startsWith('--profile=') && a !== '--first-run');
  args.push('--profile=' + id);
  if (profiles.isFirstRun(id)) { args.push('--first-run'); profiles.clearFirstRun(id); }
  const env = { ...process.env };
  delete env.MB_UNLOCKED_PROFILE;
  if (unlocked) env.MB_UNLOCKED_PROFILE = id;
  const child = spawn(process.execPath, args, { detached: true, stdio: 'ignore', env, cwd: process.cwd() });
  child.on('error', () => { /* could not start */ });
  child.unref();
}

function profileMenu() {
  profiles.reload();
  const acc = S().sync.account || {};
  const isSignedIn = !!(S().sync.token && acc.user);
  const syncOk = isSignedIn && syncStatus.startsWith('Last synced');
  const syncDot = isSignedIn ? (syncOk ? '🟢' : '🔴') : '⚪';
  const syncLabel = isSignedIn
    ? syncDot + '  ' + (syncOk ? syncStatus : 'Sync: ' + syncStatus)
    : syncDot + '  Sync not signed in';
  const items = profiles.list().map((p) => ({
    label: (p.id === profileId ? '●  ' : '    ') + p.name + (p.locked ? '   (password)' : ''),
    enabled: p.id !== profileId,
    click: () => launchProfile(p.id, false),
  }));
  const syncItem = isSignedIn
    ? { label: syncLabel, enabled: false }
    : { label: syncLabel, click: () => { profiles.clearSetupDone(); showWizard(); } };
  popup(Menu.buildFromTemplate([
    syncItem,
    { type: 'separator' },
    ...items, { type: 'separator' },
    { label: 'Manage profiles…', click: () => openInternal('settings#profiles') },
    { label: 'Settings → Sync', click: () => openInternal('settings#sync') },
    { type: 'separator' },
    { label: 'Quit this profile', click: () => app.quit() },
  ]));
}

function mainMenu() {
  const cmd = (id) => COMMANDS.find((x) => x.id === id);
  const item = (id, overrides) => {
    const c = cmd(id);
    const keys = effectiveKeys(c);
    return Object.assign({ label: c.label, accelerator: keys[0] || undefined, click: c.run }, overrides);
  };
  const wc = activeWc();
  const url = wc ? wc.getURL() : '';
  const host = hostOf(url);
  const webPage = isWeb(url);
  const allow = S().privacy.allowSites;
  const siteAllowed = host && allow.some((h) => host === h || host.endsWith('.' + h));
  const blockerOn = S().privacy.blockTrackers;
  const zoomPct = wc ? Math.round(wc.getZoomFactor() * 100) : 100;

  popup(Menu.buildFromTemplate([
    item('tab-new'), item('tab-new-private'), item('window-new'), item('window-new-private'),
    { type: 'separator' },
    item('app-history'), item('app-bookmarks'), item('app-downloads'), item('app-passwords'), item('app-notes'),
    { type: 'separator' },
    item('page-zoom-in'), item('page-zoom-out'),
    item('page-zoom-reset', { label: `Reset Zoom (${zoomPct}%)` }),
    { type: 'separator' },
    item('page-find'), item('page-print'), item('page-translate', { enabled: webPage }),
    { type: 'separator' },
    {
      label: 'Block Trackers on This Site', type: 'checkbox',
      checked: !!(blockerOn && !siteAllowed && host),
      enabled: !!(host && blockerOn),
      click: toggleSiteBlocking,
    },
    { type: 'separator' },
    {
      label: 'More Tools',
      submenu: [
        item('app-start'), item('page-add-speeddial', { enabled: webPage }),
        item('app-quick-commands'),
        { type: 'separator' },
        item('page-screenshot'), item('page-fullscreen'),
        item('page-source', { enabled: webPage }), item('page-devtools'),
        { type: 'separator' },
        item('app-profiles'), item('app-library'), item('app-import'),
        item('app-server-admin'),
        { type: 'separator' },
        item('sync-now'),
      ],
    },
    { type: 'separator' },
    item('app-settings'),
    { label: 'Appearance', click: () => openInternal('settings#appearance') },
    { type: 'separator' },
    { label: 'Check for updates', click: () => checkForUpdates(true) },
    { label: 'Quit', click: () => app.quit() },
  ]));
}

function tabMenu(id) {
  const t = cur.tabs.get(id);
  if (!t) return;
  popup(Menu.buildFromTemplate([
    { label: 'Reload', click: () => t.wc.reload() },
    { label: 'Duplicate Tab', click: () => duplicateTab(id) },
    { label: t.pinned ? 'Unpin Tab' : 'Pin Tab', click: () => togglePin(id) },
    { label: t.wc.audioMuted ? 'Unmute Tab' : 'Mute Tab', click: () => toggleMute(id) },
    { type: 'separator' },
    { label: 'Move Tab to New Window', enabled: cur.order.length > 1, click: () => tearOffTab(t) },
    { label: 'Close Tab', click: () => closeTab(id) },
    { label: 'Close Other Tabs', click: () => closeOthers(id) },
  ]));
}

// ---------- downloads ----------
const downloads = [];
const dlItems = new Map();
let dlCounter = 0;
let winUidCounter = 0;

const downloadDir = () => S().downloads.directory || app.getPath('downloads');

function uniquePath(dir, name) {
  const ext = path.extname(name), base = path.basename(name, ext);
  let p = path.join(dir, name), n = 1;
  while (fs.existsSync(p)) p = path.join(dir, `${base} (${n++})${ext}`);
  return p;
}

function onWillDownload(_e, item) {
  const ownerTab = _e.sender ? tabByWc.get(_e.sender.id) : null;
  const rec = {
    id: ++dlCounter, name: item.getFilename(), url: item.getURL(),
    total: item.getTotalBytes(), received: 0, state: 'progressing', path: '',
    winUid: ownerTab ? ownerTab.owner.uid : (cur ? cur.uid : null),
  };
  downloads.unshift(rec);
  dlItems.set(rec.id, item);
  if (!S().downloads.askWhere) {
    const dir = downloadDir();
    fs.mkdirSync(dir, { recursive: true });
    rec.path = uniquePath(dir, rec.name);
    item.setSavePath(rec.path);
  }
  item.on('updated', (_ev, state) => {
    rec.received = item.getReceivedBytes();
    rec.total = item.getTotalBytes();
    rec.state = item.isPaused() ? 'paused' : state === 'interrupted' ? 'interrupted' : 'progressing';
    pushSoon();
  });
  item.on('done', (_ev, state) => {
    rec.received = item.getReceivedBytes();
    rec.state = state;
    rec.path = item.getSavePath() || rec.path;
    if (rec.path) rec.name = path.basename(rec.path);
    dlItems.delete(rec.id);
    pushSoon();
  });
  // First-time: ask the user's download location preference
  if (!S().downloads.firstTimeAsked) {
    store.set('downloads.firstTimeAsked', true);
    setImmediate(async () => {
      const { response } = await dialog.showMessageBox({
        type: 'question',
        title: 'Download location',
        message: 'Where do you want to save downloads?',
        detail: 'You can change this later in Settings.',
        buttons: ['Save to Downloads folder automatically', 'Always ask where to save'],
        defaultId: 0,
      });
      store.set('downloads.askWhere', response === 1);
    });
  }
  pushSoon();
}

function downloadAction(id, action) {
  if (action === 'removeAll') {
    const active = new Set(['progressing', 'paused']);
    const keep = downloads.filter((d) => active.has(d.state));
    for (const d of downloads) { if (!active.has(d.state)) dlItems.delete(d.id); }
    downloads.splice(0, downloads.length, ...keep);
    pushSoon();
    return true;
  }
  const rec = downloads.find((d) => d.id === id);
  if (!rec) return false;
  const item = dlItems.get(id);
  if (action === 'open' && rec.path) shell.openPath(rec.path);
  else if (action === 'show' && rec.path) shell.showItemInFolder(rec.path);
  else if (action === 'copyUrl' && rec.url) clipboard.writeText(rec.url);
  else if (action === 'copyPath' && rec.path) clipboard.writeText(rec.path);
  else if (action === 'cancel' && item) item.cancel();
  else if (action === 'pause' && item) item.pause();
  else if (action === 'resume' && item && item.canResume()) item.resume();
  else if (action === 'restart') {
    const wc = cur && cur.activeId && cur.tabs.get(cur.activeId) ? cur.tabs.get(cur.activeId).wc : null;
    if (wc) wc.downloadURL(rec.url);
  }
  else if (action === 'remove') {
    if (item) item.cancel();
    dlItems.delete(id);
    downloads.splice(downloads.indexOf(rec), 1);
    pushSoon();
  }
  return true;
}

ipcMain.on('downloads:ctxmenu', (_e, id) => {
  const rec = downloads.find((d) => d.id === id);
  if (!rec) return;
  const item = dlItems.get(id);
  const active = rec.state === 'progressing' || rec.state === 'paused';
  const tpl = [
    { label: 'Open downloads page', click: () => newTab('my://downloads') },
    { type: 'separator' },
  ];
  if (rec.state === 'completed') {
    tpl.push({ label: 'Open file', click: () => shell.openPath(rec.path) });
    tpl.push({ label: 'Show in folder', click: () => shell.showItemInFolder(rec.path) });
  }
  tpl.push({ label: 'Copy download address', click: () => clipboard.writeText(rec.url) });
  if (rec.path) tpl.push({ label: 'Copy local path', click: () => clipboard.writeText(rec.path) });
  if (active) {
    tpl.push({ type: 'separator' });
    if (rec.state === 'progressing') tpl.push({ label: 'Pause', click: () => item && item.pause() });
    if (rec.state === 'paused') tpl.push({ label: 'Resume', click: () => item && item.canResume() && item.resume() });
    tpl.push({ label: 'Cancel', click: () => { if (item) item.cancel(); } });
  }
  if (rec.state !== 'progressing') {
    tpl.push({ type: 'separator' });
    tpl.push({ label: 'Restart download', click: () => downloadAction(id, 'restart') });
  }
  tpl.push({ type: 'separator' });
  tpl.push({ label: 'Remove from list', click: () => downloadAction(id, 'remove') });
  if (downloads.some((d) => d.state !== 'progressing' && d.state !== 'paused')) tpl.push({ label: 'Remove all finished', click: () => downloadAction(null, 'removeAll') });
  Menu.buildFromTemplate(tpl).popup();
});

// ---------- sync ----------
const syncReady = () => { const c = S().sync; return !!(c.server && c.token); };

async function call(method, p, body, opts = {}) {
  const c = S().sync;
  const res = await fetch(c.server.replace(/\/+$/, '') + p, {
    method,
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + c.token },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  if (res.status === 409 && opts.allow409) return { conflict: true };
  if (res.status === 401) {
    let body = '';
    try { body = await res.text(); } catch { /* ignore */ }
    // our server answers {"detail": ...}; a reverse proxy's login page looks different
    throw new Error(/"detail"|"error"/.test(body)
      ? 'the server rejected the token (check Settings > Sync)'
      : 'a web server or proxy in front of the sync server answered 401. If you use Nginx Proxy Manager, remove the Access List from that proxy host.');
  }
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.text()).slice(0, 200); } catch { /* ignore */ }
    throw new Error(`${p} → HTTP ${res.status} ${detail}`.trim());
  }
  return res.json();
}

function mergeBookmarks(remote) {
  for (const r of remote) {
    if (!isWeb(r.url)) continue;
    const i = bookmarks.findIndex((b) => b.url === r.url);
    if (i < 0) bookmarks.push(r);
    else if (r.updated > bookmarks[i].updated) bookmarks[i] = r;
  }
}

function mergeHistory(remote) {
  const seen = new Set(history.map((h) => h.url + '|' + h.time));
  for (const r of remote) {
    if (!isWeb(r.url) || seen.has(r.url + '|' + r.time)) continue;
    history.push(r);
  }
  history.sort((a, b) => a.time - b.time);
  if (history.length > 5000) history = history.slice(-5000);
}

async function syncAll() {
  if (syncing) return;
  if (wizardActive) return; // don't sync while the wizard is showing
  if (!syncReady()) { syncStatus = 'Sync not configured (Settings → Sync)'; return; }
  syncing = true;
  try {
    const device = S().sync.device || os.hostname();

    const cleanB = bookmarks
      .filter((b) => isWeb(b.url) && b.url.length <= 8000)
      .map((b) => ({ url: b.url, title: String(b.title || '').slice(0, 900), folder: String(b.folder || '').slice(0, 300), added: Math.round(b.added || 0), updated: Math.round(b.updated || 0), deleted: !!b.deleted }));
    let r = await call('POST', '/api/bookmarks', { since: syncState.bRev, items: cleanB });
    mergeBookmarks(r.items);
    syncState.bRev = r.rev;

    const pending = history
      .filter((h) => h.time > syncState.hPushed && isWeb(h.url) && h.url.length <= 8000)
      .slice(-5000)
      .map((h) => ({ url: h.url, title: String(h.title || '').slice(0, 900), time: Math.round(h.time) }));
    r = await call('POST', '/api/history', { since: syncState.hRev, items: pending });
    mergeHistory(r.items);
    syncState.hRev = r.rev;
    if (pending.length) syncState.hPushed = Math.max(...pending.map((h) => h.time));

    const mine = [...tabById.values()]
      .filter((t) => !t.private && isWeb(t.wc.getURL()))
      .map((t) => ({ title: t.wc.getTitle(), url: t.wc.getURL() }));
    await call('PUT', '/api/tabs', { device, tabs: mine });
    remoteTabs = (await call('GET', '/api/tabs?exclude=' + encodeURIComponent(device))).devices;

    // the password vault travels as ciphertext only; a problem here must not break the rest of the sync
    if (S().passwords.sync) {
      try { vaultSyncInfo = await syncVault({ vault, call, state: syncState }); }
      catch (e) { vaultSyncInfo = { state: 'error', message: 'Vault sync error: ' + e.message }; }
    }

    // settings, themes, search engines, speed dials, shortcuts… (per-device keys stay local)
    settingsSyncNote = '';
    if (S().sync.settings !== false) {
      try {
        const sr = await syncSettings({ store, call });
        if (sr.applied.length) {
          afterSettingsChange();
          if (sr.applied.includes('profileMeta')) applyProfileMeta();
        }
        // a profile picture chosen before sync existed was never put in the synced settings: send it once
        try {
          const p = profiles.get(profileId);
          if (p && (p.avatar || p.color) && store.updatedOf('profileMeta') === 0) {
            store.set('profileMeta.color', p.color || '');
            store.set('profileMeta.avatar', p.avatar || '');
            syncSoon();
          }
        } catch { /* optional */ }
      } catch (e) { settingsSyncNote = /HTTP 404/.test(e.message) ? ' (update the sync server to sync settings)' : ' (settings: ' + e.message + ')'; }
    }

    save('bookmarks.json', bookmarks);
    save('history.json', history);
    save('syncstate.json', syncState);
    syncStatus = 'Last synced ' + new Date().toLocaleTimeString() + settingsSyncNote;
  } catch (e) {
    syncStatus = 'Sync error: ' + e.message;
  } finally {
    syncing = false;
    pushAll();
  }
}

function syncSoon() {
  clearTimeout(syncTimer);
  syncTimer = setTimeout(syncAll, 3000);
}

function applyProfileMeta() {
  const m = S().profileMeta;
  if (!m || (!m.color && !m.avatar)) return;
  const update = {};
  if (m.color) update.color = m.color;
  if (m.avatar !== undefined) update.avatar = m.avatar;
  if (Object.keys(update).length) profiles.update(profileId, update);
  pushAll();
}

// "Test connection" button: says exactly which step fails
async function testSync() {
  const c = S().sync;
  const out = [];
  if (!c.server) return ['PROBLEM: no server address is set.'];
  const base = c.server.replace(/\/+$/, '');
  try {
    const r = await fetch(base + '/health', { signal: AbortSignal.timeout(10000) });
    const t = await r.text();
    if (r.ok && /"ok"/.test(t)) out.push('OK: the sync server answers at ' + base);
    else if (r.ok) { out.push('PROBLEM: ' + base + ' answers, but it does not look like the sync server (is the address or port right?).'); return out; }
    else { out.push('PROBLEM: ' + base + '/health answered HTTP ' + r.status + '.'); return out; }
  } catch (e) { return ['PROBLEM: cannot reach ' + base + ': ' + e.message]; }
  try {
    const r = await fetch(base + '/api/info', { signal: AbortSignal.timeout(10000) });
    if (r.ok) { const j = await r.json(); out.push('Server: ' + (j.name || 'sync server') + ' ' + (j.version || '') + (j.accounts ? ' (accounts and web interface available)' : '')); }
    else if (r.status === 404) out.push('NOTE: this server is an older version. Update it to get settings sync, accounts and the web interface.');
  } catch { /* optional */ }
  if (!c.token) { out.push('PROBLEM: not signed in. Enter an API key, or sign in with your account.'); return out; }
  out.push((c.account && c.account.user ? 'Signed in as ' + c.account.user + (c.account.profile ? ' (profile ' + c.account.profile + ')' : '') : 'API key') + ': ' + c.token.length + ' characters.');
  try {
    const r = await fetch(base + '/api/vault', { headers: { authorization: 'Bearer ' + c.token }, signal: AbortSignal.timeout(10000) });
    const t = await r.text();
    if (r.ok) out.push('OK: the key is accepted.');
    else if (r.status === 401) out.push(/"detail"|"error"/.test(t)
      ? 'PROBLEM: the server rejected the key. Copy it again from the server (web interface > your profile > API keys), or sign in again.'
      : 'PROBLEM: a proxy in front of the server answered 401. With Nginx Proxy Manager, remove the Access List from the proxy host (or enable passing the Authorization header).');
    else out.push('PROBLEM: HTTP ' + r.status + ' ' + t.slice(0, 120));
  } catch (e) { out.push('PROBLEM: ' + e.message); }
  return out;
}

// ---------- bookmark manager ----------
const SEP = ' / ';
const normFolder = (f) => String(f || '').split('/').map((x) => x.trim()).filter(Boolean).join(SEP).slice(0, 300);
const inFolder = (folder, path) => folder === path || folder.startsWith(path + SEP);
const saveFolders = () => { try { save('bookmark-folders.json', bookmarkFolders); } catch { /* ignore */ } };
function allFolderPaths() {
  const set = new Set();
  const addWithParents = (p) => { const parts = p.split(SEP); for (let i = 1; i <= parts.length; i++) set.add(parts.slice(0, i).join(SEP)); };
  for (const f of bookmarkFolders) if (f) addWithParents(f);
  for (const b of bookmarks) if (!b.deleted && !b.recycleBin && b.folder) addWithParents(b.folder);
  return [...set].sort((a, b) => a.localeCompare(b));
}
function touchBookmarks() {
  save('bookmarks.json', bookmarks); pushAll(); syncSoon();
  for (const w of windows) { if (w.bmView) try { w.bmView.webContents.send('bookmarks:changed'); } catch { /* ignore */ } }
}

// ---------- importing bookmarks ----------
const detectedSources = new Map();

function importBookmarks(items) {
  const now = Date.now();
  const have = new Map(bookmarks.map((b) => [b.url, b]));
  let added = 0, skipped = 0;
  for (const it of items) {
    if (!isWeb(it.url) || it.url.length > 8000) { skipped++; continue; }
    const ex = have.get(it.url);
    if (ex && !ex.deleted) {
      if (!ex.folder && it.folder) { ex.folder = it.folder; ex.updated = now; }
      skipped++;
      continue;
    }
    const rec = ex || { url: it.url, added: it.added || now };
    Object.assign(rec, { title: String(it.title || '').slice(0, 900), folder: String(it.folder || '').slice(0, 300), updated: now, deleted: false });
    if (!ex) { bookmarks.push(rec); have.set(rec.url, rec); }
    added++;
  }
  save('bookmarks.json', bookmarks);
  pushAll();
  syncSoon();
  return { added, skipped, total: items.length };
}

// ---------- clearing data ----------
async function clearData(what) {
  const ses = session.defaultSession;
  if (what === 'history' || what === 'all') { history = []; save('history.json', history); }
  if (what === 'cookies' || what === 'all') await ses.clearStorageData();
  if (what === 'cache' || what === 'all') await ses.clearCache();
  return true;
}

// ---------- settings ----------
function applySetting(p, value) {
  store.set(p, value);
  afterSettingsChange(p);
  if (syncReady() && S().sync.settings !== false && !p.startsWith('sync')) syncSoon();
}

function afterSettingsChange(p) {
  if (!p || p === 'general.browserId') for (const ses of uaSessions) { try { ses.setUserAgent(uaForMode(S().general.browserId)); } catch { /* ignore */ } }
  applyThemeSource();
  rebuildShortcuts();
  blocker.rebuild();
  if (p && p.startsWith('appearance')) {
    for (const w of windows) { try { w.win.setIcon(appIconImage()); } catch { /* not supported here */ } }
  }
  for (const t of tabById.values()) t.wc.send('gestures:enabled', S().mouse.gestures);
  for (const w of windows) {
    use(w);
    syncWebPanel();
    if (p && p.startsWith('appearance')) w.panelView.webContents.reload();
  }
  pushAll(true);
  if (p && p.startsWith('sync')) syncSoon();
}

// ---------- IPC ----------
const fromChrome = (e) => chromeIds.has(e.sender.id);
const fromInternal = (e) => !!(e.senderFrame && e.senderFrame.url && e.senderFrame.url.startsWith('my://'));
const ownerOf = (wc) => chromeOwner.get(wc.id) || (tabByWc.get(wc.id) || {}).owner;
const guard = (fn, { chrome = true, page = false } = {}) => (e, ...a) => {
  if ((chrome && fromChrome(e)) || (page && fromInternal(e))) {
    use(ownerOf(e.sender));
    return fn(e, ...a);
  }
  return undefined;
};
const on = (ch, fn, o) => ipcMain.on(ch, guard(fn, o));
const handle = (ch, fn, o) => ipcMain.handle(ch, guard(fn, o));
const BOTH = { chrome: true, page: true };

function setupIpc() {
  on('tab:new', (_e, url) => newTab(url || undefined));
  on('tab:switch', (_e, id) => switchTab(id));
  on('tab:close', (_e, id) => closeTab(id));
  on('tab:menu', (_e, id) => tabMenu(id));
  on('tab:newtab-menu', () => {
    const m = new Menu();
    m.append(new MenuItem({ label: 'New tab', click: () => newTab() }));
    m.append(new MenuItem({ label: 'New private tab', click: () => newTab(undefined, { private: true }) }));
    m.popup({ window: cur.win });
  });
  on('tab:move', (_e, id, beforeId) => {
    const t = tabById.get(id);
    if (!t || !Number.isInteger(id)) return;
    if (t.owner === cur) reorderTab(id, beforeId); else moveTabToWindow(t, cur, beforeId);
  });
  on('tab:dragend', (_e, id, dropEffect, outside) => tabDragEnd(id, dropEffect, !!outside));
  on('tab:mute', (_e, id) => toggleMute(id));
  on('tab:next', () => { const idx = cur.order.indexOf(cur.activeId); if (idx < cur.order.length - 1) switchTab(cur.order[idx + 1]); });
  on('tab:prev', () => { const idx = cur.order.indexOf(cur.activeId); if (idx > 0) switchTab(cur.order[idx - 1]); });
  on('tab:active-click', () => { const t = activeTab(); if (t && S().tabs.clickTabScrollsTop !== false) t.wc.executeJavaScript('window.scrollTo({top:0,behavior:"smooth"})').catch(()=>{}); });
  on('tab:new-private', () => newTab(undefined, { private: true }));
  on('menu:main', () => mainMenu());
  on('security:show', () => { if (cur.overlayMode === 'security') hideOverlay(); else showOverlay('security', securityInfo()); });
  on('zoom:reset', () => zoom(0));
  on('zoom:set', (_e, pct) => { const wc = activeWc(); if (!wc) return; wc.setZoomFactor(Math.min(3, Math.max(0.3, Number(pct) / 100))); pushState(); });
  on('translate:page', () => translateTab(null));
  on('translate:dismiss', () => { cur.translateDismissed = true; layout(); pushState(); });
  ipcMain.on('page:lang', (e, lang) => {
    const t = tabByWc.get(e.sender.id); if (!t) return;
    if (typeof lang !== 'string') return;
    t.detectedLang = lang.split('-')[0].toLowerCase();
    use(t.owner); layout(); pushState();
  });
  ipcMain.on('zoom:ctrl-wheel', (e, dir) => {
    const t = tabByWc.get(e.sender.id); if (!t) return;
    use(t.owner); const wc = t.wc; const f = wc.getZoomFactor();
    wc.setZoomFactor(Math.min(3, Math.max(0.25, f + dir * 0.1))); pushState();
  });
  on('win:min', () => cur.win.minimize());
  on('win:max', () => { if (cur.win.isMaximized()) cur.win.unmaximize(); else cur.win.maximize(); });
  on('win:close', () => cur.win.close());
  on('panel:toggle', () => togglePanel());
  on('logins:show', () => {
    const t = activeTab();
    if (!t) return;
    if (cur.overlayMode === 'pw') { hideOverlay(); return; }
    const z = t.wc.getZoomFactor() || 1;
    cur.pwSuppressUntil = 0;
    showPwFill(cur, t, { left: Math.max(0, cur.contentRect.width - 340) / z, bottom: -2 / z }, true);
  });
  on('panel:resize', (_e, phase, dx) => {
    if (!cur.panelOpen) return;
    const clampW = (n) => Math.max(240, Math.min(600, Math.round(n)));
    if (phase === 'start') { cur.resizeStart = Number(S().panel.width) || 320; return; }
    const dir = S().panel.side === 'right' ? -1 : 1;
    const nw = clampW(cur.resizeStart + dir * (Number(dx) || 0));
    if (phase === 'move') { cur.panelWidthLive = nw; layout(); return; }
    cur.panelWidthLive = null;
    applySetting('panel.width', nw);
  });
  on('tablist:show', () => { if (cur.overlayMode === 'tablist') hideOverlay(); else showOverlay('tablist', tablistData()); });
  on('tablist:switch', (_e, id) => {
    const t = tabById.get(id);
    if (!t) return;
    hideOverlay(false);
    use(t.owner);
    try { t.owner.win.focus(); } catch { /* ignore */ }
    switchTab(id);
  });
  on('tablist:close', (_e, id) => {
    const t = tabById.get(id);
    if (!t) return;
    const w = cur;
    use(t.owner); closeTab(id); use(w);
    if (cur.overlayMode === 'tablist') cur.overlay.webContents.send('tablist:data', tablistData());
  });
  on('tablist:reopen', (_e, idx) => {
    const c = closedTabs.splice(idx, 1)[0];
    hideOverlay(false);
    if (c) newTab(c.url, { pinned: c.pinned });
  });

  // address bar suggestions
  on('suggest:query', (_e, text) => {
    if (cur.overlayMode && cur.overlayMode !== 'suggest') return;
    const items = suggestItems(String(text || ''));
    if (!items.length) { cur.suggest = null; if (cur.overlayMode === 'suggest') hideOverlay(false); return; }
    cur.suggest = { items, sel: -1, typed: String(text) };
    if (cur.overlayMode !== 'suggest') showOverlay('suggest');
    showSuggestions(items, -1);
  });
  on('suggest:nav', (_e, dir) => {
    const sg = cur.suggest;
    if (!sg || cur.overlayMode !== 'suggest') return;
    let sel = sg.sel + (dir > 0 ? 1 : -1);
    if (sel >= sg.items.length) sel = -1;
    if (sel < -1) sel = sg.items.length - 1;
    sg.sel = sel;
    showSuggestions(sg.items, sel);
    const it = sel >= 0 ? sg.items[sel] : null;
    cur.toolbarView.webContents.send('url:fill', it ? (it.kind === 'search' ? it.text : it.url) : sg.typed);
  });
  on('suggest:close', () => {
    const w = cur;
    setTimeout(() => { // small delay so a click on a suggestion is not swallowed by the blur
      if (windows.has(w) && w.overlayMode === 'suggest') { use(w); hideOverlay(false); w.suggest = null; }
    }, 150);
  });
  on('url:enter', (_e, text) => {
    const sg = cur.suggest;
    const item = sg && sg.sel >= 0 ? sg.items[sg.sel] : null;
    if (cur.overlayMode === 'suggest') hideOverlay(false);
    cur.suggest = null;
    if (item) { paletteRun(item); return; }
    const t = activeTab();
    if (t) { go(t, text); t.wc.focus(); }
  });

  // ---- password manager: pages send what the user typed / which field is focused ----
  ipcMain.on('pw:captured', (e, d) => {
    const t = tabByWc.get(e.sender.id);
    if (!t || !d || typeof d.password !== 'string') return;
    use(t.owner);
    if (!S().passwords.enabled || (t.private && S().passwords.inPrivate === false) || !d.password || d.password.length > 1024) return;
    const origin = tabOrigin(t);
    if (!origin || S().passwords.neverSave.includes(origin)) return;
    const username = String(d.username || '').slice(0, 512);
    let mode = 'save', existingId = null;
    if (vault.unlocked()) {
      const ex = vault.findByOrigin(origin).find((x) => x.username === username);
      if (ex) {
        if (vault.reveal(ex.id) === d.password) return; // already saved with this password
        mode = 'update'; existingId = ex.id;
      }
    }
    const w = t.owner;
    w.pwPending = { origin, host: hostOf(t.wc.getURL()), username, password: d.password, mode, existingId, at: Date.now(), shown: false };
    setTimeout(() => { if (w.pwPending && Date.now() - w.pwPending.at < 20000) maybeShowPwSave(w); }, 2500);
    setTimeout(() => { if (w.pwPending && Date.now() - w.pwPending.at >= 120000) w.pwPending = null; }, 125000);
  });
  ipcMain.on('pw:form', (e, has) => {
    const t = tabByWc.get(e.sender.id);
    if (!t) return;
    if (!!has !== !!t.loginForm) { t.loginForm = !!has; use(t.owner); pushState(); }
  });
  ipcMain.on('pw:focus', (e, rect) => {
    const t = tabByWc.get(e.sender.id);
    if (!t || !rect || t.id !== t.owner.activeId) return;
    showPwFill(t.owner, t, rect);
  });
  ipcMain.on('pw:blur', (e) => {
    const t = tabByWc.get(e.sender.id);
    if (!t) return;
    const w = t.owner;
    if (w.pwBlurTimer) clearTimeout(w.pwBlurTimer);
    w.pwBlurTimer = setTimeout(() => {
      w.pwBlurTimer = null;
      if (windows.has(w) && w.overlayMode === 'pw' && w.overlayData && w.overlayData.view === 'fill') { use(w); hideOverlay(false); }
    }, 1500);
  });
  handle('pw:popup-enter', () => { if (cur.pwBlurTimer) { clearTimeout(cur.pwBlurTimer); cur.pwBlurTimer = null; } });
  handle('pw:popup-leave', () => {
    const w = cur;
    if (w.pwBlurTimer) clearTimeout(w.pwBlurTimer);
    w.pwBlurTimer = setTimeout(() => {
      w.pwBlurTimer = null;
      if (windows.has(w) && w.overlayMode === 'pw' && w.overlayData && w.overlayData.view === 'fill') { use(w); hideOverlay(false); }
    }, 800);
  });

  ipcMain.on('card:focus', (e, rect) => {
    const t = tabByWc.get(e.sender.id);
    if (!t || !rect || t.id !== t.owner.activeId) return;
    if (!vault.unlocked() || !S().passwords.enabled) return;
    const cards = vault.listCards();
    if (!cards.length) return;
    use(t.owner);
    const z = t.wc.getZoomFactor() || 1;
    const pos = { x: (rect.left || 0) * z, y: (rect.bottom || 0) * z + 4 };
    showOverlay('card', { items: cards, ...pos });
  });

  ipcMain.on('addr:focus', (e, rect) => {
    const t = tabByWc.get(e.sender.id);
    if (!t || !rect || t.id !== t.owner.activeId) return;
    if (!vault.unlocked() || !S().passwords.enabled) return;
    const addrs = vault.listAddresses();
    if (!addrs.length) return;
    use(t.owner);
    const z = t.wc.getZoomFactor() || 1;
    const pos = { x: (rect.left || 0) * z, y: (rect.bottom || 0) * z + 4 };
    showOverlay('addr', { items: addrs, ...pos });
  });

  // card/addr autofill pick
  handle('card:pick', (_e, id) => {
    const t = activeTab();
    if (!t) return;
    const data = vault.revealCard(id);
    if (data) t.wc.send('card:fill', data);
    hideOverlay();
    return { ok: true };
  });
  handle('addr:pick', (_e, id) => {
    const t = activeTab();
    if (!t) return;
    const data = vault.revealAddress(id);
    if (data) t.wc.send('addr:fill', data);
    hideOverlay();
    return { ok: true };
  });

  // the popups (save bubble, fill list, unlock) talk to the vault through this one channel
  handle('pw:action', async (_e, a) => {
    if (!a || typeof a.action !== 'string') return { error: 'bad request' };
    const act = a.action;
    const t = activeTab();
    if (act === 'dismiss') { cur.pwPending = null; hideOverlay(); return { ok: true }; }
    if (act === 'never') {
      if (cur.pwPending) applySetting('passwords.neverSave', [...new Set([...S().passwords.neverSave, cur.pwPending.origin])]);
      cur.pwPending = null; hideOverlay();
      return { ok: true };
    }
    if (act === 'manage') { hideOverlay(false); openInternal('passwords'); return { ok: true }; }
    if (act === 'unlockPrompt') { showOverlay('pw', { view: 'unlock', after: 'fill' }); return { ok: true }; }
    if (act === 'unlock' || act === 'create') {
      const master = String(a.master || '');
      if (act === 'create') {
        if (vault.exists()) return { error: 'A vault already exists.' };
        if (!master) return { error: 'Enter a password.' };
        if (master !== String(a.confirm || '')) return { error: 'The two passwords do not match.' };
        vault.create(master);
      } else if (!vault.unlock(master)) { await sleep(500); return { error: 'Wrong master password.' }; }
      afterUnlock();
      if (a.after === 'fill' && t && cur.pwField) showPwFill(cur, t, { left: cur.pwField.x / (t.wc.getZoomFactor() || 1), bottom: (cur.pwField.y - 4) / (t.wc.getZoomFactor() || 1) });
      else hideOverlay();
      return { ok: true };
    }
    if (act === 'save') {
      const p = cur.pwPending;
      if (!p) return { error: 'Nothing to save.' };
      const master = String(a.master || '');
      if (!vault.exists()) {
        if (master) {
          if (master !== String(a.confirm || '')) return { error: 'The two passwords do not match.' };
          vault.create(master);
        } else if (!syncReady()) createDeviceVault(); // local only: no password needed
        else return { error: 'Enter a master password.' };
      } else if (!vault.unlocked()) {
        if (!vault.unlock(master)) { await sleep(500); return { error: 'Wrong master password.' }; }
      }
      try { vault.upsert({ id: p.existingId, origin: p.origin, username: p.username, password: p.password }); } catch { return { error: 'Could not save.' }; }
      afterUnlock();
      syncSoon();
      cur.pwPending = null;
      hideOverlay();
      if (t) flashStatus(t, 'Password saved ✓');
      return { ok: true };
    }
    if (act === 'fill') {
      if (!vault.unlocked() || !t) return { error: 'Locked.' };
      const e = vault.get(a.id);
      const pw = vault.reveal(a.id);
      if (!e || pw == null || e.origin !== tabOrigin(t)) return { error: 'This login belongs to another site.' };
      t.wc.send('pw:fill', { username: e.username, password: pw });
      cur.pwSuppressUntil = Date.now() + 1200;
      touchVault();
      hideOverlay();
      return { ok: true };
    }
    if (act === 'deleteEntry') {
      if (!vault.unlocked() || !a.id) return { error: 'Locked.' };
      vault.remove(a.id);
      touchVault();
      syncSoon();
      if (t && cur.pwField) showPwFill(cur, t, { left: cur.pwField.x / (t.wc.getZoomFactor() || 1), bottom: (cur.pwField.y - 4) / (t.wc.getZoomFactor() || 1) });
      else hideOverlay(false);
      return { ok: true };
    }
    return { error: 'unknown action' };
  });

  // ---- password manager page (my://passwords) ----
  const PAGE_ONLY = { chrome: false, page: true };
  const locked = () => { if (!vault.unlocked()) throw new Error('locked'); touchVault(); };
  handle('vault:status', () => ({
    exists: vault.exists(), unlocked: vault.unlocked(), count: vault.unlocked() ? vault.count() : null,
    autoLock: S().passwords.autoLockMinutes, deviceMode: vault.isDevice(), mode: vault.mode(),
    remember: !!S().passwords.remember, syncReady: syncReady(),
    safeStorageAvailable: safeStorage.isEncryptionAvailable(),
  }), PAGE_ONLY);
  handle('vault:syncStatus', () => ({
    enabled: !!(S().passwords.sync && syncReady()), configured: syncReady(), ...vaultSyncInfo,
  }), PAGE_ONLY);
  handle('vault:syncResolve', async (_e, choice) => {
    if (!syncReady() || !['server', 'local'].includes(choice)) return { state: 'error', message: 'Sync is not set up.' };
    try {
      vaultSyncInfo = await resolveVault({ vault, call, state: syncState, choice });
      save('syncstate.json', syncState);
    } catch (e) { vaultSyncInfo = { state: 'error', message: 'Vault sync error: ' + e.message }; }
    return vaultSyncInfo;
  }, PAGE_ONLY);
  // first-time setup: { mode: 'master' | 'device', master, confirm, remember }
  handle('vault:setup', (_e, o) => {
    o = o || {};
    if (vault.exists()) return { error: 'A vault already exists.' };
    if (o.mode === 'device') createDeviceVault();
    else {
      if (!o.master) return { error: 'Enter a master password.' };
      if (o.master !== o.confirm) return { error: 'The two passwords do not match.' };
      if (typeof o.remember === 'boolean') applySetting('passwords.remember', o.remember);
      vault.create(String(o.master));
      afterUnlock();
    }
    touchVault(); syncSoon();
    return { ok: true };
  }, PAGE_ONLY);
  handle('vault:unlock', async (_e, master) => {
    if (!vault.unlock(String(master || ''))) { await sleep(500); return { error: 'Wrong master password.' }; }
    afterUnlock();
    return { ok: true };
  }, PAGE_ONLY);
  // does the server already hold a vault? (so that a second computer joins it instead of making a rival one)
  handle('vault:remoteInfo', async () => {
    if (!syncReady()) return { configured: false };
    try { const r = await call('GET', '/api/vault'); return { configured: true, exists: !!r.exists, device: !!(r.blob && r.blob.device) }; }
    catch (e) { return { configured: true, error: e.message }; }
  }, PAGE_ONLY);
  // use the server's vault on this computer: master password, or (for a device vault) the key from the other computer
  handle('vault:join', async (_e, o) => {
    o = o || {};
    if (vault.exists()) return { error: 'This computer already has a vault.' };
    if (!syncReady()) return { error: 'Sync is not set up.' };
    try {
      const r = await call('GET', '/api/vault');
      if (!r.exists) return { error: 'The server has no vault.' };
      vault.adopt(r.blob);
      syncState.vaultVer = r.version; syncState.vaultHash = crypto.createHash('sha256').update(vault.blobText()).digest('hex'); save('syncstate.json', syncState);
      if (r.blob.device) {
        const k = String(o.key || '').trim();
        if (!k || !vault.unlockWithKey(Buffer.from(k, 'base64'))) return { error: 'That key does not open the vault.' };
        writeKey(KEYFILES.device, k);
      } else {
        if (typeof o.remember === 'boolean') applySetting('passwords.remember', o.remember);
        if (!vault.unlock(String(o.master || ''))) { await sleep(500); return { error: 'Wrong master password.' }; }
        afterUnlock();
      }
      return { ok: true };
    } catch (e) { return { error: e.message }; }
  }, PAGE_ONLY);
  // a device vault whose key is missing on this computer: paste the key from the computer that made it
  handle('vault:importKey', (_e, k) => {
    k = String(k || '').trim();
    if (!vault.isDevice()) return { error: 'This vault uses a master password.' };
    if (!k || !vault.unlockWithKey(Buffer.from(k, 'base64'))) return { error: 'That key does not open the vault.' };
    writeKey(KEYFILES.device, k); touchVault();
    return { ok: true };
  }, PAGE_ONLY);
  // switch between master password and device key (the vault is re-encrypted; other devices need the new password / key)
  handle('vault:setMode', (_e, o) => {
    o = o || {};
    if (!vault.unlocked()) return { error: 'Unlock the vault first.' };
    if (o.mode === 'master') {
      if (!o.master) return { error: 'Enter a master password.' };
      if (o.master !== o.confirm) return { error: 'The two passwords do not match.' };
      vault.setMaster(String(o.master));
      delKey(KEYFILES.device);
      afterUnlock();
    } else if (o.mode === 'device') {
      const rawKey = crypto.randomBytes(32);
      vault.setDevice(rawKey);
      writeKey(KEYFILES.device, rawKey.toString('base64'));
      delKey(KEYFILES.remember);
      rawKey.fill(0);
      touchVault();
    } else return { error: 'Unknown mode.' };
    syncSoon();
    return { ok: true };
  }, PAGE_ONLY);
  // "only type the master password once on this computer" on / off
  handle('vault:setRemember', (_e, on) => {
    on = !!on;
    applySetting('passwords.remember', on);
    if (on) { if (vault.unlocked() && vault.mode() === 'master') afterUnlock(); }
    else { delKey(KEYFILES.remember); touchVault(); }
    return { ok: true };
  }, PAGE_ONLY);
  handle('vault:lock', () => { if (!vault.isDevice()) { vault.lock(); clearTimeout(vaultTimer); delKey(KEYFILES.remember); } return true; }, PAGE_ONLY);
  // the device key, so that another device of yours (the Android app) can be given the same key once
  // a QR code that connects another device (the phone): server address, a new key for it, and the vault key
  handle('pair:qr', async (e, opts) => {
    if (!syncReady()) return { error: 'Connect this browser to your server first (Settings > Sync).' };
    let tok;
    try { tok = (await call('POST', '/api/pair', { device: String((opts && opts.device) || 'Phone') })).token; }
    catch (err) { return { error: /404|405/.test(err.message) ? 'Update your sync server to use this (it needs the newest version).' : err.message }; }
    if (!tok) return { error: 'The server did not return a key.' };
    let key = '';
    let withKey = false;
    if (opts && opts.withVault && vault.exists() && vault.unlocked()) {
      try { key = vault.isDevice() ? (readKey(KEYFILES.device) || '') : vault.exportKey(); withKey = !!key; } catch { key = ''; }
    }
    const payload = ['mbp1', S().sync.server.replace(/\/+$/, ''), tok, key].join('|');
    return { svg: require('./lib/qr').makeSvg(payload, 6, '#000000', '#ffffff'), withKey };
  }, PAGE_ONLY);
  handle('vault:exportKey', () => {
    if (!vault.isDevice()) return { error: 'This vault uses a master password: just type that password on the other device.' };
    const k = readKey(KEYFILES.device);
    return k ? { key: k } : { error: 'This computer does not hold the vault key.' };
  }, PAGE_ONLY);
  // open the vault if this computer holds its key. Never creates or deletes anything.
  handle('vault:open', () => {
    autoUnlockVault();
    if (!vault.exists()) return { state: 'none' };
    if (vault.unlocked()) return { state: 'unlocked' };
    return { state: vault.isDevice() ? 'needkey' : 'locked' };
  }, PAGE_ONLY);
  handle('vault:list', () => { if (!vault.unlocked()) return null; touchVault(); return vault.list(); }, PAGE_ONLY);
  handle('vault:reveal', (_e, id) => { locked(); return vault.reveal(id); }, PAGE_ONLY);
  handle('vault:copy', (_e, id) => {
    locked();
    const pw = vault.reveal(id);
    if (pw == null) return false;
    clipboard.writeText(pw);
    setTimeout(() => { if (clipboard.readText() === pw) clipboard.clear(); }, 30000); // wipe it again after 30 s
    return true;
  }, PAGE_ONLY);
  handle('vault:save', (_e, entry) => {
    locked();
    try { const id = vault.upsert(entry || {}); syncSoon(); return { id }; } catch (err) { return { error: String(err.message || err) }; }
  }, PAGE_ONLY);
  handle('vault:folders', () => { locked(); return vault.listFolders(); }, PAGE_ONLY);
  handle('vault:folderAdd', (_e, name) => { locked(); try { const f = vault.addFolder(name); syncSoon(); return { ok: true, name: f }; } catch (e) { return { error: e.message }; } }, PAGE_ONLY);
  handle('vault:folderRename', (_e, a, b) => { locked(); try { vault.renameFolder(String(a), String(b)); syncSoon(); return { ok: true }; } catch (e) { return { error: e.message }; } }, PAGE_ONLY);
  handle('vault:folderRemove', (_e, name) => { locked(); vault.removeFolder(String(name)); syncSoon(); return { ok: true }; }, PAGE_ONLY);
  handle('vault:move', (_e, ids, folder) => { locked(); if (Array.isArray(ids)) vault.moveEntries(ids.map(String), String(folder || '')); syncSoon(); return { ok: true }; }, PAGE_ONLY);
  handle('vault:delete', (_e, id) => { locked(); vault.remove(id); syncSoon(); return true; }, PAGE_ONLY);
  handle('vault:changeMaster', async (_e, oldPw, newPw) => {
    locked();
    if (!newPw) return { error: 'Enter a new password.' };
    if (!vault.changeMaster(String(oldPw), String(newPw))) { await sleep(500); return { error: 'The current master password is wrong.' }; }
    afterUnlock();
    syncSoon();
    return { ok: true };
  }, PAGE_ONLY);
  handle('vault:export', () => { locked(); return vault.exportCsv(); }, PAGE_ONLY);
  handle('vault:importFile', (_e, text) => {
    if (!vault.unlocked()) return { error: 'Unlock the vault first.' };
    touchVault();
    try { const p = parsePasswords(String(text)); const r = vault.importRows(p.rows); syncSoon(); return { format: p.format, ...r }; }
    catch (e) { return { error: e.message }; }
  }, PAGE_ONLY);
  handle('vault:import', (_e, rows) => { locked(); const r = Array.isArray(rows) ? vault.importRows(rows) : { added: 0, updated: 0, skipped: 0 }; syncSoon(); return r; }, PAGE_ONLY);

  // ---- vault: cards ----
  handle('vault:listCards', () => { if (!vault.unlocked()) return null; touchVault(); return vault.listCards(); }, PAGE_ONLY);
  handle('vault:saveCard', (_e, entry) => {
    locked();
    try { const id = vault.upsertCard(entry || {}); syncSoon(); return { id }; }
    catch (e) { return { error: e.message }; }
  }, PAGE_ONLY);
  handle('vault:revealCard', (_e, id) => { locked(); return vault.revealCard(id); }, PAGE_ONLY);
  handle('vault:deleteCard', (_e, id) => { locked(); vault.remove(id); syncSoon(); return true; }, PAGE_ONLY);

  // ---- vault: addresses ----
  handle('vault:listAddresses', () => { if (!vault.unlocked()) return null; touchVault(); return vault.listAddresses(); }, PAGE_ONLY);
  handle('vault:saveAddress', (_e, entry) => {
    locked();
    try { const id = vault.upsertAddress(entry || {}); syncSoon(); return { id }; }
    catch (e) { return { error: e.message }; }
  }, PAGE_ONLY);
  handle('vault:revealAddress', (_e, id) => { locked(); return vault.revealAddress(id); }, PAGE_ONLY);
  handle('vault:deleteAddress', (_e, id) => { locked(); vault.remove(id); syncSoon(); return true; }, PAGE_ONLY);

  // ---- profiles ----
  handle('profiles:list', () => { profiles.reload(); return { profiles: profiles.list(), current: profileId, askAtStart: profiles.askAtStart() }; }, PAGE_ONLY);
  handle('profiles:create', (_e, o) => { profiles.reload(); return profiles.create(o || {}); }, PAGE_ONLY);
  handle('profiles:update', (_e, id, o) => {
    const r = profiles.update(String(id), o || {});
    if (r.ok && String(id) === profileId && syncReady()) {
      const p = profiles.get(profileId);
      if (p) {
        if (o.color !== undefined) store.set('profileMeta.color', p.color);
        if (o.avatar !== undefined) store.set('profileMeta.avatar', p.avatar || '');
        syncSoon();
      }
    }
    return r;
  }, PAGE_ONLY);
  handle('profiles:setPassword', async (_e, id, newPw, oldPw) => {
    const r = profiles.setPassword(String(id), String(newPw || ''), String(oldPw || ''));
    if (r.error) await sleep(400);
    return r;
  }, PAGE_ONLY);
  handle('profiles:remove', async (_e, id, pw) => {
    if (id === profileId) return { error: 'Open another profile first; this one is in use.' };
    const r = profiles.remove(String(id), String(pw || ''));
    if (r.error) await sleep(400);
    return r;
  }, PAGE_ONLY);
  handle('profiles:open', (_e, id) => { if (!profiles.get(String(id))) return false; launchProfile(String(id), false); return true; }, PAGE_ONLY);
  handle('profiles:askAtStart', (_e, v) => { profiles.setAskAtStart(!!v); return true; }, PAGE_ONLY);
  on('profile:menu', () => profileMenu());

  // side panel (internal page)
  const PANEL_TABS = ['bookmarks', 'history', 'downloads', 'notes'];
  handle('panel:state', () => ({ tab: cur.panelTab, webPanels: S().panel.webPanels }), BOTH);
  on('panel:select', (_e, tabId) => {
    const ok = PANEL_TABS.includes(tabId) || (typeof tabId === 'string' && S().panel.webPanels.some((p) => 'web:' + p.id === tabId));
    if (ok) setPanel(true, tabId);
  }, BOTH);
  on('panel:close', () => setPanel(false), BOTH);
  on('panel:open', (_e, url, mode) => {
    if (typeof url !== 'string' || (!isWeb(url) && !url.startsWith('my://'))) return;
    const t = activeTab();
    if (mode === 'tab') newTab(url, { after: cur.activeId, private: t && t.private });
    else if (mode === 'background') newTab(url, { after: cur.activeId, background: true, private: t && t.private });
    else if (t) { t.wc.loadURL(url); t.wc.focus(); }
  }, BOTH);

  // notes
  handle('notes:list', () => [...notes].filter((x) => !x.recycleBin).sort((a, b) => b.updated - a.updated), BOTH);
  handle('notes:save', (_e, n) => {
    if (!n || typeof n !== 'object') return null;
    const title = String(n.title || '').slice(0, 200);
    const text = String(n.text || '').slice(0, 50000);
    let rec = typeof n.id === 'string' ? notes.find((x) => x.id === n.id) : null;
    if (!rec) { rec = { id: 'n' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), title, text, updated: 0 }; notes.push(rec); }
    rec.title = title; rec.text = text; rec.updated = Date.now();
    saveNotes();
    return rec;
  }, BOTH);
  handle('notes:delete', (_e, id) => {
    const n = notes.find((x) => x.id === id);
    if (n) { n.recycleBin = true; n.binTime = Date.now(); saveNotes(); }
    return true;
  }, BOTH);
  handle('notes:recycleBin', () => notes.filter((x) => x.recycleBin), BOTH);
  handle('notes:restoreFromBin', (_e, id) => { const n = notes.find((x) => x.id === id); if (n) { delete n.recycleBin; delete n.binTime; saveNotes(); } return true; }, BOTH);
  handle('notes:emptyBin', (_e, ids) => {
    if (Array.isArray(ids)) notes = notes.filter((x) => !ids.includes(x.id));
    else notes = notes.filter((x) => !x.recycleBin);
    saveNotes(); return true;
  }, BOTH);
  on('nav:go', (_e, text) => {
    const t = activeTab();
    if (t) { go(t, text); t.wc.focus(); }
  }, BOTH);
  on('nav:back', () => activeWc()?.navigationHistory.goBack());
  on('nav:forward', () => activeWc()?.navigationHistory.goForward());
  on('nav:reload', () => activeWc()?.reload());
  on('bookmark:toggle', () => toggleBookmark());
  on('shield:toggle', () => toggleSiteBlocking());
  on('overlay:close', () => hideOverlay());
  on('find:do', (_e, text, forward, next) => {
    const wc = activeWc();
    if (!wc) return;
    if (!text) { wc.stopFindInPage('clearSelection'); return; }
    wc.findInPage(String(text), { forward: forward !== false, findNext: !!next });
  });
  handle('palette:query', (_e, q) => paletteItems(String(q || '')));
  on('palette:run', (_e, item) => paletteRun(item));

  // from web pages
  ipcMain.on('gesture', (e, g) => {
    const t = tabByWc.get(e.sender.id);
    if (!t) return;
    clearTimeout(t._ctxFallback); t._ctxFallback = null;
    t.ctxParams = null;
    use(t.owner);
    if (!S().mouse.gestures || t.id !== cur.activeId) return;
    const id = GESTURES[g];
    if (id) runCommand(id);
  });
  ipcMain.on('ctxmenu', (e, x, y) => {
    const t = tabByWc.get(e.sender.id);
    if (!t) return;
    use(t.owner);
    if (t.id !== cur.activeId) return;
    clearTimeout(t._ctxFallback); t._ctxFallback = null;
    if (t.ctxParams) {
      showContextMenu(t, t.ctxParams);
      t.ctxParams = null;
    } else {
      e.sender.sendInputEvent({ type: 'contextMenu', x: Math.round(x), y: Math.round(y) });
    }
  });

  // internal my:// pages (and chrome)
  handle('settings:get', () => JSON.parse(JSON.stringify(S())), BOTH);
  handle('settings:set', (_e, p, v) => {
    try { applySetting(p, v); return true; } catch { return false; }
  }, BOTH);
  handle('themes:list', () => ({ presets: PRESETS, custom: S().appearance.customThemes, current: S().appearance.themeId }), BOTH);
  handle('commands:list', () => COMMANDS.map((c) => ({
    id: c.id, label: c.label, keys: effectiveKeys(c),
    custom: Object.prototype.hasOwnProperty.call(S().keyboard.overrides, c.id),
  })), BOTH);
  handle('library:get', () => ({
    bookmarks: bookmarks.filter((b) => !b.deleted && isWeb(b.url)).sort((a, b) => b.added - a.added),
    history: history.slice(-500).reverse(),
    remoteTabs,
    syncStatus,
  }), BOTH);
  handle('history:query', (_e, opts) => {
    const o = opts || {};
    const terms = String(o.q || '').toLowerCase().split(/\s+/).filter(Boolean);
    const from = Number(o.from) || 0;
    const to = Number(o.to) || Infinity;
    const limit = Math.min(Math.max(Number(o.limit) || 300, 1), 1000);
    const out = [];
    for (let i = history.length - 1; i >= 0 && out.length < limit; i--) {
      const h = history[i];
      if (h.time < from || h.time > to) continue;
      if (terms.length) {
        const text = ((h.title || '') + ' ' + h.url).toLowerCase();
        if (!terms.every((t) => text.includes(t))) continue;
      }
      out.push({ url: h.url, title: h.title, time: h.time });
    }
    return { items: out, total: history.length };
  }, BOTH);
  // remove history entries: { urls: [...] } removes every visit of those pages, { from, to } removes a time range
  handle('history:delete', (_e, spec) => {
    const sp = spec || {};
    const before = history.length;
    if (Array.isArray(sp.urls)) {
      const set = new Set(sp.urls.filter((u) => typeof u === 'string'));
      history = history.filter((h) => !set.has(h.url));
    } else if (Number.isFinite(Number(sp.from)) || Number.isFinite(Number(sp.to))) {
      const from = Number(sp.from) || 0;
      const to = Number.isFinite(Number(sp.to)) ? Number(sp.to) : Infinity;
      history = history.filter((h) => h.time < from || h.time > to);
    }
    save('history.json', history);
    return before - history.length;
  }, BOTH);
  handle('import:detect', () => {
    detectedSources.clear();
    return detectBrowsers().map((src, i) => {
      const id = 's' + i;
      detectedSources.set(id, src);
      let count = null;
      try { count = parseBookmarkFile(fs.readFileSync(src.file)).items.length; } catch { /* unreadable */ }
      return { id, name: src.name, count };
    });
  }, BOTH);
  handle('import:run', (_e, id) => {
    const src = detectedSources.get(id);
    if (!src) return { error: 'Unknown source. Press "Look again".' };
    try { const r = parseBookmarkFile(fs.readFileSync(src.file)); return { format: r.format, ...importBookmarks(r.items) }; }
    catch (e) { return { error: 'Could not read that browser’s bookmarks: ' + e.message }; }
  }, BOTH);
  handle('import:file', (_e, name, bytes) => {
    try { const r = parseBookmarkFile(Buffer.from(bytes)); return { format: r.format, ...importBookmarks(r.items) }; }
    catch (e) { return { error: 'Could not read that file: ' + e.message }; }
  }, BOTH);
  handle('sync:test', () => testSync(), BOTH);
  handle('sync:login', async (_e, o) => {
    const c = S().sync;
    o = o || {};
    if (!c.server) return { error: 'Enter the server address first.' };
    const base = c.server.replace(/\/+$/, '');
    try {
      const res = await fetch(base + '/api/auth/device-login', {
        method: 'POST', headers: { 'content-type': 'application/json' }, signal: AbortSignal.timeout(15000),
        body: JSON.stringify({ username: String(o.username || ''), password: String(o.password || ''), totp: String(o.totp || ''), profile: o.profile || '', device: c.device || os.hostname() }),
      });
      let data = {};
      try { data = await res.json(); } catch { /* not JSON */ }
      if (res.status === 404) return { error: 'This server is an older version without account sign-in. Update the server, or use an API key.' };
      if (!res.ok) return { error: data.error || ('Sign-in failed (HTTP ' + res.status + ')'), needTotp: !!data.needTotp };
      if (data.needProfile) return { needProfile: true, profiles: data.profiles };
      applySetting('sync.token', data.token);
      applySetting('sync.account', { user: String(o.username), profile: data.profile.name });
      syncSoon();
      return { ok: true, profile: data.profile };
    } catch (e) { return { error: 'Cannot reach the server: ' + e.message }; }
  }, BOTH);
  handle('sync:logout', () => { applySetting('sync.token', ''); applySetting('sync.account', {}); return true; }, BOTH);
  handle('icon:apply', (_e, kind) => applyIcon(String(kind)), BOTH);
  handle('bookmarks:all', () => ({
    bookmarks: bookmarks.filter((b) => !b.deleted && !b.recycleBin && isWeb(b.url)).map((b) => ({ url: b.url, title: b.title || '', folder: b.folder || '', added: b.added || 0 })),
    folders: allFolderPaths(),
  }), BOTH);
  // add or edit: { url (existing, or empty for a new one), newUrl, title, folder }
  handle('bookmarks:save', (_e, o) => {
    o = o || {};
    const target = String(o.newUrl || o.url || '').trim();
    const finalUrl = /^[a-z][a-z0-9+.-]*:\/\//i.test(target) ? target : 'https://' + target;
    if (!isWeb(finalUrl) || finalUrl.length > 8000) return { error: 'Enter a web address (http or https).' };
    const now = Date.now();
    const folder = normFolder(o.folder);
    const old = o.url ? bookmarks.find((b) => b.url === o.url && !b.deleted) : null;
    const clash = bookmarks.find((b) => b.url === finalUrl && !b.deleted && b !== old);
    if (clash) return { error: 'That address is already bookmarked.' };
    let rec = bookmarks.find((b) => b.url === finalUrl);
    if (old && old.url !== finalUrl) { old.deleted = true; old.updated = now; }
    if (!rec) { rec = { url: finalUrl, added: old ? old.added : now }; bookmarks.push(rec); }
    Object.assign(rec, { title: String(o.title || '').trim().slice(0, 900) || finalUrl, folder, updated: now, deleted: false });
    if (folder && !bookmarkFolders.includes(folder)) { bookmarkFolders.push(folder); saveFolders(); }
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  handle('bookmarks:move', (_e, urls, folder) => {
    const f = normFolder(folder), set = new Set(Array.isArray(urls) ? urls : []), now = Date.now();
    for (const b of bookmarks) if (!b.deleted && set.has(b.url)) { b.folder = f; b.updated = now; }
    if (f && !bookmarkFolders.includes(f)) { bookmarkFolders.push(f); saveFolders(); }
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  handle('bookmarks:remove', (_e, urls) => {
    const set = new Set(Array.isArray(urls) ? urls : []), now = Date.now();
    for (const b of bookmarks) if (!b.deleted && !b.recycleBin && set.has(b.url)) { b.recycleBin = true; b.binTime = now; }
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  handle('bookmarks:recycleBin', () => ({
    bookmarks: bookmarks.filter((b) => b.recycleBin && !b.deleted && isWeb(b.url)).map((b) => ({ url: b.url, title: b.title || '', folder: b.folder || '', added: b.added || 0, binTime: b.binTime || 0 })),
  }), BOTH);
  handle('bookmarks:restoreFromBin', (_e, urls) => {
    const set = new Set(Array.isArray(urls) ? urls : []);
    for (const b of bookmarks) if (b.recycleBin && set.has(b.url)) { delete b.recycleBin; delete b.binTime; }
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  handle('bookmarks:emptyBin', (_e, urls) => {
    const now = Date.now();
    const set = urls ? new Set(Array.isArray(urls) ? urls : []) : null;
    for (const b of bookmarks) {
      if (!b.recycleBin) continue;
      if (set && !set.has(b.url)) continue;
      b.deleted = true; b.updated = now; delete b.recycleBin; delete b.binTime;
    }
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  handle('bookmarks:folderAdd', (_e, p) => {
    const f = normFolder(p);
    if (!f) return { error: 'Enter a folder name.' };
    if (!bookmarkFolders.includes(f)) { bookmarkFolders.push(f); saveFolders(); }
    return { ok: true, path: f };
  }, BOTH);
  handle('bookmarks:folderRename', (_e, oldPath, newPath) => {
    const from = normFolder(oldPath), to = normFolder(newPath), now = Date.now();
    if (!from || !to) return { error: 'Enter a folder name.' };
    if (from === to) return { ok: true };
    if (to === from || to.startsWith(from + SEP)) return { error: 'A folder cannot be moved into itself.' };
    const swap = (f) => (inFolder(f, from) ? to + f.slice(from.length) : f);
    for (const b of bookmarks) if (!b.deleted && b.folder && inFolder(b.folder, from)) { b.folder = swap(b.folder); b.updated = now; }
    bookmarkFolders = [...new Set([...bookmarkFolders.map(swap), to])];
    saveFolders();
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  // mode 'keep': the bookmarks move up to the parent folder; 'delete': they go to the recycle bin
  handle('bookmarks:folderDelete', (_e, p, mode) => {
    const path = normFolder(p), now = Date.now();
    if (!path) return { error: 'Choose a folder.' };
    const parent = path.includes(SEP) ? path.slice(0, path.lastIndexOf(SEP)) : '';
    for (const b of bookmarks) {
      if (b.deleted || !b.folder || !inFolder(b.folder, path)) continue;
      if (mode === 'delete') { b.recycleBin = true; b.binTime = now; }
      else {
        const rel = b.folder === path ? '' : b.folder.slice(path.length + SEP.length);
        b.folder = [parent, rel].filter(Boolean).join(SEP);
      }
      b.updated = now;
    }
    bookmarkFolders = bookmarkFolders.filter((f) => !inFolder(f, path));
    saveFolders();
    touchBookmarks();
    return { ok: true };
  }, BOTH);
  handle('bookmark:remove', (_e, url) => {
    const b = bookmarks.find((x) => x.url === url);
    if (b) { b.recycleBin = true; b.binTime = Date.now(); save('bookmarks.json', bookmarks); pushState(); syncSoon(); }
    return true;
  }, BOTH);
  // bookmark popup: save (add or update folder) and add to speed dial
  handle('bookmark:popup-save', (_e, url, title, folder) => {
    const now = Date.now();
    const f = typeof folder === 'string' ? folder.trim() : '';
    let b = bookmarks.find((x) => x.url === url);
    if (b && b.recycleBin) { delete b.recycleBin; delete b.binTime; b.deleted = false; b.updated = now; b.title = title || b.title; b.folder = f; }
    else if (b && !b.deleted) { b.folder = f; b.updated = now; b.title = title || b.title; }
    else if (b) { b.deleted = false; b.updated = now; b.title = title || b.title; b.folder = f; }
    else bookmarks.push({ url, title: String(title || url).slice(0, 300), folder: f, added: now, updated: now, deleted: false });
    if (f && !bookmarkFolders.includes(f)) { bookmarkFolders.push(f); saveFolders(); }
    save('bookmarks.json', bookmarks); pushState(); syncSoon();
    return { ok: true };
  }, BOTH);
  handle('bookmark:popup-speeddial', (_e, url, title) => {
    const added = addSpeedDial(String(url), String(title || ''));
    if (added) { const t = activeTab(); if (t) maybeThumb(t, true); }
    return { ok: true, added };
  }, BOTH);
  handle('data:clear', (_e, what) => (['history', 'cookies', 'cache', 'all'].includes(what) ? clearData(what) : false), BOTH);
  handle('downloads:list', () => downloads.map((d) => ({ ...d })), BOTH);
  handle('downloads:action', (_e, id, action) => downloadAction(id, action), BOTH);
  handle('wallpaper:pick', async () => {
    const r = await dialog.showOpenDialog({ title: 'Choose a wallpaper', properties: ['openFile'], filters: [{ name: 'Pictures', extensions: ['jpg', 'jpeg', 'png', 'webp'] }] });
    if (r.canceled || !r.filePaths[0]) return { canceled: true };
    try {
      const src = r.filePaths[0];
      if (fs.statSync(src).size > 30 * 1024 * 1024) return { error: 'That picture is larger than 30 MB.' };
      const b = fs.readFileSync(src);
      if (!sniffMime(b)) return { error: 'That is not a JPEG, PNG or WebP picture.' };
      fs.copyFileSync(src, file('wallpaper-custom'));
      applySetting('startPage.wallpaper', 'custom');
      applySetting('startPage.wallpaperRev', Date.now());
      return { ok: true };
    } catch (e) { return { error: e.message }; }
  }, PAGE_ONLY);
  handle('thumb:refresh', (_e, urls) => {
    if (!Array.isArray(urls)) return false;
    const valid = urls.filter((u) => typeof u === 'string' && isWeb(u)).slice(0, 15);
    // Delete cached files so the protocol handler returns 404 immediately
    for (const url of valid) {
      const key = thumbKey(url);
      if (!key) continue;
      thumbTimes.delete(key);
      const p = thumbFile(key);
      try { if (fs.existsSync(p)) fs.unlinkSync(p); } catch {}
    }
    // Background captures — 2 at a time so the machine stays responsive
    setImmediate(async () => {
      for (let i = 0; i < valid.length; i += 2)
        await Promise.all(valid.slice(i, i + 2).map(captureThumbBg));
    });
    return true;
  }, { chrome: false, page: true });
  handle('sync:now', async () => { await syncAll(); return syncStatus; }, BOTH);
  handle('sync:status', () => syncStatus, BOTH);
  handle('cert:allow', (_e, host) => {
    if (typeof host !== 'string' || !/^[\w.:-]+$/.test(host)) return false;
    const list = S().privacy.certExceptions.slice();
    if (!list.includes(host)) list.push(host);
    applySetting('privacy.certExceptions', list);
    return true;
  }, BOTH);

  // --- profiles: default, open-last, reset ---
  handle('profiles:setDefault', (_e, id) => profiles.setDefaultProfile(String(id || 'default')), BOTH);
  handle('profiles:setOpenLast', (_e, v) => { profiles.setOpenLastProfile(!!v); return { ok: true }; }, BOTH);
  handle('profiles:getPrefs', () => ({
    defaultProfile: profiles.getDefaultProfile(),
    openLastProfile: profiles.getOpenLastProfile(),
    lastProfile: profiles.getLastProfile(),
    current: profileId,
  }), BOTH);
  handle('profiles:resetData', async (_e, id) => {
    const target = String(id || profileId);
    if (target !== profileId) {
      // Other profiles are not running, so we can delete directly.
      return profiles.resetProfile(target);
    }
    // Current profile: delete our own data files in-place.
    // Chromium's LevelDB files may be locked on Windows — clearStorageData clears them in-process.
    setImmediate(async () => {
      const dir = profiles.dirFor(profileId);
      for (const f of ['bookmarks.json', 'history.json', 'notes.json', 'bookmark-folders.json',
                        'settings.json', 'vault.json', 'session.json', 'syncstate.json',
                        'vault-remember.key', 'vault-device.key', 'dialicons']) {
        try { fs.rmSync(path.join(dir, f), { recursive: true, force: true }); } catch {}
      }
      store = createStore(file('settings.json'));
      vault = createVault(file('vault.json'));
      bookmarks = []; bookmarkFolders = []; history = []; notes = [];
      syncState = { bRev: 0, hRev: 0, hPushed: 0 };
      syncStatus = 'Sync not configured';
      clearTimeout(syncTimer); syncTimer = null;
      try { await session.defaultSession.clearStorageData(); } catch {}
      try { await session.defaultSession.clearCache(); } catch {}
      resetInProgress = true;
      for (const w of [...windows]) { try { w.win.close(); } catch {} }
      resetInProgress = false;
      createWindow({ first: true });
    });
    return { ok: true };
  }, BOTH);
  handle('blocker:status', () => blocker.getStatus(), BOTH);
  handle('blocker:update', async () => { const r = await blocker.download(); return r; }, BOTH);
  handle('app:check-updates', async () => { await checkForUpdates(true); }, BOTH);
  handle('app:install-update', async () => {
    const au = getAutoUpdater();
    if (au && _euDownloaded) {
      au.quitAndInstall(false, true);
    } else if (!au) {
      dialog.showMessageBox({ type: 'info', title: 'mbrowser', message: 'Running from source — updates apply to packaged builds only.' });
    }
  }, BOTH);
  handle('app:build-number', () => BUILD_NUMBER, BOTH);
  handle('app:update-info', async () => {
    if (!getAutoUpdater()) return { currentBuild: BUILD_NUMBER, releaseUrl: RELEASE_URL };
    if (_euInfo) return euToInfo(_euInfo, !_euDownloaded);
    return { upToDate: true, build: BUILD_NUMBER };
  }, BOTH);
  handle('app:auto-install', async () => {
    const au = getAutoUpdater();
    if (au && _euDownloaded) {
      au.quitAndInstall(false, true);
    } else {
      newTab(RELEASE_URL);
    }
    return { ok: true };
  }, BOTH);

  handle('app:resetAll', async () => {
    if (wizardActive) return { error: 'Setup wizard is already running.' };
    // Send the response first, then do the reset asynchronously so the renderer
    // can close its modal before its window is destroyed.
    setImmediate(async () => {
      const baseDir = profiles.dirFor('default');
      const appDataDir = path.dirname(baseDir);
      // Delete all extra profile dirs (separate processes — not locked)
      for (const p of profiles.list()) {
        if (p.id !== 'default') {
          try { fs.rmSync(profiles.dirFor(p.id), { recursive: true, force: true }); } catch {}
        }
      }
      // Sweep old-style scattered files from parent appData dir
      try {
        for (const entry of fs.readdirSync(appDataDir)) {
          if (entry.startsWith('mbrowser-profile-') || entry === 'mbrowser-profiles.json') {
            try { fs.rmSync(path.join(appDataDir, entry), { recursive: true, force: true }); } catch {}
          }
        }
      } catch {}
      // Delete our own data files from the active profile dir
      // (Chromium's LevelDB files may be locked on Windows — clearStorageData clears them in-process)
      for (const f of ['bookmarks.json', 'history.json', 'notes.json', 'bookmark-folders.json',
                        'settings.json', 'vault.json', 'session.json', 'syncstate.json',
                        'vault-remember.key', 'vault-device.key', 'dialicons']) {
        try { fs.rmSync(path.join(baseDir, f), { recursive: true, force: true }); } catch {}
      }
      // Reset profiles registry
      profiles.resetAll();
      // Reset in-memory state
      store = createStore(file('settings.json'));
      vault = createVault(file('vault.json'));
      bookmarks = []; bookmarkFolders = []; history = []; notes = [];
      syncState = { bRev: 0, hRev: 0, hPushed: 0 };
      syncStatus = 'Sync not configured';
      clearTimeout(syncTimer); syncTimer = null;
      // Clear Chromium session data in-process
      try { await session.defaultSession.clearStorageData(); } catch {}
      try { await session.defaultSession.clearCache(); } catch {}
      try { await session.fromPartition('private').clearStorageData(); } catch {}
      // Close all browser windows without quitting the app
      resetInProgress = true;
      for (const w of [...windows]) { try { w.win.close(); } catch {} }
      // Show wizard, then open a fresh browser window (or launch the new default profile)
      let wizResult;
      try { wizResult = await showWizard(); } catch {}
      resetInProgress = false;
      if (wizResult === 'cancelled') { app.quit(); return; }
      const defId = profiles.getDefaultProfile();
      if (defId !== profileId && profiles.get(defId)) {
        launchProfile(defId, false);
      } else {
        createWindow({ first: true });
      }
    });
    return { ok: true };
  }, BOTH);
  handle('app:rerunWizard', async () => {
    const defaultBefore = profiles.getDefaultProfile();
    profiles.clearSetupDone();
    await showWizard();
    const defaultAfter = profiles.getDefaultProfile();
    if (defaultAfter !== defaultBefore && profiles.get(defaultAfter)) {
      launchProfile(defaultAfter, false);
    }
    return { ok: true };
  }, PAGE_ONLY);
  handle('app:newProfileWizard', async () => {
    if (wizardActive) return { error: 'Setup wizard is already running.' };
    wizardNewProfile = true;
    profiles.clearSetupDone();
    await showWizard();
    wizardNewProfile = false;
    pendingNewProfileSync = null;
    const defId = profiles.getDefaultProfile();
    if (defId !== profileId && profiles.get(defId)) launchProfile(defId, false);
    return { ok: true };
  }, PAGE_ONLY);

  handle('server:ping', async (_e, url) => {
    if (typeof url !== 'string' || !url.startsWith('http')) return { ok: false };
    try {
      const r = await net.fetch(url + '/health', { method: 'GET' });
      return { ok: r.ok, status: r.status };
    } catch { return { ok: false }; }
  }, PAGE_ONLY);
}

// ---------- internal pages ----------
const PAGES = { bookmarks: 'bookmarks.html', profiles: 'profiles.html', import: 'import.html', passwords: 'passwords.html', history: 'history.html', panel: 'panel.html', error: 'error.html', start: 'start.html', settings: 'settings.html', library: 'library.html', downloads: 'downloads.html', welcome: 'welcome.html', notes: 'notes.html', 'server-admin': 'server-admin.html' };

const CORS = { 'access-control-allow-origin': '*' };

// ---------- start page wallpapers and site icons ----------
const WALLPAPER_IDS = ['grey', 'aurora', 'dusk', 'ocean', 'dunes', 'mist', 'forest', 'ember', 'orchid', 'slate', 'nebula', 'peaks', 'paper'];
const sniffMime = (b) => {
  if (b.length > 4 && b[0] === 0xff && b[1] === 0xd8) return 'image/jpeg';
  if (b.length > 4 && b[0] === 0x89 && b[1] === 0x50) return 'image/png';
  if (b.length > 12 && b.toString('latin1', 0, 4) === 'RIFF' && b.toString('latin1', 8, 12) === 'WEBP') return 'image/webp';
  if (b.length > 4 && b[0] === 0 && b[1] === 0 && b[2] === 1 && b[3] === 0) return 'image/x-icon';
  if (b.length > 3 && b.toString('latin1', 0, 3) === 'GIF') return 'image/gif';
  return null;
};

// the hosts of the user's own speed dials: the only ones the icon fetcher may contact
function dialHosts() {
  const hosts = new Set();
  const walk = (items) => { for (const d of items || []) { if (d.type === 'folder') walk(d.items); else { try { hosts.add(new URL(d.url).host); } catch { /* skip */ } } } };
  for (const g of S().startPage.groups || []) walk(g.dials);
  return hosts;
}

const iconJobs = new Map();
async function fetchDialIcon(pageUrl) {
  const u = new URL(pageUrl);
  const dir = file('dialicons'); fs.mkdirSync(dir, { recursive: true });
  const cache = path.join(dir, u.host.replace(/[^a-z0-9.-]/gi, '_') + '.bin');
  if (fs.existsSync(cache)) return fs.readFileSync(cache);
  if (fs.existsSync(cache + '.none') && Date.now() - fs.statSync(cache + '.none').mtimeMs < 7 * 864e5) return null;
  const get = async (url, max) => {
    try {
      const r = await fetch(url, { headers: { 'user-agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36' }, signal: AbortSignal.timeout(7000), redirect: 'follow' });
      if (!r.ok) return null;
      return Buffer.from(await r.arrayBuffer()).subarray(0, max);
    } catch { return null; }
  };
  const origin = u.origin;
  const cands = [];
  const html = await get(origin + '/', 300000);
  if (html) {
    for (const m of html.toString('utf8').matchAll(/<link\s[^>]*>/gi)) {
      const tag = m[0];
      const rel = (/rel\s*=\s*["']([^"']+)["']/i.exec(tag) || [])[1];
      const href = (/href\s*=\s*["']([^"']+)["']/i.exec(tag) || [])[1];
      if (!rel || !href || !/icon/i.test(rel)) continue;
      const size = +((/sizes\s*=\s*["'](\d+)x/i.exec(tag) || [])[1] || 0);
      let abs; try { abs = new URL(href, origin + '/').href; } catch { continue; }
      if (/\.svg(\?|$)/i.test(abs)) continue;
      cands.push([abs, /apple-touch/i.test(rel) ? 1000 + size : size]);
    }
  }
  cands.push([origin + '/apple-touch-icon.png', 900], [origin + '/apple-touch-icon-precomposed.png', 890], [origin + '/favicon.ico', 1]);
  const seen = new Set();
  let best = null, bestW = 0;
  for (const [url] of cands.sort((a, b) => b[1] - a[1])) {
    if (seen.has(url) || seen.size >= 5) continue;
    seen.add(url);
    const buf = await get(url, 600000);
    if (!buf || !sniffMime(buf)) continue;
    let w = 0;
    try { w = nativeImage.createFromBuffer(buf).getSize().width; } catch { /* unknown size */ }
    if (!w) w = 32;
    if (w > bestW) { best = buf; bestW = w; }
    if (bestW >= 120) break;
  }
  if (best) { fs.writeFileSync(cache, best); return best; }
  fs.writeFileSync(cache + '.none', '');
  return null;
}

function handleMy(req) {
  const u = new URL(req.url);
  if (u.host === 'wallpaper') {
    const id = u.pathname.replace(/^\//, '');
    const p = id === 'custom' ? file('wallpaper-custom') : (WALLPAPER_IDS.includes(id) ? path.join(ASSETS, 'wallpapers', id + '.jpg') : null);
    if (p && fs.existsSync(p)) {
      const b = fs.readFileSync(p);
      return new Response(b, { headers: { 'content-type': sniffMime(b) || 'image/jpeg', 'cache-control': 'no-cache', ...CORS } });
    }
    return new Response('', { status: 404 });
  }
  if (u.host === 'dialicon') {
    const target = u.searchParams.get('u') || '';
    let host = '';
    try { host = new URL(target).host; } catch { /* bad address */ }
    if (!host || !dialHosts().has(host)) return new Response('', { status: 404 });
    if (!iconJobs.has(host)) iconJobs.set(host, fetchDialIcon(target).catch(() => null).finally(() => setTimeout(() => iconJobs.delete(host), 5000)));
    return iconJobs.get(host).then((b) => b
      ? new Response(b, { headers: { 'content-type': sniffMime(b) || 'image/png', 'cache-control': 'max-age=3600', ...CORS } })
      : new Response('', { status: 404 }));
  }
  if (u.host === 'assets') {
    if (u.pathname === '/theme.css') {
      return new Response(themeCss(), { headers: { 'content-type': 'text/css', ...CORS } });
    }
    if (u.pathname === '/logo.png') {
      // the start page shows the chosen icon; white versions would vanish on light themes, so use blue there
      let kind = store ? S().appearance.icon : 'classic';
      if (store && !themeVars().dark) kind = { white: 'blue', 'white-m': 'blue-m' }[kind] || kind;
      return new Response(fs.readFileSync(iconFile(kind, true)), { headers: { 'content-type': 'image/png', 'cache-control': 'no-store', ...CORS } });
    }
    const im = /^\/icon\/([a-z-]+)\.png$/.exec(u.pathname);
    if (im) return new Response(fs.readFileSync(iconFile(im[1], true)), { headers: { 'content-type': 'image/png', ...CORS } });
    if (u.pathname === '/common.css') {
      return new Response(fs.readFileSync(path.join(__dirname, 'pages', 'common.css')), { headers: { 'content-type': 'text/css', ...CORS } });
    }
    return new Response('Not found', { status: 404 });
  }
  if (u.host === 'thumb') {
    const key = thumbKey(u.searchParams.get('u') || '');
    const p = key && thumbFile(key);
    if (p && fs.existsSync(p)) {
      return new Response(fs.readFileSync(p), { headers: { 'content-type': 'image/jpeg', 'cache-control': 'no-store', ...CORS } });
    }
    return new Response('', { status: 404 });
  }
  const f = PAGES[u.host];
  if (!f) return new Response('Not found', { status: 404 });
  return new Response(fs.readFileSync(path.join(__dirname, 'pages', f)), {
    headers: { 'content-type': 'text/html; charset=utf-8', ...CORS },
  });
}

// What the browser tells websites it is (some sites show "unsupported browser" banners to anything but Chrome, Firefox or Safari).
const uaSessions = new Set();
const CHROME_VER = process.versions.chrome || '130.0.0.0';
const CHROME_MAJOR = CHROME_VER.split('.')[0];

// Returns the sec-ch-ua-platform-version Chrome sends for this OS.
// Linux: kernel version (e.g. "6.6.0") — Chrome reveals this via the low-entropy hint.
// Windows: "10.0.0" for Win10, "15.0.0" for Win11 (build >= 22000).
function chromePlatformVersion() {
  if (process.platform === 'linux') {
    const p = os.release().split('.');
    return `${p[0] || '0'}.${p[1] || '0'}.${(p[2] || '0').split('-')[0]}`;
  }
  if (process.platform !== 'win32') return '';
  const build = parseInt((os.release().split('.')[2] || '0'), 10);
  return build >= 22000 ? '15.0.0' : '10.0.0';
}
function uaForMode(mode) {
  const win = process.platform === 'win32';
  if (mode === 'firefox') return win ? 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0' : 'Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0';
  if (mode === 'safari') return 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15';
  if (mode === 'none') return app.userAgentFallback;
  return `Mozilla/5.0 ${win ? '(Windows NT 10.0; Win64; x64)' : '(X11; Linux x86_64)'} AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VER} Safari/537.36`;
}

function setupSession(ses) {
  // Set a plain Chrome UA for this session — strips Electron/mbrowser from navigator.userAgent
  // and from navigator.userAgentData so Google sign-in does not refuse the browser.
  const uaPlat = process.platform === 'win32' ? '(Windows NT 10.0; Win64; x64)' : '(X11; Linux x86_64)';
  const chromeUA = `Mozilla/5.0 ${uaPlat} AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VER} Safari/537.36`;
  uaSessions.add(ses);
  ses.setUserAgent(uaForMode(S().general.browserId));

  ses.protocol.handle('my', handleMy);
  ses.on('will-download', onWillDownload);
  // Without a permission handler Electron auto-denies everything, making
  // navigator.permissions.query return 'denied' for all permissions — a strong
  // embedded-browser signal that Google's _tp module flags. Use Chromium's normal
  // "ask the user" behaviour instead.
  ses.setPermissionRequestHandler((wc, permission, callback) => {
    const sensitive = ['notifications', 'geolocation', 'camera', 'microphone', 'fullscreen',
      'media', 'mediaKeySystem', 'midi', 'midiSysex', 'pointerLock', 'openExternal'];
    if (sensitive.includes(permission)) {
      // Show OS/browser prompt via Electron dialog in a future version; for now grant harmless
      // ones and prompt-block sensitive ones (user can allow in site settings later).
      callback(permission === 'fullscreen' || permission === 'openExternal');
    } else {
      callback(true);
    }
  });
  ses.setPermissionCheckHandler((wc, permission) => {
    // Return null so Chromium uses its built-in check (same as a standard browser).
    // Returning false for everything makes navigator.permissions.query report 'denied'.
    return null;
  });
  // remember certificate details per host for the address-bar security popup (verification itself is unchanged)
  ses.setCertificateVerifyProc((request, callback) => {
    try {
      const c = request.certificate;
      certInfo.set(request.hostname, {
        subject: c.subjectName, issuer: c.issuerName, validFrom: c.validStart, validTo: c.validExpiry,
        fingerprint: c.fingerprint, result: request.verificationResult,
      });
    } catch { /* details are optional */ }
    callback(-3); // -3 = use Chromium's normal verification result
  });
  blocker.attach(ses, (wcId) => { blockedCounts.set(wcId, (blockedCounts.get(wcId) || 0) + 1); pushSoon(); });
  // Combined header handler: DNT (all requests) + UA client-hint fix (Google domains).
  // Registered after blocker.attach so this wins the single-listener slot; it replicas
  // the blocker's DNT logic so that functionality is not lost.
  const uaPlatHint = process.platform === 'win32' ? '"Windows"' : '"Linux"';
  const googlePattern = /^https?:\/\/([^/]*\.)?(google\.[a-z]{2,}|gmail\.com|googleapis\.com|gstatic\.com|googleusercontent\.com)\//i;
  const uaPlatVerHint = JSON.stringify(chromePlatformVersion());
  ses.webRequest.onBeforeSendHeaders({ urls: ['<all_urls>'] }, (details, callback) => {
    const h = details.requestHeaders;
    // DNT for all requests (mirrors blocker's removed handler)
    if (S().privacy?.doNotTrack) { h['DNT'] = '1'; h['Sec-GPC'] = '1'; }
    // Google/Gmail: always masquerade as Chrome on Google domains so Gmail and
    // sign-in work regardless of the user's browserId setting. The JS-side
    // Emulation.setUserAgentOverride already reports Chrome; HTTP headers must match.
    if (S().general.browserId === 'chrome' && googlePattern.test(details.url)) {
      // Delete both common capitalisations so the Electron brand doesn't leak alongside ours.
      ['Sec-CH-UA', 'sec-ch-ua', 'Sec-CH-UA-Mobile', 'sec-ch-ua-mobile',
       'Sec-CH-UA-Platform', 'sec-ch-ua-platform', 'Sec-CH-UA-Platform-Version',
       'sec-ch-ua-platform-version', 'Sec-CH-UA-Full-Version-List',
       'sec-ch-ua-full-version-list', 'Sec-CH-UA-Arch', 'sec-ch-ua-arch',
       'Sec-CH-UA-Bitness', 'sec-ch-ua-bitness', 'Sec-CH-UA-WoW64', 'sec-ch-ua-wow64',
       'Sec-CH-UA-Model', 'sec-ch-ua-model', 'User-Agent', 'user-agent'].forEach((k) => delete h[k]);
      h['user-agent'] = chromeUA;
      h['sec-ch-ua'] = `"Chromium";v="${CHROME_MAJOR}", "Google Chrome";v="${CHROME_MAJOR}", "Not?A_Brand";v="8"`;
      h['sec-ch-ua-mobile'] = '?0';
      h['sec-ch-ua-platform'] = uaPlatHint;
      h['sec-ch-ua-platform-version'] = uaPlatVerHint;
      h['sec-ch-ua-full-version-list'] = `"Chromium";v="${CHROME_VER}", "Google Chrome";v="${CHROME_VER}", "Not?A_Brand";v="8.0.0.0"`;
      h['sec-ch-ua-arch'] = '"x86"';
      h['sec-ch-ua-bitness'] = '"64"';
      h['sec-ch-ua-model'] = '""';
      if (process.platform === 'win32') h['sec-ch-ua-wow64'] = '?0';
    }
    callback({ requestHeaders: h });
  });
}

// ---------- speed dial thumbnails ----------
const thumbTimes = new Map();

function thumbKey(url) {
  try {
    const u = new URL(url);
    if (!/^https?:$/.test(u.protocol)) return null;
    const norm = u.host.replace(/^www\./, '') + u.pathname.replace(/\/+$/, '') + u.search;
    return crypto.createHash('sha1').update(norm).digest('hex').slice(0, 20);
  } catch { return null; }
}
const thumbFile = (key) => path.join(file('thumbs'), key + '.jpg');

// While a speed dial's page is the visible tab, grab a small screenshot of it.
function maybeThumb(tab, force = false) {
  if (tab.private || !store) return;
  let url;
  try { url = tab.wc.getURL(); } catch { return; }
  const key = thumbKey(url);
  if (!key) return;
  const keys = new Set(S().startPage.groups.flatMap((g) => g.dials.map((d) => thumbKey(d.url))));
  if (!keys.has(key)) return;
  const p = thumbFile(key);
  const last = thumbTimes.get(key) || (fs.existsSync(p) ? fs.statSync(p).mtimeMs : 0);
  if (!force && Date.now() - last < 6 * 3600 * 1000) return;
  thumbTimes.set(key, Date.now());
  setTimeout(async () => {
    try {
      if (tab.owner.activeId !== tab.id || thumbKey(tab.wc.getURL()) !== key) { thumbTimes.delete(key); return; }
      const img = await tab.wc.capturePage();
      if (img.isEmpty()) { thumbTimes.delete(key); return; }
      fs.mkdirSync(file('thumbs'), { recursive: true });
      fs.writeFileSync(p, img.resize({ width: 480 }).toJPEG(80));
    } catch { thumbTimes.delete(key); }
  }, 2500);
}

// Background thumbnail capture: prefer an already-open tab, fall back to hidden window.
function captureThumbBg(url) {
  const key = thumbKey(url);
  if (!key) return Promise.resolve();
  const p = thumbFile(key);

  // If the URL is already open in a visible tab, capture from it directly (best quality).
  for (const w of windows) {
    for (const id of w.order) {
      const t = tabById.get(id);
      if (!t) continue;
      let tabUrl;
      try { tabUrl = t.wc.getURL(); } catch { continue; }
      if (thumbKey(tabUrl) !== key) continue;
      return new Promise((resolve) => {
        setTimeout(async () => {
          try {
            const img = await t.wc.capturePage();
            if (!img.isEmpty()) {
              fs.mkdirSync(file('thumbs'), { recursive: true });
              fs.writeFileSync(p, img.resize({ width: 480 }).toJPEG(80));
              thumbTimes.set(key, Date.now());
            }
          } catch {}
          resolve();
        }, 500);
      });
    }
  }

  // No open tab — load in a hidden window, wait for rendering to settle.
  return new Promise((resolve) => {
    let win, settled = false;
    const done = () => {
      if (settled) return; settled = true;
      try { if (win && !win.isDestroyed()) win.destroy(); } catch {}
      resolve();
    };
    const bail = setTimeout(done, 25000);
    let captureTimer = null;
    const capture = () => {
      // Debounce: re-arm on each did-stop-loading (redirects fire it multiple times)
      clearTimeout(captureTimer);
      captureTimer = setTimeout(async () => {
        try {
          const img = await win.webContents.capturePage();
          if (!img.isEmpty()) {
            fs.mkdirSync(file('thumbs'), { recursive: true });
            fs.writeFileSync(p, img.resize({ width: 480 }).toJPEG(80));
            thumbTimes.set(key, Date.now());
          }
        } catch {}
        clearTimeout(bail); done();
      }, 5000);
    };
    try {
      win = new BrowserWindow({
        show: false, width: 1280, height: 800,
        webPreferences: { session: session.defaultSession, nodeIntegration: false, contextIsolation: true },
      });
      // did-stop-loading fires after all subframes finish, later than did-finish-load
      win.webContents.on('did-stop-loading', capture);
      win.webContents.on('did-fail-load', (_e, code) => { if (code !== -3) { clearTimeout(captureTimer); clearTimeout(bail); done(); } });
      win.webContents.loadURL(url);
    } catch { clearTimeout(bail); done(); }
  });
}

// returns true when added, false when it was already there
function addSpeedDial(url, title) {
  if (!isWeb(url)) return false;
  const sp = JSON.parse(JSON.stringify(S().startPage));
  const gi = Math.max(0, sp.groups.findIndex((g) => g.id === sp.activeGroup));
  const key = thumbKey(url);
  if (sp.groups.some((g) => g.dials.some((d) => thumbKey(d.url) === key))) return false;
  sp.groups[gi].dials.push({ title: String(title || '').trim().slice(0, 80) || hostOf(url), url });
  store.set('startPage', sp);
  if (syncReady() && S().sync.settings !== false) syncSoon();
  return true;
}

function flashStatus(t, text) {
  t.status = text;
  use(t.owner);
  pushState();
  setTimeout(() => {
    if (t.status === text && windows.has(t.owner)) { t.status = ''; use(t.owner); pushState(); }
  }, 2500);
}

function addCurrentToSpeedDial() {
  const t = activeTab();
  if (!t || t.private) { if (t) flashStatus(t, 'Speed dials are not available in private tabs'); return; }
  const added = addSpeedDial(t.wc.getURL(), t.wc.getTitle());
  flashStatus(t, added ? 'Added to Speed Dial ✓' : 'Already in Speed Dial');
  if (added) maybeThumb(t, true);
}

// ---------- windows ----------
function startupTabs() {
  const g = S().general;
  if (g.startup === 'restore') {
    const sess = load('session.json', { tabs: [] });
    if (sess.tabs.length) sess.tabs.forEach((t) => newTab(t.url, { pinned: t.pinned })); else newTab(START);
  } else if (g.startup === 'home') newTab(g.homepage);
  else newTab(START);
}

function createWindow(opts = {}) {
  const w = {
    win: null, toolbarView: null, tabbarView: null, statusView: null, overlay: null,
    pwPending: null, pwField: null, pwSuppressUntil: 0, overlayData: null,
    panelView: null, panelResizer: null, panelWidthLive: null, resizeStart: 0, webPanel: null, webPanelUrl: '', panelOpen: !!S().panel.open, panelTab: S().panel.last || 'bookmarks', suggest: null,
    tabs: new Map(), order: [], activeId: null, overlayMode: null,
    contentRect: { x: 0, y: 0, width: 800, height: 600 },
  };
  let geo = { width: 1280, height: 800 };
  if (opts.at) geo = { x: Math.round(opts.at.x), y: Math.round(opts.at.y), width: 1100, height: 760 };
  else if (cur && cur.win) {
    const b = cur.win.getBounds();
    geo = { x: b.x + 40, y: b.y + 40, width: b.width, height: b.height };
  }
  w.uid = ++winUidCounter;
  windows.add(w);
  use(w);
  // No system title bar by default (our tab strip is the title bar). Use --native-titlebar or the
  // setting "Use the system title bar" if you prefer the standard one.
  w.frameless = !(S().appearance.nativeTitleBar || process.argv.includes('--native-titlebar'));
  const prof = profiles.get(profileId);
  w.win = new BaseWindow({ ...geo, title: profileId === 'default' ? 'mbrowser' : 'mbrowser - ' + (prof ? prof.name : profileId), icon: appIconImage(), frame: !w.frameless, backgroundColor: '#0a0e1f' });
  try { if (typeof w.win.removeMenu === 'function') w.win.removeMenu(); } catch { /* ignore */ }

  const chromeView = (query) => {
    const v = new WebContentsView({
      webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
    });
    chromeIds.add(v.webContents.id);
    chromeOwner.set(v.webContents.id, w);
    v.webContents.on('before-input-event', (e, i) => { use(w); handleShortcut(e, i); });
    v.webContents.loadFile(path.join(__dirname, 'ui.html'), { query });
    return v;
  };
  w.tabbarView = chromeView({ part: 'tabs' });
  w.toolbarView = chromeView({ part: 'toolbar' });
  w.statusView = chromeView({ part: 'status' });
  w.win.contentView.addChildView(w.tabbarView);
  w.win.contentView.addChildView(w.toolbarView);
  w.win.contentView.addChildView(w.statusView);

  w.panelView = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, 'page-preload.js'), contextIsolation: true, sandbox: true },
  });
  chromeOwner.set(w.panelView.webContents.id, w);
  w.panelView.webContents.loadURL('my://panel');
  w.win.contentView.addChildView(w.panelView);
  w.bmView = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, 'page-preload.js'), contextIsolation: true, sandbox: true },
  });
  chromeOwner.set(w.bmView.webContents.id, w);
  w.bmView.webContents.loadURL('my://bookmarks');
  w.win.contentView.addChildView(w.bmView);
  w.panelResizer = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  w.panelResizer.setBackgroundColor('#00000000');
  chromeIds.add(w.panelResizer.webContents.id);
  chromeOwner.set(w.panelResizer.webContents.id, w);
  w.panelResizer.webContents.loadFile(path.join(__dirname, 'resizer.html'));
  w.win.contentView.addChildView(w.panelResizer);

  w.overlay = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  chromeIds.add(w.overlay.webContents.id);
  chromeOwner.set(w.overlay.webContents.id, w);
  w.overlay.setBackgroundColor('#00000000');
  w.overlay.webContents.loadFile(path.join(__dirname, 'overlay.html'));

  w.win.on('resize', () => { use(w); setImmediate(() => { use(w); layout(); }); });
  w.win.on('enter-full-screen', () => { use(w); layout(); });
  w.win.on('leave-full-screen', () => { use(w); layout(); });
  w.win.on('focus', () => use(w));
  w.win.on('maximize', () => { use(w); layout(); pushState(); });
  w.win.on('unmaximize', () => { use(w); layout(); pushState(); });
  w.win.on('closed', () => onWindowClosed(w));

  let loaded = 0;
  const ready = () => {
    if (++loaded < 3) return;
    use(w);
    layout();
    if (opts.first) startupTabs();
    else if (!opts.empty) newTab(undefined, opts.private ? { private: true } : undefined);
    pushState();
    if (opts.first) syncAll();
  };
  for (const v of [w.tabbarView, w.toolbarView, w.statusView]) v.webContents.once('did-finish-load', ready);
  return w;
}

function onWindowClosed(w) {
  // Flush session BEFORE windows.delete(w) so this window's tabs are still reachable.
  clearTimeout(sessionTimer);
  sessionTimer = null;
  const sessionList = [...windows]
    .flatMap((win) => win.order.map((id) => win.tabs.get(id)))
    .filter((t) => t && !t.private && /^https?:\/\//i.test(t.wc.getURL()))
    .map((t) => ({ url: t.wc.getURL(), pinned: t.pinned }));
  try { save('session.json', { tabs: sessionList }); } catch { /* ignore */ }

  windows.delete(w);
  // Save all non-private tabs to recently-closed before tearing down
  for (const id of w.order) {
    const t = w.tabs.get(id);
    if (!t || t.private) continue;
    const u = t.wc.getURL();
    if (u && u !== 'about:blank' && !u.startsWith('my://')) {
      closedTabs.push({ url: u, title: t.wc.getTitle() || u, pinned: t.pinned });
      if (closedTabs.length > 50) closedTabs.shift();
    }
  }
  for (const t of [...w.tabs.values()]) {
    const id = t.wc.id;
    tabByWc.delete(id); tabById.delete(t.id); blockedCounts.delete(id);
    try { t.wc.close(); } catch { /* already gone */ }
  }
  w.tabs.clear();
  w.order = [];
  for (const v of [w.tabbarView, w.toolbarView, w.statusView, w.panelView, w.panelResizer, w.webPanel, w.bmView, w.overlay]) {
    if (!v) continue;
    let id = null;
    try { id = v.webContents.id; } catch { /* destroyed */ }
    if (id != null) { chromeIds.delete(id); chromeOwner.delete(id); }
    try { v.webContents.close(); } catch { /* ignore */ }
  }
  if (cur === w) cur = [...windows][0] || null;
  scheduleSessionSave();
}

// Move a tab (and its live page) into another window.
function moveTabToWindow(t, dest, beforeId) {
  const src = t.owner;
  if (src === dest || !windows.has(dest)) return;
  use(src);
  if (src.overlayMode) hideOverlay(false);
  const wasActive = src.activeId === t.id;
  const idx = src.order.indexOf(t.id);
  if (wasActive) src.win.contentView.removeChildView(t.view);
  src.tabs.delete(t.id);
  src.order.splice(idx, 1);
  const srcEmpty = src.order.length === 0;
  if (wasActive) {
    src.activeId = null;
    if (!srcEmpty) switchTab(src.order[Math.min(idx, src.order.length - 1)]);
  } else if (!srcEmpty) pushState();

  t.owner = dest;
  use(dest);
  dest.tabs.set(t.id, t);
  let at = beforeId != null && dest.order.includes(beforeId) ? dest.order.indexOf(beforeId) : dest.order.length;
  const pc = dest.order.filter((i) => dest.tabs.get(i).pinned).length;
  at = t.pinned ? Math.min(at, pc) : Math.max(at, pc);
  dest.order.splice(at, 0, t.id);
  switchTab(t.id);
  try { dest.win.focus(); } catch { /* ignore */ }
  if (srcEmpty) src.win.close();
}

function tearOffTab(t, at) {
  if (t.owner.order.length < 2) return;
  const w = createWindow({ empty: true, at });
  moveTabToWindow(t, w, null);
}

function reorderTab(id, beforeId) {
  const t = cur.tabs.get(id);
  if (!t) return;
  cur.order = cur.order.filter((i) => i !== id);
  let idx = beforeId != null && cur.order.includes(beforeId) ? cur.order.indexOf(beforeId) : cur.order.length;
  const pc = cur.order.filter((i) => cur.tabs.get(i).pinned).length;
  idx = t.pinned ? Math.min(idx, pc) : Math.max(idx, pc);
  cur.order.splice(idx, 0, id);
  pushState();
}

// A tab was dragged and released outside any tab strip: drop it into the window under
// the cursor, or tear it off into a new window.
function tabDragEnd(id, dropEffect, outside) {
  const t = tabById.get(id);
  if (!t) return;
  const src = t.owner;
  const pt = screen.getCursorScreenPoint();
  const inBounds = (b) => pt.x >= b.x && pt.x <= b.x + b.width && pt.y >= b.y && pt.y <= b.y + b.height;
  // move to another existing window if cursor lands on it
  const target = [...windows].find((w) => w !== src && inBounds(w.win.getBounds()));
  if (target) { moveTabToWindow(t, target, null); return; }
  // tear off to new window if cursor is outside the source window (or Wayland reports both 0)
  const srcBounds = src.win.getBounds();
  const inSrc = inBounds(srcBounds);
  if (!inSrc || outside) {
    if (src.order.length > 1) tearOffTab(t, { x: pt.x - 120, y: pt.y - 20 });
  }
}

// ---------- app ----------
// ---------- profile gate: password prompt / profile chooser shown before anything else opens ----------
let gateWcId = null;
let gateResolve = null;
let wizardWcId = null;
let wizardResolve = null;
let wizardActive = false;
let wizardNewProfile = false; // true when wizard is launched to create an additional profile
let pendingNewProfileSync = null; // { server, token, account } held until the new profile is created

function showGate(mode) {
  return new Promise((resolve) => {
    const win = new BaseWindow({ width: 460, height: 640, title: 'mbrowser', icon: appIconImage(), resizable: false, backgroundColor: '#0a0e1f' });
    try { if (typeof win.removeMenu === 'function') win.removeMenu(); } catch { /* ignore */ }
    const view = new WebContentsView({
      webPreferences: { preload: path.join(__dirname, 'gate-preload.js'), contextIsolation: true, sandbox: true },
    });
    win.contentView.addChildView(view);
    const fit = () => { const b = win.getContentBounds(); view.setBounds({ x: 0, y: 0, width: b.width, height: b.height }); };
    fit();
    win.on('resize', fit);
    gateWcId = view.webContents.id;
    view.webContents.loadFile(path.join(__dirname, 'gate.html'), { query: { mode, id: profileId } });
    let done = false;
    gateResolve = (r) => {
      if (done) return;
      done = true; gateWcId = null;
      resolve(r);
      setTimeout(() => { try { win.close(); } catch { /* closed */ } }, 0);
    };
    win.on('closed', () => { if (!done) { done = true; gateWcId = null; resolve('quit'); } });
  });
}

ipcMain.handle('gate:list', (e) => (e.sender.id === gateWcId
  ? { profiles: profiles.list(), current: profileId, askAtStart: profiles.askAtStart() } : null));
ipcMain.handle('gate:choose', async (e, id, pw, remember) => {
  if (e.sender.id !== gateWcId) return { error: 'not allowed' };
  const p = profiles.get(String(id));
  if (!p) return { error: 'Unknown profile.' };
  if (p.locked && !profiles.verify(p.id, String(pw || ''))) { await sleep(500); return { error: 'Wrong password.' }; }
  if (typeof remember === 'boolean') profiles.setAskAtStart(remember);
  if (p.id === profileId) gateResolve('continue');
  else { launchProfile(p.id, true); gateResolve('quit'); }
  return { ok: true };
});
ipcMain.on('gate:cancel', (e) => { if (e.sender.id === gateWcId && gateResolve) gateResolve('quit'); });

function showWizard() {
  wizardActive = true;
  return new Promise((resolve) => {
    const win = new BaseWindow({ width: 740, height: 840, title: 'mbrowser setup', icon: appIconImage(), resizable: true, minWidth: 560, minHeight: 640, backgroundColor: '#0f0524', frame: false });
    try { if (typeof win.removeMenu === 'function') win.removeMenu(); } catch { /* ignore */ }
    const view = new WebContentsView({
      webPreferences: { preload: path.join(__dirname, 'page-preload.js'), contextIsolation: true, sandbox: true },
    });
    win.contentView.addChildView(view);
    const fit = () => { const b = win.getContentBounds(); view.setBounds({ x: 0, y: 0, width: b.width, height: b.height }); };
    fit();
    win.on('resize', fit);
    wizardWcId = view.webContents.id;
    view.webContents.loadURL('my://welcome');
    let done = false;
    const finish = (r) => {
      if (done) return;
      done = true; wizardWcId = null; wizardActive = false;
      clearTimeout(syncTimer); // cancel any sync that was queued during wizard
      profiles.setSetupDone();
      resolve(r);
    };
    wizardResolve = (r) => {
      finish(r);
      setTimeout(() => { try { win.close(); } catch { /* closed */ } }, 0);
    };
    win.on('closed', () => finish('cancelled'));
  });
}

// ----- wizard IPC (only answered for the wizard window) -----
const isWizard = (e) => e.sender.id === wizardWcId;

ipcMain.handle('wizard:status', async (e) => {
  if (!isWizard(e)) return null;
  // When adding a new profile, the new profile starts with no server configured.
  const isNewProfile = !!wizardNewProfile;
  let serverVault = null;
  if (!isNewProfile && syncReady()) { try { const r = await call('GET', '/api/vault'); serverVault = { exists: !!r.exists, device: !!(r.blob && r.blob.device) }; } catch { /* unknown */ } }
  return {
    serverVault,
    profileId,
    profileName: profiles.get(profileId)?.name || 'Default',
    profiles: profiles.list(),
    defaultProfile: profiles.getDefaultProfile(),
    openLastProfile: profiles.getOpenLastProfile(),
    serverConfigured: isNewProfile ? false : syncReady(),
    syncAccount: isNewProfile ? {} : (S().sync.account || {}),
    addingProfile: isNewProfile,
    vaultExists: isNewProfile ? false : vault.exists(),
    vaultDeviceMode: vault.isDevice(),
    remember: !!S().passwords.remember,
    safeStorageAvailable: safeStorage.isEncryptionAvailable(),
    blockTrackers: S().privacy.blockTrackers,
    searchEngines: S().search.engines,
    defaultEngine: S().search.default,
  };
});

ipcMain.handle('wizard:test-server', async (e, url) => {
  if (!isWizard(e)) return null;
  try {
    const base = String(url || '').replace(/\/+$/, '');
    if (!base) return { error: 'Enter a server address.' };
    const res = await fetch(base + '/api/health', { signal: AbortSignal.timeout(8000) });
    return { ok: true, status: res.status };
  } catch (err) { return { error: 'Cannot reach server: ' + err.message }; }
});

ipcMain.handle('wizard:connect', async (e, o) => {
  if (!isWizard(e)) return null;
  o = o || {};
  const base = String(o.server || '').replace(/\/+$/, '');
  if (!base) return { error: 'Enter the server address.' };
  const forNewProfile = !!wizardNewProfile;
  try {
    if (!forNewProfile) applySetting('sync.server', base);
    const res = await fetch(base + '/api/auth/device-login', {
      method: 'POST', headers: { 'content-type': 'application/json' }, signal: AbortSignal.timeout(15000),
      body: JSON.stringify({ username: String(o.username || ''), password: String(o.password || ''), totp: String(o.totp || ''), profile: o.profile || '', create: !!o.create, list: !!o.list, device: S().sync.device || os.hostname() }),
    });
    let data = {};
    try { data = await res.json(); } catch { /* not JSON */ }
    if (res.status === 404) return { error: 'Server is an older version. Update it, or use an API key in Settings.' };
    if (!res.ok) return { error: data.error || ('Sign-in failed (HTTP ' + res.status + ')'), needTotp: !!data.needTotp };
    if (data.needProfile) return { needProfile: true, profiles: data.profiles };
    if (forNewProfile) {
      // Store credentials to be written to the new profile's settings when it is created.
      pendingNewProfileSync = { server: base, token: data.token, account: { user: String(o.username), profile: data.profile?.name || '' } };
    } else {
      applySetting('sync.token', data.token);
      applySetting('sync.account', { user: String(o.username), profile: data.profile?.name || '' });
      syncSoon();
    }
    return { ok: true, profile: data.profile };
  } catch (err) {
    if (!forNewProfile) applySetting('sync.server', '');
    return { error: 'Cannot reach server: ' + err.message };
  }
});

ipcMain.handle('wizard:rename-profile', (e, name) => {
  if (!isWizard(e)) return null;
  return profiles.update(profileId, { name: String(name || '') });
});

ipcMain.handle('wizard:create-profile', (e, name) => {
  if (!isWizard(e)) return null;
  const all = profiles.list();
  // After a factory reset (or any other path where only the bare "Default" exists)
  // rename it instead of creating a second redundant profile —
  // unless the wizard was launched explicitly to add a new profile.
  if (!wizardNewProfile && all.length === 1 && all[0].id === 'default') {
    const r = profiles.update('default', { name: String(name || '').trim() });
    if (r.ok) profiles.setDefaultProfile('default');
    return r;
  }
  const result = profiles.create({ name: String(name || '').trim() });
  if (result.ok) {
    profiles.clearFirstRun(result.id); // wizard IS the first run; no repeat needed
    profiles.setDefaultProfile(result.id);
    if (pendingNewProfileSync) {
      try {
        const dir = profiles.dirFor(result.id);
        fs.mkdirSync(dir, { recursive: true });
        const sf = path.join(dir, 'settings.json');
        let existing = {};
        try { existing = JSON.parse(fs.readFileSync(sf, 'utf8')); } catch { /* fresh profile */ }
        existing.sync = Object.assign({}, existing.sync || {}, {
          server: pendingNewProfileSync.server,
          token: pendingNewProfileSync.token,
          account: pendingNewProfileSync.account,
        });
        const tmp = sf + '.tmp';
        fs.writeFileSync(tmp, JSON.stringify(existing, null, 2), { mode: 0o600 });
        fs.renameSync(tmp, sf);
      } catch { /* non-fatal: new profile starts without sync */ }
      pendingNewProfileSync = null;
    }
  }
  return result;
});

ipcMain.handle('wizard:set-default-profile', (e, id) => {
  if (!isWizard(e)) return null;
  return profiles.setDefaultProfile(String(id || profileId));
});

ipcMain.handle('wizard:set-open-last', (e, v) => {
  if (!isWizard(e)) return null;
  profiles.setOpenLastProfile(!!v);
  return { ok: true };
});

// { mode: 'master' | 'device' | 'join', master, confirm, remember, key }
ipcMain.handle('wizard:vault-setup', async (e, o) => {
  if (!isWizard(e)) return null;
  o = o || {};
  if (typeof o.remember === 'boolean') applySetting('passwords.remember', o.remember);
  try {
    if (o.mode === 'join') {
      if (vault.exists()) return { ok: true, already: true };
      const r = await call('GET', '/api/vault');
      if (!r.exists) return { error: 'The server has no vault.' };
      vault.adopt(r.blob);
      syncState.vaultVer = r.version; syncState.vaultHash = crypto.createHash('sha256').update(vault.blobText()).digest('hex'); save('syncstate.json', syncState);
      if (r.blob.device) {
        const k = String(o.key || '').trim();
        if (!k || !vault.unlockWithKey(Buffer.from(k, 'base64'))) return { error: 'That key does not open the vault.' };
        writeKey(KEYFILES.device, k);
      } else {
        if (!vault.unlock(String(o.master || ''))) { await sleep(500); return { error: 'Wrong master password.' }; }
        afterUnlock();
      }
      return { ok: true };
    }
    if (vault.exists()) return { ok: true, already: true };
    if (o.mode === 'device') { createDeviceVault(); return { ok: true }; }
    if (!o.master) return { error: 'Enter a master password.' };
    if (o.master !== o.confirm) return { error: 'The two passwords do not match.' };
    vault.create(String(o.master));
    afterUnlock();
    return { ok: true };
  } catch (err) { return { error: err.message }; }
});

ipcMain.handle('wizard:done', (e) => {
  if (!isWizard(e)) return null;
  if (wizardResolve) wizardResolve('done');
  return { ok: true };
});

ipcMain.handle('wizard:cancel', (e) => {
  if (!isWizard(e)) return null;
  if (wizardResolve) wizardResolve('cancelled');
  return { ok: true };
});

app.whenReady().then(async () => {
  if (!gotLock) return; // another instance of this profile is already running
  const me = profiles.get(profileId);
  const chooser = !profileExplicit && profiles.askAtStart() && !unlockedByParent;
  if ((me.locked && !unlockedByParent) || chooser) {
    const result = await showGate(chooser ? 'choose' : 'unlock');
    if (result !== 'continue') { app.quit(); return; }
  }
  // If a reset was requested in the previous session, wipe the userData directory NOW —
  // before Chromium loads anything from it — then continue with a fresh data directory.
  // Clear Electron's session storage after a factory reset (cookies, cache, IndexedDB).
  if (process.argv.includes('--reset-on-start')) {
    try { await session.defaultSession.clearStorageData(); } catch { /* ignore */ }
    try { await session.defaultSession.clearCache(); } catch { /* ignore */ }
  }

  const pendingProfileId = profiles.pendingProfileReset();
  if (pendingProfileId) {
    profiles.clearPendingProfileReset();
    const profileDir = profiles.dirFor(pendingProfileId);
    try { fs.rmSync(profileDir, { recursive: true, force: true }); } catch { /* ignore */ }
    profiles.reload();
    try { await session.defaultSession.clearStorageData(); } catch { /* ignore */ }
    try { await session.defaultSession.clearCache(); } catch { /* ignore */ }
  }

  store = createStore(file('settings.json'));
  blocker = createBlocker(S, path.join(app.getPath('userData'), 'adblock'));
  applyProfileMeta();

  // one-time migration of the old sync.json
  if (!S().sync.server && !S().sync.token) {
    const old = load('sync.json', null);
    if (old && old.token && old.server && !old.server.includes('YOUR-SERVER')) {
      store.set('sync', { server: old.server, token: old.token, device: old.device || '' });
    }
  }

  bookmarks = load('bookmarks.json', []).map((b) => ({ deleted: false, updated: b.added || Date.now(), ...b }));
  history = load('history.json', []);
  notes = load('notes.json', []);
  bookmarkFolders = load('bookmark-folders.json', []);
  vault = createVault(file('vault.json'));

  autoUnlockVault();

  syncState = { ...syncState, ...load('syncstate.json', {}) };
  syncStatus = syncReady() ? 'Waiting for first sync…' : 'Sync not configured (Settings → Sync)';

  applyThemeSource();
  rebuildShortcuts();
  setupSession(session.defaultSession);
  setupSession(session.fromPartition('private'));
  setupIpc();

  Menu.setApplicationMenu(null); // no File / Edit / View menu bar
  nativeTheme.on('updated', () => pushAll());

  if (process.argv.includes('--first-run') || !profiles.setupDone()) {
    const wizardResult = await showWizard();
    if (wizardResult === 'cancelled') { app.quit(); return; }
  }

  profiles.setLastProfile(profileId);
  createWindow({ first: true });

  setInterval(syncAll, 60 * 1000);

  // Wire up electron-updater events, then check ~60s after launch
  initAutoUpdater();
  const autoCheck = () => { if (S().general.autoUpdate !== false) checkForUpdates(false); };
  setTimeout(autoCheck, 60_000);
  setInterval(autoCheck, 6 * 60 * 60 * 1000);
});

// Self-signed certificates (common on home servers): refuse unless the user chose "proceed anyway"
app.on('certificate-error', (event, _wc, url, _error, _cert, callback) => {
  const host = hostOf(url);
  if (store && host && S().privacy.certExceptions.includes(host)) { event.preventDefault(); callback(true); }
  else callback(false);
});

let quitCleared = false;
app.on('before-quit', (e) => {
  // Session flush: only needed if windows are still open (e.g. app.quit() called
  // programmatically before any window closes). onWindowClosed handles the normal case.
  clearTimeout(sessionTimer);
  sessionTimer = null;
  if (windows.size > 0) {
    const list = [...windows].flatMap((w) => w.order.map((id) => w.tabs.get(id)))
      .filter((t) => t && !t.private && /^https?:\/\//i.test(t.wc.getURL()))
      .map((t) => ({ url: t.wc.getURL(), pinned: t.pinned }));
    try { save('session.json', { tabs: list }); } catch { /* ignore */ }
  }
  if (quitCleared || !store) return;
  const c = S().privacy.clearOnExit;
  const what = c.history && c.cookies && c.cache ? 'all' : null;
  const jobs = [];
  if (what) jobs.push(clearData('all'));
  else {
    if (c.history) jobs.push(clearData('history'));
    if (c.cookies) jobs.push(clearData('cookies'));
    if (c.cache) jobs.push(clearData('cache'));
  }
  if (!jobs.length) return;
  e.preventDefault();
  Promise.allSettled(jobs).then(() => { quitCleared = true; app.quit(); });
});

app.on('window-all-closed', () => { if (!resetInProgress) app.quit(); });

