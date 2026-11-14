"""Maintenance commands, run inside the container:
   docker compose exec mbrowser-sync python -m app.cli list-users
   docker compose exec mbrowser-sync python -m app.cli reset-password USER
   docker compose exec mbrowser-sync python -m app.cli disable-2fa USER
   docker compose exec mbrowser-sync python -m app.cli create-admin USER"""
import getpass
import os
import sys

from .store import ApiError, Store


def _password(args):
    if len(args) > 1:
        return args[1]
    p1 = getpass.getpass('New password: ')
    if p1 != getpass.getpass('Repeat it: '):
        sys.exit('The passwords do not match.')
    return p1


def main(argv=None):
    a = list(sys.argv[1:] if argv is None else argv)
    if not a:
        print(__doc__)
        return 1
    store = Store(os.environ.get('CONFIG_DIR', '/config'))
    cmd, rest = a[0], a[1:]
    try:
        if cmd == 'list-users':
            for u in store.list_users():
                print('%-24s %s%s%s' % (u['username'], 'admin ' if u['is_admin'] else 'user  ', '2FA ' if u['totp_enabled'] else '    ', 'DISABLED' if u['disabled'] else ''))
            return 0
        if not rest:
            print('Give a user name.\n' + __doc__)
            return 1
        if cmd == 'create-admin':
            store.create_user(rest[0], _password(rest), admin=True)
            print('Administrator "%s" created.' % rest[0])
            return 0
        u = store.user_by_name(rest[0])
        if not u:
            print('There is no user "%s".' % rest[0])
            return 1
        if cmd == 'reset-password':
            store.set_password(u['id'], _password(rest))
            store.set_flags(u['id'], disabled=0)
            print('Password changed for "%s"; all their sessions were signed out.' % u['username'])
        elif cmd == 'disable-2fa':
            store.set_flags(u['id'], totp_enabled=0, totp_secret=None, totp_step=0)
            store.replace_recovery(u['id'], [])
            print('Two-factor authentication turned off for "%s".' % u['username'])
        else:
            print('Unknown command.\n' + __doc__)
            return 1
    except ApiError as e:
        print(e.message)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
