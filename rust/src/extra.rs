//! Reader view, tab stacks and names, the content blocker, profiles and a few small pages' needs.

use super::*;
use webkit2gtk::{UserContentInjectedFrames, UserContentManagerExt, UserScript, UserScriptInjectionTime};

pub fn base_dir() -> PathBuf {
    let base = std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".config"));
    base.join("mbrowser-rs")
}

pub fn profile_id() -> String {
    std::env::args().find_map(|a| a.strip_prefix("--profile=").map(|s| s.to_string())).unwrap_or_else(|| "default".into())
}

// ---------------------------------------------------------------- reader view

fn run_reader(b: &B, id: u32, call: &str, channel: &str) {
    let (view, root) = {
        let br = b.borrow();
        (br.tabs.iter().find(|t| t.id == id).map(|t| t.view.clone()), br.root.clone())
    };
    let Some(view) = view else { return };
    let Ok(js) = std::fs::read_to_string(root.join("assets/reader.js")) else { return };
    let script = format!(
        "{js};Promise.resolve(window.__mbReader.{call}()).then(function(r){{window.webkit.messageHandlers.ipc.postMessage(JSON.stringify({{k:'s',ch:'{channel}',a:[r],src:'tab:{id}'}}))}}).catch(function(){{}});"
    );
    view.run_javascript(&script, None::<&gtk::gio::Cancellable>, |_| {});
}

/// After a page has loaded: is it an article? (drives the address-bar button)
pub fn detect_reader(b: &B, id: u32) {
    let b = b.clone();
    glib::timeout_add_local_once(Duration::from_millis(700), move || {
        let ok = b.borrow().tabs.iter().find(|t| t.id == id).map_or(false, |t| is_web(&t.url) && !t.reader_on);
        if ok {
            run_reader(&b, id, "check", "reader:found");
        }
    });
}

pub fn toggle_reader(b: &B) {
    let id = b.borrow().active;
    run_reader(b, id, "toggle", "reader:toggled");
}

// ---------------------------------------------------------------- stacks

fn prune_stacks(br: &mut Br) {
    let mut count: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
    for t in &br.tabs {
        if let Some(g) = &t.group {
            *count.entry(g.clone()).or_insert(0) += 1;
        }
    }
    for t in br.tabs.iter_mut() {
        if t.group.as_ref().map_or(false, |g| count.get(g).copied().unwrap_or(0) < 2) {
            t.group = None;
        }
    }
}

pub fn stack_tabs(b: &B, drag: u32, target: u32) {
    {
        let mut br = b.borrow_mut();
        if drag == target {
            return;
        }
        let (Some(a), Some(t)) = (br.tabs.iter().find(|t| t.id == drag), br.tabs.iter().find(|t| t.id == target)) else { return };
        if a.pinned || t.pinned {
            return;
        }
        let gid = t.group.clone().unwrap_or_else(|| format!("s{}_{}", drag, now_ms()));
        for x in br.tabs.iter_mut().filter(|x| x.id == drag || x.id == target) {
            x.group = Some(gid.clone());
        }
        // the dropped tab sits right after the last tab of the stack
        if let Some(pos) = br.tabs.iter().position(|x| x.id == drag) {
            let t = br.tabs.remove(pos);
            let last = br.tabs.iter().rposition(|x| x.group.as_deref() == Some(&gid)).map(|i| i + 1).unwrap_or(br.tabs.len());
            br.tabs.insert(last, t);
        }
        prune_stacks(&mut br);
    }
    push(b);
    schedule_save(b);
}

pub fn unstack(b: &B, id: u32, whole: bool) {
    {
        let mut br = b.borrow_mut();
        let Some(g) = br.tabs.iter().find(|t| t.id == id).and_then(|t| t.group.clone()) else { return };
        for x in br.tabs.iter_mut().filter(|x| x.group.as_deref() == Some(&g) && (whole || x.id == id)) {
            x.group = None;
        }
        prune_stacks(&mut br);
    }
    push(b);
    schedule_save(b);
}

// ---------------------------------------------------------------- content blocker

const BUILTIN: &str = "doubleclick.net googlesyndication.com googleadservices.com google-analytics.com googletagmanager.com googletagservices.com adservice.google.com adnxs.com adsrvr.org advertising.com amazon-adsystem.com criteo.com criteo.net taboola.com outbrain.com scorecardresearch.com quantserve.com moatads.com rubiconproject.com pubmatic.com openx.net casalemedia.com smartadserver.com 2mdn.net serving-sys.com adform.net bidswitch.net sharethrough.com teads.tv zedo.com yieldmo.com facebook.net hotjar.com mixpanel.com segment.io fullstory.com crazyegg.com mouseflow.com demdex.net omtrdc.net everesttech.net bluekai.com krxd.net exelator.com rlcdn.com ads-twitter.com ads.linkedin.com snap.licdn.com analytics.tiktok.com amplitude.com parsely.com chartbeat.net mc.yandex.ru adsymptotic.com media.net adition.com revcontent.com mgid.com zemanta.com 33across.com lijit.com sovrn.com indexww.com";

