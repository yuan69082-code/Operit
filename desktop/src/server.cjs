const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { randomUUID, createHmac, timingSafeEqual } = require('node:crypto');
const { Store } = require('./store.cjs');
const { generate } = require('./model.cjs');
const { createTools } = require('./tools.cjs');
const theme = require('./theme.cjs');
const release = require('../release.json');
const root = path.resolve(__dirname, '../..');

function fail(message, status = 400) { throw Object.assign(new Error(message), { status }); }
function json(res, value, status = 200) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}
async function body(req, max = 24 * 1024 * 1024) {
  const chunks = []; let size = 0;
  for await (const part of req) { size += part.length; if (size > max) fail('请求过大', 413); chunks.push(part); }
  return Buffer.concat(chunks);
}
function equal(a, b) {
  const aa = Buffer.from(a), bb = Buffer.from(b);
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}

function createSharedServer(options = {}) {
  const dataDir = options.dataDir || path.join(process.env.LOCALAPPDATA || os.homedir(), 'Operit', 'shared-data');
  const store = new Store(dataDir);
  const busy = new Map();
  const tools = options.tools || createTools(dataDir);
  const transport = options.generate || generate;
  const staticDir = options.staticDir || path.join(root, 'web-chat/dist');
  const assetsDir = path.join(dataDir, 'assets'); fs.mkdirSync(assetsDir, { recursive: true });
  const version = process.env.OPERIT_RELEASE_VERSION || release.version;
  let uploads = {};
  const uploadFile = path.join(dataDir, 'uploads.json');
  if (fs.existsSync(uploadFile)) uploads = JSON.parse(fs.readFileSync(uploadFile, 'utf8'));
  function assetUrl(id) {
    return '/api/web/assets/' + id + '?sig=' + createHmac('sha256', store.state.token).update(id).digest('hex');
  }
  function selector() {
    const config = store.state.config;
    return { active_prompt: { type: 'character_card', id: 'shared', name: config.name }, cards: [{ id: 'shared', name: config.name, description: '', created_at: 0, updated_at: 0 }], groups: [] };
  }
  function modelSelector() {
    const model = store.state.config.model;
    return { current_config_id: 'shared', current_config_name: '共享模型', current_model_index: 0, current_model_name: model,
      current_provider_type: 'OPENAI', locked_by_character_card: false,
      thinking_quality_mapping: { mode: 'unsupported', parameter_label: '', options: [], reasoning_required: false },
      configs: [{ id: 'shared', name: '共享模型', model_name: model, models: [model], selected: true, selected_model_index: 0 }] };
  }
  function inputSettings() {
    return { enable_thinking_mode: false, thinking_option_id: '', enable_memory_auto_update: false, enable_auto_read: false,
      enable_max_context_mode: false, enable_tools: store.state.config.tools, disable_stream_output: false,
      disable_user_preference_description: false, permission_level: 'ALLOW', current_window_tokens: 0,
      base_context_length_k: 0, max_context_length_k: 0, active_context_length_k: 0, max_window_tokens: 0 };
  }
  function page(chat, params) {
    const limit = Math.min(200, Math.max(1, Number(params.get('limit') || 24)));
    if (!Number.isFinite(limit)) fail('Invalid limit');
    let messages = chat.messages;
    if (params.has('before_timestamp')) messages = messages.filter(m => m.timestamp < Number(params.get('before_timestamp')));
    if (params.has('after_timestamp')) messages = messages.filter(m => m.timestamp > Number(params.get('after_timestamp')));
    const selected = params.has('after_timestamp') ? messages.slice(0, limit) : messages.slice(-limit);
    return { messages: selected, has_more_before: selected.length > 0 && chat.messages[0].timestamp < selected[0].timestamp,
      has_more_after: selected.length > 0 && chat.messages.at(-1).timestamp > selected.at(-1).timestamp };
  }
  function publicConfig() {
    const { apiKey, ...config } = store.state.config;
    return { ...config, keyConfigured: !!apiKey, version, device: 'pc', protocol: release.protocol,
      connectionUrls: Object.values(os.networkInterfaces()).flat().filter(item => item && item.family === 'IPv4' && !item.internal)
        .map(item => 'http://' + item.address + ':' + server.address().port + '/?shared=1') };
  }
  async function stream(req, res, chat, input) {
    if (busy.has(chat.id)) fail('该会话正在回复，请稍后发送', 409);
    if (typeof input.message !== 'string') fail('消息必须为文字');
    if (!input.message.trim() && !input.attachment_ids?.length) fail('消息不能为空');
    const config = { ...store.state.config };
    if (!config.endpoint || !config.apiKey || !config.model) fail('请先完成共享服务的模型配置');
    const ids = input.attachment_ids || [];
    if (!Array.isArray(ids) || ids.some(id => typeof id !== 'string' || !uploads[id])) fail('附件不存在');
    const attachments = ids.map(id => ({ ...uploads[id], id, asset_url: assetUrl(id) }));
    const content = [{ type: 'text', text: input.message }];
    for (const id of ids) {
      const file = uploads[id]; const bytes = fs.readFileSync(path.join(assetsDir, id));
      if (file.mime_type.startsWith('image/')) content.push({ type: 'image_url', image_url: { url: 'data:' + file.mime_type + ';base64,' + bytes.toString('base64') } });
      else content[0].text += '\n[附件：' + file.file_name + ']\n' + bytes.toString('utf8');
    }
    const userModel = { role: 'user', content: ids.length ? content : input.message };
    const user = store.message(chat, 'user', input.message, { attachments });
    // Commit a user turn once, then keep the reply lease until its producer actually stops.
    chat.modelMessages.push(userModel); store.save();
    const assistant = store.message(chat, 'assistant', '', { streaming: true, role_name: config.name, model_name: config.model });
    const abort = new AbortController(); busy.set(chat.id, abort);
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
    const send = (event, fields = {}) => { if (!res.destroyed) res.write('event: ' + event + '\ndata: ' + JSON.stringify({ event, chat_id: chat.id, ...fields }) + '\n\n'); };
    const disconnected = () => { if (!res.writableEnded) abort.abort(); };
    res.on('close', disconnected);
    send('start'); send('user_message', { message: user });
    let lastSave = Date.now();
    try {
      const result = await transport({ config, history: chat.modelMessages, tools, signal: abort.signal,
        onDelta: async delta => { assistant.content_raw += delta; send('assistant_delta', { message: assistant, delta });
          if (Date.now() - lastSave > 250) { store.save(); lastSave = Date.now(); } },
        onTool: async (name, result) => { if (input.return_tool_status) console.info('pc.tool.complete', name, result.error ? 'error' : 'ok'); }
      });
      assistant.content_raw = result.text;
      chat.modelMessages.push(...result.generated);
      assistant.streaming = false; store.save(); send('assistant_done', { message: assistant });
    } catch (error) {
      console.error('shared.reply', error.message);
      assistant.streaming = false; assistant.error = error.message; store.save();
      send('error', { error: error.message });
    } finally {
      busy.delete(chat.id); res.off('close', disconnected); res.end();
    }
  }
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (url.pathname === '/setup' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(fs.readFileSync(path.join(__dirname, '../setup.html'))); return;
      }
      if (!url.pathname.startsWith('/api/')) {
        if (req.method !== 'GET') fail('Method not allowed', 405);
        const requested = path.resolve(staticDir, '.' + decodeURIComponent(url.pathname));
        if (!requested.startsWith(staticDir + path.sep) && requested !== staticDir) fail('Invalid path', 403);
        const file = url.pathname === '/' ? path.join(staticDir, 'index.html') : requested;
        if (!fs.existsSync(file) || !fs.statSync(file).isFile()) fail('页面未构建或文件不存在', 404);
        const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }[path.extname(file)] || 'application/octet-stream';
        res.writeHead(200, { 'Content-Type': mime, 'Cache-Control': path.extname(file) === '.html' ? 'no-store' : 'max-age=3600' }); fs.createReadStream(file).pipe(res); return;
      }
      const asset = url.pathname.match(/^\/api\/web\/assets\/([a-f0-9-]+)$/);
      if (asset && req.method === 'GET') {
        const id = asset[1], signature = createHmac('sha256', store.state.token).update(id).digest('hex');
        if (!equal(url.searchParams.get('sig') || '', signature)) fail('Unauthorized', 401);
        if (!uploads[id]) fail('附件不存在', 404);
        res.writeHead(200, { 'Content-Type': uploads[id].mime_type, 'Content-Disposition': 'inline', 'X-Content-Type-Options': 'nosniff' });
        fs.createReadStream(path.join(assetsDir, id)).pipe(res); return;
      }
      if (!equal(req.headers.authorization || '', 'Bearer ' + store.state.token)) fail('Unauthorized', 401);
      let input = {};
      if (['POST', 'PATCH', 'PUT'].includes(req.method) && url.pathname !== '/api/web/uploads') {
        const raw = await body(req); input = raw.length ? JSON.parse(raw.toString('utf8')) : {};
      }
      if (url.pathname === '/api/shared/config') {
        if (req.method === 'GET') return json(res, publicConfig());
        if (req.method !== 'POST') fail('Method not allowed', 405);
        if (busy.size) fail('请等待当前回复结束后更改配置', 409);
        for (const key of ['endpoint', 'model', 'name', 'prompt']) {
          if (typeof input[key] !== 'string') fail('缺少配置字段：' + key);
        }
        const endpoint = new URL(input.endpoint);
        if (!['https:', 'http:'].includes(endpoint.protocol)) fail('接口地址必须为HTTP或HTTPS');
        if (typeof input.tools !== 'boolean') fail('工具设置无效');
        const config = store.state.config;
        for (const key of ['endpoint', 'model', 'name', 'prompt', 'tools']) config[key] = input[key];
        if (typeof input.apiKey === 'string' && input.apiKey) config.apiKey = input.apiKey;
        for (const chat of store.state.chats) chat.character_card_name = config.name;
        store.save(); return json(res, publicConfig());
      }
      if (url.pathname === '/api/shared/import-phone' && req.method === 'POST') {
        if (busy.size) fail('请等待当前回复结束', 409);
        if (typeof input.url !== 'string' || typeof input.token !== 'string') fail('需要手机地址和Token');
        const base = new URL(input.url);
        if (!['http:', 'https:'].includes(base.protocol)) fail('手机地址必须为HTTP或HTTPS');
        async function phone(route) {
          const response = await fetch(new URL(route, base), { headers: { Authorization: 'Bearer ' + input.token }, signal: AbortSignal.timeout(30000) });
          if (!response.ok) fail('手机接口返回HTTP ' + response.status, 502); return response.json();
        }
        const list = await phone('/api/web/chats'); let count = 0;
        for (const old of list) {
          const id = 'phone-' + old.id;
          if (store.state.chats.some(chat => chat.id === id)) continue;
          const messages = []; let after = null;
          while (true) {
            const result = await phone('/api/web/chats/' + encodeURIComponent(old.id) + '/messages?limit=200' + (after === null ? '&before_timestamp=9999999999999' : '&before_timestamp=' + after));
            messages.unshift(...result.messages);
            if (!result.has_more_before || !result.messages.length) break;
            const next = result.messages[0].timestamp;
            if (after !== null && next >= after) fail('手机分页没有前进', 502);
            after = next;
          }
          const chat = { ...old, id, character_card_name: store.state.config.name, character_group_id: null,
            messages: messages.map(message => ({ ...message, attachments: [], image_links: [], streaming: false })),
            modelMessages: messages.filter(message => message.sender === 'user' || message.sender === 'assistant')
              .map(message => ({ role: message.sender, content: message.content_raw })) };
          store.state.chats.push(chat); count++; store.save();
        }
        return json(res, { imported: count, scope: 'text' });
      }
      if (url.pathname === '/api/shared/updates' && req.method === 'GET') {
        const response = await fetch('https://api.github.com/repos/yuan69082-code/Operit/releases?per_page=10', { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Operit-Shared' }, signal: AbortSignal.timeout(15000) });
        if (!response.ok) fail('无法读取更新信息：HTTP ' + response.status, 502);
        const releases = await response.json();
        const item = releases.find(item => item.assets.some(asset => asset.name === 'shared-release.json'));
        if (!item) return json(res, { current: version, release: null, update_available: false });
        const manifest = item.assets.find(asset => asset.name === 'shared-release.json');
        const manifestResponse = await fetch(manifest.browser_download_url, { signal: AbortSignal.timeout(15000) });
        if (!manifestResponse.ok) fail('更新清单不可用', 502);
        const latest = await manifestResponse.json();
        const build = Number(version.split('+')[1] || 0);
        const currentCode = release.android_version_base * 100000 + build;
        return json(res, { current: version, release: latest, page: item.html_url, update_available: latest.version_code > currentCode });
      }
      if (url.pathname === '/api/web/bootstrap' && req.method === 'GET') return json(res, {
        version_name: version, current_chat_id: store.state.currentChatId, default_chat_style: 'bubble', default_input_style: 'classic',
        show_thinking_process: true, show_status_tags: true, show_input_processing_status: true,
        capabilities: { attachments: true, per_chat_theme: false, structured_render: true, streaming: true, rename_chat: true, delete_chat: true, shared_service: true }
      });
      if (url.pathname === '/api/web/character-selector') return json(res, selector());
      if (url.pathname === '/api/web/active-prompt' && req.method === 'POST') {
        if (input.id !== 'shared') fail('请在共享服务设置中更改角色'); return json(res, selector());
      }
      if (url.pathname === '/api/web/model-selector') {
        if (req.method === 'POST') return json(res, { success: true, requires_character_card_switch_confirmation: false, selector: modelSelector() });
        return json(res, modelSelector());
      }
      if (url.pathname === '/api/web/memory-selector') return json(res, { current_profile_id: 'shared', profiles: [{ id: 'shared', name: '共享会话' }] });
      if (url.pathname === '/api/web/input-settings') {
        if (req.method === 'PATCH') {
          if (Object.keys(input).some(key => key !== 'enable_tools')) fail('该设置尚未在共享服务中实现', 501);
          if (typeof input.enable_tools !== 'boolean') fail('Invalid tools setting');
          store.state.config.tools = input.enable_tools; store.save();
        }
        return json(res, inputSettings());
      }
      if (url.pathname === '/api/web/uploads' && req.method === 'POST') {
        const raw = await body(req);
        const request = new Request('http://localhost/upload', { method: 'POST', headers: { 'Content-Type': req.headers['content-type'] }, body: raw });
        const form = await request.formData(), file = form.get('file');
        if (!file || typeof file.arrayBuffer !== 'function') fail('缺少附件');
        if (!['image/png', 'image/jpeg', 'image/webp', 'text/plain', 'text/markdown', 'application/json'].includes(file.type)) fail('首版附件支持PNG、JPEG、WebP和文字文件', 415);
        if (file.size > 16 * 1024 * 1024) fail('附件最大16MB', 413);
        const id = randomUUID(); fs.writeFileSync(path.join(assetsDir, id), Buffer.from(await file.arrayBuffer()));
        uploads[id] = { file_name: file.name, mime_type: file.type, file_size: file.size };
        fs.writeFileSync(uploadFile + '.tmp', JSON.stringify(uploads)); fs.renameSync(uploadFile + '.tmp', uploadFile);
        return json(res, { attachment_id: id, ...uploads[id] });
      }
      if (url.pathname === '/api/web/chats' && req.method === 'GET') return json(res, store.state.chats.map(chat => store.summary(chat, busy.has(chat.id))));
      if (url.pathname === '/api/web/chats' && req.method === 'POST') return json(res, store.summary(store.create(input)));
      if (url.pathname === '/api/web/chats/reorder' && req.method === 'POST') {
        for (const item of input.items) { const chat = store.chat(item.chat_id); chat.group = item.group; chat.display_order = item.display_order; }
        store.state.chats.sort((a, b) => (a.display_order || 0) - (b.display_order || 0)); store.save(); return json(res, { success: true });
      }
      const route = url.pathname.match(/^\/api\/web\/chats\/([^/]+)(?:\/(.*))?$/);
      if (route) {
        const chat = store.chat(decodeURIComponent(route[1])), action = route[2];
        if (!action && req.method === 'PATCH') {
          if (typeof input.title === 'string') chat.title = input.title;
          if (input.update_group) chat.group = input.group;
          if (input.update_locked) chat.locked = input.locked;
          chat.updated_at = Date.now(); store.save(); return json(res, store.summary(chat, busy.has(chat.id)));
        }
        if (!action && req.method === 'DELETE') {
          if (chat.locked || busy.has(chat.id)) fail('会话已锁定或正在回复', 409);
          store.state.chats = store.state.chats.filter(item => item.id !== chat.id);
          if (store.state.currentChatId === chat.id) store.state.currentChatId = store.state.chats[0]?.id || null;
          store.save(); return json(res, { success: true });
        }
        if (action === 'select' && req.method === 'POST') { store.state.currentChatId = chat.id; store.save(); return json(res, { success: true }); }
        if (action === 'theme' && req.method === 'GET') return json(res, theme);
        if (action === 'messages' && req.method === 'GET') return json(res, page(chat, url.searchParams));
        if (action === 'message-locator' && req.method === 'GET') {
          const query = url.searchParams.get('query') || '';
          return json(res, chat.messages.filter(message => message.content_raw.includes(query)).map(message => ({ message_index: null, timestamp: message.timestamp, sender: message.sender, preview_content: message.content_raw.slice(0,180), content_length: message.content_raw.length, display_mode: 'normal', is_favorite: !!message.favorite })));
        }
        if (action === 'messages/reveal' && req.method === 'POST') {
          const index = chat.messages.findIndex(message => message.timestamp === input.timestamp);
          if (index < 0) fail('消息不存在', 404);
          return json(res, { messages: chat.messages.slice(Math.max(0,index-12),index+13), has_more_before: index > 12, has_more_after: index+13 < chat.messages.length });
        }
        if (action === 'messages/favorite' && req.method === 'PATCH') {
          const message = chat.messages.find(message => message.timestamp === input.timestamp);
          if (!message) fail('消息不存在', 404); message.favorite = !!input.is_favorite; store.save(); return json(res, { success: true });
        }
        if (action === 'messages/stream' && req.method === 'POST') return await stream(req, res, chat, input);
      }
      fail('该功能尚未在共享服务中实现', 501);
    } catch (error) {
      console.error('shared.http', url.pathname, error.message);
      if (!res.headersSent) json(res, { error: error.message }, error.status || 500);
      else res.end();
    }
  });
  server.on('close', () => { for (const abort of busy.values()) abort.abort(); });
  return { server, store, busy };
}
module.exports = { createSharedServer };
