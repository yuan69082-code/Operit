import { useEffect, useState } from 'react';

type RuntimeStatus = {
  state: string; enabled: boolean; reason: string;
  idle: { enabled?: boolean; interval_minutes?: number; daily_limit?: number; held?: boolean };
};
type Command = { action: 'runtime_status' } | { action: 'runtime_control'; operation: 'enable'; enabled: boolean }
  | { action: 'runtime_control'; operation: 'pause'; minutes?: number }
  | { action: 'runtime_control'; operation: 'resume' }
  | { action: 'runtime_control'; operation: 'idle'; enabled: boolean; minutes: number; daily: number };
export function AutonomousRuntimePanel({ token, chat }: { token: string; chat: string }) {
  const [status, setStatus] = useState<RuntimeStatus | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [idle, setIdle] = useState(false);
  const [minutes, setMinutes] = useState('30');
  const [daily, setDaily] = useState('12');
  async function call(command: Command, signal?: AbortSignal): Promise<RuntimeStatus> {
    const response = await fetch('/api/web/private-state', { method: 'POST', signal,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...command, chat_id: chat }) });
    if (!response.ok) throw new Error(await response.text());
    return response.json();
  }
  useEffect(() => {
    const controller = new AbortController();
    let first = true;
    async function refresh() {
      try {
        const next = await call({ action: 'runtime_status' }, controller.signal);
        if (controller.signal.aborted) return;
        setStatus(next);
        if (first) { setIdle(next.idle.enabled ?? false); setMinutes(String(next.idle.interval_minutes ?? 30)); setDaily(String(next.idle.daily_limit ?? 12)); first = false; }
      } catch (failure) {
        if (!controller.signal.aborted) { console.error('runtime.read', failure); setError(failure instanceof Error ? failure.message : '读取失败'); }
      }
    }
    void refresh();
    const timer = setInterval(() => { void refresh(); }, 5000);
    return () => { controller.abort(); clearInterval(timer); };
  }, [token, chat]);
  async function change(command: Command) {
    setBusy(true); setError('');
    try { setStatus(await call(command)); }
    catch (failure) { console.error('runtime.change', failure); setError(failure instanceof Error ? failure.message : '保存失败'); }
    finally { setBusy(false); }
  }
  return <details className="private-card"><summary>自主运行 · {status?.state ?? '读取中'}</summary>
    <p>总开关控制手机上所有助手的后台自主活动；普通聊天不受影响。空闲接续只设置当前助手。</p>
    {status ? <label><input type="checkbox" checked={status.enabled} disabled={busy} onChange={e => void change({ action: 'runtime_control', operation: 'enable', enabled: e.target.checked })} />允许后台自主运行</label> : null}
    <p role="status">{status?.state} {status?.reason}</p>
    <button disabled={busy} onClick={() => void change({ action: 'runtime_control', operation: 'pause' })}>暂停</button>
    <button disabled={busy} onClick={() => void change({ action: 'runtime_control', operation: 'pause', minutes: 60 })}>暂停1小时</button>
    <button disabled={busy} onClick={() => void change({ action: 'runtime_control', operation: 'resume' })}>恢复</button>
    <p>恢复不重做未知操作。系统强制停止期间无法运行，等待不代表模型一直在思考。</p>
    <label><input type="checkbox" checked={idle} onChange={e => setIdle(e.target.checked)} />为此助手提供空闲判断机会</label>
    <label>间隔分钟（1–1440）<input type="number" min="1" max="1440" value={minutes} onChange={e => setMinutes(e.target.value)} /></label>
    <label>每日最多轮次（1–96）<input type="number" min="1" max="96" value={daily} onChange={e => setDaily(e.target.value)} /></label>
    <p>需开启内部状态。沿用现有闭环；额外调用消耗模型额度。助手可选择延后、暂停或无结论。</p>
    {status?.idle.held ? <p>助手已暂停空闲接续，重新保存可恢复机会。</p> : null}
    <button disabled={busy} onClick={() => {
      const m = Number(minutes), d = Number(daily);
      if (!Number.isInteger(m) || m < 1 || m > 1440 || !Number.isInteger(d) || d < 1 || d > 96) { setError('请填写范围内整数'); return; }
      void change({ action: 'runtime_control', operation: 'idle', enabled: idle, minutes: m, daily: d });
    }}>保存空闲设置</button>
    {error ? <p role="alert">{error}</p> : null}
  </details>;
}
