// 尝试从错误响应里提取后端返回的 message 字段，否则退回到 "HTTP <code>"。
async function _err(r) {
  try {
    const ct = r.headers.get('content-type') || '';
    if (ct.indexOf('application/json') >= 0) {
      const j = await r.json();
      if (j && j.message) return new Error(j.message);
    } else {
      const t = await r.text();
      if (t) return new Error(t.length > 200 ? t.slice(0, 200) : t);
    }
  } catch (_) { /* ignore */ }
  return new Error('HTTP ' + r.status);
}

window.api = {
  async get(url) {
    const r = await fetch(url);
    if (!r.ok) throw await _err(r);
    return r.json();
  },
  async post(url, body) {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : null });
    if (!r.ok) throw await _err(r);
    return r.status === 204 ? null : r.json();
  },
  async put(url, body) {
    const r = await fetch(url, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!r.ok) throw await _err(r);
    return r.json();
  },
  async del(url) {
    const r = await fetch(url, { method: 'DELETE' });
    if (!r.ok) throw await _err(r);
  }
};

window.escapeHtml = function (s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
};

// "2026-06-21T17:40:13.448" -> "2026-06-21 17:40:13"
// 空值返回空串，非标准格式原样返回（不破坏显示）
window.fmtDate = function (s) {
  if (s == null || s === '') return '';
  const str = String(s);
  // ISO local datetime: YYYY-MM-DDTHH:MM:SS[.SSS]
  const m = str.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/);
  return m ? `${m[1]} ${m[2]}` : str;
};

// 任务终态：详情页不再需要轮询
window.TERMINAL_TASK = new Set(['SUCCESS', 'FAILED', 'PARTIAL', 'STOPPED']);
// 模块终态：日志已经定格，弹窗不必默认自动刷新
window.TERMINAL_MODULE = new Set(['FAILED', 'STOPPED']);

// 各种业务状态 → Tabler badge color
window.STATUS_BADGE = {
  PENDING:  'bg-blue-lt',
  CLONING:  'bg-blue-lt',
  SCANNING: 'bg-blue-lt',
  BUILDING: 'bg-yellow-lt',
  STARTING: 'bg-yellow-lt',
  RUNNING:  'bg-yellow-lt',
  SUCCESS:  'bg-green-lt',
  FAILED:   'bg-red-lt',
  PARTIAL:  'bg-orange-lt',
  STOPPED:  'bg-secondary-lt'
};
