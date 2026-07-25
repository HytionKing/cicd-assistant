(async function () {
  const tbody = document.getElementById('task-tbody');
  const pagerEl = document.getElementById('task-pager');
  const countHint = document.getElementById('task-count-hint');
  const form = document.getElementById('filter-form');
  const selStatus = document.getElementById('f-status');
  const inpFrom = document.getElementById('f-from');
  const inpTo = document.getElementById('f-to');
  const PAGE_SIZES = [10, 20, 50, 100];
  let pageSize = 20;
  let currentPage = 1;
  let total = 0;
  let lastPagerState = '';
  // 保存当前生效的筛选条件（提交查询时才刷新），轮询按同一份条件重拉
  let filters = { repoIds: [], branches: [], status: '', from: '', to: '' };

  // 仓库多选：tom-select 接管，remove_button 插件加叉号可移除单个
  let allRepos = [];
  try {
    allRepos = await api.get('/api/repos') || [];
  } catch (e) { /* 静默 */ }
  const repoTs = new TomSelect('#f-repo', {
    plugins: ['remove_button'],
    valueField: 'id',
    labelField: 'name',
    searchField: 'name',
    options: allRepos,
    maxOptions: 200,
    placeholder: '全部仓库',
    hideSelected: true
  });

  // 分支多选：无远端候选，允许用户键入回车自由创建 tag（create: true + delimiter 分割）
  const branchTs = new TomSelect('#f-branch', {
    plugins: ['remove_button'],
    create: true,
    createOnBlur: true,
    persist: false,
    delimiter: ',',
    placeholder: '留空=全部；输入分支名回车添加'
  });

  // tom-select 不会自动把 <select> 的 form-select class 复制到生成的 wrapper 上，
  // 手动加一下让边框/背景走 Tabler 的 form-select 样式
  repoTs.wrapper.classList.add('form-select', 'form-select-sm');
  branchTs.wrapper.classList.add('form-select', 'form-select-sm');

  // 日期：flatpickr 中文本地化。ISO 格式（Y-m-d）跟后端 normalizeFrom/To 期望一致
  flatpickr.localize(flatpickr.l10ns.zh);
  const fromFp = flatpickr('#f-from', { dateFormat: 'Y-m-d', allowInput: true });
  const toFp   = flatpickr('#f-to',   { dateFormat: 'Y-m-d', allowInput: true });

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  function keepAliveBadge(k) {
    // 后端 Boolean，null 视作 true（老数据兼容）
    const on = k === null || k === undefined ? true : !!k;
    return on
      ? '<span class="badge bg-green-lt">是</span>'
      : '<span class="badge bg-secondary-lt">否</span>';
  }

  // 计算分页按钮序列：1 ... cur-1 cur cur+1 ... last
  function pageItems(current, totalPages) {
    if (totalPages <= 7) {
      return Array.from({ length: totalPages }, (_, i) => i + 1);
    }
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
    // 状态没变就不动 DOM —— 否则定时轮询每 5 秒重建 pager，会销毁
    // Bootstrap 已经绑好的 dropdown 实例，导致下拉点不开
    const state = `${total}|${currentPage}|${pageSize}|${totalPages}`;
    if (state === lastPagerState) return;
    lastPagerState = state;

    const items = pageItems(currentPage, totalPages);
    const prevDis = currentPage <= 1 ? 'disabled' : '';
    const nextDis = currentPage >= totalPages ? 'disabled' : '';

    pagerEl.innerHTML = `
      <div class="d-flex align-items-center">
        <div class="dropup">
          <button class="btn dropdown-toggle" type="button"
                  data-bs-toggle="dropdown" data-bs-display="static" aria-expanded="false">
            ${pageSize} 条/页
          </button>
          <div class="dropdown-menu">
            ${PAGE_SIZES.map(n => `<a class="dropdown-item ${n === pageSize ? 'active' : ''}" href="#" data-size="${n}">${n} 条/页</a>`).join('')}
          </div>
        </div>
        <div class="text-secondary small ms-3 d-none d-md-inline">共 ${total} 条</div>
        <ul class="pagination m-0 ms-auto">
          <li class="page-item ${prevDis}">
            <a class="page-link" href="#" data-go="prev" aria-label="prev">
              <i class="ti ti-chevron-left"></i>
            </a>
          </li>
          ${items.map(p => {
            if (p === '...') return `<li class="page-item disabled"><span class="page-link">…</span></li>`;
            const active = p === currentPage ? 'active' : '';
            return `<li class="page-item ${active}"><a class="page-link" href="#" data-page="${p}">${p}</a></li>`;
          }).join('')}
          <li class="page-item ${nextDis}">
            <a class="page-link" href="#" data-go="next" aria-label="next">
              <i class="ti ti-chevron-right"></i>
            </a>
          </li>
        </ul>
      </div>
    `;
    // 重渲染后显式初始化 dropdown 实例（兜底）
    pagerEl.querySelectorAll('[data-bs-toggle="dropdown"]').forEach(t => {
      tabler.Dropdown.getOrCreateInstance(t);
    });
  }

  // 把 filters 拼成 URL query string
  function buildQuery() {
    const params = new URLSearchParams();
    params.set('page', currentPage);
    params.set('size', pageSize);
    filters.repoIds.forEach(id => params.append('repoIds', id));
    filters.branches.forEach(b => params.append('branches', b));
    if (filters.status) params.set('status', filters.status);
    if (filters.from) params.set('createdFrom', filters.from);
    if (filters.to) params.set('createdTo', filters.to);
    return params.toString();
  }

  async function load() {
    const d = await api.get('/api/tasks?' + buildQuery());
    total = d.total || 0;
    countHint.textContent = total > 0 ? `共 ${total} 条` : '';
    const items = d.items || [];
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
        <td>${keepAliveBadge(t.keepAlive)}</td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(t.createdAt))}</small></td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(t.finishedAt))}</small></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <a class="btn btn-sm" href="/tasks/${t.id}"><i class="ti ti-eye me-1"></i>查看</a>
            <button class="btn btn-sm btn-outline-danger" data-id="${t.id}"><i class="ti ti-trash me-1"></i>删除</button>
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="8" class="text-center text-secondary py-4">没有匹配的任务</td></tr>';
    renderPager();
  }

  // 表单提交 = 应用筛选并回到第 1 页
  form.addEventListener('submit', (ev) => {
    ev.preventDefault();
    filters = {
      repoIds: repoTs.getValue() || [],
      branches: branchTs.getValue() || [],
      status: selStatus.value,
      from: inpFrom.value,
      to: inpTo.value
    };
    currentPage = 1;
    lastPagerState = ''; // 强制 pager 重渲
    load();
  });

  document.getElementById('btn-reset').addEventListener('click', () => {
    // 清空表单并回到无筛选状态
    repoTs.clear();
    branchTs.clear();
    branchTs.clearOptions();
    selStatus.value = '';
    fromFp.clear();
    toFp.clear();
    filters = { repoIds: [], branches: [], status: '', from: '', to: '' };
    currentPage = 1;
    lastPagerState = '';
    load();
  });

  document.getElementById('btn-refresh').addEventListener('click', load);

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const ok = await UI.confirm({
      title: '确认删除该任务？',
      text: '仍在运行的进程会被一并停止，端口将释放。该任务的编译/运行日志目录会一并清除。'
    });
    if (!ok) return;
    await api.del('/api/tasks/' + btn.dataset.id);
    await load();
    UI.success('任务已删除');
  });

  pagerEl.addEventListener('click', (ev) => {
    const sizeLink = ev.target.closest('a[data-size]');
    if (sizeLink) {
      ev.preventDefault();
      const newSize = Number(sizeLink.dataset.size);
      if (newSize !== pageSize) {
        pageSize = newSize;
        currentPage = 1;
        lastPagerState = '';
        load();
      }
      return;
    }
    const goLink = ev.target.closest('a[data-go]');
    if (goLink) {
      ev.preventDefault();
      if (goLink.parentElement.classList.contains('disabled')) return;
      if (goLink.dataset.go === 'prev') currentPage--;
      else if (goLink.dataset.go === 'next') currentPage++;
      load();
      return;
    }
    const pageLink = ev.target.closest('a[data-page]');
    if (pageLink) {
      ev.preventDefault();
      const p = Number(pageLink.dataset.page);
      if (p !== currentPage) {
        currentPage = p;
        load();
      }
    }
  });

  await load();
  setInterval(load, 5000);
})();
