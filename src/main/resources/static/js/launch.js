(async function () {
  const sel = document.getElementById('sel-repo');
  const branchesBox = document.getElementById('branches-box');
  const modulesBox = document.getElementById('modules-box');
  const modulesActions = document.getElementById('modules-actions');
  const msg = document.getElementById('msg');

  const repos = await api.get('/api/repos');
  sel.innerHTML = repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('')
    || '<option value="">(没有仓库，先去仓库管理添加)</option>';

  // 通知 webhook 列表：只列 enabled=1 的，选空则不推
  const selWebhook = document.getElementById('sel-webhook');
  try {
    const hooks = await api.get('/api/notifications');
    const enabled = (hooks || []).filter(w => w.enabled === 1);
    selWebhook.innerHTML = '<option value="">不推送</option>' +
      enabled.map(w => `<option value="${w.id}">${escapeHtml(w.name)}</option>`).join('');
  } catch (e) { /* 通知列表拉不到就静默降级，不阻塞主流程 */ }

  function checkRow(value, opts) {
    // opts.checked：默认勾上；opts.manual：手动加的分支加个 badge 让用户一眼看到区别
    const checked = opts && opts.checked ? ' checked' : '';
    const manual = opts && opts.manual
      ? ' <span class="badge bg-blue-lt ms-1" style="font-size:.68rem">手动</span>' : '';
    return `<label class="form-check"><input type="checkbox" class="form-check-input" value="${escapeHtml(value)}"${checked}/><span class="form-check-label">${escapeHtml(value)}${manual}</span></label>`;
  }

  // 已存在的分支不再重复加（大小写敏感精确匹配即可，git branch 本身区分大小写）
  function branchExists(name) {
    return !!branchesBox.querySelector(`input[type=checkbox][value="${CSS.escape(name)}"]`);
  }

  async function loadModules() {
    const id = sel.value;
    if (!id) return;
    try {
      const r = await api.get('/api/repos/' + id + '/modules');
      if (r.configured && r.modules.length > 0) {
        modulesBox.innerHTML = r.modules.map(checkRow).join('');
        modulesActions.classList.remove('hidden');
      } else {
        modulesBox.innerHTML = '<span class="text-secondary small">未配置模块列表，启动时将自动扫描全部 SpringBoot 模块。如需限定，请到"仓库管理"填写"模块列表"。</span>';
        modulesActions.classList.add('hidden');
      }
    } catch (e) {
      modulesBox.innerHTML = '<span class="text-danger small">加载模块列表失败: ' + escapeHtml(e.message) + '</span>';
      modulesActions.classList.add('hidden');
    }
  }

  sel.addEventListener('change', () => {
    branchesBox.innerHTML = '<span class="text-secondary small">点击"拉取分支"加载</span>';
    loadModules();
  });
  await loadModules();

  document.getElementById('btn-load-branches').onclick = async () => {
    const id = sel.value;
    if (!id) return;
    msg.textContent = '加载分支中...';
    branchesBox.innerHTML = '<span class="text-secondary small">加载中...</span>';
    try {
      const r = await api.get('/api/repos/' + id + '/branches');
      if (!r.success) {
        msg.textContent = '获取失败: ' + r.message;
        branchesBox.innerHTML = '<span class="text-danger small">' + escapeHtml(r.message || '') + '</span>';
        return;
      }
      branchesBox.innerHTML = r.branches.map(checkRow).join('') || '<span class="text-secondary small">没有分支</span>';
      msg.textContent = '共 ' + r.branches.length + ' 个分支';
    } catch (e) {
      msg.textContent = '请求失败: ' + e.message;
    }
  };

  // 手动添加分支：把远端拉不到的分支（临时/hotfix/尚未推的本地分支）直接键入加进列表
  const manualInput = document.getElementById('txt-manual-branch');
  function addManualBranches() {
    const raw = (manualInput.value || '').trim();
    if (!raw) return;
    const names = raw.split(/[,，\s]+/).map(s => s.trim()).filter(Boolean);
    const added = [];
    const skipped = [];
    for (const n of names) {
      if (branchExists(n)) { skipped.push(n); continue; }
      // 首次添加时 branchesBox 里可能还是"点击拉取分支加载"提示，需要先清掉
      if (branchesBox.querySelector('span.text-secondary')) branchesBox.innerHTML = '';
      branchesBox.insertAdjacentHTML('beforeend', checkRow(n, { checked: true, manual: true }));
      added.push(n);
    }
    manualInput.value = '';
    if (added.length) msg.textContent = '已添加 ' + added.length + ' 个手动分支';
    if (skipped.length) UI.warning('这些分支已在列表：' + skipped.join(', '));
  }
  document.getElementById('btn-add-manual').onclick = addManualBranches;
  manualInput.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter') { ev.preventDefault(); addManualBranches(); }
  });

  // 保活开关：关掉的提示文字改一下让用户知道后果
  const chkKeepAlive = document.getElementById('chk-keep-alive');
  const keepAliveHint = document.getElementById('keep-alive-hint');
  chkKeepAlive.addEventListener('change', () => {
    keepAliveHint.textContent = chkKeepAlive.checked
      ? '保留进程供访问'
      : '启动+swagger验证通过后立即停止（省内存）';
    keepAliveHint.classList.toggle('text-warning', !chkKeepAlive.checked);
    keepAliveHint.classList.toggle('text-secondary', chkKeepAlive.checked);
  });

  document.getElementById('btn-all').onclick = (ev) => {
    ev.preventDefault();
    modulesBox.querySelectorAll('input[type=checkbox]').forEach(i => i.checked = true);
  };
  document.getElementById('btn-none').onclick = (ev) => {
    ev.preventDefault();
    modulesBox.querySelectorAll('input[type=checkbox]').forEach(i => i.checked = false);
  };

  document.getElementById('btn-start').onclick = async () => {
    const id = sel.value;
    if (!id) return;
    const branches = Array.from(branchesBox.querySelectorAll('input[type=checkbox]:checked')).map(i => i.value);
    if (branches.length === 0) { UI.warning('请选择至少一个分支'); return; }
    const modules = Array.from(modulesBox.querySelectorAll('input[type=checkbox]:checked')).map(i => i.value).join(',');
    try {
      const t = await api.post('/api/tasks', {
        repoId: Number(id),
        branches,
        modules,
        keepAlive: chkKeepAlive.checked,
        notifyWebhookId: selWebhook.value ? Number(selWebhook.value) : null
      });
      msg.textContent = '任务已创建 #' + t.id;
      setTimeout(() => { location.href = '/tasks/' + t.id; }, 500);
    } catch (e) {
      msg.textContent = '创建失败: ' + e.message;
    }
  };
})();
