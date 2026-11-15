/* Reads the mbrowser password vault in the browser. The server only stores ciphertext; the master password
   (or device key) never leaves this page. Scrypt is done here because WebCrypto has none. */
(function () {
  'use strict';
  const b64 = (s) => Uint8Array.from(atob(s || ''), (c) => c.charCodeAt(0));

  async function pbkdf2(pass, salt, iters, len) {
    const k = await crypto.subtle.importKey('raw', pass, 'PBKDF2', false, ['deriveBits']);
    return new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iters }, k, len * 8));
  }

  function salsa8(B, Bi, Y, Yi) {
    const x = new Int32Array(16), r = (a, b) => (a << b) | (a >>> (32 - b));
    for (let i = 0; i < 16; i++) x[i] = B[Bi + i];
    for (let i = 0; i < 8; i += 2) {
      x[4] ^= r(x[0] + x[12] | 0, 7); x[8] ^= r(x[4] + x[0] | 0, 9); x[12] ^= r(x[8] + x[4] | 0, 13); x[0] ^= r(x[12] + x[8] | 0, 18);
      x[9] ^= r(x[5] + x[1] | 0, 7); x[13] ^= r(x[9] + x[5] | 0, 9); x[1] ^= r(x[13] + x[9] | 0, 13); x[5] ^= r(x[1] + x[13] | 0, 18);
      x[14] ^= r(x[10] + x[6] | 0, 7); x[2] ^= r(x[14] + x[10] | 0, 9); x[6] ^= r(x[2] + x[14] | 0, 13); x[10] ^= r(x[6] + x[2] | 0, 18);
      x[3] ^= r(x[15] + x[11] | 0, 7); x[7] ^= r(x[3] + x[15] | 0, 9); x[11] ^= r(x[7] + x[3] | 0, 13); x[15] ^= r(x[11] + x[7] | 0, 18);
      x[1] ^= r(x[0] + x[3] | 0, 7); x[2] ^= r(x[1] + x[0] | 0, 9); x[3] ^= r(x[2] + x[1] | 0, 13); x[0] ^= r(x[3] + x[2] | 0, 18);
      x[6] ^= r(x[5] + x[4] | 0, 7); x[7] ^= r(x[6] + x[5] | 0, 9); x[4] ^= r(x[7] + x[6] | 0, 13); x[5] ^= r(x[4] + x[7] | 0, 18);
      x[11] ^= r(x[10] + x[9] | 0, 7); x[8] ^= r(x[11] + x[10] | 0, 9); x[9] ^= r(x[8] + x[11] | 0, 13); x[10] ^= r(x[9] + x[8] | 0, 18);
      x[12] ^= r(x[15] + x[14] | 0, 7); x[13] ^= r(x[12] + x[15] | 0, 9); x[14] ^= r(x[13] + x[12] | 0, 13); x[15] ^= r(x[14] + x[13] | 0, 18);
    }
    for (let i = 0; i < 16; i++) Y[Yi + i] = (B[Bi + i] + x[i]) | 0;
  }

  // BlockMix on B (32*r words) in place, using scratch Y
  function blockMix(B, Y, r) {
    const X = new Int32Array(16);
    for (let i = 0; i < 16; i++) X[i] = B[(2 * r - 1) * 16 + i];
    const T = new Int32Array(16);
    for (let i = 0; i < 2 * r; i++) {
      for (let j = 0; j < 16; j++) T[j] = X[j] ^ B[i * 16 + j];
      salsa8(T, 0, X, 0);
      const dst = (i & 1 ? r + (i >> 1) : (i >> 1)) * 16;
      for (let j = 0; j < 16; j++) Y[dst + j] = X[j];
    }
    B.set(Y.subarray(0, 32 * r));
  }

  async function scrypt(pass, salt, N, r, p, dkLen, onTick) {
    const B = await pbkdf2(pass, salt, 1, p * 128 * r);
    const words = 32 * r;
    for (let pi = 0; pi < p; pi++) {
      const X = new Int32Array(words), Y = new Int32Array(words);
      const dv = new DataView(B.buffer, B.byteOffset + pi * 128 * r, 128 * r);
      for (let i = 0; i < words; i++) X[i] = dv.getInt32(i * 4, true);
      const V = new Int32Array(N * words);
      for (let i = 0; i < N; i++) {
        V.set(X, i * words); blockMix(X, Y, r);
        if (onTick && (i & 4095) === 0) { onTick(i / (2 * N)); await new Promise((f) => setTimeout(f)); }
      }
      for (let i = 0; i < N; i++) {
        const j = (X[(2 * r - 1) * 16] >>> 0) % N;
        for (let k = 0; k < words; k++) X[k] ^= V[j * words + k];
        blockMix(X, Y, r);
        if (onTick && (i & 4095) === 0) { onTick(0.5 + i / (2 * N)); await new Promise((f) => setTimeout(f)); }
      }
      for (let i = 0; i < words; i++) dv.setInt32(i * 4, X[i], true);
    }
    return pbkdf2(pass, B, 1, dkLen);
  }

  // blob: the stored { kdf, salt, iv, tag, data, device? }.  secret: master password, or for a device vault the base64 key.
  async function open(blob, secret, onTick) {
    let key;
    if (blob.device) key = b64(String(secret).trim());
    else key = await scrypt(new TextEncoder().encode(String(secret)), b64(blob.salt), blob.kdf.N, blob.kdf.r, blob.kdf.p, 32, onTick);
    if (key.length !== 32) throw new Error('That is not a valid key.');
    const ck = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']);
    const ct = b64(blob.data), tag = b64(blob.tag);
    const joined = new Uint8Array(ct.length + tag.length); joined.set(ct); joined.set(tag, ct.length);
    let plain;
    try { plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: b64(blob.iv) }, ck, joined); }
    catch { throw new Error(blob.device ? 'That key does not open this vault.' : 'Wrong master password.'); }
    const doc = JSON.parse(new TextDecoder().decode(plain));
    return { entries: (doc.entries || []).filter((e) => !e.deleted), folders: doc.folders || [] };
  }

  window.MBVault = { open, scrypt };
})();