fn hosts_list(custom: &str) -> Vec<String> {
    let mut hosts: Vec<String> = BUILTIN.split_whitespace().map(|s| s.to_string()).collect();
    for line in custom.lines() {
        let l = line.trim().trim_start_matches("||").trim_end_matches('^').to_lowercase();
        if !l.is_empty() && !l.starts_with('#') && !l.starts_with('!') && l.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-') {
            hosts.push(l);
        }
    }
    hosts
}

/// WebKitGTK's content-filter API is not in the Rust bindings, so third-party tracker hosts are blocked by a script that
/// runs before the page: it refuses fetch, XHR, beacons and script/img/iframe sources that point at a listed host.
fn blocker_script(hosts: &[String]) -> String {
    format!(
        "(function(){{var H={};function bad(u){{try{{var x=new URL(u,location.href);if(x.hostname===location.hostname)return false;var h=x.hostname;for(var i=0;i<H.length;i++){{if(h===H[i]||h.endsWith('.'+H[i]))return true}}}}catch(e){{}}return false}}\
var f=window.fetch;if(f)window.fetch=function(a){{var u=a&&a.url||a;if(bad(String(u)))return Promise.reject(new TypeError('blocked'));return f.apply(this,arguments)}};\
var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){{if(bad(String(u)))u='about:blank';return o.apply(this,arguments)}};\
if(navigator.sendBeacon){{var sb=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(u,d){{return bad(String(u))?false:sb(u,d)}}}}\
var sa=Element.prototype.setAttribute;Element.prototype.setAttribute=function(n,v){{if((n==='src'||n==='href')&&/^(SCRIPT|IMG|IFRAME|LINK)$/.test(this.tagName)&&bad(String(v)))return;return sa.apply(this,arguments)}};\
['src'].forEach(function(p){{[HTMLScriptElement,HTMLImageElement,HTMLIFrameElement].forEach(function(C){{var d=Object.getOwnPropertyDescriptor(C.prototype,p);if(d&&d.set)Object.defineProperty(C.prototype,p,{{get:d.get,set:function(v){{if(bad(String(v)))return;d.set.call(this,v)}},configurable:true}})}})}});}})();",
        json!(hosts)
    )
}

pub fn init_blocker(b: &B) {
    let custom = s_str(&b.borrow().settings, "privacy.customBlocklist");
    b.borrow_mut().x.filter = Some(blocker_script(&hosts_list(&custom)));
    apply_blocker(b);
}

fn wanted(br: &Br) -> bool {
    s_bool(&br.settings, "privacy.blockTrackers", true)
}

pub fn attach_filter(b: &B, ucm: &UserContentManager) {
    let (f, on) = {
        let br = b.borrow();
        (br.x.filter.clone(), wanted(&br))
    };
    if let (Some(f), true) = (f, on) {
        ucm.add_script(&UserScript::new(&f, UserContentInjectedFrames::AllFrames, UserScriptInjectionTime::Start, &[], &[]));
    }
}

/// Already-open tabs keep the scripts they have; the setting applies to the next page load of new tabs.
pub fn apply_blocker(_b: &B) {}

pub fn settings_changed(b: &B, path: &str) {
    if path.starts_with("privacy.blockTrackers") {
        apply_blocker(b);
    }
    if path.starts_with("privacy.customBlocklist") {
        init_blocker(b);
    }
}

// ---------------------------------------------------------------- profiles

fn profiles_file() -> PathBuf {
    base_dir().join("profiles.json")
}

fn load_profiles() -> Value {
    let mut v = read_json(&profiles_file()).unwrap_or(json!({}));
    if !v["profiles"].is_array() || v["profiles"].as_array().map_or(true, |a| a.is_empty()) {
        v["profiles"] = json!([{"id": "default", "name": "Default", "color": "#5b8cff", "avatar": ""}]);
    }
    v
}

fn save_profiles(v: &Value) {
    let _ = std::fs::create_dir_all(base_dir());
    write_json(&profiles_file(), v);
}

pub fn profile_info(id: &str) -> Value {
    let v = load_profiles();
    v["profiles"].as_array().and_then(|a| a.iter().find(|p| p["id"].as_str() == Some(id)).cloned()).unwrap_or(json!({"id": id, "name": "Default", "color": "#5b8cff"}))
}

