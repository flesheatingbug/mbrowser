//! mbrowser-rs: a small experimental browser in Rust (GTK 3 + WebKitGTK).
//! Tabs, address bar, back/forward/reload, load progress, a few shortcuts.
//! It uses the system WebKitGTK (install `webkit2gtk-4.1` on Arch/CachyOS).

use gtk::prelude::*;
use std::rc::Rc;
use webkit2gtk::{LoadEvent, WebView, WebViewExt};

const SEARCH: &str = "https://duckduckgo.com/?q=";
const HOME: &str = "https://duckduckgo.com";

struct App {
    window: gtk::Window,
    notebook: gtk::Notebook,
    entry: gtk::Entry,
    back: gtk::Button,
    forward: gtk::Button,
    progress: gtk::ProgressBar,
}

impl App {
    fn current(&self) -> Option<WebView> {
        let n = self.notebook.current_page()?;
        self.notebook.nth_page(Some(n))?.downcast::<WebView>().ok()
    }
    fn is_current(&self, v: &WebView) -> bool {
        self.current().map_or(false, |c| &c == v)
    }
    /// Refresh the toolbar and window title from the tab you are looking at.
    fn sync(&self) {
        match self.current() {
            Some(v) => {
                self.back.set_sensitive(v.can_go_back());
                self.forward.set_sensitive(v.can_go_forward());
                let uri = v.uri().map(|s| s.to_string()).unwrap_or_default();
                if !self.entry.has_focus() {
                    self.entry.set_text(&uri);
                }
                let title = v.title().map(|s| s.to_string()).unwrap_or_default();
                self.window.set_title(&if title.is_empty() { "mbrowser-rs".to_string() } else { format!("{title} - mbrowser-rs") });
            }
            None => self.window.set_title("mbrowser-rs"),
        }
    }
}

/// Turn what was typed into a URL: a full URL stays, "host.tld" gets https, anything else is a search.
fn to_url(text: &str) -> String {
    let t = text.trim();
    if t.contains("://") || t.starts_with("about:") {
        return t.to_string();
    }
    if !t.contains(' ') && (t.contains('.') || t.starts_with("localhost")) {
        return format!("https://{t}");
    }
    let mut q = String::new();
    for b in t.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => q.push(b as char),
            b' ' => q.push('+'),
            _ => q.push_str(&format!("%{b:02X}")),
        }
    }
    format!("{SEARCH}{q}")
}

fn new_tab(app: &Rc<App>, url: Option<&str>) -> WebView {
    let view = WebView::new();

    // tab label: title + close button
    let label = gtk::Label::new(Some("New tab"));
    label.set_max_width_chars(18);
    label.set_ellipsize(gtk::pango::EllipsizeMode::End);
    label.set_xalign(0.0);
    let close = gtk::Button::with_label("×");
    close.set_relief(gtk::ReliefStyle::None);
    let tab = gtk::Box::new(gtk::Orientation::Horizontal, 4);
    tab.pack_start(&label, true, true, 0);
    tab.pack_end(&close, false, false, 0);
    tab.show_all();

    {
        let app = app.clone();
        let view = view.clone();
        close.connect_clicked(move |_| close_view(&app, &view));
    }
    {
        let app = app.clone();
        let label = label.clone();
        view.connect_title_notify(move |v| {
            let t = v.title().map(|s| s.to_string()).unwrap_or_default();
            label.set_text(if t.is_empty() { "New tab" } else { &t });
            if app.is_current(v) {
                app.sync();
            }
        });
    }
    {
        let app = app.clone();
        view.connect_uri_notify(move |v| {
            if app.is_current(v) {
                app.sync();
            }
        });
    }
    {
        let app = app.clone();
        view.connect_estimated_load_progress_notify(move |v| {
            if app.is_current(v) {
                app.progress.set_fraction(v.estimated_load_progress());
            }
        });
    }
    {
        let app = app.clone();
        view.connect_load_changed(move |v, ev| {
            if !app.is_current(v) {
                return;
            }
            match ev {
                LoadEvent::Started => {
                    app.progress.set_fraction(0.05);
                    app.progress.show();
                }
                LoadEvent::Finished => app.progress.hide(),
                _ => {}
            }
            app.sync();
        });
    }
    {
        // links that ask for a new window (target=_blank, window.open) become tabs
        let app = app.clone();
        view.connect_create(move |_, _| new_tab(&app, None).upcast::<gtk::Widget>());
    }

    let idx = app.notebook.append_page(&view, Some(&tab));
    app.notebook.set_tab_reorderable(&view, true);
    view.show();
    app.notebook.set_current_page(Some(idx));
    if let Some(u) = url {
        view.load_uri(&to_url(u));
    }
    view
}

