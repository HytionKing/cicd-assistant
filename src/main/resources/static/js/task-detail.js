(async function () {
  const taskId = document.getElementById('task-id').textContent;
  const summary = document.getElementById('task-summary');
  const tbody = document.getElementById('modules-tbody');
  const refreshBtn = document.getElementById('btn-refresh');
  const logModalEl = document.getElementById('log-modal');
  const logModal = new bootstrap.Modal(logModalEl);
  const logContent = document.getElementById('log-content');
  const logTitle = document.getElementById('log-title');
  const chkLogAuto = document.getElementById('chk-log-auto');
  let currentModuleId = null;
  let currentModuleName = '';
  let currentBranch = '';
  let currentModuleStatus = '';
  let currentType = 'run';
  let logRequestToken = 0;
  let logTimer = null;
  let outerLoadTimer = null;
  let lastTaskStatus = '';

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  async function load() {
    const d = await api.get('/api/tasks/' + taskId);
    const t = d.task;
    if (!t) { summary.textContent = '任务不存在'; return; }
    summary.innerHTML = `
      <div class="row g-2">
        <div class="col-md-3"><span class="text-secondary">仓库</span><div><strong>${escapeHtml(t.repoName || '')}</strong></div></div>
        <div class="col-md-3"><span class="text-secondary">状态</span><div>${statusBadge(t.status)}</div></div>
        <div class="col-md-3"><span class="text-secondary">创建</span><div><small>${escapeHtml(fmtDate(t.createdAt))}</small></div></div>
        <div class="col-md-3"><span class="text-secondary">完成</span><div><small>${escapeHtml(fmtDate(t.finishedAt))}</small></div></div>
        <div class="col-md-6"><span class="text-secondary">分支</span><div><code>${escapeHtml(t.branches || '')}</code></div></div>
        <div class="col-md-6"><span class="text-secondary">指定模块</span><div><code>${escapeHtml(t.modules || '(自动扫描)')}</code></div></div>
        ${t.errorMessage ? `<div class="col-12"><div class="alert alert-danger mb-0 mt-2 py-2"><strong>错误：</strong>${escapeHtml(t.errorMessage)}</div></div>` : ''}
      </div>
    `;

    tbody.innerHTML = (d.modules || []).map(m => `
      <tr>
        <td><code>${escapeHtml(m.branch)}</code></td>
        <td><strong>${escapeHtml(m.moduleName)}</strong></td>
        <td>${statusBadge(m.status)}</td>
        <td>${m.port || ''}</td>
        <td><small class="text-secondary">${m.pid || ''}</small></td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(m.keepAliveUntil))}</small></td>
        <td>${m.swaggerUrl ? `<a href="${escapeHtml(m.swaggerUrl)}" target="_blank" class="text-decoration-none"><i class="ti ti-external-link me-1"></i>打开</a>` : ''}</td>
        <td><small class="text-danger">${escapeHtml(m.errorMessage || '')}</small></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <button class="btn btn-sm" data-act="log" data-id="${m.id}"
                    data-name="${escapeHtml(m.moduleName || '')}"
                    data-branch="${escapeHtml(m.branch || '')}"
                    data-status="${escapeHtml(m.status || '')}"><i class="ti ti-file-text me-1"></i>日志</button>
            ${m.status === 'SUCCESS' || m.status === 'RUNNING' ? `<button class="btn btn-sm btn-outline-danger" data-act="stop" data-id="${m.id}"><i class="ti ti-player-stop me-1"></i>停止</button>` : ''}
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="9" class="text-center text-secondary py-4">尚无模块</td></tr>';

    lastTaskStatus = t.status;
    // 任务进入终态 → 没必要继续 4 秒轮询了
    if (TERMINAL_TASK.has(t.status)) {
      stopOuterPoll();
    }
  }

  async function loadLog() {
    if (!currentModuleId) return;
    const myToken = ++logRequestToken;
    const reqModuleId = currentModuleId;
    const reqType = currentType;
    try {
      const r = await api.get('/api/tasks/modules/' + reqModuleId + '/log?type=' + reqType);
      if (myToken !== logRequestToken) return;
      const newText = r.content || '(空)';
      const oldText = logContent.textContent || '';
      if (newText === oldText) return;

      const prevScrollTop = logContent.scrollTop;
      const wasAtBottom = prevScrollTop + logContent.clientHeight >= logContent.scrollHeight - 20;

      if (oldText && newText.startsWith(oldText)) {
        const delta = newText.substring(oldText.length);
        logContent.appendChild(document.createTextNode(delta));
      } else {
        logContent.textContent = newText;
        logContent.scrollTop = prevScrollTop;
      }
      if (wasAtBottom) {
        logContent.scrollTop = logContent.scrollHeight;
      }
    } catch (e) {
      if (myToken !== logRequestToken) return;
      logContent.textContent = '加载失败: ' + e.message;
    }
  }

  function startLogAutoRefresh() {
    stopLogAutoRefresh();
    if (chkLogAuto.checked) {
      logTimer = setInterval(loadLog, 3000);
    }
  }
  function stopLogAutoRefresh() {
    if (logTimer) { clearInterval(logTimer); logTimer = null; }
  }

  function updateLogTitle() {
    const typeLabel = currentType === 'build' ? '编译日志' : '运行日志';
    const name = currentModuleName || ('#' + currentModuleId);
    const branchPart = currentBranch ? ` [${currentBranch}]` : '';
    logTitle.textContent = `${name}${branchPart} · ${typeLabel}`;
  }

  function startOuterPoll() {
    if (!outerLoadTimer) outerLoadTimer = setInterval(load, 4000);
  }
  function stopOuterPoll() {
    if (outerLoadTimer) { clearInterval(outerLoadTimer); outerLoadTimer = null; }
  }

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const id = btn.dataset.id;
    if (btn.dataset.act === 'stop') {
      if (!confirm('确认停止？')) return;
      await api.post('/api/tasks/modules/' + id + '/stop');
      await load();
    } else if (btn.dataset.act === 'log') {
      stopLogAutoRefresh();
      logContent.textContent = '加载中...';
      currentModuleId = id;
      currentModuleName = btn.dataset.name || '';
      currentBranch = btn.dataset.branch || '';
      currentModuleStatus = btn.dataset.status || '';
      currentType = 'run';
      // 模块终态（FAILED / STOPPED）日志已经定格，默认关闭自动刷新；其它状态默认开
      chkLogAuto.checked = !TERMINAL_MODULE.has(currentModuleStatus);
      updateLogTitle();
      document.querySelectorAll('.log-tab').forEach(t => t.classList.toggle('active', t.dataset.type === 'run'));
      logModal.show();
      await loadLog();
      startLogAutoRefresh();
    }
  });

  document.querySelectorAll('.log-tab').forEach(t => {
    t.onclick = async (ev) => {
      ev.preventDefault();
      if (currentType === t.dataset.type) return;
      currentType = t.dataset.type;
      document.querySelectorAll('.log-tab').forEach(x => x.classList.toggle('active', x === t));
      updateLogTitle();
      logContent.textContent = '加载中...';
      await loadLog();
    };
  });

  document.getElementById('btn-log-refresh').onclick = loadLog;
  chkLogAuto.onchange = startLogAutoRefresh;

  // 手动刷新按钮：随时点
  if (refreshBtn) refreshBtn.onclick = load;

  // 日志模态框打开期间停掉外层 4 秒轮询，关闭后再恢复（但若任务已是终态则不再恢复）
  logModalEl.addEventListener('show.bs.modal', stopOuterPoll);
  logModalEl.addEventListener('hidden.bs.modal', async () => {
    stopLogAutoRefresh();
    currentModuleId = null;
    logRequestToken++;
    // 关闭后立刻刷一次拿到最新状态；非终态再恢复 4 秒轮询，终态保持暂停
    await load();
    if (!TERMINAL_TASK.has(lastTaskStatus)) startOuterPoll();
  });

  await load();
  startOuterPoll();
})();
