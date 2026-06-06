(async function () {
  const tbody = document.getElementById('task-tbody');

  async function load() {
    const list = await api.get('/api/tasks');
    tbody.innerHTML = list.map(t => `
      <tr>
        <td><a href="/tasks/${t.id}">#${t.id}</a></td>
        <td>${escapeHtml(t.repoName || '')}</td>
        <td>${escapeHtml(t.branches || '')}</td>
        <td><span class="status ${t.status}">${t.status}</span></td>
        <td>${escapeHtml(t.createdAt || '')}</td>
        <td>${escapeHtml(t.finishedAt || '')}</td>
        <td>
          <a class="btn" href="/tasks/${t.id}">查看</a>
          <button class="btn danger" data-id="${t.id}">删除</button>
        </td>
      </tr>
    `).join('');
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
