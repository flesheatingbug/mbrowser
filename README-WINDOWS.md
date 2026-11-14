# mbrowser on Windows

Everything that is not Linux-specific is shared, so the Windows version is this same project.
It has **not been tested on Windows yet**: expect to report a bug or two.

## Option 1: build it on a Windows PC
1. Install Node.js 20 or newer from https://nodejs.org
2. Extract the whole zip to a normal folder (for example `C:\mbrowser`), then double-click **build-windows.cmd**. Do not use "Run as administrator", and do not run it from inside the zip file.
3. Take `dist\mbrowser Setup x.y.z.exe` (installer) or `dist\mbrowser-x.y.z-win.zip` (no install)

### If the build stops with "Cannot create symbolic link ... A required privilege is not held"
Windows only allows that when **Developer Mode** is on. Turn it on once (Settings > System > For developers >
Developer Mode, called "Privacy & security > For developers" on some versions) and run build-windows.cmd again.
The script also retries automatically with a simpler build that skips editing the program icon, so you still
get an installer either way; Developer Mode just gives the full result.

## Option 2: let GitHub build it (no Windows PC needed)
1. Put this folder in a GitHub repository (private is fine)
2. Open the repository's **Actions** tab, choose **Build mbrowser**, **Run workflow**
3. When it finishes, download the Windows artifact from the run page

## Just try it without installing
Double-click **run-windows.cmd** (installs the dependencies the first time).

## Notes
- Windows will show a "SmartScreen" warning for the installer because it is not code-signed.
  Choose "More info" then "Run anyway".
- Your data lives in `%APPDATA%\mbrowser`.
- To use the same bookmarks, history, tabs and passwords as on Linux: Settings > Sync,
  enter your server address and the token from the server's `token` file. For the password
  vault, unlock it on Windows with the same master password.
- If the window cannot be dragged, start with `run-windows.cmd --native-titlebar` or tick
  Settings > Appearance > "Use the system title bar".
- Setting mbrowser as the default browser is not wired up yet.
