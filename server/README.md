# mbrowser sync server

Syncs your mbrowser bookmarks, history, open tabs, settings and (end-to-end encrypted) passwords to your own server,
with a modern web interface to manage it:

* **Accounts and profiles**: several users, each with several profiles (for example Personal and Work)
* **Sign in from the browser with your account**, or use an **API key** you can generate, view, copy and revoke on the site
* **Two-factor authentication** (optional, any authenticator app) with recovery codes
* Browse, search, delete and export what is synced (bookmarks as an HTML file, history as JSON)
* Nothing to install but Docker. No database server, no extra packages.

## Quick start

```sh
cp .env.example .env          # then edit .env: CONFIG_PATH, HTTP_PORT, PUID, PGID, TZ
docker compose up -d --build
docker compose logs -f        # (Ctrl+C to leave the log)
```

Open `http://<your-server>:4242`, create the administrator account, create a profile and connect mbrowser.
Settings live in `.env`, the same way as with linuxserver.io containers:

| Variable | Default | Meaning |
|---|---|---|
| `CONFIG_PATH` | `./config` | Host folder mounted as `/config` (database, nothing else) |
| `HTTP_PORT` | `4242` | Port on the host |
| `PUID` / `PGID` | `1000` | User and group that own the files (`id` shows yours) |
| `TZ` | `Etc/UTC` | Time zone, for example `Europe/Amsterdam` |
| `ADMIN_USER` / `ADMIN_PASSWORD` | empty | Create the first administrator from the environment (only while no account exists) |
| `TRUST_PROXY` | `true` | Trust `X-Forwarded-For` / `-Proto` from your reverse proxy |
| `SESSION_DAYS` | `7` | How long a web login lasts |

## Upgrading from the old single-token server

Point `CONFIG_PATH` at the **same folder** the old server used. On first start the server finds the old `sync.db` and
`token` file and turns them into a profile called **Default**; the old token keeps working as an API key named
"Legacy token", so browsers that are already connected keep syncing without any change. The first administrator you
create becomes the owner of that profile. The old files are left untouched.

## Connect mbrowser

*Settings → Sync*: enter the server address, then either **Sign in** with your account (user name, password, the 2FA code
if enabled, and the profile), or paste an **API key** from the site (*Profiles → your profile → API keys*). Use
**Test connection** to check each step.

## Reverse proxy (Nginx Proxy Manager and similar)

* Forward to `http://<server>:4242` (or to `mbrowser-sync:4242` if both containers share a Docker network).
* Request an SSL certificate and turn on *Force SSL*. Websockets are not needed.
* **Do not attach an Access List** to the proxy host. mbrowser authenticates itself, and an access list answers `401`
  before the request reaches the server (the browser's *Test connection* tells you when that happens).
* Leave `TRUST_PROXY=true` so rate limiting sees real client addresses.

## Two-factor authentication

*Account & security → Two-factor authentication → Turn on*: scan the QR code (or type the key), enter the code, and keep the
ten recovery codes somewhere safe. It protects the website and the browser's account sign-in. API keys are separate
credentials and are not affected, which is why you can revoke them individually.

Locked out? Remove it from the command line:

```sh
docker compose exec mbrowser-sync python -m app.cli disable-2fa YOURNAME
docker compose exec mbrowser-sync python -m app.cli reset-password YOURNAME
docker compose exec mbrowser-sync python -m app.cli list-users
```

## Backups and updates

* **Backup**: stop the container (`docker compose stop`) and copy the `CONFIG_PATH` folder. Restoring means copying it back.
* **Update**: replace the files of this folder (keep `.env`), then `docker compose up -d --build`. Data is migrated automatically.
* **Dockhand / Portainer**: run `docker compose build` once; then the stack can use the local image `mbrowser-sync:latest`
  (delete the `build: .` line) with the variables from `.env` entered in the stack's environment.

## Security notes

* Passwords are stored as salted scrypt hashes. Sessions use random cookies (HttpOnly, SameSite=Strict, Secure behind
  https) and every change needs a CSRF token. Logins and 2FA codes are rate limited; each 2FA code works once.
* API keys are stored so that you can view them later (that is a feature you asked for): protect the `CONFIG_PATH`
  folder like a password file. Revoke a key and it stops working immediately.
* The password vault reaches this server already encrypted by your browser. The server never sees your master password
  or your passwords. Use a long, unique master password: anyone who gets the vault file could try to guess it offline.
* Run it behind https if it is reachable from outside your home network.

## Layout

```
compose.yaml  .env.example  Dockerfile  docker-entrypoint.sh
app/          the server (Python standard library only) and the web interface (app/static)
```
