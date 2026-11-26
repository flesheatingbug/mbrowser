# mbrowser — project guide

Electron 33 browser (Linux/CachyOS Wayland first, Windows, Android later) that syncs bookmarks,
history, tabs, settings and an encrypted password vault to a self-hosted Docker server (`server/`).
The user runs the **system** electron on Linux (`sudo pacman -S electron`, then `electron .`),
not npm's. Browser version in package.json: 0.9.0. Server: 1.0.0 (stdlib-only Python, SQLite).

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

## State and workflow
- Desktop 0.9.0 (Electron 44), Android native app (`android/`, versionName `0.3.<run>`, built only by GitHub Actions), server 1.0.0.
- One long-lived branch: **master**. The old `next` pre-release branch is retired (delete it on GitHub if it still shows up); the repo's old default `main` is stale.
  Commit to master, push, check CI. Commits end with the `Co-Authored-By` and `Claude-Session` lines the session gives.
- **Beta switch** (Android: Settings > Beta features; desktop: General > Beta features, per computer, never synced) hides features still being tested.
  Currently gated: Android casting (menu row, overlay icon, fullscreen button) and desktop Extensions (home network mode graduated out of beta). Promote a feature by removing its gate.
- Desktop extensions (beta): unpacked Chrome extensions or install from a Chrome Web Store link (`lib/crx.js` unpacks the CRX). Only part of the Chrome extension API exists in Electron.
- Android cast: Google Cast default receiver; `Caster.kt` plus `CastRelay.kt` (a small server on the phone that fetches the stream with the page's cookies/referer/UA, adds CORS and rewrites HLS playlists). Live streams stop the page's player and hide the video while casting (one-viewer-per-token sites). Untested on real hardware beyond the user's reports.
- Desktop lock profile: status-bar lock button / Ctrl+Shift+L covers every window of the profile with `lock.html` (a top-most WebContentsView re-asserted by `enforceLock`), pauses and mutes media, blocks shortcuts. Optional password in Settings > Privacy (`lib/lock.js`, scrypt, `lock.json`, per profile, never synced).
- Android automatic sync stays silent when the server is unreachable (changes stay dirty and go up next time); manual Sync now still reports it.
- Done since the 0.9 rebuild: setup wizard, profile avatars/default/open-last, vault device/master modes, cards and addresses, recycle bins, speed dial overhaul, downloads menu, tab-strip wheel, Windows default-browser flow, passkey setting, Android fullscreen video and background audio.

- Home network mode (desktop, user-confirmed working): server `ALLOW_TUNNEL=true` exposes `GET /api/tunnel` (Upgrade: mbtunnel, X-Target host:port, private IPs only) and `/api/tunnel/status`. `lib/hometunnel.js` runs a local HTTP proxy over it; `applyHomeNetwork` in main.js sets a PAC on the default and private sessions (LAN ranges and plain/.lan/.local names via proxy). Status-bar chip toggles it. 
- Home network sign-in: `POST /api/tunnel/login` (password + totp/recovery) returns a tunnel-only token (kind `tunnel`, rejected by the sync API); `TUNNEL_KEY_AUTH=true` also allows sync keys. Servers/tokens are per profile: desktop `homenet.json` via `lib/homenet.js` (settings page "Home network"), Android `Prefs.homeServers`/`homeActive`/`homeNetwork` keyed by profile id and `HomeNet.kt`. One server is active at a time.
- Search suggestions: desktop × button removes the history entries; Android long-press asks to remove.
- Address-bar engine badge: the pick applies to the next search only (`cur.engineTemp`) unless `search.rememberSwitched` (Android `prefs.rememberEngine`). Android `prefs.lazyTabs` / desktop `tabs.lazyBackground` control lazy loading of tabs. Store links: desktop `EXTERNAL` schemes ask then `shell.openExternal`; Android handles `intent:` and Play Store pages.
- Passwords: a login form on a page with a locked vault prompts to unlock (checkbox keeps it unlocked: desktop `passwords.remember`, Android `prefs.vaultKeep` always/app); Android also offers "Fill saved login" via a snackbar (`loginForm` JS bridge) and a set-up hint when no vault exists.
- Android password manager choice (`prefs.passwordMode`: both / builtin / system): builtin sets `importantForAutofill=NO_EXCLUDE_DESCENDANTS` on page WebViews to keep Android autofill services (Bitwarden) out; system hides this browser's dropdown, auto-fill and save offers. The login dropdown (`showLoginPopup`) works in private tabs; saving there is off.
- Extension toolbar buttons (beta): icon from the manifest, click opens the popup in a small window, right-click = Options / Hide / Manage; hide state is `hidden` in extensions.json. Untested at runtime.
- Lock screen uses the profile password when the profile has one, else the optional `lock.json` password. Session restore keeps every window (`session.json` has `windows`).
- Android live cast: only the video is hidden (not the page); CastRelay prefetches live segments.
- Beta items in Settings show an amber BETA badge/highlight (`.beta` in pages/settings.html).

- Reader view (both platforms): `assets/reader.js` (desktop) and `android/app/src/main/assets/reader.js` are identical copies; keep them in sync. Injected on demand: `check()` drives the address-bar button (shown on article-like pages), `toggle()` shows a shadow-DOM overlay with text size / font / theme controls. Desktop: `detectReadable`/`toggleReader` in main.js, Ctrl+Alt+R, `appearance.toolbar.reader`. Android: `btnReader`, menu row, Back closes it. Untested at runtime.

- Tab stacks and split view (desktop): drop a tab on the middle of another tab (ui.html `zone()`) -> `tab:stack` -> `stackTabs`; stacked tabs share `tab.group` and overlap in the strip, "Unstack Tabs" in the tab menu, saved in `session.json` (`tabs[].group`). Split view is `cur.split = {ids, fr}` (N columns, fractions), laid out in `layoutCore` with a thin divider view per gap (`splitResizer`, `resizer.html?split=i`, IPC `split:resize`). Dragging a tab from the strip fires `tab:dragstart`, which shows the `dropzone` overlay over the page (left/right highlight, drop -> `split:drop` -> `splitAdd`). Ctrl+Alt+S toggles. Background tabs that load show a moving bar under the tab (`.tab.loading:not(.active)`). Bookmarks bar (folder "Bookmarks bar"), force dark (`DARK_CSS`), opt-in mouse gestures. All untested at runtime.

- Cloud sync (desktop, untested against real Google/Microsoft): `sync.backend` = server | gdrive | onedrive (per profile). `lib/cloudsync.js` rebuilds the server API (`call(method, path, body)`) on encrypted files in Drive appDataFolder / OneDrive approot, so `syncAll`/`syncVault`/`syncSettings` are unchanged. Sign-in is OAuth PKCE on a loopback port; the user supplies their own client ID (`sync.gClientId/gClientSecret/msClientId`). Everything is AES-256-GCM with a scrypt key from a sync passphrase; tokens and key live in `cloud.json` (safeStorage). IPC `cloud:*` (page only), UI in `pages/settings.html` (`backendPicker`, `cloudSection`). Android not done. Known gap: two devices syncing in the same second can overwrite each other's file; the next sync heals it.

- Cookie pop-ups: `assets/cookiehide.js` (desktop) and `android/app/src/main/assets/cookiehide.js` are identical copies; keep them in sync. It clicks a Reject / Only necessary button where a known banner has one, hides known consent containers and un-freezes scrolling. Setting `privacy.hideCookieBanners` (Android `prefs.hideCookies`), default on; injected on dom-ready / onPageFinished for http(s) pages. Untested at runtime.

- What's new: `assets/changelog.json` (desktop About page, IPC `app:changelog`) and `android/app/src/main/assets/changelog.json` (About > What's new) are identical copies; add a dated entry at the top when shipping user-visible changes.

