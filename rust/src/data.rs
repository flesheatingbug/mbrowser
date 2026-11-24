//! Bookmarks (with folders, recycle bin and speed dials), history (with its bin), notes and settings.
//! Same rules as main.js so a profile can sync with the Electron and Android versions.

use super::*;
use std::collections::BTreeSet;

pub const SEP: &str = " / ";
pub const SD_ROOT: &str = "Speed Dials";
pub const SYNC_KEYS: &[&str] = &["general", "appearance", "startPage", "dialLayout", "tabs", "search", "privacy", "passwords", "panel", "keyboard", "pages", "downloads", "profileMeta"];

pub fn excluded(top: &str) -> &'static [&'static str] {
    match top {
        "startPage" => &["dialLayout"],
        "appearance" => &["nativeTitleBar"],
        "panel" => &["open", "last"],
        "privacy" => &["certExceptions"],
        "downloads" => &["directory"],
        "general" => &["beta", "homeNetwork"],
        _ => &[],
    }
}

pub fn norm_folder(f: &str) -> String {
    let s = f.split('/').map(|x| x.trim()).filter(|x| !x.is_empty()).collect::<Vec<_>>().join(SEP);
    s.chars().take(300).collect()
}

pub fn in_folder(folder: &str, path: &str) -> bool {
    folder == path || folder.starts_with(&format!("{path}{SEP}"))
}

fn flag(v: &Value, k: &str) -> bool {
    v[k].as_bool().unwrap_or(false)
}

pub fn live(b: &Value) -> bool {
    !flag(b, "deleted") && !flag(b, "recycleBin")
}

fn text(v: &Value, k: &str) -> String {
    v[k].as_str().unwrap_or("").to_string()
}

pub fn all_folder_paths(br: &Br) -> Vec<String> {
    let mut set = BTreeSet::new();
    let mut add = |p: &str| {
        let parts: Vec<&str> = p.split(SEP).collect();
        for i in 1..=parts.len() {
            set.insert(parts[..i].join(SEP));
        }
    };
    for f in &br.x.folders {
        if !f.is_empty() {
            add(f);
        }
    }
    for b in br.bookmarks.iter().filter(|b| live(b)) {
        let f = text(b, "folder");
        if !f.is_empty() {
            add(&f);
        }
    }
    set.into_iter().collect()
}

pub fn save_files(br: &Br) {
    write_json(&br.data.join("bookmarks.json"), &Value::Array(br.bookmarks.clone()));
    write_json(&br.data.join("bookmark-folders.json"), &json!(br.x.folders));
    write_json(&br.data.join("notes.json"), &Value::Array(br.x.notes.clone()));
    write_json(&br.data.join("history-bin.json"), &Value::Array(br.x.hbin.clone()));
}

pub fn load_files(br: &mut Br) {
    br.x.folders = read_json(&br.data.join("bookmark-folders.json")).and_then(|v| v.as_array().cloned()).unwrap_or_default().iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect();
    br.x.notes = read_json(&br.data.join("notes.json")).and_then(|v| v.as_array().cloned()).unwrap_or_default();
    br.x.hbin = read_json(&br.data.join("history-bin.json")).and_then(|v| v.as_array().cloned()).unwrap_or_default();
}

/// Bookmarks changed: save, refresh the interface, tell open bookmark pages, sync soon.
pub fn touch(b: &B) {
    {
        let br = b.borrow();
        save_files(&br);
    }
    push(b);
    let views: Vec<WebView> = b.borrow().tabs.iter().map(|t| t.view.clone()).collect();
    for v in views {
        emit(&v, "bookmarks:changed", json!([]));
    }
    super::sync::sync_soon(b);
}

// ---------------------------------------------------------------- settings

pub fn apply_setting(b: &B, path: &str, value: Value) {
    let top = path.split('.').next().unwrap_or("").to_string();
    {
        let mut br = b.borrow_mut();
        set_path(&mut br.settings, path, value);
        if SYNC_KEYS.contains(&top.as_str()) {
            let rest = path.split('.').nth(1).unwrap_or("");
            if !excluded(&top).contains(&rest) {
                set_path(&mut br.settings, &format!("_updated.{top}"), json!(now_ms()));
            }
        }
    }
    schedule_save(b);
    push(b);
    super::sync::sync_soon(b);
    super::extra::settings_changed(b, path);
}

