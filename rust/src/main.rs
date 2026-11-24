//! mbrowser-rs: the mbrowser interface on GTK 3 + WebKitGTK.
//!
//! The browser's own pages (ui.html for the tab strip, toolbar and status bar, overlay.html for menus and
//! suggestions, and every my:// page) are the same files the Electron version uses. They talk to the program
//! through `window.api` / `window.browser`; here a small shim turns those calls into messages to Rust and Rust
//! answers them. Web pages are plain WebKit views, one per tab.

use gtk::glib;
use gtk::prelude::*;
use serde_json::{json, Map, Value};
use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::time::Duration;
use javascriptcore::ValueExt;
use webkit2gtk::{
    DownloadExt, FindControllerExt, HitTestResultExt, LoadEvent, SecurityManagerExt,
    URISchemeRequestExt, UserContentInjectedFrames, UserContentManager, UserContentManagerExt, UserScript,
    UserScriptInjectionTime, WebContext, WebContextExt, WebView, WebViewExt,
};

const TAB_H: i32 = 38;
const TOOL_H: i32 = 50;
const STATUS_H: i32 = 32;

// ---------------------------------------------------------------- state

struct Tab {
    id: u32,
    view: WebView,
    title: String,
    url: String,
    loading: bool,
    pinned: bool,
    private: bool,
    muted: bool,
    hover: String,
    custom: Option<String>,
}

struct Br {
    win: gtk::Window,
    tabs_v: WebView,
    tool_v: WebView,
    status_v: WebView,
    overlay_v: WebView,
    stack: gtk::Stack,
    ctx: WebContext,
    pctx: WebContext,
    ucm_chrome: UserContentManager,
    tabs: Vec<Tab>,
    active: u32,
    next: u32,
    settings: Value,
    bookmarks: Vec<Value>,
    history: Vec<Value>,
    downloads: Vec<Value>,
    closed: Vec<String>,
    themes: Vec<Value>,
    root: PathBuf,
    data: PathBuf,
    mode: Option<String>,
    suggest: Vec<Value>,
    sel: i64,
    typed: String,
    push_pending: bool,
    save_pending: bool,
    dl_next: u64,
    hook: Hook,
}

type B = Rc<RefCell<Br>>;
type Hook = Rc<RefCell<Option<B>>>;

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

// ---------------------------------------------------------------- files and settings

fn find_root() -> PathBuf {
    let mut c: Vec<PathBuf> = vec![];
    if let Ok(p) = std::env::var("MBROWSER_ASSETS") {
        c.push(PathBuf::from(p));
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(d) = exe.parent() {
            c.push(d.join("../share/mbrowser"));
            c.push(d.join("../../.."));
            c.push(d.join("../../../.."));
        }
    }
    if let Ok(d) = std::env::current_dir() {
        c.push(d.clone());
        c.push(d.join(".."));
    }
    for p in c {
        if p.join("ui.html").exists() {
            return p.canonicalize().unwrap_or(p);
        }
    }
    PathBuf::from(".")
}

fn data_dir() -> PathBuf {
    let base = std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".config"));
    let d = base.join("mbrowser-rs");
    let _ = std::fs::create_dir_all(&d);
    d
}

fn read_json(p: &std::path::Path) -> Option<Value> {
    serde_json::from_str(&std::fs::read_to_string(p).ok()?).ok()
}

fn write_json(p: &std::path::Path, v: &Value) {
    let _ = std::fs::write(p, v.to_string());
}

fn merge(base: &mut Value, over: &Value) {
    match (base, over) {
        (Value::Object(b), Value::Object(o)) => {
            for (k, v) in o {
                match b.get_mut(k) {
                    Some(bv) if bv.is_object() && v.is_object() => merge(bv, v),
                    _ => {
                        b.insert(k.clone(), v.clone());
                    }
                }
            }
        }
        (b, o) => *b = o.clone(),
    }
}

fn set_path(root: &mut Value, path: &str, val: Value) {
    let mut cur = root;
    let parts: Vec<&str> = path.split('.').collect();
    for (i, p) in parts.iter().enumerate() {
        if !cur.is_object() {
            *cur = Value::Object(Map::new());
        }
        let m = cur.as_object_mut().unwrap();
        if i == parts.len() - 1 {
            m.insert(p.to_string(), val);
            return;
        }
        cur = m.entry(p.to_string()).or_insert_with(|| Value::Object(Map::new()));
    }
}

fn s_str(s: &Value, path: &str) -> String {
    let mut c = s;
    for p in path.split('.') {
        c = &c[p];
    }
    c.as_str().unwrap_or("").to_string()
}

fn s_bool(s: &Value, path: &str, dflt: bool) -> bool {
    let mut c = s;
    for p in path.split('.') {
        c = &c[p];
    }
    c.as_bool().unwrap_or(dflt)
}

fn save_all(br: &Br) {
    write_json(&br.data.join("settings.json"), &br.settings);
    write_json(&br.data.join("bookmarks.json"), &Value::Array(br.bookmarks.clone()));
    let h = br.history.len().saturating_sub(20000);
    write_json(&br.data.join("history.json"), &Value::Array(br.history[h..].to_vec()));
    let tabs: Vec<Value> = br
        .tabs
        .iter()
        .filter(|t| !t.private && (t.url.starts_with("http://") || t.url.starts_with("https://")))
        .map(|t| json!({"url": t.url, "pinned": t.pinned, "title": t.custom}))
        .collect();
    if !tabs.is_empty() {
        write_json(&br.data.join("session.json"), &json!({"tabs": tabs}));
    }
}

fn schedule_save(b: &B) {
    if b.borrow().save_pending {
        return;
    }
    b.borrow_mut().save_pending = true;
    let b = b.clone();
    glib::timeout_add_local_once(Duration::from_secs(2), move || {
        b.borrow_mut().save_pending = false;
        save_all(&b.borrow());
    });
}

// ---------------------------------------------------------------- address handling

fn is_scheme_url(t: &str) -> bool {
    match t.find("://") {
        Some(i) if i > 0 => t[..i].chars().all(|c| c.is_ascii_alphanumeric() || "+.-".contains(c)) && t.chars().next().map_or(false, |c| c.is_ascii_alphabetic()),
        _ => false,
    }
}

fn host_port(t: &str) -> bool {
    // name:1234 or name:1234/path
    if let Some((h, rest)) = t.split_once(':') {
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        let after = &rest[digits.len()..];
        return !h.is_empty()
            && h.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
            && (2..=5).contains(&digits.len())
            && (after.is_empty() || after.starts_with('/'));
    }
    false
}

fn looks_like_domain(t: &str) -> bool {
    if t.contains(char::is_whitespace) {
        return false;
    }
    let head = t.split(|c| c == '/' || c == '?' || c == '#').next().unwrap_or("");
    let head = head.split(':').next().unwrap_or("");
    if !head.contains('.') || head.starts_with('.') || head.ends_with('.') {
        return false;
    }
    head.rsplit('.').next().map_or(false, |l| l.len() >= 2)
}

fn is_local_host(h: &str) -> bool {
    h == "localhost" || h.ends_with(".local") || h.ends_with(".lan") || h.starts_with("192.168.") || h.starts_with("10.") || h.starts_with("127.") || h.starts_with("172.16.")
}

fn pct(s: &str) -> String {
    let mut q = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => q.push(b as char),
            b' ' => q.push('+'),
            _ => q.push_str(&format!("%{b:02X}")),
        }
    }
    q
}

fn engine<'a>(s: &'a Value, nick: Option<&str>) -> Value {
    let list = s["search"]["engines"].as_array().cloned().unwrap_or_default();
    if let Some(n) = nick {
        if let Some(e) = list.iter().find(|e| e["nick"].as_str() == Some(n)) {
            return e.clone();
        }
    }
    let id = s["search"]["default"].as_str().unwrap_or("ddg");
    list.iter().find(|e| e["id"].as_str() == Some(id)).or(list.first()).cloned().unwrap_or(json!({"id":"ddg","name":"DuckDuckGo","url":"https://duckduckgo.com/?q=%s"}))
}

fn search_url(e: &Value, q: &str) -> String {
    e["url"].as_str().unwrap_or("https://duckduckgo.com/?q=%s").replace("%s", &pct(q))
}

