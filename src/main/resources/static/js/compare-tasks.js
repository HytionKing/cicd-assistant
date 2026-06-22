(async function () {
  const tbody = document.getElementById('ct-tbody');
  const pagerEl = document.getElementById('ct-pager');
  const PAGE_SIZES = [10, 20, 50, 100];
  let pageSize = 20;
  let currentPage = 1;
  let total = 0;
  let lastPagerState = '';

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  function pageItems(current, totalPages) {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i + 1);
    const set = new Set([1, totalPages, current, current - 1, current + 1, current - 2, current + 2]);
    const sorted = Array.from(set).filter(n => n >= 1 && n <= totalPages).sort((a, b) => a - b);
    const out = [];
    for (let i = 0; i < sorted.length; i++) {
      out.push(sorted[i]);
      if (i + 1 < sorted.length && sorted[i + 1] - sorted[i] > 1) out.push('...');
    }
    return out;
  }

  function renderPager() {
    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    if (currentPage > totalPages) currentPage = totalPages;
    const state = `${total}|${currentPage}|${pageSize}|${totalPages}`;
    if (state === lastPagerState) return;
    lastPagerState = state;

    const items = pageItems(currentPage, totalPages);
    const prevDis = currentPage <= 1 ? 'disabled' : '';
    const nextDis = currentPage >= totalPages ? 'disabled' : '';
    pagerEl.innerHTML = `
      <div class="d-flex align-items-center">
        <div class="dropup">
          <button class="btn dropdown-toggle" type="button" data-bs-toggle="dropdown" data-bs-display="static">
            ${pageSize} 条/页
          </button>
          <div class="dropdown-menu">
            ${PAGE_SIZES.map(n => `<a class="dropdown-item ${n === pageSize ? 'active' : ''}" href="#" data-size="${n}">${n} 条/页</a>`).join('')}
          </div>
        </div>
        <div class="text-secondary small ms-3 d-none d-md-inline">共 ${total} 条</div>
        <ul class="pagination m-0 ms-auto">
          <li class="page-item ${prevDis}"><a class="page-link" href="#" data-go="prev"><i class="ti ti-chevron-left"></i></a></li>
          ${items.map(p => p === '...'
            ? '<li class="page-item disabled"><span class="page-link">…</span></li>'
            : `<li class="page-item ${p === currentPage ? 'active' : ''}"><a class="page-link" href="#" data-page="${p}">${p}</a></li>`).join('')}
          <li class="page-item ${nextDis}"><a class="page-link" href="#" data-go="next"><i class="ti ti-chevron-right"></i></a></li>
        </ul>
      </div>
    `;
    pagerEl.querySelectorAll('[data-bs-toggle="dropdown"]').forEach(t => tabler.Dropdown.getOrCreateInstance(t));
  }

  async function load() {
    const d = await api.get(`/api/compare/tasks?page=${currentPage}&size=${pageSize}`);
    total = d.total || 0;
    const items = d.items || [];
    if (items.length === 0 && currentPage > 1) {
      currentPage--;
      return load();
    }
    tbody.innerHTML = items.map(t => {
      const progress = (t.progressTotal && t.progressTotal > 0)
        ? `${t.progressDone || 0} / ${t.progressTotal}`
        : '-';
      return `
        <tr>
          <td><a href="/compare/tasks/${t.id}" class="text-decoration-none">#${t.id}</a></td>
          <td><strong>${escapeHtml(t.repoName || '')}</strong></td>
          <td><code>${escapeHtml(t.baselineBranch)}</code> → <small class="text-secondary">${escapeHtml(t.targetBranches || '')}</small></td>
          <td><span class="badge bg-blue-lt">${escapeHtml(t.mode)}</span></td>
          <td>${statusBadge(t.status)}</td>
          <td><small>${progress}</small>${t.progressPhase ? `<br><small class="text-secondary">${escapeHtml(t.progressPhase)}</small>` : ''}</td>
          <td><small class="text-secondary">${escapeHtml(fmtDate(t.createdAt))}</small></td>
          <td><small class="text-secondary">${escapeHtml(fmtDate(t.finishedAt))}</small></td>
          <td class="text-end">
            <div class="btn-list justify-content-end">
              <a class="btn btn-sm" href="/compare/tasks/${t.id}"><i class="ti ti-eye me-1"></i>查看</a>
              <button class="btn btn-sm btn-outline-danger" data-id="${t.id}"><i class="ti ti-trash me-1"></i>删除</button>
            </div>
          </td>
        </tr>
      `;
    }).join('') || '<tr><td colspan="9" class="text-center text-secondary py-4">还没有任务</td></tr>';
    renderPager();
  }

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const ok = await UI.confirm({ title: '确认删除该检测任务？', text: '相关 findings 和子结果会一并删除。' });
    if (!ok) return;
    await api.del('/api/compare/tasks/' + btn.dataset.id);
    lastPagerState = '';   // 触发 pager 重新计算
    await load();
    UI.success('已删除');
  });

  pagerEl.addEventListener('click', (ev) => {
    const sizeLink = ev.target.closest('a[data-size]');
    if (sizeLink) {
      ev.preventDefault();
      const newSize = Number(sizeLink.dataset.size);
      if (newSize !== pageSize) { pageSize = newSize; currentPage = 1; load(); }
      return;
    }
    const goLink = ev.target.closest('a[data-go]');
    if (goLink) {
      ev.preventDefault();
      if (goLink.parentElement.classList.contains('disabled')) return;
      if (goLink.dataset.go === 'prev') currentPage--;
      else currentPage++;
      load();
      return;
    }
    const pageLink = ev.target.closest('a[data-page]');
    if (pageLink) {
      ev.preventDefault();
      const p = Number(pageLink.dataset.page);
      if (p !== currentPage) { currentPage = p; load(); }
    }
  });

  await load();
  setInterval(load, 5000);
})();
