(async function () {
  const tbody = document.getElementById('repo-tbody');
  const modalEl = document.getElementById('modal-repo');
  const modal = new tabler.Modal(modalEl);
  const form = document.getElementById('repo-form');
  const title = document.getElementById('modal-title');

  async function load() {
    const list = await api.get('/api/repos');
    tbody.innerHTML = list.map(r => `
      <tr>
        <td><span class="text-secondary">#${r.id}</span></td>
        <td><strong>${escapeHtml(r.name)}</strong></td>
        <td><span class="text-secondary">${escapeHtml(r.gitlabHost || '')}</span></td>
        <td>${escapeHtml(r.projectPath || '')}</td>
        <td>${r.branchPrefix ? `<code>${escapeHtml(r.branchPrefix)}</code>` : ''}</td>
        <td><span class="badge bg-secondary-lt">${escapeHtml(r.authType)}</span></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <button class="btn btn-sm" data-act="edit" data-id="${r.id}"><i class="ti ti-edit me-1"></i>编辑</button>
            <button class="btn btn-sm btn-outline-info" data-act="test" data-id="${r.id}"><i class="ti ti-plug-connected me-1"></i>测试</button>
            <button class="btn btn-sm btn-outline-danger" data-act="del" data-id="${r.id}"><i class="ti ti-trash me-1"></i>删除</button>
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="7" class="text-center text-secondary py-4">还没有仓库，点击右上角"新建仓库"开始</td></tr>';
  }

  function openModal(repo) {
    title.textContent = repo ? '编辑仓库' : '新建仓库';
    form.reset();
    if (repo) {
      Object.keys(repo).forEach(k => {
        if (form.elements[k] !== undefined) form.elements[k].value = repo[k] == null ? '' : repo[k];
      });
    } else {
      form.elements.id.value = '';
      form.elements.authType.value = 'TOKEN';
      form.elements.actuatorPath.value = '/actuator/health';
      form.elements.swaggerPaths.value = '/swagger-ui/index.html,/doc.html';
    }
    modal.show();
  }

  document.getElementById('btn-new').onclick = () => openModal(null);

  form.onsubmit = async (ev) => {
    ev.preventDefault();
    const data = {};
    new FormData(form).forEach((v, k) => { data[k] = v; });
    const id = data.id;
    delete data.id;
    try {
      if (id) await api.put('/api/repos/' + id, data);
      else await api.post('/api/repos', data);
      modal.hide();
      await load();
      UI.success(id ? '仓库已更新' : '仓库已新建');
    } catch (e) { UI.danger('保存失败: ' + e.message); }
  };

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const id = btn.dataset.id;
    const act = btn.dataset.act;
    if (act === 'edit') {
      const r = await api.get('/api/repos/' + id);
      openModal(r);
    } else if (act === 'del') {
      const ok = await UI.confirm({ title: '确认删除该仓库？', text: '相关任务历史不会被删除，但今后无法再用此配置启动新任务。' });
      if (!ok) return;
      await api.del('/api/repos/' + id);
      await load();
      UI.success('仓库已删除');
    } else if (act === 'test') {
      const original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>测试中';
      try {
        const r = await api.post('/api/repos/' + id + '/test-connection');
        if (r.success) UI.success('连接成功：' + r.message, { duration: 6000 });
        else UI.danger('连接失败：' + r.message, { duration: 8000 });
      } catch (e) {
        UI.danger('请求失败: ' + e.message);
      } finally {
        btn.disabled = false;
        btn.innerHTML = original;
      }
    }
  });

  await load();
})();
