import { useEffect, useState } from 'react';

type Run = { id: string; activity: string; source: string; attempt: number; at: number; status: string;
  detail?: string; error?: string; stack?: string; steps?: { at: number; detail: string }[] };
type Page = { items: Run[]; next_offset: number | null };
export function AutonomousJournalPanel({ token, chat }: { token: string; chat: string }) {
  const [open, setOpen] = useState(false);
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<Page>({ items: [], next_offset: null });
  const [error, setError] = useState('');
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    async function refresh() {
      try {
        const response = await fetch('/api/web/private-state', { method: 'POST', signal: controller.signal,
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: 'runtime_logs', chat_id: chat, offset }) });
        if (!response.ok) throw new Error(await response.text());
        const data: Page = await response.json();
        if (!controller.signal.aborted) { setPage(data); setError(''); }
      } catch (failure) {
        if (!controller.signal.aborted) { console.error('runtime.logs', failure); setError(failure instanceof Error ? failure.message : '读取失败'); }
      }
    }
    void refresh();
    const timer = offset === 0 ? setInterval(() => void refresh(), 5000) : null;
    return () => { controller.abort(); if (timer !== null) clearInterval(timer); };
  }, [token, chat, offset, open]);
  return <details onToggle={event => setOpen(event.currentTarget.open)}><summary>运行日志</summary>
    <p>记录活动步骤、重试和跳过原因，重启后保留。详细回执在原活动卡片中；更新前缺失的日志无法补造。</p>
    {page.items.length === 0 ? <p>这一页尚无运行记录。</p> : null}
    {page.items.map(run => <article key={run.id} className="private-card">
      <strong>{run.source} · 第{run.attempt}次尝试</strong>
      <p>{new Date(run.at).toLocaleString()} · {run.detail ?? '准备活动'}</p>
      <details><summary>步骤与错误详情</summary><p>活动：{run.activity}</p>
        {run.steps?.map((step, index) => <p key={index}>{new Date(step.at).toLocaleTimeString()} {step.detail}</p>)}
        {run.error ? <p role="status">{run.error}</p> : null}
        {run.stack ? <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{run.stack}</pre> : null}
      </details>
    </article>)}
    <button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - 8))}>上一页</button>
    <button disabled={page.next_offset === null} onClick={() => { if (page.next_offset !== null) setOffset(page.next_offset); }}>下一页</button>
    <button onClick={() => {
      const blob = new Blob([JSON.stringify(page.items, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url;
      a.download = `operit-runs-${offset}.json`; a.click(); URL.revokeObjectURL(url);
    }}>导出本页</button>
    {error ? <p role="alert">{error}</p> : null}
  </details>;
}
