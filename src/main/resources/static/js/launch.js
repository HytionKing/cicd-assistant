(async function () {
  const sel = document.getElementById('sel-repo');
  const branchesBox = document.getElementById('branches-box');
  const modulesBox = document.getElementById('modules-box');
  const modulesActions = document.getElementById('modules-actions');
  const msg = document.getElementById('msg');

  const repos = await api.get('/api/repos');
  sel.innerHTML = repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('');

  async function loadModules() {
    const id = sel.value;
    if (!id) return;
    try {
      const r = await api.get('/api/repos/' + id + '/modules');
      if (r.configured && r.modules.length > 0) {
        modulesBox.innerHTML = r.modules.map(m =>
          `<label><input type="checkbox" value="${escapeHtml(m)}"/> ${escapeHtml(m)}</label>`
        ).join('');
        modulesActions.classList.remove('hidden');
      } else {
        modulesBox.innerHTML = '<span class="hint">未配置模块列表，启动时将自动扫描全部 SpringBoot 模块。如需限定，请到"仓库管理"填写"模块列表"。</span>';
        modulesActions.classList.add('hidden');
      }
    } catch (e) {
      modulesBox.innerHTML = '<span class="hint">加载模块列表失败: ' + escapeHtml(e.message) + '</span>';
      modulesActions.classList.add('hidden');
    }
  }

  // 仓库切换 → 重新加载模块列表 + 清空已加载的分支
  sel.addEventListener('change', () => {
    branchesBox.innerHTML = '';
    loadModules();
  });
  await loadModules();

  document.getElementById('btn-load-branches').onclick = async () => {
    const id = sel.value;
    if (!id) return;
    msg.textContent = '加载分支中...';
    branchesBox.innerHTML = '';
    try {
      const r = await api.get('/api/repos/' + id + '/branches');
      if (!r.success) { msg.textContent = '获取失败: ' + r.message; return; }
      branchesBox.innerHTML = r.branches.map(b => `<label><input type="checkbox" value="${escapeHtml(b)}"/> ${escapeHtml(b)}</label>`).join('');
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
    if (branches.length === 0) { alert('请选择至少一个分支'); return; }
    // 一个都不勾 = 留空，后端走自动扫描（兼容老行为）
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
