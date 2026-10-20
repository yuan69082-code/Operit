const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { EngineWindow } = require('../src/engine/window.cjs');
const { canonicalizeJson, sha256Hash } = require('../src/engine/vendor/canonical-json.cjs');
const { createSharedServer } = require('../src/server.cjs');

function directory(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operit-engine-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
function enabled(window, grants = ['messages']) {
  window.configure({ enabled: true, baseUrl: 'http://127.0.0.1:8766', serviceToken: 'unit-test-token-'.repeat(4) });
  return window.bind('shared', { subjectId: 'actual-subject', enabled: true, grants });
}
function input(binding, id = 'event-1', kind = 'message.user') {
  return { id, bindingKey: binding.key, bindingId: binding.id, conversationId: 'chat-1', kind,
    occurredAt: '2026-10-20T00:00:00.000Z', payload: { text: '只发送用户原话', name: '园园' } };
}
function receipt(event, status = 'completed', finalExpression = '主体的表达') {
  return { eventId: event.id, eventHash: event.hash, subjectId: event.subjectId, status, finalExpression };
}
async function listening(t, server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  return 'http://127.0.0.1:' + server.address().port;
}

test('default off, no adapter creation; binding survives disabling and restart', async t => {
  const dir = directory(t); let calls = 0;
  const window = new EngineWindow(dir, { adapterFactory: () => { calls++; throw Error('unexpected'); } });
  assert.equal(window.publicConfig().enabled, false);
  const binding = window.bind('shared', { subjectId: 'actual-subject', enabled: false, grants: ['messages'] });
  assert.throws(() => window.enqueue(input(binding)), /关闭/);
  await assert.rejects(window.inspect(), /开启/);
  assert.equal(calls, 0);
  const restored = new EngineWindow(dir);
  assert.equal(restored.binding('shared').id, binding.id);
  assert.equal(restored.binding('shared').enabled, false);
  assert.equal(restored.publicConfig().tokenConfigured, false);
});

test('canonical Chinese payloads are stable and invalid Unicode is rejected', () => {
  assert.equal(sha256Hash(canonicalizeJson({ z: '园园', a: '沈知屿' })),
    sha256Hash(canonicalizeJson({ a: '沈知屿', z: '园园' })));
  assert.throws(() => canonicalizeJson({ text: '\ud800' }), /surrogate/i);
});

test('production adapter can check health but never sends stored events to Engine', async t => {
  const requests = [];
  const server = http.createServer((req, res) => {
    requests.push({ method: req.method, path: req.url, authorization: req.headers.authorization });
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ status: 'ready' }));
  });
  const origin = await listening(t, server);
  const window = new EngineWindow(directory(t));
  window.configure({ enabled: true, baseUrl: origin, serviceToken: 'unit-test-token-'.repeat(4) });
  const binding = window.bind('shared', { subjectId: 'actual-subject', enabled: true, grants: ['messages'] });
  const checked = await window.inspect();
  assert.equal(checked.reachable, true);
  assert.equal(checked.bindingVerified, false);
  assert.equal(checked.deliverySupported, false);
  const event = window.enqueue(input(binding));
  assert.equal((await window.process(event.id)).status, 'awaiting_protocol');
  assert.deepEqual(requests, [{ method: 'GET', path: '/health/ready', authorization: undefined }]);
  assert.equal(JSON.stringify(window.publicConfig()).includes('unit-test-token'), false);
});

test('event dedup survives restart and conflicting contents cannot reuse an ID', async t => {
  const dir = directory(t), window = new EngineWindow(dir), binding = enabled(window);
  const event = window.enqueue(input(binding)); await window.process(event.id);
  const restored = new EngineWindow(dir);
  assert.equal(restored.enqueue(input(binding)).hash, event.hash);
  assert.equal(restored.list('shared').length, 1);
  assert.throws(() => restored.enqueue({ ...input(binding), payload: { text: 'changed' } }), /不同内容/);
});

test('unsupported and ungranted events cannot enter the queue', t => {
  const window = new EngineWindow(directory(t)), binding = enabled(window);
  for (const kind of ['phone.unlock', 'software.model', 'call.declined', 'group.turn_requested', 'helper.wake_requested']) {
    assert.throws(() => window.enqueue(input(binding, 'restricted', kind)), /授权/);
  }
  assert.throws(() => window.enqueue(input(binding, 'invalid', 'system.create_subject')), /尚未支持/);
  assert.throws(() => window.enqueue({ ...input(binding), kind: null }), /事件类型/);
  assert.equal(window.list('shared').length, 0);
});

test('revocation and unbind invalidate unsent work; new subject cannot inherit it', async t => {
  const window = new EngineWindow(directory(t)), binding = enabled(window, ['messages', 'phone']);
  const event = window.enqueue(input(binding, 'phone-1', 'phone.unlock'));
  window.bind('shared', { subjectId: binding.subjectId, enabled: true, grants: ['messages'] });
  assert.equal((await window.process(event.id)).status, 'revoked');
  assert.throws(() => window.bind('shared', { subjectId: 'another', enabled: true, grants: ['messages'] }), /解除/);
  const second = window.enqueue(input(binding, 'message-2'));
  window.unbind('shared');
  const next = window.bind('shared', { subjectId: 'another', enabled: true, grants: ['messages'] });
  assert.notEqual(next.id, binding.id);
  assert.equal((await window.process(second.id)).status, 'revoked');
  assert.throws(() => window.enqueue(input(binding, 'stale-1')), /关联已改变/);
});

