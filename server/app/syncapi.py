"""The endpoints the browser talks to. Same paths and shapes as the earlier single-token server."""
import json
import re

from .store import ApiError, now_ms

MAX_ITEMS = 5000


def _num(v, default=0):
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def _clip(s, n=1024):
    return str(s or '')[:n]


def _good_url(u):
    u = str(u or '').strip()
    return bool(re.match(r'^https?://', u, re.I)) and len(u) <= 8192


def _next_rev(c):
    c.execute("INSERT INTO meta(k, v) VALUES('rev', 1) ON CONFLICT(k) DO UPDATE SET v = v + 1")
    return c.execute("SELECT v FROM meta WHERE k='rev'").fetchone()[0]


def _cur_rev(c):
    r = c.execute("SELECT v FROM meta WHERE k='rev'").fetchone()
    return r[0] if r else 0


def _items(body):
    items = body.get('items') or []
    if not isinstance(items, list):
        raise ApiError(400, 'items must be a list')
    if len(items) > MAX_ITEMS:
        raise ApiError(413, 'too many items')
    return [i for i in items if isinstance(i, dict)]


def bookmarks_sync(c, body):
    since = _num(body.get('since'))
    for it in _items(body):
        if not _good_url(it.get('url')) or 'updated' not in it:
            continue
        c.execute(
            """INSERT INTO bookmarks(url, title, folder, added, updated, deleted, rev) VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(url) DO UPDATE SET title=excluded.title, folder=excluded.folder, added=excluded.added,
                 updated=excluded.updated, deleted=excluded.deleted, rev=excluded.rev
               WHERE excluded.updated > bookmarks.updated""",
            (str(it['url']).strip(), _clip(it.get('title')), _clip(it.get('folder'), 300), _num(it.get('added')),
             _num(it.get('updated')), 1 if it.get('deleted') else 0, _next_rev(c)))
    rows = c.execute('SELECT url, title, folder, added, updated, deleted FROM bookmarks WHERE rev > ?', (since,)).fetchall()
    return {'rev': _cur_rev(c), 'items': [
        {'url': r['url'], 'title': r['title'], 'folder': r['folder'] or '', 'added': r['added'],
         'updated': r['updated'], 'deleted': bool(r['deleted'])} for r in rows]}


def history_sync(c, body):
    since = _num(body.get('since'))
    for it in _items(body):
        if not _good_url(it.get('url')) or 'time' not in it:
            continue
        c.execute('INSERT OR IGNORE INTO history(url, time, title, rev) VALUES(?,?,?,?)',
                  (str(it['url']).strip(), _num(it.get('time')), _clip(it.get('title')), _next_rev(c)))
    rows = c.execute('SELECT url, title, time, rev FROM history WHERE rev > ? ORDER BY rev LIMIT ?', (since, MAX_ITEMS)).fetchall()
    rev = _cur_rev(c)
    if len(rows) == MAX_ITEMS:
        rev = rows[-1]['rev']  # more to come: only advance as far as we actually sent
    return {'rev': rev, 'items': [{'url': r['url'], 'title': r['title'], 'time': r['time']} for r in rows]}


def tabs_put(c, body):
    device = _clip(body.get('device'), 64).strip()
    if not device:
        raise ApiError(400, 'device is required')
    tabs = [{'url': str(t['url']).strip(), 'title': _clip(t.get('title'))}
            for t in (body.get('tabs') or [])[:200] if isinstance(t, dict) and _good_url(t.get('url'))]
    c.execute('INSERT INTO tabs(device, tabs, updated) VALUES(?,?,?) ON CONFLICT(device) DO UPDATE SET tabs=excluded.tabs, updated=excluded.updated',
              (device, json.dumps(tabs), now_ms()))
    return {'ok': True}


def tabs_get(c, exclude=''):
    rows = c.execute('SELECT device, tabs, updated FROM tabs WHERE device != ? ORDER BY updated DESC', (exclude,)).fetchall()
    return {'devices': [{'device': r['device'], 'updated': r['updated'], 'tabs': json.loads(r['tabs'])} for r in rows]}


def _versioned_get(c, table, col):
    r = c.execute('SELECT version, %s AS data FROM %s WHERE id = 1' % (col, table)).fetchone()
    return r


def vault_get(c):
    r = _versioned_get(c, 'vault', 'blob')
    if not r:
        return {'exists': False, 'version': 0, 'blob': None}
    return {'exists': True, 'version': r['version'], 'blob': json.loads(r['data'])}


def vault_put(c, body):
    blob, base = body.get('blob'), _num(body.get('base'))
    need = ('salt', 'iv', 'tag', 'data')
    if not isinstance(blob, dict) or not all(isinstance(blob.get(k), str) for k in need) or not isinstance(blob.get('kdf'), dict):
        raise ApiError(400, 'invalid vault')
    if len(blob['data']) > 8_000_000 or len(blob['salt']) > 256 or len(blob['iv']) > 64 or len(blob['tag']) > 64:
        raise ApiError(413, 'vault too large')
    r = c.execute('SELECT version FROM vault WHERE id = 1').fetchone()
    cur = r['version'] if r else 0
    if base != cur:
        raise ApiError(409, 'vault changed on the server')
    clean = {'v': _num(blob.get('v'), 1), 'kdf': blob['kdf'], **{k: blob[k] for k in need}}
    if blob.get('device') is True:
        clean['device'] = True  # a vault locked with a per-computer key, not a master password
    c.execute('INSERT INTO vault(id, version, blob, updated) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version, blob=excluded.blob, updated=excluded.updated',
              (cur + 1, json.dumps(clean), now_ms()))
    return {'version': cur + 1}


def wallpaper_get(c):
    r = c.execute('SELECT updated, data FROM wallpaper WHERE id = 1').fetchone()
    return {'updated': r['updated'] if r else 0, 'data': r['data'] if r else None}


def wallpaper_put(c, body):
    data, upd = body.get('data'), _num(body.get('updated'))
    if not isinstance(data, str) or not data or len(data) > 3_000_000:
        raise ApiError(413 if isinstance(data, str) and data else 400, 'invalid or too large wallpaper')
    r = c.execute('SELECT updated FROM wallpaper WHERE id = 1').fetchone()
    if r and r['updated'] >= upd:
        return {'updated': r['updated']}   # the server already has a newer or equal photo
    c.execute('INSERT INTO wallpaper(id, updated, data) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET updated=excluded.updated, data=excluded.data', (upd, data))
    return {'updated': upd}


def settings_get(c):
    r = _versioned_get(c, 'settings', 'doc')
    if not r:
        return {'version': 0, 'doc': None}
    return {'version': r['version'], 'doc': json.loads(r['data'])}


def settings_put(c, body):
    doc, base = body.get('doc'), _num(body.get('base'))
    if not isinstance(doc, dict):
        raise ApiError(400, 'invalid settings document')
    text = json.dumps(doc)
    if len(text) > 500_000:
        raise ApiError(413, 'settings document too large')
    r = c.execute('SELECT version FROM settings WHERE id = 1').fetchone()
    cur = r['version'] if r else 0
    if base != cur:
        raise ApiError(409, 'settings changed on the server')
    c.execute('INSERT INTO settings(id, version, doc, updated) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version, doc=excluded.doc, updated=excluded.updated',
              (cur + 1, text, now_ms()))
    return {'version': cur + 1}
