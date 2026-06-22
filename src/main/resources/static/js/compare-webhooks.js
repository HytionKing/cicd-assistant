(async function () {
  const tbody = document.getElementById('wh-tbody');
  const modalEl = document.getElementById('modal-wh');
  const modal = new tabler.Modal(modalEl);
  const form = document.getElementById('wh-form');
  const title = document.getElementById('wh-modal-title');

  async function load() {
    const list = await api.get('/api/compare/webhooks');
    tbody.innerHTML = list.map(w => `
      <tr>
        <td>#${w.id}</td>
        <td><strong>${escapeHtml(w.name)}</strong></td>
        <td><small class="text-secondary text-break">${escapeHtml(w.url)}</small></td>
        <td>${w.enabled === 1
              ? '<span class="badge bg-green-lt">启用</span>'
              : '<span class="badge bg-secondary-lt">禁用</span>'}</td>
        <td><small class="text-secondary">${escapeHtml(fmtDate(w.updatedAt))}</small></td>
        <td class="text-end">
          <div class="btn-list justify-content-end">
            <button class="btn btn-sm" data-act="edit" data-id="${w.id}"><i class="ti ti-edit me-1"></i>编辑</button>
            <button class="btn btn-sm btn-outline-info" data-act="test" data-id="${w.id}"><i class="ti ti-send me-1"></i>测试</button>
            <button class="btn btn-sm btn-outline-danger" data-act="del" data-id="${w.id}"><i class="ti ti-trash me-1"></i>删除</button>
          </div>
        </td>
      </tr>
    `).join('') || '<tr><td colspan="6" class="text-center text-secondary py-4">还没有 Webhook</td></tr>';
  }

  function openModal(w) {
    title.textContent = w ? '编辑 Webhook' : '新建 Webhook';
    form.reset();
    if (w) {
      form.elements.id.value = w.id;
      form.elements.name.value = w.name || '';
      form.elements.url.value = w.url || '';
      form.elements.enabled.checked = w.enabled === 1;
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
      name: form.elements.name.value,
      url: form.elements.url.value,
      enabled: form.elements.enabled.checked ? 1 : 0
    };
    const id = form.elements.id.value;
    try {
      if (id) await api.put('/api/compare/webhooks/' + id, data);
      else await api.post('/api/compare/webhooks', data);
      modal.hide();
      await load();
      UI.success(id ? '已更新' : '已新建');
    } catch (e) { UI.danger('保存失败: ' + e.message); }
  };

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const id = btn.dataset.id;
    const act = btn.dataset.act;
    if (act === 'edit') {
      const w = await api.get('/api/compare/webhooks/' + id);
      openModal(w);
    } else if (act === 'del') {
      const ok = await UI.confirm({ title: '确认删除该 Webhook？' });
      if (!ok) return;
      await api.del('/api/compare/webhooks/' + id);
      await load();
      UI.success('已删除');
    } else if (act === 'test') {
      const original = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>发送中';
      try {
        const r = await api.post('/api/compare/webhooks/' + id + '/test');
        if (r.success) UI.success('发送成功：' + r.message, { duration: 6000 });
        else UI.danger('发送失败：' + r.message, { duration: 8000 });
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
