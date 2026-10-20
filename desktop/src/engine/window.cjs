const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { canonicalizeJson, sha256Hash } = require('./vendor/canonical-json.cjs');
const { createHttpContinuityIntegrationTransport } = require('./vendor/http-transport.cjs');
const { ValidationError } = require('./errors.cjs');

const GRANTS = Object.freeze(['messages', 'group', 'phone', 'software', 'call', 'helpers']);
const STATES = Object.freeze({
  disabled: '连接已关闭；主体关联保留',
  queued: '已保存在软件，尚未投递',
  awaiting_protocol: '等待正式主体绑定和事件协议；尚未发给引擎',
  sending: '投递中',
  unknown: '处理结果未知，需要查询；不会自动重发',
  waiting: '引擎已接收，等待处理',
  completed: '引擎已完成',
  rejected: '引擎拒绝了该事件',
  revoked: '授权已撤回，未投递',
});
const idPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/;
function identifier(value, field) {
  if (typeof value !== 'string' || !idPattern.test(value)) throw new ValidationError(field + ' 无效');
  return value;
}
function grantFor(kind) {
  if (typeof kind !== 'string' || kind.length > 160) throw new ValidationError('事件类型无效');
  if (kind === 'message.user') return 'messages';
  if (kind.startsWith('group.')) return 'group';
  if (kind.startsWith('phone.')) return 'phone';
  if (kind.startsWith('software.')) return 'software';
  if (kind.startsWith('call.')) return 'call';
  if (kind.startsWith('helper.')) return 'helpers';
  throw new ValidationError('尚未支持的事件类型');
}
function fail(message, status = 409) { throw Object.assign(new Error(message), { status }); }

/** No current HTTP endpoint can confirm a real subject binding.
 * Health is deliberately separate from capability/binding readiness. Never POST
 * test identities or turn this application's event envelope into an Engine protocol.
 */
class PendingEngineAdapter {
  constructor(config) {
    this.transport = createHttpContinuityIntegrationTransport({
      baseUrl: config.baseUrl, serviceToken: config.serviceToken,
      connectTimeoutMs: 3000, responseTimeoutMs: 30000, maxResponseBytes: 1048576,
    });
  }
  canDeliver() { return false; }
  async inspect() {
    const reachable = await this.transport.checkReady();
    return { reachable, authenticated: false, bindingVerified: false, deliverySupported: false,
      message: reachable ? '服务可达；正式主体绑定与事件协议尚未接通' : '引擎服务尚未就绪' };
  }
}

