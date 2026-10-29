import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { createServer as createHttpServer } from 'node:http';
import { connect, createServer as createTcpServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createProxy, readConfig } from '../src/server.mjs';

const SERVER_FILE = fileURLToPath(new URL('../src/server.mjs', import.meta.url));
const HEADER_END = Buffer.from('\r\n\r\n');
const WS_KEY = 'dGhlIHNhbXBsZSBub25jZQ==';

async function listen(server, host = '127.0.0.1', port = 0) {
  server.listen(port, host);
  await once(server, 'listening');
  return server.address().port;
}

function managedServer(server) {
  const sockets = new Set();
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  return {
    server,
    listen: () => listen(server),
    close: async () => {
      for (const socket of sockets) socket.destroy();
      if (server.listening) await new Promise((resolve) => server.close(resolve));
    },
  };
}

async function startUpstream(handler, upgradeHandler) {
  const server = createHttpServer(handler);
  if (upgradeHandler) server.on('upgrade', upgradeHandler);
  const managed = managedServer(server);
  const port = await managed.listen();
  return { ...managed, port };
}

async function startProxy(upstreamPort, options = {}) {
  const logs = [];
  const proxy = createProxy({
    listenPort: 0,
    upstreamHost: '127.0.0.1',
    upstreamPort,
    logger: (event, fields) => logs.push({ event, ...fields }),
    ...options,
  });
  const address = await proxy.listen();
  return { proxy, port: address.port, logs };
}

async function closeProxy(proxy) {
  if (proxy) await proxy.close();
}

function parseResponse(buffer) {
  const boundary = buffer.indexOf(HEADER_END);
  assert.notEqual(boundary, -1, 'HTTP response must include a header terminator');
  const headerText = buffer.subarray(0, boundary).toString('latin1');
  const lines = headerText.split('\r\n');
  const status = Number(lines[0].split(' ')[1]);
  const headers = new Map();
  for (const line of lines.slice(1)) {
    const colon = line.indexOf(':');
    if (colon > 0) headers.set(line.slice(0, colon).toLowerCase(), line.slice(colon + 1).trim());
  }
  return { status, headers, body: buffer.subarray(boundary + HEADER_END.length) };
}

function rawExchange(port, request, timeoutMs = 2_000) {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1');
    const chunks = [];
    let done = false;
    const finish = (error, timedOut = false) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (error && (timedOut || chunks.length === 0)) reject(error);
      else resolve(Buffer.concat(chunks));
    };
    const timer = setTimeout(() => {
      socket.destroy();
      finish(new Error('raw HTTP exchange timed out'), true);
    }, timeoutMs);
    socket.once('connect', () => socket.write(request));
    socket.on('data', (chunk) => chunks.push(Buffer.from(chunk)));
    socket.once('end', () => finish());
    socket.once('close', () => finish());
    socket.once('error', (error) => finish(error));
  });
}

function socketReader(socket) {
  let buffer = Buffer.alloc(0);
  const waiters = new Set();
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    for (const waiter of [...waiters]) {
      if (!waiter.predicate(buffer)) continue;
      waiters.delete(waiter);
      clearTimeout(waiter.timer);
      socket.off('close', waiter.onClose);
      waiter.resolve(buffer);
    }
  });
  return {
    waitFor(predicate, timeoutMs = 2_000) {
      if (predicate(buffer)) return Promise.resolve(buffer);
      return new Promise((resolve, reject) => {
        const waiter = {
          predicate,
          resolve,
          timer: setTimeout(() => {
            waiters.delete(waiter);
            socket.off('close', waiter.onClose);
            reject(new Error('socket read timed out'));
          }, timeoutMs),
          onClose: () => {
            waiters.delete(waiter);
            clearTimeout(waiter.timer);
            reject(new Error('socket closed before expected bytes arrived'));
          },
        };
        waiters.add(waiter);
        socket.once('close', waiter.onClose);
      });
    },
    take(count) {
      assert.ok(buffer.length >= count, `expected ${count} bytes, have ${buffer.length}`);
      const output = buffer.subarray(0, count);
      buffer = buffer.subarray(count);
      return output;
    },
    get length() {
      return buffer.length;
    },
  };
}