fn close_view(app: &Rc<App>, view: &WebView) {
    if let Some(n) = app.notebook.page_num(view) {
        app.notebook.remove_page(Some(n));
    }
    if app.notebook.n_pages() == 0 {
        gtk::main_quit();
    } else {
        app.sync();
    }
}

fn main() {
    gtk::init().expect("could not start GTK (is a display available?)");
    if let Some(s) = gtk::Settings::default() {
        s.set_gtk_application_prefer_dark_theme(true);
    }

    let window = gtk::Window::new(gtk::WindowType::Toplevel);
    window.set_default_size(1200, 800);
    window.set_title("mbrowser-rs");

    let back = gtk::Button::with_label("←");
    let forward = gtk::Button::with_label("→");
    let reload = gtk::Button::with_label("⟳");
    let newtab = gtk::Button::with_label("+");
    let entry = gtk::Entry::new();
    entry.set_placeholder_text(Some("Search or enter address"));
    let bar = gtk::Box::new(gtk::Orientation::Horizontal, 4);
    bar.set_border_width(4);
    bar.pack_start(&back, false, false, 0);
    bar.pack_start(&forward, false, false, 0);
    bar.pack_start(&reload, false, false, 0);
    bar.pack_start(&entry, true, true, 0);
    bar.pack_start(&newtab, false, false, 0);

    let progress = gtk::ProgressBar::new();
    progress.set_no_show_all(true);
    let notebook = gtk::Notebook::new();
    notebook.set_scrollable(true);

    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    column.pack_start(&bar, false, false, 0);
    column.pack_start(&progress, false, false, 0);
    column.pack_start(&notebook, true, true, 0);
    window.add(&column);

    let app = Rc::new(App { window: window.clone(), notebook: notebook.clone(), entry: entry.clone(), back: back.clone(), forward: forward.clone(), progress });

    {
        let app = app.clone();
        back.connect_clicked(move |_| { if let Some(v) = app.current() { v.go_back(); } });
    }
    {
        let app = app.clone();
        forward.connect_clicked(move |_| { if let Some(v) = app.current() { v.go_forward(); } });
    }
    {
        let app = app.clone();
        reload.connect_clicked(move |_| { if let Some(v) = app.current() { v.reload(); } });
    }
    {
        let app = app.clone();
        newtab.connect_clicked(move |_| { new_tab(&app, Some(HOME)); });
    }
    {
        let app = app.clone();
        entry.connect_activate(move |e| {
            if let Some(v) = app.current() {
                v.load_uri(&to_url(e.text().as_str()));
                v.grab_focus();
            }
        });
    }
    {
        let app = app.clone();
        notebook.connect_switch_page(move |_, _, _| {
            // the current page is only updated after this signal, so refresh on idle
            let app = app.clone();
            gtk::glib::idle_add_local_once(move || app.sync());
        });
    }
    {
        // Ctrl+T new tab, Ctrl+W close, Ctrl+L address bar, Ctrl+R reload
        let app = app.clone();
        window.connect_key_press_event(move |_, e| {
            if !e.state().contains(gtk::gdk::ModifierType::CONTROL_MASK) {
                return gtk::Inhibit(false);
            }
            match e.keyval().to_unicode() {
                Some('t') => { new_tab(&app, Some(HOME)); gtk::Inhibit(true) }
                Some('w') => { if let Some(v) = app.current() { close_view(&app, &v); } gtk::Inhibit(true) }
                Some('l') => { app.entry.grab_focus(); app.entry.select_region(0, -1); gtk::Inhibit(true) }
                Some('r') => { if let Some(v) = app.current() { v.reload(); } gtk::Inhibit(true) }
                _ => gtk::Inhibit(false),
            }
        });
    }
    window.connect_delete_event(|_, _| { gtk::main_quit(); gtk::Inhibit(false) });

    let start: Vec<String> = std::env::args().skip(1).filter(|a| !a.starts_with('-')).collect();
    if start.is_empty() {
        new_tab(&app, Some(HOME));
    } else {
        for u in &start {
            new_tab(&app, Some(u));
        }
    }
    window.show_all();
    progress_hide(&app);
    gtk::main();
}

fn progress_hide(app: &Rc<App>) {
    app.progress.hide();
}
