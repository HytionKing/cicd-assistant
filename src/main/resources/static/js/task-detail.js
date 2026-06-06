(async function () {
  const taskId = document.getElementById('task-id').textContent;
  const summary = document.getElementById('task-summary');
  const tbody = document.getElementById('modules-tbody');
  const logModal = document.getElementById('log-modal');
  const logContent = document.getElementById('log-content');
  const logTitle = document.getElementById('log-title');
  let currentModuleId = null;
  let currentType = 'run';

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
          <button class="btn" data-act="log" data-id="${m.id}">日志</button>
          ${m.status === 'SUCCESS' || m.status === 'RUNNING' ? `<button class="btn danger" data-act="stop" data-id="${m.id}">停止</button>` : ''}
        </td>
      </tr>
    `).join('');
  }

  async function loadLog() {
    if (!currentModuleId) return;
    try {
      const r = await api.get('/api/tasks/modules/' + currentModuleId + '/log?type=' + currentType);
      logContent.textContent = r.content || '(空)';
    } catch (e) {
      logContent.textContent = '加载失败: ' + e.message;
    }
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
      currentModuleId = id;
      currentType = 'run';
      logTitle.textContent = '模块 #' + id + ' 日志';
      document.querySelectorAll('.log-tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.type === 'run'));
      logModal.classList.remove('hidden');
      await loadLog();
    }
  });

  document.querySelectorAll('.log-tabs .tab').forEach(t => {
    t.onclick = async () => {
      currentType = t.dataset.type;
      document.querySelectorAll('.log-tabs .tab').forEach(x => x.classList.toggle('active', x === t));
      await loadLog();
    };
  });

  document.getElementById('btn-log-close').onclick = () => logModal.classList.add('hidden');

  await load();
  setInterval(load, 4000);
})();