pub fn pick(key: &str, v: &Value) -> Value {
    let mut v = v.clone();
    if let Some(o) = v.as_object_mut() {
        for k in excluded(key) {
            o.remove(*k);
        }
    }
    v
}

/// when a group was last changed here (groups changed before this was tracked count as "now" on first look)
pub fn updated_of(b: &B, key: &str) -> u64 {
    let mut br = b.borrow_mut();
    let have = br.settings["_updated"][key].as_u64().unwrap_or(0);
    if have > 0 {
        return have;
    }
    let differs = pick(key, &br.settings[key]) != pick(key, &br.x.defaults[key]);
    if differs {
        let n = now_ms();
        set_path(&mut br.settings, &format!("_updated.{key}"), json!(n));
        return n;
    }
    0
}

pub fn apply_group(b: &B, key: &str, value: &Value, updated: u64) {
    let mut br = b.borrow_mut();
    let mut base = br.x.defaults[key].clone();
    let mut over = value.clone();
    if let (Some(o), Some(cur)) = (over.as_object_mut(), br.settings[key].as_object()) {
        for k in excluded(key) {
            if let Some(v) = cur.get(*k) {
                o.insert(k.to_string(), v.clone());
            }
        }
    }
    merge(&mut base, &over);
    br.settings[key] = base;
    set_path(&mut br.settings, &format!("_updated.{key}"), json!(updated));
}

// ---------------------------------------------------------------- speed dials (they live in the bookmarks)

fn clean_name(s: &str, fb: &str) -> String {
    let mut out = String::new();
    // /\s*\/\s*/g -> "-"
    let mut chars = s.chars().peekable();
    let mut pending_ws = String::new();
    while let Some(c) = chars.next() {
        if c.is_whitespace() {
            pending_ws.push(c);
        } else if c == '/' {
            pending_ws.clear();
            while chars.peek().map_or(false, |x| x.is_whitespace()) {
                chars.next();
            }
            out.push('-');
        } else {
            out.push_str(&pending_ws);
            pending_ws.clear();
            out.push(c);
        }
    }
    out.push_str(&pending_ws);
    let t: String = out.trim().chars().take(60).collect();
    if t.is_empty() { fb.to_string() } else { t }
}

fn dial_groups(br: &Br) -> Vec<Value> {
    br.settings["startPage"]["groups"].as_array().cloned().unwrap_or_default()
}

fn group_path(groups: &[Value], gi: usize) -> String {
    format!("{SD_ROOT}{SEP}{}", clean_name(groups[gi]["name"].as_str().unwrap_or(""), &format!("Group {}", gi + 1)))
}

fn cur_layout(br: &Br) -> Value {
    let own = &br.settings["dialLayout"]["layout"];
    if own.as_object().map_or(false, |o| !o.is_empty()) {
        return own.clone();
    }
    br.settings["startPage"]["dialLayout"].clone()
}

fn build_dials(br: &Br, folders: &[String], paths: &[String], layout: &Value, path: &str, adopt: bool) -> Vec<Value> {
    let prefix = format!("{path}{SEP}");
    let mut out: Vec<Value> = vec![];
    for f in folders.iter().filter(|f| f.starts_with(&prefix) && !f[prefix.len()..].contains(SEP) && !(adopt && paths.contains(f))) {
        out.push(json!({"type": "folder", "title": &f[prefix.len()..], "items": build_dials(br, folders, paths, layout, f, false)}));
    }
    let mut bms: Vec<&Value> = br.bookmarks.iter().filter(|x| live(x) && x["folder"].as_str() == Some(path)).collect();
    bms.sort_by(|a, c| a["added"].as_f64().unwrap_or(0.0).partial_cmp(&c["added"].as_f64().unwrap_or(0.0)).unwrap_or(std::cmp::Ordering::Equal));
    for bm in bms {
        let url = text(bm, "url");
        let title = text(bm, "title");
        out.push(json!({"title": if title.is_empty() { host_of(&url) } else { title }, "url": url}));
    }
    // the arranged order first (folders and tiles mixed), anything new after it
    let pos: Vec<String> = layout[path].as_array().map(|a| a.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect()).unwrap_or_default();
    let key_of = |d: &Value| if d["type"] == "folder" { format!("f:{}", text(d, "title")) } else { format!("u:{}", text(d, "url")) };
    let mut idx: Vec<(usize, Value)> = out.into_iter().enumerate().collect();
    idx.sort_by_key(|(i, d)| (pos.iter().position(|k| *k == key_of(d)).unwrap_or(1_000_000), *i));
    idx.into_iter().map(|(_, d)| d).collect()
}

