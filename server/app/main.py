"""mbrowser sync server: web interface, accounts and sync API in one small program (standard library only)."""
import json
import os
import re
import signal
import sys
import threading
import time
import traceback
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from . import guiapi, security, syncapi
from .store import ApiError, Store

VERSION = '1.0.0'
STATIC_DIR = os.path.join(os.path.dirname(__file__), 'static')
STATIC_FILES = {'/': 'index.html', '/index.html': 'index.html', '/app.js': 'app.js', '/style.css': 'style.css',
                '/logo.png': 'logo.png', '/favicon.png': 'logo.png'}
TYPES = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png'}
CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; "
       "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors *")


class Config:
    def __init__(self, env=os.environ):
        self.port = int(env.get('PORT', '4242'))
        self.config_dir = env.get('CONFIG_DIR', '/config')
        self.admin_user = env.get('ADMIN_USER', '').strip()
        self.admin_password = env.get('ADMIN_PASSWORD', '')
        self.trust_proxy = env.get('TRUST_PROXY', 'true').lower() in ('1', 'true', 'yes')
        self.session_days = max(1, int(env.get('SESSION_DAYS', '7')))


class Ctx:
    def __init__(self, cfg):
        self.cfg = cfg
        self.store = Store(cfg.config_dir)
        self.limiter = security.RateLimiter()
        self.version = VERSION


# (method, regex, handler, auth, max body bytes)   auth: none | session | admin | token
ROUTES = []
KB, MB = 1024, 1024 * 1024


def route(method, pattern, auth='session', limit=256 * KB):
    def deco(fn):
        ROUTES.append((method, re.compile('^' + pattern + '$'), fn, auth, limit))
        return fn
    return deco


def _info(ctx, req):
    return 200, {'name': 'mbrowser-sync', 'version': VERSION, 'accounts': True,
                 'features': ['bookmarks', 'history', 'tabs', 'vault', 'settings', 'wallpaper', 'pair', 'accounts']}


def _health(ctx, req):
    return 200, {'ok': True}


route('GET', '/health', 'none')(_health)
route('GET', '/api/info', 'none')(_info)

# ---- web interface API
G = '/api/gui'
route('GET', G + '/state', 'none')(guiapi.state)
route('POST', G + '/setup', 'none')(guiapi.setup)
route('POST', G + '/login', 'none')(guiapi.login)
route('POST', G + '/logout', 'none')(guiapi.logout)
route('POST', '/api/auth/device-login', 'none')(guiapi.device_login)
route('POST', G + '/password')(guiapi.change_password)
route('POST', G + '/2fa/begin')(guiapi.tfa_begin)
route('POST', G + '/2fa/enable')(guiapi.tfa_enable)
route('POST', G + '/2fa/disable')(guiapi.tfa_disable)
route('POST', G + '/2fa/recovery')(guiapi.tfa_new_recovery)
route('POST', G + '/sessions/revoke-others')(guiapi.revoke_others)
route('GET', G + '/profiles')(guiapi.profiles_list)
route('POST', G + '/profiles')(guiapi.profile_create)
P = G + r'/profiles/(\d+)'
route('GET', P + '/overview')(guiapi.profile_overview)
route('PATCH', P)(guiapi.profile_rename)
route('DELETE', P)(guiapi.profile_delete)
route('GET', P + '/keys')(guiapi.keys_list)
route('POST', P + '/keys')(guiapi.key_create)
route('DELETE', G + r'/keys/(\d+)')(guiapi.key_delete)
route('GET', P + '/bookmarks')(guiapi.bookmarks_list)
route('DELETE', P + '/bookmarks', limit=2 * MB)(guiapi.bookmarks_delete)
route('GET', P + '/history')(guiapi.history_list)
route('DELETE', P + '/history', limit=2 * MB)(guiapi.history_delete)
route('GET', P + '/devices')(guiapi.devices_list)
route('DELETE', P + r'/devices/([^/]+)')(guiapi.device_delete)
route('GET', P + '/settings')(guiapi.settings_view)
route('DELETE', P + '/settings')(guiapi.settings_delete)
route('DELETE', P + '/vault')(guiapi.vault_delete)
route('GET', P + '/export/bookmarks.html')(guiapi.export_bookmarks)
route('GET', P + '/export/history.json')(guiapi.export_history)
route('GET', G + '/users', 'admin')(guiapi.users_list)
route('POST', G + '/users', 'admin')(guiapi.user_create)
route('DELETE', G + r'/users/(\d+)', 'admin')(guiapi.user_delete)
route('POST', G + r'/users/(\d+)/password', 'admin')(guiapi.user_password)
route('POST', G + r'/users/(\d+)/disable2fa', 'admin')(guiapi.user_disable_2fa)
route('POST', G + r'/users/(\d+)/toggle', 'admin')(guiapi.user_toggle)