function websocketRequest(target, extraHeaders = '', { optionalHeaders = true } = {}) {
  const lines = [
    `GET ${target} HTTP/1.1`,
    'Host: proxy.test',
    'Connection: keep-alive, Upgrade',
    'Upgrade: websocket',
    `Sec-WebSocket-Key: ${WS_KEY}`,
    'Sec-WebSocket-Version: 13',
  ];
  if (optionalHeaders) {
    lines.push('Sec-WebSocket-Protocol: atlas.remote.v1, atlas.remote.debug');
    lines.push('Sec-WebSocket-Extensions: x-atlas-test');
  }
  if (extraHeaders) lines.push(extraHeaders);
  return `${lines.join('\r\n')}\r\n\r\n`;
}

async function openWebSocket(port, target, extraHeaders = '', options = {}) {
  const socket = connect(port, '127.0.0.1');
  await once(socket, 'connect');
  const reader = socketReader(socket);
  socket.write(websocketRequest(target, extraHeaders, options));
  let received;
  try {
    received = await reader.waitFor((buffer) => buffer.indexOf(HEADER_END) >= 0);
  } catch (error) {
    socket.destroy();
    throw error;
  }
  const boundary = received.indexOf(HEADER_END);
  const response = parseResponse(reader.take(boundary + HEADER_END.length));
  return { socket, reader, response };
}

function maskedFrame(opcode, payload, mask = Buffer.from([0x31, 0x52, 0x73, 0x94])) {
  assert.ok(payload.length < 126, 'test frame uses the short payload form');
  const frame = Buffer.alloc(2 + mask.length + payload.length);
  frame[0] = 0x80 | opcode;
  frame[1] = 0x80 | payload.length;
  mask.copy(frame, 2);
  for (let index = 0; index < payload.length; index += 1) {
    frame[6 + index] = payload[index] ^ mask[index % mask.length];
  }
  return frame;
}

function serverFrame(opcode, payload) {
  assert.ok(payload.length < 126, 'test frame uses the short payload form');
  return Buffer.concat([Buffer.from([0x80 | opcode, payload.length]), payload]);
}

function waitForEvent(emitter, event, timeoutMs = 2_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      emitter.off(event, onEvent);
      reject(new Error(`timed out waiting for ${event}`));
    }, timeoutMs);
    const onEvent = (...args) => {
      clearTimeout(timer);
      resolve(args);
    };
    emitter.once(event, onEvent);
  });
}

function canConnect(port) {
  return new Promise((resolve) => {
    const socket = connect(port, '127.0.0.1');
    let done = false;
    const finish = (connected) => {
      if (done) return;
      done = true;
      socket.destroy();
      resolve(connected);
    };
    socket.setTimeout(300, () => finish(false));
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
  });
}

test('configuration binds to loopback and rejects public addresses', () => {
  assert.deepEqual(readConfig({}), {
    listenHost: '127.0.0.1', listenPort: 4400, upstreamHost: '127.0.0.1', upstreamPort: 4402,
  });
  assert.throws(() => readConfig({ LISTEN_HOST: '0.0.0.0' }), /LISTEN_HOST must be 127\.0\.0\.1/);
  assert.throws(() => readConfig({ UPSTREAM_HOST: '192.0.2.10' }), /UPSTREAM_HOST must be a loopback IP literal/);
  assert.throws(() => createProxy({ listenHost: '::1' }), /listenHost must be 127\.0\.0\.1/);
  assert.throws(() => createProxy({ upstreamHost: 'relay.example.test' }), /upstreamHost must be a loopback IP literal/);
});

