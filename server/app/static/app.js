(() => {
  'use strict';
  const I = (d) => '<svg viewBox="0 0 24 24">' + d + '</svg>';
  const ICONS = {
    profiles: I('<circle cx="12" cy="8" r="3.6"/><path d="M5 20c.8-3.6 3.7-5.4 7-5.4s6.2 1.8 7 5.4"/>'),
    account: I('<rect x="5" y="11" width="14" height="9.5" rx="2.2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>'),
    users: I('<circle cx="9" cy="8.5" r="3.2"/><path d="M3 19c.6-3 3-4.6 6-4.6s5.4 1.6 6 4.6M16 5.6a3.2 3.2 0 0 1 0 5.8M18 14.6c1.8.6 3 2 3.4 4.4"/>'),
    about: I('<circle cx="12" cy="12" r="9"/><path d="M12 11v5.2M12 7.8v.01"/>'),
    out: I('<path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M16 8l4 4-4 4M20 12H9"/>'),
    plus: I('<path d="M12 5v14M5 12h14"/>'),
  };

  // ---------------------------------------------------------------- helpers
  const app = document.getElementById('app');
  const state = { csrf: '', user: null, baseUrl: location.origin, setupNeeded: false, version: '' };

  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'html') el.innerHTML = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid == null || kid === false) continue;
      el.append(kid && kid.nodeType ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }

  async function api(method, path, body) {
    const opts = { method, headers: {}, credentials: 'same-origin' };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    if (state.csrf && method !== 'GET') opts.headers['X-CSRF-Token'] = state.csrf;
    const r = await fetch('/api/gui' + path, opts);
    let j = null;
    try { j = await r.json(); } catch { /* no body */ }
    if (!r.ok) {
      if (r.status === 401 && j && j.signin) { state.user = null; state.csrf = ''; setTimeout(route, 0); }
      const e = new Error((j && j.error) || 'Request failed (' + r.status + ')');
      e.data = j || {}; e.status = r.status;
      throw e;
    }
    if (j && j.csrf) state.csrf = j.csrf;
    return j;
  }

  let toastTimer;
  function toast(msg, kind) {
    document.querySelectorAll('.toast').forEach((t) => t.remove());
    const t = h('div', { class: 'toast ' + (kind || '') }, msg);
    document.body.append(t);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.remove(), 3500);
  }
  const fail = (e) => toast(e.message || String(e), 'bad');

  // ---------------------------------------------------------------- themes (same palettes as the browser)
  // id, name, dark, bg, panel, panel2, fg, muted, line, accent
  const THEMES = [
    ['mbrowser', 'mbrowser', 1, '#0a0e1f', '#111830', '#18214a', '#eaf0ff', '#8f9bc2', '#243056', '#5b8cff'],
    ['midnight', 'Midnight', 1, '#1e1f22', '#2b2d31', '#3a3d44', '#e6e6e6', '#9aa0a6', '#34363b', '#7aa2f7'],
    ['daylight', 'Daylight', 0, '#e4e7ec', '#ffffff', '#d3d7de', '#1f2328', '#656d76', '#c4c9d1', '#3b82f6'],
    ['nord', 'Nord', 1, '#2e3440', '#3b4252', '#434c5e', '#eceff4', '#a3adc0', '#4c566a', '#88c0d0'],
    ['dracula', 'Dracula', 1, '#282a36', '#343746', '#44475a', '#f8f8f2', '#a0a4c0', '#44475a', '#bd93f9'],
    ['gruvbox', 'Gruvbox', 1, '#282828', '#3c3836', '#504945', '#ebdbb2', '#a89984', '#504945', '#fabd2f'],
    ['solarized-dark', 'Solarized Dark', 1, '#002b36', '#073642', '#0d4452', '#eee8d5', '#93a1a1', '#0f4c5c', '#2aa1e8'],
    ['solarized-light', 'Solarized Light', 0, '#eee8d5', '#fdf6e3', '#e3dcc5', '#073642', '#657b83', '#d3cbb2', '#268bd2'],
    ['forest', 'Forest', 1, '#1a2421', '#24332e', '#2f4139', '#e3efe8', '#92ab9f', '#2c3d36', '#6fcf97'],
    ['ocean', 'Ocean', 1, '#0d1b2a', '#14263a', '#1c3550', '#e0ecf7', '#8aa3bd', '#1f3a56', '#38bdf8'],
    ['rose', 'Rose', 1, '#1f1a1d', '#2c2328', '#3a2d34', '#f3e6ec', '#b496a3', '#3a2d34', '#f472b6'],
    ['sand', 'Sand', 0, '#ece5d8', '#faf6ee', '#ddd4c3', '#2b2620', '#7a6f5f', '#cfc5b1', '#c2410c'],
    ['contrast', 'High contrast', 1, '#000000', '#111111', '#222222', '#ffffff', '#cccccc', '#777777', '#ffe600'],
  ];
  const VARS = ['--bg', '--panel', '--panel2', '--fg', '--muted', '--line', '--accent', '--accent-fg'];
  function onColor(hex) {
    const n = parseInt(hex.slice(1), 16), lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255) > 0.35 ? '#000000' : '#ffffff';
  }
  function getTheme() { try { return localStorage.getItem('mb-theme') || 'system'; } catch { return 'system'; } }
  function applyTheme(id) {
    const root = document.documentElement, t = THEMES.find((x) => x[0] === id);
    VARS.forEach((v) => root.style.removeProperty(v));
    if (!t) { root.removeAttribute('data-theme'); root.style.removeProperty('color-scheme'); return; }
    root.setAttribute('data-theme', t[0]);
    ['--bg', '--panel', '--panel2', '--fg', '--muted', '--line', '--accent'].forEach((v, i) => root.style.setProperty(v, t[3 + i]));
    root.style.setProperty('--accent-fg', onColor(t[9]));
    root.style.setProperty('color-scheme', t[2] ? 'dark' : 'light');
  }
  function setTheme(id) { try { localStorage.setItem('mb-theme', id); } catch { /* private mode */ } applyTheme(id); }
  applyTheme(getTheme());

  function ago(ms) {
    if (!ms) return 'never';
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    if (s < 86400 * 30) return Math.floor(s / 86400) + ' days ago';
    return new Date(ms).toLocaleDateString();
  }
  const when = (ms) => (ms ? new Date(ms).toLocaleString() : '—');
  const bytes = (n) => (n > 1e6 ? (n / 1e6).toFixed(1) + ' MB' : n > 1e3 ? Math.round(n / 1e3) + ' KB' : n + ' B');

  function copyText(text) {
    const done = () => toast('Copied', 'good');
    if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, () => legacyCopy(text, done)); return; }
    legacyCopy(text, done);
  }
  function legacyCopy(text, done) {
    const ta = h('textarea', { style: 'position:fixed;opacity:0' }); ta.value = text;
    document.body.append(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch { toast('Select the text and press Ctrl+C', 'bad'); }
    ta.remove();
  }

  // a simple modal: content element(s), buttons [{ text, primary, danger, onclick(close) }]
  function modal(title, content, buttons) {
    const bg = h('div', { class: 'modal-bg' });
    const close = () => bg.remove();
    const box = h('div', { class: 'modal' }, h('h2', null, title), content,
      h('div', { class: 'actions' }, (buttons || [{ text: 'Close' }]).map((b) =>
        h('button', { class: 'btn' + (b.primary ? ' primary' : '') + (b.danger ? ' danger' : ''), type: 'button',
          onclick: async () => { if (b.onclick) { const keep = await b.onclick(close); if (keep === false) return; } close(); } }, b.text))));
    bg.addEventListener('mousedown', (e) => { if (e.target === bg) close(); });
    bg.append(box);
    document.body.append(bg);
    const first = box.querySelector('input'); if (first) first.focus();
    return close;
  }

  function field(label, input) { return h('div', { class: 'field' }, h('label', null, label), input); }

  // ask for text; resolves with the values or null
  function ask(title, fields, okText, danger) {
    return new Promise((resolve) => {
      const inputs = {};
      const body = h('div', null, fields.map((f) => {
        const i = h('input', { type: f.type || 'text', value: f.value || '', placeholder: f.placeholder || '', autocomplete: 'off' });
        inputs[f.key] = i;
        return field(f.label, i);
      }), fields.length === 0 ? null : null);
      const submit = (close) => { const v = {}; for (const k in inputs) v[k] = inputs[k].value; resolve(v); close(); return false; };
      const close = modal(title, body, [
        { text: 'Cancel', onclick: () => { resolve(null); } },
        { text: okText || 'OK', primary: !danger, danger, onclick: submit },
      ]);
      body.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(close); });
    });
  }

  // ---------------------------------------------------------------- routing and layout
  const parse = () => { const p = (location.hash || '#/profiles').replace(/^#\/?/, '').split('/'); return p; };
  window.addEventListener('hashchange', () => route());

  function shell(active, content) {
    const link = (hash, label, icon, key) => h('a', { href: '#/' + hash, class: active === key ? 'on' : '' }, h('span', { html: ICONS[icon] }), label);
    return h('div', { class: 'shell' },
      h('aside', { class: 'side' },
        h('div', { class: 'brand' }, h('img', { src: '/logo.png', alt: '' }), 'mbrowser sync'),
        h('nav', { class: 'nav' },
          link('profiles', 'Profiles', 'profiles', 'profiles'),
          link('account', 'Account & security', 'account', 'account'),
          state.user.isAdmin ? link('users', 'Users', 'users', 'users') : null,
          link('about', 'Connect a browser', 'about', 'about')),
        h('div', { class: 'spacer' }),
        h('label', { class: 'muted small', style: 'display:block;margin:0 4px 4px' }, 'Theme'),
        h('select', { style: 'width:100%;margin-bottom:10px', value: getTheme(), onchange: (e) => setTheme(e.target.value) },
          h('option', { value: 'system' }, 'Follow system'), THEMES.map((t) => h('option', { value: t[0], selected: getTheme() === t[0] }, t[1]))),
        h('div', { class: 'me' }, h('b', null, state.user.username), ' ', state.user.isAdmin ? h('span', { class: 'badge accent' }, 'admin') : null,
          h('div', { class: 'muted small' }, state.user.totpEnabled ? '2FA is on' : '2FA is off'),
          h('button', { class: 'btn sm', style: 'margin-top:8px', onclick: signOut }, h('span', { html: ICONS.out }), 'Sign out'))),
      h('main', { class: 'main' }, content));
  }

  function show(node) { app.replaceChildren(node); window.scrollTo(0, 0); }

  async function signOut() { try { await api('POST', '/logout', {}); } catch { /* ignore */ } state.user = null; state.csrf = ''; location.hash = ''; route(); }

  async function route() {
    try {
      const st = await api('GET', '/state');
      state.setupNeeded = st.setupNeeded; state.version = st.version;
      if (st.authenticated) { state.user = st.user; state.csrf = st.csrf; state.baseUrl = st.baseUrl || location.origin; } else state.user = null;
    } catch (e) { show(h('div', { class: 'center' }, h('div', { class: 'card auth' }, 'Cannot reach the server: ' + e.message))); return; }
    if (state.setupNeeded) return viewSetup();
    if (!state.user) return viewLogin();
    const [page, a, b] = parse();
    try {
      if (page === 'profile' && a) return await viewProfile(a, b || 'overview');
      if (page === 'account') return await viewAccount();
      if (page === 'users' && state.user.isAdmin) return await viewUsers();
      if (page === 'about') return viewAbout();
      return await viewProfiles();
    } catch (e) { fail(e); }
  }

  // ---------------------------------------------------------------- setup and login
  function authCard(title, lead, body) {
    return h('div', { class: 'center' }, h('div', { class: 'card auth' },
      h('div', { class: 'brand' }, h('img', { src: '/logo.png', alt: '' }), 'mbrowser sync'), h('p', { class: 'lead' }, lead), body));
  }

  function viewSetup() {
    const user = h('input', { type: 'text', autocomplete: 'username' });
    const pw = h('input', { type: 'password', autocomplete: 'new-password' });
    const pw2 = h('input', { type: 'password', autocomplete: 'new-password' });
    const err = h('div', { class: 'bad small' });
    const form = h('form', { onsubmit: async (e) => {
      e.preventDefault(); err.textContent = '';
      if (pw.value !== pw2.value) { err.textContent = 'The two passwords do not match.'; return; }
      try { await api('POST', '/setup', { username: user.value, password: pw.value }); location.hash = '#/profiles'; route(); }
      catch (ex) { err.textContent = ex.message; }
    } }, field('Administrator name', user), field('Password (at least 8 characters)', pw), field('Repeat password', pw2), err,
    h('button', { class: 'btn primary', style: 'width:100%;justify-content:center' }, 'Create account'));
    show(authCard('Welcome', 'Welcome! Create the administrator account for this server.', form));
    user.focus();
  }

  function viewLogin(needTotp, note) {
    const user = h('input', { type: 'text', autocomplete: 'username', value: viewLogin.last || '' });
    const pw = h('input', { type: 'password', autocomplete: 'current-password' });
    const code = h('input', { type: 'text', inputmode: 'numeric', autocomplete: 'one-time-code', placeholder: '123456' });
    const rec = h('input', { type: 'text', placeholder: 'xxxxx-xxxxx', autocomplete: 'off' });
    const err = h('div', { class: 'bad small' }, note || '');
    let useRecovery = false;
    const recBox = h('div', { style: 'display:none' }, field('Recovery code', rec));
    const codeBox = h('div', { style: needTotp ? '' : 'display:none' }, field('Authenticator code', code));
    const toggle = h('a', { href: '#', style: needTotp ? 'font-size:13px' : 'display:none', onclick: (e) => {
      e.preventDefault(); useRecovery = !useRecovery;
      recBox.style.display = useRecovery ? '' : 'none'; codeBox.style.display = useRecovery ? 'none' : '';
      toggle.textContent = useRecovery ? 'Use the authenticator code instead' : 'Lost your phone? Use a recovery code';
    } }, 'Lost your phone? Use a recovery code');
    const form = h('form', { onsubmit: async (e) => {
      e.preventDefault(); err.textContent = '';
      viewLogin.last = user.value;
      try {
        await api('POST', '/login', { username: user.value, password: pw.value, totp: useRecovery ? '' : code.value, recovery: useRecovery ? rec.value : '' });
        route();
      } catch (ex) {
        if (ex.data && ex.data.needTotp && !needTotp) { viewLogin.pw = pw.value; return viewLogin(true, ''); }
        err.textContent = ex.message;
      }
    } }, field('User name', user), field('Password', pw), codeBox, recBox, err, h('p', null, toggle),
    h('button', { class: 'btn primary', style: 'width:100%;justify-content:center' }, 'Sign in'));
    show(authCard('Sign in', 'Sign in to manage your synced browsers.', form));
    if (needTotp && viewLogin.pw) { pw.value = viewLogin.pw; viewLogin.pw = ''; code.focus(); } else (user.value ? pw : user).focus();
  }

  // ---------------------------------------------------------------- profiles
  async function viewProfiles() {
    const { profiles } = await api('GET', '/profiles');
    const create = async () => {
      const v = await ask('New profile', [{ key: 'name', label: 'Name (for example Personal or Work)' }], 'Create');
      if (!v) return;
      try { const p = await api('POST', '/profiles', { name: v.name }); location.hash = '#/profile/' + p.id + '/keys'; } catch (e) { fail(e); }
    };
    const cards = profiles.map((p) => h('div', { class: 'card pcard', onclick: () => { location.hash = '#/profile/' + p.id + '/overview'; } },
      h('div', { class: 'row', style: 'justify-content:space-between' }, h('h2', { style: 'margin:0' }, p.name), p.stats.vault ? h('span', { class: 'badge ok' }, 'vault') : null),
      h('div', { class: 'muted small' }, 'Last activity: ' + ago(p.stats.lastSync)),
      h('div', { class: 'stats' },
        h('div', { class: 'stat' }, h('b', null, p.stats.bookmarks), h('span', null, 'bookmarks')),
        h('div', { class: 'stat' }, h('b', null, p.stats.history), h('span', null, 'history')),
        h('div', { class: 'stat' }, h('b', null, p.stats.devices), h('span', null, 'devices')),
        h('div', { class: 'stat' }, h('b', null, p.keys), h('span', null, 'keys')))));
    show(shell('profiles', h('div', null,
      h('div', { class: 'head' }, h('div', null, h('h1', null, 'Profiles'), h('div', { class: 'muted' }, 'Each profile is a separate set of synced data: for example Personal and Work.')),
        h('button', { class: 'btn primary', onclick: create }, h('span', { html: ICONS.plus }), 'New profile')),
      profiles.length ? h('div', { class: 'grid' }, cards)
        : h('div', { class: 'card empty' }, 'No profile yet. Create one, then connect your browser to it.'))));
  }

  const PTABS = [['overview', 'Overview'], ['keys', 'API keys'], ['bookmarks', 'Bookmarks'], ['history', 'History'], ['devices', 'Devices & tabs'], ['passwords', 'Passwords'], ['data', 'Settings & vault'], ['manage', 'Manage']];

  async function viewProfile(id, tab) {
    const p = await api('GET', '/profiles/' + id + '/overview');
    const body = h('div');
    const tabs = h('div', { class: 'tabs' }, PTABS.map(([k, label]) => h('a', { href: '#/profile/' + id + '/' + k, class: tab === k ? 'on' : '' }, label)));
    show(shell('profiles', h('div', null,
      h('div', { class: 'head' }, h('div', null, h('a', { href: '#/profiles', class: 'muted small' }, '← All profiles'), h('h1', null, p.name),
        h('div', { class: 'muted' }, 'Last activity ' + ago(p.stats.lastSync)))),
      tabs, body)));
    const fn = { overview: tOverview, keys: tKeys, bookmarks: tBookmarks, history: tHistory, devices: tDevices, passwords: tPasswords, data: tData, manage: tManage }[tab] || tOverview;
    await fn(body, p);
  }

  function tOverview(body, p) {
    const s = p.stats;
    const tile = (n, l) => h('div', { class: 'stat' }, h('b', null, n), h('span', null, l));
    body.append(
      h('div', { class: 'card' }, h('h2', null, 'What is stored'),
        h('div', { class: 'stats' }, tile(s.bookmarks, 'bookmarks'), tile(s.history, 'history entries'), tile(s.devices, 'devices with open tabs'),
          tile(s.vault ? 'yes' : 'no', 'encrypted password vault'), tile(s.settings ? s.settings.groups.length : 0, 'synced settings groups')),
        s.vault ? h('p', { class: 'muted small' }, 'The vault is end-to-end encrypted by your browser. This server stores it but cannot read it (' + bytes(s.vault.bytes) + ', changed ' + ago(s.vault.updated) + ').') : null),
      h('div', { class: 'card' }, h('h2', null, 'Connect a browser to this profile'),
        h('ol', { class: 'steps' },
          h('li', null, 'In mbrowser open ', h('b', null, 'Settings → Sync'), '.'),
          h('li', null, 'Server address: ', h('span', { class: 'mono' }, state.baseUrl), ' ', h('button', { class: 'btn sm', onclick: () => copyText(state.baseUrl) }, 'Copy')),
          h('li', null, 'Either ', h('b', null, 'sign in with your account'), ' (user name, password and 2FA code if enabled; pick the profile "' + p.name + '"), or paste an ',
            h('a', { href: '#/profile/' + p.id + '/keys' }, 'API key'), '.'),
          h('li', null, 'Press ', h('b', null, 'Sync now'), ' or ', h('b', null, 'Test connection'), '.'))));
  }

  async function tKeys(body, p) {
    const { keys } = await api('GET', '/profiles/' + p.id + '/keys');
    const rows = keys.map((k) => {
      const inp = h('input', { type: 'password', value: k.token, readonly: true, class: 'mono', style: 'width:250px' });
      const show = h('button', { class: 'btn sm', onclick: () => { const on = inp.type === 'password'; inp.type = on ? 'text' : 'password'; show.textContent = on ? 'Hide' : 'Show'; } }, 'Show');
      return h('tr', null,
        h('td', null, h('b', null, k.name), h('div', { class: 'small' }, h('span', { class: 'badge ' + (k.kind === 'device' ? 'accent' : k.kind === 'legacy' ? 'warn' : '') }, k.kind === 'device' ? 'browser sign-in' : k.kind === 'legacy' ? 'from the old server' : 'API key'))),
        h('td', { class: 'small muted' }, when(k.created)),
        h('td', { class: 'small muted' }, k.lastUsed ? ago(k.lastUsed) + (k.lastDevice ? ' · ' + k.lastDevice : '') : 'never'),
        h('td', null, h('div', { class: 'keybox' }, inp, show, h('button', { class: 'btn sm', onclick: () => copyText(k.token) }, 'Copy'))),
        h('td', null, h('button', { class: 'btn sm danger', onclick: async () => {
          if (!confirm('Revoke "' + k.name + '"? Browsers using it will stop syncing until they sign in again.')) return;
          try { await api('DELETE', '/keys/' + k.id); route(); } catch (e) { fail(e); } } }, 'Revoke')));
    });
    const name = h('input', { type: 'text', placeholder: 'Name, for example "Home laptop"', style: 'min-width:260px' });
    body.append(
      h('div', { class: 'card' }, h('h2', null, 'API keys'),
        h('p', { class: 'muted' }, 'A key lets one browser sync with this profile. You can view and copy keys here at any time, and revoke them individually. Signing in from the browser with your account creates a "browser sign-in" key automatically.'),
        keys.length ? h('div', { class: 'tablewrap' }, h('table', null, h('thead', null, h('tr', null, ['Name', 'Created', 'Last used', 'Key', ''].map((t) => h('th', null, t)))), h('tbody', null, rows)))
          : h('div', { class: 'empty' }, 'No keys yet.')),
      h('div', { class: 'card' }, h('h3', null, 'Create a new key'),
        h('div', { class: 'row' }, name, h('button', { class: 'btn primary', onclick: async () => {
          try { await api('POST', '/profiles/' + p.id + '/keys', { name: name.value }); toast('Key created', 'good'); route(); } catch (e) { fail(e); } } }, 'Create key'))));
  }

  async function tBookmarks(body, p) {
    const q = h('input', { type: 'search', placeholder: 'Search title, address or folder', style: 'min-width:260px' });
    const out = h('div');
    const picked = new Set();
    const del = h('button', { class: 'btn danger', style: 'display:none', onclick: async () => {
      if (!confirm('Delete ' + picked.size + ' bookmark(s)? Browsers will remove them at their next sync.')) return;
      try { await api('DELETE', '/profiles/' + p.id + '/bookmarks', { urls: [...picked] }); picked.clear(); load(); } catch (e) { fail(e); } } }, 'Delete selected');
    async function load() {
      const r = await api('GET', '/profiles/' + p.id + '/bookmarks?limit=500&q=' + encodeURIComponent(q.value));
      del.style.display = picked.size ? '' : 'none';
      out.replaceChildren(r.items.length ? h('div', { class: 'tablewrap' }, h('table', null,
        h('thead', null, h('tr', null, ['', 'Title', 'Folder', 'Address'].map((t) => h('th', null, t)))),
        h('tbody', null, r.items.map((b) => h('tr', null,
          h('td', null, h('input', { type: 'checkbox', checked: picked.has(b.url), onchange: (e) => { if (e.target.checked) picked.add(b.url); else picked.delete(b.url); del.style.display = picked.size ? '' : 'none'; } })),
          h('td', null, b.title || b.url), h('td', { class: 'muted' }, b.folder || '—'), h('td', { class: 'url' }, h('a', { href: b.url, target: '_blank', rel: 'noopener noreferrer' }, b.url)))))))
        : h('div', { class: 'empty' }, q.value ? 'No bookmarks match.' : 'No bookmarks synced yet.'),
        h('div', { class: 'muted small' }, r.total + ' bookmark' + (r.total === 1 ? '' : 's') + (r.total > r.items.length ? ' (showing the first ' + r.items.length + ')' : '')));
    }
    let t; q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(load, 250); });
    body.append(h('div', { class: 'card' }, h('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:12px' },
      h('div', { class: 'row' }, q, del), h('a', { class: 'btn', href: '/api/gui/profiles/' + p.id + '/export/bookmarks.html' }, 'Export as HTML')), out));
    await load();
  }

  async function tHistory(body, p) {
    const q = h('input', { type: 'search', placeholder: 'Search history', style: 'min-width:260px' });
    const out = h('div');
    async function load() {
      const r = await api('GET', '/profiles/' + p.id + '/history?limit=300&q=' + encodeURIComponent(q.value));
      out.replaceChildren(r.items.length ? h('div', { class: 'tablewrap' }, h('table', null,
        h('thead', null, h('tr', null, ['When', 'Title', 'Address', ''].map((t) => h('th', null, t)))),
        h('tbody', null, r.items.map((x) => h('tr', null, h('td', { class: 'small muted' }, when(x.time)), h('td', null, x.title || '—'),
          h('td', { class: 'url' }, x.url),
          h('td', null, h('button', { class: 'btn sm danger', onclick: async () => { try { await api('DELETE', '/profiles/' + p.id + '/history', { urls: [x.url] }); load(); } catch (e) { fail(e); } } }, 'Remove')))))))
        : h('div', { class: 'empty' }, 'No history.'), h('div', { class: 'muted small' }, r.total + ' entries'));
    }
    let t; q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(load, 250); });
    body.append(h('div', { class: 'card' },
      h('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:12px' }, q, h('div', { class: 'row' },
        h('a', { class: 'btn', href: '/api/gui/profiles/' + p.id + '/export/history.json' }, 'Export JSON'),
        h('button', { class: 'btn danger', onclick: async () => {
          if (!confirm('Delete all history stored on the server? Your browsers keep their own copy.')) return;
          try { await api('DELETE', '/profiles/' + p.id + '/history', { all: true }); load(); } catch (e) { fail(e); } } }, 'Delete all'))), out));
    await load();
  }

  async function tDevices(body, p) {
    const { devices } = await api('GET', '/profiles/' + p.id + '/devices');
    body.append(...(devices.length ? devices.map((d) => h('div', { class: 'card' },
      h('div', { class: 'row', style: 'justify-content:space-between' }, h('h2', { style: 'margin:0' }, d.device), h('div', { class: 'row' },
        h('span', { class: 'muted small' }, 'updated ' + ago(d.updated)),
        h('button', { class: 'btn sm danger', onclick: async () => { try { await api('DELETE', '/profiles/' + p.id + '/devices/' + encodeURIComponent(d.device)); route(); } catch (e) { fail(e); } } }, 'Forget'))),
      (d.tabs || []).length ? h('ul', { style: 'padding-left:18px;line-height:1.7' }, d.tabs.map((t) => h('li', null, h('a', { href: /^https?:/i.test(t.url) ? t.url : '#', target: '_blank', rel: 'noopener noreferrer' }, t.title || t.url)))) : h('div', { class: 'muted' }, 'No open tabs shared.')))
      : [h('div', { class: 'card empty' }, 'No device has shared its open tabs yet.')]));
  }

  function download(name, text, type) {
    const url = URL.createObjectURL(new Blob([text], { type: type || 'text/plain' }));
    const l = h('a', { href: url, download: name }); document.body.append(l); l.click(); l.remove(); setTimeout(() => URL.revokeObjectURL(url), 5000);
  }
  function csvCell(v) { v = String(v == null ? '' : v); return /[",\n\r]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v; }

  async function tPasswords(body, p) {
    const info = await api('GET', '/profiles/' + p.id + '/vault');
    if (!info.exists) { body.append(h('div', { class: 'card empty' }, 'No vault has been synced to this profile yet. Turn on password sync in a browser and it will appear here.')); return; }
    const blob = info.blob, dev = !!blob.device;
    const secret = h('input', { type: 'password', autocomplete: 'off', placeholder: dev ? 'Device key (base64)' : 'Master password', style: 'min-width:260px' });
    const status = h('div', { class: 'muted small', style: 'margin-top:8px' });
    const out = h('div');
    const go = async () => {
      if (!secret.value) return;
      if (!window.crypto || !crypto.subtle) { status.textContent = 'Your browser only allows this over HTTPS (or on localhost). Open the server through its https:// address.'; status.className = 'bad small'; return; }
      status.className = 'muted small'; status.textContent = 'Unlocking…'; btn.disabled = true;
      try {
        const v = await MBVault.open(blob, secret.value, (f) => { status.textContent = 'Unlocking… ' + Math.round(f * 100) + '%'; });
        secret.value = ''; status.textContent = '';
        show_(v);
      } catch (e) { status.className = 'bad small'; status.textContent = e.message || String(e); btn.disabled = false; }
    };
    const btn = h('button', { class: 'btn primary', onclick: go }, 'Unlock');
    secret.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    body.append(h('div', { class: 'card' }, h('h2', null, 'Password vault'),
      h('p', { class: 'muted' }, 'The vault is decrypted here in your browser. Your ' + (dev ? 'device key' : 'master password') + ' is never sent to the server, and nothing is stored on this page: reload and it locks again.'),
      h('div', { class: 'row' }, secret, btn, h('button', { class: 'btn', title: 'Still encrypted: only your master password (or device key) opens it', onclick: () => download('mbrowser-vault-backup-' + new Date().toISOString().slice(0, 10) + '.json', JSON.stringify(blob), 'application/json') }, 'Download encrypted backup')), status), out);

    function show_(v) {
      const pws = v.entries.filter((e) => !e.type || e.type === 'password');
      const cards = v.entries.filter((e) => e.type === 'card').length, addrs = v.entries.filter((e) => e.type === 'address').length;
      const q = h('input', { type: 'search', placeholder: 'Search ' + pws.length + ' passwords', style: 'min-width:260px' });
      const list = h('div', { class: 'tablewrap' });
      let timer = null;
      const lock = () => { out.replaceChildren(); secret.disabled = false; btn.disabled = false; status.textContent = 'Locked.'; };
      const render = () => {
        const s = q.value.trim().toLowerCase();
        const rows = pws.filter((e) => !s || [e.name, e.origin, e.username, e.folder].some((x) => String(x || '').toLowerCase().includes(s)))
          .sort((x, y) => String(x.name || x.origin).localeCompare(String(y.name || y.origin)));
        list.replaceChildren(rows.length ? h('table', null, h('thead', null, h('tr', null, ['Site', 'User name', 'Password', 'Folder'].map((t) => h('th', null, t)))),
          h('tbody', null, rows.map((e) => {
            const pass = h('span', { class: 'mono' }, '••••••••');
            let shown = false;
            return h('tr', null,
              h('td', null, h('b', null, e.name || e.origin || ''), e.origin ? h('div', { class: 'muted small' }, e.origin) : null),
              h('td', null, e.username || '', ' ', e.username ? h('button', { class: 'btn sm', onclick: () => copyText(e.username) }, 'Copy') : null),
              h('td', null, pass, ' ',
                h('button', { class: 'btn sm', onclick: (ev) => { shown = !shown; pass.textContent = shown ? e.password : '••••••••'; ev.target.textContent = shown ? 'Hide' : 'Show'; } }, 'Show'), ' ',
                h('button', { class: 'btn sm', onclick: () => copyText(e.password) }, 'Copy')),
              h('td', { class: 'muted' }, e.folder || ''));
          }))) : h('div', { class: 'empty' }, 'Nothing matches.'));
      };
      q.addEventListener('input', render);
      render();
      out.replaceChildren(h('div', { class: 'card' },
        h('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:12px' }, q,
          h('div', { class: 'row' }, cards || addrs ? h('span', { class: 'muted small' }, cards + ' cards and ' + addrs + ' addresses are in the vault too; open them in the browser.') : null,
            h('button', { class: 'btn', onclick: () => {
              if (!confirm('This file contains every password in plain text. Keep it somewhere safe and delete it when you are done. Continue?')) return;
              const head = ['name', 'url', 'username', 'password', 'note', 'folder'];
              const lines = [head.join(',')].concat(pws.map((e) => [e.name, e.origin, e.username, e.password, e.note, e.folder].map(csvCell).join(',')));
              download('mbrowser-passwords-' + new Date().toISOString().slice(0, 10) + '.csv', lines.join('\r\n'), 'text/csv');
            } }, 'Export CSV'),
            h('button', { class: 'btn', onclick: lock }, 'Lock'))), list,
        h('p', { class: 'muted small' }, 'This view is read-only. Add and change passwords in the browser; they sync here. It locks by itself after 5 minutes.')));
      clearTimeout(timer); timer = setTimeout(lock, 5 * 60 * 1000);
    }
  }

  async function tData(body, p) {
    const s = p.stats;
    body.append(
      h('div', { class: 'card' }, h('h2', null, 'Synced settings'),
        s.settings ? h('div', null,
          h('p', { class: 'muted' }, 'Groups: ' + (s.settings.groups.join(', ') || 'none') + ' · version ' + s.settings.version + ' · changed ' + ago(s.settings.updated)),
          h('div', { class: 'row' },
            h('button', { class: 'btn', onclick: async () => { const d = await api('GET', '/profiles/' + p.id + '/settings'); modal('Settings document', h('pre', { style: 'max-height:50vh;overflow:auto;font-size:12px' }, JSON.stringify(d.doc, null, 2)), [{ text: 'Close' }]); } }, 'View'),
            h('button', { class: 'btn danger', onclick: async () => { if (!confirm('Delete the synced settings? Browsers will upload theirs again at the next sync.')) return; try { await api('DELETE', '/profiles/' + p.id + '/settings'); route(); } catch (e) { fail(e); } } }, 'Delete')))
          : h('p', { class: 'muted' }, 'No settings have been synced yet.')),
      h('div', { class: 'card' }, h('h2', null, 'Password vault'),
        s.vault ? h('div', null,
          h('p', { class: 'muted' }, 'An encrypted vault is stored (' + bytes(s.vault.bytes) + ', version ' + s.vault.version + ', changed ' + ago(s.vault.updated) + '). It is encrypted by your browser; the server cannot read it. Open the Passwords tab to unlock and view it here.'),
          h('button', { class: 'btn danger', onclick: async () => { if (!confirm('Delete the stored vault? Browsers that still have a copy will upload it again at the next sync.')) return; try { await api('DELETE', '/profiles/' + p.id + '/vault'); route(); } catch (e) { fail(e); } } }, 'Delete stored vault'))
          : h('p', { class: 'muted' }, 'No vault has been synced yet.')));
  }

  function tManage(body, p) {
    const name = h('input', { type: 'text', value: p.name });
    body.append(
      h('div', { class: 'card' }, h('h2', null, 'Rename'), h('div', { class: 'row' }, name,
        h('button', { class: 'btn primary', onclick: async () => { try { await api('PATCH', '/profiles/' + p.id, { name: name.value }); toast('Renamed', 'good'); route(); } catch (e) { fail(e); } } }, 'Save'))),
      h('div', { class: 'card danger-zone' }, h('h2', null, 'Delete this profile'),
        h('p', { class: 'muted' }, 'Deletes all bookmarks, history, settings, the vault and all API keys of this profile from the server. Browsers keep their own local data.'),
        h('button', { class: 'btn danger', onclick: async () => {
          const v = await ask('Delete "' + p.name + '"?', [{ key: 'confirm', label: 'Type the profile name to confirm', placeholder: p.name }], 'Delete forever', true);
          if (!v) return;
          try { await api('DELETE', '/profiles/' + p.id, { confirm: v.confirm }); toast('Profile deleted', 'good'); location.hash = '#/profiles'; } catch (e) { fail(e); } } }, 'Delete profile')));
  }

  // ---------------------------------------------------------------- account
  async function viewAccount() {
    const st = await api('GET', '/state');
    state.user = st.user;
    const u = st.user;
    const cur = h('input', { type: 'password', autocomplete: 'current-password' });
    const nw = h('input', { type: 'password', autocomplete: 'new-password' });
    const nw2 = h('input', { type: 'password', autocomplete: 'new-password' });

    const showCodes = (codes, title) => modal(title || 'Recovery codes', h('div', null,
      h('p', { class: 'muted' }, 'Keep these somewhere safe. Each code works once if you lose your phone. They are shown only now.'),
      h('div', { class: 'codes' }, codes.map((c) => h('span', null, c))),
      h('div', { class: 'row' }, h('button', { class: 'btn sm', onclick: () => copyText(codes.join('\n')) }, 'Copy'),
        h('button', { class: 'btn sm', onclick: () => {
          const a = h('a', { href: 'data:text/plain;charset=utf-8,' + encodeURIComponent('mbrowser sync recovery codes\n\n' + codes.join('\n') + '\n'), download: 'mbrowser-recovery-codes.txt' }); document.body.append(a); a.click(); a.remove(); } }, 'Download'))),
      [{ text: 'I saved them', primary: true }]);

    async function enable2fa() {
      const v = await ask('Turn on two-factor authentication', [{ key: 'password', label: 'Your password', type: 'password' }], 'Continue');
      if (!v) return;
      let info;
      try { info = await api('POST', '/2fa/begin', { password: v.password }); } catch (e) { return fail(e); }
      const code = h('input', { type: 'text', inputmode: 'numeric', placeholder: '123456', autocomplete: 'off' });
      modal('Scan with your authenticator app', h('div', null,
        info.qr ? h('div', { class: 'qr', html: info.qr }) : null,
        h('p', { class: 'muted small', style: 'text-align:center' }, 'Or enter this key by hand: ', h('span', { class: 'mono' }, info.secret.replace(/(.{4})/g, '$1 ').trim())),
        field('Then enter the 6-digit code it shows', code)),
      [{ text: 'Cancel' }, { text: 'Turn on', primary: true, onclick: async () => {
        try { const r = await api('POST', '/2fa/enable', { code: code.value }); route(); showCodes(r.recovery); } catch (e) { fail(e); return false; } } }]);
    }
    async function disable2fa() {
      const v = await ask('Turn off two-factor authentication', [{ key: 'password', label: 'Your password', type: 'password' }, { key: 'code', label: 'Current authenticator code (or a recovery code)' }], 'Turn off', true);
      if (!v) return;
      const isCode = /^\d{6}$/.test(v.code.replace(/\s/g, ''));
      try { await api('POST', '/2fa/disable', { password: v.password, code: isCode ? v.code : '', recovery: isCode ? '' : v.code }); toast('Two-factor authentication is off', 'good'); route(); } catch (e) { fail(e); }
    }
    async function newCodes() {
      const v = await ask('New recovery codes', [{ key: 'password', label: 'Your password', type: 'password' }, { key: 'code', label: 'Current authenticator code' }], 'Create new codes');
      if (!v) return;
      try { const r = await api('POST', '/2fa/recovery', { password: v.password, code: v.code }); route(); showCodes(r.recovery, 'New recovery codes'); } catch (e) { fail(e); }
    }

    show(shell('account', h('div', null,
      h('div', { class: 'head' }, h('div', null, h('h1', null, 'Account & security'), h('div', { class: 'muted' }, 'Signed in as ' + u.username))),
      h('div', { class: 'card' }, h('h2', null, 'Two-factor authentication'),
        u.totpEnabled
          ? h('div', null, h('p', null, h('span', { class: 'badge ok' }, 'On'), ' ', h('span', { class: 'muted' }, 'You need a code from your authenticator app to sign in to this website and to sign in from a browser. Recovery codes left: ' + (st.recoveryLeft ?? 0) + '.')),
            h('div', { class: 'row' }, h('button', { class: 'btn', onclick: newCodes }, 'New recovery codes'), h('button', { class: 'btn danger', onclick: disable2fa }, 'Turn off')))
          : h('div', null, h('p', { class: 'muted' }, 'Optional, and recommended if this server can be reached from the internet. Works with any authenticator app (Google Authenticator, Aegis, 2FAS, Bitwarden, …). API keys are not affected.'),
            h('button', { class: 'btn primary', onclick: enable2fa }, 'Turn on'))),
      h('div', { class: 'card' }, h('h2', null, 'Change password'),
        h('div', { style: 'max-width:380px' }, field('Current password', cur), field('New password (at least 8 characters)', nw), field('Repeat new password', nw2),
          h('button', { class: 'btn primary', onclick: async () => {
            if (nw.value !== nw2.value) return toast('The new passwords do not match.', 'bad');
            try { await api('POST', '/password', { current: cur.value, new: nw.value }); cur.value = nw.value = nw2.value = ''; toast('Password changed. Other devices were signed out.', 'good'); } catch (e) { fail(e); } } }, 'Change password'))),
      h('div', { class: 'card' }, h('h2', null, 'Sessions'),
        h('p', { class: 'muted' }, 'Sign out of this website on all other browsers and computers. Browsers that sync keep working: revoke their keys on the profile page.'),
        h('button', { class: 'btn', onclick: async () => { try { await api('POST', '/sessions/revoke-others', {}); toast('Other sessions were signed out', 'good'); } catch (e) { fail(e); } } }, 'Sign out everywhere else')))));
  }

  // ---------------------------------------------------------------- users (admin)
  async function viewUsers() {
    const { users } = await api('GET', '/users');
    const add = async () => {
      const v = await ask('New user', [{ key: 'username', label: 'User name' }, { key: 'password', label: 'Password (at least 8 characters)', type: 'password' }], 'Create');
      if (!v) return;
      const admin = confirm('Make "' + v.username + '" an administrator?\n\nOK = administrator, Cancel = normal user');
      try { await api('POST', '/users', { username: v.username, password: v.password, isAdmin: admin }); toast('User created', 'good'); route(); } catch (e) { fail(e); }
    };
    const rows = users.map((u) => h('tr', null,
      h('td', null, h('b', null, u.username), ' ', u.id === state.user.id ? h('span', { class: 'badge' }, 'you') : null, ' ', u.is_admin ? h('span', { class: 'badge accent' }, 'admin') : null, ' ', u.disabled ? h('span', { class: 'badge warn' }, 'disabled') : null),
      h('td', { class: 'small muted' }, u.profiles + ' profile' + (u.profiles === 1 ? '' : 's')),
      h('td', { class: 'small' }, u.totp_enabled ? h('span', { class: 'ok' }, '2FA on') : h('span', { class: 'muted' }, '2FA off')),
      h('td', { class: 'small muted' }, when(u.created)),
      h('td', null, h('div', { class: 'row' },
        h('button', { class: 'btn sm', onclick: async () => { const v = await ask('New password for ' + u.username, [{ key: 'password', label: 'New password (at least 8 characters)', type: 'password' }], 'Set password'); if (!v) return; try { await api('POST', '/users/' + u.id + '/password', { password: v.password }); toast('Password set', 'good'); } catch (e) { fail(e); } } }, 'Reset password'),
        u.totp_enabled ? h('button', { class: 'btn sm', onclick: async () => { if (!confirm('Turn off 2FA for ' + u.username + '?')) return; try { await api('POST', '/users/' + u.id + '/disable2fa', {}); route(); } catch (e) { fail(e); } } }, 'Remove 2FA') : null,
        u.id === state.user.id ? null : h('button', { class: 'btn sm', onclick: async () => { try { await api('POST', '/users/' + u.id + '/toggle', {}); route(); } catch (e) { fail(e); } } }, u.disabled ? 'Enable' : 'Disable'),
        u.id === state.user.id ? null : h('button', { class: 'btn sm danger', onclick: async () => {
          const v = await ask('Delete ' + u.username + '?', [{ key: 'confirm', label: 'Type the user name to confirm. Their profiles and all synced data will be deleted.' }], 'Delete forever', true);
          if (!v) return; try { await api('DELETE', '/users/' + u.id, { confirm: v.confirm }); route(); } catch (e) { fail(e); } } }, 'Delete')))));
    show(shell('users', h('div', null,
      h('div', { class: 'head' }, h('div', null, h('h1', null, 'Users'), h('div', { class: 'muted' }, 'Everyone who can sign in. Each user only sees their own profiles.')),
        h('button', { class: 'btn primary', onclick: add }, h('span', { html: ICONS.plus }), 'New user')),
      h('div', { class: 'card' }, h('div', { class: 'tablewrap' }, h('table', null, h('thead', null, h('tr', null, ['User', 'Profiles', 'Security', 'Created', ''].map((t) => h('th', null, t)))), h('tbody', null, rows)))))));
  }

  // ---------------------------------------------------------------- about / connect
  function viewAbout() {
    show(shell('about', h('div', null,
      h('div', { class: 'head' }, h('div', null, h('h1', null, 'Connect a browser'), h('div', { class: 'muted' }, 'mbrowser sync server ' + state.version))),
      h('div', { class: 'card' }, h('h2', null, 'Server address'),
        h('div', { class: 'keybox' }, h('input', { type: 'text', value: state.baseUrl, readonly: true }), h('button', { class: 'btn', onclick: () => copyText(state.baseUrl) }, 'Copy')),
        h('p', { class: 'muted small' }, 'Use the address you reach this page with. Behind a reverse proxy (for example Nginx Proxy Manager) use your https address and do not add an access list: mbrowser authenticates itself.')),
      h('div', { class: 'card' }, h('h2', null, 'Two ways to sign in from the browser'),
        h('ol', { class: 'steps' },
          h('li', null, h('b', null, 'With your account:'), ' Settings → Sync → Account. Enter your user name and password (and the 2FA code). If you have several profiles you choose one.'),
          h('li', null, h('b', null, 'With an API key:'), ' create or copy one on the profile’s ', h('i', null, 'API keys'), ' tab and paste it into Settings → Sync → API key.')),
        h('p', { class: 'muted small' }, 'Only the encrypted password vault is stored for passwords: this server never sees your master password or your passwords.')))));
  }

  window.__mb = { h, route, state, api };
  route();
})();
