'use strict';
// Unpacks a Chrome extension (.crx, versions 2 and 3, or a plain zip) into a folder. No dependencies.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const MAX_TOTAL = 200 * 1024 * 1024;

function zipStart(buf) {
  if (buf.length > 16 && buf.toString('latin1', 0, 4) === 'Cr24') {
    const ver = buf.readUInt32LE(4);
    if (ver === 2) return 16 + buf.readUInt32LE(8) + buf.readUInt32LE(12);
    if (ver === 3) return 12 + buf.readUInt32LE(8);
    throw new Error('Unsupported extension package version ' + ver);
  }
  if (buf.length > 4 && buf[0] === 0x50 && buf[1] === 0x4b) return 0;
  throw new Error('That download is not an extension package');
}

function extractCrx(buf, dest) {
  const zip = buf.subarray(zipStart(buf));
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) if (zip.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('Package is damaged (no zip directory)');
  const count = zip.readUInt16LE(eocd + 10);
  let p = zip.readUInt32LE(eocd + 16);
  const root = path.resolve(dest);
  fs.mkdirSync(root, { recursive: true });
  let total = 0;
  for (let n = 0; n < count; n++) {
    if (zip.readUInt32LE(p) !== 0x02014b50) throw new Error('Package is damaged (bad directory entry)');
    const method = zip.readUInt16LE(p + 10);
    const csize = zip.readUInt32LE(p + 20);
    const usize = zip.readUInt32LE(p + 24);
    const nlen = zip.readUInt16LE(p + 28), elen = zip.readUInt16LE(p + 30), clen = zip.readUInt16LE(p + 32);
    const lho = zip.readUInt32LE(p + 42);
    const name = zip.toString('utf8', p + 46, p + 46 + nlen);
    p += 46 + nlen + elen + clen;
    const out = path.resolve(root, name);
    if (out !== root && !out.startsWith(root + path.sep)) throw new Error('Package contains an unsafe path');
    if (name.endsWith('/')) { fs.mkdirSync(out, { recursive: true }); continue; }
    total += usize;
    if (total > MAX_TOTAL) throw new Error('Package is too large');
    if (zip.readUInt32LE(lho) !== 0x04034b50) throw new Error('Package is damaged (bad file header)');
    const start = lho + 30 + zip.readUInt16LE(lho + 26) + zip.readUInt16LE(lho + 28);
    const raw = zip.subarray(start, start + csize);
    const data = method === 0 ? raw : method === 8 ? zlib.inflateRawSync(raw) : null;
    if (!data) throw new Error('Package uses an unsupported compression method');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, data);
  }
  if (!fs.existsSync(path.join(root, 'manifest.json'))) throw new Error('Package has no manifest.json');
}

/** The 32-letter Chrome Web Store id in a store link, or a bare id. */
function storeId(text) {
  const m = /(?:^|[\/\s])([a-p]{32})(?:[\/?#\s]|$)/.exec(String(text || '').trim());
  return m ? m[1] : null;
}

const crxUrl = (id, chromeVersion) =>
  'https://clients2.google.com/service/update2/crx?response=redirect&prodversion=' + encodeURIComponent(chromeVersion) +
  '&acceptformat=crx2,crx3&x=' + encodeURIComponent('id=' + id + '&uc');

module.exports = { extractCrx, storeId, crxUrl };