pub fn read_dials(br: &Br) -> Vec<Value> {
    let groups = dial_groups(br);
    let paths: Vec<String> = (0..groups.len()).map(|gi| group_path(&groups, gi)).collect();
    let folders = all_folder_paths(br);
    let layout = cur_layout(br);
    groups
        .iter()
        .enumerate()
        .map(|(gi, g)| {
            let mut dials = build_dials(br, &folders, &paths, &layout, &paths[gi], false);
            if gi == 0 {
                dials.extend(build_dials(br, &folders, &paths, &layout, SD_ROOT, true));
            }
            let mut g = g.clone();
            g["dials"] = Value::Array(dials);
            g
        })
        .collect()
}

pub fn dial_urls(br: &Br) -> Vec<String> {
    fn walk(a: &[Value], out: &mut Vec<String>) {
        for d in a {
            if d["type"] == "folder" {
                walk(d["items"].as_array().map(|v| v.as_slice()).unwrap_or(&[]), out);
            } else if let Some(u) = d["url"].as_str() {
                out.push(u.to_string());
            }
        }
    }
    let mut out = vec![];
    for g in read_dials(br) {
        walk(g["dials"].as_array().map(|v| v.as_slice()).unwrap_or(&[]), &mut out);
    }
    out
}

fn strip_dials(groups: &[Value]) -> Value {
    Value::Array(groups.iter().map(|g| { let mut c = g.clone(); if let Some(o) = c.as_object_mut() { o.remove("dials"); } c }).collect())
}

/// Make the bookmarks match this dial tree (groups: [{id,name,dials:[…]}]).
pub fn write_dials(b: &B, groups: &[Value]) {
    let now = now_ms();
    {
        let mut br = b.borrow_mut();
        let mut want: Vec<(String, String, String)> = vec![]; // url, title, folder
        let mut folders: BTreeSet<String> = BTreeSet::new();
        let mut layout = Map::new();
        fn walk(items: &[Value], base: &str, want: &mut Vec<(String, String, String)>, folders: &mut BTreeSet<String>, layout: &mut Map<String, Value>) {
            let mut keys = vec![];
            for d in items {
                if d["type"] == "folder" {
                    let nm = clean_name(d["title"].as_str().unwrap_or(""), "Folder");
                    let p = format!("{base}{SEP}{nm}");
                    folders.insert(p.clone());
                    keys.push(json!(format!("f:{nm}")));
                    walk(d["items"].as_array().map(|v| v.as_slice()).unwrap_or(&[]), &p, want, folders, layout);
                } else if let Some(u) = d["url"].as_str().filter(|u| is_web(u)) {
                    if !want.iter().any(|w| w.0 == u) {
                        want.push((u.to_string(), d["title"].as_str().unwrap_or("").chars().take(900).collect(), base.to_string()));
                        keys.push(json!(format!("u:{u}")));
                    }
                }
            }
            layout.insert(base.to_string(), Value::Array(keys));
        }
        for gi in 0..groups.len() {
            let p = group_path(groups, gi);
            folders.insert(p.clone());
            walk(groups[gi]["dials"].as_array().map(|v| v.as_slice()).unwrap_or(&[]), &p, &mut want, &mut folders, &mut layout);
        }
        for (url, title, folder) in &want {
            if let Some(x) = br.bookmarks.iter_mut().find(|x| x["url"].as_str() == Some(url)) {
                let revive = !live(x);
                if revive || x["title"].as_str() != Some(title) || x["folder"].as_str() != Some(folder) {
                    x["deleted"] = json!(false);
                    if let Some(o) = x.as_object_mut() { o.remove("recycleBin"); o.remove("binTime"); }
                    if !title.is_empty() { x["title"] = json!(title); }
                    x["folder"] = json!(folder);
                    x["updated"] = json!(now);
                }
            } else {
                br.bookmarks.push(json!({"url": url, "title": title, "folder": folder, "added": now, "updated": now, "deleted": false}));
            }
        }
        if Value::Object(layout.clone()) != cur_layout(&br) {
            set_path(&mut br.settings, "dialLayout.layout", Value::Object(layout));
            set_path(&mut br.settings, "_updated.dialLayout", json!(now));
        }
        // tiles that are no longer in the tree go to the recycle bin
        for x in br.bookmarks.iter_mut() {
            let f = x["folder"].as_str().unwrap_or("").to_string();
            if live(x) && !f.is_empty() && in_folder(&f, SD_ROOT) && !want.iter().any(|w| Some(w.0.as_str()) == x["url"].as_str()) {
                x["recycleBin"] = json!(true);
                x["binTime"] = json!(now);
                x["updated"] = json!(now);
            }
        }
        // empty dial folders
        let keep: Vec<String> = br.x.folders.iter().filter(|f| !in_folder(f, SD_ROOT) || *f == SD_ROOT || folders.contains(*f) || br.bookmarks.iter().any(|x| live(x) && x["folder"].as_str().map_or(false, |bf| in_folder(bf, f)))).cloned().collect();
        br.x.folders = keep;
        for f in folders {
            if !br.x.folders.contains(&f) {
                br.x.folders.push(f);
            }
        }
    }
    touch(b);
}

