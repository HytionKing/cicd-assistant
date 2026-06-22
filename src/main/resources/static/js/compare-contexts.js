(async function () {
  const tbody = document.getElementById('ctx-tbody');
  const modalEl = document.getElementById('modal-ctx');
  const modal = new tabler.Modal(modalEl);
  const form = document.getElementById('ctx-form');
  const title = document.getElementById('ctx-modal-title');
  let repoNameMap = {};

  async function loadRepoOptions() {
    const repos = await api.get('/api/repos');
    repoNameMap = {};
    repos.forEach(r => { repoNameMap[r.id] = r.name; });
    const sel = form.elements.repoId;
    sel.innerHTML = '<option value="">全局（所有仓库）</option>' +
      repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('');
  }

  async function load() {
    const list = await api.get('/api/compare/contexts');
    tbody.innerHTML = list.map(c => `
      <tr>
        <td>#${c.id}</td>
        <td><strong>${escapeHtml(c.title)}</strong></td>
        <td>${c.repoId == null
              ? '<span class="badge bg-blue-lt">全局</span>'
              : '<span class="badge bg-secondary-lt">' + escapeHtml(repoNameMap[c.repoId] || ('#' + c.repoId)) + '</span>'}</td>
        <td>${c.enabled === 1
              ? '<span class="badge bg-green-lt">启用</span>'
              : '<span class="badge bg-secondary-lt">禁用</span>'}</td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(c.updatedAt))}</small></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <button class="btn btn-sm" data-act="edit" data-id="${c.id}"><i class="ti ti-edit me-1"></i>编辑</button>
            <button class="btn btn-sm btn-outline-danger" data-act="del" data-id="${c.id}"><i class="ti ti-trash me-1"></i>删除</button>
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="6" class="text-center text-secondary py-4">还没有条目</td></tr>';
  }

  function openModal(c) {
    title.textContent = c ? '编辑条目' : '新建条目';
    form.reset();
    if (c) {
      form.elements.id.value = c.id;
      form.elements.title.value = c.title || '';
      form.elements.content.value = c.content || '';
      form.elements.repoId.value = c.repoId == null ? '' : c.repoId;
      form.elements.enabled.checked = c.enabled === 1;
    } else {
      form.elements.id.value = '';
      form.elements.enabled.checked = true;
    }
    modal.show();
  }

  document.getElementById('btn-new').onclick = () => openModal(null);

  form.onsubmit = async (ev) => {
    ev.preventDefault();
    const data = {
      title: form.elements.title.value,
      content: form.elements.content.value,
      repoId: form.elements.repoId.value ? Number(form.elements.repoId.value) : null,
      enabled: form.elements.enabled.checked ? 1 : 0
    };
    const id = form.elements.id.value;
    try {
      if (id) await api.put('/api/compare/contexts/' + id, data);
      else await api.post('/api/compare/contexts', data);
      modal.hide();
      await load();
      UI.success(id ? '已更新' : '已新建');
    } catch (e) { UI.danger('保存失败: ' + e.message); }
  };

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const id = btn.dataset.id;
    if (btn.dataset.act === 'edit') {
      const c = await api.get('/api/compare/contexts/' + id);
      openModal(c);
    } else if (btn.dataset.act === 'del') {
      const ok = await UI.confirm({ title: '确认删除该条目？' });
      if (!ok) return;
      await api.del('/api/compare/contexts/' + id);
      await load();
      UI.success('已删除');
    }
  });

  await loadRepoOptions();
  await load();
})();
