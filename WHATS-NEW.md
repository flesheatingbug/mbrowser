# mbrowser 0.8

Extract this zip to a NEW folder, then start it as before. Your data is untouched.
The sync server is a separate download: `mbrowser-server.zip` (README inside).

## Sync
- **Sign in with your account** (Settings > Sync > Account) or paste an **API key**. Keys can now be **shown and copied**
  (Show / Copy next to the field). The server's new web interface creates and lists keys too.
- "settings: /api/settings -> HTTP 404" means the sync server is the old version. The new server has it; until you
  update, everything else keeps syncing and the browser tells you to update the server.
- Test connection now also reports the server version and whether accounts are available.

## Bookmarks
- New **Bookmark Manager** (Ctrl+Shift+O): folder tree with sub folders, search, sort, add / edit, move (button or
  drag and drop onto a folder), delete, rename and delete folders, empty folders.

## Passwords
- Every login has a **name** (to recognise it) and can be put in a **folder**, like Bitwarden. Folders: create,
  rename, delete (the logins are kept), move by drag and drop or with "Move to folder". Imports bring in names and
  folders (Bitwarden, Chrome / Vivaldi, LastPass, 1Password, KeePass).
- A **key icon** appears in the address bar on pages with a login form (and whenever the site has saved logins):
  click it to fill. It works even when the page's own fields are not detected.
- The passwords page refreshes when you come back to it, so imports show up immediately.

## Look
- Toolbar, panel header and window buttons use **theme-coloured line icons** instead of emoji / text symbols.
- Program icon: the change now applies to the window straight away (and to the Linux launcher if installed with
  install-linux.sh); the start page logo follows the chosen icon.

## Profiles
- Opening another profile now starts the program exactly the way this one was started (fixes the plain Electron
  "welcome" page).
