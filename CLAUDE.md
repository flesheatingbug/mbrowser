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
  Currently gated: Android casting (menu row, overlay icon, fullscreen button) and desktop Extensions. Promote a feature by removing its gate.
- Desktop extensions (beta): unpacked Chrome extensions or install from a Chrome Web Store link (`lib/crx.js` unpacks the CRX). Only part of the Chrome extension API exists in Electron.
- Android cast: Google Cast default receiver; `Caster.kt` plus `CastRelay.kt` (a small server on the phone that fetches the stream with the page's cookies/referer/UA, adds CORS and rewrites HLS playlists). Live streams close the page while casting (one-viewer-per-token sites). Untested on real hardware beyond the user's reports.
- Android automatic sync stays silent when the server is unreachable (changes stay dirty and go up next time); manual Sync now still reports it.
- Done since the 0.9 rebuild: setup wizard, profile avatars/default/open-last, vault device/master modes, cards and addresses, recycle bins, speed dial overhaul, downloads menu, tab-strip wheel, Windows default-browser flow, passkey setting, Android fullscreen video and background audio.

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
