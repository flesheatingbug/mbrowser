"""Endpoints used by the web interface (cookie session + CSRF header) and by the browser's account sign-in."""
import html
import json
import re
import secrets
import time

from . import qr, security, syncapi
from .store import ApiError, now_ms


def public_user(u):
    return {'id': u['id'], 'username': u['username'], 'isAdmin': bool(u['is_admin']), 'totpEnabled': bool(u['totp_enabled'])}


# ---------------------------------------------------------------- state / login
def state(ctx, req):
    out = {'name': 'mbrowser sync', 'version': ctx.version, 'setupNeeded': ctx.store.user_count() == 0, 'authenticated': False}
    if req.session:
        u = req.session
        out.update(authenticated=True, user=public_user(u), csrf=u['csrf'], recoveryLeft=ctx.store.recovery_left(u['id']) if u['totp_enabled'] else None,
                   baseUrl=req.base_url)
    return 200, out


def _start_session(ctx, req, uid):
    sid, csrf = ctx.store.create_session(uid, ctx.cfg.session_days)
    req.set_cookie = (sid, ctx.cfg.session_days * 86400)
    return sid, csrf


def setup(ctx, req):
    if ctx.store.user_count() > 0:
        raise ApiError(403, 'Setup has already been completed.')
    b = req.json()
    uid = ctx.store.create_user(b.get('username'), b.get('password'), admin=True)
    _, csrf = _start_session(ctx, req, uid)
    return 200, {'ok': True, 'user': public_user(ctx.store.user_by_id(uid)), 'csrf': csrf}


def _check_credentials(ctx, req, b):
    """Shared by the web login and the browser's sign-in. Returns the user row or raises ApiError."""
    name = str(b.get('username', '')).strip()
    pw = str(b.get('password', ''))
    kip, kuser = 'login-ip:' + req.ip, 'login-user:' + name.lower()
    if ctx.limiter.blocked(kip, 40, 900) or ctx.limiter.blocked(kuser, 8, 900):
        raise ApiError(429, 'Too many attempts. Wait a few minutes and try again.')
    user = ctx.store.user_by_name(name)
    salt, h = (user['pw_salt'], user['pw_hash']) if user else (security.DUMMY_SALT, security.DUMMY_HASH)
    good = security.verify_password(pw, salt, h) and user is not None and not user['disabled']
    if not good:
        ctx.limiter.fail(kip); ctx.limiter.fail(kuser)
        raise ApiError(401, 'Wrong user name or password.')
    if user['totp_enabled']:
        code, rec = str(b.get('totp') or '').strip(), str(b.get('recovery') or '').strip()
        if not code and not rec:
            raise ApiError(401, 'Enter the 6-digit code from your authenticator app.', needTotp=True)
        if code:
            step = security.verify_totp(user['totp_secret'], code, user['totp_step'])
            if step is None:
                ctx.limiter.fail(kip); ctx.limiter.fail(kuser)
                raise ApiError(401, 'That code is wrong or expired.', needTotp=True)
            ctx.store.set_flags(user['id'], totp_step=step)
        elif not ctx.store.use_recovery(user['id'], rec):
            ctx.limiter.fail(kip); ctx.limiter.fail(kuser)
            raise ApiError(401, 'That recovery code is wrong or was already used.', needTotp=True)
    ctx.limiter.reset(kuser)
    return user


def login(ctx, req):
    user = _check_credentials(ctx, req, req.json())
    _, csrf = _start_session(ctx, req, user['id'])
    return 200, {'ok': True, 'user': public_user(user), 'csrf': csrf}


def logout(ctx, req):
    if req.session:
        ctx.store.end_session(req.cookie('mb_session'))
    req.set_cookie = ('', 0)
    return 200, {'ok': True}


TICKET_SECONDS = 600


