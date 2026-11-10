import { useEffect, useState } from 'react';
import './privateState.css';

type Value = string | number | boolean | null | Value[] | { [key: string]: Value };
type Row = { [key: string]: Value };
type Page = { items?: Row[]; records?: Row[]; item?: Row | null; next_offset?: number | null; candidate_hash?: string };
type Status = { name: string; owner: string; enabled: boolean; window: { status: string; opened_at?: number; closes_at?: number; submitted?: number } };
type Section = { title: string; kind: string; id?: string };
const familyNames: Record<string, string> = { personality: '人格', self_knowledge: '自我认识与局限', value: '价值', stance: '立场', preference: '偏好', relation: '关系认识', drive: '驱力', need: '需要', desire: '愿望', emotion: '当下情绪', sentiment: '持续情感', feeling: '感受' };
const family = (id: string): Section => ({ title: familyNames[id], kind: 'inner_family', id });
const sections: Record<string, Section[]> = {
  当前: [{ title: '当前状态', kind: 'state' }, ...['drive', 'need', 'desire', 'emotion', 'sentiment', 'feeling'].map(family)],
  人格与自我: ['personality', 'self_knowledge', 'value', 'stance'].map(family),
  偏好: [family('preference')], 关系: [family('relation')],
  牵挂与事件: [{ title: '事情与持续影响', kind: 'episode' }, { title: '重点位置', kind: 'focus' }, { title: '念头与关注', kind: 'thought' }],
  相互影响: [{ title: '联合与理解', kind: 'union' }, { title: '经历关联', kind: 'experience_link' }],
  打算与行动: [{ title: '决定', kind: 'decision' }, { title: '实际操作与能力', kind: 'action' }, { title: '结果后的理解', kind: 'feedback' }, { title: '活动随记', kind: 'activity' }],
  休息: [{ title: '休息与醒来', kind: 'rest' }, { title: '梦醒随记', kind: 'waking_note' }],
  梦醒随记: [{ title: '梦醒随记', kind: 'waking_note' }],
  内容承接: [{ title: '待处理候选', kind: 'inner_pending' }, { title: '已采用内容', kind: 'inner' }]
};
const labels: Record<string, string> = { description: '完整内容', kind: '类别', family: '内在部分', context: '适用情境', exceptions: '条件与例外', object_ref: '涉及对象', direction: '方向', current_state: '当前体验', understanding: '认识', feeling: '感受', label: '称谓', orientation: '倾向', horizon: '持续范围', ownership: '属于谁的选择', aspect: '方面', strength: '强度', intensity: '当前强度', importance: '长期重要性', attention: '当前注意', resolution: '处理情况', meaning: '当前理解', long_term_impact: '长期影响', reason: '依据', text: '随记', status: '情况', source: '来源', origin: '来源类型', operation: '变化', message_count: '已保存表达次数', goal: '想做的事', purpose: '安排', result: '实际结果', error: '未完成原因', limitation: '限制', limitations: '限制', summary: '概览', interpretation: '理解', at: '记录时间', created_at: '提交时间', adopted_at: '采用时间', formed_at: '历史形成时间', submitted_at: '实际提交时间', members: '共同参与的成员', replacement_reason: '重点替换理由', name: '名称', title: '标题', unresolved: '事情仍未解决', objects: '涉及对象', goals: '相关目标', level: '层级', semantic_rule: '专门规则', tool: '实际使用能力', completed_at: '完成时间', read: '用户是否读过', saved: '是否保存', rest_link_status: '休息关联', reality: '材料性质' };
const words: Record<string, string> = { established_import: '已有确定内容导入/写入（不冒称找到原文）', user_proposal: '用户提供的变化', subject_adoption: '主体已采用', subject_development: '主体发展', initial_configuration: '初始设置', long_term: '长期', short_term: '短期', contextual: '情境性', self: '自己的偏好', accommodation: '配合他人', like: '喜欢', dislike: '不喜欢', neutral: '无感', uncertain: '尚不确定', active: '持续', background: '背景', foreground: '当前关注', resolved: '已解决，经历保留', unresolved: '未解决', completed: '实际完成', failed: '失败', unknown: '结果未知', quiet: '未发消息', interrupted: '中断', started: '已开始', received: '已接收', survival: '求生欲：不随时间自动变化，零值不触发停止', life: '生', specific: '细分', broad: '广义', WAKING_NOTE_NOT_EXTERNAL_FACT: '醒后随记，不是外部现实事实', explicit_model_display_note: '活动中明确留下的可见随记' };
function object(value: Value | undefined): Row | null { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null; }
function text(value: Value | undefined): string { return typeof value === 'string' ? value : ''; }
function date(value: number): string { return new Date(value).toLocaleString(); }
function newId(): string { return Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join(''); }
async function call<T>(token: string, chat: string, body: Row, signal?: AbortSignal): Promise<T> {
  const response = await fetch('/api/web/private-state', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, chat_id: chat }), signal });
  if (!response.ok) { const error = await response.json() as { error: string }; throw new Error(error.error); }
  return response.json() as Promise<T>;
}
function Content({ value }: { value: Row }) {
  return <dl className="private-fields">{Object.entries(value).filter(([key]) => key in labels).map(([key, item]) => <div key={key}><dt>{labels[key]}</dt><dd>{typeof item === 'number' && /(^at$|_at$)/.test(key) ? date(item) : typeof item === 'string' ? (words[item] ?? familyNames[item] ?? item) : item === null ? '未记录' : typeof item === 'boolean' ? (item ? '是' : '否') : typeof item === 'number' ? item : Array.isArray(item) ? item.map((entry, index) => <div key={index}>{object(entry) ? <Content value={object(entry)!} /> : text(entry)}</div>) : <Content value={item} />}</dd></div>)}</dl>;
}