fn normalize(s: &Value, input: &str) -> String {
    let t = input.trim();
    if t.is_empty() {
        return s_str(s, "general.homepage");
    }
    if t.starts_with("about:blank") || t.starts_with("view-source:") || is_scheme_url(t) {
        return t.to_string();
    }
    if host_port(t) {
        return format!("http://{t}");
    }
    let low = t.to_lowercase();
    if low == "localhost" || low.starts_with("localhost:") || low.starts_with("localhost/") || looks_like_domain(t) {
        let host = t.split(|c| c == '/' || c == '?' || c == '#').next().unwrap_or("").split(':').next().unwrap_or("").to_lowercase();
        return format!("{}{}", if is_local_host(&host) { "http://" } else { "https://" }, t);
    }
    if let Some((first, rest)) = t.split_once(' ') {
        let e = engine(s, Some(first));
        if e["nick"].as_str() == Some(first) {
            return search_url(&e, rest.trim());
        }
    }
    search_url(&engine(s, None), t)
}

fn host_of(url: &str) -> String {
    url.split("://").nth(1).unwrap_or("").split(|c| c == '/' || c == '?' || c == '#').next().unwrap_or("").to_string()
}

fn is_web(u: &str) -> bool {
    u.starts_with("http://") || u.starts_with("https://")
}

// ---------------------------------------------------------------- page scripts and the my:// scheme

fn shim(src: &str, preload: &str) -> String {
    format!(
        r#"(function(){{
if(window.__mbshim)return;window.__mbshim=1;
var SRC={src:?},L={{}},P={{}},n=0;
function post(o){{try{{window.webkit.messageHandlers.ipc.postMessage(JSON.stringify(o))}}catch(e){{}}}}
var ipcRenderer={{
 send:function(ch){{post({{k:'s',ch:ch,a:Array.prototype.slice.call(arguments,1),src:SRC}})}},
 invoke:function(ch){{var a=Array.prototype.slice.call(arguments,1);return new Promise(function(r){{var i=++n;P[i]=r;post({{k:'i',id:i,ch:ch,a:a,src:SRC}})}})}},
 on:function(ch,cb){{(L[ch]=L[ch]||[]).push(cb)}}
}};
var webFrame={{insertCSS:function(){{}},executeJavaScript:function(){{}},setZoomFactor:function(){{}}}};
var process={{argv:[],platform:'linux',versions:{{chrome:'130.0.0.0'}},getSystemVersion:function(){{return '6.0.0'}}}};
window.__emit=function(ch,args){{(L[ch]||[]).forEach(function(f){{try{{f.apply(null,[{{}}].concat(args))}}catch(e){{console.error(e)}}}})}};
window.__res=function(i,v){{var r=P[i];delete P[i];if(r)r(v)}};
function require(){{return {{contextBridge:{{exposeInMainWorld:function(k,o){{window[k]=o}}}},ipcRenderer:ipcRenderer,webFrame:webFrame}}}}
{preload}
}})();"#
    )
}

fn make_ucm(hook: &Hook, src: &str, root: &PathBuf, chrome: bool) -> UserContentManager {
    let ucm = UserContentManager::new();
    let file = if chrome { "preload.js" } else { "page-preload.js" };
    let pre = std::fs::read_to_string(root.join(file)).unwrap_or_default();
    let code = shim(src, &pre);
    let allow: &[&str] = if chrome { &[] } else { &["my://*/*"] };
    ucm.add_script(&UserScript::new(&code, UserContentInjectedFrames::TopFrame, UserScriptInjectionTime::Start, allow, &[]));
    ucm.register_script_message_handler("ipc");
    let hook = hook.clone();
    ucm.connect_script_message_received(Some("ipc"), move |_, r| {
        let text = r.js_value().map(|v| v.to_str().to_string()).unwrap_or_default();
        let b = hook.borrow().clone();
        if let Some(b) = b {
            ipc(&b, &text);
        }
    });
    ucm
}

fn mime_of(p: &str) -> &'static str {
    match p.rsplit('.').next().unwrap_or("") {
        "html" => "text/html; charset=utf-8",
        "css" => "text/css",
        "js" => "text/javascript",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "svg" => "image/svg+xml",
        "json" => "application/json",
        _ => "application/octet-stream",
    }
}

const PAGES: &[&str] = &[
    "bookmarks", "profiles", "import", "passwords", "history", "panel", "error", "start", "settings", "library", "downloads",
    "welcome", "addprofile", "notes", "server-admin",
];

fn theme_css(br: &Br) -> String {
    let t = theme_vars(br);
    let mut vars = String::new();
    for k in ["bg", "tab", "active", "fg", "muted", "line", "accent", "onaccent"] {
        vars.push_str(&format!("--{k}:{};", t[k].as_str().unwrap_or("#000")));
    }
    format!(":root{{{vars}color-scheme:{}}}", if t["dark"].as_bool().unwrap_or(true) { "dark" } else { "light" })
}

fn serve_my(b: &B, uri: &str) -> (Vec<u8>, &'static str) {
    let rest = uri.trim_start_matches("my://");
    let (host, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let path = path.split(|c| c == '?' || c == '#').next().unwrap_or("/");
    let br = b.borrow();
    let root = br.root.clone();
    let read = |p: PathBuf| std::fs::read(p).ok();
    let nf = (b"Not found".to_vec(), "text/plain");
    if path.contains("..") {
        return nf;
    }
    match host {
        "chrome" => {
            let f = path.trim_start_matches('/');
            if matches!(f, "ui.html" | "overlay.html" | "resizer.html" | "gate.html") {
                if let Some(d) = read(root.join(f)) {
                    return (d, "text/html; charset=utf-8");
                }
            }
            nf
        }
        "assets" => {
            if path == "/theme.css" {
                return (theme_css(&br).into_bytes(), "text/css");
            }
            if path == "/common.css" {
                return read(root.join("pages/common.css")).map(|d| (d, "text/css")).unwrap_or(nf);
            }
            if path == "/logo.png" {
                return read(root.join("assets/icon.png")).map(|d| (d, "image/png")).unwrap_or(nf);
            }
            if let Some(n) = path.strip_prefix("/icon/") {
                let n = n.trim_end_matches(".png");
                for f in [format!("assets/icons/{n}-128.png"), format!("assets/icons/{n}.png"), "assets/icon.png".to_string()] {
                    if let Some(d) = read(root.join(f)) {
                        return (d, "image/png");
                    }
                }
            }
            nf
        }
        "wallpaper" => {
            let id = path.trim_start_matches('/');
            read(root.join(format!("assets/wallpapers/{id}.jpg"))).map(|d| (d, "image/jpeg")).unwrap_or(nf)
        }
        h if PAGES.contains(&h) => read(root.join(format!("pages/{h}.html"))).map(|d| (d, "text/html; charset=utf-8")).unwrap_or(nf),
        _ => nf,
    }
}

fn register_scheme(ctx: &WebContext, hook: &Hook) {
    if let Some(sm) = ctx.security_manager() {
        sm.register_uri_scheme_as_secure("my");
        sm.register_uri_scheme_as_cors_enabled("my");
    }
    let hook = hook.clone();
    ctx.register_uri_scheme("my", move |req| {
        let uri = req.uri().map(|s| s.to_string()).unwrap_or_default();
        let b = hook.borrow().clone();
        let (data, mime) = match b {
            Some(b) => serve_my(&b, &uri),
            None => (b"starting".to_vec(), "text/plain"),
        };
        let len = data.len() as i64;
        let stream = gtk::gio::MemoryInputStream::from_bytes(&glib::Bytes::from_owned(data));
        req.finish(&stream, len, Some(mime));
    });
}

// ---------------------------------------------------------------- theme and state

fn on_color(hex: &str) -> &'static str {
    let h = hex.trim_start_matches('#');
    if h.len() != 6 {
        return "#000000";
    }
    let n = u32::from_str_radix(h, 16).unwrap_or(0);
    let (r, g, b) = (((n >> 16) & 255) as f64, ((n >> 8) & 255) as f64, (n & 255) as f64);
    if 0.299 * r + 0.587 * g + 0.114 * b > 150.0 {
        "#000000"
    } else {
        "#ffffff"
    }
}

fn theme_vars(br: &Br) -> Value {
    let id = s_str(&br.settings, "appearance.themeId");
    let custom = br.settings["appearance"]["customThemes"].as_array().cloned().unwrap_or_default();
    let want = if id == "system" || id.is_empty() { "mbrowser".to_string() } else { id };
    let t = br.themes.iter().chain(custom.iter()).find(|t| t["id"].as_str() == Some(&want)).or(br.themes.first()).cloned().unwrap_or(json!({}));
    let ov = s_str(&br.settings, "appearance.accentOverride");
    let accent = if ov.starts_with('#') && ov.len() == 7 { ov } else { t["accent"].as_str().unwrap_or("#5b8cff").to_string() };
    json!({
        "bg": t["bg"], "tab": t["tab"], "active": t["active"], "fg": t["fg"], "muted": t["muted"], "line": t["line"],
        "accent": accent, "onaccent": on_color(&accent), "dark": t["dark"].as_bool().unwrap_or(true)
    })
}