class EngineWindow {
  constructor(directory, { adapterFactory = config => new PendingEngineAdapter(config) } = {}) {
    fs.mkdirSync(directory, { recursive: true });
    this.file = path.join(directory, 'engine-window.json');
    this.adapterFactory = adapterFactory;
    this.active = new Set();
    this.state = fs.existsSync(this.file) ? JSON.parse(fs.readFileSync(this.file, 'utf8')) : {
      schema: 1, config: { enabled: false, baseUrl: 'http://127.0.0.1:8766', serviceToken: '' },
      bindings: {}, events: [],
    };
    if (this.state.schema !== 1) throw new Error('Unsupported engine-window schema');
    for (const event of this.state.events) if (event.status === 'sending') event.status = 'unknown';
    this.save();
  }
  save() {
    fs.writeFileSync(this.file + '.tmp', JSON.stringify(this.state), { mode: 0o600 });
    fs.renameSync(this.file + '.tmp', this.file);
  }
  publicConfig() {
    const { serviceToken, ...config } = this.state.config;
    return { ...config, tokenConfigured: !!serviceToken, deliverySupported: false,
      protocolStatus: 'awaiting_engine_contract', message: STATES.awaiting_protocol };
  }
  configure(input) {
    if (typeof input.enabled !== 'boolean' || typeof input.baseUrl !== 'string') throw new ValidationError('连接配置无效');
    const token = input.serviceToken === undefined || input.serviceToken === '' ? this.state.config.serviceToken : input.serviceToken;
    if (typeof token !== 'string') throw new ValidationError('服务令牌格式无效');
    // Validate origin even while disabled; never accept a remote URL or URL credentials.
    new PendingEngineAdapter({ baseUrl: input.baseUrl, serviceToken: token || '0'.repeat(32) });
    if (input.enabled && (typeof token !== 'string' || token.length < 32)) throw new ValidationError('请填写至少32字符的引擎服务令牌');
    if (this.active.size) fail('请等待正在进行的连接操作结束');
    const origin = new URL(input.baseUrl).origin;
    if (new URL(this.state.config.baseUrl).origin !== origin && Object.keys(this.state.bindings).length) {
      fail('请先解除所有入口关联，再更换引擎地址；旧事件不会转交');
    }
    this.state.config = { enabled: input.enabled, baseUrl: origin, serviceToken: token };
    this.save();
    return this.publicConfig();
  }
  binding(key) { return Object.hasOwn(this.state.bindings, key) ? this.state.bindings[key] : null; }
  bind(key, input) {
    identifier(key, '入口标识');
    identifier(input.subjectId, '引擎主体标识');
    if (typeof input.enabled !== 'boolean' || !Array.isArray(input.grants) ||
      input.grants.some(g => !GRANTS.includes(g)) || new Set(input.grants).size !== input.grants.length) throw new ValidationError('关联授权无效');
    const old = this.binding(key);
    if (old && this.active.has(old.id)) fail('请等待当前投递结束后修改关联');
    if (old && old.subjectId !== input.subjectId) fail('请先解除原关联，再关联另一主体；旧事件不会转交');
    const binding = { id: old ? old.id : randomUUID(), key, subjectId: input.subjectId,
      enabled: input.enabled, grants: [...input.grants], verified: false,
      createdAt: old ? old.createdAt : new Date().toISOString() };
    this.state.bindings[key] = binding;
    for (const event of this.state.events) {
      if (event.bindingId === binding.id && !binding.grants.includes(grantFor(event.kind)) &&
          ['queued', 'awaiting_protocol', 'disabled'].includes(event.status)) event.status = 'revoked';
    }
    this.save();
    return structuredClone(binding);
  }
  unbind(key) {
    const binding = this.binding(key);
    if (!binding) return;
    if (this.active.has(binding.id)) fail('请等待当前投递结束');
    for (const event of this.state.events) {
      if (event.bindingId === binding.id && ['queued', 'awaiting_protocol', 'disabled'].includes(event.status)) event.status = 'revoked';
    }
    delete this.state.bindings[key];
    this.save();
  }
  requireEnabled(binding) {
    if (!binding) fail('尚未关联引擎主体');
    if (!binding.enabled || !this.state.config.enabled) fail(STATES.disabled);
  }
  async inspect() {
    if (!this.state.config.enabled) fail('请先开启软件连接；检查不会启动引擎');
    const key = 'inspect';
    if (this.active.has(key)) fail('正在检查连接');
    this.active.add(key);
    try { return await this.adapterFactory(this.state.config).inspect(); }
    finally { this.active.delete(key); }
  }
  enqueue(input) {
    const key = identifier(input.bindingKey, '入口标识');
    const binding = this.binding(key);
    this.requireEnabled(binding);
    const grant = grantFor(input.kind);
    if (!binding.grants.includes(grant)) fail('该主体没有获得此类事件授权', 403);
    if (input.bindingId !== binding.id) fail('关联已改变，请重新读取配置');
    identifier(input.id, '事件标识');
    identifier(input.conversationId, '会话标识');
    if (typeof input.occurredAt !== 'string' || !Number.isFinite(Date.parse(input.occurredAt))) throw new ValidationError('事件时间无效');
    if (!input.payload || typeof input.payload !== 'object' || Array.isArray(input.payload)) throw new ValidationError('事件内容无效');
    const envelope = { version: 'operit-window-event/v1', id: input.id, bindingKey: key, bindingId: binding.id,
      subjectId: binding.subjectId, conversationId: input.conversationId, kind: input.kind,
      occurredAt: input.occurredAt, payload: structuredClone(input.payload) };
    const bytes = canonicalizeJson(envelope);
    if (bytes.length > 131072) throw new ValidationError('事件超过128KB，请缩小内容');
    const hash = sha256Hash(bytes);
    const existing = this.state.events.find(e => e.id === input.id);
    if (existing) {
      if (existing.hash !== hash) fail('同一事件标识对应不同内容，拒绝重复投递');
      return this.publicEvent(existing);
    }
    if (this.state.events.length >= 10000) fail('软件事件记录已达到上限，请处理后再发送', 507);
    const event = { ...envelope, hash, status: 'queued', createdAt: new Date().toISOString(), finalExpression: null };
    this.state.events.push(event); this.save();
    return this.publicEvent(event);
  }
  event(id) {
    const event = this.state.events.find(e => e.id === id);
    if (!event) fail('事件不存在', 404);
    return event;
  }
  publicEvent(event) { return { ...structuredClone(event), message: STATES[event.status] }; }
  list(key) { return this.state.events.filter(e => e.bindingKey === key).slice(-100).map(e => this.publicEvent(e)); }
  async process(id) {
    const event = this.event(id);
    if (['completed', 'rejected', 'revoked'].includes(event.status)) return this.publicEvent(event);
    const binding = this.binding(event.bindingKey);
    if (!binding || binding.id !== event.bindingId || binding.subjectId !== event.subjectId) fail('旧事件不属于当前关联');
    this.requireEnabled(binding);
    if (!binding.grants.includes(grantFor(event.kind))) fail('此类事件授权已撤回', 403);
    if (this.active.has(binding.id)) fail('该主体入口正在投递或查询');
    const adapter = this.adapterFactory(this.state.config);
    if (!adapter.canDeliver(binding, event.kind)) {
      // Do not erase uncertainty about an earlier attempt when a protocol disappears.
      if (['queued', 'disabled', 'awaiting_protocol'].includes(event.status)) event.status = 'awaiting_protocol';
      this.save(); return this.publicEvent(event);
    }
    this.active.add(binding.id);
    try {
      const query = ['unknown', 'waiting'].includes(event.status);
      if (!query) { event.status = 'sending'; this.save(); }
      const receipt = query ? await adapter.query(structuredClone(event)) : await adapter.deliver(structuredClone(event));
      // An adapter translates a verified Engine receipt; it cannot supply another subject's reply.
      if (!receipt || receipt.eventId !== event.id || receipt.eventHash !== event.hash || receipt.subjectId !== event.subjectId ||
          !['waiting', 'completed', 'rejected', 'unknown'].includes(receipt.status)) throw new Error('引擎回执身份或状态不匹配');
      if (receipt.status === 'completed' && receipt.finalExpression !== null && typeof receipt.finalExpression !== 'string') throw new Error('引擎最终表达格式无效');
      event.status = receipt.status;
      event.finalExpression = receipt.status === 'completed' ? receipt.finalExpression : null;
      delete event.error;
      this.save();
    } catch (error) {
      event.status = 'unknown';
      // No remote response body, credentials, or model intermediate output in logs/UI.
      event.error = '未取得可验证回执；保留原事件，等待查询';
      this.save();
    } finally { this.active.delete(binding.id); }
    return this.publicEvent(event);
  }
}
module.exports = { EngineWindow, PendingEngineAdapter, GRANTS, STATES, grantFor };
