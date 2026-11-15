'use strict';
// A small QR code generator (byte mode, error correction level M, versions 1-10) with SVG output.
// A port of server/app/qr.py, so the desktop can show a code without any package or network.

const VERSIONS = { 1: [26, 10, 1], 2: [44, 16, 1], 3: [70, 26, 1], 4: [100, 18, 2], 5: [134, 24, 2],
  6: [172, 16, 4], 7: [196, 18, 4], 8: [242, 22, 4], 9: [292, 22, 5], 10: [346, 26, 5] };

const capacity = (v) => VERSIONS[v][0] - VERSIONS[v][1] * VERSIONS[v][2];

function rsMul(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >> 7) * 0x11D); z ^= ((y >> i) & 1) * x; }
  return z;
}
function rsGenerator(degree) {
  const result = new Array(degree - 1).fill(0).concat([1]);
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = rsMul(result[j], root);
      if (j + 1 < degree) result[j] ^= result[j + 1];
    }
    root = rsMul(root, 2);
  }
  return result;
}
function rsRemainder(data, divisor) {
  const result = new Array(divisor.length).fill(0);
  for (const b of data) {
    const factor = b ^ result.shift();
    result.push(0);
    divisor.forEach((coef, i) => { result[i] ^= rsMul(coef, factor); });
  }
  return result;
}
const bit = (x, i) => (x >> i) & 1;

function encodeCodewords(data, ver) {
  const [total, eccLen, nblocks] = VERSIONS[ver];
  const cap = total - eccLen * nblocks;
  const bits = [];
  const put = (val, n) => { for (let i = n - 1; i >= 0; i--) bits.push((val >> i) & 1); };
  put(0b0100, 4);
  put(data.length, ver <= 9 ? 8 : 16);
  for (const b of data) put(b, 8);
  put(0, Math.min(4, cap * 8 - bits.length));
  put(0, ((8 - (bits.length % 8)) % 8));
  const cw = [];
  for (let i = 0; i < bits.length; i += 8) cw.push(parseInt(bits.slice(i, i + 8).join(''), 2));
  let pad = 0xEC;
  while (cw.length < cap) { cw.push(pad); pad ^= 0xEC ^ 0x11; }

  const shortBlocks = nblocks - (total % nblocks);
  const shortLen = Math.floor(total / nblocks);
  const divisor = rsGenerator(eccLen);
  const blocks = [];
  let k = 0;
  for (let i = 0; i < nblocks; i++) {
    let dat = cw.slice(k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1));
    k += dat.length;
    const ecc = rsRemainder(dat, divisor);
    if (i < shortBlocks) dat = dat.concat([0]);
    blocks.push(dat.concat(ecc));
  }
  const out = [];
  for (let i = 0; i < blocks[0].length; i++) {
    blocks.forEach((blk, j) => { if (i !== shortLen - eccLen || j >= shortBlocks) out.push(blk[i]); });
  }
  return out;
}