fn favicon_of(url: &str) -> Value {
    if is_web(url) {
        let scheme = if url.starts_with("https") { "https" } else { "http" };
        json!(format!("{scheme}://{}/favicon.ico", host_of(url)))
    } else {
        Value::Null
    }
}

fn build_state(br: &Br) -> Value {
    let act = br.tabs.iter().find(|t| t.id == br.active);
    let url = act.map(|t| t.url.clone()).unwrap_or_default();
    let host = host_of(&url);
    let security = if is_web(&url) {
        if url.starts_with("https") { json!({"level":"secure","label":""}) } else { json!({"level":"insecure","label":"Not secure"}) }
    } else {
        json!({"level":"internal","label":""})
    };
    let bookmarked = br.bookmarks.iter().any(|b| b["url"].as_str() == Some(&url) && !b["deleted"].as_bool().unwrap_or(false));
    let e = engine(&br.settings, None);
    let origin = {
        let u = e["url"].as_str().unwrap_or("").replace("%s", "x");
        let scheme = u.split("://").next().unwrap_or("https");
        format!("{scheme}://{}", host_of(&u))
    };
    let tabs: Vec<Value> = br
        .tabs
        .iter()
        .map(|t| {
            json!({
                "id": t.id,
                "title": t.custom.clone().unwrap_or_else(|| if t.title.is_empty() { "New Tab".to_string() } else { t.title.clone() }),
                "renamed": t.custom.is_some(),
                "favicon": favicon_of(&t.url),
                "pinned": t.pinned,
                "group": Value::Null,
                "inSplit": false,
                "private": t.private,
                "audible": false,
                "muted": t.muted,
                "loading": t.loading,
            })
        })
        .collect();
    let dls: Vec<Value> = br.downloads.iter().rev().take(5).cloned().collect();
    json!({
        "activeId": br.active,
        "url": url,
        "shown": url,
        "canBack": act.map_or(false, |t| t.view.can_go_back()),
        "canForward": act.map_or(false, |t| t.view.can_go_forward()),
        "bookmarked": bookmarked,
        "tabBar": s_str(&br.settings, "appearance.tabBar"),
        "security": security,
        "frameless": false,
        "logins": Value::Null,
        "profile": {"id": "default", "name": "Default", "color": "#5b8cff", "count": 1},
        "maximized": br.win.is_maximized(),
        "zoom": act.map_or(100, |t| (t.view.zoom_level() * 100.0).round() as i64),
        "statusText": "",
        "hover": act.map(|t| t.hover.clone()).unwrap_or_default(),
        "loading": act.map_or(false, |t| t.loading),
        "tabCount": br.tabs.len(),
        "wheelScrollTabs": true,
        "clickTabScrollsTop": true,
        "toolbarButtons": br.settings["appearance"]["toolbar"],
        "engine": {"name": e["name"], "origin": origin},
        "detectedLang": "",
        "systemLang": "en",
        "defaultBarVisible": false,
        "bmBar": Value::Null,
        "translateBarVisible": false,
        "theme": theme_vars(br),
        "blocked": 0,
        "blockerOn": false,
        "home": {"avail": false, "on": false, "ready": false},
        "reader": {"avail": false, "on": false},
        "exts": [],
        "siteAllowed": !host.is_empty() && false,
        "private": act.map_or(false, |t| t.private),
        "downloadCount": br.downloads.len(),
        "downloads": dls,
        "tabs": tabs,
    })
}

fn emit(v: &WebView, ch: &str, args: Value) {
    let js = format!("window.__emit&&window.__emit({},{});", json!(ch), args);
    v.run_javascript(&js, None::<&gtk::gio::Cancellable>, |_| {});
}

fn push_now(b: &B) {
    let (state, views) = {
        let br = b.borrow();
        (build_state(&br), vec![br.tabs_v.clone(), br.tool_v.clone(), br.status_v.clone(), br.overlay_v.clone()])
    };
    for v in views {
        emit(&v, "state", json!([state]));
    }
}

fn push(b: &B) {
    if b.borrow().push_pending {
        return;
    }
    b.borrow_mut().push_pending = true;
    let b = b.clone();
    glib::timeout_add_local_once(Duration::from_millis(40), move || {
        b.borrow_mut().push_pending = false;
        push_now(&b);
    });
}

// ---------------------------------------------------------------- tabs

fn new_tab_url(br: &Br) -> String {
    match s_str(&br.settings, "general.newTab").as_str() {
        "blank" => "about:blank".into(),
        "home" => s_str(&br.settings, "general.homepage"),
        _ => "my://start".into(),
    }
}

fn active_view(b: &B) -> Option<WebView> {
    let br = b.borrow();
    br.tabs.iter().find(|t| t.id == br.active).map(|t| t.view.clone())
}

fn with_tab<F: FnOnce(&mut Tab)>(b: &B, id: u32, f: F) {
    if let Some(t) = b.borrow_mut().tabs.iter_mut().find(|t| t.id == id) {
        f(t);
    }
}

fn new_tab(b: &B, url: Option<&str>, private: bool, background: bool) -> WebView {
    let (id, ctx, hook, root, stack, target) = {
        let mut br = b.borrow_mut();
        let id = br.next;
        br.next += 1;
        let target = url.map(|u| u.to_string()).unwrap_or_else(|| new_tab_url(&br));
        (id, if private { br.pctx.clone() } else { br.ctx.clone() }, br.hook.clone(), br.root.clone(), br.stack.clone(), target)
    };
    let ucm = make_ucm(&hook, &format!("tab:{id}"), &root, false);
    let view = WebView::builder().web_context(&ctx).user_content_manager(&ucm).build();
    if let Some(s) = WebViewExt::settings(&view) {
        use webkit2gtk::SettingsExt;
        s.set_enable_smooth_scrolling(true);
        s.set_enable_developer_extras(true);
        s.set_javascript_can_open_windows_automatically(true);
    }

    {
        let b = b.clone();
        view.connect_title_notify(move |v| {
            let t = v.title().map(|s| s.to_string()).unwrap_or_default();
            let url = v.uri().map(|s| s.to_string()).unwrap_or_default();
            with_tab(&b, id, |tab| tab.title = t.clone());
            // keep the history entry's title up to date
            if is_web(&url) {
                let mut br = b.borrow_mut();
                if let Some(h) = br.history.iter_mut().rev().take(5).find(|h| h["url"].as_str() == Some(&url)) {
                    h["title"] = json!(t);
                }
            }
            push(&b);
        });
    }
    {
        let b = b.clone();
        view.connect_uri_notify(move |v| {
            let u = v.uri().map(|s| s.to_string()).unwrap_or_default();
            with_tab(&b, id, |t| t.url = u);
            push(&b);
        });
    }
    {
        let b = b.clone();
        view.connect_load_changed(move |v, ev| {
            match ev {
                LoadEvent::Started => with_tab(&b, id, |t| t.loading = true),
                LoadEvent::Committed => {
                    let u = v.uri().map(|s| s.to_string()).unwrap_or_default();
                    with_tab(&b, id, |t| t.url = u.clone());
                    let private = b.borrow().tabs.iter().find(|t| t.id == id).map_or(false, |t| t.private);
                    if is_web(&u) && !private {
                        b.borrow_mut().history.push(json!({"url": u, "title": "", "time": now_ms()}));
                        schedule_save(&b);
                    }
                }
                LoadEvent::Finished => with_tab(&b, id, |t| t.loading = false),
                _ => {}
            }
            push(&b);
        });
    }
    {
        let b = b.clone();
        view.connect_mouse_target_changed(move |_, hit, _| {
            let link = if hit.context_is_link() { hit.link_uri().map(|s| s.to_string()).unwrap_or_default() } else { String::new() };
            with_tab(&b, id, |t| t.hover = link);
            push(&b);
        });
    }
    {
        // target=_blank and window.open become tabs
        let b = b.clone();
        view.connect_local("create", false, move |_| {
            let v = new_tab(&b, None, false, false);
            Some(v.upcast::<gtk::Widget>().to_value())
        });
    }
    {
        let b = b.clone();
        view.connect_find_controller_notify_dummy(&b);
    }

    stack.add_named(&view, &id.to_string());
    view.show();
    b.borrow_mut().tabs.push(Tab { id, view: view.clone(), title: String::new(), url: target.clone(), loading: false, pinned: false, private, muted: false, hover: String::new(), custom: None });
    if !background {
        switch_tab(b, id);
    }
    view.load_uri(&target);
    push(b);
    view
}

