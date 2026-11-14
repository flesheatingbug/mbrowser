"""Passwords, API keys, sessions, rate limiting, two-factor codes (TOTP) and recovery codes. Standard library only."""
import base64
import hashlib
import hmac
import os
import re
import secrets
import struct
import threading
import time
from urllib.parse import quote

_SCRYPT = dict(n=2 ** 14, r=8, p=1, dklen=32, maxmem=64 * 1024 * 1024)


def hash_password(password):
    salt = os.urandom(16)
    return salt, hashlib.scrypt(password.encode('utf-8'), salt=salt, **_SCRYPT)


def verify_password(password, salt, expected):
    h = hashlib.scrypt(password.encode('utf-8'), salt=salt, **_SCRYPT)
    return hmac.compare_digest(h, expected)


# used when a user name does not exist, so a wrong user and a wrong password take equally long
DUMMY_SALT, DUMMY_HASH = hash_password('not-a-real-password')


def sha256(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def new_api_key():
    return 'mbk_' + secrets.token_urlsafe(30)


def new_session_id():
    return secrets.token_urlsafe(32)


class RateLimiter:
    """Counts failures per key inside a time window (in memory)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.fails = {}

    def _prune(self, key, window):
        cutoff = time.time() - window
        lst = [t for t in self.fails.get(key, []) if t > cutoff]
        if lst:
            self.fails[key] = lst
        else:
            self.fails.pop(key, None)
        return lst

    def blocked(self, key, limit, window):
        with self.lock:
            return len(self._prune(key, window)) >= limit

    def fail(self, key, window=900):
        with self.lock:
            self._prune(key, window)
            self.fails.setdefault(key, []).append(time.time())

    def reset(self, key):
        with self.lock:
            self.fails.pop(key, None)


# ---------------------------------------------------------------- two-factor (RFC 6238)
def new_totp_secret():
    return base64.b32encode(os.urandom(20)).decode('ascii').rstrip('=')


def hotp(secret_b32, counter, digits=6):
    key = base64.b32decode(secret_b32 + '=' * (-len(secret_b32) % 8), casefold=True)
    mac = hmac.new(key, struct.pack('>Q', counter), hashlib.sha1).digest()
    o = mac[-1] & 15
    code = (struct.unpack('>I', mac[o:o + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def verify_totp(secret_b32, code, last_step=0, now=None, window=1):
    """Returns the matching 30-second step (to be remembered, so a code cannot be used twice) or None."""
    code = re.sub(r'\D', '', str(code or ''))
    if len(code) != 6:
        return None
    step = int((now if now is not None else time.time()) // 30)
    for s in range(step - window, step + window + 1):
        if s > last_step and hmac.compare_digest(hotp(secret_b32, s), code):
            return s
    return None


def otpauth_uri(secret, account, issuer='mbrowser'):
    return 'otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30' % (
        quote(issuer), quote(account), secret, quote(issuer))


_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789'


def new_recovery_codes(n=10):
    return [''.join(secrets.choice(_ALPHABET) for _ in range(5)) + '-' + ''.join(secrets.choice(_ALPHABET) for _ in range(5))
            for _ in range(n)]


def normalize_recovery(code):
    return re.sub(r'[^a-z0-9]', '', str(code or '').lower())