# ---- sync API (Bearer key)
def _with_profile(fn):
    def handler(ctx, req, *a):
        with ctx.store.pdb(req.profile_id) as c:
            return 200, fn(c, req)
    return handler


route('POST', '/api/bookmarks', 'token', 16 * MB)(_with_profile(lambda c, r: syncapi.bookmarks_sync(c, r.json())))
route('POST', '/api/history', 'token', 16 * MB)(_with_profile(lambda c, r: syncapi.history_sync(c, r.json())))
route('PUT', '/api/tabs', 'token', 2 * MB)(_with_profile(lambda c, r: syncapi.tabs_put(c, r.json())))
route('GET', '/api/tabs', 'token')(_with_profile(lambda c, r: syncapi.tabs_get(c, r.q('exclude'))))
route('GET', '/api/vault', 'token')(_with_profile(lambda c, r: syncapi.vault_get(c)))
route('PUT', '/api/vault', 'token', 12 * MB)(_with_profile(lambda c, r: syncapi.vault_put(c, r.json())))
def _pair(ctx, req):
    """A signed-in device asks for a fresh key for another device of the same profile (shown to it as a QR code)."""
    b = req.json()
    device = re.sub(r'\s+', ' ', str(b.get('device') or 'Phone')).strip()[:40] or 'Phone'
    _, token = ctx.store.replace_device_token(req.profile_id, device)
    return 200, {'token': token}


route('POST', '/api/pair', 'token')(_pair)
route('GET', '/api/wallpaper', 'token')(_with_profile(lambda c, r: syncapi.wallpaper_get(c)))
route('PUT', '/api/wallpaper', 'token', 5 * MB)(_with_profile(lambda c, r: syncapi.wallpaper_put(c, r.json())))
route('GET', '/api/settings', 'token')(_with_profile(lambda c, r: syncapi.settings_get(c)))
route('PUT', '/api/settings', 'token', 2 * MB)(_with_profile(lambda c, r: syncapi.settings_put(c, r.json())))


