# mbrowser for Android

Native Kotlin app on the system WebView. It syncs with the same self-hosted server as the desktop browser, so your bookmarks,
history, tabs, passwords, settings and profile picture follow you between devices.

## Install
Download the latest APK: [mbrowser.apk](https://github.com/flesheatingbug/mbrowser/releases/download/latest-apk/mbrowser.apk)
and open it (allow *Install unknown apps* for your browser or file manager the first time).
Every build is signed with the same key, so new builds install over the old one. The app also checks for updates itself
(menu > Check for updates) and installs them for you. The last few builds are kept as `apk-build-N` releases if you need to go back.

The APK is built only by GitHub Actions (`.github/workflows/android.yml`) on every change under `android/`. Each build's release notes list the commits it contains.

## First run
A short welcome screen lets you pick a theme, connect to your server (or stay standalone) and choose a profile. A few sample
speed dials (DuckDuckGo and others) are added so the start page isn't empty. Sign-in works with servers that list profiles without a ticket.
You can also pair a device by scanning the QR code shown in the desktop password manager.

## What it does
**Browsing**
- Tabs and private tabs, a Vivaldi-style tab switcher with swipeable sections: Tabs, Private, Other devices, Recently closed. Typing an address in the switcher opens it.
- Address bar with search fallback, voice and QR search, engine icons in the search strip, and suggestions from recent pages and other devices (inset, rounded, and empty until you type).
- Find on page, translate page or selected text, pull to refresh (it stays out of the way of videos and scrollable areas; can be switched off), restores tabs on restart.
- Downloads with an in-app list (open, remove, delete, clear all) and APK install support.
- Built-in ad and tracker blocker using EasyList, EasyPrivacy and uBlock filter lists.
- Identifies as Chrome by default (Firefox, Safari or the stock WebView are selectable). `X-Requested-With` is not sent, which helps Google sign-in.

**Speed dial**
- Multiple dials, swipe between them, folders, drag to rearrange, page thumbnails or icon-only tiles.
- Icons are taken from the visited page, manifest icons or touch icons, with retries after a failure, and the tile menu is tidied into sections.
- Wallpapers (built in or your own, edge to edge), a transparent engine strip over the wallpaper, and the top bar sharing the page colour in every theme.

**Look**
- The same colour themes as desktop (Default follows the system), button styles (minimal outline, Material 3 Expressive, solid geometric, soft), toolbar layouts, logo and app icon variants.
- Your look is synced through the server, so a theme chosen on one device applies to the others straight after a sync.

**Sync**
- Bookmarks and history (revision counters), open tabs and tabs from other devices, settings, wallpaper, and the speed dial layout. Runs on start, on stop and from the menu.
- Several profiles on one server: the main menu shows your profile picture (synced both ways with desktop) and a switcher. You can add, rename and delete profiles, and change the picture (photo, emoji or colour) in Settings.

**Passwords**
- Encrypted vault (AES-256-GCM) with passwords, cards and addresses: unlock, view, copy, edit, sync and autofill. Also available in private tabs.
- Master password mode, or device mode with no master password (key kept on this phone). Options to keep it unlocked and to switch modes.

## Not done yet
Save-login prompt, extensions, Play Store distribution.

## Building yourself
- **Android Studio:** open the `android/` folder, let Gradle sync, then run on a phone or build an APK.
- **No PC SDK:** fork the repo, add the `KEYSTORE_PASSPHRASE` secret for your own signing key, and run Actions > Android APK.
- Point the app at your server in Settings (server URL, sign in, device name).

## Layout
`app/src/main/java/app/mbrowser/`: `MainActivity` (browser UI), `StartPage`, `DialIcons`, `Store` (local bookmarks and history), `Sync`, `Api`, `Settings` (synced settings groups),
`Vault` and `PasswordsActivity`, `Blocker`/`AdBlock`, `Themes`/`Look`/`Wallpapers`, `Avatars`, `Updater`, `SettingsActivity`, `WelcomeActivity`.