pub fn profile_dir(id: &str) -> PathBuf {
    if id == "default" { base_dir() } else { base_dir().join("profiles").join(id) }
}

fn profiles_invoke(b: &B, ch: &str, a: &[Value]) -> Option<Value> {
    let cur = profile_id();
    let mut v = load_profiles();
    Some(match ch {
        "profiles:list" => {
            let list: Vec<Value> = v["profiles"].as_array().cloned().unwrap_or_default();
            json!({"profiles": list, "current": cur, "askAtStart": v["askAtStart"].as_bool().unwrap_or(false)})
        }
        "profiles:create" => {
            let o = a.get(0).cloned().unwrap_or(json!({}));
            let name = o["name"].as_str().unwrap_or("").trim().to_string();
            if name.is_empty() {
                return Some(json!({"error": "Enter a profile name."}));
            }
            let slug: String = name.to_lowercase().chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).collect::<String>().trim_matches('-').chars().take(16).collect();
            let id = format!("{}-{:x}", if slug.is_empty() { "p".to_string() } else { slug }, now_ms() % 0xfffff);
            let color = o["color"].as_str().unwrap_or("#5b8cff");
            if let Some(arr) = v["profiles"].as_array_mut() {
                arr.push(json!({"id": id, "name": name, "color": color, "avatar": o["avatar"].as_str().unwrap_or("")}));
            }
            save_profiles(&v);
            json!({"ok": true, "id": id})
        }
        "profiles:update" => {
            let id = arg_str(a, 0);
            let o = a.get(1).cloned().unwrap_or(json!({}));
            let mut found = false;
            if let Some(arr) = v["profiles"].as_array_mut() {
                for p in arr.iter_mut().filter(|p| p["id"].as_str() == Some(&id)) {
                    found = true;
                    for k in ["name", "color", "avatar"] {
                        if let Some(x) = o.get(k) {
                            if k == "name" && x.as_str().map_or(true, |s| s.trim().is_empty()) {
                                continue;
                            }
                            p[k] = x.clone();
                        }
                    }
                }
            }
            if !found {
                return Some(json!({"error": "No such profile."}));
            }
            save_profiles(&v);
            push(b);
            json!({"ok": true})
        }
        "profiles:remove" => {
            let id = arg_str(a, 0);
            if id == cur {
                return Some(json!({"error": "Open another profile first; this one is in use."}));
            }
            if id == "default" {
                return Some(json!({"error": "The default profile cannot be removed."}));
            }
            if let Some(arr) = v["profiles"].as_array_mut() {
                arr.retain(|p| p["id"].as_str() != Some(&id));
            }
            save_profiles(&v);
            let _ = std::fs::remove_dir_all(profile_dir(&id));
            json!({"ok": true})
        }
        "profiles:open" | "profiles:addWindow" => {
            let id = if ch == "profiles:open" { arg_str(a, 0) } else { cur.clone() };
            if let Ok(exe) = std::env::current_exe() {
                let _ = std::process::Command::new(exe).arg(format!("--profile={id}")).spawn();
            }
            json!(true)
        }
        "profiles:askAtStart" => {
            v["askAtStart"] = json!(a.get(0).and_then(|x| x.as_bool()).unwrap_or(false));
            save_profiles(&v);
            json!(true)
        }
        "profiles:setDefault" => {
            v["default"] = json!(arg_str(a, 0));
            save_profiles(&v);
            json!(true)
        }
        "profiles:setOpenLast" => {
            v["openLast"] = json!(a.get(0).and_then(|x| x.as_bool()).unwrap_or(false));
            save_profiles(&v);
            json!({"ok": true})
        }
        "profiles:getPrefs" => json!({"defaultProfile": v["default"].as_str().unwrap_or("default"), "openLastProfile": v["openLast"].as_bool().unwrap_or(false), "lastProfile": cur, "current": cur}),
        "profiles:setPassword" => json!({"error": "Profile passwords are not part of the Rust build yet."}),
        "profiles:resetData" => json!({"error": "Resetting profile data is not part of the Rust build yet."}),
        _ => return None,
    })
}

// ---------------------------------------------------------------- everything else the pages ask for

