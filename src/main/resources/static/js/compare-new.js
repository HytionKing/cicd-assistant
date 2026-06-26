(async function () {
  const selRepo = document.getElementById('sel-repo');
  const inpBaseline = document.getElementById('inp-baseline');
  const btnPickBaseline = document.getElementById('btn-pick-baseline');
  const baselineModalEl = document.getElementById('modal-pick-baseline');
  const baselineModal = new tabler.Modal(baselineModalEl);
  const baselineList = document.getElementById('baseline-list');
  const targetsBox = document.getElementById('targets-box');
  const targetsMsg = document.getElementById('targets-msg');
  const mrsBox = document.getElementById('mrs-box');
  const mrsMsg = document.getElementById('mrs-msg');
  const selMode = document.getElementById('sel-mode');
  const selWebhook = document.getElementById('sel-webhook');
  const selContexts = document.getElementById('sel-contexts');
  const contextsWrap = document.getElementById('contexts-wrap');
  const inpMrLimit = document.getElementById('inp-mr-limit');
  const baselineMrHint = document.getElementById('baseline-mr-hint');
  const inpTargetPrefix = document.getElementById('inp-target-prefix');
  const btnApplyPrefix = document.getElementById('btn-apply-prefix');
  const chkTodayOnly = document.getElementById('chk-today-only');

  // 被对比分支默认前缀，浏览器记住上次的（不同团队规则不同：env-/release-/dev-…）
  const PREFIX_KEY = 'compare.targetPrefix';
  inpTargetPrefix.value = localStorage.getItem(PREFIX_KEY) || 'env-';

  let allBranches = [];
  let appConfig = { mrFetchDefaultLimit: 20, mrFetchMaxLimit: 100 };

  async function loadConfig() {
    try {
      appConfig = await api.get('/api/compare/config');
      inpMrLimit.value = appConfig.mrFetchDefaultLimit;
      inpMrLimit.max = appConfig.mrFetchMaxLimit;
    } catch (e) {
      inpMrLimit.value = 20;
    }
  }

  function updateBaselineHint() {
    const hasAnyMrChecked = !!mrsBox.querySelector('.mr-check:checked');
    baselineMrHint.classList.toggle('d-none', !hasAnyMrChecked);
  }

  async function loadRepos() {
    const repos = await api.get('/api/repos');
    selRepo.innerHTML = repos.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('')
      || '<option value="">(没有仓库，先去仓库管理添加)</option>';
  }

  async function loadWebhooks() {
    const list = await api.get('/api/compare/webhooks');
    const enabled = list.filter(w => w.enabled === 1);
    selWebhook.innerHTML = '<option value="">不推送</option>' +
      enabled.map(w => `<option value="${w.id}">${escapeHtml(w.name)}</option>`).join('');
  }

  async function loadContexts() {
    const repoId = selRepo.value || '';
    const list = await api.get('/api/compare/contexts/applicable' + (repoId ? '?repoId=' + repoId : ''));
    selContexts.innerHTML = list.map(c =>
      `<option value="${c.id}">${escapeHtml(c.title)}${c.repoId == null ? ' (全局)' : ''}</option>`
    ).join('');
  }

  function updateContextsVisibility() {
    contextsWrap.classList.toggle('d-none', selMode.value === 'RULE');
  }

  async function loadBranches() {
    const id = selRepo.value;
    if (!id) return [];
    const r = await api.get('/api/repos/' + id + '/branches');
    if (!r.success) {
      UI.danger('获取分支失败: ' + r.message);
      return [];
    }
    return r.branches || [];
  }

  function renderTargets(branches) {
    if (branches.length === 0) {
      targetsBox.innerHTML = '<span class="text-secondary small">没有分支</span>';
      return;
    }
    const prefix = (inpTargetPrefix.value || '').trim();
    targetsBox.innerHTML = branches.map(b =>
      `<label class="form-check">
         <input type="checkbox" class="form-check-input" value="${escapeHtml(b)}"
                ${prefix && b.startsWith(prefix) ? 'checked' : ''}/>
         <span class="form-check-label">${escapeHtml(b)}</span>
       </label>`
    ).join('');
    const matched = prefix ? branches.filter(b => b.startsWith(prefix)).length : 0;
    targetsMsg.textContent = '共 ' + branches.length + ' 个分支'
      + (prefix ? '，默认勾选「' + prefix + '」前缀（' + matched + ' 个）' : '');
  }

  function applyPrefixCheck() {
    const prefix = (inpTargetPrefix.value || '').trim();
    localStorage.setItem(PREFIX_KEY, prefix);
    targetsBox.querySelectorAll('input[type="checkbox"]').forEach(i => {
      i.checked = prefix !== '' && i.value.startsWith(prefix);
    });
    const matched = prefix ? Array.from(targetsBox.querySelectorAll('input:checked')).length : 0;
    targetsMsg.textContent = '已按「' + prefix + '」重新勾选（' + matched + ' 个）';
  }

  function renderBaselineList(branches) {
    baselineList.innerHTML = branches.map(b =>
      `<a href="#" class="dropdown-item baseline-pick" data-branch="${escapeHtml(b)}">${escapeHtml(b)}</a>`
    ).join('');
    baselineList.querySelectorAll('.baseline-pick').forEach(a => {
      a.onclick = (ev) => {
        ev.preventDefault();
        inpBaseline.value = a.dataset.branch;
        baselineModal.hide();
      };
    });
  }

  document.getElementById('btn-load-targets').onclick = async () => {
    const id = selRepo.value;
    if (!id) { UI.warning('请先选择仓库'); return; }
    targetsMsg.textContent = '加载中...';
    allBranches = await loadBranches();
    renderTargets(allBranches);
  };

  btnPickBaseline.onclick = async () => {
    if (allBranches.length === 0) {
      const id = selRepo.value;
      if (!id) { UI.warning('请先选择仓库'); return; }
      baselineList.innerHTML = '<span class="text-secondary small">加载中...</span>';
      baselineModal.show();
      allBranches = await loadBranches();
    } else {
      baselineModal.show();
    }
    renderBaselineList(allBranches);
  };

  document.getElementById('btn-load-mrs').onclick = async () => {
    const id = selRepo.value;
    if (!id) { UI.warning('请先选择仓库'); return; }
    const targets = Array.from(targetsBox.querySelectorAll('input:checked')).map(i => i.value);
    if (targets.length === 0) { UI.warning('请先勾选至少一个被对比分支'); return; }
    let lim = parseInt(inpMrLimit.value, 10);
    if (!Number.isFinite(lim) || lim < 1) lim = appConfig.mrFetchDefaultLimit;
    if (lim > appConfig.mrFetchMaxLimit) lim = appConfig.mrFetchMaxLimit;
    mrsMsg.textContent = '查询中...';
    mrsBox.innerHTML = '<span class="text-secondary small">查询中...</span>';
    try {
      const r = await api.get('/api/compare/recent-mrs?repoId=' + id +
        '&targetBranches=' + encodeURIComponent(targets.join(',')) +
        '&limit=' + lim +
        (chkTodayOnly.checked ? '&todayOnly=true' : ''));
      mrsMsg.textContent = '';
      const groups = r.groups || [];
      let total = 0;
      mrsBox.innerHTML = groups.map(g => {
        total += (g.mrs || []).length;
        const items = (g.mrs || []).map(m => `
          <label class="form-check d-block">
            <input type="checkbox" class="form-check-input mr-check"
                   data-iid="${m.iid}" data-branch="${escapeHtml(g.targetBranch)}"/>
            <span class="form-check-label">
              <strong>!${m.iid}</strong> ${escapeHtml(m.title || '')}
              <small class="text-secondary ms-2">${escapeHtml(m.author || '')} · ${escapeHtml(m.mergedAt || '')}</small>
            </span>
          </label>
        `).join('') || '<span class="text-secondary small">该分支无近期 MR</span>';
        return `
          <div class="mr-group mb-3">
            <div class="d-flex align-items-center mb-1">
              <strong>${escapeHtml(g.targetBranch)}</strong>
              <span class="ms-2 text-secondary small">(${(g.mrs || []).length} 条)</span>
              <a href="#" class="ms-3 small group-all" data-branch="${escapeHtml(g.targetBranch)}">全选本组</a>
              <a href="#" class="ms-2 small group-none" data-branch="${escapeHtml(g.targetBranch)}">全不选</a>
              ${g.error ? `<span class="ms-3 text-danger small">${escapeHtml(g.error)}</span>` : ''}
            </div>
            <div class="ms-3">${items}</div>
          </div>
        `;
      }).join('');
      mrsBox.querySelectorAll('.group-all').forEach(a => a.onclick = (ev) => {
        ev.preventDefault();
        const b = a.dataset.branch;
        mrsBox.querySelectorAll('.mr-check').forEach(i => { if (i.dataset.branch === b) i.checked = true; });
        updateBaselineHint();
      });
      mrsBox.querySelectorAll('.group-none').forEach(a => a.onclick = (ev) => {
        ev.preventDefault();
        const b = a.dataset.branch;
        mrsBox.querySelectorAll('.mr-check').forEach(i => { if (i.dataset.branch === b) i.checked = false; });
        updateBaselineHint();
      });
      mrsBox.addEventListener('change', (ev) => {
        if (ev.target.classList && ev.target.classList.contains('mr-check')) updateBaselineHint();
      });
      mrsMsg.textContent = '共 ' + total + ' 条 MR';
      updateBaselineHint();
    } catch (e) {
      mrsMsg.textContent = '';
      UI.danger('查询失败: ' + e.message);
    }
  };

  selMode.onchange = updateContextsVisibility;
  selRepo.onchange = () => {
    allBranches = [];
    targetsBox.innerHTML = '<span class="text-secondary small">点击"拉取分支"加载（默认勾选 env-* 前缀）</span>';
    mrsBox.innerHTML = '<span class="text-secondary small">选完被对比分支后点击"查询 MR"</span>';
    loadContexts();
  };

  document.getElementById('btn-start').onclick = async () => {
    const repoId = Number(selRepo.value);
    if (!repoId) { UI.warning('请选择仓库'); return; }
    const baseline = inpBaseline.value.trim();
    if (!baseline) { UI.warning('请填写基准分支'); return; }
    const targets = Array.from(targetsBox.querySelectorAll('input:checked')).map(i => i.value);
    if (targets.length === 0) { UI.warning('请勾选至少一个被对比分支'); return; }
    const mrs = Array.from(mrsBox.querySelectorAll('.mr-check:checked')).map(i =>
      ({ iid: Number(i.dataset.iid), targetBranch: i.dataset.branch })
    );
    const mode = selMode.value;
    const contextIds = Array.from(selContexts.selectedOptions).map(o => Number(o.value));
    const webhookId = selWebhook.value ? Number(selWebhook.value) : null;
    try {
      const t = await api.post('/api/compare/tasks', {
        repoId, baselineBranch: baseline, targetBranches: targets,
        mrs, mode, contextIds, webhookId
      });
      UI.success('任务已创建 #' + t.id);
      setTimeout(() => { location.href = '/compare/tasks/' + t.id; }, 500);
    } catch (e) {
      UI.danger('创建失败: ' + e.message);
    }
  };

  btnApplyPrefix.onclick = applyPrefixCheck;
  // 输入框回车直接应用前缀，不用点旁边那个对勾
  inpTargetPrefix.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter') { ev.preventDefault(); applyPrefixCheck(); }
  });

  await loadConfig();
  await loadRepos();
  await loadWebhooks();
  await loadContexts();
  updateContextsVisibility();
})();