test('HTTP allowlist proxies health and ready, and refuses other paths and methods', async (t) => {
  const upstreamPaths = [];
  const upstream = await startUpstream((req, res) => {
    upstreamPaths.push(req.url);
    if (req.url === '/health') {
      res.writeHead(201, { 'content-type': 'application/vnd.atlas.health+json' });
      res.end('{"status":"probe"}');
      return;
    }
    if (req.url === '/ready') {
      res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('relay warming\n');
      return;
    }
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('hidden upstream route');
  });
  const { proxy, port } = await startProxy(upstream.port);
  t.after(async () => {
    await closeProxy(proxy);
    await upstream.close();
  });
  const base = `http://127.0.0.1:${port}`;

  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 201);
  assert.equal(health.headers.get('content-type'), 'application/vnd.atlas.health+json');
  assert.equal(await health.text(), '{"status":"probe"}');

  const ready = await fetch(`${base}/ready`);
  assert.equal(ready.status, 503);
  assert.equal(ready.headers.get('content-type'), 'text/plain; charset=utf-8');
  assert.equal(await ready.text(), 'relay warming\n');

  const metrics = await fetch(`${base}/metrics`);
  assert.equal(metrics.status, 404);
  assert.equal(await metrics.text(), 'not found\n');
  const unknown = await fetch(`${base}/admin`);
  assert.equal(unknown.status, 404);
  const queriedHealth = await fetch(`${base}/health?token=do-not-forward`);
  assert.equal(queriedHealth.status, 404);
  assert.deepEqual(upstreamPaths, ['/health', '/ready']);

  for (const method of ['POST', 'PUT', 'DELETE']) {
    const response = await fetch(`${base}/health`, { method });
    assert.equal(response.status, 405);
    assert.equal(response.headers.get('allow'), 'GET');
  }
  const wsWithoutUpgrade = await fetch(`${base}/ws?serverId=synthetic&role=server&v=2`);
  assert.equal(wsWithoutUpgrade.status, 426);
  const partialUpgrade = await rawExchange(port,
    'GET /ws?serverId=synthetic&role=server&v=2 HTTP/1.1\r\nHost: test\r\nConnection: close\r\nUpgrade: websocket\r\n\r\n');
  assert.equal(parseResponse(partialUpgrade).status, 400);
  const websocketMetrics = await rawExchange(port, websocketRequest('/metrics'));
  assert.equal(parseResponse(websocketMetrics).status, 404);
});

test('WebSocket upgrade forwards routing headers and preserves text and binary frames byte for byte', async (t) => {
  let upstreamRequest;
  const upstream = await startUpstream((_req, res) => {
    res.writeHead(404);
    res.end();
  }, (req, socket, head) => {
    upstreamRequest = {
      url: req.url,
      key: req.headers['sec-websocket-key'],
      version: req.headers['sec-websocket-version'],
      protocol: req.headers['sec-websocket-protocol'],
      extensions: req.headers['sec-websocket-extensions'],
      privateHeader: req.headers['x-private-header'],
    };
    const expected = websocketAccept(WS_KEY);
    socket.write([
      'HTTP/1.1 101 Switching Protocols',
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Accept: ${expected}`,
      'Sec-WebSocket-Protocol: atlas.remote.v1',
      '',
      '',
    ].join('\r\n'));
    if (head.length) socket.write(head);
    socket.on('data', (chunk) => socket.write(chunk));
  });
  const capturedLogs = [];
  const { proxy, port } = await startProxy(upstream.port, {
    logger: (event, fields) => capturedLogs.push({ event, ...fields }),
  });
  t.after(async () => {
    await closeProxy(proxy);
    await upstream.close();
  });

  const queryMarker = 'synthetic-route-marker';
  let opened;
  try {
    opened = await openWebSocket(
      port,
      `/ws?serverId=${queryMarker}&role=client&v=2&connectionId=synthetic-connection`,
      'Content-Length: 0\r\nX-Private-Header: do-not-forward',
    );
  } catch (error) {
    assert.fail(`${error.message}; upstream=${JSON.stringify(upstreamRequest)}; logs=${JSON.stringify(capturedLogs)}`);
  }
  const { socket, reader, response } = opened;
  t.after(() => socket.destroy());
  assert.equal(response.status, 101);
  assert.equal(response.headers.get('sec-websocket-protocol'), 'atlas.remote.v1');
  assert.deepEqual(upstreamRequest, {
    url: `/ws?serverId=${queryMarker}&role=client&v=2&connectionId=synthetic-connection`,
    key: WS_KEY,
    version: '13',
    protocol: 'atlas.remote.v1, atlas.remote.debug',
    extensions: 'x-atlas-test',
    privateHeader: undefined,
  });

  const textPayload = Buffer.from('frame-probe-marker', 'utf8');
  const binaryPayload = Buffer.from([0x00, 0xff, 0x10, 0x80, 0x41, 0x00]);
  const textFrame = maskedFrame(0x1, textPayload);
  const binaryFrame = maskedFrame(0x2, binaryPayload, Buffer.from([0xaa, 0x55, 0x11, 0xee]));
  const frames = Buffer.concat([textFrame, binaryFrame]);
  socket.write(frames);
  await reader.waitFor((buffer) => buffer.length >= frames.length);
  assert.deepEqual(reader.take(frames.length), frames);

  const logged = JSON.stringify(capturedLogs);
  assert.equal(logged.includes(queryMarker), false);
  assert.equal(logged.includes('frame-probe-marker'), false);
  assert.equal(logged.includes(binaryPayload.toString('hex')), false);
});

test('WebSocket tunnel preserves DEVICE_REVOKED and the upstream 4001 close code', async (t) => {
  const revokedPayload = Buffer.from(JSON.stringify({
    protocol: 'atlas.remote.v1',
    kind: 'error',
    error: { code: 'DEVICE_REVOKED' },
  }), 'utf8');
  const revokedFrame = serverFrame(0x1, revokedPayload);
  const closeReason = Buffer.from('device revoked', 'utf8');
  const closePayload = Buffer.alloc(2 + closeReason.length);
  closePayload.writeUInt16BE(4001, 0);
  closeReason.copy(closePayload, 2);
  const closeFrame = serverFrame(0x8, closePayload);
  const expectedFrames = Buffer.concat([revokedFrame, closeFrame]);
  const upstream = await startUpstream((_req, res) => {
    res.writeHead(404);
    res.end();
  }, (_req, socket) => {
    const response = Buffer.from([
      'HTTP/1.1 101 Switching Protocols',
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Accept: ${websocketAccept(WS_KEY)}`,
      '',
      '',
    ].join('\r\n'), 'ascii');
    socket.write(Buffer.concat([response, expectedFrames]), () => socket.end());
  });
  const { proxy, port } = await startProxy(upstream.port);
  t.after(async () => {
    await closeProxy(proxy);
    await upstream.close();
  });

  const { socket, reader, response } = await openWebSocket(
    port,
    '/ws?serverId=synthetic&role=client&v=2',
    '',
    { optionalHeaders: false },
  );
  t.after(() => socket.destroy());
  assert.equal(response.status, 101);

  await reader.waitFor((buffer) => buffer.length >= expectedFrames.length);
  const observedFrames = reader.take(expectedFrames.length);
  assert.deepEqual(observedFrames, expectedFrames);
  assert.equal(observedFrames.subarray(revokedFrame.length, revokedFrame.length + 2)[0] & 0x0f, 0x8);
  assert.equal(observedFrames.readUInt16BE(revokedFrame.length + 2), 4001);
  assert.equal(observedFrames.subarray(revokedFrame.length + 4).toString('utf8'), 'device revoked');
});

