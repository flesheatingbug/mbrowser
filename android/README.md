# mbrowser for Android (0.1.0, first draft)

Native Kotlin app on the system WebView. It talks to the same sync server as the desktop browser.

**Status: written but never compiled.** The authoring sandbox had no Android SDK or network, so expect a few
compile errors on the first build. Fix them (Claude Code is good at this) before judging the design.

## Build
1. Install Android Studio. Open the `android/` folder; let it sync Gradle (it creates the wrapper and `local.properties`).
2. Run on a phone (USB debugging) or an emulator. For an APK: Build > Build APK(s).
3. Open Settings in the app: server URL, API key (from the server web UI, Profiles > API keys), device name. "Test and save" checks the key.

## Build without Android Studio (no PC SDK, nothing on the phone)
Push the repo to GitHub. `.github/workflows/android.yml` builds a debug APK on every change under `android/`
(or run it by hand: Actions > Android APK > Run workflow). Download the `mbrowser-debug-apk` artifact, copy the APK to the phone and
open it (allow "install unknown apps" for your file manager or browser). If the build fails, the Actions log shows the compile errors.

## What works (by design)
- Tabs (WebView each), address bar with search fallback, back/forward/reload, downloads via DownloadManager.
- Speed dial start page built from bookmarks. Star button, bookmarks list, history list (tap opens, long-press removes/clears).
- Sync on start, on stop and from the menu: bookmarks and history (`/api/bookmarks`, `/api/history`, rev counters), open tabs (`PUT /api/tabs`),
  and "Tabs from other devices" (`GET /api/tabs?exclude=<device>`).
- Opens http/https links from other apps. Cleartext http is allowed so a LAN server works.

## Not done yet
Password vault and settings sync, folders, speed-dial editing, tracker blocking, profiles/setup wizard, themes, find-in-page, proper icons
(buttons use text glyphs), release signing. Gmail sign-in is best effort only (the "; wv" marker is stripped from the user agent).

## Layout
`app/src/main/java/app/mbrowser/`: `MainActivity` (browser UI), `Store` (local bookmarks/history JSON), `Sync` + `Api` (server client),
`ItemsActivity` (lists), `SettingsActivity`, `Prefs`.
