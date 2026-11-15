![mbrowser — Browse Your Way. Your Data. Your Server.](assets/banner.png)

# mbrowser

A Chromium-based browser built on Electron that syncs your bookmarks, history, tabs, passwords, and settings to **your own self-hosted server** — no third-party cloud required.

---

## Downloads

| Platform | Download |
|----------|----------|
| 🐧 **Linux** (AppImage) | [mbrowser-linux.AppImage](https://github.com/flesheatingbug/mbrowser/releases/download/latest-desktop/mbrowser-linux.AppImage) |
| 🪟 **Windows** (Installer) | [mbrowser-windows-setup.exe](https://github.com/flesheatingbug/mbrowser/releases/download/latest-desktop/mbrowser-windows-setup.exe) |
| 🤖 **Android** (APK) | [mbrowser.apk](https://github.com/flesheatingbug/mbrowser/releases/download/latest-apk/mbrowser.apk) |

> **Android**: Enable *Install from unknown sources* in your device settings, then open the APK to install.

---

## Features

- **Speed dial start page** — multiple dials, folders, background images, synced across devices
- **Bookmark manager** — folders, recycle bin, Vivaldi-style context menus, speed dial integration
- **Password vault** — AES-256-GCM encrypted, stored on your own server; Cards and Addresses autofill
- **History & Notes** — full history view, recycle bin, notes synced across devices
- **Profiles** — separate browser instances with their own data, avatars, and settings
- **Side panel** — bookmarks, history, downloads, notes in a persistent panel
- **Privacy** — built-in ad/tracker blocker, private windows, no telemetry
- **Self-hosted sync** — bookmarks, history, tabs, passwords, settings, all encrypted in transit

---

## Installing on Linux

### AppImage (recommended)

```bash
chmod +x mbrowser-linux.AppImage
./mbrowser-linux.AppImage
```

Or install it as a system app (adds an application menu entry and desktop icon):

```bash
# Using the system Electron (Arch/CachyOS):
sudo pacman -S electron
git clone https://github.com/flesheatingbug/mbrowser.git
cd mbrowser
electron .
```

### From source

Requires Node 20+ and npm.

```bash
git clone https://github.com/flesheatingbug/mbrowser.git
cd mbrowser
npm install
npm start
```

---

## Installing on Windows

Run **mbrowser-windows-setup.exe** and follow the installer. No administrator rights required (installs per-user by default).

If you see a Windows Defender SmartScreen warning, click **More info → Run anyway** — the app is unsigned.

---

## Keyboard shortcuts

| Action | Shortcut |
|--------|----------|
| Settings | `Ctrl+,` |
| Quick commands | `F2` |
| Side panel | `F4` |
| History | `Ctrl+H` |
| Bookmarks | `Ctrl+Shift+O` |
| Password manager | — (use Quick Commands) |
| New tab | `Ctrl+T` |
| New private window | `Ctrl+Shift+P` |
| Find in page | `Ctrl+F` |

---

## Sync server — Docker setup (Dockge / Portainer / Compose)

mbrowser can run completely standalone (no server needed), or you can self-host the sync server to sync across devices.

### Quick start with Dockge

1. In Dockge, click **+ Compose** and paste the contents of [`server/dockge-stack.yaml`](server/dockge-stack.yaml).
2. Edit the volume path to wherever you want to store data, e.g.:
   ```yaml
   - /opt/mbrowser-sync/data:/config
   ```
3. Set `PUID`/`PGID` to match your server user (run `id` to check).
4. Click **Deploy**.

### Quick start with Docker Compose

```bash
# 1. Clone the repo (or just download the server/ folder)
git clone https://github.com/flesheatingbug/mbrowser.git
cd mbrowser/server

# 2. Copy and edit the env file
cp .env.example .env
nano .env   # set CONFIG_PATH, PUID, PGID, TZ

# 3. Build and start
docker compose up -d --build

# 4. Watch the log
docker compose logs -f
```

The web interface and API will be available at **http://your-server:4242**.

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CONFIG_PATH` | `./config` | Host path where the database is stored |
| `HTTP_PORT` | `4242` | Port exposed on the host |
| `PUID` / `PGID` | `1000` | File ownership (run `id` on the host) |
| `TZ` | `Etc/UTC` | Timezone (e.g. `Europe/London`) |
| `ADMIN_USER` | *(blank)* | Auto-create first admin on first run |
| `ADMIN_PASSWORD` | *(blank)* | Auto-create first admin on first run |
| `TRUST_PROXY` | `true` | Trust `X-Forwarded-For` from a reverse proxy |
| `SESSION_DAYS` | `7` | Web login session lifetime |

### Behind Nginx Proxy Manager

1. In the Dockge stack / compose file, **remove** the `ports:` block and uncomment the `networks:` section — set it to your NPM Docker network name.
2. In NPM, create a **Proxy Host** pointing to `mbrowser-sync:4242`.
3. Enable **Websockets support** and add an SSL certificate.

> **Note**: Do not add an Access List to the proxy host — it returns its own 401 which breaks the browser's sync login.

### Connecting the browser

1. Open **Settings → Sync**.
2. Enter your server address (e.g. `https://sync.example.com` or `http://192.168.1.50:4242`).
3. Click **Sign in** and enter your server account credentials.
4. The browser will start syncing automatically.

The server admin panel is available from **Settings → Server Admin** directly inside the browser.

### Maintenance commands

```bash
# List all users
docker exec mbrowser-sync python -m app.cli list-users

# Reset a user's password
docker exec mbrowser-sync python -m app.cli reset-password <username>

# View sync stats
docker exec mbrowser-sync python -m app.cli stats
```

---

## Image update (Docker)

The server image is rebuilt automatically whenever `server/` changes on the `main` branch and published to the GitHub Container Registry:

```
ghcr.io/flesheatingbug/mbrowser-sync:latest
```

To update a running container: in Dockge click **Pull & Restart**, or:

```bash
docker compose pull && docker compose up -d
```

---

## Building from source

The Windows and Linux builds are produced automatically by GitHub Actions. To build locally:

```bash
npm install
npm run dist:linux   # Linux AppImage
npm run dist:win     # Windows installer + zip
```

Output is in `dist/`.

---

## Project structure

```
mbrowser/
├── main.js              # Main process: windows, tabs, IPC, sync, vault
├── preload.js           # Chrome UI ↔ main process bridge
├── page-preload.js      # my:// internal pages bridge
├── ui.html              # Toolbar, tabs, status bar
├── overlay.html         # Popups (password fill, find, etc.)
├── pages/               # Internal browser pages (my:// protocol)
│   ├── start.html       # Speed dial / new tab page
│   ├── bookmarks.html   # Bookmark manager
│   ├── passwords.html   # Password / card / address vault
│   ├── history.html     # Browsing history
│   ├── downloads.html   # Downloads
│   ├── settings.html    # Settings
│   ├── profiles.html    # Profile manager
│   └── ...
├── lib/                 # Shared modules (vault, settings, sync, blocker)
├── server/              # Self-hosted sync server (Python, SQLite, Docker)
└── android/             # Android companion app (Kotlin)
```

---

## License

MIT
