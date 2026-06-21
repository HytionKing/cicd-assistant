(async function () {
  const tbody = document.getElementById('task-tbody');
  const pagerEl = document.getElementById('task-pager');
  const PAGE_SIZE = 20;
  let currentPage = 1;
  let total = 0;

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  function renderPager() {
    const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
    const prevDis = currentPage <= 1 ? 'disabled' : '';
    const nextDis = currentPage >= pages ? 'disabled' : '';
    pagerEl.innerHTML = `
      <div class="d-flex align-items-center">
        <span class="text-secondary small">共 ${total} 条，第 ${currentPage} / ${pages} 页</span>
        <ul class="pagination m-0 ms-auto">
          <li class="page-item ${prevDis}"><a class="page-link" href="#" data-go="prev">
            <i class="ti ti-chevron-left"></i> 上一页</a></li>
          <li class="page-item ${nextDis}"><a class="page-link" href="#" data-go="next">
            下一页 <i class="ti ti-chevron-right"></i></a></li>
        </ul>
      </div>
    `;
  }

  async function load() {
    const d = await api.get(`/api/tasks?page=${currentPage}&size=${PAGE_SIZE}`);
    total = d.total || 0;
    const items = d.items || [];
    // 当前页空且不在第 1 页 → 退一页（用户刚删光当前页最后一条）
    if (items.length === 0 && currentPage > 1) {
      currentPage--;
      return load();
    }
    tbody.innerHTML = items.map(t => `
      <tr>
        <td><a href="/tasks/${t.id}" class="text-decoration-none">#${t.id}</a></td>
        <td><strong>${escapeHtml(t.repoName || '')}</strong></td>
        <td class="text-secondary"><small>${escapeHtml(t.branches || '')}</small></td>
        <td>${statusBadge(t.status)}</td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(t.createdAt))}</small></td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(t.finishedAt))}</small></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <a class="btn btn-sm" href="/tasks/${t.id}"><i class="ti ti-eye me-1"></i>查看</a>
            <button class="btn btn-sm btn-outline-danger" data-id="${t.id}"><i class="ti ti-trash me-1"></i>删除</button>
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="7" class="text-center text-secondary py-4">还没有任务，去"代码启动"创建一个吧</td></tr>';
    renderPager();
  }

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    if (!confirm('删除任务会停止仍在运行的服务，确认？')) return;
    await api.del('/api/tasks/' + btn.dataset.id);
    await load();
  });

  pagerEl.addEventListener('click', (ev) => {
    const a = ev.target.closest('a[data-go]');
    if (!a) return;
    ev.preventDefault();
    if (a.parentElement.classList.contains('disabled')) return;
    if (a.dataset.go === 'prev') currentPage--;
    else if (a.dataset.go === 'next') currentPage++;
    load();
  });

  await load();
  setInterval(load, 5000);
})();