/// helper trait so the tab setup above stays readable (find results go to the overlay)
trait FindHook {
    fn connect_find_controller_notify_dummy(&self, b: &B);
}
impl FindHook for WebView {
    fn connect_find_controller_notify_dummy(&self, b: &B) {
        if let Some(fc) = self.find_controller() {
            let b1 = b.clone();
            fc.connect_counted_matches(move |_, n| {
                let ov = b1.borrow().overlay_v.clone();
                emit(&ov, "find:result", json!([{"matches": n, "activeMatchOrdinal": 0, "finalUpdate": true}]));
            });
            let b2 = b.clone();
            fc.connect_failed_to_find_text(move |_| {
                let ov = b2.borrow().overlay_v.clone();
                emit(&ov, "find:result", json!([{"matches": 0, "activeMatchOrdinal": 0, "finalUpdate": true}]));
            });
        }
    }
}

fn switch_tab(b: &B, id: u32) {
    let (stack, ok) = {
        let br = b.borrow();
        (br.stack.clone(), br.tabs.iter().any(|t| t.id == id))
    };
    if !ok {
        return;
    }
    stack.set_visible_child_name(&id.to_string());
    b.borrow_mut().active = id;
    hide_overlay(b, false);
    push(b);
}

fn close_tab(b: &B, id: u32) {
    let (stack, view, next, empty) = {
        let mut br = b.borrow_mut();
        let Some(pos) = br.tabs.iter().position(|t| t.id == id) else { return };
        let t = br.tabs.remove(pos);
        if is_web(&t.url) && !t.private {
            br.closed.push(t.url.clone());
        }
        let next = if br.active == id { br.tabs.get(pos.min(br.tabs.len().saturating_sub(1))).map(|t| t.id) } else { None };
        (br.stack.clone(), t.view.clone(), next, br.tabs.is_empty())
    };
    if empty {
        save_all(&b.borrow());
        gtk::main_quit();
        return;
    }
    if let Some(n) = next {
        switch_tab(b, n);
    }
    stack.remove(&view);
    push(b);
    schedule_save(b);
}

fn go(b: &B, text: &str) {
    let url = normalize(&b.borrow().settings, text);
    if let Some(v) = active_view(b) {
        v.load_uri(&url);
        v.grab_focus();
    }
}

fn reopen_closed(b: &B) {
    let u = b.borrow_mut().closed.pop();
    if let Some(u) = u {
        new_tab(b, Some(&u), false, false);
    }
}

fn cycle(b: &B, dir: i32) {
    let target = {
        let br = b.borrow();
        let n = br.tabs.len() as i32;
        if n < 2 {
            return;
        }
        let i = br.tabs.iter().position(|t| t.id == br.active).unwrap_or(0) as i32;
        br.tabs[((i + dir + n) % n) as usize].id
    };
    switch_tab(b, target);
}

fn zoom(b: &B, dir: i32) {
    if let Some(v) = active_view(b) {
        let z = if dir == 0 { 1.0 } else { (v.zoom_level() + 0.1 * dir as f64).clamp(0.3, 3.0) };
        v.set_zoom_level(z);
        push(b);
    }
}

// ---------------------------------------------------------------- overlay

fn width_height(b: &B) -> (i32, i32) {
    let br = b.borrow();
    (br.win.allocated_width().max(400), br.win.allocated_height().max(300))
}

fn show_overlay(b: &B, mode: &str, data: Value, rect: (i32, i32, i32, i32)) {
    let (ov, ucm_focus) = {
        let mut br = b.borrow_mut();
        br.mode = Some(mode.to_string());
        (br.overlay_v.clone(), mode != "suggest")
    };
    ov.set_margin_start(rect.0);
    ov.set_margin_top(rect.1);
    ov.set_size_request(rect.2, rect.3);
    ov.show();
    emit(&ov, "overlay:open", json!([mode, data]));
    if ucm_focus {
        ov.grab_focus();
    }
}

fn hide_overlay(b: &B, refocus: bool) {
    let (ov, had, mode) = {
        let mut br = b.borrow_mut();
        let m = br.mode.take();
        (br.overlay_v.clone(), m.is_some(), m)
    };
    if !had {
        return;
    }
    ov.hide();
    if mode.as_deref() == Some("find") {
        if let Some(v) = active_view(b) {
            if let Some(fc) = v.find_controller() {
                fc.search_finish();
            }
        }
    }
    if refocus {
        if let Some(v) = active_view(b) {
            v.grab_focus();
        }
    }
}

fn open_find(b: &B) {
    let (w, _) = width_height(b);
    show_overlay(b, "find", json!({}), (w - 400, TAB_H + TOOL_H + 6, 390, 48));
}

fn open_palette(b: &B) {
    let (w, h) = width_height(b);
    let ww = 680.min(w - 40);
    show_overlay(b, "palette", json!({}), ((w - ww) / 2, 60, ww, 470.min((h - 90).max(120))));
}

fn open_bookmark_popup(b: &B) {
    let (url, title, saved, folder, folders) = {
        let br = b.borrow();
        let Some(t) = br.tabs.iter().find(|t| t.id == br.active) else { return };
        if !is_web(&t.url) {
            return;
        }
        let ex = br.bookmarks.iter().find(|x| x["url"].as_str() == Some(&t.url) && !x["deleted"].as_bool().unwrap_or(false));
        let mut folders: Vec<String> = br.bookmarks.iter().filter_map(|x| x["folder"].as_str().filter(|s| !s.is_empty()).map(|s| s.to_string())).collect();
        folders.sort();
        folders.dedup();
        (t.url.clone(), t.title.clone(), ex.is_some(), ex.and_then(|x| x["folder"].as_str()).unwrap_or("").to_string(), folders)
    };
    if b.borrow().mode.as_deref() == Some("bookmark") {
        hide_overlay(b, true);
        return;
    }
    let (w, _) = width_height(b);
    show_overlay(b, "bookmark", json!({"url": url, "title": title, "bookmarked": saved, "folder": folder, "folders": folders, "inSpeedDial": false}), (w - 332, TAB_H + TOOL_H, 320, 300));
}

// ---------------------------------------------------------------- suggestions and the palette

fn matches(terms: &[String], fields: &[&str]) -> bool {
    let s = fields.join(" ").to_lowercase();
    terms.iter().all(|t| s.contains(t))
}

fn suggest_items(br: &Br, text: &str) -> Vec<Value> {
    let q = text.trim();
    if q.is_empty() {
        return vec![];
    }
    let terms: Vec<String> = q.to_lowercase().split_whitespace().map(|s| s.to_string()).collect();
    let mut out: Vec<Value> = vec![];
    let mut seen = std::collections::HashSet::new();
    let mut push = |it: Value, key: String, out: &mut Vec<Value>| {
        if seen.insert(key) {
            out.push(it);
        }
    };
    let urlish = is_scheme_url(q) || looks_like_domain(q) || host_port(q) || q.to_lowercase().starts_with("localhost");
    let eng = q.split_once(' ').map(|(f, r)| (engine(&br.settings, Some(f)), f.to_string(), r.to_string())).filter(|(e, f, _)| e["nick"].as_str() == Some(f.as_str()));
    if urlish {
        push(json!({"kind":"search","text":q,"label":normalize(&br.settings, q),"detail":"Go to address","icon":"→"}), format!("q:{q}"), &mut out);
    } else if let Some((e, _, r)) = eng {
        push(json!({"kind":"search","text":q,"label":format!("Search {} for “{}”", e["name"].as_str().unwrap_or(""), r),"detail":e["name"],"icon":"🔍"}), format!("q:{q}"), &mut out);
    } else {
        let e = engine(&br.settings, None);
        push(json!({"kind":"search","text":q,"label":format!("Search for “{q}”"),"detail":e["name"],"icon":"🔍"}), format!("q:{q}"), &mut out);
    }
    for t in br.tabs.iter().filter(|t| t.id != br.active && matches(&terms, &[&t.title, &t.url])).take(3) {
        push(json!({"kind":"tab","id":t.id,"url":t.url,"label":if t.title.is_empty(){&t.url}else{&t.title},"detail":"Switch to tab","icon":"▭"}), t.url.clone(), &mut out);
    }
    for b in br.bookmarks.iter().filter(|b| !b["deleted"].as_bool().unwrap_or(false) && matches(&terms, &[b["title"].as_str().unwrap_or(""), b["url"].as_str().unwrap_or("")])).take(4) {
        let u = b["url"].as_str().unwrap_or("").to_string();
        let t = b["title"].as_str().filter(|s| !s.is_empty()).unwrap_or(&u).to_string();
        push(json!({"kind":"url","url":u,"label":t,"detail":u,"icon":"★"}), u.clone(), &mut out);
    }
    let now = now_ms() as f64;
    let mut agg: std::collections::HashMap<String, (f64, String)> = std::collections::HashMap::new();
    for h in &br.history {
        let (u, t) = (h["url"].as_str().unwrap_or(""), h["title"].as_str().unwrap_or(""));
        if !matches(&terms, &[t, u]) {
            continue;
        }
        let age = now - h["time"].as_f64().unwrap_or(0.0);
        let e = agg.entry(u.to_string()).or_insert((0.0, t.to_string()));
        e.0 += (-age / (14.0 * 864e5)).exp() + 0.2;
        if !t.is_empty() {
            e.1 = t.to_string();
        }
    }
    let mut h: Vec<(String, (f64, String))> = agg.into_iter().collect();
    h.sort_by(|a, b| b.1 .0.partial_cmp(&a.1 .0).unwrap_or(std::cmp::Ordering::Equal));
    for (u, (_, t)) in h.into_iter().take(5) {
        push(json!({"kind":"url","url":u,"label":if t.is_empty(){u.clone()}else{t},"detail":u,"icon":"🕘","forgettable":true}), u.clone(), &mut out);
    }
    out.truncate(10);
    out
}

