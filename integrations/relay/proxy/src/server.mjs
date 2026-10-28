import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer, request as httpRequest } from 'node:http';
import { isIP } from 'node:net';
import { pathToFileURL } from 'node:url';

export const DEFAULT_LIMITS = Object.freeze({
  maxHeaderBytes: 8 * 1024,
  maxTargetBytes: 2 * 1024,
  maxConnections: 64,
  connectTimeoutMs: 2_000,
  httpTimeoutMs: 5_000,
  handshakeTimeoutMs: 3_000,
  websocketIdleTimeoutMs: 60_000,
  maxResponseBytes: 64 * 1024,
  maxForwardedHeaderBytes: 2 * 1024,
});

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const ERROR_BODY = Buffer.from('upstream unavailable\n');
const STATUS_TEXT = new Map([
  [400, 'Bad Request'],
  [404, 'Not Found'],
  [405, 'Method Not Allowed'],
  [426, 'Upgrade Required'],
  [431, 'Request Header Fields Too Large'],
  [502, 'Bad Gateway'],
  [503, 'Service Unavailable'],
]);
const SAFE_REASONS = new Set([
  'method', 'path', 'query', 'upgrade_required', 'upgrade_invalid',
  'request_body', 'limit', 'header_limit', 'upstream_unavailable',
  'upstream_timeout', 'upstream_response', 'response_limit',
  'handshake_invalid', 'client_closed', 'idle_timeout', 'malformed',
]);

function isLoopbackAddress(host) {
  const family = isIP(host);
  if (family === 4) return Number(host.split('.')[0]) === 127;
  return family === 6 && host.toLowerCase() === '::1';
}

function parsePort(value, name, allowZero) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < (allowZero ? 0 : 1) || port > 65_535) {
    throw new TypeError(`${name} must be an integer port`);
  }
  return port;
}

function hostPort(host, port) {
  return isIP(host) === 6 ? `[${host}]:${port}` : `${host}:${port}`;
}

export function readConfig(env = process.env) {
  const listenHost = env.LISTEN_HOST ?? '127.0.0.1';
  const upstreamHost = env.UPSTREAM_HOST ?? '127.0.0.1';
  if (listenHost !== '127.0.0.1') {
    throw new TypeError('LISTEN_HOST must be 127.0.0.1');
  }
  if (!isLoopbackAddress(upstreamHost)) {
    throw new TypeError('UPSTREAM_HOST must be a loopback IP literal');
  }
  return {
    listenHost,
    listenPort: parsePort(env.LISTEN_PORT ?? '4400', 'LISTEN_PORT', true),
    upstreamHost,
    upstreamPort: parsePort(env.UPSTREAM_PORT ?? '4402', 'UPSTREAM_PORT', false),
  };
}

function defaultLogger(event, fields) {
  process.stdout.write(`${JSON.stringify({ time: new Date().toISOString(), event, ...fields })}\n`);
}

function safeLog(logger, event, fields = {}) {
  const safeFields = {};
  if (Number.isInteger(fields.status)) safeFields.status = fields.status;
  if (Number.isInteger(fields.durationMs)) safeFields.durationMs = Math.max(0, Math.min(fields.durationMs, 600_000));
  if (fields.route === 'health' || fields.route === 'ready' || fields.route === 'ws') safeFields.route = fields.route;
  if (typeof fields.reason === 'string' && SAFE_REASONS.has(fields.reason)) safeFields.reason = fields.reason;
  if (Number.isInteger(fields.port)) safeFields.port = fields.port;
  try {
    logger(event, safeFields);
  } catch {
    // Logging must not change the proxy's response or tunnel behavior.
  }
}

function responseBody(status) {
  if (status === 404) return 'not found\n';
  if (status === 405) return 'method not allowed\n';
  if (status === 426) return 'websocket upgrade required\n';
  if (status === 431) return 'request headers too large\n';
  if (status === 503) return 'proxy connection limit reached\n';
  if (status === 502) return ERROR_BODY;
  return 'bad request\n';
}

