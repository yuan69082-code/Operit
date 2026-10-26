// Adapted from yuan69082-code/vio-live-frontend @ 1eaeef29c6e09156fa0350392f0acb95f9a17c96.
// Only module imports and local error types changed. See ../README.md.
const { request: httpRequest } = require('node:http');
const { TextDecoder } = require('node:util');

const { ApplicationError, ValidationError } = require('../errors.cjs');

const JSON_CONTENT_TYPES = new Set([
  'application/json',
  'application/json; charset=utf-8',
]);

class ContinuityTransportError extends ApplicationError {
  constructor(message, {
    transportCode,
    outcomeUnknown = false,
    httpStatus = null,
  }) {
    super(message, {
      code: 'continuity_transport_failure',
      statusCode: 503,
      details: { transportCode, outcomeUnknown, httpStatus },
    });
    this.transportCode = transportCode;
    this.outcomeUnknown = outcomeUnknown;
    this.httpStatus = httpStatus;
  }
}

function transportFailure(message, options) {
  return new ContinuityTransportError(message, options);
}

function requirePositiveInteger(value, field) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new ValidationError(`${field} must be a positive safe integer.`);
  }
  return value;
}

function normalizeBaseUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new ValidationError('Continuity Engine base URL is invalid.');
  }
  if (
    url.protocol !== 'http:'
    || url.hostname !== '127.0.0.1'
    || url.username
    || url.password
    || (url.pathname !== '/' && url.pathname !== '')
    || url.search
    || url.hash
  ) {
    throw new ValidationError(
      'Continuity Engine base URL must be a credential-free loopback HTTP origin.',
    );
  }
  return url.origin;
}

function strictJson(buffer) {
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
  } catch {
    throw transportFailure('Continuity Engine response is not valid UTF-8.', {
      transportCode: 'invalid_utf8',
      outcomeUnknown: true,
    });
  }
  try {
    const value = JSON.parse(text);
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      throw new TypeError('response is not an object');
    }
    return value;
  } catch {
    throw transportFailure('Continuity Engine response is not valid JSON.', {
      transportCode: 'invalid_json',
      outcomeUnknown: true,
    });
  }
}

function mapHttpFailure(statusCode) {
  if (statusCode === 401) {
    return transportFailure('Continuity Engine authentication failed.', {
      transportCode: 'unauthorized',
      httpStatus: statusCode,
    });
  }
  if (statusCode >= 500) {
    return transportFailure('Continuity Engine is unavailable.', {
      transportCode: 'engine_unavailable',
      outcomeUnknown: true,
      httpStatus: statusCode,
    });
  }
  if (statusCode === 408) {
    return transportFailure('Continuity Engine response outcome is unknown.', {
      transportCode: 'request_timeout',
      outcomeUnknown: true,
      httpStatus: statusCode,
    });
  }
  return transportFailure('Continuity Engine rejected the transport request.', {
    transportCode: 'http_error',
    httpStatus: statusCode,
  });
}

