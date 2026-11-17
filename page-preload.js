// Preload for every web page tab: the `browser` API for my:// pages
const { contextBridge, ipcRenderer, webFrame } = require('electron');

// slim scrollbars on web pages, injected before the first paint (no flash of the old ones)
if (location.protocol === 'http:' || location.protocol === 'https:') {
  try { webFrame.insertCSS('::-webkit-scrollbar{width:12px;height:12px}::-webkit-scrollbar-track{background:transparent}::-webkit-scrollbar-thumb{background:rgba(128,128,128,.4);border-radius:10px;border:3px solid transparent;background-clip:content-box;min-height:36px}::-webkit-scrollbar-thumb:hover{background:rgba(128,128,128,.7);background-clip:content-box}::-webkit-scrollbar-corner{background:transparent}'); } catch { /* ignore */ }
}

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
    historyBinList: invoke('history:binList'),
    historyRestore: invoke('history:restore'),
    historyEmptyBin: invoke('history:emptyBin'),
    removeBookmark: invoke('bookmark:remove'),
    clearData: invoke('data:clear'),
    getDownloads: invoke('downloads:list'),
    downloadAction: invoke('downloads:action'),
    refreshThumbs: (urls) => ipcRenderer.invoke('thumb:refresh', urls),
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
    bookmarksRecycleBin: invoke('bookmarks:recycleBin'),
    bookmarksRestoreFromBin: invoke('bookmarks:restoreFromBin'),
    bookmarksEmptyBin: invoke('bookmarks:emptyBin'),
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
    relaunch: invoke('app:relaunch'),
    extensionsList: invoke('extensions:list'),
    extensionsAdd: invoke('extensions:add'),
    extensionsToggle: invoke('extensions:toggle'),
    extensionsRemove: invoke('extensions:remove'),
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
    onBookmarksChanged: (cb) => ipcRenderer.on('bookmarks:changed', () => cb()),
    vaultStatus: invoke('vault:status'),
    wallpaperPick: invoke('wallpaper:pick'),
    vaultOpen: invoke('vault:open'),
    vaultSetup: invoke('vault:setup'),
    vaultRemoteInfo: invoke('vault:remoteInfo'),
    vaultJoin: invoke('vault:join'),
    vaultImportKey: invoke('vault:importKey'),
    vaultSetMode: invoke('vault:setMode'),
    vaultSetRemember: invoke('vault:setRemember'),
    vaultExportKey: invoke('vault:exportKey'),
    pairQr: invoke('pair:qr'),
    vaultListCards: invoke('vault:listCards'),
    vaultSaveCard: invoke('vault:saveCard'),
    vaultRevealCard: invoke('vault:revealCard'),
    vaultDeleteCard: invoke('vault:deleteCard'),
    vaultListAddresses: invoke('vault:listAddresses'),
    vaultSaveAddress: invoke('vault:saveAddress'),
    vaultRevealAddress: invoke('vault:revealAddress'),
    vaultDeleteAddress: invoke('vault:deleteAddress'),
    vaultSyncStatus: invoke('vault:syncStatus'),
    vaultSyncResolve: invoke('vault:syncResolve'),
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
    notesRecycleBin: invoke('notes:recycleBin'),
    notesRestoreFromBin: invoke('notes:restoreFromBin'),
    notesEmptyBin: invoke('notes:emptyBin'),
    profilesSetDefault: invoke('profiles:setDefault'),
    profilesSetOpenLast: invoke('profiles:setOpenLast'),
    profilesGetPrefs: invoke('profiles:getPrefs'),
    profilesResetData: invoke('profiles:resetData'),
    appResetAll: invoke('app:resetAll'),
    appRerunWizard: invoke('app:rerunWizard'),
    appNewProfileWizard: invoke('app:newProfileWizard'),
    profilesAddWindow: invoke('profiles:addWindow'),
    addProfileInfo: invoke('addprofile:info'),
    addProfileServerProfiles: invoke('addprofile:serverProfiles'),
    addProfileUseConnected: invoke('addprofile:useConnected'),
    addProfileNewServer: invoke('addprofile:newServer'),
    addProfileLocal: invoke('addprofile:local'),
    addProfileClose: () => ipcRenderer.send('addprofile:close'),
    wizardStatus: invoke('wizard:status'),
    wizardTestServer: invoke('wizard:test-server'),
    wizardConnect: invoke('wizard:connect'),
    wizardRenameProfile: invoke('wizard:rename-profile'),
    wizardCreateProfile: invoke('wizard:create-profile'),
    wizardSetDefaultProfile: invoke('wizard:set-default-profile'),
    wizardSetOpenLast: invoke('wizard:set-open-last'),
    wizardVaultSetup: invoke('wizard:vault-setup'),
    wizardDone: invoke('wizard:done'),
    wizardCancel: invoke('wizard:cancel'),
    appBuildNumber: invoke('app:build-number'),
    appVersions: invoke('app:versions'),
    defaultStatus: invoke('default:status'),
    defaultSet: invoke('default:set'),
    appUpdateInfo: invoke('app:update-info'),
    appAutoInstall: (info) => ipcRenderer.invoke('app:auto-install', info),
    onDlProgress: (cb) => ipcRenderer.on('app:dl-progress', (_, v) => cb(v)),
    offDlProgress: () => ipcRenderer.removeAllListeners('app:dl-progress'),
  });
}

