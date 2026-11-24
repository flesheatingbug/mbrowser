//! Sync with your own mbrowser server: bookmarks, history, open tabs and settings.
//! The network work runs on a thread; the results are merged on the GTK thread.

use super::*;
use std::sync::mpsc;

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new().timeout(Duration::from_secs(15)).build()
}

fn call(server: &str, token: &str, method: &str, path: &str, body: Option<&Value>, allow409: bool) -> Result<Value, String> {
    let url = format!("{}{}", server.trim_end_matches('/'), path);
    let req = agent().request(method, &url).set("authorization", &format!("Bearer {token}")).set("content-type", "application/json");
    let res = match body {
        Some(b) => req.send_string(&b.to_string()),
        None => req.call(),
    };
    match res {
        Ok(r) => {
            let t = r.into_string().unwrap_or_default();
            Ok(serde_json::from_str(&t).unwrap_or(Value::Null))
        }
        Err(ureq::Error::Status(409, _)) if allow409 => Ok(json!({"conflict": true})),
        Err(ureq::Error::Status(401, r)) => {
            let t = r.into_string().unwrap_or_default();
            Err(if t.contains("\"detail\"") || t.contains("\"error\"") {
                "the server rejected the token (check Settings > Sync)".to_string()
            } else {
                "a web server or proxy in front of the sync server answered 401. If you use Nginx Proxy Manager, remove the Access List from that proxy host.".to_string()
            })
        }
        Err(ureq::Error::Status(code, r)) => {
            let t: String = r.into_string().unwrap_or_default().chars().take(200).collect();
            Err(format!("{path} → HTTP {code} {t}").trim().to_string())
        }
        Err(e) => Err(format!("cannot reach {url}: {e}")),
    }
}

fn device_name(s: &Value) -> String {
    let d = s_str(s, "sync.device");
    if !d.is_empty() {
        return d;
    }
    std::fs::read_to_string("/etc/hostname").map(|h| h.trim().to_string()).ok().filter(|h| !h.is_empty()).unwrap_or_else(|| "linux".into())
}

fn ready(s: &Value) -> bool {
    !s_str(s, "sync.server").is_empty() && !s_str(s, "sync.token").is_empty()
}

// ---------------------------------------------------------------- state on disk

pub fn init(b: &B) {
    let st = read_json(&b.borrow().data.join("syncstate.json")).unwrap_or(json!({}));
    {
        let mut br = b.borrow_mut();
        br.x.b_rev = st["bRev"].as_i64().unwrap_or(0);
        br.x.h_rev = st["hRev"].as_i64().unwrap_or(0);
        br.x.h_pushed = st["hPushed"].as_f64().unwrap_or(0.0);
        br.x.sync_status = if ready(&br.settings) { "Not synced yet".into() } else { "Sync not configured (Settings → Sync)".into() };
    }
    // a first sync shortly after start, then every few minutes
    let b1 = b.clone();
    glib::timeout_add_local_once(Duration::from_secs(4), move || start(&b1, None));
    let b2 = b.clone();
    glib::timeout_add_seconds_local(300, move || {
        start(&b2, None);
        glib::ControlFlow::Continue
    });
}

fn save_state(br: &Br) {
    write_json(&br.data.join("syncstate.json"), &json!({"bRev": br.x.b_rev, "hRev": br.x.h_rev, "hPushed": br.x.h_pushed}));
}

pub fn status(b: &B) -> String {
    b.borrow().x.sync_status.clone()
}

pub fn sync_soon(b: &B) {
    if b.borrow().x.sync_timer || !ready(&b.borrow().settings) {
        return;
    }
    b.borrow_mut().x.sync_timer = true;
    let b = b.clone();
    glib::timeout_add_local_once(Duration::from_secs(3), move || {
        b.borrow_mut().x.sync_timer = false;
        start(&b, None);
    });
}

// ---------------------------------------------------------------- the sync itself

struct Job {
    server: String,
    token: String,
    device: String,
    items: Vec<Value>,
    b_rev: i64,
    pending: Vec<Value>,
    h_rev: i64,
    tabs: Vec<Value>,
    settings: bool,
    groups: Vec<(String, u64, Value)>,
}