def _ticket_user(ctx, b):
    """A short-lived ticket from an earlier successful sign-in, so choosing or creating a profile
    does not need the password (and a fresh 2FA code) a second time. Kept in memory only."""
    t = str(b.get('ticket') or '').strip()
    if not t:
        return None
    if not hasattr(ctx, 'tickets'):
        ctx.tickets = {}
    now = time.time()
    for k in [k for k, v in ctx.tickets.items() if v[1] < now]:
        del ctx.tickets[k]
    v = ctx.tickets.get(t)
    if not v:
        raise ApiError(401, 'The sign-in expired. Please sign in again.')
    user = ctx.store.user_by_id(v[0])
    if not user or user['disabled']:
        raise ApiError(401, 'The sign-in expired. Please sign in again.')
    return user


def _new_ticket(ctx, user_id):
    if not hasattr(ctx, 'tickets'):
        ctx.tickets = {}
    t = secrets.token_urlsafe(24)
    ctx.tickets[t] = (user_id, time.time() + TICKET_SECONDS)
    return t


def device_login(ctx, req):
    """Used by the browser: sign in with an account, get back an API key for one profile.

    Extra options for apps that let the user choose:
      list:true       -> only list the account's profiles and return a short-lived ticket (no key is issued)
      ticket          -> use that ticket instead of user name / password / code
      profile         -> name or id of the profile to use
      create:"name"   -> make a new profile with that name and use it
    """
    b = req.json()
    user = _ticket_user(ctx, b) or _check_credentials(ctx, req, b)
    profiles = ctx.store.list_profiles(user['id'])
    brief = [{'id': p['id'], 'name': p['name']} for p in profiles]
    if b.get('list'):
        return 200, {'profiles': brief, 'ticket': _new_ticket(ctx, user['id']), 'user': {'username': user['username']}}
    create = str(b.get('create') or '').strip()
    want = str(b.get('profile') or '').strip()
    chosen = None
    if create:
        chosen = ctx.store.get_profile(ctx.store.create_profile(user['id'], create))
    elif want:
        chosen = next((p for p in profiles if str(p['id']) == want or p['name'].lower() == want.lower()), None)
        if not chosen:
            raise ApiError(404, 'There is no profile with that name.')
    elif not profiles:
        raise ApiError(409, 'This account has no profile yet. Create one in the web interface first.')
    elif len(profiles) == 1:
        chosen = profiles[0]
    else:
        return 200, {'needProfile': True, 'profiles': brief}
    device = re.sub(r'\s+', ' ', str(b.get('device') or 'Browser')).strip()[:40] or 'Browser'
    _, token = ctx.store.replace_device_token(chosen['id'], device)
    return 200, {'token': token, 'profile': {'id': chosen['id'], 'name': chosen['name']}, 'user': {'username': user['username']}}


# ---------------------------------------------------------------- account
def _verify_pw(ctx, req, pw):
    if ctx.limiter.blocked('pw-user:%d' % req.session['id'], 8, 900):
        raise ApiError(429, 'Too many attempts. Wait a few minutes.')
    if not security.verify_password(str(pw or ''), req.session['pw_salt'], req.session['pw_hash']):
        ctx.limiter.fail('pw-user:%d' % req.session['id'])
        raise ApiError(403, 'The password is wrong.')


def change_password(ctx, req):
    b = req.json()
    _verify_pw(ctx, req, b.get('current'))
    ctx.store.set_password(req.session['id'], str(b.get('new') or ''))
    _, csrf = _start_session(ctx, req, req.session['id'])  # set_password signs everything out; keep this browser signed in
    return 200, {'ok': True, 'csrf': csrf}


def tfa_begin(ctx, req):
    _verify_pw(ctx, req, req.json().get('password'))
    u = ctx.store.user_by_id(req.session['id'])
    if u['totp_enabled']:
        raise ApiError(409, 'Two-factor authentication is already on.')
    secret = security.new_totp_secret()
    ctx.store.set_flags(u['id'], totp_secret=secret, totp_enabled=0, totp_step=0)
    uri = security.otpauth_uri(secret, u['username'])
    try:
        svg = qr.make_svg(uri, scale=5)
    except ValueError:
        svg = None
    return 200, {'secret': secret, 'uri': uri, 'qr': svg}