pub fn add_speed_dial(b: &B, url: &str, title: &str) -> bool {
    if !is_web(url) {
        return false;
    }
    let key = host_of(url) + "/" + url.splitn(4, '/').nth(3).unwrap_or("");
    let _ = key;
    if dial_urls(&b.borrow()).iter().any(|u| u == url) {
        return false;
    }
    let mut groups = read_dials(&b.borrow());
    if groups.is_empty() {
        return false;
    }
    let active = b.borrow().settings["startPage"]["activeGroup"].as_str().unwrap_or("").to_string();
    let gi = groups.iter().position(|g| g["id"].as_str() == Some(&active)).unwrap_or(0);
    let t: String = title.trim().chars().take(80).collect();
    if let Some(a) = groups[gi]["dials"].as_array_mut() {
        a.push(json!({"title": if t.is_empty() { host_of(url) } else { t }, "url": url}));
    }
    write_dials(b, &groups);
    true
}

/// Old versions kept the tiles inside the settings: move any into the bookmarks, once.
pub fn migrate_dials(b: &B) {
    let groups = dial_groups(&b.borrow());
    if !groups.iter().any(|g| g["dials"].is_array()) {
        return;
    }
    let cur = read_dials(&b.borrow());
    let have: std::collections::HashSet<String> = dial_urls(&b.borrow()).into_iter().collect();
    let mut merged = cur.clone();
    fn merge_in(from: &[Value], into: &mut Vec<Value>, have: &std::collections::HashSet<String>) {
        for d in from {
            if d["type"] == "folder" {
                let title = d["title"].as_str().unwrap_or("");
                let pos = into.iter().position(|x| x["type"] == "folder" && x["title"].as_str() == Some(title));
                let idx = match pos { Some(i) => i, None => { into.push(json!({"type": "folder", "title": title, "items": []})); into.len() - 1 } };
                let mut items = into[idx]["items"].as_array().cloned().unwrap_or_default();
                merge_in(d["items"].as_array().map(|v| v.as_slice()).unwrap_or(&[]), &mut items, have);
                into[idx]["items"] = Value::Array(items);
            } else if let Some(u) = d["url"].as_str() {
                if !have.contains(u) && is_web(u) {
                    into.push(d.clone());
                }
            }
        }
    }
    for (gi, g) in groups.iter().enumerate() {
        let target = if gi < merged.len() { gi } else { 0 };
        if merged.is_empty() {
            break;
        }
        let mut dials = merged[target]["dials"].as_array().cloned().unwrap_or_default();
        merge_in(g["dials"].as_array().map(|v| v.as_slice()).unwrap_or(&[]), &mut dials, &have);
        merged[target]["dials"] = Value::Array(dials);
    }
    write_dials(b, &merged);
    let stripped = strip_dials(&groups);
    set_path(&mut b.borrow_mut().settings, "startPage.groups", stripped);
    schedule_save(b);
}