struct Outcome {
    b_items: Vec<Value>,
    b_rev: i64,
    h_items: Vec<Value>,
    h_rev: i64,
    pushed_to: f64,
    remote_tabs: Value,
    applied: Vec<(String, Value, u64)>,
    note: String,
}

fn run(j: Job) -> Result<Outcome, String> {
    let r = call(&j.server, &j.token, "POST", "/api/bookmarks", Some(&json!({"since": j.b_rev, "items": j.items})), false)?;
    let b_items = r["items"].as_array().cloned().unwrap_or_default();
    let b_rev = r["rev"].as_i64().unwrap_or(j.b_rev);

    let r = call(&j.server, &j.token, "POST", "/api/history", Some(&json!({"since": j.h_rev, "items": j.pending})), false)?;
    let h_items = r["items"].as_array().cloned().unwrap_or_default();
    let h_rev = r["rev"].as_i64().unwrap_or(j.h_rev);
    let pushed_to = j.pending.iter().filter_map(|h| h["time"].as_f64()).fold(0.0, f64::max);

    call(&j.server, &j.token, "PUT", "/api/tabs", Some(&json!({"device": j.device, "tabs": j.tabs})), false)?;
    let remote = call(&j.server, &j.token, "GET", &format!("/api/tabs?exclude={}", pct(&j.device).replace('+', "%20")), None, false)?;
    let remote_tabs = remote["devices"].clone();

    let mut applied = vec![];
    let mut note = String::new();
    if j.settings {
        match sync_settings(&j) {
            Ok((a, n)) => {
                applied = a;
                note = n;
            }
            Err(e) => note = if e.contains("HTTP 404") { " (update the sync server to sync settings)".into() } else { format!(" (settings: {e})") },
        }
    }
    Ok(Outcome { b_items, b_rev, h_items, h_rev, pushed_to, remote_tabs, applied, note })
}

fn sync_settings(j: &Job) -> Result<(Vec<(String, Value, u64)>, String), String> {
    let remote = call(&j.server, &j.token, "GET", "/api/settings", None, false)?;
    let empty = json!({});
    let rgroups = remote["doc"]["groups"].as_object().cloned().unwrap_or_default();
    let _ = empty;
    let mut merged = Map::new();
    let mut applied = vec![];
    let mut push = remote["doc"].is_null();
    // groups this app does not manage travel through untouched
    for (k, v) in &rgroups {
        if !data::SYNC_KEYS.contains(&k.as_str()) {
            merged.insert(k.clone(), v.clone());
        }
    }
    for (key, updated, value) in &j.groups {
        let local = json!({"updated": updated, "value": value});
        let r = rgroups.get(key);
        let valid = r.map_or(false, |r| r["updated"].is_number() && r["value"].is_object());
        if !valid {
            merged.insert(key.clone(), local);
            push = true;
            continue;
        }
        let r = r.unwrap();
        let ru = r["updated"].as_u64().unwrap_or(0);
        if ru > *updated {
            applied.push((key.clone(), r["value"].clone(), ru));
            merged.insert(key.clone(), r.clone());
        } else if ru < *updated {
            merged.insert(key.clone(), local);
            push = true;
        } else {
            merged.insert(key.clone(), r.clone());
        }
    }
    if push {
        let res = call(&j.server, &j.token, "PUT", "/api/settings", Some(&json!({"base": remote["version"], "doc": {"groups": merged}})), true)?;
        if res["conflict"].as_bool().unwrap_or(false) {
            return Ok((applied, " (the server changed, trying again soon)".into()));
        }
    }
    let n = if !applied.is_empty() {
        format!(" Settings updated from another device ({})", applied.iter().map(|a| a.0.clone()).collect::<Vec<_>>().join(", "))
    } else {
        String::new()
    };
    Ok((applied, n))
}

