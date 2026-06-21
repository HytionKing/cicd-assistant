window.api = {
  async get(url) {
    const r = await fetch(url);
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  },
  async post(url, body) {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : null });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.status === 204 ? null : r.json();
  },
  async put(url, body) {
    const r = await fetch(url, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  },
  async del(url) {
    const r = await fetch(url, { method: 'DELETE' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
  }
};

window.escapeHtml = function (s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
};

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