// ---------------------------------------------------------------- IPC handlers

fn urls_arg(a: &[Value], i: usize) -> Vec<String> {
    a.get(i).and_then(|v| v.as_array()).map(|l| l.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect()).unwrap_or_default()
}

fn bookmarks_public(br: &Br, pred: impl Fn(&Value) -> bool) -> Vec<Value> {
    br.bookmarks.iter().filter(|x| pred(x) && is_web(x["url"].as_str().unwrap_or(""))).map(|x| json!({"url": x["url"], "title": x["title"].as_str().unwrap_or(""), "folder": x["folder"].as_str().unwrap_or(""), "added": x["added"].as_u64().unwrap_or(0), "binTime": x["binTime"].as_u64().unwrap_or(0)})).collect()
}

pub fn invoke(b: &B, ch: &str, a: &[Value]) -> Option<Value> {
    let now = now_ms();
    Some(match ch {
        "settings:get" => {
            let mut s = b.borrow().settings.clone();
            let groups = read_dials(&b.borrow());
            if !groups.is_empty() {
                set_path(&mut s, "startPage.groups", Value::Array(groups));
            }
            s
        }
        "settings:set" => {
            let p = arg_str(a, 0);
            let mut v = a.get(1).cloned().unwrap_or(Value::Null);
            if p == "startPage" && v["groups"].is_array() {
                let g = v["groups"].as_array().cloned().unwrap_or_default();
                write_dials(b, &g);
                v["groups"] = strip_dials(&g);
                if let Some(o) = v.as_object_mut() { o.remove("dialLayout"); }
            } else if p == "startPage.groups" && v.is_array() {
                let g = v.as_array().cloned().unwrap_or_default();
                write_dials(b, &g);
                v = strip_dials(&g);
            }
            apply_setting(b, &p, v);
            json!(true)
        }
        "bookmarks:all" => {
            let br = b.borrow();
            json!({"bookmarks": bookmarks_public(&br, |x| live(x)), "folders": all_folder_paths(&br)})
        }
        "bookmarks:save" => {
            let o = a.get(0).cloned().unwrap_or(json!({}));
            let target = o["newUrl"].as_str().filter(|s| !s.is_empty()).or(o["url"].as_str()).unwrap_or("").trim().to_string();
            let fin = if is_scheme_url(&target) { target } else { format!("https://{target}") };
            if !is_web(&fin) || fin.len() > 8000 {
                return Some(json!({"error": "Enter a web address (http or https)."}));
            }
            let folder = norm_folder(o["folder"].as_str().unwrap_or(""));
            let old = o["url"].as_str().unwrap_or("").to_string();
            let title: String = o["title"].as_str().unwrap_or("").chars().take(900).collect();
            {
                let mut br = b.borrow_mut();
                if !old.is_empty() && old != fin {
                    for x in br.bookmarks.iter_mut().filter(|x| x["url"].as_str() == Some(&old) && !flag(x, "deleted")) {
                        x["deleted"] = json!(true);
                        x["updated"] = json!(now);
                    }
                }
                if let Some(x) = br.bookmarks.iter_mut().find(|x| x["url"].as_str() == Some(&fin)) {
                    x["deleted"] = json!(false);
                    if let Some(m) = x.as_object_mut() { m.remove("recycleBin"); m.remove("binTime"); }
                    x["title"] = json!(if title.is_empty() { fin.clone() } else { title });
                    x["folder"] = json!(folder);
                    x["updated"] = json!(now);
                } else {
                    br.bookmarks.push(json!({"url": fin, "title": if title.is_empty() { fin.clone() } else { title }, "folder": folder, "added": now, "updated": now, "deleted": false}));
                }
                if !folder.is_empty() && !br.x.folders.contains(&folder) {
                    br.x.folders.push(folder);
                }
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmark:popup-save" => {
            let (url, title, folder) = (arg_str(a, 0), arg_str(a, 1), norm_folder(&arg_str(a, 2)));
            {
                let mut br = b.borrow_mut();
                if let Some(x) = br.bookmarks.iter_mut().find(|x| x["url"].as_str() == Some(&url)) {
                    x["deleted"] = json!(false);
                    if let Some(m) = x.as_object_mut() { m.remove("recycleBin"); m.remove("binTime"); }
                    if !title.is_empty() { x["title"] = json!(title); }
                    x["folder"] = json!(folder);
                    x["updated"] = json!(now);
                } else {
                    br.bookmarks.push(json!({"url": url, "title": if title.is_empty() { url.clone() } else { title }, "folder": folder, "added": now, "updated": now, "deleted": false}));
                }
                if !folder.is_empty() && !br.x.folders.contains(&folder) {
                    br.x.folders.push(folder);
                }
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmark:popup-speeddial" => {
            let added = add_speed_dial(b, &arg_str(a, 0), &arg_str(a, 1));
            json!({"ok": true, "added": added})
        }
        "bookmarks:move" => {
            let urls = urls_arg(a, 0);
            let f = norm_folder(&arg_str(a, 1));
            {
                let mut br = b.borrow_mut();
                for x in br.bookmarks.iter_mut().filter(|x| !flag(x, "deleted") && urls.iter().any(|u| x["url"].as_str() == Some(u))) {
                    x["folder"] = json!(f);
                    x["updated"] = json!(now);
                }
                if !f.is_empty() && !br.x.folders.contains(&f) {
                    br.x.folders.push(f);
                }
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmarks:remove" | "bookmark:remove" => {
            let urls = if a.get(0).map_or(false, |v| v.is_array()) { urls_arg(a, 0) } else { vec![arg_str(a, 0)] };
            for x in b.borrow_mut().bookmarks.iter_mut().filter(|x| live(x) && urls.iter().any(|u| x["url"].as_str() == Some(u))) {
                x["recycleBin"] = json!(true);
                x["binTime"] = json!(now);
                x["updated"] = json!(now);
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmarks:recycleBin" => {
            let br = b.borrow();
            json!({"bookmarks": bookmarks_public(&br, |x| flag(x, "recycleBin") && !flag(x, "deleted"))})
        }
        "bookmarks:restoreFromBin" => {
            let urls = urls_arg(a, 0);
            for x in b.borrow_mut().bookmarks.iter_mut().filter(|x| flag(x, "recycleBin") && urls.iter().any(|u| x["url"].as_str() == Some(u))) {
                if let Some(m) = x.as_object_mut() { m.remove("recycleBin"); m.remove("binTime"); }
                x["updated"] = json!(now);
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmarks:emptyBin" => {
            let urls = if a.get(0).map_or(false, |v| v.is_array()) { Some(urls_arg(a, 0)) } else { None };
            for x in b.borrow_mut().bookmarks.iter_mut().filter(|x| flag(x, "recycleBin")) {
                if let Some(u) = &urls {
                    if !u.iter().any(|y| x["url"].as_str() == Some(y)) { continue; }
                }
                x["deleted"] = json!(true);
                x["updated"] = json!(now);
                if let Some(m) = x.as_object_mut() { m.remove("recycleBin"); m.remove("binTime"); }
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmarks:folderAdd" => {
            let f = norm_folder(&arg_str(a, 0));
            if f.is_empty() {
                return Some(json!({"error": "Enter a folder name."}));
            }
            if !b.borrow().x.folders.contains(&f) {
                b.borrow_mut().x.folders.push(f.clone());
            }
            touch(b);
            json!({"ok": true, "path": f})
        }
        "bookmarks:folderRename" => {
            let (from, to) = (norm_folder(&arg_str(a, 0)), norm_folder(&arg_str(a, 1)));
            if from.is_empty() || to.is_empty() {
                return Some(json!({"error": "Enter a folder name."}));
            }
            if from == to {
                return Some(json!({"ok": true}));
            }
            if to.starts_with(&format!("{from}{SEP}")) {
                return Some(json!({"error": "A folder cannot be moved into itself."}));
            }
            let swap = |f: &str| if in_folder(f, &from) { format!("{to}{}", &f[from.len()..]) } else { f.to_string() };
            {
                let mut br = b.borrow_mut();
                for x in br.bookmarks.iter_mut().filter(|x| !flag(x, "deleted")) {
                    let f = x["folder"].as_str().unwrap_or("").to_string();
                    if !f.is_empty() && in_folder(&f, &from) {
                        x["folder"] = json!(swap(&f));
                        x["updated"] = json!(now);
                    }
                }
                let mut nf: Vec<String> = br.x.folders.iter().map(|f| swap(f)).collect();
                nf.push(to);
                nf.sort();
                nf.dedup();
                br.x.folders = nf;
            }
            touch(b);
            json!({"ok": true})
        }
        "bookmarks:folderDelete" => {
            let path = norm_folder(&arg_str(a, 0));
            let mode = arg_str(a, 1);
            if path.is_empty() {
                return Some(json!({"error": "Choose a folder."}));
            }
            let parent = path.rfind(SEP).map(|i| path[..i].to_string()).unwrap_or_default();
            {
                let mut br = b.borrow_mut();
                for x in br.bookmarks.iter_mut().filter(|x| !flag(x, "deleted")) {
                    let f = x["folder"].as_str().unwrap_or("").to_string();
                    if f.is_empty() || !in_folder(&f, &path) { continue; }
                    if mode == "delete" {
                        x["recycleBin"] = json!(true);
                        x["binTime"] = json!(now);
                    } else {
                        let rel = if f == path { String::new() } else { f[path.len() + SEP.len()..].to_string() };
                        let parts: Vec<String> = [parent.clone(), rel].into_iter().filter(|s| !s.is_empty()).collect();
                        x["folder"] = json!(parts.join(SEP));
                    }
                    x["updated"] = json!(now);
                }
                br.x.folders.retain(|f| !in_folder(f, &path));
            }
            touch(b);
            json!({"ok": true})
        }

        // ---- history
        "history:query" => {
            let o = a.get(0).cloned().unwrap_or(json!({}));
            let terms: Vec<String> = o["q"].as_str().unwrap_or("").to_lowercase().split_whitespace().map(|s| s.to_string()).collect();
            let from = o["from"].as_f64().unwrap_or(0.0);
            let to = o["to"].as_f64().filter(|t| *t > 0.0).unwrap_or(f64::MAX);
            let limit = o["limit"].as_u64().filter(|l| *l > 0).unwrap_or(300).clamp(1, 20000) as usize;
            let br = b.borrow();
            let items: Vec<Value> = br.history.iter().rev().filter(|h| {
                let t = h["time"].as_f64().unwrap_or(0.0);
                t >= from && t <= to && matches(&terms, &[h["title"].as_str().unwrap_or(""), h["url"].as_str().unwrap_or("")])
            }).take(limit).map(|h| json!({"url": h["url"], "title": h["title"], "time": h["time"]})).collect();
            json!({"items": items, "total": br.history.len()})
        }
        "history:delete" => {
            let sp = a.get(0).cloned().unwrap_or(json!({}));
            let before = b.borrow().history.len();
            {
                let mut br = b.borrow_mut();
                let hist = std::mem::take(&mut br.history);
                let (keep, gone): (Vec<Value>, Vec<Value>) = if let Some(urls) = sp["urls"].as_array() {
                    let set: Vec<&str> = urls.iter().filter_map(|u| u.as_str()).collect();
                    hist.into_iter().partition(|h| !set.contains(&h["url"].as_str().unwrap_or("")))
                } else if sp["from"].is_number() || sp["to"].is_number() {
                    let from = sp["from"].as_f64().unwrap_or(0.0);
                    let to = sp["to"].as_f64().unwrap_or(f64::MAX);
                    hist.into_iter().partition(|h| { let t = h["time"].as_f64().unwrap_or(0.0); t < from || t > to })
                } else {
                    (hist, vec![])
                };
                br.history = keep;
                for h in gone {
                    br.x.hbin.push(json!({"url": h["url"], "title": h["title"], "time": h["time"], "binTime": now}));
                }
                let n = br.x.hbin.len();
                if n > 5000 { br.x.hbin.drain(0..n - 5000); }
            }
            schedule_save(b);
            save_files(&b.borrow());
            json!(before - b.borrow().history.len())
        }
        "history:binList" => {
            let mut l = b.borrow().x.hbin.clone();
            l.sort_by(|x, y| y["binTime"].as_u64().cmp(&x["binTime"].as_u64()).then(y["time"].as_u64().cmp(&x["time"].as_u64())));
            l.truncate(1000);
            Value::Array(l)
        }
        "history:restore" => {
            let sp = a.get(0).cloned().unwrap_or(json!({}));
            let all = sp["all"].as_bool().unwrap_or(false);
            let urls: Vec<String> = sp["urls"].as_array().map(|l| l.iter().filter_map(|u| u.as_str().map(|s| s.to_string())).collect()).unwrap_or_default();
            let mut n = 0;
            {
                let mut br = b.borrow_mut();
                let bin = std::mem::take(&mut br.x.hbin);
                let (back, rest): (Vec<Value>, Vec<Value>) = bin.into_iter().partition(|h| all || urls.iter().any(|u| h["url"].as_str() == Some(u)));
                br.x.hbin = rest;
                let seen: std::collections::HashSet<String> = br.history.iter().map(|h| format!("{}|{}", h["url"], h["time"])).collect();
                for h in back {
                    if !seen.contains(&format!("{}|{}", h["url"], h["time"])) {
                        br.history.push(json!({"url": h["url"], "title": h["title"], "time": h["time"]}));
                        n += 1;
                    }
                }
                br.history.sort_by(|x, y| x["time"].as_f64().partial_cmp(&y["time"].as_f64()).unwrap_or(std::cmp::Ordering::Equal));
            }
            schedule_save(b);
            save_files(&b.borrow());
            json!(n)
        }
        "history:emptyBin" => {
            b.borrow_mut().x.hbin.clear();
            save_files(&b.borrow());
            json!(true)
        }

        // ---- notes
        "notes:list" => {
            let mut l: Vec<Value> = b.borrow().x.notes.iter().filter(|n| !flag(n, "recycleBin")).cloned().collect();
            l.sort_by(|x, y| y["updated"].as_u64().cmp(&x["updated"].as_u64()));
            Value::Array(l)
        }
        "notes:save" => {
            let n = a.get(0).cloned().unwrap_or(json!({}));
            if !n.is_object() {
                return Some(Value::Null);
            }
            let title: String = n["title"].as_str().unwrap_or("").chars().take(200).collect();
            let text: String = n["text"].as_str().unwrap_or("").chars().take(50000).collect();
            let id = n["id"].as_str().map(|s| s.to_string());
            let mut br = b.borrow_mut();
            let idx = id.as_ref().and_then(|i| br.x.notes.iter().position(|x| x["id"].as_str() == Some(i)));
            let i = match idx {
                Some(i) => i,
                None => {
                    let nid = format!("n{:x}{:04x}", now, now % 65536);
                    br.x.notes.push(json!({"id": nid, "title": "", "text": "", "updated": 0}));
                    br.x.notes.len() - 1
                }
            };
            br.x.notes[i]["title"] = json!(title);
            br.x.notes[i]["text"] = json!(text);
            br.x.notes[i]["updated"] = json!(now);
            let rec = br.x.notes[i].clone();
            save_files(&br);
            rec
        }
        "notes:delete" => {
            let id = arg_str(a, 0);
            if let Some(n) = b.borrow_mut().x.notes.iter_mut().find(|x| x["id"].as_str() == Some(&id)) {
                n["recycleBin"] = json!(true);
                n["binTime"] = json!(now);
            }
            save_files(&b.borrow());
            json!(true)
        }
        "notes:recycleBin" => Value::Array(b.borrow().x.notes.iter().filter(|n| flag(n, "recycleBin")).cloned().collect()),
        "notes:restoreFromBin" => {
            let id = arg_str(a, 0);
            if let Some(n) = b.borrow_mut().x.notes.iter_mut().find(|x| x["id"].as_str() == Some(&id)) {
                if let Some(m) = n.as_object_mut() { m.remove("recycleBin"); m.remove("binTime"); }
            }
            save_files(&b.borrow());
            json!(true)
        }
        "notes:emptyBin" => {
            let ids: Option<Vec<String>> = a.get(0).and_then(|v| v.as_array()).map(|l| l.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect());
            {
                let mut br = b.borrow_mut();
                match ids {
                    Some(ids) => br.x.notes.retain(|n| !ids.iter().any(|i| n["id"].as_str() == Some(i))),
                    None => br.x.notes.retain(|n| !flag(n, "recycleBin")),
                }
            }
            save_files(&b.borrow());
            json!(true)
        }
        _ => return None,
    })
}