/// Start a sync. `reply` (source view, request id) gets the status text when it ends.
pub fn start(b: &B, reply: Option<(String, u64)>) {
    let (job, busy) = {
        let br = b.borrow();
        (ready(&br.settings), br.x.syncing)
    };
    let answer = |b: &B, reply: &Option<(String, u64)>| {
        if let Some((src, id)) = reply {
            if let Some(v) = view_for(b, src) {
                v.run_javascript(&format!("window.__res({id},{});", json!(status(b))), None::<&gtk::gio::Cancellable>, |_| {});
            }
        }
    };
    if busy {
        answer(b, &reply);
        return;
    }
    if !job {
        b.borrow_mut().x.sync_status = "Sync not configured (Settings → Sync)".into();
        answer(b, &reply);
        return;
    }
    // what goes up
    let groups: Vec<(String, u64, Value)> = if s_bool(&b.borrow().settings, "sync.settings", true) {
        data::SYNC_KEYS.iter().map(|k| (k.to_string(), data::updated_of(b, k), data::pick(k, &b.borrow().settings[*k]))).collect()
    } else {
        vec![]
    };
    let j = {
        let mut br = b.borrow_mut();
        let now = now_ms();
        let mut stamped = false;
        for x in br.bookmarks.iter_mut() {
            if x["recycleBin"].as_bool().unwrap_or(false) && !x["deleted"].as_bool().unwrap_or(false) && !x["binSent"].as_bool().unwrap_or(false) {
                x["updated"] = json!(now);
                x["binSent"] = json!(true);
                stamped = true;
            }
        }
        let _ = stamped;
        let items: Vec<Value> = br.bookmarks.iter().filter(|x| is_web(x["url"].as_str().unwrap_or("")) && x["url"].as_str().map_or(0, |u| u.len()) <= 8000).map(|x| {
            let mut o = json!({
                "url": x["url"], "title": x["title"].as_str().unwrap_or("").chars().take(900).collect::<String>(),
                "folder": x["folder"].as_str().unwrap_or("").chars().take(300).collect::<String>(),
                "added": x["added"].as_f64().unwrap_or(0.0).round(), "updated": x["updated"].as_f64().unwrap_or(0.0).round(),
                "deleted": x["deleted"].as_bool().unwrap_or(false) || x["recycleBin"].as_bool().unwrap_or(false) && false,
            });
            if x["recycleBin"].as_bool().unwrap_or(false) {
                o["recycleBin"] = json!(true);
                o["binTime"] = x["binTime"].clone();
            }
            o
        }).collect();
        let pushed = br.x.h_pushed;
        let skip = br.history.len().saturating_sub(5000);
        let pending: Vec<Value> = br.history.iter().skip(skip).filter(|h| h["time"].as_f64().unwrap_or(0.0) > pushed && is_web(h["url"].as_str().unwrap_or(""))).map(|h| json!({"url": h["url"], "title": h["title"].as_str().unwrap_or("").chars().take(900).collect::<String>(), "time": h["time"].as_f64().unwrap_or(0.0).round()})).collect();
        let tabs: Vec<Value> = br.tabs.iter().filter(|t| !t.private && is_web(&t.url)).map(|t| json!({"title": t.title, "url": t.url})).collect();
        br.x.syncing = true;
        br.x.sync_status = "Syncing…".into();
        Job {
            server: s_str(&br.settings, "sync.server"), token: s_str(&br.settings, "sync.token"), device: device_name(&br.settings),
            items, b_rev: br.x.b_rev, pending, h_rev: br.x.h_rev, tabs, settings: !groups.is_empty(), groups,
        }
    };
    let (tx, rx) = mpsc::channel::<Result<Outcome, String>>();
    std::thread::spawn(move || {
        let _ = tx.send(run(j));
    });
    let b = b.clone();
    glib::timeout_add_local(Duration::from_millis(200), move || match rx.try_recv() {
        Err(mpsc::TryRecvError::Empty) => glib::ControlFlow::Continue,
        Err(mpsc::TryRecvError::Disconnected) => {
            b.borrow_mut().x.syncing = false;
            b.borrow_mut().x.sync_status = "Sync error: the worker stopped".into();
            answer(&b, &reply);
            glib::ControlFlow::Break
        }
        Ok(res) => {
            finish(&b, res);
            answer(&b, &reply);
            glib::ControlFlow::Break
        }
    });
}

