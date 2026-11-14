# mbrowser — project guide

Electron 33 browser (Linux/CachyOS Wayland first, Windows, Android later) that syncs bookmarks,
history, tabs, settings and an encrypted password vault to a self-hosted Docker server (`server/`).
The user runs the **system** electron on Linux (`sudo pacman -S electron`, then `electron .`),
not npm's. Browser version in package.json: 0.8.0. Server: 1.0.0 (stdlib-only Python, SQLite).

## Layout
- `main.js` windows (BaseWindow + WebContentsView), tabs, IPC, sync, vault, profiles, downloads, menus.
  Per-window `Win` object with module-level `cur` / `use(w)`.
- `preload.js` (chrome UI -> `window.api`), `page-preload.js` (my:// pages -> `window.browser`; also login-form detection), `gate-preload.js`.
- `ui.html` toolbar/tabs/status, `overlay.html` popups, `resizer.html`, `gate.html`.
- `pages/` internal my:// pages: start, settings, bookmarks, history, passwords, import, profiles, panel, downloads, error, library, common.css.
- `lib/` settings, settingssync, vault, vaultsync, profiles, importers, blocker, themes.
- `assets/icons/` app icons (shipped via extraResources). `server/` the Docker sync server + web GUI.
- IPC handlers are guarded (`on`/`handle` with chrome/page/BOTH/PAGE_ONLY): keep new handlers guarded.
- Theme via CSS vars (--bg --tab --active --fg --muted --line --accent --onaccent) at `my://assets/theme.css`; icons are inline SVG with currentColor.
- Profiles are separate Electron instances (`--profile=<id>`, single-instance lock each).
- Vault: AES-256-GCM, scrypt key, tombstones, merge by `updated`. Sync uses rev counters (bookmarks/history) and optimistic concurrency with `base` + 409 (settings/vault).

## Known gotchas
- Docker Compose mangles `$word` in env values.
- Behind Nginx Proxy Manager an Access List returns its own 401.
- electron-builder on Windows needs Developer Mode (winCodeSign symlinks) or `-c.win.signAndEditExecutable=false` (see build-windows.cmd). Run from the project folder.
- Google sign-in says "browser may not be secure"; a Chrome-like UA alone does not fix it.

## STATE: the 0.9 work was lost
A sandbox reset lost the 0.9 work-in-progress. This tree is the saved 0.8.0. Redo first:
default icon white-m (+ Linux launcher icon cache refresh), AppImage target (`dist:linux`),
profile avatars/default/open-last/reset, vault "device" mode (no master password, key via safeStorage),
Cards/Addresses in vault, setupDone flag + `my://welcome` wizard.

## TODO (user's list, in order)
1. First-run wizard: connect to server or standalone; pick/create server profile; rename + choose default profile; open-last-profile setting. Server needs `POST /api/auth/device-profile` and `list:true` on device-login.
2. Profile UI: avatars, default, open-last, reset profile / reset app.
3. Passwords UI: no master password (optional), Cards (payment) + Addresses autofill.
4. Bookmarks: manager as default, Vivaldi-like context menus, add-all-to-speed-dial keeping folders, recycle bin.
5. Speed dial overhaul: multiple dials as synced tabs, default name "Speed dial", icons for bookmarks/history/notes, secure Dashboard button per profile, context menus (incl. blank-area add), background image/colour, hide logo/name, columns, hide titles, folders/subfolders, show/hide add button. Fix: Customize button stops working in small windows.
6. Downloads right-click menu (open, show in folder, copy address, copy path, restart, pause, remove, remove all).
7. History full view default + recycle bin; Notes recycle bin.
8. All Tabs / recently closed include tabs from other synced sessions.
9. Mouse wheel on tab strip switches tabs (setting to disable); customizable toolbar.
10. Google sign-in workaround.
11. Extensions (Chrome/Firefox) if feasible.
12. Translate.
13. Verify Linux AppImage build; Android later.
Also: rebuild tests (server e2e, vault, sync, profiles, importers).