fn palette_items(br: &Br, q: &str) -> Vec<Value> {
    let terms: Vec<String> = q.to_lowercase().split_whitespace().map(|s| s.to_string()).collect();
    let mut out = vec![];
    for t in br.tabs.iter().filter(|t| matches(&terms, &[&t.title, &t.url])).take(8) {
        out.push(json!({"kind":"tab","id":t.id,"label":if t.title.is_empty(){&t.url}else{&t.title},"detail":format!("Open tab · {}", t.url)}));
    }
    for b in br.bookmarks.iter().filter(|b| !b["deleted"].as_bool().unwrap_or(false) && matches(&terms, &[b["title"].as_str().unwrap_or(""), b["url"].as_str().unwrap_or("")])).take(8) {
        out.push(json!({"kind":"url","url":b["url"],"label":b["title"],"detail":b["url"]}));
    }
    out
}

fn run_item(b: &B, item: &Value) {
    hide_overlay(b, false);
    match item["kind"].as_str() {
        Some("tab") => {
            if let Some(id) = item["id"].as_u64() {
                switch_tab(b, id as u32);
            }
        }
        Some("url") => {
            if let (Some(u), Some(v)) = (item["url"].as_str(), active_view(b)) {
                v.load_uri(u);
                v.grab_focus();
            }
        }
        Some("search") => go(b, item["text"].as_str().unwrap_or("")),
        _ => {}
    }
}

fn show_suggestions(b: &B) {
    let (ov, items, sel, w) = {
        let br = b.borrow();
        (br.overlay_v.clone(), br.suggest.clone(), br.sel, br.win.allocated_width().max(400))
    };
    ov.set_size_request(760.min((w - 190).max(200)), items.len() as i32 * 36 + 12);
    emit(&ov, "suggest:items", json!([items, sel]));
}

// ---------------------------------------------------------------- menus

fn menu_item(menu: &gtk::Menu, label: &str, f: impl Fn() + 'static) {
    let it = gtk::MenuItem::with_label(label);
    it.connect_activate(move |_| f());
    menu.append(&it);
}

fn main_menu(b: &B) {
    let m = gtk::Menu::new();
    let items: Vec<(&str, Box<dyn Fn()>)> = vec![
        ("New tab", { let b = b.clone(); Box::new(move || { new_tab(&b, None, false, false); }) }),
        ("New private tab", { let b = b.clone(); Box::new(move || { new_tab(&b, None, true, false); }) }),
        ("Reopen closed tab", { let b = b.clone(); Box::new(move || reopen_closed(&b)) }),
        ("Bookmarks", { let b = b.clone(); Box::new(move || { new_tab(&b, Some("my://bookmarks"), false, false); }) }),
        ("History", { let b = b.clone(); Box::new(move || { new_tab(&b, Some("my://history"), false, false); }) }),
        ("Downloads", { let b = b.clone(); Box::new(move || { new_tab(&b, Some("my://downloads"), false, false); }) }),
        ("Passwords", { let b = b.clone(); Box::new(move || { new_tab(&b, Some("my://passwords"), false, false); }) }),
        ("Find in page", { let b = b.clone(); Box::new(move || open_find(&b)) }),
        ("Zoom in", { let b = b.clone(); Box::new(move || zoom(&b, 1)) }),
        ("Zoom out", { let b = b.clone(); Box::new(move || zoom(&b, -1)) }),
        ("Settings", { let b = b.clone(); Box::new(move || { new_tab(&b, Some("my://settings"), false, false); }) }),
        ("Quit", Box::new(|| gtk::main_quit())),
    ];
    for (l, f) in items {
        menu_item(&m, l, f);
    }
    m.show_all();
    m.popup_easy(0, gtk::current_event_time());
}

fn tab_menu(b: &B, id: u32) {
    let m = gtk::Menu::new();
    let (pinned, muted) = b.borrow().tabs.iter().find(|t| t.id == id).map_or((false, false), |t| (t.pinned, t.muted));
    { let b = b.clone(); menu_item(&m, "New tab", move || { new_tab(&b, None, false, false); }); }
    { let b = b.clone(); menu_item(&m, "Reload", move || { let v = b.borrow().tabs.iter().find(|t| t.id == id).map(|t| t.view.clone()); if let Some(v) = v { v.reload(); } }); }
    { let b = b.clone(); menu_item(&m, "Duplicate", move || { let u = b.borrow().tabs.iter().find(|t| t.id == id).map(|t| t.url.clone()); if let Some(u) = u { new_tab(&b, Some(&u), false, false); } }); }
    { let b = b.clone(); menu_item(&m, if pinned { "Unpin tab" } else { "Pin tab" }, move || { with_tab(&b, id, |t| t.pinned = !t.pinned); push(&b); }); }
    { let b = b.clone(); menu_item(&m, if muted { "Unmute tab" } else { "Mute tab" }, move || { mute_tab(&b, id); }); }
    { let b = b.clone(); menu_item(&m, "Close other tabs", move || { let ids: Vec<u32> = b.borrow().tabs.iter().filter(|t| t.id != id && !t.pinned).map(|t| t.id).collect(); for i in ids { close_tab(&b, i); } }); }
    { let b = b.clone(); menu_item(&m, "Close tab", move || close_tab(&b, id)); }
    m.show_all();
    m.popup_easy(0, gtk::current_event_time());
}

fn mute_tab(b: &B, id: u32) {
    let v = {
        let mut br = b.borrow_mut();
        br.tabs.iter_mut().find(|t| t.id == id).map(|t| { t.muted = !t.muted; (t.view.clone(), t.muted) })
    };
    if let Some((v, m)) = v {
        v.set_is_muted(m);
    }
    push(b);
}

// ---------------------------------------------------------------- IPC from the pages

fn arg_str(a: &[Value], i: usize) -> String {
    a.get(i).and_then(|v| v.as_str()).unwrap_or("").to_string()
}

fn view_for(b: &B, src: &str) -> Option<WebView> {
    let br = b.borrow();
    match src {
        "tabs" => Some(br.tabs_v.clone()),
        "toolbar" => Some(br.tool_v.clone()),
        "status" => Some(br.status_v.clone()),
        "overlay" => Some(br.overlay_v.clone()),
        s => s.strip_prefix("tab:").and_then(|n| n.parse::<u32>().ok()).and_then(|id| br.tabs.iter().find(|t| t.id == id).map(|t| t.view.clone())),
    }
}

fn ipc(b: &B, text: &str) {
    let Ok(m) = serde_json::from_str::<Value>(text) else { return };
    let ch = m["ch"].as_str().unwrap_or("").to_string();
    let args: Vec<Value> = m["a"].as_array().cloned().unwrap_or_default();
    let src = m["src"].as_str().unwrap_or("").to_string();
    if m["k"] == "i" {
        let r = invoke(b, &ch, &args);
        let id = m["id"].as_u64().unwrap_or(0);
        if let Some(v) = view_for(b, &src) {
            v.run_javascript(&format!("window.__res({id},{});", r), None::<&gtk::gio::Cancellable>, |_| {});
        }
    } else {
        send(b, &ch, &args);
    }
}