fn finish(b: &B, res: Result<Outcome, String>) {
    b.borrow_mut().x.syncing = false;
    match res {
        Err(e) => b.borrow_mut().x.sync_status = format!("Sync error: {e}"),
        Ok(o) => {
            let mut dials = false;
            {
                let mut br = b.borrow_mut();
                for r in o.b_items {
                    let url = r["url"].as_str().unwrap_or("").to_string();
                    if !is_web(&url) {
                        continue;
                    }
                    let mut r = r;
                    r["folder"] = json!(data::norm_folder(r["folder"].as_str().unwrap_or("")));
                    match br.bookmarks.iter().position(|x| x["url"].as_str() == Some(&url)) {
                        None => br.bookmarks.push(r),
                        Some(i) => {
                            if r["updated"].as_f64().unwrap_or(0.0) > br.bookmarks[i]["updated"].as_f64().unwrap_or(0.0) {
                                br.bookmarks[i] = r;
                            }
                        }
                    }
                }
                br.x.b_rev = o.b_rev;
                let seen: std::collections::HashSet<String> = br.history.iter().map(|h| format!("{}|{}", h["url"], h["time"])).collect();
                for r in o.h_items {
                    if is_web(r["url"].as_str().unwrap_or("")) && !seen.contains(&format!("{}|{}", r["url"], r["time"])) {
                        br.history.push(r);
                    }
                }
                br.history.sort_by(|x, y| x["time"].as_f64().partial_cmp(&y["time"].as_f64()).unwrap_or(std::cmp::Ordering::Equal));
                let n = br.history.len();
                if n > 5000 {
                    br.history.drain(0..n - 5000);
                }
                br.x.h_rev = o.h_rev;
                if o.pushed_to > 0.0 {
                    br.x.h_pushed = br.x.h_pushed.max(o.pushed_to);
                }
                br.x.remote_tabs = o.remote_tabs;
            }
            for (k, v, u) in &o.applied {
                data::apply_group(b, k, v, *u);
                if k == "startPage" {
                    dials = true;
                }
            }
            if dials {
                data::migrate_dials(b);
            }
            {
                let mut br = b.borrow_mut();
                let t = std::process::Command::new("date").arg("+%H:%M:%S").output().ok().and_then(|o| String::from_utf8(o.stdout).ok()).unwrap_or_default();
                br.x.sync_status = format!("Last synced {}{}", t.trim(), o.note);
                save_state(&br);
            }
            data::save_files(&b.borrow());
            schedule_save(b);
            push(b);
            let views: Vec<WebView> = b.borrow().tabs.iter().map(|t| t.view.clone()).collect();
            for v in views {
                emit(&v, "bookmarks:changed", json!([]));
            }
        }
    }
}

// ---------------------------------------------------------------- calls from the settings page

/// Run `work` on a thread and answer the page that asked when it is done. `after` runs on the GTK thread first.
pub fn job<F, A>(b: &B, src: String, id: u64, work: F, after: A)
where
    F: FnOnce() -> Value + Send + 'static,
    A: FnOnce(&B, &Value) + 'static,
{
    let (tx, rx) = mpsc::channel::<Value>();
    std::thread::spawn(move || {
        let _ = tx.send(work());
    });
    let b = b.clone();
    let mut after = Some(after);
    glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
        Err(mpsc::TryRecvError::Empty) => glib::ControlFlow::Continue,
        Err(_) => glib::ControlFlow::Break,
        Ok(v) => {
            if let Some(a) = after.take() {
                a(&b, &v);
            }
            if let Some(view) = view_for(&b, &src) {
                view.run_javascript(&format!("window.__res({id},{});", v), None::<&gtk::gio::Cancellable>, |_| {});
            }
            glib::ControlFlow::Break
        }
    });
}

