'use strict';
const fs = require('fs');

const HOME = 'https://duckduckgo.com';

const DEFAULTS = {
  general: { startup: 'restore', homepage: HOME, newTab: 'start', browserId: 'auto', autoUpdate: true },
  appearance: { themeId: 'system', customThemes: [], accentOverride: '', tabBar: 'top', statusBar: true, nativeTitleBar: false, icon: 'white-m',
    toolbar: { back: true, forward: true, reload: true, shield: true, star: true, profile: true, downloads: true, key: true, siteinfo: true,
      order: ['back', 'forward', 'reload', 'address', 'shield', 'downloads', 'star', 'profile'] } },
  tabs: { newTabPosition: 'end', newTabFocus: 'address', wheelScrolls: true, clickTabScrollsTop: true, lazyBackground: true },
  search: {
    default: 'ddg',
    engines: [
      { id: 'ddg', name: 'DuckDuckGo', nick: 'd', url: 'https://duckduckgo.com/?q=%s' },
      { id: 'google', name: 'Google', nick: 'g', url: 'https://www.google.com/search?q=%s' },
      { id: 'bing', name: 'Bing', nick: 'b', url: 'https://www.bing.com/search?q=%s' },
      { id: 'wiki', name: 'Wikipedia', nick: 'w', url: 'https://en.wikipedia.org/w/index.php?search=%s' },
      { id: 'yt', name: 'YouTube', nick: 'y', url: 'https://www.youtube.com/results?search_query=%s' },
      { id: 'gh', name: 'GitHub', nick: 'gh', url: 'https://github.com/search?q=%s' },
    ],
  },
  privacy: {
    blockTrackers: true,
    doNotTrack: true,
    allowSites: [],
    allowHosts: [],
    certExceptions: [],
    customBlocklist: '',
    clearOnExit: { history: false, cookies: false, cache: false },
  },
  passwords: { enabled: true, autoLockMinutes: 15, neverSave: [], sync: true, remember: true, inPrivate: true },
  downloads: { askWhere: false, directory: '', firstTimeAsked: false },
  panel: { open: false, last: 'bookmarks', side: 'left', width: 320, webPanels: [] },
  pages: { defaultZoom: 100 },
  keyboard: { overrides: {} },
  sync: { server: '', token: '', device: '', settings: true, account: {} },
  profileMeta: { color: '', avatar: '' },
  dialLayout: { layout: {} },   // order of the speed dial tiles, synced as its own group
  startPage: {
    tileSize: 150,
    thumbnails: true,
    showSearch: true,
    activeGroup: 'main',
    columns: 0,
    showBrand: true,
    showTitles: true,
    showAddButton: true,
    gap: 22,
    margins: 'normal',
    showDashboard: false,
    background: '',
    dialStyle: 'icons',   // 'icons' (big site icons, the default) or 'cards' (page previews)
    wallpaper: 'none',    // 'none' (the theme's own colours), a built-in name, or 'custom'
    wallpaperRev: 0,
    extraTabs: { bookmarks: true, history: true, notes: true },
    groups: [
      {
        id: 'main',
        name: 'Speed dial',
        dials: [
          { title: 'DuckDuckGo', url: 'https://duckduckgo.com' },
          { title: 'Wikipedia', url: 'https://wikipedia.org' },
          { title: 'GitHub', url: 'https://github.com' },
        ],
      },
    ],
  },
};

// Which groups of settings travel between your devices, and the few keys inside them that stay per-device
const SYNC_KEYS = ['general', 'appearance', 'startPage', 'dialLayout', 'tabs', 'search', 'privacy', 'passwords', 'panel', 'keyboard', 'pages', 'downloads', 'profileMeta'];
const EXCLUDE = {
  startPage: ['dialLayout'],               // the order of the tiles has its own group, so rearranging never fights the look settings
  appearance: ['nativeTitleBar'],          // depends on the desktop
  panel: ['open', 'last'],                 // window state
  privacy: ['certExceptions'],             // security decisions stay on the device they were made on
  downloads: ['directory'],                // a folder path
};
const isExcluded = (top, rest) => (EXCLUDE[top] || []).includes(String(rest).split('.')[0]);

const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v);
const clone = (v) => JSON.parse(JSON.stringify(v));

function merge(base, over) {
  const out = clone(base);
  if (!isObj(over)) return out;
  for (const k of Object.keys(over)) {
    if (k === '__proto__' || k === 'constructor' || k === 'prototype') continue;
    out[k] = isObj(out[k]) && isObj(over[k]) ? merge(out[k], over[k]) : over[k];
  }
  return out;
}

function createStore(file) {
  let saved = {};
  try { saved = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { /* first run */ }
  let data = merge(DEFAULTS, saved);
  // migrate the old flat speedDials list into the first start page group
  if (Array.isArray(saved.speedDials) && !(saved.startPage && saved.startPage.groups)) {
    data.startPage.groups[0].dials = saved.speedDials;
  }
  delete data.speedDials;
  // older versions had a dark / light / system switch instead of themes
  if (saved.appearance && saved.appearance.theme && !saved.appearance.themeId) {
    data.appearance.themeId = { light: 'daylight', system: 'system' }[saved.appearance.theme] || 'mbrowser';
  }
  delete data.appearance.theme;
  delete data.appearance.accent;
  // 'start' was the old default; migrate existing profiles to 'restore'
  if (saved.general && saved.general.startup === 'start') data.general.startup = 'restore';

  if (!isObj(data._updated)) data._updated = {};
  const write = () => fs.writeFileSync(file, JSON.stringify(data, null, 2));
  const pick = (key, value) => {
    const v = clone(value);
    for (const k of EXCLUDE[key] || []) delete v[k];
    return v;
  };
  const differs = (key) => JSON.stringify(pick(key, data[key])) !== JSON.stringify(pick(key, DEFAULTS[key]));

  return {
    get: () => data,
    // set('privacy.blockTrackers', false); value null deletes the key
    set(p, value) {
      const parts = String(p).split('.');
      if (!(parts[0] in DEFAULTS)) throw new Error('unknown setting ' + p);
      if (parts.some((x) => x === '__proto__' || x === 'constructor' || x === 'prototype')) {
        throw new Error('bad path');
      }
      let o = data;
      for (let i = 0; i < parts.length - 1; i++) {
        if (!isObj(o[parts[i]])) o[parts[i]] = {};
        o = o[parts[i]];
      }
      const last = parts[parts.length - 1];
      if (value === null) delete o[last]; else o[last] = value;
      if (SYNC_KEYS.includes(parts[0]) && !isExcluded(parts[0], parts.slice(1).join('.'))) data._updated[parts[0]] = Date.now();
      write();
    },

    // ---- support for syncing settings between devices ----
    syncable: (key) => pick(key, data[key]),
    // when a group was last changed here. Groups changed before this was tracked count as "changed now" on first look.
    updatedOf(key) {
      if (!data._updated[key] && differs(key)) { data._updated[key] = Date.now(); write(); }
      return data._updated[key] || 0;
    },
    applyGroup(key, value, updated) {
      const keep = {};
      for (const k of EXCLUDE[key] || []) keep[k] = clone(data[key][k]);
      data[key] = merge(DEFAULTS[key], { ...value, ...keep });
      data._updated[key] = updated;
      write();
    },
  };
}

module.exports = { createStore, DEFAULTS, HOME, SYNC_KEYS, EXCLUDE };