test('WebSocket query rejects legacy, missing, duplicate, unknown, malformed, and oversized values', async (t) => {
  let upstreamUpgrades = 0;
  const upstream = await startUpstream((_req, res) => res.writeHead(404).end(), (_req, socket) => {
    upstreamUpgrades += 1;
    socket.destroy();
  });
  const { proxy, port } = await startProxy(upstream.port, { limits: { maxTargetBytes: 400 } });
  t.after(async () => {
    await closeProxy(proxy);
    await upstream.close();
  });

  const invalidTargets = [
    '/ws?serverId=synthetic&role=server&v=1',
    '/ws?serverId=synthetic&role=server',
    '/ws?serverId=synthetic&role=admin&v=2',
    '/ws?role=server&v=2',
    '/ws?serverId=synthetic&role=server&v=2&extra=x',
    '/ws?serverId=synthetic&serverId=other&role=server&v=2',
    '/ws?serverId=synthetic&role=server&v=2&connectionId=duplicate&connectionId=other',
    '/ws?serverId=synthetic&role=server&v=2&connectionId=bad%00id',
    '/ws?serverId=synthetic%ZZ&role=server&v=2',
    `/ws?serverId=${'s'.repeat(257)}&role=server&v=2`,
    `/ws?serverId=${'s'.repeat(200)}&role=server&v=2&connectionId=${'c'.repeat(200)}`,
  ];
  for (const target of invalidTargets) {
    const buffer = await rawExchange(port, websocketRequest(target));
    assert.equal(parseResponse(buffer).status, 400, target);
  }
  assert.equal(upstreamUpgrades, 0);
});

test('WebSocket upgrades with an HTTP body are rejected before contacting upstream', async (t) => {
  let upstreamUpgrades = 0;
  const upstream = await startUpstream((_req, res) => res.writeHead(404).end(), (_req, socket) => {
    upstreamUpgrades += 1;
    socket.destroy();
  });
  const { proxy, port } = await startProxy(upstream.port);
  t.after(async () => {
    await closeProxy(proxy);
    await upstream.close();
  });

  const target = '/ws?serverId=synthetic&role=server&v=2';
  const bodyRequests = [
    { headers: 'Content-Length: 1', body: 'x' },
    { headers: 'Transfer-Encoding: chunked', body: '1\r\nx\r\n0\r\n\r\n' },
  ];
  for (const { headers, body } of bodyRequests) {
    const response = await rawExchange(port, websocketRequest(target, headers) + body);
    assert.equal(parseResponse(response).status, 400, headers);
    assert.equal(upstreamUpgrades, 0, headers);
  }
});