function writeHttpError(res, status, extraHeaders = {}) {
  const body = responseBody(status);
  res.writeHead(status, {
    'content-type': 'text/plain; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
    ...extraHeaders,
  });
  res.end(body);
}

function writeSocketError(socket, status, extraHeaders = {}) {
  if (socket.destroyed || !socket.writable) return;
  const body = Buffer.from(responseBody(status));
  const headers = [
    `HTTP/1.1 ${status} ${STATUS_TEXT.get(status) ?? 'Bad Request'}`,
    'Content-Type: text/plain; charset=utf-8',
    'Cache-Control: no-store',
    'Connection: close',
    `Content-Length: ${body.length}`,
    ...Object.entries(extraHeaders).map(([key, value]) => `${key}: ${value}`),
    '',
    '',
  ].join('\r\n');
  socket.end(Buffer.concat([Buffer.from(headers, 'ascii'), body]), () => socket.destroy());
}

function isRequestBodyPresent(req) {
  if (req.headers['transfer-encoding']) return true;
  const length = req.headers['content-length'];
  return length !== undefined && Number(length) !== 0;
}

function routeFromTarget(target) {
  if (target === '/health') return 'health';
  if (target === '/ready') return 'ready';
  return null;
}

function isWebSocketTarget(target) {
  return target === '/ws' || target?.startsWith('/ws?');
}

function hasUpgradeIntent(req) {
  return rawHeaderValues(req, 'upgrade').length > 0
    || headerList(req, 'connection').some((value) => value.toLowerCase() === 'upgrade');
}

function decodeQueryPart(value) {
  return decodeURIComponent(value.replaceAll('+', ' '));
}

function parseWsQuery(target, maxTargetBytes) {
  if (typeof target !== 'string' || Buffer.byteLength(target, 'utf8') > maxTargetBytes) {
    return { error: 'query' };
  }
  if (!target.startsWith('/ws?') || target.includes('#')) return { error: 'query' };
  const rawQuery = target.slice(4);
  if (!rawQuery) return { error: 'query' };

  const values = new Map();
  try {
    for (const pair of rawQuery.split('&')) {
      const equals = pair.indexOf('=');
      if (equals <= 0) return { error: 'query' };
      const key = decodeQueryPart(pair.slice(0, equals));
      const value = decodeQueryPart(pair.slice(equals + 1));
      if (values.has(key)) return { error: 'query' };
      values.set(key, value);
    }
  } catch {
    return { error: 'query' };
  }

  const allowed = new Set(['serverId', 'role', 'v', 'connectionId']);
  if ([...values.keys()].some((key) => !allowed.has(key))) return { error: 'query' };
  const serverId = values.get('serverId');
  const role = values.get('role');
  const version = values.get('v');
  const connectionId = values.get('connectionId');
  const validId = (value) => typeof value === 'string'
    && Buffer.byteLength(value, 'utf8') >= 1
    && Buffer.byteLength(value, 'utf8') <= 256
    && !/\p{Cc}/u.test(value);

  if (!validId(serverId) || (role !== 'server' && role !== 'client') || version !== '2') {
    return { error: 'query' };
  }
  if (connectionId !== undefined && !validId(connectionId)) return { error: 'query' };

  const canonical = new URLSearchParams();
  canonical.set('serverId', serverId);
  canonical.set('role', role);
  canonical.set('v', '2');
  if (connectionId !== undefined) canonical.set('connectionId', connectionId);
  return { path: `/ws?${canonical.toString()}` };
}

function rawHeaderValues(req, name) {
  const values = [];
  for (let i = 0; i < req.rawHeaders.length; i += 2) {
    if (req.rawHeaders[i].toLowerCase() === name.toLowerCase()) values.push(req.rawHeaders[i + 1]);
  }
  return values;
}

function headerList(req, name) {
  return rawHeaderValues(req, name).flatMap((value) => value.split(',')).map((value) => value.trim()).filter(Boolean);
}