fn send(b: &B, ch: &str, a: &[Value]) {
    match ch {
        "tab:new" => {
            let u = arg_str(a, 0);
            new_tab(b, if u.is_empty() { None } else { Some(&u) }, false, false);
        }
        "tab:new-private" => { new_tab(b, None, true, false); }
        "tab:switch" => { if let Some(id) = a.get(0).and_then(|v| v.as_u64()) { switch_tab(b, id as u32); } }
        "tab:close" => { if let Some(id) = a.get(0).and_then(|v| v.as_u64()) { close_tab(b, id as u32); } }
        "tab:next" => cycle(b, 1),
        "tab:prev" => cycle(b, -1),
        "tab:mute" => { if let Some(id) = a.get(0).and_then(|v| v.as_u64()) { mute_tab(b, id as u32); } }
        "tab:menu" => { if let Some(id) = a.get(0).and_then(|v| v.as_u64()) { tab_menu(b, id as u32); } }
        "tab:move" => {
            if let Some(id) = a.get(0).and_then(|v| v.as_u64()) {
                let before = a.get(1).and_then(|v| v.as_u64());
                let mut br = b.borrow_mut();
                if let Some(pos) = br.tabs.iter().position(|t| t.id as u64 == id) {
                    let t = br.tabs.remove(pos);
                    let to = before.and_then(|bid| br.tabs.iter().position(|x| x.id as u64 == bid)).unwrap_or(br.tabs.len());
                    br.tabs.insert(to, t);
                }
                drop(br);
                push(b);
            }
        }
        "tab:active-click" => { if let Some(v) = active_view(b) { v.run_javascript("window.scrollTo({top:0,behavior:'smooth'})", None::<&gtk::gio::Cancellable>, |_| {}); } }
        "nav:go" => go(b, &arg_str(a, 0)),
        "nav:back" => { if let Some(v) = active_view(b) { v.go_back(); } }
        "nav:forward" => { if let Some(v) = active_view(b) { v.go_forward(); } }
        "nav:reload" => { if let Some(v) = active_view(b) { if v.is_loading() { v.stop_loading(); } else { v.reload(); } } }
        "url:enter" => {
            let (item, text) = {
                let br = b.borrow();
                (if br.sel >= 0 { br.suggest.get(br.sel as usize).cloned() } else { None }, arg_str(a, 0))
            };
            hide_overlay(b, false);
            { let mut br = b.borrow_mut(); br.suggest.clear(); br.sel = -1; }
            match item { Some(it) => run_item(b, &it), None => go(b, &text) }
        }
        "suggest:query" => {
            let text = arg_str(a, 0);
            if matches!(b.borrow().mode.as_deref(), Some(m) if m != "suggest") {
                return;
            }
            let items = suggest_items(&b.borrow(), &text);
            if items.is_empty() {
                b.borrow_mut().suggest.clear();
                if b.borrow().mode.as_deref() == Some("suggest") {
                    hide_overlay(b, false);
                }
                return;
            }
            let was = b.borrow().mode.as_deref() == Some("suggest");
            { let mut br = b.borrow_mut(); br.suggest = items.clone(); br.sel = -1; br.typed = text; }
            if !was {
                let w = width_height(b).0;
                show_overlay(b, "suggest", json!({}), (150, TAB_H + TOOL_H - 2, 760.min((w - 190).max(200)), items.len() as i32 * 36 + 12));
            }
            show_suggestions(b);
        }
        "suggest:nav" => {
            let dir = a.get(0).and_then(|v| v.as_i64()).unwrap_or(1);
            let (fill, tool) = {
                let mut br = b.borrow_mut();
                if br.mode.as_deref() != Some("suggest") || br.suggest.is_empty() {
                    return;
                }
                let n = br.suggest.len() as i64;
                let mut sel = br.sel + if dir > 0 { 1 } else { -1 };
                if sel >= n { sel = -1; }
                if sel < -1 { sel = n - 1; }
                br.sel = sel;
                let fill = if sel >= 0 {
                    let it = &br.suggest[sel as usize];
                    if it["kind"] == "search" { it["text"].clone() } else { it["url"].clone() }
                } else { json!(br.typed) };
                (fill, br.tool_v.clone())
            };
            show_suggestions(b);
            emit(&tool, "url:fill", json!([fill]));
        }
        "suggest:forget" => {
            let u = arg_str(a, 0);
            b.borrow_mut().history.retain(|h| h["url"].as_str() != Some(&u));
            let typed = b.borrow().typed.clone();
            let items = suggest_items(&b.borrow(), &typed);
            if items.is_empty() { hide_overlay(b, false); } else { { let mut br = b.borrow_mut(); br.suggest = items; br.sel = -1; } show_suggestions(b); }
            schedule_save(b);
        }
        "suggest:close" => {
            let b = b.clone();
            glib::timeout_add_local_once(Duration::from_millis(150), move || {
                if b.borrow().mode.as_deref() == Some("suggest") {
                    hide_overlay(&b, false);
                }
            });
        }
        "overlay:close" => hide_overlay(b, true),
        "overlay:resize" => {
            let h = a.get(0).and_then(|v| v.as_f64()).unwrap_or(0.0) as i32;
            if h > 0 && b.borrow().mode.as_deref() == Some("bookmark") {
                let ov = b.borrow().overlay_v.clone();
                let (w, _) = width_height(b);
                ov.set_size_request(320, h.clamp(150, 520));
                ov.set_margin_start(w - 332);
            }
        }
        "palette:run" => { if let Some(it) = a.get(0) { run_item(b, &it.clone()); } }
        "menu:main" => main_menu(b),
        "bookmark:toggle" => open_bookmark_popup(b),
        "find:do" => {
            let text = arg_str(a, 0);
            let forward = a.get(1).and_then(|v| v.as_bool()).unwrap_or(true);
            let next = a.get(2).and_then(|v| v.as_bool()).unwrap_or(false);
            if let Some(v) = active_view(b) {
                if let Some(fc) = v.find_controller() {
                    if text.is_empty() {
                        fc.search_finish();
                    } else if next {
                        if forward { fc.search_next(); } else { fc.search_previous(); }
                    } else {
                        fc.search(&text, 1 | 32, 1000); // case-insensitive, wrap around
                        fc.count_matches(&text, 1 | 32, 1000);
                    }
                }
            }
        }
        "zoom:reset" => zoom(b, 0),
        "zoom:set" => {
            if let (Some(p), Some(v)) = (a.get(0).and_then(|v| v.as_f64()), active_view(b)) {
                v.set_zoom_level((p / 100.0).clamp(0.3, 3.0));
                push(b);
            }
        }
        "win:min" => b.borrow().win.iconify(),
        "win:max" => { let w = b.borrow().win.clone(); if w.is_maximized() { w.unmaximize(); } else { w.maximize(); } }
        "win:close" => gtk::main_quit(),
        "reader:toggle" | "security:show" | "shield:toggle" | "home:toggle" | "panel:toggle" | "lock:now" | "profile:menu" => {}
        _ => {}
    }
}

fn bookmark_add_or_update(b: &B, url: &str, title: &str, folder: &str) {
    let now = now_ms();
    let mut br = b.borrow_mut();
    if let Some(x) = br.bookmarks.iter_mut().find(|x| x["url"].as_str() == Some(url)) {
        x["deleted"] = json!(false);
        x["folder"] = json!(folder);
        x["updated"] = json!(now);
        if !title.is_empty() {
            x["title"] = json!(title);
        }
        x.as_object_mut().map(|o| o.remove("recycleBin"));
    } else {
        br.bookmarks.push(json!({"url": url, "title": if title.is_empty() { url } else { title }, "folder": folder, "added": now, "updated": now, "deleted": false}));
    }
}

fn bookmark_remove(b: &B, url: &str) {
    let now = now_ms();
    let mut br = b.borrow_mut();
    for x in br.bookmarks.iter_mut().filter(|x| x["url"].as_str() == Some(url)) {
        x["deleted"] = json!(true);
        x["updated"] = json!(now);
    }
}

