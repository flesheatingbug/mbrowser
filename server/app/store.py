"""Storage: one admin database (users, profiles, keys, sessions) plus one small database per sync profile."""
import json
import os
import re
import shutil
import sqlite3
import threading
import time
from contextlib import contextmanager

from . import security


class ApiError(Exception):
    def __init__(self, status, message, **extra):
        super().__init__(message)
        self.status, self.message, self.extra = status, message, extra


ADMIN_SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE, pw_salt BLOB NOT NULL, pw_hash BLOB NOT NULL,
  is_admin INTEGER NOT NULL DEFAULT 0, totp_secret TEXT, totp_enabled INTEGER NOT NULL DEFAULT 0,
  totp_step INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, disabled INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS recovery(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, code_hash TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS profiles(id INTEGER PRIMARY KEY, owner_id INTEGER, name TEXT NOT NULL, created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS tokens(
  id INTEGER PRIMARY KEY, profile_id INTEGER NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
  token TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created INTEGER NOT NULL, last_used INTEGER, last_device TEXT);
CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, csrf TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
"""

PROFILE_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v INTEGER);
CREATE TABLE IF NOT EXISTS bookmarks(url TEXT PRIMARY KEY, title TEXT, folder TEXT DEFAULT '', added INTEGER, updated INTEGER, deleted INTEGER, rev INTEGER);
CREATE TABLE IF NOT EXISTS history(url TEXT, time INTEGER, title TEXT, rev INTEGER, PRIMARY KEY(url, time));
CREATE TABLE IF NOT EXISTS tabs(device TEXT PRIMARY KEY, tabs TEXT, updated INTEGER);
CREATE TABLE IF NOT EXISTS settings(id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER, doc TEXT, updated INTEGER);
CREATE TABLE IF NOT EXISTS wallpaper(id INTEGER PRIMARY KEY CHECK (id = 1), updated INTEGER, data TEXT);
CREATE TABLE IF NOT EXISTS vault(id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER, blob TEXT, updated INTEGER);
CREATE INDEX IF NOT EXISTS bookmarks_rev ON bookmarks(rev);
CREATE INDEX IF NOT EXISTS history_rev ON history(rev);
"""

USERNAME_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.@+-]{1,39}$')


def now_ms():
    return int(time.time() * 1000)


class Store:
    def __init__(self, config_dir):
        self.dir = config_dir
        self.pdir = os.path.join(config_dir, 'profiles')
        os.makedirs(self.pdir, exist_ok=True)
        self.admin_path = os.path.join(config_dir, 'admin.db')
        self._ready = set()
        self._lock = threading.Lock()
        with self.adb() as c:
            c.execute('PRAGMA journal_mode=WAL')
            c.executescript(ADMIN_SCHEMA)
        self.migrate_legacy()

    # ------------------------------------------------------------ connections
    @contextmanager
    def adb(self):
        c = sqlite3.connect(self.admin_path, timeout=15)
        c.row_factory = sqlite3.Row
        try:
            yield c
            c.commit()
        finally:
            c.close()

    def profile_path(self, pid):
        return os.path.join(self.pdir, '%d.db' % int(pid))

    @contextmanager
    def pdb(self, pid):
        path = self.profile_path(pid)
        c = sqlite3.connect(path, timeout=15)
        c.row_factory = sqlite3.Row
        try:
            if path not in self._ready:
                with self._lock:
                    c.execute('PRAGMA journal_mode=WAL')
                    c.executescript(PROFILE_SCHEMA)
                    try:
                        c.execute("ALTER TABLE bookmarks ADD COLUMN folder TEXT DEFAULT ''")
                    except sqlite3.OperationalError:
                        pass  # already there
                    self._ready.add(path)
            yield c
            c.commit()
        finally:
            c.close()

    # ------------------------------------------------------------ legacy single-token servers
    def migrate_legacy(self):
        """An older version of this server kept sync.db and a single token file in the same folder.
        They become the profile "Default", and the old token keeps working as an API key."""
        old_db = os.path.join(self.dir, 'sync.db')
        with self.adb() as c:
            if c.execute("SELECT v FROM meta WHERE k='legacy_migrated'").fetchone():
                return
            if c.execute('SELECT COUNT(*) FROM profiles').fetchone()[0] or not os.path.exists(old_db):
                c.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('legacy_migrated','0')")
                return
            c.execute("INSERT INTO profiles(id, owner_id, name, created) VALUES(1, NULL, 'Default', ?)", (now_ms(),))
            src = sqlite3.connect(old_db)
            dst = sqlite3.connect(self.profile_path(1))
            src.backup(dst)
            src.close()
            dst.close()
            tok_file = os.path.join(self.dir, 'token')
            try:
                tok = open(tok_file).read().strip()
            except OSError:
                tok = ''
            if tok:
                c.execute('INSERT INTO tokens(profile_id,name,kind,token,token_hash,created) VALUES(1,?,?,?,?,?)',
                          ('Legacy token (from the old server)', 'legacy', tok, security.sha256(tok), now_ms()))
            c.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('legacy_migrated','1')")

    # ------------------------------------------------------------ users
    @staticmethod
    def _d(row):
        return dict(row) if row is not None else None

    def user_count(self):
        with self.adb() as c:
            return c.execute('SELECT COUNT(*) FROM users').fetchone()[0]

    def create_user(self, username, password, admin=False):
        username = (username or '').strip()
        if not USERNAME_RE.match(username):
            raise ApiError(400, 'The user name must be 2-40 characters: letters, digits and . _ @ + -')
        if len(password or '') < 8:
            raise ApiError(400, 'The password must be at least 8 characters.')
        salt, h = security.hash_password(password)
        with self.adb() as c:
            try:
                cur = c.execute('INSERT INTO users(username,pw_salt,pw_hash,is_admin,created) VALUES(?,?,?,?,?)',
                                (username, salt, h, 1 if admin else 0, now_ms()))
            except sqlite3.IntegrityError:
                raise ApiError(409, 'That user name is taken.')
            uid = cur.lastrowid
            if admin or self.user_count_conn(c) == 1:
                c.execute('UPDATE profiles SET owner_id=? WHERE owner_id IS NULL', (uid,))  # migrated data gets an owner
            return uid

    @staticmethod
    def user_count_conn(c):
        return c.execute('SELECT COUNT(*) FROM users').fetchone()[0]

    def user_by_name(self, name):
        with self.adb() as c:
            return self._d(c.execute('SELECT * FROM users WHERE username=?', (name or '',)).fetchone())

    def user_by_id(self, uid):
        with self.adb() as c:
            return self._d(c.execute('SELECT * FROM users WHERE id=?', (uid,)).fetchone())

    def list_users(self):
        with self.adb() as c:
            rows = c.execute('''SELECT u.id, u.username, u.is_admin, u.totp_enabled, u.created, u.disabled,
                (SELECT COUNT(*) FROM profiles p WHERE p.owner_id=u.id) AS profiles FROM users u ORDER BY u.username''').fetchall()
            return [dict(r) for r in rows]

    def set_password(self, uid, password):
        if len(password or '') < 8:
            raise ApiError(400, 'The password must be at least 8 characters.')
        salt, h = security.hash_password(password)
        with self.adb() as c:
            c.execute('UPDATE users SET pw_salt=?, pw_hash=? WHERE id=?', (salt, h, uid))
            c.execute('DELETE FROM sessions WHERE user_id=?', (uid,))  # sign out everywhere

    def set_flags(self, uid, **kw):
        cols = {'disabled', 'is_admin', 'totp_secret', 'totp_enabled', 'totp_step'}
        with self.adb() as c:
            for k, v in kw.items():
                if k in cols:
                    c.execute('UPDATE users SET %s=? WHERE id=?' % k, (v, uid))

    def delete_user(self, uid):
        pids = [p['id'] for p in self.list_profiles(uid)]
        for pid in pids:
            self.delete_profile(pid)
        with self.adb() as c:
            c.execute('DELETE FROM sessions WHERE user_id=?', (uid,))
            c.execute('DELETE FROM recovery WHERE user_id=?', (uid,))
            c.execute('DELETE FROM users WHERE id=?', (uid,))

    def admin_count(self):
        with self.adb() as c:
            return c.execute('SELECT COUNT(*) FROM users WHERE is_admin=1 AND disabled=0').fetchone()[0]

    # ------------------------------------------------------------ recovery codes
    def replace_recovery(self, uid, codes):
        with self.adb() as c:
            c.execute('DELETE FROM recovery WHERE user_id=?', (uid,))
            for code in codes:
                c.execute('INSERT INTO recovery(user_id, code_hash) VALUES(?,?)', (uid, security.sha256(security.normalize_recovery(code))))

    def use_recovery(self, uid, code):
        h = security.sha256(security.normalize_recovery(code))
        with self.adb() as c:
            row = c.execute('SELECT id FROM recovery WHERE user_id=? AND code_hash=? AND used=0', (uid, h)).fetchone()
            if not row:
                return False
            c.execute('UPDATE recovery SET used=1 WHERE id=?', (row['id'],))
            return True

    def recovery_left(self, uid):
        with self.adb() as c:
            return c.execute('SELECT COUNT(*) FROM recovery WHERE user_id=? AND used=0', (uid,)).fetchone()[0]

    # ------------------------------------------------------------ sessions
    def create_session(self, uid, days):
        sid = security.new_session_id()
        csrf = security.new_session_id()
        t = int(time.time())
        with self.adb() as c:
            c.execute('DELETE FROM sessions WHERE expires < ?', (t,))
            c.execute('INSERT INTO sessions(id,user_id,csrf,created,expires) VALUES(?,?,?,?,?)',
                      (security.sha256(sid), uid, csrf, t, t + days * 86400))
        return sid, csrf

    def session(self, sid, days):
        if not sid:
            return None
        t = int(time.time())
        with self.adb() as c:
            row = c.execute('''SELECT s.id AS sid, s.csrf, s.expires, u.* FROM sessions s JOIN users u ON u.id = s.user_id
                               WHERE s.id=? AND s.expires>? AND u.disabled=0''', (security.sha256(sid), t)).fetchone()
            if not row:
                return None
            if row['expires'] - t < (days - 1) * 86400:  # keep active sessions alive
                c.execute('UPDATE sessions SET expires=? WHERE id=?', (t + days * 86400, row['sid']))
            return dict(row)

    def end_session(self, sid):
        with self.adb() as c:
            c.execute('DELETE FROM sessions WHERE id=?', (security.sha256(sid),))

    def end_other_sessions(self, uid, keep_sid):
        with self.adb() as c:
            c.execute('DELETE FROM sessions WHERE user_id=? AND id<>?', (uid, security.sha256(keep_sid)))

    # ------------------------------------------------------------ profiles
    def create_profile(self, owner_id, name):
        name = re.sub(r'\s+', ' ', (name or '')).strip()[:60]
        if not name:
            raise ApiError(400, 'Enter a name for the profile.')
        with self.adb() as c:
            if c.execute('SELECT 1 FROM profiles WHERE owner_id IS ? AND lower(name)=lower(?)', (owner_id, name)).fetchone():
                raise ApiError(409, 'You already have a profile with that name.')
            cur = c.execute('INSERT INTO profiles(owner_id,name,created) VALUES(?,?,?)', (owner_id, name, now_ms()))
            return cur.lastrowid

    def list_profiles(self, owner_id):
        with self.adb() as c:
            return [dict(r) for r in c.execute('SELECT * FROM profiles WHERE owner_id=? ORDER BY id', (owner_id,)).fetchall()]

    def get_profile(self, pid):
        with self.adb() as c:
            return self._d(c.execute('SELECT * FROM profiles WHERE id=?', (pid,)).fetchone())

    def rename_profile(self, pid, name):
        name = re.sub(r'\s+', ' ', (name or '')).strip()[:60]
        if not name:
            raise ApiError(400, 'Enter a name for the profile.')
        p = self.get_profile(pid)
        with self.adb() as c:
            if c.execute('SELECT 1 FROM profiles WHERE owner_id IS ? AND lower(name)=lower(?) AND id<>?', (p['owner_id'], name, pid)).fetchone():
                raise ApiError(409, 'You already have a profile with that name.')
            c.execute('UPDATE profiles SET name=? WHERE id=?', (name, pid))

    def delete_profile(self, pid):
        with self.adb() as c:
            c.execute('DELETE FROM tokens WHERE profile_id=?', (pid,))
            c.execute('DELETE FROM profiles WHERE id=?', (pid,))
        path = self.profile_path(pid)
        for suffix in ('', '-wal', '-shm'):
            try:
                os.remove(path + suffix)
            except OSError:
                pass
        self._ready.discard(path)

    # ------------------------------------------------------------ keys / tokens
    def create_token(self, pid, name, kind='api', token=None):
        token = token or security.new_api_key()
        name = re.sub(r'\s+', ' ', (name or '')).strip()[:60] or 'API key'
        with self.adb() as c:
            cur = c.execute('INSERT INTO tokens(profile_id,name,kind,token,token_hash,created) VALUES(?,?,?,?,?,?)',
                            (pid, name, kind, token, security.sha256(token), now_ms()))
            return cur.lastrowid, token

    def replace_device_token(self, pid, name):
        with self.adb() as c:
            c.execute("DELETE FROM tokens WHERE profile_id=? AND kind='device' AND name=?", (pid, name))
        return self.create_token(pid, name, 'device')

    def replace_token(self, pid, name, kind):
        with self.adb() as c:
            c.execute("DELETE FROM tokens WHERE profile_id=? AND kind=? AND name=?", (pid, kind, name))
        return self.create_token(pid, name, kind)

    def list_tokens(self, pid):
        with self.adb() as c:
            return [dict(r) for r in c.execute('SELECT * FROM tokens WHERE profile_id=? ORDER BY id', (pid,)).fetchall()]

    def token_row(self, tid):
        with self.adb() as c:
            return self._d(c.execute('SELECT * FROM tokens WHERE id=?', (tid,)).fetchone())

    def delete_token(self, tid):
        with self.adb() as c:
            c.execute('DELETE FROM tokens WHERE id=?', (tid,))

    def find_token(self, token):
        if not token:
            return None
        with self.adb() as c:
            return self._d(c.execute('SELECT * FROM tokens WHERE token_hash=?', (security.sha256(token),)).fetchone())

    def touch_token(self, row, device):
        t = now_ms()
        if row.get('last_used') and t - row['last_used'] < 60000 and (not device or device == row.get('last_device')):
            return
        with self.adb() as c:
            c.execute('UPDATE tokens SET last_used=?, last_device=? WHERE id=?', (t, device or row.get('last_device'), row['id']))

    # ------------------------------------------------------------ statistics for the web interface
    def profile_stats(self, pid):
        out = {'bookmarks': 0, 'history': 0, 'devices': 0, 'vault': None, 'settings': None, 'lastSync': None}
        with self.pdb(pid) as c:
            out['bookmarks'] = c.execute('SELECT COUNT(*) FROM bookmarks WHERE deleted=0').fetchone()[0]
            out['history'] = c.execute('SELECT COUNT(*) FROM history').fetchone()[0]
            out['devices'] = c.execute('SELECT COUNT(*) FROM tabs').fetchone()[0]
            v = c.execute('SELECT version, length(blob) AS size, updated FROM vault WHERE id=1').fetchone()
            if v:
                out['vault'] = {'version': v['version'], 'bytes': v['size'], 'updated': v['updated']}
            s = c.execute('SELECT version, doc, updated FROM settings WHERE id=1').fetchone()
            if s:
                try:
                    groups = sorted((json.loads(s['doc']).get('groups') or {}).keys())
                except ValueError:
                    groups = []
                out['settings'] = {'version': s['version'], 'updated': s['updated'], 'groups': groups}
            times = [x for x in (v['updated'] if v else None, s['updated'] if s else None) if x]
            t = c.execute('SELECT MAX(updated) FROM tabs').fetchone()[0]
            if t:
                times.append(t)
        with self.adb() as c:
            lu = c.execute('SELECT MAX(last_used) FROM tokens WHERE profile_id=?', (pid,)).fetchone()[0]
        if lu:
            times.append(lu)
        out['lastSync'] = max(times) if times else None
        return out


# ---------------------------------------------------------------- whole-server backup (appended to Store below)
import io
import tempfile
import zipfile


def _store_backup_zip(self):
    """A zip with a consistent copy of every database (taken with SQLite's own backup, so it is safe while the server runs)."""
    tmp = tempfile.mkdtemp(prefix='mbbackup-')
    try:
        names = [('admin.db', self.admin_path)]
        for f in sorted(os.listdir(self.pdir)):
            if re.match(r'^\d+\.db$', f):
                names.append(('profiles/' + f, os.path.join(self.pdir, f)))
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('manifest.json', json.dumps({'app': 'mbrowser-sync', 'created': now_ms(), 'files': [n for n, _ in names]}))
            for i, (arc, path) in enumerate(names):
                out = os.path.join(tmp, '%d.db' % i)
                src = sqlite3.connect(path, timeout=15)
                dst = sqlite3.connect(out)
                try:
                    src.backup(dst)
                finally:
                    dst.close()
                    src.close()
                z.write(out, arc)
        return buf.getvalue()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _store_restore_zip(self, data):
    """Replaces every database with the ones in the zip. The current data is saved to backups/ first. Returns the number of files restored."""
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise ApiError(400, 'That is not a backup file.')
    names = [n for n in z.namelist() if n != 'manifest.json']
    ok = lambda n: n == 'admin.db' or re.match(r'^profiles/\d+\.db$', n)
    if 'admin.db' not in names or not all(ok(n) for n in names):
        raise ApiError(400, 'That is not an mbrowser server backup.')
    tmp = tempfile.mkdtemp(prefix='mbrestore-')
    try:
        staged = {}
        for i, n in enumerate(names):
            p = os.path.join(tmp, '%d.db' % i)
            with open(p, 'wb') as f:
                f.write(z.read(n))
            try:
                c = sqlite3.connect(p)
                good = c.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
                if n == 'admin.db':
                    c.execute('SELECT COUNT(*) FROM users')
                c.close()
            except sqlite3.Error:
                good = False
            if not good:
                raise ApiError(400, 'The backup file is damaged (%s).' % n)
            staged[n] = p
        bdir = os.path.join(self.dir, 'backups')
        os.makedirs(bdir, exist_ok=True)
        with open(os.path.join(bdir, 'before-restore-%s.zip' % time.strftime('%Y%m%d-%H%M%S')), 'wb') as f:
            f.write(self.backup_zip())
        keep = sorted(os.listdir(bdir))
        for old in keep[:-5]:
            try:
                os.remove(os.path.join(bdir, old))
            except OSError:
                pass
        with self._lock:
            for f in os.listdir(self.pdir):
                if re.match(r'^\d+\.db(-wal|-shm)?$', f):
                    os.remove(os.path.join(self.pdir, f))
            for ext in ('-wal', '-shm'):
                try:
                    os.remove(self.admin_path + ext)
                except OSError:
                    pass
            for n, p in staged.items():
                dest = self.admin_path if n == 'admin.db' else os.path.join(self.pdir, n.split('/')[1])
                shutil.move(p, dest)
            self._ready.clear()
        return len(staged)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


Store.backup_zip = _store_backup_zip
Store.restore_zip = _store_restore_zip