Object.assign(labels, { version: '版本', effects: '实际采用的影响', from: '作用来自', to: '作用到', request: '实际操作请求', declaration: '明确形成的操作打算', receipt: '实际回执（不代表愿望满足）', execution_status: '执行情况', success: '技术操作成功', capability: '软件能力', stage: '构建或运行阶段', target: '作用对象', available: '当前可读取', choice: '选择', next: '后续想法', visibility_notice: '可见范围' });

function InitialReference({ token, chat }: { token: string; chat: string }) {
  const [reference, setReference] = useState<Row | null>(null);
  const [error, setError] = useState('');
  async function read() {
    try { setReference(await call<Row>(token, chat, { action: 'initial_reference' })); setError(''); }
    catch (failure) { console.error('private.initial', failure); setError(failure instanceof Error ? failure.message : '读取失败'); }
  }
  return <details onToggle={event => { if (event.currentTarget.open && reference === null) void read(); }}><summary>初始设置 · 仅供参考</summary><p>已采用的人格是持续依据；初始设置不冒充今天形成的新认识。</p>{reference ? <Content value={reference} /> : error ? <p role="alert">{error}<button onClick={() => void read()}>重试</button></p> : <p>正在读取…</p>}</details>;
}

export function PrivateStateScreen({ token, chat, close }: { token: string; chat: string; close: () => void }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [tab, setTab] = useState('当前');
  const [section, setSection] = useState<Section>(sections.当前[0]);
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<Page>({});
  const [detail, setDetail] = useState<Row | null>(null);
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form, setForm] = useState<{ record: Row; candidate?: Row; hash?: string; targetVersion?: number } | null>(null);
  const [sourceMode, setSourceMode] = useState('established');
  const [original, setOriginal] = useState('');
  const [source, setSource] = useState('');
  const [importContent, setImportContent] = useState('');
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(t); }, []);
  useEffect(() => {
    const controller = new AbortController(); setBusy(true); setError(''); setPage({}); setDetail(null);
    Promise.all([call<Status>(token, chat, { action: 'status' }, controller.signal), call<Page>(token, chat, { action: 'page', kind: section.kind, id: section.id ?? null, offset }, controller.signal)])
      .then(([s, p]) => { if (!controller.signal.aborted) { setStatus(s); setPage(p); } })
      .catch((failure: Error) => { if (!controller.signal.aborted) { console.error('private.read', failure); setError(failure.message); } })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [token, chat, section, offset, revision]);
  function navigate(next: Section) { setSection(next); setOffset(0); }
  async function mutate(body: Row) {
    setBusy(true); setError(''); setNotice('');
    try { await call<Row>(token, chat, body); setRevision(n => n + 1); setNotice('保存完成。候选需在后续正常轮次实际读取后才能采用。'); setForm(null); }
    catch (failure) { console.error('private.save', failure); setError(failure instanceof Error ? failure.message : '保存未完成'); }
    finally { setBusy(false); }
  }
  function provenance(record: Row): Row {
    if (sourceMode === 'established') return {};
    if (sourceMode === 'new') return { original: JSON.stringify(record), source: '用户本次提出的新变化' };
    if (!original.trim() || !source.trim()) throw new Error('请填写实际原文和来源');
    return { original, source };
  }
  function completeRecord(record: Row): Row {
    const copy = { ...record };
    for (const key of ['object_ref', 'parent_ref']) if (copy[key] === '') delete copy[key];
    if (['emotion', 'sentiment', 'feeling'].includes(text(copy.family)) && copy.unresolved === undefined) copy.unresolved = false;
    return copy;
  }
  async function edit(row: Row) {
    setError('');
    try {
      if (section.kind === 'inner_pending') {
        const exact = await call<Page>(token, chat, { action: 'page', kind: 'inner_pending', id: text(row.id) });
        if (!exact.item || !exact.candidate_hash) throw new Error('候选已改变，请刷新');
        setForm({ record: { ...object(exact.item.record)! }, candidate: exact.item, hash: exact.candidate_hash });
      } else setForm({ record: { ...object(row.content)! }, targetVersion: typeof row.version === 'number' ? row.version : 0 });
      setSourceMode('new');
    } catch (failure) { console.error('private.edit', failure); setError(failure instanceof Error ? failure.message : '读取全文失败'); }
  }
  async function withdraw(row: Row) {
    try {
      const exact = await call<Page>(token, chat, { action: 'page', kind: 'inner_pending', id: text(row.id) });
      if (!exact.candidate_hash) throw new Error('候选已变化');
      await mutate({ action: 'withdraw', id: text(row.id), candidate_hash: exact.candidate_hash });
    } catch (failure) { console.error('private.withdraw', failure); setError(failure instanceof Error ? failure.message : '撤回失败'); }
  }
  async function showDetail(row: Row, kind: string) {
    setError('');
    try {
      if (kind === 'action') {
        const result = await call<Page>(token, chat, { action: 'page', kind, id: text(row.id) });
        if (!result.item) throw new Error('完整回执未能读取，列表仅为概要');
        setDetail(result.item);
      } else setDetail(row);
    } catch (failure) { console.error('private.detail', failure); setError(failure instanceof Error ? failure.message : '读取失败'); }
  }
  async function showMember(member: Row) {
    try {
      const result = await call<Page>(token, chat, { action: 'page', kind: text(member.kind), id: text(member.id) });
      if (!result.item) throw new Error('成员未能读取，原有联合关联仍保留');
      setDetail(result.item);
    } catch (failure) { console.error('private.member', failure); setError(failure instanceof Error ? failure.message : '成员读取失败'); }
  }
  const rows = page.items ?? page.records ?? (page.item ? [page.item] : []);
  const window = status?.window;
  const remaining = window?.closes_at === undefined ? 0 : Math.max(0, window.closes_at - now);
  return <div className="private-overlay"><main className="private-page" aria-label="私域内部状态">
    <header><div><small>私域 · {status?.name}</small><h1>内部状态</h1><p>当前、留下的经历，以及后来形成的理解。</p></div><button onClick={close}>返回聊天</button></header>
    <nav aria-label="私域分区">{Object.keys(sections).map(name => <button key={name} aria-pressed={tab === name} onClick={() => { setTab(name); navigate(sections[name][0]); }}>{name}</button>)}</nav>
    <div className="private-subnav">{sections[tab].map(s => <button key={s.title} aria-pressed={section.title === s.title} onClick={() => navigate(s)}>{s.title}</button>)}</div>
    {status && !status.enabled ? <p className="private-notice">此助手尚未开启内部状态。已有资料保留；请在手机该助手的内部状态设置中开启。</p> : null}
    {error ? <p role="alert" className="private-error">{error}<button onClick={() => setRevision(n => n + 1)}>重新读取</button></p> : null}
    {notice ? <p role="status" className="private-notice">{notice}</p> : null}
    {tab === '人格与自我' ? <InitialReference key={chat} token={token} chat={chat} /> : null}
    {tab === '内容承接' ? <section className="private-card">
      <h2>承接已有的自己</h2><p>完整保存原文。预览、摘要和展示长度不会覆盖已保存内容。</p>
      {window?.status === 'not_opened' ? <button disabled={busy} onClick={() => void mutate({ action: 'open_window' })}>由我开启 48 小时承接窗口</button> : window?.opened_at !== undefined && window.closes_at !== undefined ? <p>开始 {date(window.opened_at)} · 截止 {date(window.closes_at)}<br />剩余 {Math.floor(remaining / 3600000)} 小时 {Math.floor(remaining / 60000) % 60} 分钟 · 已提交 {window.submitted} 次<br />按时提交的未改动候选仍可在截止后采用。之后的新变化继续使用正常来源。</p> : null}
      <button disabled={busy} onClick={() => { setSourceMode('established'); setForm({ record: { id: newId(), family: 'personality', kind: '', description: '', context: '', exceptions: '', status: 'active' } }); }}>直接填写</button>
      <details><summary>导入文件或粘贴多项内容</summary><p>JSON 数组或逐行 JSON，按项保存。中断后重新导入相同内容不会重复提交；没有内容总条数限制。</p>
        <SourceChoice mode={sourceMode} change={setSourceMode} original={original} setOriginal={setOriginal} source={source} setSource={setSource} />
        <input type="file" accept=".json,.jsonl,.txt" onChange={e => { const file = e.target.files?.[0]; if (file) void file.text().then(setImportContent).catch(failure => { console.error('private.file', failure); setError('文件读取失败'); }); }} />
        <textarea aria-label="导入内容" value={importContent} onChange={e => setImportContent(e.target.value)} rows={8} />
        <button disabled={busy || !importContent.trim()} onClick={() => { try { void mutate({ action: 'import', content: importContent, ...(sourceMode === 'new' ? { source_mode: 'new' } : provenance({})) }); } catch (failure) { console.error('private.import', failure); setError(failure instanceof Error ? failure.message : '请检查来源'); } }}>提交各项候选</button>
      </details>
    </section> : null}
    {busy ? <p role="status">正在读取或保存…</p> : !error && rows.length === 0 ? <p className="private-empty">此处暂未留存相应记录。未加载的内容不表示已遗忘或放下。</p> : null}
    <div className="private-grid">{rows.map((row, index) => {
      const item = object(row.value) ?? row;
      const content = object(item.content) ?? object(item.record) ?? item;
      return <article className="private-card" key={text(item.id) || index}><h2>{text(content.kind) || text(content.title) || section.title}</h2>
        <div className="private-preview"><Content value={content} />{content !== item ? <Content value={item} /> : null}</div>
        {item.has_note === false || section.kind === 'activity' && !item.note_preview ? <p>未留存可见随记；可打开详情核对保密选择、实际操作或中断情况。</p> : typeof item.note_preview === 'string' ? <p>{item.note_preview}</p> : null}
        <button onClick={() => void showDetail(item, section.kind)}>查看全文与来源</button>
        {section.kind === 'activity' ? <button onClick={() => navigate({ title: '这次活动', kind: 'activity_part', id: text(item.id) })}>随记、操作与表达</button> : null}
        {section.kind === 'inner_pending' ? <><button disabled={busy} onClick={() => void edit(item)}>修改全文</button><button disabled={busy} onClick={() => void withdraw(item)}>撤回</button></> : section.kind === 'inner' || section.kind === 'inner_family' ? <button disabled={busy} onClick={() => void edit(item)}>提出修改候选</button> : null}
        {!['activity', 'activity_part', 'waking_note', 'thought', 'source', 'category_versions', 'action_versions', 'inner_pending'].includes(section.kind) ? <button onClick={() => navigate({ title: '变化历史', kind: section.kind === 'action' ? 'action_versions' : 'category_versions', id: section.kind === 'action' ? text(item.id) : `${['inner_family', 'inner'].includes(section.kind) ? 'inner_record' : section.kind}:${text(item.id)}` })}>变化历史</button> : null}
        {section.kind === 'state' ? <button onClick={() => navigate({ title: '这一类别里的事情', kind: 'episode_category', id: text(item.id) })}>分别查看相关事情</button> : null}
      </article>;
    })}</div>
    <footer><button disabled={busy || offset === 0} onClick={() => setOffset(Math.max(0, offset - 8))}>上一页</button><span>第 {Math.floor(offset / 8) + 1} 页</span><button disabled={busy || page.next_offset == null} onClick={() => { if (page.next_offset != null) setOffset(page.next_offset); }}>下一页</button><button onClick={() => setRevision(n => n + 1)}>刷新</button></footer>
    {detail ? <div className="private-dialog"><section className="private-card"><button onClick={() => setDetail(null)}>关闭详情</button><Content value={object(detail.content) ?? object(detail.record) ?? detail} /><Content value={detail} />
      {Array.isArray(object(detail.content)?.members) ? <section><p>关联保留当时版本；点开读取成员自己的当前状态，旧联合不会覆盖它。</p>{(object(detail.content)!.members as Value[]).map((value, i) => { const member = object(value); return member ? <button key={i} onClick={() => void showMember(member)}>查看成员 {text(member.id)} · 关联时 v{typeof member.version === 'number' ? member.version : '未记录'}</button> : null; })}</section> : null}
      {object(detail.request)?.parameters ? <details><summary>实际输入参数</summary><pre>{JSON.stringify(object(detail.request)!.parameters, null, 2)}</pre></details> : null}
      {object(detail.receipt)?.data ? <details><summary>完整实际输出</summary><pre>{JSON.stringify(object(detail.receipt)!.data, null, 2)}</pre></details> : null}
      {Object.entries(object(detail.bindings) ?? object(detail.evidence) ?? {}).map(([id, evidence]) => <details key={id}><summary>来源 {id}</summary>{object(evidence) ? <Content value={object(evidence)!} /> : null}<button onClick={() => { setDetail(null); navigate({ title: '原始来源', kind: 'source', id }); }}>查阅原始材料</button><pre>{JSON.stringify(evidence, null, 2)}</pre></details>)}
      <details><summary>完整保存记录与技术信息</summary><pre>{JSON.stringify(detail, null, 2)}</pre></details></section></div> : null}
    {form ? <CandidateForm value={form.record} existing={form.candidate !== undefined || form.targetVersion !== undefined} setValue={record => setForm({ ...form, record })} mode={sourceMode} change={setSourceMode} original={original} setOriginal={setOriginal} source={source} setSource={setSource} close={() => setForm(null)} busy={busy} error={error} save={() => {
      try { const record = completeRecord(form.record); void mutate({ action: 'submit', record, ...provenance(record), ...(form.candidate ? { replaces: text(form.candidate.id), previous_hash: form.hash! } : {}), ...(form.targetVersion === undefined ? {} : { expected_target_version: form.targetVersion }) }); }
      catch (failure) { console.error('private.candidate', failure); setError(failure instanceof Error ? failure.message : '提交未完成'); }
    }} /> : null}
  </main></div>;
}
function SourceChoice({ mode, change, original, setOriginal, source, setSource }: { mode: string; change: (s: string) => void; original: string; setOriginal: (s: string) => void; source: string; setSource: (s: string) => void }) {
  return <div><label>来源<select value={mode} onChange={e => change(e.target.value)}><option value="established">已有确定内容导入/写入</option><option value="original">附实际原文与来源</option><option value="new">本次提出的新变化</option></select></label>
    {mode === 'original' ? <><label>实际原文<textarea value={original} onChange={e => setOriginal(e.target.value)} rows={5} /></label><label>来源说明<input value={source} onChange={e => setSource(e.target.value)} /></label></> : <p>{mode === 'established' ? '声明承接已有内容，不伪称找到历史原文；限已开启的 48 小时内提交。' : '以本次用户提交为来源，后续由主体读取和决定。'}</p>}</div>;
}
function CandidateForm({ value, existing, setValue, close, save, busy, error, ...source }: { value: Row; existing: boolean; setValue: (r: Row) => void; close: () => void; save: () => void; busy: boolean; error: string; mode: string; change: (s: string) => void; original: string; setOriginal: (s: string) => void; source: string; setSource: (s: string) => void }) {
  const field = (key: string, title: string, long = false) => <label key={key}>{title}{long ? <textarea aria-label={title} rows={6} value={text(value[key])} onChange={e => setValue({ ...value, [key]: e.target.value })} /> : <input aria-label={title} value={text(value[key])} onChange={e => setValue({ ...value, [key]: e.target.value })} />}</label>;
  const choice = (key: string, title: string, options: string[]) => <label>{title}<select value={text(value[key])} onChange={e => setValue({ ...value, [key]: e.target.value })}><option value="">请选择</option>{options.map(o => <option key={o} value={o}>{words[o] ?? o}</option>)}</select></label>;
  return <div className="private-dialog"><section className="private-card private-form"><h2>完整候选</h2><p>提交不直接覆盖。修改版须在后续正常轮次重新读取；采用时保存这一版全文。</p>
    <label>类别<select disabled={existing} value={text(value.family)} onChange={e => setValue({ id: value.id, family: e.target.value, kind: value.kind, description: value.description, context: value.context, exceptions: value.exceptions, status: value.status })}>{Object.entries(familyNames).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
    {field('kind', '内容名称')}{field('description', '完整描述（保留条件、程度和矛盾）', true)}{field('context', '适用情境', true)}{field('exceptions', '例外', true)}{field('object_ref', '对象身份（用户为 user，其他对象用已有身份）')}
    {value.family === 'preference' ? <>{field('aspect', '偏好方面')}{choice('orientation', '倾向', ['like', 'dislike', 'neutral', 'uncertain'])}{choice('horizon', '持续范围', ['long_term', 'short_term', 'contextual'])}{choice('ownership', '自己的选择或配合', ['self', 'accommodation'])}</> : null}
    {value.family === 'relation' ? <>{field('label', '关系称谓')}{field('understanding', '对这个对象的认识', true)}{field('feeling', '对这个对象的感受', true)}</> : null}
    {value.family === 'drive' ? field('direction', '方向', true) : null}
    {value.family === 'need' ? <>{choice('level', '层级', ['broad', 'specific'])}{field('parent_ref', '上层已采用需要 ID（细分需要填写）')}</> : null}
    {value.family === 'desire' ? field('parent_ref', '关联的已采用需要 ID') : null}
    {['emotion', 'sentiment', 'feeling'].includes(text(value.family)) ? <>{field('current_state', '当前体验', true)}<label><input type="checkbox" checked={value.unresolved === true} onChange={e => setValue({ ...value, unresolved: e.target.checked })} />事情尚未解决</label></> : null}
    <SourceChoice {...source} />
    <details><summary>保留的完整结构（可复制核对）</summary><pre>{JSON.stringify(value, null, 2)}</pre></details>
    {error ? <p role="alert" className="private-error">{error}</p> : null}<button disabled={busy} onClick={save}>保存候选全文</button><button disabled={busy} onClick={close}>取消</button>
  </section></div>;
}