pub fn invoke(b: &B, ch: &str, a: &[Value]) -> Option<Value> {
    if ch.starts_with("profiles:") {
        return profiles_invoke(b, ch, a);
    }
    Some(match ch {
        "blocker:status" => json!({"enabled": wanted(&b.borrow()), "ready": b.borrow().x.filter.is_some(), "hosts": BUILTIN.split_whitespace().count(), "lists": [], "updated": 0}),
        "blocker:update" => json!({"ok": true}),
        "cert:allow" => {
            let host = arg_str(a, 0);
            let mut list: Vec<Value> = b.borrow().settings["privacy"]["certExceptions"].as_array().cloned().unwrap_or_default();
            if !host.is_empty() && !list.iter().any(|x| x.as_str() == Some(&host)) {
                list.push(json!(host));
            }
            data::apply_setting(b, "privacy.certExceptions", Value::Array(list));
            json!(true)
        }
        "default:status" => json!({"supported": true, "isDefault": false}),
        "default:set" => {
            let _ = std::process::Command::new("xdg-settings").args(["set", "default-web-browser", "mbrowser.desktop"]).spawn();
            json!(true)
        }
        "thumb:refresh" | "icon:apply" | "wallpaper:pick" => json!(false),
        "folder:pick" => {
            let win = b.borrow().win.clone();
            let d = gtk::FileChooserDialog::with_buttons(Some("Choose a folder"), Some(&win), gtk::FileChooserAction::SelectFolder, &[("Cancel", gtk::ResponseType::Cancel), ("Choose", gtk::ResponseType::Accept)]);
            let r = d.run();
            let path = d.filename();
            d.close();
            match (r, path) {
                (gtk::ResponseType::Accept, Some(p)) => json!({"path": p.to_string_lossy()}),
                _ => json!({"canceled": true}),
            }
        }
        "app:relaunch" => {
            if let Ok(exe) = std::env::current_exe() {
                let _ = std::process::Command::new(exe).arg(format!("--profile={}", profile_id())).spawn();
            }
            gtk::main_quit();
            json!(true)
        }
        "app:check-updates" | "app:install-update" | "app:auto-install" => json!({"ok": true}),
        "app:update-info" => json!({"upToDate": true, "build": 0}),
        "lock:info" | "lock:has" => json!({"locked": false, "has": false}),
        "lock:set" | "lock:unlock" => json!({"error": "The lock screen is not part of the Rust build yet."}),
        "cloud:info" => json!({"provider": "", "connected": false, "hasKey": false, "ready": false, "account": "", "backend": "server",
            "gClientId": "", "gClientSecret": "", "msClientId": "", "builtin": {"gdrive": {"id": false, "secret": false}, "onedrive": {"id": false, "secret": false}}}),
        "cloud:backend" | "cloud:connect" | "cloud:passphrase" | "cloud:disconnect" => json!({"error": "Google Drive and OneDrive sync are not part of the Rust build yet. Use your own server."}),
        "homenet:list" => json!({"servers": [], "active": ""}),
        "vault:status" => json!({"exists": false, "unlocked": false, "count": null, "autoLock": 15, "deviceMode": true, "mode": "device", "remember": true, "syncReady": false, "safeStorageAvailable": false}),
        "vault:setup" | "vault:unlock" | "vault:save" | "vault:join" | "vault:setMode" => json!({"error": "The password vault is not part of the Rust build yet."}),
        "pw:action" => json!(false),
        "import:detect" => json!([]),
        "wizard:status" => Value::Null,
        _ => return None,
    })
}

pub fn send(b: &B, ch: &str, a: &[Value], src: &str) -> bool {
    match ch {
        "tab:rename" => {
            if let Some(id) = a.get(0).and_then(|v| v.as_u64()) {
                let n: String = arg_str(a, 1).trim().chars().take(80).collect();
                with_tab(b, id as u32, |t| t.custom = if n.is_empty() { None } else { Some(n) });
                push(b);
                schedule_save(b);
            }
        }
        "tab:stack" => {
            if let (Some(x), Some(y)) = (a.get(0).and_then(|v| v.as_u64()), a.get(1).and_then(|v| v.as_u64())) {
                stack_tabs(b, x as u32, y as u32);
            }
        }
        "reader:toggle" => toggle_reader(b),
        "reader:found" => {
            if let Some(id) = src.strip_prefix("tab:").and_then(|n| n.parse::<u32>().ok()) {
                let ok = a.get(0).and_then(|v| v.as_bool()).unwrap_or(false);
                with_tab(b, id, |t| t.readable = ok);
                push(b);
            }
        }
        "reader:toggled" => {
            if let Some(id) = src.strip_prefix("tab:").and_then(|n| n.parse::<u32>().ok()) {
                let r = arg_str(a, 0);
                with_tab(b, id, |t| t.reader_on = r == "on");
                push(b);
            }
        }
        "translate:page" => {
            let u = b.borrow().tabs.iter().find(|t| t.id == b.borrow().active).map(|t| t.url.clone()).unwrap_or_default();
            if is_web(&u) {
                if let Some(v) = active_view(b) {
                    v.load_uri(&format!("https://translate.google.com/translate?sl=auto&tl=en&u={}", pct(&u)));
                }
            }
        }
        _ => return false,
    }
    true
}