class Matrix {
  constructor(ver) {
    this.ver = ver; this.size = ver * 4 + 17;
    const n = this.size;
    this.m = Array.from({ length: n }, () => new Array(n).fill(false));
    this.fn = Array.from({ length: n }, () => new Array(n).fill(false));
    this.drawFunctions();
  }
  set(x, y, dark) { this.m[y][x] = !!dark; this.fn[y][x] = true; }
  finder(cx, cy) {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy;
      if (x >= 0 && x < this.size && y >= 0 && y < this.size) { const d = Math.max(Math.abs(dx), Math.abs(dy)); this.set(x, y, d !== 2 && d !== 4); }
    }
  }
  alignment(cx, cy) {
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) this.set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }
  alignPositions() {
    if (this.ver === 1) return [];
    const n = Math.floor(this.ver / 7) + 2;
    const step = Math.floor((this.ver * 4 + n * 2 + 1) / (n * 2 - 2)) * 2;
    const res = []; for (let i = 0; i < n - 1; i++) res.push(this.size - 7 - i * step);
    res.push(6);
    return res.reverse();
  }
  drawFunctions() {
    const n = this.size;
    for (let i = 0; i < n; i++) { this.set(6, i, i % 2 === 0); this.set(i, 6, i % 2 === 0); }
    this.finder(3, 3); this.finder(n - 4, 3); this.finder(3, n - 4);
    const pos = this.alignPositions();
    pos.forEach((cx, i) => pos.forEach((cy, j) => {
      if ((i === 0 && j === 0) || (i === 0 && j === pos.length - 1) || (i === pos.length - 1 && j === 0)) return;
      this.alignment(cx, cy);
    }));
    this.format(0);
    this.versionInfo();
  }
  format(mask) {
    const data = (0 << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;
    const n = this.size;
    for (let i = 0; i < 6; i++) this.set(8, i, bit(bits, i));
    this.set(8, 7, bit(bits, 6)); this.set(8, 8, bit(bits, 7)); this.set(7, 8, bit(bits, 8));
    for (let i = 9; i < 15; i++) this.set(14 - i, 8, bit(bits, i));
    for (let i = 0; i < 8; i++) this.set(n - 1 - i, 8, bit(bits, i));
    for (let i = 8; i < 15; i++) this.set(8, n - 15 + i, bit(bits, i));
    this.set(8, n - 8, true);
  }
  versionInfo() {
    if (this.ver < 7) return;
    let rem = this.ver;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
    const bits = (this.ver << 12) | rem;
    const n = this.size;
    for (let i = 0; i < 18; i++) { const a = n - 11 + (i % 3), b = Math.floor(i / 3); this.set(a, b, bit(bits, i)); this.set(b, a, bit(bits, i)); }
  }
  place(codewords) {
    const n = this.size; let i = 0; const total = codewords.length * 8;
    for (let right = n - 1; right > 0; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < n; vert++) for (let j = 0; j < 2; j++) {
        const x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? n - 1 - vert : vert;
        if (!this.fn[y][x] && i < total) { this.m[y][x] = !!bit(codewords[i >> 3], 7 - (i & 7)); i++; }
      }
    }
  }
  mask(k) {
    for (let y = 0; y < this.size; y++) for (let x = 0; x < this.size; x++) {
      const inv = [(x + y) % 2 === 0, y % 2 === 0, x % 3 === 0, (x + y) % 3 === 0,
        (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x * y) % 2 + (x * y) % 3 === 0,
        ((x * y) % 2 + (x * y) % 3) % 2 === 0, (((x + y) % 2) + (x * y) % 3) % 2 === 0][k];
      if (!this.fn[y][x] && inv) this.m[y][x] = !this.m[y][x];
    }
  }
  penalty() {
    const n = this.size, m = this.m; let score = 0;
    const cols = Array.from({ length: n }, (_, x) => m.map((r) => r[x]));
    for (const grid of [m, cols]) for (const row of grid) {
      let run = 1, prev = row[0];
      for (let i = 1; i < row.length; i++) {
        const v = row[i];
        if (v === prev) run++; else { if (run >= 5) score += 3 + run - 5; run = 1; prev = v; }
      }
      if (run >= 5) score += 3 + run - 5;
      const s = row.map((v) => (v ? '1' : '0')).join('');
      const count = (p) => { let c = 0, i = -1; while ((i = s.indexOf(p, i + 1)) !== -1) c++; return c; };
      score += 40 * (count('10111010000') + count('00001011101'));
    }
    for (let y = 0; y < n - 1; y++) for (let x = 0; x < n - 1; x++) if (m[y][x] === m[y][x + 1] && m[y][x] === m[y + 1][x] && m[y][x] === m[y + 1][x + 1]) score += 3;
    let dark = 0; for (const r of m) for (const v of r) if (v) dark++;
    const k = Math.floor((Math.abs(dark * 20 - n * n * 10) + n * n - 1) / (n * n)) - 1;
    return score + Math.max(0, k) * 10;
  }
}

function makeMatrix(text) {
  const data = Buffer.from(text, 'utf8');
  let ver = null;
  for (let v = 1; v <= 10; v++) if (data.length <= capacity(v) - (v <= 9 ? 2 : 3)) { ver = v; break; }
  if (ver === null) throw new Error('text too long for this QR generator');
  const cw = encodeCodewords([...data], ver);
  let best = null, bestScore = null;
  for (let k = 0; k < 8; k++) {
    const mat = new Matrix(ver);
    mat.place(cw); mat.mask(k); mat.format(k);
    const sc = mat.penalty();
    if (best === null || sc < bestScore) { best = mat; bestScore = sc; }
  }
  return best.m;
}

function makeSvg(text, scale = 5, dark = '#000000', light = '#ffffff') {
  const m = makeMatrix(text), n = m.length, q = 4, parts = [];
  m.forEach((row, y) => {
    let x = 0;
    while (x < n) {
      if (row[x]) { const s = x; while (x < n && row[x]) x++; parts.push(`M${s + q} ${y + q}h${x - s}v1h-${x - s}z`); } else x++;
    }
  });
  const size = (n + 2 * q) * scale;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n + 2 * q} ${n + 2 * q}" width="${size}" height="${size}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="${light}"/><path d="${parts.join('')}" fill="${dark}"/></svg>`;
}

module.exports = { makeMatrix, makeSvg };