test('uncertain delivery queries the same event after restart without replay', async t => {
  const dir = directory(t); let delivered = 0, queried = 0;
  const adapter = { canDeliver: () => true,
    deliver: async () => { delivered++; throw Error('connection lost after send'); },
    query: async event => { queried++; return receipt(event); } };
  const window = new EngineWindow(dir, { adapterFactory: () => adapter });
  const binding = enabled(window), event = window.enqueue(input(binding));
  assert.equal((await window.process(event.id)).status, 'unknown');
  const restored = new EngineWindow(dir, { adapterFactory: () => adapter });
  assert.equal((await restored.process(event.id)).finalExpression, '主体的表达');
  await restored.process(event.id);
  assert.equal(delivered, 1); assert.equal(queried, 1);
});

test('process death during sending becomes unknown; wrong-subject receipt is not displayed', async t => {
  const dir = directory(t), window = new EngineWindow(dir), binding = enabled(window);
  const event = window.enqueue(input(binding));
  window.event(event.id).status = 'sending'; window.save();
  let delivers = 0;
  const restored = new EngineWindow(dir, { adapterFactory: () => ({
    canDeliver: () => true, deliver: async () => { delivers++; },
    query: async event => ({ ...receipt(event), subjectId: 'wrong-subject' })
  }) });
  const result = await restored.process(event.id);
  assert.equal(result.status, 'unknown'); assert.equal(result.finalExpression, null); assert.equal(delivers, 0);
});

test('binding mutation and duplicate processing cannot race an in-flight delivery', async t => {
  let release;
  const gate = new Promise(resolve => release = resolve);
  const window = new EngineWindow(directory(t), { adapterFactory: () => ({
    canDeliver: () => true, deliver: async event => { await gate; return receipt(event, 'waiting'); }
  }) });
  const binding = enabled(window), event = window.enqueue(input(binding)), work = window.process(event.id);
  await assert.rejects(window.process(event.id), /正在投递/);
  assert.throws(() => window.unbind('shared'), /等待/);
  assert.throws(() => window.configure({ enabled: false, baseUrl: 'http://127.0.0.1:8766' }), /等待/);
  release(); assert.equal((await work).status, 'waiting');
});

test('model is never used for associated shared chats, including disconnected mode', async t => {
  let models = 0;
  const instance = createSharedServer({ dataDir: directory(t), generate: async () => { models++; throw Error('model must not run'); } });
  const origin = await listening(t, instance.server);
  const request = (route, data) => fetch(origin + route, { method: data === undefined ? 'GET' : 'POST',
    headers: { Authorization: 'Bearer ' + instance.store.state.token, 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data) });
  assert.equal((await fetch(origin + '/api/shared/engine/config')).status, 401);
  const binding = enabled(instance.engine);
  const chat = await (await request('/api/web/chats', { title: '引擎主体' })).json();
  const result = await request('/api/web/chats/' + chat.id + '/messages/stream', { message: '你好', attachment_ids: [] });
  assert.match(await result.text(), /尚未发给引擎/);
  assert.equal(instance.store.chat(chat.id).messages.length, 1);
  assert.equal(instance.store.chat(chat.id).modelMessages.length, 0);
  instance.engine.bind('shared', { subjectId: binding.subjectId, enabled: false, grants: ['messages'] });
  const paused = await request('/api/web/chats/' + chat.id + '/messages/stream', { message: '不要代答' });
  assert.equal(paused.status, 409); assert.equal(models, 0);
  assert.equal(instance.engine.binding('shared').subjectId, binding.subjectId);
});

test('unsafe Engine origins and malformed tokens are rejected without connecting', t => {
  const window = new EngineWindow(directory(t));
  for (const baseUrl of ['https://example.org', 'http://localhost:8766', 'http://127.0.0.1:8766/path',
    'http://name:secret@127.0.0.1:8766', 'http://127.0.0.1:8766?token=secret']) {
    assert.throws(() => window.configure({ enabled: false, baseUrl }), /loopback/);
  }
  assert.throws(() => window.configure({ enabled: false, baseUrl: 'http://127.0.0.1:8766', serviceToken: false }), /令牌格式/);
});

test('changing Engine service cannot redirect a live binding and its queued events', t => {
  const window = new EngineWindow(directory(t)), binding = enabled(window);
  const event = window.enqueue(input(binding));
  assert.throws(() => window.configure({ enabled: true, baseUrl: 'http://127.0.0.1:9000' }), /解除所有/);
  window.unbind('shared');
  window.configure({ enabled: false, baseUrl: 'http://127.0.0.1:9000' });
  assert.equal(window.event(event.id).status, 'revoked');
});
