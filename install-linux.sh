#!/bin/sh
# Adds mbrowser to your application menu (with its icon) and creates an "mbrowser" command.
# Run it from the mbrowser folder:   sh install-linux.sh [icon]
# icon: classic (default), color, blue, white, blue-m, white-m
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ICON="${1:-classic}"
ELECTRON="$(command -v electron || true)"
[ -z "$ELECTRON" ] && { echo "electron was not found. On CachyOS: sudo pacman -S electron"; exit 1; }

mkdir -p "$HOME/.local/share/applications" "$HOME/.local/bin" "$HOME/.local/share/icons/hicolor/512x512/apps" "$HOME/.local/share/icons/hicolor/128x128/apps"
if [ "$ICON" = "classic" ]; then
  cp "$DIR/assets/icon.png" "$HOME/.local/share/icons/hicolor/512x512/apps/mbrowser.png"
  cp "$DIR/assets/icon-128.png" "$HOME/.local/share/icons/hicolor/128x128/apps/mbrowser.png"
else
  [ -f "$DIR/assets/icons/$ICON.png" ] || { echo "Unknown icon '$ICON'. Choose: classic color blue white blue-m white-m"; exit 1; }
  cp "$DIR/assets/icons/$ICON.png" "$HOME/.local/share/icons/hicolor/512x512/apps/mbrowser.png"
  cp "$DIR/assets/icons/$ICON-128.png" "$HOME/.local/share/icons/hicolor/128x128/apps/mbrowser.png"
fi

cat > "$HOME/.local/bin/mbrowser" <<LAUNCH
#!/bin/sh
exec "$ELECTRON" "$DIR" "\$@"
LAUNCH
chmod +x "$HOME/.local/bin/mbrowser"

cat > "$HOME/.local/share/applications/mbrowser.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=mbrowser
GenericName=Web Browser
Comment=Browse your way. Your data. Your server.
Exec=$HOME/.local/bin/mbrowser %U
Icon=mbrowser
Terminal=false
Categories=Network;WebBrowser;
StartupWMClass=mbrowser
MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
DESKTOP

command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$HOME/.local/share/applications" || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q "$HOME/.local/share/icons/hicolor" || true
echo "Done. Find 'mbrowser' in your application menu, or run: mbrowser"
echo "(make sure ~/.local/bin is on your PATH)"