def tfa_enable(ctx, req):
    u = ctx.store.user_by_id(req.session['id'])
    if u['totp_enabled'] or not u['totp_secret']:
        raise ApiError(409, 'Start the setup first.')
    step = security.verify_totp(u['totp_secret'], req.json().get('code'), 0)
    if step is None:
        raise ApiError(400, 'That code is wrong or expired. Check the clock on your phone and try the next code.')
    codes = security.new_recovery_codes()
    ctx.store.replace_recovery(u['id'], codes)
    ctx.store.set_flags(u['id'], totp_enabled=1, totp_step=step)
    return 200, {'ok': True, 'recovery': codes}


def _second_factor(ctx, u, b):
    code, rec = str(b.get('code') or '').strip(), str(b.get('recovery') or '').strip()
    if code:
        step = security.verify_totp(u['totp_secret'], code, u['totp_step'])
        if step is None:
            raise ApiError(403, 'That code is wrong or expired.')
        ctx.store.set_flags(u['id'], totp_step=step)
    elif not (rec and ctx.store.use_recovery(u['id'], rec)):
        raise ApiError(403, 'Enter a current code from your authenticator app (or an unused recovery code).')


def tfa_disable(ctx, req):
    b = req.json()
    _verify_pw(ctx, req, b.get('password'))
    u = ctx.store.user_by_id(req.session['id'])
    if not u['totp_enabled']:
        raise ApiError(409, 'Two-factor authentication is not on.')
    _second_factor(ctx, u, b)
    ctx.store.set_flags(u['id'], totp_enabled=0, totp_secret=None, totp_step=0)
    ctx.store.replace_recovery(u['id'], [])
    return 200, {'ok': True}


def tfa_new_recovery(ctx, req):
    b = req.json()
    _verify_pw(ctx, req, b.get('password'))
    u = ctx.store.user_by_id(req.session['id'])
    if not u['totp_enabled']:
        raise ApiError(409, 'Two-factor authentication is not on.')
    _second_factor(ctx, u, b)
    codes = security.new_recovery_codes()
    ctx.store.replace_recovery(u['id'], codes)
    return 200, {'recovery': codes}


def revoke_others(ctx, req):
    ctx.store.end_other_sessions(req.session['id'], req.cookie('mb_session'))
    return 200, {'ok': True}


# ---------------------------------------------------------------- profiles
def _own(ctx, req, pid):
    p = ctx.store.get_profile(int(pid))
    if not p or p['owner_id'] != req.session['id']:
        raise ApiError(404, 'Profile not found.')
    return p


def _profile_json(ctx, p, full=False):
    d = {'id': p['id'], 'name': p['name'], 'created': p['created'], 'stats': ctx.store.profile_stats(p['id'])}
    if full:
        d['keys'] = len(ctx.store.list_tokens(p['id']))
    return d


def profiles_list(ctx, req):
    return 200, {'profiles': [_profile_json(ctx, p, True) for p in ctx.store.list_profiles(req.session['id'])]}


def profile_create(ctx, req):
    pid = ctx.store.create_profile(req.session['id'], req.json().get('name'))
    return 200, _profile_json(ctx, ctx.store.get_profile(pid), True)


def profile_overview(ctx, req, pid):
    return 200, _profile_json(ctx, _own(ctx, req, pid), True)


def profile_rename(ctx, req, pid):
    _own(ctx, req, pid)
    ctx.store.rename_profile(int(pid), req.json().get('name'))
    return 200, {'ok': True}


def profile_delete(ctx, req, pid):
    p = _own(ctx, req, pid)
    if str(req.json().get('confirm') or '') != p['name']:
        raise ApiError(400, 'Type the profile name to confirm.')
    ctx.store.delete_profile(p['id'])
    return 200, {'ok': True}


# ---------------------------------------------------------------- keys
def keys_list(ctx, req, pid):
    _own(ctx, req, pid)
    return 200, {'keys': [{'id': t['id'], 'name': t['name'], 'kind': t['kind'], 'token': t['token'], 'created': t['created'],
                           'lastUsed': t['last_used'], 'lastDevice': t['last_device']} for t in ctx.store.list_tokens(int(pid))]}