test('bounded failures cover header, response, connection, and timeout limits', async (t) => {
  await t.test('oversized request headers return 431', async (st) => {
    const upstream = await startUpstream((_req, res) => res.end('ok'));
    const { proxy, port } = await startProxy(upstream.port);
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const response = await rawExchange(port, `GET /health HTTP/1.1\r\nHost: test\r\nX-Large: ${'x'.repeat(9 * 1024)}\r\n\r\n`);
    assert.equal(parseResponse(response).status, 431);
  });

  await t.test('oversized upstream response becomes a bounded 502', async (st) => {
    const upstream = await startUpstream((_req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('123456789012345');
    });
    const { proxy, port, logs } = await startProxy(upstream.port, { limits: { maxResponseBytes: 8 } });
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const response = await fetch(`http://127.0.0.1:${port}/health`);
    const body = await response.text();
    assert.equal(response.status, 502);
    assert.equal(body, 'upstream unavailable\n');
    assert.ok(body.length < 128);
    assert.ok(logs.some((entry) => entry.reason === 'response_limit'));
  });

  await t.test('oversized forwarded WebSocket headers are rejected before connecting upstream', async (st) => {
    let upstreamUpgrades = 0;
    const upstream = await startUpstream((_req, res) => res.end(), (_req, socket) => {
      upstreamUpgrades += 1;
      socket.destroy();
    });
    const { proxy, port } = await startProxy(upstream.port, { limits: { maxForwardedHeaderBytes: 16 } });
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const buffer = await rawExchange(port, websocketRequest(
      '/ws?serverId=synthetic&role=server&v=2',
      `Sec-WebSocket-Protocol: ${'p'.repeat(32)}`,
      { optionalHeaders: false },
    ));
    assert.equal(parseResponse(buffer).status, 400);
    assert.equal(upstreamUpgrades, 0);
  });

  await t.test('unavailable upstream returns a bounded 502 without raw error details', async (st) => {
    const reservation = createTcpServer();
    const upstreamPort = await listen(reservation);
    await new Promise((resolve) => reservation.close(resolve));
    const { proxy, port } = await startProxy(upstreamPort);
    st.after(() => closeProxy(proxy));
    const response = await fetch(`http://127.0.0.1:${port}/health`);
    const body = await response.text();
    assert.equal(response.status, 502);
    assert.equal(body, 'upstream unavailable\n');
    assert.equal(body.includes('ECONNREFUSED'), false);
  });

  await t.test('HTTP upstream timeout returns 502', async (st) => {
    const upstream = await startUpstream((_req, _res) => {});
    const { proxy, port } = await startProxy(upstream.port, { limits: { httpTimeoutMs: 80 } });
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const response = await fetch(`http://127.0.0.1:${port}/ready`);
    assert.equal(response.status, 502);
    assert.equal(await response.text(), 'upstream unavailable\n');
  });

  await t.test('connection limit refuses the second accepted socket', async (st) => {
    const upstream = await startUpstream((_req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('ok');
    });
    const { proxy, port } = await startProxy(upstream.port, { limits: { maxConnections: 1 } });
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const first = connect(port, '127.0.0.1');
    await once(first, 'connect');
    const firstReader = socketReader(first);
    first.write('GET /health HTTP/1.1\r\nHost: test\r\n\r\n');
    const firstBytes = await firstReader.waitFor((buffer) => buffer.includes(HEADER_END));
    const boundary = firstBytes.indexOf(HEADER_END);
    assert.equal(parseResponse(firstReader.take(boundary + HEADER_END.length)).status, 200);

    const secondResponse = await rawExchange(port, 'GET /health HTTP/1.1\r\nHost: test\r\n\r\n');
    assert.equal(parseResponse(secondResponse).status, 503);
    first.destroy();
  });

  await t.test('WebSocket idle timeout closes the tunnel', async (st) => {
    const upstream = await startUpstream((_req, res) => res.end(), (_req, socket) => {
      const accept = websocketAccept(WS_KEY);
      socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    });
    const { proxy, port } = await startProxy(upstream.port, { limits: { websocketIdleTimeoutMs: 80 } });
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const { socket, response } = await openWebSocket(port, '/ws?serverId=synthetic&role=server&v=2');
    assert.equal(response.status, 101);
    await waitForEvent(socket, 'close', 1_500);
  });

  await t.test('WebSocket handshake timeout returns a bounded 502', async (st) => {
    const upstream = await startUpstream((_req, res) => res.end(), () => {});
    const { proxy, port } = await startProxy(upstream.port, { limits: { handshakeTimeoutMs: 80 } });
    st.after(async () => { await closeProxy(proxy); await upstream.close(); });
    const { socket, reader, response } = await openWebSocket(port, '/ws?serverId=synthetic&role=server&v=2');
    st.after(() => socket.destroy());
    assert.equal(response.status, 502);
    const bodyLength = Number(response.headers.get('content-length'));
    await reader.waitFor((buffer) => buffer.length >= bodyLength);
    assert.equal(reader.take(bodyLength).toString(), 'upstream unavailable\n');
  });
});