/// Channels whose answer comes later. Returns true when handled.
pub fn async_invoke(b: &B, ch: &str, a: &[Value], src: &str, id: u64) -> bool {
    let src = src.to_string();
    match ch {
        "sync:now" => {
            start(b, Some((src, id)));
            true
        }
        "sync:test" => {
            let server = s_str(&b.borrow().settings, "sync.server");
            let token = s_str(&b.borrow().settings, "sync.token");
            let account = b.borrow().settings["sync"]["account"].clone();
            job(b, src, id, move || test(&server, &token, &account), |_, _| {});
            true
        }
        "sync:login" => {
            let o = a.get(0).cloned().unwrap_or(json!({}));
            let server = s_str(&b.borrow().settings, "sync.server");
            let device = device_name(&b.borrow().settings);
            let user = o["username"].as_str().unwrap_or("").to_string();
            job(b, src, id, move || login(&server, &device, &o), move |b, v| {
                if v["ok"].as_bool().unwrap_or(false) {
                    if let Some(t) = v["token"].as_str() {
                        data::apply_setting(b, "sync.token", json!(t));
                        data::apply_setting(b, "sync.account", json!({"user": user, "profile": v["profile"]["name"]}));
                        sync_soon(b);
                    }
                }
            });
            true
        }
        _ => false,
    }
}

fn login(server: &str, device: &str, o: &Value) -> Value {
    if server.is_empty() {
        return json!({"error": "Enter the server address first."});
    }
    let url = format!("{}/api/auth/device-login", server.trim_end_matches('/'));
    let body = json!({"username": o["username"], "password": o["password"], "totp": o["totp"], "profile": o["profile"], "device": device});
    let res = agent().post(&url).set("content-type", "application/json").send_string(&body.to_string());
    let (code, text) = match res {
        Ok(r) => (r.status(), r.into_string().unwrap_or_default()),
        Err(ureq::Error::Status(c, r)) => (c, r.into_string().unwrap_or_default()),
        Err(e) => return json!({"error": format!("Cannot reach the server: {e}")}),
    };
    let data: Value = serde_json::from_str(&text).unwrap_or(json!({}));
    if code == 404 {
        return json!({"error": "This server is an older version without account sign-in. Update the server, or use an API key."});
    }
    if !(200..300).contains(&code) {
        return json!({"error": data["error"].as_str().map(|s| s.to_string()).unwrap_or(format!("Sign-in failed (HTTP {code})")), "needTotp": data["needTotp"].as_bool().unwrap_or(false)});
    }
    if data["needProfile"].as_bool().unwrap_or(false) {
        return json!({"needProfile": true, "profiles": data["profiles"]});
    }
    json!({"ok": true, "profile": data["profile"], "token": data["token"]})
}

fn test(server: &str, token: &str, account: &Value) -> Value {
    let mut out: Vec<String> = vec![];
    if server.is_empty() {
        return json!(["PROBLEM: no server address is set."]);
    }
    let base = server.trim_end_matches('/');
    match agent().get(&format!("{base}/health")).call() {
        Ok(r) => {
            let t = r.into_string().unwrap_or_default();
            if t.contains("\"ok\"") {
                out.push(format!("OK: the sync server answers at {base}"));
            } else {
                out.push(format!("PROBLEM: {base} answers, but it does not look like the sync server (is the address or port right?)."));
                return json!(out);
            }
        }
        Err(ureq::Error::Status(c, _)) => return json!([format!("PROBLEM: {base}/health answered HTTP {c}.")]),
        Err(e) => return json!([format!("PROBLEM: cannot reach {base}: {e}")]),
    }
    if let Ok(r) = agent().get(&format!("{base}/api/info")).call() {
        let j: Value = serde_json::from_str(&r.into_string().unwrap_or_default()).unwrap_or(json!({}));
        out.push(format!("Server: {} {}", j["name"].as_str().unwrap_or("sync server"), j["version"].as_str().unwrap_or("")));
    }
    if token.is_empty() {
        out.push("PROBLEM: not signed in. Enter an API key, or sign in with your account.".into());
        return json!(out);
    }
    let who = match account["user"].as_str() {
        Some(u) if !u.is_empty() => format!("Signed in as {u}"),
        _ => "API key".into(),
    };
    out.push(format!("{who}: {} characters.", token.len()));
    match call(server, token, "GET", "/api/vault", None, false) {
        Ok(_) => out.push("OK: the key is accepted.".into()),
        Err(e) => out.push(format!("PROBLEM: {e}")),
    }
    json!(out)
}
