// Actual WebChat entry + UI, with simulated HTTP records. Not phone/model verification.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const theme = require('../desktop/src/theme.cjs');
const out = path.resolve(__dirname, '../docs/TODO/subject-state-foundation/screenshots');
const full = '我喜欢把话说清楚，但不总是急着下结论。\n遇到陌生的事，会先停一停；有时也会犹豫。\n' + '用于检查长文本显示的模拟内容，限定条件和例外都必须保留。'.repeat(25);
const person = { id: 'p1', owner: 'fixture', version: 1, origin: 'established_import', adopted_at: 1794268800000,
  content: { id: 'p1', family: 'personality', kind: '表达与保留', description: full, status: 'active', exceptions: '温和表达不等于失去立场' } };
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  try {
    const errors = [], requests = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => localStorage.setItem('operit-web-chat-token', 'ui-fixture-only'));
    let opened = false, pending = [], failRead = false;
    await page.route('**/api/**', async route => {
      const req = route.request(), p = new URL(req.url()).pathname, b = req.postDataJSON() || {};
      requests.push({ path: p, ...b });
      const reply = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      if (p === '/api/web/bootstrap') return reply({ version_name: '模拟数据', current_chat_id: 'chat-fixture', default_chat_style: 'bubble', default_input_style: 'classic', capabilities: { private_state: true } });
      if (p === '/api/web/character-selector') return reply({ active_prompt: { type: 'character_card', id: 'fixture', name: '界面验证助手' }, cards: [{ id: 'fixture', name: '界面验证助手', description: '', created_at: 1, updated_at: 1 }], groups: [] });
      if (p === '/api/web/model-selector') return reply({ current_config_id: 'fixture', current_model_index: 0, current_model_name: '未调用模型', current_provider_type: 'OPENAI', configs: [], thinking_quality_mapping: { mode: 'unsupported', parameter_label: '', options: [], reasoning_required: false } });
      if (p === '/api/web/memory-selector') return reply({ current_profile_id: '', profiles: [] });
      if (p === '/api/web/input-settings') return reply({ enable_tools: true, current_window_tokens: 0, max_window_tokens: 32768, permission_level: 'ASK' });
      if (p === '/api/web/chats') return reply([{ id: 'chat-fixture', title: '界面验证', character_card_name: '界面验证助手', updated_at: 1, active_streaming: false, locked: false }]);
      if (p.endsWith('/theme')) return reply(theme);
      if (p.endsWith('/messages')) return reply({ messages: [], has_more_before: false, has_more_after: false });
      if (p.endsWith('/message-locator')) return reply([]);
      if (p.endsWith('/select')) return reply({ success: true });
      if (p !== '/api/web/private-state') throw new Error('Unmodelled request ' + p);
      assert.equal(b.chat_id, 'chat-fixture');
      if (b.action === 'status') return reply({ name: '界面验证助手（模拟记录）', owner: 'fixture', enabled: true, window: opened ? { status: 'open', opened_at: Date.now() - 3600000, closes_at: Date.now() + 47 * 3600000, submitted: pending.length } : { status: 'not_opened' } });
      if (b.action === 'open_window') { opened = true; return reply({ saved: true }); }
      if (b.action === 'submit') {
        if (b.replaces) pending = pending.filter(x => x.id !== b.replaces);
        pending.push({ id: 'candidate-' + pending.length, owner: 'fixture', candidate_version: 1, record: b.record, origin: b.original ? 'user_proposal' : 'established_import', created_at: Date.now(), bindings: {} });
        return reply({ saved: true });
      }
      if (b.action === 'withdraw') { pending = pending.filter(x => x.id !== b.id); return reply({ withdrawn: b.id }); }
      if (failRead) return reply({ error: '模拟读取失败：原有内容仍保留' }, 503);
      if (b.kind === 'inner_pending') return b.id ? reply({ item: pending.find(x => x.id === b.id), candidate_hash: 'exact-ui-version' }) : reply({ items: pending, next_offset: null });
      if (b.kind === 'inner_family' && b.id === 'personality' || b.kind === 'inner') return reply({ records: [person], next_offset: null });
      if (b.kind === 'category_versions') { assert.equal(b.id, 'inner_record:p1'); return reply({ items: [{ ...person, reason: '模拟采用记录：用于检查历史导航' }], next_offset: null }); }
      if (b.kind === 'state') return reply({ items: [{ id: 'curious', name: '好奇', current_state: '想把那个问题弄明白', intensity: 0.6, attention: 'background', resolution: 'unresolved', reason: '模拟状态：不代表真实助手' }], next_offset: null });
      if (b.kind === 'waking_note') return reply({ items: [{ id: 'w1', at: 1794268800000, content: { text: '只记得海面上很淡的一道光。情节已经散了，感觉还在。想先把它留在这里。', reality: 'WAKING_NOTE_NOT_EXTERNAL_FACT' } }], next_offset: null });
      if (b.kind === 'episode') return reply({ items: [{ id: 'e' + (b.offset || 0), meaning: '尚未说完的事', intensity: 0.2, importance: '重要，但不需要一直在前台', attention: 'background', resolution: 'unresolved' }], next_offset: b.offset ? null : 8 });
      return reply({ items: [], next_offset: null });
    });
    await page.goto('http://127.0.0.1:5178/');
    await page.getByRole('button', { name: '私域 · 内部状态', exact: true }).click();
    const nav = page.getByRole('navigation', { name: '私域分区' });
    const button = name => page.getByRole('button', { name, exact: true });
    const tab = name => nav.getByRole('button', { name, exact: true }).click();
    const shot = name => page.screenshot({ path: path.join(out, name + '.png') });
    await page.getByText('想把那个问题弄明白', { exact: true }).waitFor();
    await shot('private-desktop-overview');
    await tab('人格与自我'); await button('查看全文与来源').click();
    assert.ok((await page.locator('.private-dialog').innerText()).includes(full));
    await shot('private-desktop-fulltext'); await button('关闭详情').click();
    await button('变化历史').click(); await page.getByText('模拟采用记录：用于检查历史导航').waitFor();
    await tab('内容承接'); await button('由我开启 48 小时承接窗口').click();
    await page.getByText(/剩余 47 小时/).waitFor();
    await button('直接填写').click(); await page.getByLabel('内容名称', { exact: true }).fill('完整的已有立场');
    const fullField = () => page.getByLabel('完整描述（保留条件、程度和矛盾）', { exact: true });
    await fullField().fill(full); await button('保存候选全文').click();
    await button('修改全文').click(); const edited = full + '\n这是用户的新修订，不替换为摘要。';
    await fullField().fill(edited); await button('保存候选全文').click(); await button('修改全文').waitFor();
    assert.equal(pending[0].record.description, edited);
    assert.equal(requests.filter(x => x.action === 'submit').at(-1).record.description, edited);
    assert.ok(!requests.some(x => x.action === 'adopt'));
    await page.setViewportSize({ width: 412, height: 915 }); await shot('private-mobile-import');
    await tab('梦醒随记'); await page.getByText(/只记得海面上很淡/).waitFor(); await shot('private-mobile-waking');
    await tab('牵挂与事件'); await button('下一页').click();
    await page.getByText('第 2 页', { exact: true }).waitFor();
    assert.ok(requests.some(x => x.kind === 'episode' && x.offset === 8));
    await tab('偏好'); const empty = page.getByText('此处暂未留存相应记录。未加载的内容不表示已遗忘或放下。', { exact: true });
    await empty.waitFor(); failRead = true; await button('刷新').click();
    await page.getByRole('alert').getByText(/模拟读取失败/).waitFor(); await shot('private-mobile-read-error');
    failRead = false; await button('重新读取').click(); await empty.waitFor();
    assert.equal(await page.locator('.private-page').evaluate(e => e.scrollWidth > e.clientWidth), false);
    assert.deepEqual(errors, []); assert.ok(!requests.some(x => /stream|send/.test(x.path)));
    const evidence = { simulation: 'HTTP fixture; no phone, real model or actual subject content', checks: ['production navigation', 'full text exact revision', 'source selection', 'no page-time model call', 'history', 'pagination', 'empty and recoverable error', 'desktop and mobile layout'], requestCount: requests.length };
    fs.writeFileSync(path.join(out, 'ui-check.json'), JSON.stringify(evidence, null, 2)); console.log(JSON.stringify(evidence));
  } catch (e) {
    await page.screenshot({ path: path.join(out, 'ui-failure.png') });
    console.error((await page.locator('body').innerText()).slice(-5000)); throw e;
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
