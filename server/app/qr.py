"""A small QR code generator (byte mode, error correction level M, versions 1-10) with SVG output.
Enough for a two-factor setup link; no third-party packages needed."""

# per version 1..10: (total codewords, EC codewords per block, number of blocks) for level M
_VERSIONS = {
    1: (26, 10, 1), 2: (44, 16, 1), 3: (70, 26, 1), 4: (100, 18, 2), 5: (134, 24, 2),
    6: (172, 16, 4), 7: (196, 18, 4), 8: (242, 22, 4), 9: (292, 22, 5), 10: (346, 26, 5),
}


def _data_capacity(ver):
    total, ecc, blocks = _VERSIONS[ver]
    return total - ecc * blocks


def _rs_mul(x, y):
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_generator(degree):
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _rs_mul(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _rs_mul(root, 2)
    return result


def _rs_remainder(data, divisor):
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _rs_mul(coef, factor)
    return result


def _bit(x, i):
    return (x >> i) & 1


def _encode_codewords(data, ver):
    total, ecc_len, nblocks = _VERSIONS[ver]
    cap = total - ecc_len * nblocks
    bits = []

    def put(val, n):
        for i in range(n - 1, -1, -1):
            bits.append((val >> i) & 1)

    put(0b0100, 4)
    put(len(data), 8 if ver <= 9 else 16)
    for b in data:
        put(b, 8)
    put(0, min(4, cap * 8 - len(bits)))
    put(0, (-len(bits)) % 8)
    cw = [int(''.join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(cw) < cap:
        cw.append(pad)
        pad ^= 0xEC ^ 0x11

    short_blocks = nblocks - total % nblocks
    short_len = total // nblocks
    divisor = _rs_generator(ecc_len)
    blocks, k = [], 0
    for i in range(nblocks):
        dat = cw[k:k + short_len - ecc_len + (0 if i < short_blocks else 1)]
        k += len(dat)
        ecc = _rs_remainder(dat, divisor)
        if i < short_blocks:
            dat = dat + [0]
        blocks.append(dat + ecc)
    out = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != short_len - ecc_len or j >= short_blocks:
                out.append(blk[i])
    return out


class _Matrix:
    def __init__(self, ver):
        self.ver = ver
        self.size = ver * 4 + 17
        n = self.size
        self.m = [[False] * n for _ in range(n)]
        self.fn = [[False] * n for _ in range(n)]
        self._draw_functions()

    def _set(self, x, y, dark):
        self.m[y][x] = bool(dark)
        self.fn[y][x] = True

    def _finder(self, cx, cy):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x, y = cx + dx, cy + dy
                if 0 <= x < self.size and 0 <= y < self.size:
                    self._set(x, y, max(abs(dx), abs(dy)) not in (2, 4))

    def _alignment(self, cx, cy):
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                self._set(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    def _align_positions(self):
        if self.ver == 1:
            return []
        n = self.ver // 7 + 2
        step = (self.ver * 4 + n * 2 + 1) // (n * 2 - 2) * 2
        res = [self.size - 7 - i * step for i in range(n - 1)] + [6]
        return list(reversed(res))

    def _draw_functions(self):
        n = self.size
        for i in range(n):
            self._set(6, i, i % 2 == 0)
            self._set(i, 6, i % 2 == 0)
        self._finder(3, 3)
        self._finder(n - 4, 3)
        self._finder(3, n - 4)
        pos = self._align_positions()
        for i, cx in enumerate(pos):
            for j, cy in enumerate(pos):
                if (i == 0 and j == 0) or (i == 0 and j == len(pos) - 1) or (i == len(pos) - 1 and j == 0):
                    continue
                self._alignment(cx, cy)
        self._format(0)          # reserve the format area
        self._version_info()

    def _format(self, mask):
        data = (0 << 3) | mask   # level M = 00
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = ((data << 10) | rem) ^ 0x5412
        n = self.size
        for i in range(0, 6):
            self._set(8, i, _bit(bits, i))
        self._set(8, 7, _bit(bits, 6))
        self._set(8, 8, _bit(bits, 7))
        self._set(7, 8, _bit(bits, 8))
        for i in range(9, 15):
            self._set(14 - i, 8, _bit(bits, i))
        for i in range(0, 8):
            self._set(n - 1 - i, 8, _bit(bits, i))
        for i in range(8, 15):
            self._set(8, n - 15 + i, _bit(bits, i))
        self._set(8, n - 8, True)

    def _version_info(self):
        if self.ver < 7:
            return
        rem = self.ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = (self.ver << 12) | rem
        n = self.size
        for i in range(18):
            a, b = n - 11 + i % 3, i // 3
            self._set(a, b, _bit(bits, i))
            self._set(b, a, _bit(bits, i))

    def place(self, codewords):
        n, i = self.size, 0
        total_bits = len(codewords) * 8
        for right in range(n - 1, 0, -2):
            if right == 6:
                right = 5
            for vert in range(n):
                for j in range(2):
                    x = right - j
                    upward = ((right + 1) & 2) == 0
                    y = n - 1 - vert if upward else vert
                    if not self.fn[y][x] and i < total_bits:
                        self.m[y][x] = bool(_bit(codewords[i >> 3], 7 - (i & 7)))
                        i += 1

    def mask(self, k):
        for y in range(self.size):
            for x in range(self.size):
                inv = [
                    (x + y) % 2 == 0, y % 2 == 0, x % 3 == 0, (x + y) % 3 == 0,
                    (x // 3 + y // 2) % 2 == 0, x * y % 2 + x * y % 3 == 0,
                    (x * y % 2 + x * y % 3) % 2 == 0, ((x + y) % 2 + x * y % 3) % 2 == 0,
                ][k]
                if not self.fn[y][x] and inv:
                    self.m[y][x] = not self.m[y][x]

    def penalty(self):
        n, m = self.size, self.m
        score = 0
        for grid in (m, [list(r) for r in zip(*m)]):
            for row in grid:
                run, prev = 1, row[0]
                for v in row[1:]:
                    if v == prev:
                        run += 1
                    else:
                        if run >= 5:
                            score += 3 + run - 5
                        run, prev = 1, v
                if run >= 5:
                    score += 3 + run - 5
                s = ''.join('1' if v else '0' for v in row)
                score += 40 * (s.count('10111010000') + s.count('00001011101'))
        for y in range(n - 1):
            for x in range(n - 1):
                if m[y][x] == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                    score += 3
        dark = sum(v for r in m for v in r)
        k = (abs(dark * 20 - n * n * 10) + n * n - 1) // (n * n) - 1
        return score + max(0, k) * 10


def make_matrix(text):
    data = text.encode('utf-8')
    ver = next((v for v in range(1, 11) if len(data) <= _data_capacity(v) - (2 if v <= 9 else 3)), None)
    if ver is None:
        raise ValueError('text too long for this QR generator')
    cw = _encode_codewords(data, ver)
    best, best_score = None, None
    for k in range(8):
        mat = _Matrix(ver)
        mat.place(cw)
        mat.mask(k)
        mat._format(k)
        sc = mat.penalty()
        if best is None or sc < best_score:
            best, best_score = mat, sc
    return best.m


def make_svg(text, scale=5, dark='#000000', light='#ffffff'):
    m = make_matrix(text)
    n = len(m)
    q = 4
    parts = []
    for y, row in enumerate(m):
        x = 0
        while x < n:
            if row[x]:
                s = x
                while x < n and row[x]:
                    x += 1
                parts.append('M%d %dh%dv1h-%dz' % (s + q, y + q, x - s, x - s))
            else:
                x += 1
    size = (n + 2 * q) * scale
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" shape-rendering="crispEdges">'
            '<rect width="100%%" height="100%%" fill="%s"/><path d="%s" fill="%s"/></svg>'
            % (n + 2 * q, n + 2 * q, size, size, light, ''.join(parts), dark))