fn invoke(b: &B, ch: &str, a: &[Value]) -> Value {
    match ch {
        "settings:get" => b.borrow().settings.clone(),
        "settings:set" => {
            let p = arg_str(a, 0);
            if let Some(v) = a.get(1) {
                set_path(&mut b.borrow_mut().settings, &p, v.clone());
                schedule_save(b);
                push(b);
            }
            json!(true)
        }
        "themes:list" => {
            let br = b.borrow();
            json!({"presets": br.themes, "custom": br.settings["appearance"]["customThemes"], "current": br.settings["appearance"]["themeId"]})
        }
        "commands:list" => json!([]),
        "bookmarks:all" => {
            let br = b.borrow();
            let list: Vec<Value> = br.bookmarks.iter().filter(|x| !x["deleted"].as_bool().unwrap_or(false) && !x["recycleBin"].as_bool().unwrap_or(false) && is_web(x["url"].as_str().unwrap_or("")))
                .map(|x| json!({"url": x["url"], "title": x["title"], "folder": x["folder"], "added": x["added"]})).collect();
            let mut folders: Vec<String> = list.iter().filter_map(|x| x["folder"].as_str().filter(|s| !s.is_empty()).map(|s| s.to_string())).collect();
            folders.sort();
            folders.dedup();
            json!({"bookmarks": list, "folders": folders})
        }
        "bookmarks:save" => {
            let o = a.get(0).cloned().unwrap_or(json!({}));
            let target = o["newUrl"].as_str().filter(|s| !s.is_empty()).or(o["url"].as_str()).unwrap_or("").trim().to_string();
            let fin = if is_scheme_url(&target) { target } else { format!("https://{target}") };
            if !is_web(&fin) {
                return json!({"error": "Enter a web address (http or https)."});
            }
            let old = o["url"].as_str().unwrap_or("").to_string();
            if !old.is_empty() && old != fin {
                bookmark_remove(b, &old);
            }
            bookmark_add_or_update(b, &fin, o["title"].as_str().unwrap_or(""), o["folder"].as_str().unwrap_or(""));
            schedule_save(b);
            push(b);
            json!({"ok": true})
        }
        "bookmarks:remove" | "bookmark:remove" | "bookmark:popup-remove" => {
            bookmark_remove(b, &arg_str(a, 0));
            schedule_save(b);
            push(b);
            json!(true)
        }
        "bookmark:popup-save" => {
            bookmark_add_or_update(b, &arg_str(a, 0), &arg_str(a, 1), arg_str(a, 2).trim());
            schedule_save(b);
            push(b);
            json!({"ok": true})
        }
        "bookmark:popup-speeddial" => json!({"ok": true, "added": false}),
        "bookmarks:move" => {
            let (url, folder) = (arg_str(a, 0), arg_str(a, 1));
            if let Some(x) = b.borrow_mut().bookmarks.iter_mut().find(|x| x["url"].as_str() == Some(&url)) {
                x["folder"] = json!(folder);
                x["updated"] = json!(now_ms());
            }
            schedule_save(b);
            json!(true)
        }
        "history:query" => {
            let o = a.get(0).cloned().unwrap_or(json!({}));
            let terms: Vec<String> = o["q"].as_str().unwrap_or("").to_lowercase().split_whitespace().map(|s| s.to_string()).collect();
            let from = o["from"].as_f64().unwrap_or(0.0);
            let to = o["to"].as_f64().unwrap_or(f64::MAX);
            let limit = o["limit"].as_u64().unwrap_or(300).clamp(1, 20000) as usize;
            let br = b.borrow();
            let out: Vec<Value> = br.history.iter().rev().filter(|h| {
                let t = h["time"].as_f64().unwrap_or(0.0);
                t >= from && t <= to && matches(&terms, &[h["title"].as_str().unwrap_or(""), h["url"].as_str().unwrap_or("")])
            }).take(limit).cloned().collect();
            Value::Array(out)
        }
        "history:delete" => {
            let urls: Vec<String> = a.get(0).and_then(|v| v.as_array()).map(|l| l.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect()).unwrap_or_else(|| vec![arg_str(a, 0)]);
            b.borrow_mut().history.retain(|h| !urls.iter().any(|u| h["url"].as_str() == Some(u)));
            schedule_save(b);
            json!(true)
        }
        "data:clear" => {
            if matches!(arg_str(a, 0).as_str(), "history" | "all") {
                b.borrow_mut().history.clear();
                schedule_save(b);
            }
            json!(true)
        }
        "library:get" => {
            let br = b.borrow();
            json!({"bookmarks": br.bookmarks, "history": br.history.iter().rev().take(500).collect::<Vec<_>>(), "remoteTabs": [], "syncStatus": {"state": "idle", "message": "Sync is not part of the Rust build yet"}})
        }
        "palette:query" => Value::Array(palette_items(&b.borrow(), &arg_str(a, 0))),
        "downloads:list" => Value::Array(b.borrow().downloads.clone()),
        "downloads:dir" => json!(downloads_dir()),
        "downloads:action" => {
            let id = a.get(0).and_then(|v| v.as_u64()).unwrap_or(0);
            let act = arg_str(a, 1);
            let path = b.borrow().downloads.iter().find(|d| d["id"].as_u64() == Some(id)).and_then(|d| d["path"].as_str().map(|s| s.to_string()));
            if let (Some(p), true) = (path, act == "open" || act == "show") {
                let target = if act == "show" { PathBuf::from(&p).parent().map(|x| x.to_path_buf()).unwrap_or_default() } else { PathBuf::from(&p) };
                let _ = std::process::Command::new("xdg-open").arg(target).spawn();
            }
            json!(true)
        }
        "profiles:list" => json!({"profiles": [{"id": "default", "name": "Default", "color": "#5b8cff", "avatar": ""}], "current": "default", "askAtStart": false}),
        "profiles:getPrefs" => json!({"defaultProfile": "default", "openLast": false}),
        "sync:status" => json!({"state": "idle", "message": "Sync is not part of the Rust build yet"}),
        "app:versions" => json!({"app": "rust", "version": env!("CARGO_PKG_VERSION")}),
        "app:build-number" => json!(0),
        "vault:open" | "vault:list" | "vault:folders" | "vault:listCards" | "vault:listAddresses" => json!([]),
        "panel:state" => json!({"open": false}),
        "extensions:list" => json!([]),
        _ => Value::Null,
    }
}

fn downloads_dir() -> String {
    std::env::var("XDG_DOWNLOAD_DIR").unwrap_or_else(|_| format!("{}/Downloads", std::env::var("HOME").unwrap_or_else(|_| ".".into())))
}

// ---------------------------------------------------------------- downloads

fn hook_downloads(ctx: &WebContext, b: &B) {
    let b = b.clone();
    ctx.connect_download_started(move |_, dl| {
        let id = {
            let mut br = b.borrow_mut();
            br.dl_next += 1;
            br.dl_next
        };
        let url = dl.request().and_then(|r| webkit2gtk::URIRequestExt::uri(&r)).map(|s| s.to_string()).unwrap_or_default();
        {
            let b = b.clone();
            let url = url.clone();
            dl.connect_decide_destination(move |d, name| {
                let dir = downloads_dir();
                let _ = std::fs::create_dir_all(&dir);
                let mut path = PathBuf::from(&dir).join(name);
                let mut n = 1;
                while path.exists() {
                    let (stem, ext) = match name.rsplit_once('.') { Some((s, e)) => (s.to_string(), format!(".{e}")), None => (name.to_string(), String::new()) };
                    path = PathBuf::from(&dir).join(format!("{stem} ({n}){ext}"));
                    n += 1;
                }
                d.set_destination(&format!("file://{}", path.display()));
                b.borrow_mut().downloads.push(json!({"id": id, "name": path.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default(), "url": url, "total": 0, "received": 0, "state": "progressing", "path": path.to_string_lossy()}));
                push(&b);
                true
            });
        }
        {
            let b = b.clone();
            dl.connect_received_data(move |d, _| {
                let rec = d.received_data_length();
                for x in b.borrow_mut().downloads.iter_mut().filter(|x| x["id"].as_u64() == Some(id)) {
                    x["received"] = json!(rec);
                }
                push(&b);
            });
        }
        {
            let b = b.clone();
            dl.connect_finished(move |_| {
                for x in b.borrow_mut().downloads.iter_mut().filter(|x| x["id"].as_u64() == Some(id) && x["state"] == "progressing") {
                    x["state"] = json!("completed");
                }
                push(&b);
            });
        }
        {
            let b = b.clone();
            dl.connect_failed(move |_, _| {
                for x in b.borrow_mut().downloads.iter_mut().filter(|x| x["id"].as_u64() == Some(id)) {
                    x["state"] = json!("cancelled");
                }
                push(&b);
            });
        }
    });
}

// ---------------------------------------------------------------- start up

