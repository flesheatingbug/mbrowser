# mbrowser-rs (experiment)

A small browser in Rust: GTK 3 + WebKitGTK, tabs, address bar, progress bar.
No sync, vault or settings yet; this is a playground.

## Run (CachyOS / Arch)
```
sudo pacman -S webkit2gtk gtk3
chmod +x mbrowser-rs-x86_64.AppImage
./mbrowser-rs-x86_64.AppImage
```
The AppImage does not bundle WebKitGTK; it uses the system one. Build locally with `cargo run --release` in this folder
(needs `webkit2gtk` and `gtk3` dev files; on Arch the normal packages include them).

Shortcuts: Ctrl+T new tab, Ctrl+W close tab, Ctrl+L address bar, Ctrl+R reload.