## Rust port (`rust/`, experimental)
- GTK3 + WebKitGTK take on the desktop browser (`gtk` 0.18, `webkit2gtk` 2.0, `ureq`, `serde_json`). It runs the **real UI files** (`ui.html`, `overlay.html`, `pages/*`, `preload.js`, `page-preload.js`) unchanged: chrome pages are served from a `my://chrome/...` scheme, a shim fakes `require('electron')`/`window.api`, and calls arrive as JSON through the `ipc` script message handler (`{k:'s'|'i', ch, a, src, id}`); answers go back with `window.__res(id, v)`, events with `window.__emit(ch, args)`.
- Files: `src/main.rs` (window, tabs, overlays, IPC `send`/`invoke`, downloads, menus), `src/data.rs` (bookmarks with folders/recycle bin, speed dials, history + bin, notes, settings groups; same rules as main.js so a profile can sync with Electron/Android), `src/sync.rs` (server sync on a worker thread: bookmarks, history, tabs, settings; `sync:login/test/now/status`; every 5 min), `src/extra.rs` (reader view, tab stacks/rename, script-based tracker blocker, profiles, small stubs). `data/defaults.json` + `data/themes.json` are generated and embedded.
- Not in the Rust build yet (pages show a "not part of the Rust build yet" message): password vault, Google Drive/OneDrive sync, split view, extensions, home network, lock screen, import, bookmarks bar, force dark.
- The content-filter API is not in the Rust bindings, so the blocker is a user script that refuses fetch/XHR/beacon/script/img/iframe requests to listed third-party hosts (applies to tabs opened after a setting change).
- Build: CI only (`.github/workflows/rust-appimage.yml` makes an AppImage; the sandbox cannot fetch crates). Keep `use` of `RefCell` borrows short and never across GTK calls. Everything is untested on real hardware beyond the user's first look at the UI.

## Open items
- All Tabs / recently closed including tabs from other synced sessions.
- Google sign-in workaround. Translate on desktop (Android has it).
- Re-test Amazon/eBay; redeploy the server for the backup/restore endpoints.
- goloudplayer.com stopped after about a minute with the blocker on: the per-site "allow ads" switch avoids it; the exact blocked request was never found.
- supplies-team.co.uk site search reported broken in the browser (details pending from the user).
- Rebuild tests (server e2e, vault, sync, profiles, importers).

## Vault modes (0.9)
- Two modes: **master password** (scrypt; works on every device, default when a server is connected) and **device key** (random key in `vault-device.key` via safeStorage; this computer only, default for local). Switching re-encrypts (`vault:setMode`).
- "Only type it once" (`passwords.remember`, default on): derived key kept in `vault-remember.key` (safeStorage); off = ask each launch + auto-lock.
- `vault:open` never creates/deletes a vault. The server preserves the blob's `device` flag. Second computer joins the server's vault (`vault:join` / wizard "join"), never makes a rival one.
