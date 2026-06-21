(async function () {
  const sel = document.getElementById('sel-repo');
  const branchesBox = document.getElementById('branches-box');
  const modulesBox = document.getElementById('modules-box');
  const modulesActions = document.getElementById('modules-actions');
  const msg = document.getElementById('msg');

  const repos = await api.get('/api/repos');
  sel.innerHTML = repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('')
    || '<option value="">(没有仓库，先去仓库管理添加)</option>';

  function checkRow(value) {
    return `<label class="form-check"><input type="checkbox" class="form-check-input" value="${escapeHtml(value)}"/><span class="form-check-label">${escapeHtml(value)}</span></label>`;
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
      const t = await api.post('/api/tasks', { repoId: Number(id), branches, modules });
      msg.textContent = '任务已创建 #' + t.id;
      setTimeout(() => { location.href = '/tasks/' + t.id; }, 500);
    } catch (e) {
      msg.textContent = '创建失败: ' + e.message;
    }
  };
})();
