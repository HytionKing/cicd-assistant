(async function () {
  const tbody = document.getElementById('task-tbody');

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  async function load() {
    const list = await api.get('/api/tasks');
    tbody.innerHTML = list.map(t => `
      <tr>
        <td><a href="/tasks/${t.id}" class="text-decoration-none">#${t.id}</a></td>
        <td><strong>${escapeHtml(t.repoName || '')}</strong></td>
        <td class="text-secondary"><small>${escapeHtml(t.branches || '')}</small></td>
        <td>${statusBadge(t.status)}</td>
        <td><small class="text-secondary">${escapeHtml(t.createdAt || '')}</small></td>
        <td><small class="text-secondary">${escapeHtml(t.finishedAt || '')}</small></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <a class="btn btn-sm" href="/tasks/${t.id}"><i class="ti ti-eye me-1"></i>查看</a>
            <button class="btn btn-sm btn-outline-danger" data-id="${t.id}"><i class="ti ti-trash me-1"></i>删除</button>
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="7" class="text-center text-secondary py-4">还没有任务，去"代码启动"创建一个吧</td></tr>';
  }

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    if (!confirm('删除任务会停止仍在运行的服务，确认？')) return;
    await api.del('/api/tasks/' + btn.dataset.id);
    await load();
  });

  await load();
  setInterval(load, 5000);
})();
