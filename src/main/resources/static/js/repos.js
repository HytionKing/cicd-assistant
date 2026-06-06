(async function () {
  const tbody = document.getElementById('repo-tbody');
  const modal = document.getElementById('modal');
  const form = document.getElementById('repo-form');
  const title = document.getElementById('modal-title');

  async function load() {
    const list = await api.get('/api/repos');
    tbody.innerHTML = list.map(r => `
      <tr>
        <td>${r.id}</td>
        <td>${escapeHtml(r.name)}</td>
        <td>${escapeHtml(r.gitlabHost || '')}</td>
        <td>${escapeHtml(r.projectPath || '')}</td>
        <td>${escapeHtml(r.branchPrefix || '')}</td>
        <td>${escapeHtml(r.authType)}</td>
        <td>
          <button class="btn" data-act="edit" data-id="${r.id}">编辑</button>
          <button class="btn" data-act="test" data-id="${r.id}">测试</button>
          <button class="btn danger" data-act="del" data-id="${r.id}">删除</button>
        </td>
      </tr>
    `).join('');
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
    modal.classList.remove('hidden');
  }

  function closeModal() { modal.classList.add('hidden'); }

  document.getElementById('btn-new').onclick = () => openModal(null);
  document.getElementById('btn-cancel').onclick = closeModal;

  form.onsubmit = async (ev) => {
    ev.preventDefault();
    const data = {};
    new FormData(form).forEach((v, k) => { data[k] = v; });
    const id = data.id;
    delete data.id;
    try {
      if (id) await api.put('/api/repos/' + id, data);
      else await api.post('/api/repos', data);
      closeModal();
      await load();
    } catch (e) { alert('保存失败: ' + e.message); }
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
      if (!confirm('确认删除？')) return;
      await api.del('/api/repos/' + id);
      await load();
    } else if (act === 'test') {
      btn.disabled = true; btn.textContent = '测试中...';
      try {
        const r = await api.post('/api/repos/' + id + '/test-connection');
        alert(r.success ? '连接成功' : '连接失败: ' + r.message);
      } catch (e) {
        alert('请求失败: ' + e.message);
      } finally {
        btn.disabled = false; btn.textContent = '测试';
      }
    }
  });

  await load();
})();