// ---- ctrl+scroll wheel zoom ----
window.addEventListener('wheel', (e) => {
  if (!e.ctrlKey) return;
  e.preventDefault();
  ipcRenderer.send('zoom:ctrl-wheel', e.deltaY > 0 ? -1 : 1);
}, { passive: false, capture: true });

// ---- Google sign-in: belt-and-suspenders userAgentData fix ----
// CDP (Emulation.setUserAgentOverride) is the primary mechanism; this JS
// injection is a fallback in case CDP doesn't fire for sandboxed renderers.
// process.getSystemVersion() works in sandboxed preloads; require('os') does not.
if (/^https?:$/.test(location.protocol)) {
  const { webFrame } = require('electron');
  const isWin = process.platform === 'win32';
  const platStr = isWin ? 'Windows' : 'Linux';
  // Chrome sends "" for Linux, and "10.0.0"/"15.0.0" for Windows (not the raw kernel build).
  const platVer = (() => {
    if (!isWin) return '';
    const build = parseInt((process.getSystemVersion().split('.')[2] || '0'), 10);
    return build >= 22000 ? '15.0.0' : '10.0.0';
  })();
  const chromeVer = process.versions.chrome || '130.0.6723.117';
  const chromeMajor = chromeVer.split('.')[0];
  webFrame.executeJavaScript(`(function(){
    // only for the Chrome identity: with Firefox or Safari nothing is faked
    if (!navigator.userAgentData || !/ Chrome\\//.test(navigator.userAgent)) return;
    var brands = [
      {brand:'Not?A_Brand',version:'8'},
      {brand:'Chromium',version:'${chromeMajor}'},
      {brand:'Google Chrome',version:'${chromeMajor}'}
    ];
    var full = [
      {brand:'Not?A_Brand',version:'8.0.0.0'},
      {brand:'Chromium',version:'${chromeVer}'},
      {brand:'Google Chrome',version:'${chromeVer}'}
    ];
    var platVer = ${JSON.stringify(platVer)};
    // Only override if the brands still contain 'Electron' (CDP hasn't fixed it)
    var existing = navigator.userAgentData;
    if (existing && existing.brands && !existing.brands.some(function(b){return b.brand==='Electron';})) return;
    var uad = {
      brands:brands, mobile:false, platform:'${platStr}',
      getHighEntropyValues:function(h){
        var r={};
        if(h.includes('brands')) r.brands=brands;
        if(h.includes('mobile')) r.mobile=false;
        if(h.includes('platform')) r.platform='${platStr}';
        if(h.includes('platformVersion')) r.platformVersion=platVer;
        if(h.includes('architecture')) r.architecture='x86';
        if(h.includes('bitness')) r.bitness='64';
        if(h.includes('model')) r.model='';
        if(h.includes('uaFullVersion')) r.uaFullVersion='${chromeVer}';
        if(h.includes('fullVersionList')) r.fullVersionList=full;
        if(h.includes('wow64')) r.wow64=false;
        return Promise.resolve(r);
      },
      toJSON:function(){return {brands:brands,mobile:false,platform:'${platStr}'};}
    };
    try { Object.defineProperty(navigator,'userAgentData',{get:function(){return uad;},configurable:true}); } catch(e){}
    // Ensure window.chrome looks like a real Chrome tab
    var c = window.chrome || (window.chrome = {});
    if (!c.runtime) c.runtime = {
      id:undefined,
      connect:function(){return {};},
      sendMessage:function(){},
      onMessage:{addListener:function(){},removeListener:function(){},hasListener:function(){return false;}},
      onConnect:{addListener:function(){},removeListener:function(){}},
    };
    if (!c.csi) c.csi = function(){ return {startE:Date.now(),onloadT:0,pageT:performance.now(),tran:15}; };
    if (!c.loadTimes) c.loadTimes = function(){ return {
      requestTime:Date.now()/1000,startLoadTime:Date.now()/1000,commitLoadTime:0,
      finishDocumentLoadTime:0,finishLoadTime:0,firstPaintTime:0,firstPaintAfterLoadTime:0,
      navigationType:'Other',wasFetchedViaSpdy:false,wasNpnNegotiated:true,
      npnNegotiatedProtocol:'h2',wasAlternateProtocolAvailable:false,connectionInfo:'h2'
    }; };
  })()`);
}

