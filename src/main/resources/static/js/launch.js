(async function () {
  const sel = document.getElementById('sel-repo');
  const box = document.getElementById('branches-box');
  const msg = document.getElementById('msg');

  const repos = await api.get('/api/repos');
  sel.innerHTML = repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('');

  document.getElementById('btn-load-branches').onclick = async () => {
    const id = sel.value;
    if (!id) return;
    msg.textContent = '加载分支中...';
    box.innerHTML = '';
    try {
      const r = await api.get('/api/repos/' + id + '/branches');
      if (!r.success) { msg.textContent = '获取失败: ' + r.message; return; }
      box.innerHTML = r.branches.map(b => `<label><input type="checkbox" value="${escapeHtml(b)}"/> ${escapeHtml(b)}</label>`).join('');
      msg.textContent = '共 ' + r.branches.length + ' 个分支';
    } catch (e) {
      msg.textContent = '请求失败: ' + e.message;
    }
  };

  document.getElementById('btn-start').onclick = async () => {
    const id = sel.value;
    if (!id) return;
    const branches = Array.from(box.querySelectorAll('input[type=checkbox]:checked')).map(i => i.value);
    if (branches.length === 0) { alert('请选择至少一个分支'); return; }
    const modules = document.getElementById('inp-modules').value.trim();
    try {
      const t = await api.post('/api/tasks', { repoId: Number(id), branches, modules });
      msg.textContent = '任务已创建 #' + t.id;
      setTimeout(() => { location.href = '/tasks/' + t.id; }, 500);
    } catch (e) {
      msg.textContent = '创建失败: ' + e.message;
    }
  };
})();