function singleHeader(req, name) {
  const values = rawHeaderValues(req, name);
  return values.length === 1 ? values[0].trim() : null;
}

function validateUpgradeHeaders(req, maxForwardedHeaderBytes) {
  if (req.httpVersion !== '1.1') return null;
  if (!headerList(req, 'connection').some((value) => value.toLowerCase() === 'upgrade')) return null;
  if (singleHeader(req, 'upgrade')?.toLowerCase() !== 'websocket') return null;
  const key = singleHeader(req, 'sec-websocket-key');
  const version = singleHeader(req, 'sec-websocket-version');
  if (!key || version !== '13' || !/^[A-Za-z0-9+/]{22}==$/.test(key)) return null;
  const decodedKey = Buffer.from(key, 'base64');
  if (decodedKey.length !== 16 || decodedKey.toString('base64') !== key) return null;

  const protocolHeaders = rawHeaderValues(req, 'sec-websocket-protocol');
  const extensionHeaders = rawHeaderValues(req, 'sec-websocket-extensions');
  const protocol = protocolHeaders.length ? protocolHeaders.join(', ') : undefined;
  const extensions = extensionHeaders.length ? extensionHeaders.join(', ') : undefined;
  if (protocol !== undefined && Buffer.byteLength(protocol) > maxForwardedHeaderBytes) return null;
  if (extensions !== undefined && Buffer.byteLength(extensions) > maxForwardedHeaderBytes) return null;
  if (protocol !== undefined && protocol.split(',').some((item) => !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(item.trim()))) return null;
  return { key, protocol, extensions };
}

function responseHeaderValues(response, name) {
  const values = [];
  for (let i = 0; i < response.rawHeaders.length; i += 2) {
    if (response.rawHeaders[i].toLowerCase() === name.toLowerCase()) values.push(response.rawHeaders[i + 1]);
  }
  return values;
}

function expectedAccept(key) {
  return createHash('sha1').update(key + WS_GUID).digest('base64');
}

function equalAscii(left, right) {
  const a = Buffer.from(left, 'ascii');
  const b = Buffer.from(right, 'ascii');
  return a.length === b.length && timingSafeEqual(a, b);
}

function makeUpgradeResponse(response, handshake) {
  if (response.statusCode !== 101) return null;
  const accepts = responseHeaderValues(response, 'sec-websocket-accept');
  if (accepts.length !== 1 || !equalAscii(accepts[0], expectedAccept(handshake.key))) return null;

  const lines = [
    'HTTP/1.1 101 Switching Protocols',
    'Upgrade: websocket',
    'Connection: Upgrade',
    `Sec-WebSocket-Accept: ${accepts[0]}`,
  ];
  const selectedProtocols = responseHeaderValues(response, 'sec-websocket-protocol');
  if (selectedProtocols.length > 1) return null;
  if (selectedProtocols.length === 1) {
    const offered = (handshake.protocol ?? '').split(',').map((value) => value.trim());
    if (!offered.includes(selectedProtocols[0])) return null;
    lines.push(`Sec-WebSocket-Protocol: ${selectedProtocols[0]}`);
  }
  const selectedExtensions = responseHeaderValues(response, 'sec-websocket-extensions');
  if (selectedExtensions.length > 1 || (selectedExtensions.length && !handshake.extensions)) return null;
  if (selectedExtensions.length === 1) lines.push(`Sec-WebSocket-Extensions: ${selectedExtensions[0]}`);
  return Buffer.from(`${lines.join('\r\n')}\r\n\r\n`, 'ascii');
}

function validateLimits(limits) {
  for (const [name, value] of Object.entries(limits)) {
    if (!Number.isInteger(value) || value < 1) throw new TypeError(`${name} must be a positive integer`);
  }
  return limits;
}

function armConnectTimeout(request, timeoutMs, onTimeout) {
  const timer = setTimeout(onTimeout, timeoutMs);
  timer.unref?.();
  request.once('socket', (socket) => {
    if (socket.connecting) socket.once('connect', () => clearTimeout(timer));
    else clearTimeout(timer);
  });
  return () => clearTimeout(timer);
}