def key_create(ctx, req, pid):
    _own(ctx, req, pid)
    tid, token = ctx.store.create_token(int(pid), req.json().get('name'), 'api')
    return 200, {'id': tid, 'token': token}


def key_delete(ctx, req, tid):
    t = ctx.store.token_row(int(tid))
    if not t:
        raise ApiError(404, 'Key not found.')
    _own(ctx, req, t['profile_id'])
    ctx.store.delete_token(t['id'])
    return 200, {'ok': True}


# ---------------------------------------------------------------- data of a profile
def _like(q):
    return '%' + q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'


def _int(v, default, lo, hi):
    try:
        return max(lo, min(hi, int(v)))
    except (TypeError, ValueError):
        return default


def bookmarks_list(ctx, req, pid):
    _own(ctx, req, pid)
    q, limit, offset = req.q('q'), _int(req.q('limit'), 200, 1, 1000), _int(req.q('offset'), 0, 0, 10 ** 9)
    with ctx.store.pdb(int(pid)) as c:
        where, args = 'deleted=0', []
        if q:
            where += " AND (title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' OR folder LIKE ? ESCAPE '\\')"
            args += [_like(q)] * 3
        total = c.execute('SELECT COUNT(*) FROM bookmarks WHERE ' + where, args).fetchone()[0]
        rows = c.execute('SELECT url,title,folder,added FROM bookmarks WHERE %s ORDER BY folder, added DESC LIMIT ? OFFSET ?' % where, args + [limit, offset]).fetchall()
    return 200, {'total': total, 'items': [dict(r) for r in rows]}


def bookmarks_delete(ctx, req, pid):
    _own(ctx, req, pid)
    urls = [u for u in (req.json().get('urls') or []) if isinstance(u, str)][:5000]
    with ctx.store.pdb(int(pid)) as c:
        for u in urls:  # a "deleted" mark (not a removal) so that the browsers delete it too
            c.execute('UPDATE bookmarks SET deleted=1, updated=?, rev=? WHERE url=? AND deleted=0', (now_ms(), syncapi._next_rev(c), u))
    return 200, {'ok': True}


def history_list(ctx, req, pid):
    _own(ctx, req, pid)
    q, limit, offset = req.q('q'), _int(req.q('limit'), 200, 1, 1000), _int(req.q('offset'), 0, 0, 10 ** 9)
    with ctx.store.pdb(int(pid)) as c:
        where, args = '1=1', []
        if q:
            where = "(title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\')"
            args = [_like(q)] * 2
        total = c.execute('SELECT COUNT(*) FROM history WHERE ' + where, args).fetchone()[0]
        rows = c.execute('SELECT url,title,time FROM history WHERE %s ORDER BY time DESC LIMIT ? OFFSET ?' % where, args + [limit, offset]).fetchall()
    return 200, {'total': total, 'items': [dict(r) for r in rows]}


def history_delete(ctx, req, pid):
    _own(ctx, req, pid)
    b = req.json()
    with ctx.store.pdb(int(pid)) as c:
        if b.get('all'):
            n = c.execute('DELETE FROM history').rowcount
        else:
            urls = [u for u in (b.get('urls') or []) if isinstance(u, str)][:5000]
            n = sum(c.execute('DELETE FROM history WHERE url=?', (u,)).rowcount for u in urls)
    return 200, {'deleted': n}


def devices_list(ctx, req, pid):
    _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        return 200, syncapi.tabs_get(c, '')


def device_delete(ctx, req, pid, device):
    _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        c.execute('DELETE FROM tabs WHERE device=?', (device,))
    return 200, {'ok': True}


def settings_view(ctx, req, pid):
    _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        return 200, syncapi.settings_get(c)


def settings_delete(ctx, req, pid):
    _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        c.execute('DELETE FROM settings')
    return 200, {'ok': True}


def vault_delete(ctx, req, pid):
    _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        c.execute('DELETE FROM vault')
    return 200, {'ok': True}


