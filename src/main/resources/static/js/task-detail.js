(async function () {
  const taskId = document.getElementById('task-id').textContent;
  const summary = document.getElementById('task-summary');
  const tbody = document.getElementById('modules-tbody');
  const logModal = document.getElementById('log-modal');
  const logContent = document.getElementById('log-content');
  const logTitle = document.getElementById('log-title');
  let currentModuleId = null;
  let currentModuleName = '';
  let currentBranch = '';
  let currentType = 'run';
  // 用 token 区分每次"打开/切换"日志请求，避免上一个模块的 in-flight 请求覆盖当前内容
  let logRequestToken = 0;
  let logTimer = null;

  async function load() {
    const d = await api.get('/api/tasks/' + taskId);
    const t = d.task;
    if (!t) { summary.textContent = '任务不存在'; return; }
    summary.innerHTML = `
      <div><b>仓库：</b>${escapeHtml(t.repoName || '')}</div>
      <div><b>分支：</b>${escapeHtml(t.branches || '')}</div>
      <div><b>指定模块：</b>${escapeHtml(t.modules || '(自动扫描)')}</div>
      <div><b>状态：</b><span class="status ${t.status}">${t.status}</span></div>
      <div><b>创建：</b>${escapeHtml(t.createdAt || '')} <b>开始：</b>${escapeHtml(t.startedAt || '')} <b>完成：</b>${escapeHtml(t.finishedAt || '')}</div>
      ${t.errorMessage ? `<div><b>错误：</b>${escapeHtml(t.errorMessage)}</div>` : ''}
    `;

    tbody.innerHTML = (d.modules || []).map(m => `
      <tr>
        <td>${escapeHtml(m.branch)}</td>
        <td>${escapeHtml(m.moduleName)}</td>
        <td><span class="status ${m.status}">${m.status}</span></td>
        <td>${m.port || ''}</td>
        <td>${m.pid || ''}</td>
        <td>${escapeHtml(m.keepAliveUntil || '')}</td>
        <td>${m.swaggerUrl ? `<a href="${escapeHtml(m.swaggerUrl)}" target="_blank">打开</a>` : ''}</td>
        <td>${escapeHtml(m.errorMessage || '')}</td>
        <td>
          <button class="btn" data-act="log" data-id="${m.id}"
                  data-name="${escapeHtml(m.moduleName || '')}"
                  data-branch="${escapeHtml(m.branch || '')}">日志</button>
          ${m.status === 'SUCCESS' || m.status === 'RUNNING' ? `<button class="btn danger" data-act="stop" data-id="${m.id}">停止</button>` : ''}
        </td>
      </tr>
    `).join('');
  }

  async function loadLog() {
    if (!currentModuleId) return;
    const myToken = ++logRequestToken;
    const reqModuleId = currentModuleId;
    const reqType = currentType;
    try {
      const r = await api.get('/api/tasks/modules/' + reqModuleId + '/log?type=' + reqType);
      // 期间用户已经切到了别的模块/类型，丢弃这次响应
      if (myToken !== logRequestToken) return;
      const atBottom = logContent.scrollTop + logContent.clientHeight >= logContent.scrollHeight - 20;
      logContent.textContent = r.content || '(空)';
      if (atBottom) logContent.scrollTop = logContent.scrollHeight;
    } catch (e) {
      if (myToken !== logRequestToken) return;
      logContent.textContent = '加载失败: ' + e.message;
    }
  }

  function startLogAutoRefresh() {
    stopLogAutoRefresh();
    if (document.getElementById('chk-log-auto').checked) {
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
    logTitle.textContent = `${name}${branchPart} - ${typeLabel}`;
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
      // 切换模块前，先把旧定时器和旧内容清掉，防止旧定时器/旧内容残留
      stopLogAutoRefresh();
      logContent.textContent = '加载中...';
      currentModuleId = id;
      currentModuleName = btn.dataset.name || '';
      currentBranch = btn.dataset.branch || '';
      currentType = 'run';
      updateLogTitle();
      document.querySelectorAll('.log-tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.type === 'run'));
      logModal.classList.remove('hidden');
      await loadLog();
      startLogAutoRefresh();
    }
  });

  document.querySelectorAll('.log-tabs .tab').forEach(t => {
    t.onclick = async () => {
      if (currentType === t.dataset.type) return;
      currentType = t.dataset.type;
      document.querySelectorAll('.log-tabs .tab').forEach(x => x.classList.toggle('active', x === t));
      updateLogTitle();
      logContent.textContent = '加载中...';
      await loadLog();
    };
  });

  document.getElementById('btn-log-refresh').onclick = loadLog;
  document.getElementById('chk-log-auto').onchange = startLogAutoRefresh;
  document.getElementById('btn-log-close').onclick = () => {
    stopLogAutoRefresh();
    currentModuleId = null;          // 关掉后任何残留的 fetch 都立刻无效
    logRequestToken++;
    logModal.classList.add('hidden');
  };

  await load();
  setInterval(load, 4000);
})();