export function createProxy(options = {}) {
  const listenHost = options.listenHost ?? '127.0.0.1';
  const listenPort = options.listenPort ?? 4400;
  const upstreamHost = options.upstreamHost ?? '127.0.0.1';
  const upstreamPort = options.upstreamPort ?? 4402;
  if (listenHost !== '127.0.0.1') throw new TypeError('listenHost must be 127.0.0.1');
  if (!isLoopbackAddress(upstreamHost)) throw new TypeError('upstreamHost must be a loopback IP literal');
  parsePort(listenPort, 'listenPort', true);
  parsePort(upstreamPort, 'upstreamPort', false);
  const limits = validateLimits({ ...DEFAULT_LIMITS, ...options.limits });
  const logger = options.logger ?? defaultLogger;
  const sockets = new Set();
  const activeClients = new Set();
  let stopping = false;

  const server = createServer({
    maxHeaderSize: limits.maxHeaderBytes,
    requestTimeout: limits.httpTimeoutMs,
    headersTimeout: Math.min(limits.httpTimeoutMs, 10_000),
    keepAliveTimeout: 1_000,
  }, (req, res) => {
    const startedAt = Date.now();
    const route = routeFromTarget(req.url);
    const routeName = route ?? (isWebSocketTarget(req.url) ? 'ws' : undefined);

    if (req.method !== 'GET') {
      req.resume();
      writeHttpError(res, 405, { allow: 'GET' });
      safeLog(logger, 'request_rejected', { route: routeName, reason: 'method', status: 405 });
      return;
    }
    if (isRequestBodyPresent(req)) {
      req.resume();
      writeHttpError(res, 400);
      safeLog(logger, 'request_rejected', { route: routeName, reason: 'request_body', status: 400 });
      return;
    }
    if (route) {
      proxyHttp(req, res, route, startedAt);
      return;
    }
    if (isWebSocketTarget(req.url)) {
      const status = hasUpgradeIntent(req) ? 400 : 426;
      writeHttpError(res, status, status === 426 ? { upgrade: 'websocket' } : {});
      safeLog(logger, 'request_rejected', {
        route: 'ws', reason: status === 426 ? 'upgrade_required' : 'upgrade_invalid', status,
      });
      return;
    }
    writeHttpError(res, 404);
    safeLog(logger, 'request_rejected', { reason: 'path', status: 404 });
  });

  function trackSocket(socket) {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  }

  function proxyHttp(req, res, route, startedAt) {
    const targetPath = `/${route}`;
    let finished = false;
    let timeout;
    let clearConnectTimeout = () => {};
    let upstream;
    const finishError = (reason) => {
      if (finished) return;
      finished = true;
      clearTimeout(timeout);
      clearConnectTimeout();
      upstream?.destroy();
      if (!res.destroyed && !res.writableEnded) writeHttpError(res, 502);
      safeLog(logger, 'http_proxy_failed', { route, reason, status: 502, durationMs: Date.now() - startedAt });
    };

    timeout = setTimeout(() => finishError('upstream_timeout'), limits.httpTimeoutMs);
    timeout.unref?.();
    upstream = httpRequest({
      hostname: upstreamHost,
      port: upstreamPort,
      method: 'GET',
      path: targetPath,
      agent: false,
      maxHeaderSize: limits.maxHeaderBytes,
      headers: { host: hostPort(upstreamHost, upstreamPort), 'accept-encoding': 'identity', connection: 'close' },
    }, (upstreamResponse) => {
      const chunks = [];
      let size = 0;
      upstreamResponse.on('data', (chunk) => {
        if (finished) return;
        size += chunk.length;
        if (size > limits.maxResponseBytes) {
          finishError('response_limit');
          return;
        }
        chunks.push(chunk);
      });
      upstreamResponse.once('aborted', () => finishError('upstream_unavailable'));
      upstreamResponse.once('error', () => finishError('upstream_unavailable'));
      upstreamResponse.once('end', () => {
        if (finished) return;
        finished = true;
        clearTimeout(timeout);
        clearConnectTimeout();
        const body = Buffer.concat(chunks, size);
        const headers = { 'content-length': body.length };
        if (typeof upstreamResponse.headers['content-type'] === 'string') {
          headers['content-type'] = upstreamResponse.headers['content-type'];
        }
        if (typeof upstreamResponse.headers['content-encoding'] === 'string') {
          headers['content-encoding'] = upstreamResponse.headers['content-encoding'];
        }
        res.writeHead(upstreamResponse.statusCode ?? 502, headers);
        res.end(body);
        safeLog(logger, 'http_proxy', { route, status: upstreamResponse.statusCode ?? 502, durationMs: Date.now() - startedAt });
      });
    });
    clearConnectTimeout = armConnectTimeout(upstream, limits.connectTimeoutMs, () => finishError('upstream_timeout'));

    upstream.once('upgrade', (_response, socket) => {
      socket.destroy();
      finishError('upstream_response');
    });
    upstream.once('error', () => finishError('upstream_unavailable'));
    res.once('close', () => {
      if (finished) return;
      finished = true;
      clearTimeout(timeout);
      clearConnectTimeout();
      upstream.destroy();
    });
    upstream.end();
  }

  function handleUpgrade(req, clientSocket, clientHead) {
    const startedAt = Date.now();
    const fail = (status, reason) => {
      writeSocketError(clientSocket, status);
      safeLog(logger, 'request_rejected', { route: 'ws', reason, status, durationMs: Date.now() - startedAt });
    };

    if (req.method !== 'GET') return fail(405, 'method');
    if (!isWebSocketTarget(req.url)) return fail(404, 'path');
    const parsedQuery = parseWsQuery(req.url, limits.maxTargetBytes);
    if (parsedQuery.error) return fail(400, 'query');
    const handshake = validateUpgradeHeaders(req, limits.maxForwardedHeaderBytes);
    if (!handshake) return fail(400, 'upgrade_invalid');
    if (stopping) return fail(503, 'limit');

    const headers = {
      host: hostPort(upstreamHost, upstreamPort),
      connection: 'Upgrade',
      upgrade: 'websocket',
      'sec-websocket-key': handshake.key,
      'sec-websocket-version': '13',
    };
    if (handshake.protocol !== undefined) headers['sec-websocket-protocol'] = handshake.protocol;
    if (handshake.extensions !== undefined) headers['sec-websocket-extensions'] = handshake.extensions;

    let finished = false;
    let timeout;
    let clearConnectTimeout = () => {};
    let upstream;
    const failUpstream = (reason) => {
      if (finished) return;
      finished = true;
      clearTimeout(timeout);
      clearConnectTimeout();
      upstream?.destroy();
      if (!clientSocket.destroyed) writeSocketError(clientSocket, 502);
      safeLog(logger, 'ws_upgrade_failed', { route: 'ws', reason, status: 502, durationMs: Date.now() - startedAt });
    };

    timeout = setTimeout(() => failUpstream('upstream_timeout'), limits.handshakeTimeoutMs);
    timeout.unref?.();
    upstream = httpRequest({
      hostname: upstreamHost,
      port: upstreamPort,
      method: 'GET',
      path: parsedQuery.path,
      agent: false,
      maxHeaderSize: limits.maxHeaderBytes,
      headers,
    });
    clearConnectTimeout = armConnectTimeout(upstream, limits.connectTimeoutMs, () => failUpstream('upstream_timeout'));
    upstream.once('response', (response) => {
      response.resume();
      failUpstream('upstream_response');
    });
    upstream.once('upgrade', (response, upstreamSocket, upstreamHead) => {
      const responseHead = makeUpgradeResponse(response, handshake);
      if (!responseHead) {
        upstreamSocket.destroy();
        failUpstream('handshake_invalid');
        return;
      }
      if (finished) {
        upstreamSocket.destroy();
        return;
      }
      finished = true;
      clearTimeout(timeout);
      clearConnectTimeout();
      trackSocket(upstreamSocket);
      clientSocket.write(responseHead);
      if (upstreamHead.length) clientSocket.write(upstreamHead);
      if (clientHead.length) upstreamSocket.write(clientHead);

      let tunnelClosed = false;
      const closePair = (reason) => {
        if (tunnelClosed) return;
        tunnelClosed = true;
        if (reason) safeLog(logger, 'ws_closed', { route: 'ws', reason, durationMs: Date.now() - startedAt });
        clientSocket.destroy();
        upstreamSocket.destroy();
      };
      clientSocket.setTimeout(limits.websocketIdleTimeoutMs, () => closePair('idle_timeout'));
      upstreamSocket.setTimeout(limits.websocketIdleTimeoutMs, () => closePair('idle_timeout'));
      clientSocket.once('error', () => closePair('client_closed'));
      upstreamSocket.once('error', () => closePair('upstream_unavailable'));
      clientSocket.once('close', () => {
        if (!tunnelClosed) {
          tunnelClosed = true;
          upstreamSocket.destroy();
        }
      });
      upstreamSocket.once('close', () => {
        if (!tunnelClosed) {
          tunnelClosed = true;
          clientSocket.destroy();
        }
      });
      clientSocket.pipe(upstreamSocket);
      upstreamSocket.pipe(clientSocket);
      safeLog(logger, 'ws_upgrade', { route: 'ws', status: 101, durationMs: Date.now() - startedAt });
    });
    upstream.once('error', () => failUpstream('upstream_unavailable'));
    clientSocket.once('close', () => {
      if (finished) return;
      finished = true;
      clearTimeout(timeout);
      clearConnectTimeout();
      upstream.destroy();
      safeLog(logger, 'ws_upgrade_failed', { route: 'ws', reason: 'client_closed', durationMs: Date.now() - startedAt });
    });
    upstream.end();
  }

  server.on('connection', (socket) => {
    if (stopping || activeClients.size >= limits.maxConnections) {
      writeSocketError(socket, 503);
      safeLog(logger, 'connection_rejected', { reason: 'limit', status: 503 });
      return;
    }
    activeClients.add(socket);
    trackSocket(socket);
    socket.once('close', () => activeClients.delete(socket));
  });

  server.on('clientError', (error, socket) => {
    const status = error.code === 'HPE_HEADER_OVERFLOW' ? 431 : 400;
    writeSocketError(socket, status);
    safeLog(logger, 'request_rejected', { reason: status === 431 ? 'header_limit' : 'malformed', status });
  });

  server.on('upgrade', handleUpgrade);

  return {
    server,
    address: () => server.address(),
    listen: () => new Promise((resolve, reject) => {
      const onError = (error) => {
        server.off('listening', onListening);
        reject(error);
      };
      const onListening = () => {
        server.off('error', onError);
        const address = server.address();
        safeLog(logger, 'proxy_started', { port: typeof address === 'object' && address ? address.port : listenPort });
        resolve(address);
      };
      server.once('error', onError);
      server.once('listening', onListening);
      server.listen(listenPort, listenHost);
    }),
    close: async () => {
      if (stopping) return;
      stopping = true;
      safeLog(logger, 'proxy_stopping');
      const closeServer = server.listening
        ? new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
        : Promise.resolve();
      for (const socket of sockets) socket.destroy();
      await closeServer;
      safeLog(logger, 'proxy_stopped');
    },
  };
}

async function main() {
  const proxy = createProxy(readConfig());
  let stopping = false;
  let serverStarted = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    try {
      if (serverStarted || proxy.server.listening) await proxy.close();
      process.exitCode = 0;
    } catch {
      process.exitCode = 1;
    }
  };
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
  await proxy.listen();
  serverStarted = true;
  if (stopping) await proxy.close();
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(() => {
    process.stderr.write('proxy startup failed\n');
    process.exitCode = 1;
  });
}