def export_bookmarks(ctx, req, pid):
    p = _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        rows = c.execute('SELECT url,title,folder,added FROM bookmarks WHERE deleted=0 ORDER BY folder, added').fetchall()
    tree = {'items': [], 'kids': {}}
    for r in rows:
        node = tree
        for part in [x for x in (r['folder'] or '').split(' / ') if x]:
            node = node['kids'].setdefault(part, {'items': [], 'kids': {}})
        node['items'].append(r)

    def render(node, depth):
        pad = '    ' * depth
        out = []
        for name, kid in node['kids'].items():
            out.append('%s<DT><H3>%s</H3>\n%s<DL><p>\n%s%s</DL><p>\n' % (pad, html.escape(name), pad, render(kid, depth + 1), pad))
        for r in node['items']:
            out.append('%s<DT><A HREF="%s" ADD_DATE="%d">%s</A>\n' % (pad, html.escape(r['url'], quote=True), (r['added'] or 0) // 1000, html.escape(r['title'] or r['url'])))
        return ''.join(out)

    body = ('<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">\n'
            '<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n' + render(tree, 1) + '</DL><p>\n')
    name = re.sub(r'[^A-Za-z0-9_.-]+', '_', p['name']) or 'profile'
    return 200, body.encode('utf-8'), {'Content-Type': 'text/html; charset=utf-8', 'Content-Disposition': 'attachment; filename="bookmarks-%s.html"' % name}


def export_history(ctx, req, pid):
    p = _own(ctx, req, pid)
    with ctx.store.pdb(int(pid)) as c:
        rows = [dict(r) for r in c.execute('SELECT url,title,time FROM history ORDER BY time DESC').fetchall()]
    name = re.sub(r'[^A-Za-z0-9_.-]+', '_', p['name']) or 'profile'
    return 200, json.dumps(rows, indent=1).encode('utf-8'), {'Content-Type': 'application/json', 'Content-Disposition': 'attachment; filename="history-%s.json"' % name}


# ---------------------------------------------------------------- users (admin)
def users_list(ctx, req):
    return 200, {'users': ctx.store.list_users()}


def user_create(ctx, req):
    b = req.json()
    uid = ctx.store.create_user(b.get('username'), b.get('password'), admin=bool(b.get('isAdmin')))
    return 200, {'id': uid}


def _other(ctx, req, uid):
    u = ctx.store.user_by_id(int(uid))
    if not u:
        raise ApiError(404, 'User not found.')
    return u


def user_delete(ctx, req, uid):
    u = _other(ctx, req, uid)
    if u['id'] == req.session['id']:
        raise ApiError(400, 'You cannot delete your own account here.')
    if u['is_admin'] and ctx.store.admin_count() <= 1:
        raise ApiError(400, 'This is the last administrator.')
    if str(req.json().get('confirm') or '') != u['username']:
        raise ApiError(400, 'Type the user name to confirm. Their profiles and all synced data will be deleted.')
    ctx.store.delete_user(u['id'])
    return 200, {'ok': True}


def user_password(ctx, req, uid):
    u = _other(ctx, req, uid)
    ctx.store.set_password(u['id'], str(req.json().get('password') or ''))
    return 200, {'ok': True}


def user_disable_2fa(ctx, req, uid):
    u = _other(ctx, req, uid)
    ctx.store.set_flags(u['id'], totp_enabled=0, totp_secret=None, totp_step=0)
    ctx.store.replace_recovery(u['id'], [])
    return 200, {'ok': True}


def user_toggle(ctx, req, uid):
    u = _other(ctx, req, uid)
    if u['id'] == req.session['id']:
        raise ApiError(400, 'You cannot disable your own account.')
    disable = 0 if u['disabled'] else 1
    if disable and u['is_admin'] and ctx.store.admin_count() <= 1:
        raise ApiError(400, 'This is the last administrator.')
    ctx.store.set_flags(u['id'], disabled=disable)
    if disable:
        ctx.store.end_other_sessions(u['id'], '')
    return 200, {'disabled': bool(disable)}