fn chrome_view(ctx: &WebContext, hook: &Hook, root: &PathBuf, src: &str, page: &str, h: i32) -> WebView {
    let ucm = make_ucm(hook, src, root, true);
    let v = WebView::builder().web_context(ctx).user_content_manager(&ucm).build();
    v.set_size_request(-1, h);
    v.load_uri(page);
    v
}

fn main() {
    gtk::init().expect("could not start GTK (is a display available?)");
    if let Some(s) = gtk::Settings::default() {
        s.set_gtk_application_prefer_dark_theme(true);
    }
    let root = find_root();
    let data = data_dir();
    let hook: Hook = Rc::new(RefCell::new(None));

    let ctx = WebContext::default().expect("web context");
    let pctx = WebContext::new_ephemeral();
    register_scheme(&ctx, &hook);
    register_scheme(&pctx, &hook);

    // settings: the defaults from the Electron version, with what was saved on top
    let mut settings: Value = read_json(&root.join("rust/data/defaults.json")).or_else(|| serde_json::from_str(include_str!("../data/defaults.json")).ok()).unwrap_or(json!({}));
    if let Some(saved) = read_json(&data.join("settings.json")) {
        merge(&mut settings, &saved);
    }
    let themes: Vec<Value> = serde_json::from_str(include_str!("../data/themes.json")).unwrap_or_default();
    let bookmarks: Vec<Value> = read_json(&data.join("bookmarks.json")).and_then(|v| v.as_array().cloned()).unwrap_or_default();
    let history: Vec<Value> = read_json(&data.join("history.json")).and_then(|v| v.as_array().cloned()).unwrap_or_default();

    let window = gtk::Window::new(gtk::WindowType::Toplevel);
    window.set_default_size(1280, 820);
    window.set_title("mbrowser");
    let icon = root.join("assets/icon.png");
    if icon.exists() {
        let _ = window.set_icon_from_file(&icon);
    }

    let tabs_v = chrome_view(&ctx, &hook, &root, "tabs", "my://chrome/ui.html?part=tabs", TAB_H);
    let tool_v = chrome_view(&ctx, &hook, &root, "toolbar", "my://chrome/ui.html?part=toolbar", TOOL_H);
    let status_v = chrome_view(&ctx, &hook, &root, "status", "my://chrome/ui.html?part=status", STATUS_H);
    let overlay_v = chrome_view(&ctx, &hook, &root, "overlay", "my://chrome/overlay.html", 10);
    overlay_v.set_background_color(&gtk::gdk::RGBA::new(0.0, 0.0, 0.0, 0.0));
    overlay_v.set_halign(gtk::Align::Start);
    overlay_v.set_valign(gtk::Align::Start);
    overlay_v.set_no_show_all(true);
    overlay_v.hide();

    let stack = gtk::Stack::new();
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    column.pack_start(&tabs_v, false, false, 0);
    column.pack_start(&tool_v, false, false, 0);
    column.pack_start(&stack, true, true, 0);
    column.pack_start(&status_v, false, false, 0);
    let over = gtk::Overlay::new();
    over.add(&column);
    over.add_overlay(&overlay_v);
    over.set_overlay_pass_through(&overlay_v, false);
    window.add(&over);

    let br = Br {
        win: window.clone(), tabs_v: tabs_v.clone(), tool_v: tool_v.clone(), status_v: status_v.clone(), overlay_v: overlay_v.clone(),
        stack, ctx: ctx.clone(), pctx: pctx.clone(), ucm_chrome: UserContentManager::new(), tabs: vec![], active: 0, next: 1,
        settings, bookmarks, history, downloads: vec![], closed: vec![], themes, root: root.clone(), data: data.clone(),
        mode: None, suggest: vec![], sel: -1, typed: String::new(), push_pending: false, save_pending: false, dl_next: 0, hook: hook.clone(),
    };
    let b: B = Rc::new(RefCell::new(br));
    *hook.borrow_mut() = Some(b.clone());
    hook_downloads(&ctx, &b);
    hook_downloads(&pctx, &b);

    // the chrome pages ask for their first state once they have loaded
    for v in [&tabs_v, &tool_v, &status_v, &overlay_v] {
        let b = b.clone();
        v.connect_load_changed(move |_, ev| {
            if ev == LoadEvent::Finished {
                push(&b);
            }
        });
    }

    {
        let b = b.clone();
        window.connect_key_press_event(move |_, e| {
            use gtk::gdk::keys::constants as k;
            let st = e.state();
            let ctrl = st.contains(gtk::gdk::ModifierType::CONTROL_MASK);
            let shift = st.contains(gtk::gdk::ModifierType::SHIFT_MASK);
            let alt = st.contains(gtk::gdk::ModifierType::MOD1_MASK);
            let key = e.keyval();
            let stop = glib::Propagation::Stop;
            if key == k::Escape && b.borrow().mode.is_some() {
                hide_overlay(&b, true);
                return stop;
            }
            if alt && key == k::Left { if let Some(v) = active_view(&b) { v.go_back(); } return stop; }
            if alt && key == k::Right { if let Some(v) = active_view(&b) { v.go_forward(); } return stop; }
            if key == k::F5 { if let Some(v) = active_view(&b) { v.reload(); } return stop; }
            if key == k::F6 { let t = b.borrow().tool_v.clone(); t.grab_focus(); emit(&t, "focus-url", json!([])); return stop; }
            if !ctrl {
                return glib::Propagation::Proceed;
            }
            let c = key.to_unicode().map(|c| c.to_ascii_lowercase());
            match (c, shift) {
                (Some('t'), false) => { new_tab(&b, None, false, false); stop }
                (Some('t'), true) => { reopen_closed(&b); stop }
                (Some('n'), true) => { new_tab(&b, None, true, false); stop }
                (Some('w'), _) => { let id = b.borrow().active; close_tab(&b, id); stop }
                (Some('l'), _) => { let t = b.borrow().tool_v.clone(); t.grab_focus(); emit(&t, "focus-url", json!([])); stop }
                (Some('r'), _) => { if let Some(v) = active_view(&b) { v.reload(); } stop }
                (Some('f'), _) => { open_find(&b); stop }
                (Some('d'), _) => { open_bookmark_popup(&b); stop }
                (Some('p'), true) => { open_palette(&b); stop }
                (Some('h'), _) => { new_tab(&b, Some("my://history"), false, false); stop }
                (Some('j'), _) => { new_tab(&b, Some("my://downloads"), false, false); stop }
                (Some('+'), _) | (Some('='), _) => { zoom(&b, 1); stop }
                (Some('-'), _) => { zoom(&b, -1); stop }
                (Some('0'), _) => { zoom(&b, 0); stop }
                _ => {
                    if key == k::Tab || key == k::ISO_Left_Tab { cycle(&b, if shift || key == k::ISO_Left_Tab { -1 } else { 1 }); return stop; }
                    if key == k::Page_Down { cycle(&b, 1); return stop; }
                    if key == k::Page_Up { cycle(&b, -1); return stop; }
                    glib::Propagation::Proceed
                }
            }
        });
    }
    {
        let b = b.clone();
        window.connect_delete_event(move |_, _| {
            save_all(&b.borrow());
            gtk::main_quit();
            glib::Propagation::Proceed
        });
    }
    {
        let b2 = b.clone();
        window.connect_window_state_event(move |_, _| { push(&b2); glib::Propagation::Proceed });
    }

    window.show_all();
    overlay_v.hide();

    // tabs: from the command line, else the last session, else a new tab
    let cli: Vec<String> = std::env::args().skip(1).filter(|a| !a.starts_with('-')).collect();
    if !cli.is_empty() {
        for u in &cli {
            new_tab(&b, Some(&normalize(&b.borrow().settings, u)), false, false);
        }
    } else {
        let restore = s_str(&b.borrow().settings, "general.startup") == "restore" || s_bool(&b.borrow().settings, "general.restore", false);
        let saved = read_json(&data.join("session.json")).and_then(|v| v["tabs"].as_array().cloned()).unwrap_or_default();
        if restore && !saved.is_empty() {
            for t in &saved {
                if let Some(u) = t["url"].as_str() {
                    let v = new_tab(&b, Some(u), false, true);
                    let _ = v;
                    if t["pinned"].as_bool().unwrap_or(false) {
                        let id = b.borrow().tabs.last().map(|x| x.id).unwrap_or(0);
                        with_tab(&b, id, |x| x.pinned = true);
                    }
                }
            }
            let first = b.borrow().tabs.first().map(|t| t.id);
            if let Some(f) = first {
                switch_tab(&b, f);
            }
        } else {
            new_tab(&b, None, false, false);
        }
    }
    gtk::main();
}