test('SIGTERM and SIGINT stop the listener with exit code zero and release the port', async (t) => {
  for (const signal of ['SIGTERM', 'SIGINT']) {
    await t.test(signal, async (st) => {
      const reservation = createTcpServer();
      const port = await listen(reservation);
      await new Promise((resolve) => reservation.close(resolve));
      const child = spawn(process.execPath, [SERVER_FILE], {
        env: {
          ...process.env,
          LISTEN_HOST: '127.0.0.1',
          LISTEN_PORT: String(port),
          UPSTREAM_HOST: '127.0.0.1',
          UPSTREAM_PORT: '4402',
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      st.after(() => {
        if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      });
      let output = '';
      child.stdout.setEncoding('utf8');
      child.stderr.setEncoding('utf8');
      child.stdout.on('data', (chunk) => { output += chunk; });
      child.stderr.on('data', (chunk) => { output += chunk; });
      const ready = new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('proxy child did not report startup')), 2_000);
        const inspect = () => {
          if (output.includes('"event":"proxy_started"')) {
            clearTimeout(timeout);
            resolve();
          }
        };
        child.stdout.on('data', inspect);
        child.once('error', (error) => {
          clearTimeout(timeout);
          reject(error);
        });
        inspect();
      });
      await ready;
      const exited = waitForEvent(child, 'exit', 2_000);
      child.kill(signal);
      const [code, exitSignal] = await exited;
      assert.equal(code, 0, output);
      assert.equal(exitSignal, null);
      assert.ok(output.includes('"event":"proxy_stopped"'));

      const released = createTcpServer();
      await listen(released, '127.0.0.1', port);
      await new Promise((resolve) => released.close(resolve));
    });
  }
});

test('pinned relay integration on 4402', async (t) => {
  if (!await canConnect(4402)) {
    t.skip('127.0.0.1:4402 is not listening; do not start or modify the relay here');
    return;
  }

  const proxy = createProxy({ listenPort: 0, upstreamHost: '127.0.0.1', upstreamPort: 4402 });
  const proxyPort = (await proxy.listen()).port;
  t.after(() => closeProxy(proxy));

  for (const route of ['health', 'ready']) {
    const direct = await fetch(`http://127.0.0.1:4402/${route}`, { signal: AbortSignal.timeout(3_000) });
    const proxied = await fetch(`http://127.0.0.1:${proxyPort}/${route}`, { signal: AbortSignal.timeout(3_000) });
    assert.equal(proxied.status, direct.status, `/${route} status`);
    assert.equal(proxied.headers.get('content-type'), direct.headers.get('content-type'), `/${route} content-type`);
    assert.deepEqual(Buffer.from(await proxied.arrayBuffer()), Buffer.from(await direct.arrayBuffer()), `/${route} body`);
  }
  const metrics = await fetch(`http://127.0.0.1:${proxyPort}/metrics`);
  assert.equal(metrics.status, 404);
  const { socket, response } = await openWebSocket(
    proxyPort,
    '/ws?serverId=synthetic&role=server&v=2',
    '',
    { optionalHeaders: false },
  );
  t.after(() => socket.destroy());
  assert.equal(response.status, 101);
});

function websocketAccept(key) {
  return createHash('sha1').update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64');
}
