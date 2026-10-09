const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Store } = require('../src/store.cjs');
const { createSharedServer } = require('../src/server.cjs');
const { generate } = require('../src/model.cjs');

function temporary(t) { const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'operit-shared-')); t.after(() => fs.rmSync(directory, { recursive: true, force: true })); return directory; }
async function start(t, options = {}) {
  const instance = createSharedServer({ dataDir: temporary(t), ...options });
  await new Promise(resolve => instance.server.listen(0, '127.0.0.1', resolve));
  t.after(() => { instance.server.closeAllConnections(); return new Promise(resolve => instance.server.close(resolve)); });
  const base = 'http://127.0.0.1:' + instance.server.address().port;
  async function request(route, input, method = input === undefined ? 'GET' : 'POST') {
    return fetch(base + route, { method, headers: { Authorization: 'Bearer ' + instance.store.state.token, 'Content-Type': 'application/json' }, body: input === undefined ? undefined : JSON.stringify(input) });
  }
  instance.store.state.config = { endpoint: 'https://model.invalid/chat/completions', apiKey: 'test', model: 'test', name: 'same-persona', prompt: 'same-system-prompt', tools: false };
  instance.store.save();
  return { ...instance, base, request };
}

test('records and identity survive process restart; stale streaming lease clears', t => {
  const directory = temporary(t), first = new Store(directory);
  first.state.config.name = '原有角色';
  const chat = first.create({ title: '原有会话' });
  first.message(chat, 'assistant', '保留原文', { streaming: true });
  const second = new Store(directory);
  assert.equal(second.state.token, first.state.token);
  assert.equal(second.state.config.name, '原有角色');
  assert.equal(second.chat(chat.id).messages[0].content_raw, '保留原文');
  assert.equal(second.chat(chat.id).messages[0].streaming, false);
});

test('unauthenticated client cannot read records or model credentials', async t => {
  const instance = await start(t);
  const blocked = await fetch(instance.base + '/api/web/chats');
  assert.equal(blocked.status, 401);
  const config = await (await instance.request('/api/shared/config')).json();
  assert.equal('apiKey' in config, false);
  assert.equal(config.keyConfigured, true);
});

test('two clients see the same persisted message and a concurrent send is rejected', async t => {
  let finish, entered;
  const ready = new Promise(resolve => entered = resolve);
  const blocked = new Promise(resolve => finish = resolve);
  const instance = await start(t, { generate: async ({ onDelta }) => {
    await onDelta('电脑独立回复'); entered(); await blocked;
    return { text: '电脑独立回复', generated: [{ role: 'assistant', content: '电脑独立回复' }] };
  } });
  const chat = await (await instance.request('/api/web/chats', { title: '双端' })).json();
  const first = await instance.request('/api/web/chats/' + chat.id + '/messages/stream', { message: '手机发出', attachment_ids: [] });
  await ready;
  const second = await instance.request('/api/web/chats/' + chat.id + '/messages/stream', { message: '电脑同时发送', attachment_ids: [] });
  assert.equal(second.status, 409);
  const page = await (await instance.request('/api/web/chats/' + chat.id + '/messages')).json();
  assert.equal(page.messages[0].content_raw, '手机发出');
  assert.equal(page.messages[1].content_raw, '电脑独立回复');
  finish();
  const stream = await first.text();
  assert.match(stream, /assistant_done/);
  const otherClient = await (await instance.request('/api/web/chats/' + chat.id + '/messages')).json();
  assert.equal(otherClient.messages[1].streaming, false);
  assert.equal(instance.store.chat(chat.id).modelMessages.length, 2);
});

test('provider error is visible and preserves the user turn without a fake AI answer', async t => {
  const instance = await start(t, { generate: async () => { throw new Error('API拒绝请求'); } });
  const chat = await (await instance.request('/api/web/chats', {})).json();
  const response = await instance.request('/api/web/chats/' + chat.id + '/messages/stream', { message: '原话', attachment_ids: [] });
  assert.match(await response.text(), /API拒绝请求/);
  assert.equal(instance.busy.size, 0);
  assert.deepEqual(instance.store.chat(chat.id).modelMessages, [{ role: 'user', content: '原话' }]);
});

function streamed(chunks) { return new Response(chunks.map(chunk => 'data: ' + JSON.stringify(chunk) + '\n\n').join('') + 'data: [DONE]\n\n'); }
test('model keeps persona and routes a real tool result into the following request', async () => {
  const requests = []; const calls = [];
  const responses = [
    streamed([{ choices: [{ delta: { tool_calls: [{ index: 0, id: 'call1', function: { name: 'pc_read_file', arguments: '{"path":"demo.txt"}' } }] }, finish_reason: 'tool_calls' }] }]),
    streamed([{ choices: [{ delta: { content: '已读取真实内容' }, finish_reason: 'stop' }] }])
  ];
  const result = await generate({ config: { endpoint: 'https://model.invalid', apiKey: 'test', model: 'same-model', prompt: '原有提示词', tools: true },
    history: [{ role: 'user', content: '读文件' }], signal: new AbortController().signal,
    tools: { definitions: [], execute: async (name, args) => { calls.push({ name, args }); return { content: '真实内容' }; } },
    onDelta: async () => {}, onTool: async () => {},
    fetchImpl: async (url, request) => { requests.push(JSON.parse(request.body)); return responses.shift(); }
  });
  assert.equal(result.text, '已读取真实内容');
  assert.equal(requests[0].model, 'same-model');
  assert.match(requests[0].messages[0].content, /^原有提示词/);
  assert.equal(calls[0].args.path, 'demo.txt');
  assert.deepEqual(JSON.parse(requests[1].messages[3].content), { content: '真实内容' });
});
