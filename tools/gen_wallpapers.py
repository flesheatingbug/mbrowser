#!/usr/bin/env python3
"""Generates the built-in wallpapers (original artwork made by this script, released CC0 / public domain).
Writes phone-size (portrait) images into the Android app and landscape images for the desktop browser.
  python3 tools/gen_wallpapers.py"""
import numpy as np, os
from PIL import Image, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
ANDROID = os.path.join(ROOT, 'android/app/src/main/res/drawable-nodpi')
DESKTOP = os.path.join(ROOT, 'assets/wallpapers')

def rgb(h): h = h.lstrip('#'); return np.array([int(h[i:i+2], 16) for i in (0, 2, 4)], dtype=np.float32)

def mesh(w, h, base, blobs, seed):
    """Smooth colour field: blobs = [(x, y, colour, radius)] with x, y, radius as fractions of the size."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    xx /= w; yy /= h
    acc = np.zeros((h, w, 3), np.float32); tot = np.full((h, w, 1), 1e-4, np.float32)
    acc += rgb(base) * 0.15; tot += 0.15
    asp = w / h
    for x, y, c, r in blobs:
        d2 = ((xx - x) * asp) ** 2 + (yy - y) ** 2
        wgt = np.exp(-d2 / (r * r))[..., None]
        acc += wgt * rgb(c); tot += wgt
    return acc / tot

def sky(w, h, top, bottom):
    t = np.linspace(0, 1, h, dtype=np.float32)[:, None, None]
    return np.broadcast_to(rgb(top) * (1 - t) + rgb(bottom) * t, (h, w, 3)).copy()

def ridge(img, colour, base, amp, freqs, seed, haze=0.0):
    h, w, _ = img.shape
    rng = np.random.default_rng(seed)
    xs = np.linspace(0, 1, w, dtype=np.float32)
    y = np.zeros(w, np.float32)
    for i, f in enumerate(freqs):
        y += np.sin(xs * f * 2 * np.pi + rng.uniform(0, 6.28)) * amp / (i + 1)
    edge = (base + y) * h
    rows = np.arange(h, dtype=np.float32)[:, None]
    mask = (rows >= edge[None, :]).astype(np.float32)[..., None]
    c = rgb(colour)
    # soft top edge and a vertical fade so layers melt into each other
    fade = np.clip((rows - edge[None, :]) / (0.25 * h), 0, 1)[..., None]
    col = c * (1 - haze * (1 - fade)) + img * haze * (1 - fade)
    return img * (1 - mask) + col * mask

def finish(img, seed, path, blur=0):
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    if blur: im = im.filter(ImageFilter.GaussianBlur(blur))
    a = np.asarray(im).astype(np.float32)
    a += np.random.default_rng(seed).normal(0, 1.1, a.shape)   # a touch of grain hides banding
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(path, quality=84, optimize=True, progressive=True)

def stars(img, n, seed, size=1.6):
    h, w, _ = img.shape
    rng = np.random.default_rng(seed)
    for _ in range(n):
        x, y = rng.integers(0, w), rng.integers(0, int(h * 0.7))
        b = rng.uniform(0.3, 1.0)
        r = int(size * rng.uniform(0.6, 1.6))
        img[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1] += 200 * b
    return np.clip(img, 0, 255)

def make(name, w, h, desktop):
    s = hash(name) % 1000 + 7
    P = (lambda a, b: (a, b)) if not desktop else (lambda a, b: (b, a))
    def at(x, y): return P(x, y) if not desktop else (x, y)
    if name == 'aurora':
        img = mesh(w, h, '#07121f', [(0.2, 0.2, '#0e6b5e', 0.35), (0.8, 0.35, '#3a2a8c', 0.4), (0.5, 0.1, '#12a37f', 0.25), (0.5, 1.0, '#04070d', 0.6)], s)
        img = stars(img, 160, s)
    elif name == 'dusk':
        img = mesh(w, h, '#2b1b3d', [(0.5, 0.95, '#ff8a4c', 0.35), (0.2, 0.7, '#d9487d', 0.4), (0.8, 0.5, '#6a3a9c', 0.45), (0.5, 0.0, '#1b1233', 0.5)], s)
    elif name == 'ocean':
        img = sky(w, h, '#bfe3f2', '#f5e9d0')
        for i, (c, b) in enumerate([('#6fb6d6', 0.50), ('#3f8fb8', 0.62), ('#256a96', 0.74), ('#164a73', 0.86)]):
            img = ridge(img, c, b, 0.03, [1.5, 3.2, 6.1], s + i, 0.25)
    elif name == 'dunes':
        img = sky(w, h, '#f6d9a8', '#fbe9cb')
        for i, (c, b) in enumerate([('#e8b377', 0.5), ('#d4915a', 0.62), ('#b86d44', 0.75), ('#8a4a33', 0.88)]):
            img = ridge(img, c, b, 0.05, [0.9, 2.1, 4.0], s + i, 0.2)
    elif name == 'mist':
        img = sky(w, h, '#c9d6e3', '#eef2f6')
        for i, (c, b) in enumerate([('#b3c3d4', 0.42), ('#93a8bf', 0.54), ('#6f869f', 0.68), ('#4a6079', 0.82)]):
            img = ridge(img, c, b, 0.06, [1.0, 2.6, 5.5], s + i, 0.55)
    elif name == 'forest':
        img = sky(w, h, '#2a4a3a', '#a9c9a4')
        for i, (c, b) in enumerate([('#5f8f6a', 0.5), ('#3f7152', 0.62), ('#27523f', 0.75), ('#143a2d', 0.88)]):
            img = ridge(img, c, b, 0.04, [2.0, 4.5, 9.0], s + i, 0.3)
    elif name == 'ember':
        img = mesh(w, h, '#12060a', [(0.5, 1.0, '#e2461f', 0.45), (0.15, 0.85, '#8a1f2b', 0.4), (0.85, 0.7, '#c2381f', 0.35), (0.5, 0.0, '#0a0305', 0.6)], s)
    elif name == 'orchid':
        img = mesh(w, h, '#3a2140', [(0.2, 0.2, '#e58fc0', 0.4), (0.8, 0.6, '#8a5ad6', 0.45), (0.4, 0.95, '#f2b0d6', 0.35), (0.9, 0.05, '#4a2a6a', 0.4)], s)
    elif name == 'slate':
        img = mesh(w, h, '#14171c', [(0.3, 0.2, '#2b3440', 0.5), (0.8, 0.8, '#1c2430', 0.5), (0.5, 0.5, '#222a35', 0.4)], s)
    elif name == 'nebula':
        img = mesh(w, h, '#05050e', [(0.25, 0.3, '#5a2a9c', 0.35), (0.75, 0.55, '#c23a7a', 0.3), (0.5, 0.8, '#1f4aa8', 0.35), (0.6, 0.15, '#2a1a5a', 0.3)], s)
        img = stars(img, 420, s, 1.3)
    elif name == 'peaks':
        img = mesh(w, h, '#f6c9a0', [(0.5, 0.55, '#ffd9a0', 0.4), (0.5, 0.0, '#6a79c9', 0.6), (0.1, 0.3, '#c99ac9', 0.4)], s)
        for i, (c, b) in enumerate([('#8d7fb0', 0.52), ('#6a5f93', 0.64), ('#4a416f', 0.76), ('#2c2650', 0.88)]):
            img = ridge(img, c, b, 0.07, [1.2, 3.0, 7.0], s + i, 0.35)
    elif name == 'paper':
        img = mesh(w, h, '#f4efe6', [(0.2, 0.2, '#fbf7ef', 0.5), (0.8, 0.8, '#e9dfcf', 0.5), (0.7, 0.2, '#efe4d2', 0.4)], s)
    else:
        raise SystemExit(name)
    return img

NAMES = ['aurora', 'dusk', 'ocean', 'dunes', 'mist', 'forest', 'ember', 'orchid', 'slate', 'nebula', 'peaks', 'paper']
if __name__ == '__main__':
    os.makedirs(ANDROID, exist_ok=True); os.makedirs(DESKTOP, exist_ok=True)
    for n in NAMES:
        finish(make(n, 1080, 2400, False), 1, os.path.join(ANDROID, f'wp_{n}.jpg'), blur=1.2)
        finish(make(n, 1920, 1080, True), 2, os.path.join(DESKTOP, f'{n}.jpg'), blur=1.2)
        print('made', n)
