# mbrowser-rs (experiment)

A small browser in Rust: GTK 3 + WebKitGTK, tabs, address bar, progress bar.
No sync, vault or settings yet; this is a playground.

## Run (CachyOS / Arch)
```
sudo pacman -S webkit2gtk-4.1 gtk3
chmod +x mbrowser-rs-x86_64.AppImage
./mbrowser-rs-x86_64.AppImage
```
The AppImage does not bundle WebKitGTK; it uses the system one (`webkit2gtk-4.1`). Build locally with `cargo run --release` in this folder
(needs `webkit2gtk-4.1` and `gtk3`; on Arch the normal packages include the dev files).

Shortcuts: Ctrl+T new tab, Ctrl+W close tab, Ctrl+L address bar, Ctrl+R reload.
