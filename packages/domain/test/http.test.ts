import assert from 'node:assert/strict';
import test from 'node:test';
import {SessionTransport} from '../src/http.ts';
import {session} from './fixtures.ts';

const ok = (value: unknown) => ({ok: true, status: 200, json: async () => value});
const unauthorized = () => ({ok: false, status: 401, json: async () => ({detail: {code: 'expired', message: 'Expired'}})});

test('guest sessions stay in memory and notify persistence hooks', async () => {
  const changes: unknown[] = [];
  const calls: unknown[] = [];
  const transport = new SessionTransport({baseUrl: 'https://example.test/', fetch: async (url, options) => {
    calls.push({url, options});
    return ok(session());
  }}, value => changes.push(value));
  assert.equal(transport.getSession(), null);
  await transport.guest('  River  ');
  assert.deepEqual(transport.getSession(), session());
  assert.equal(calls[0].url, 'https://example.test/v1/auth/guest');
  assert.deepEqual(JSON.parse(calls[0].options.body), {display_name: 'River'});
  assert.equal(calls[0].options.headers.Authorization, undefined);
  transport.clear();
  assert.equal(changes.at(-1), null);
});

test('authenticated requests rotate once and retry with the replacement token', async () => {
  const calls: {url: string; options: RequestInit}[] = [];
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async (url, options) => {
    calls.push({url, options});
    if (url.endsWith('/auth/refresh')) return ok(session('-new'));
    return calls.length === 1 ? unauthorized() : ok({done: true});
  }}, () => {});
  transport.restoreSession(session());
  assert.deepEqual(await transport.request('GET', '/v1/rooms/test/snapshot'), {done: true});
  assert.equal(calls.length, 3);
  assert.equal(calls[0].options.headers.Authorization, 'Bearer test-access');
  assert.equal(calls[1].options.headers.Authorization, undefined);
  assert.deepEqual(JSON.parse(calls[1].options.body), {refresh_token: 'test-refresh'});
  assert.equal(calls[2].options.headers.Authorization, 'Bearer test-access-new');
});

test('concurrent unauthorized requests share refresh rotation', async () => {
  let refreshes = 0;
  let oldRequests = 0;
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async (url, options) => {
    if (url.endsWith('/auth/refresh')) {
      refreshes += 1;
      await new Promise(resolve => setTimeout(resolve, 2));
      return ok(session('-new'));
    }
    if (options.headers.Authorization === 'Bearer test-access') {
      oldRequests += 1;
      return unauthorized();
    }
    return ok({done: true});
  }}, () => {});
  transport.restoreSession(session());
  await Promise.all([transport.request('GET', '/first'), transport.request('GET', '/second')]);
  assert.equal(oldRequests, 2);
  assert.equal(refreshes, 1);
});

test('late unauthorized replies reuse an already rotated access token', async () => {
  let refreshes = 0;
  let release: (() => void) | undefined;
  const barrier = new Promise<void>(resolve => {release = resolve;});
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async (url, options) => {
    if (url.endsWith('/auth/refresh')) {
      refreshes += 1;
      return ok(session('-new'));
    }
    if (options.headers.Authorization === 'Bearer test-access') {
      if (url.endsWith('/late')) await barrier;
      return unauthorized();
    }
    return ok({done: true});
  }}, () => {});
  transport.restoreSession(session());
  const late = transport.request('GET', '/late');
  await transport.request('GET', '/first');
  release!();
  await late;
  assert.equal(refreshes, 1);
});

test('rejected refresh clears the session without a request loop', async () => {
  let calls = 0;
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async () => {
    calls += 1;
    return unauthorized();
  }}, () => {});
  transport.restoreSession(session());
  await assert.rejects(transport.request('GET', '/room'), {code: 'session_expired'});
  assert.equal(calls, 2);
  assert.equal(transport.getSession(), null);
});

test('a second unauthorized response ends the session after one retry', async () => {
  let calls = 0;
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async url => {
    calls += 1;
    return url.endsWith('/auth/refresh') ? ok(session('-new')) : unauthorized();
  }}, () => {});
  transport.restoreSession(session());
  await assert.rejects(transport.request('GET', '/room'), {code: 'session_expired'});
  assert.equal(calls, 3);
  assert.equal(transport.getSession(), null);
});

test('a non-JSON unauthorized reply still starts token refresh', async () => {
  let calls = 0;
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async url => {
    calls += 1;
    if (url.endsWith('/auth/refresh')) return ok(session('-new'));
    return calls === 1
      ? {ok: false, status: 401, json: async () => {throw new SyntaxError();}}
      : ok({done: true});
  }}, () => {});
  transport.restoreSession(session());
  await transport.request('GET', '/room');
  assert.equal(calls, 3);
});

test('temporary refresh failure preserves the session', async () => {
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async url => {
    if (url.endsWith('/auth/refresh')) throw new Error('network has a sensitive URL');
    return unauthorized();
  }}, () => {});
  transport.restoreSession(session());
  await assert.rejects(transport.request('GET', '/room'), error => {
    assert.equal(error.code, 'network_error');
    assert.equal(error.message.includes('sensitive'), false);
    return true;
  });
  assert.deepEqual(transport.getSession(), session());
});

test('requests time out and abort even if the fetch adapter ignores cancellation', async () => {
  let signal: AbortSignal | null | undefined;
  const transport = new SessionTransport({baseUrl: 'https://example.test', requestTimeoutMs: 5, fetch: async (_, options) => {
    signal = options.signal;
    return new Promise(() => {});
  }}, () => {});
  transport.restoreSession(session());
  await assert.rejects(transport.request('GET', '/room'), {code: 'request_timeout'});
  assert.equal(signal?.aborted, true);
});

test('signing out cancels requests and prevents a late response restoring state', async () => {
  let resolve: ((response: ReturnType<typeof ok>) => void) | undefined;
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: () => new Promise(done => {resolve = done;})}, () => {});
  const pending = transport.guest('River');
  transport.clear();
  await assert.rejects(pending, {code: 'cancelled'});
  resolve!(ok(session()));
  await Promise.resolve();
  assert.equal(transport.getSession(), null);
});

test('server addresses require TLS unless an explicit debug override is set', () => {
  const valid = new SessionTransport({baseUrl: 'http://127.0.0.1:8000', allowInsecureHttp: true}, () => {});
  assert.equal(valid.baseUrl, 'http://127.0.0.1:8000');
  for (const baseUrl of ['http://example.test', 'https://user:pass@example.test', 'https://example.test?token=secret', 'wss://example.test', 'bad']) {
    assert.throws(() => new SessionTransport({baseUrl}, () => {}), {code: 'invalid_url'});
  }
});

test('refresh cannot replace the authenticated user', async () => {
  const transport = new SessionTransport({baseUrl: 'https://example.test', fetch: async url => {
    return url.endsWith('/auth/refresh')
      ? ok({...session('-new'), user: {...session().user, id: '55555555-5555-4555-8555-555555555555'}})
      : unauthorized();
  }}, () => {});
  transport.restoreSession(session());
  await assert.rejects(transport.request('GET', '/room'), {code: 'invalid_response'});
  assert.deepEqual(transport.getSession(), session());
});