function createHttpContinuityIntegrationTransport({
  baseUrl,
  serviceToken,
  connectTimeoutMs,
  responseTimeoutMs,
  maxResponseBytes,
}) {
  const origin = normalizeBaseUrl(baseUrl);
  if (typeof serviceToken !== 'string' || serviceToken.length < 32) {
    throw new ValidationError('Continuity Engine service token must contain at least 32 characters.');
  }
  requirePositiveInteger(connectTimeoutMs, 'connectTimeoutMs');
  requirePositiveInteger(responseTimeoutMs, 'responseTimeoutMs');
  requirePositiveInteger(maxResponseBytes, 'maxResponseBytes');

  function exchange({ method, path, body = null, authorized = true, outcomeUnknown = false }) {
    return new Promise((resolve, reject) => {
      const target = new URL(path, `${origin}/`);
      const headers = {
        accept: 'application/json',
        connection: 'close',
      };
      if (authorized) headers.authorization = `Bearer ${serviceToken}`;
      if (body !== null) {
        headers['content-type'] = 'application/json; charset=utf-8';
        headers['content-length'] = String(body.length);
      }

      let settled = false;
      let connected = false;
      let connectTimer;
      let responseTimer;

      const finish = (operation, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(connectTimer);
        clearTimeout(responseTimer);
        operation(value);
      };

      const request = httpRequest({
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port,
        path: `${target.pathname}${target.search}`,
        method,
        headers,
        agent: false,
      });

      connectTimer = setTimeout(() => {
        request.destroy(transportFailure('Continuity Engine connection timed out.', {
          transportCode: 'connect_timeout',
        }));
      }, connectTimeoutMs);

      request.on('socket', (socket) => {
        const onConnected = () => {
          if (connected || settled) return;
          connected = true;
          clearTimeout(connectTimer);
          responseTimer = setTimeout(() => {
            request.destroy(transportFailure('Continuity Engine response timed out.', {
              transportCode: 'response_timeout',
              outcomeUnknown,
            }));
          }, responseTimeoutMs);
        };
        if (socket.connecting) socket.once('connect', onConnected);
        else onConnected();
      });

      request.on('response', (response) => {
        const chunks = [];
        let bytes = 0;
        response.on('data', (chunk) => {
          bytes += chunk.length;
          if (bytes > maxResponseBytes) {
            response.destroy(transportFailure('Continuity Engine response is too large.', {
              transportCode: 'response_too_large',
              outcomeUnknown,
              httpStatus: response.statusCode ?? null,
            }));
            return;
          }
          chunks.push(chunk);
        });
        response.on('error', (error) => finish(reject, error));
        response.on('end', () => {
          if (settled) return;
          const contentType = String(response.headers['content-type'] ?? '').trim().toLowerCase();
          if (!JSON_CONTENT_TYPES.has(contentType)) {
            finish(reject, transportFailure('Continuity Engine response Content-Type is invalid.', {
              transportCode: 'invalid_content_type',
              outcomeUnknown,
              httpStatus: response.statusCode ?? null,
            }));
            return;
          }
          let payload;
          try {
            payload = strictJson(Buffer.concat(chunks));
          } catch (error) {
            if (error instanceof ContinuityTransportError) {
              error.outcomeUnknown = outcomeUnknown;
              error.httpStatus = response.statusCode ?? null;
              error.details.outcomeUnknown = outcomeUnknown;
              error.details.httpStatus = response.statusCode ?? null;
            }
            finish(reject, error);
            return;
          }
          finish(resolve, {
            statusCode: response.statusCode ?? 0,
            payload,
          });
        });
      });

      request.on('error', (error) => {
        if (error instanceof ContinuityTransportError) {
          finish(reject, error);
          return;
        }
        finish(reject, transportFailure('Continuity Engine connection failed.', {
          transportCode: 'connection_failed',
          outcomeUnknown: connected && outcomeUnknown,
        }));
      });

      if (body !== null) request.write(body);
      request.end();
    });
  }

  return Object.freeze({
    mode: 'local-http',
    testOnly: false,
    async submitCanonicalRequest(canonicalBody) {
      if (!Buffer.isBuffer(canonicalBody)) {
        throw new ValidationError('canonicalBody must be a Buffer.');
      }
      const response = await exchange({
        method: 'POST',
        path: '/internal/v1/continuity/interactions',
        body: canonicalBody,
        outcomeUnknown: true,
      });
      if (response.statusCode !== 200) throw mapHttpFailure(response.statusCode);
      return response;
    },
    async submitCapabilityResult(canonicalBody) {
      if (!Buffer.isBuffer(canonicalBody)) {
        throw new ValidationError('canonicalBody must be a Buffer.');
      }
      const response = await exchange({
        method: 'POST',
        path: '/internal/v1/continuity/capability-results',
        body: canonicalBody,
        outcomeUnknown: true,
      });
      if (response.statusCode !== 200) throw mapHttpFailure(response.statusCode);
      return response;
    },
    async queryRequest(requestId) {
      if (typeof requestId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(requestId)) {
        throw new ValidationError('requestId is not safe for the Engine query path.');
      }
      const response = await exchange({
        method: 'GET',
        path: `/internal/v1/continuity/requests/${encodeURIComponent(requestId)}`,
      });
      if (response.statusCode === 404) {
        if (response.payload.error !== 'not_found' || Object.keys(response.payload).length !== 1) {
          throw transportFailure('Continuity Engine not-found response is invalid.', {
            transportCode: 'invalid_not_found',
            httpStatus: 404,
          });
        }
        return { statusCode: 404, kind: 'not_found', payload: response.payload };
      }
      if (response.statusCode !== 200) throw mapHttpFailure(response.statusCode);
      return { ...response, kind: 'query' };
    },
    async checkReady() {
      const response = await exchange({
        method: 'GET',
        path: '/health/ready',
        authorized: false,
      });
      return response.statusCode === 200
        && Object.keys(response.payload).length === 1
        && response.payload.status === 'ready';
    },
  });
}


module.exports = { ContinuityTransportError, createHttpContinuityIntegrationTransport };
