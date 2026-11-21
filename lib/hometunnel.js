'use strict';
// Home-network tunnel client. A tiny HTTP proxy on 127.0.0.1 that sends connections for private addresses
// (192.168.x.x, router.lan ...) through your sync server, so they reach YOUR home network instead of the one
// this computer is on. The server must have ALLOW_TUNNEL=true; the connection is authenticated with the sync key.
const net = require('net');
const tls = require('tls');
const http = require('http');

const HOP = new Set(['proxy-connection', 'proxy-authorization', 'connection', 'keep-alive', 'te', 'trailer', 'upgrade', 'transfer-encoding']);

/** Opens a raw pipe to host:port on the server's network. Resolves with a connected socket. */
function openTunnel(cfg, host, port) {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(cfg.server); } catch { return reject(new Error('The sync server address is not valid.')); }
    const secure = u.protocol === 'https:';
    const opts = { host: u.hostname, port: Number(u.port) || (secure ? 443 : 80), servername: u.hostname };
    const sock = secure ? tls.connect(opts) : net.connect(opts);
    let done = false;
    const fail = (msg) => { if (done) return; done = true; sock.destroy(); reject(new Error(msg)); };
    sock.setTimeout(15000, () => fail('The sync server did not answer in time.'));
    sock.once('error', (e) => fail('Could not reach the sync server (' + (e.code || e.message) + ').'));
    sock.once(secure ? 'secureConnect' : 'connect', () => {
      const base = u.pathname.replace(/\/+$/, '');
      sock.write('GET ' + base + '/api/tunnel HTTP/1.1\r\nHost: ' + u.host + '\r\nAuthorization: Bearer ' + cfg.token +
        '\r\nX-Device: ' + (cfg.device || 'mbrowser') + '\r\nX-Target: ' + (host.includes(':') ? '[' + host + ']' : host) + ':' + port +
        '\r\nConnection: Upgrade\r\nUpgrade: mbtunnel\r\n\r\n');
    });
    let buf = Buffer.alloc(0);
    const onData = (d) => {
      buf = Buffer.concat([buf, d]);
      const end = buf.indexOf('\r\n\r\n');
      if (end < 0) { if (buf.length > 65536) fail('Unexpected answer from the sync server.'); return; }
      sock.removeListener('data', onData);
      const head = buf.subarray(0, end).toString('latin1');
      const rest = buf.subarray(end + 4);
      const code = Number((/^HTTP\/1\.[01] (\d{3})/.exec(head) || [])[1]);
      if (code === 101) {
        if (done) return;
        done = true; sock.setTimeout(0); sock.removeAllListeners('error');
        if (rest.length) sock.unshift(rest);
        return resolve(sock);
      }
      // an error answer: read its JSON body for a readable message
      const len = Number((/content-length:\s*(\d+)/i.exec(head) || [])[1]) || 0;
      const finish = () => {
        let msg = 'The sync server refused the tunnel (HTTP ' + code + ').';
        try { const j = JSON.parse(buf.subarray(end + 4).toString('utf8')); if (j.error) msg = j.error; } catch { /* keep default */ }
        if (code === 401) msg = 'The home network sign-in was rejected or has expired. Sign in again under Settings → Home network.';
        fail(msg);
      };
      if (buf.length - end - 4 >= len) return finish();
      sock.on('data', (d2) => { buf = Buffer.concat([buf, d2]); if (buf.length - end - 4 >= len) finish(); });
    };
    sock.on('data', onData);
  });
}

function splitHostPort(s, defPort) {
  const m = /^\[([^\]]+)\]:(\d+)$/.exec(s) || /^([^:]+):(\d+)$/.exec(s);
  return m ? [m[1], Number(m[2])] : [s.replace(/^\[|\]$/g, ''), defPort];
}

/** Starts the local proxy. getCfg() -> { server, token, device }. Resolves with { port, close }. */
function startProxy(getCfg, onError) {
  const server = http.createServer((req, res) => {
    let u;
    try { u = new URL(req.url); } catch { res.writeHead(400); return res.end('Bad proxy request'); }
    const [host, port] = [u.hostname.replace(/^\[|\]$/g, ''), Number(u.port) || 80];
    openTunnel(getCfg(), host, port).then((up) => {
      const headers = { connection: 'close' };
      for (const [k, v] of Object.entries(req.headers)) if (!HOP.has(k.toLowerCase())) headers[k] = v;
      const pr = http.request({ createConnection: () => up, method: req.method, path: u.pathname + u.search, headers }, (pres) => {
        res.writeHead(pres.statusCode, pres.statusMessage, pres.headers);
        pres.pipe(res);
      });
      pr.on('error', (e) => { if (onError) onError(e); try { res.destroy(); } catch { /* gone */ } });
      res.on('close', () => up.destroy());
      req.pipe(pr);
    }).catch((e) => { if (onError) onError(e); res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' }); res.end('Home network tunnel: ' + e.message); });
  });
  server.on('connect', (req, client, head) => {
    const [host, port] = splitHostPort(req.url, 443);
    client.on('error', () => {});
    openTunnel(getCfg(), host, port).then((up) => {
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head && head.length) up.write(head);
      up.on('error', () => client.destroy()); client.on('close', () => up.destroy()); up.on('close', () => client.destroy());
      up.pipe(client); client.pipe(up);
    }).catch((e) => { if (onError) onError(e); client.end('HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nHome network tunnel: ' + e.message); });
  });
  server.on('clientError', (_e, s) => { try { s.destroy(); } catch { /* gone */ } });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve({ port: server.address().port, close: () => { try { server.close(); } catch { /* ignore */ } server.closeAllConnections && server.closeAllConnections(); } }));
  });
}

/** PAC script: private addresses and home-style names go through the proxy, everything else stays direct. */
function pacScript(port) {
  const P = "'PROXY 127.0.0.1:" + port + "'";
  return 'function FindProxyForURL(url, host) {\n' +
    "  if (host === 'localhost' || host === '[::1]' || /^127\\./.test(host)) return 'DIRECT';\n" +
    '  if (/^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(host)) {\n' +
    "    if (isInNet(host, '10.0.0.0', '255.0.0.0') || isInNet(host, '172.16.0.0', '255.240.0.0') || isInNet(host, '192.168.0.0', '255.255.0.0')) return " + P + ";\n" +
    "    return 'DIRECT';\n  }\n" +
    "  if (isPlainHostName(host) || /\\.(local|lan|home|home\\.arpa|internal)$/i.test(host)) return " + P + ";\n" +
    "  return 'DIRECT';\n}\n";
}

module.exports = { openTunnel, startProxy, pacScript, splitHostPort };