// ---- page language detection (for translate bar) ----
if (/^https?:$/.test(location.protocol)) {
  const sendLang = () => {
    const lang = document.documentElement.lang || document.documentElement.getAttribute('xml:lang');
    if (lang) ipcRenderer.send('page:lang', lang);
  };
  window.addEventListener('DOMContentLoaded', sendLang);
  // Some SPAs set lang after initial render
  window.addEventListener('load', sendLang);
}

// ---- password capture and autofill (web pages only; nothing is filled until the user picks a login) ----
if (/^https?:$/.test(location.protocol)) {
  const isVisible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const visibleInputs = () => [...document.querySelectorAll('input')].filter(isVisible);
  const pwFields = (root) => [...(root || document).querySelectorAll('input[type=password]')].filter(isVisible);
  const textual = (el) => ['text', 'email', 'tel', ''].includes((el.type || 'text').toLowerCase());
  // the first step of a two-step sign-in (Amazon, Google…) has only the email / username field: no password field yet
  const looksLikeUser = (el) => {
    if (!(el instanceof HTMLInputElement) || !textual(el) || el.readOnly || el.disabled) return false;
    const ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/(^|\s)(username|email|webauthn)/.test(ac)) return true;
    const t = (el.type || 'text').toLowerCase(), nm = ((el.name || '') + ' ' + (el.id || '')).toLowerCase();
    if (/search|query|(^|[^a-z])q($|[^a-z])|coupon|promo|newsletter|subscribe|comment|zip|postal|city|first|last/.test(nm)) return false;
    return t === 'email' || /email|e-mail|user(name)?|login|identifier|signin|sign-in|account/.test(nm);
  };
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
  let formFirst = 0;
  const checkForm = () => {
    // pages that keep changing (endless image grids) would postpone this forever, or make it run constantly: at most once a second
    if (formTimer) return;
    formTimer = setTimeout(() => {
      formTimer = null;
      const has = pwFields(document).length > 0 || visibleInputs().some(looksLikeUser);
      if (has !== hadForm) { hadForm = has; ipcRenderer.send('pw:form', has); }
    }, 1000);
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
      login = all.slice(all.indexOf(el) + 1, all.indexOf(el) + 3).some((n) => n.type === 'password') || looksLikeUser(el);
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

  // ---- payment card autofill ----
  const isCardField = (el) => {
    const ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    const nm = (el.name || el.id || '').toLowerCase();
    return ac.includes('cc-number') || ac.includes('cc-csc') || ac.includes('cc-exp') ||
      nm.includes('cardnumber') || nm.includes('card_number') || nm.includes('cc-number') ||
      nm.includes('ccnumber') || nm.includes('card-number');
  };
  const isAddressField = (el) => {
    const ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    return ac.includes('street-address') || ac.includes('address-line1') || ac === 'postal-code' || ac.includes('address-level2');
  };

  let lastCardField = null, lastAddressField = null;
  window.addEventListener('focusin', (e) => {
    const el = e.target;
    if (!(el instanceof HTMLInputElement) || !isVisible(el)) return;
    if (isCardField(el)) {
      lastCardField = el;
      const r = el.getBoundingClientRect();
      ipcRenderer.send('card:focus', { left: r.left, bottom: r.bottom });
    } else if (isAddressField(el)) {
      lastAddressField = el;
      const r = el.getBoundingClientRect();
      ipcRenderer.send('addr:focus', { left: r.left, bottom: r.bottom });
    }
  }, true);

  ipcRenderer.on('card:fill', (_e, d) => {
    if (!d) return;
    const fill = (ac, val) => {
      const f = document.querySelector(`input[autocomplete="${ac}"]`) || document.querySelector(`input[autocomplete*="${ac}"]`);
      if (f && val) setValue(f, val);
    };
    fill('cc-number', d.number ? d.number.replace(/(\d{4})/g, '$1 ').trim() : '');
    fill('cc-name', d.cardName || '');
    fill('cc-exp', d.expiry || '');
    const expParts = (d.expiry || '').split(/[\/\-]/);
    if (expParts.length === 2) { fill('cc-exp-month', expParts[0]); fill('cc-exp-year', expParts[1]); }
    fill('cc-csc', d.cvv || '');
  });

  ipcRenderer.on('addr:fill', (_e, d) => {
    if (!d) return;
    const fill = (ac, val) => {
      const f = document.querySelector(`input[autocomplete="${ac}"]`) || document.querySelector(`input[autocomplete*="${ac}"]`);
      if (f && val) setValue(f, val);
    };
    if (d.fullName) fill('name', d.fullName);
    if (d.street) fill('street-address', d.street);
    if (d.street) fill('address-line1', d.street);
    if (d.street2) fill('address-line2', d.street2);
    if (d.city) fill('address-level2', d.city);
    if (d.state) fill('address-level1', d.state);
    if (d.zip) fill('postal-code', d.zip);
    if (d.country) fill('country', d.country);
    if (d.phone) fill('tel', d.phone);
    if (d.email) fill('email', d.email);
  });
}