class Req:
    def __init__(self, handler, method, path, query, body):
        self.h, self.method, self.path, self.query, self.body = handler, method, path, query, body
        self.session = None
        self.profile_id = None
        self.set_cookie = None
        self._json = None
        self._cookies = None
        cfg = handler.server.ctx.cfg
        xff = handler.headers.get('X-Forwarded-For', '')
        self.ip = (xff.split(',')[0].strip() if (cfg.trust_proxy and xff) else handler.client_address[0])
        proto = handler.headers.get('X-Forwarded-Proto', '') if cfg.trust_proxy else ''
        self.secure = proto.split(',')[0].strip().lower() == 'https'
        host = (handler.headers.get('X-Forwarded-Host') if cfg.trust_proxy else None) or handler.headers.get('Host', '')
        self.base_url = ('https' if self.secure else 'http') + '://' + host

    @property
    def headers(self):
        return self.h.headers

    def q(self, name, default=''):
        return (self.query.get(name) or [default])[0]

    def cookie(self, name):
        if self._cookies is None:
            self._cookies = SimpleCookie(self.h.headers.get('Cookie', ''))
        m = self._cookies.get(name)
        return m.value if m else ''

    def json(self):
        if self._json is None:
            try:
                v = json.loads(self.body.decode('utf-8') or '{}')
            except (ValueError, UnicodeDecodeError):
                raise ApiError(400, 'Invalid JSON.')
            if not isinstance(v, dict):
                raise ApiError(400, 'A JSON object is expected.')
            self._json = v
        return self._json


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'mbrowser-sync'
    sys_version = ''
    timeout = 60

    def log_message(self, fmt, *args):
        pass  # we log ourselves

    def _send(self, status, payload, headers=None, head_only=False, cookie=None, secure=False):
        if isinstance(payload, (dict, list)):
            data = json.dumps(payload).encode('utf-8')
            ctype = 'application/json'
        elif isinstance(payload, str):
            data, ctype = payload.encode('utf-8'), 'text/plain; charset=utf-8'
        else:
            data, ctype = payload, 'application/octet-stream'
        self.send_response(status)
        h = {'Content-Type': ctype, 'Content-Length': str(len(data)), 'X-Content-Type-Options': 'nosniff',
             'Referrer-Policy': 'same-origin', 'Cache-Control': 'no-store'}
        h.update(headers or {})
        if 'html' in h['Content-Type']:
            h['Content-Security-Policy'] = CSP
        for k, v in h.items():
            self.send_header(k, v)
        if cookie is not None:
            sid, age = cookie
            attrs = 'mb_session=%s; Path=/; HttpOnly; SameSite=Strict; Max-Age=%d' % (sid, age)
            if secure:
                attrs += '; Secure'
            self.send_header('Set-Cookie', attrs)
        self.end_headers()
        if not head_only:
            self.wfile.write(data)

    def _static(self, path, head_only):
        name = STATIC_FILES.get(path)
        if not name:
            return False
        try:
            with open(os.path.join(STATIC_DIR, name), 'rb') as f:
                data = f.read()
        except OSError:
            return False
        ext = os.path.splitext(name)[1]
        self._send(200, data, {'Content-Type': TYPES.get(ext, 'application/octet-stream'), 'Cache-Control': 'no-cache'}, head_only)
        return True

    def _handle(self):
        t0 = time.time()
        ctx = self.server.ctx
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        method = self.command
        status = 500
        try:
            if method in ('GET', 'HEAD') and not path.startswith('/api/') and path != '/health':
                if self._static(path, method == 'HEAD'):
                    return
                status = 404
                return self._send(404, {'error': 'Not found'})
            matched, path_ok = None, False
            for m, rx, fn, auth, limit in ROUTES:
                mo = rx.match(path)
                if mo:
                    path_ok = True
                    if m == method:
                        matched = (fn, auth, limit, mo.groups())
                        break
            if not matched:
                status = 405 if path_ok else 404
                return self._send(status, {'error': 'Method not allowed' if path_ok else 'Not found', 'detail': 'Not Found'})
            fn, auth, limit, groups = matched

            n = int(self.headers.get('Content-Length') or 0)
            if self.headers.get('Transfer-Encoding'):
                raise ApiError(411, 'Content-Length is required.')
            if n > limit:
                self.close_connection = True
                raise ApiError(413, 'Request too large.')
            body = self.rfile.read(n) if n else b''
            req = Req(self, method, path, parse_qs(parsed.query), body)
            if body and method in ('POST', 'PUT', 'PATCH', 'DELETE') and 'json' not in self.headers.get('Content-Type', '').lower():
                raise ApiError(415, 'Send JSON (Content-Type: application/json).')

            if auth == 'token':
                self._auth_token(ctx, req)
            else:
                sess = ctx.store.session(req.cookie('mb_session'), ctx.cfg.session_days)
                req.session = sess
                if auth in ('session', 'admin'):
                    if not sess:
                        raise ApiError(401, 'Please sign in.', signin=True)
                    if method not in ('GET', 'HEAD'):
                        import hmac
                        if not hmac.compare_digest(self.headers.get('X-CSRF-Token', ''), sess['csrf']):
                            raise ApiError(403, 'Security check failed. Reload the page and try again.')
                    if auth == 'admin' and not sess['is_admin']:
                        raise ApiError(403, 'Administrators only.')
            result = fn(ctx, req, *groups)
            status = result[0]
            hdrs = result[2] if len(result) > 2 else None
            self._send(status, result[1], hdrs, method == 'HEAD', req.set_cookie, req.secure)
        except ApiError as e:
            status = e.status
            self._send(e.status, {'error': e.message, 'detail': e.message, **e.extra})
        except (ConnectionError, TimeoutError):
            self.close_connection = True
        except Exception:
            traceback.print_exc()
            status = 500
            try:
                self._send(500, {'error': 'Server error', 'detail': 'Server error'})
            except OSError:
                pass
        finally:
            if path != '/health' and not (method == 'GET' and path in STATIC_FILES):
                sys.stdout.write('%s %s %s %d %dms\n' % (time.strftime('%H:%M:%S'), method, path, status, (time.time() - t0) * 1000))
                sys.stdout.flush()

    def _auth_token(self, ctx, req):
        kip = 'token-ip:' + req.ip
        if ctx.limiter.blocked(kip, 60, 300):
            raise ApiError(429, 'Too many failed attempts. Wait a few minutes.')
        h = self.headers.get('Authorization', '')
        token = h[7:].strip() if h.lower().startswith('bearer ') else ''
        row = ctx.store.find_token(token)
        if not row:
            ctx.limiter.fail(kip, 300)
            raise ApiError(401, 'bad token')
        ctx.store.touch_token(row, self.headers.get('X-Device', ''))
        req.profile_id = row['profile_id']

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = _handle


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, addr, ctx):
        super().__init__(addr, Handler)
        self.ctx = ctx


def main():
    cfg = Config()
    ctx = Ctx(cfg)
    if cfg.admin_user and cfg.admin_password and ctx.store.user_count() == 0:
        ctx.store.create_user(cfg.admin_user, cfg.admin_password, admin=True)
        print('Created the administrator account "%s" from ADMIN_USER / ADMIN_PASSWORD.' % cfg.admin_user)
    srv = Server(('0.0.0.0', cfg.port), ctx)
    print('mbrowser sync server %s listening on port %d (data in %s)' % (VERSION, cfg.port, cfg.config_dir))
    if ctx.store.user_count() == 0:
        print('No account exists yet: open the web interface to create the administrator.')
    sys.stdout.flush()
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=srv.shutdown).start())
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == '__main__':
    main()
