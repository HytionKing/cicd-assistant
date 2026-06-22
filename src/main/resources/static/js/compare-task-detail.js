(async function () {
  const taskId = document.getElementById('task-id').textContent;
  const summary = document.getElementById('task-summary');
  const cardsEl = document.getElementById('targets-cards');
  const refreshBtn = document.getElementById('btn-refresh');
  const findingModalEl = document.getElementById('finding-modal');
  const findingModal = new tabler.Modal(findingModalEl);
  const findingTitle = document.getElementById('finding-title');
  const findingBody = document.getElementById('finding-body');

  let outerTimer = null;
  let lastTaskStatus = '';

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  function sevBadge(s) {
    const map = { ERROR: 'bg-red-lt', WARN: 'bg-yellow-lt', INFO: 'bg-blue-lt' };
    return `<span class="badge ${map[s] || 'bg-secondary-lt'}">${escapeHtml(s)}</span>`;
  }

  function detectorBadge(d) {
    return d === 'LLM'
      ? '<span class="badge bg-purple-lt">AI</span>'
      : '<span class="badge bg-cyan-lt">规则</span>';
  }

  async function load() {
    const d = await api.get('/api/compare/tasks/' + taskId);
    const t = d.task;
    if (!t) { summary.textContent = '任务不存在'; return; }

    const progress = t.progressTotal
      ? Math.round((t.progressDone || 0) * 100 / t.progressTotal)
      : 0;

    summary.innerHTML = `
      <div class="row g-2">
        <div class="col-md-3"><span class="text-secondary">仓库</span><div><strong>${escapeHtml(t.repoName || '')}</strong></div></div>
        <div class="col-md-3"><span class="text-secondary">基准分支</span><div><code>${escapeHtml(t.baselineBranch)}</code></div></div>
        <div class="col-md-3"><span class="text-secondary">模式</span><div><span class="badge bg-blue-lt">${escapeHtml(t.mode)}</span></div></div>
        <div class="col-md-3"><span class="text-secondary">状态</span><div>${statusBadge(t.status)}</div></div>

        <div class="col-md-6"><span class="text-secondary">被对比分支</span><div><code>${escapeHtml(t.targetBranches || '')}</code></div></div>
        <div class="col-md-3"><span class="text-secondary">创建</span><div><small>${escapeHtml(fmtDate(t.createdAt))}</small></div></div>
        <div class="col-md-3"><span class="text-secondary">完成</span><div><small>${escapeHtml(fmtDate(t.finishedAt))}</small></div></div>

        <div class="col-12">
          <div class="d-flex align-items-center mt-2">
            <span class="text-secondary me-2">进度</span>
            <div class="progress flex-fill" style="height:8px;">
              <div class="progress-bar" style="width:${progress}%"></div>
            </div>
            <span class="ms-2 small text-secondary">${t.progressDone || 0} / ${t.progressTotal || 0}${t.progressPhase ? ' · ' + escapeHtml(t.progressPhase) : ''}</span>
          </div>
        </div>
        ${t.errorMessage ? `<div class="col-12"><div class="alert alert-danger mb-0 mt-2 py-2"><strong>错误：</strong>${escapeHtml(t.errorMessage)}</div></div>` : ''}
      </div>
    `;

    const targets = d.targets || [];
    cardsEl.innerHTML = targets.map(tg => `
      <div class="card mb-2" data-target-id="${tg.id}">
        <div class="card-header d-flex align-items-center">
          <div class="me-3"><code>${escapeHtml(tg.targetBranch)}</code></div>
          <div class="me-3">${statusBadge(tg.status)}</div>
          <div class="me-3"><span class="badge bg-red-lt">${tg.errorCount || 0} 错</span></div>
          <div class="me-3"><span class="badge bg-yellow-lt">${tg.warnCount || 0} 警</span></div>
          <div class="me-3"><span class="badge bg-blue-lt">${tg.infoCount || 0} 提示</span></div>
          <div class="ms-auto small text-secondary">${escapeHtml(fmtDate(tg.startedAt))} - ${escapeHtml(fmtDate(tg.finishedAt))}</div>
          <button class="btn btn-sm ms-2 btn-toggle-findings" data-target-id="${tg.id}">
            <i class="ti ti-chevron-down"></i> 展开
          </button>
        </div>
        ${tg.errorMessage ? `<div class="card-body py-2"><div class="alert alert-danger mb-0 py-1"><small>${escapeHtml(tg.errorMessage)}</small></div></div>` : ''}
        <div class="findings-container collapse" id="findings-${tg.id}">
          <div class="card-body py-2"><span class="text-secondary small">点击"展开"加载差异列表</span></div>
        </div>
      </div>
    `).join('') || '<div class="card"><div class="card-body text-center text-secondary py-4">尚无子任务</div></div>';

    lastTaskStatus = t.status;
    if (TERMINAL_TASK.has(t.status)) stopOuterPoll();
  }

  async function loadFindings(targetId) {
    const container = document.getElementById('findings-' + targetId);
    container.innerHTML = '<div class="card-body py-2"><span class="text-secondary small">加载中...</span></div>';
    try {
      const list = await api.get(`/api/compare/tasks/${taskId}/targets/${targetId}/findings`);
      if (!list.length) {
        container.innerHTML = '<div class="card-body py-3"><span class="text-success"><i class="ti ti-check me-1"></i>无差异</span></div>';
        return;
      }
      container.innerHTML = `
        <div class="table-responsive">
          <table class="table table-vcenter table-sm mb-0">
            <thead>
              <tr><th>严重度</th><th>来源</th><th>类型</th><th>文件</th><th>摘要</th><th>MR</th><th></th></tr>
            </thead>
            <tbody>
              ${list.map(f => `
                <tr>
                  <td>${sevBadge(f.severity)}</td>
                  <td>${detectorBadge(f.detector)}</td>
                  <td><small>${escapeHtml(f.type || '')}</small></td>
                  <td><code class="small">${escapeHtml(f.filePath || '')}</code></td>
                  <td><small>${escapeHtml(f.summary || '')}</small></td>
                  <td>${f.mrIid ? `<small>!${f.mrIid}</small>` : ''}</td>
                  <td><button class="btn btn-sm btn-finding-detail" data-id="${f.id}">详情</button></td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      `;
      container.querySelectorAll('.btn-finding-detail').forEach(btn => {
        btn.onclick = () => {
          const f = list.find(x => x.id == btn.dataset.id);
          if (!f) return;
          findingTitle.textContent = `${f.severity} · ${f.filePath}`;
          findingBody.innerHTML = `
            <div class="mb-2">
              <strong>类型：</strong> ${escapeHtml(f.type || '')}
              <strong>来源：</strong> ${f.detector === 'LLM' ? 'AI' : '规则'}
              ${f.mrIid ? `　<strong>MR：</strong> !${f.mrIid}` : ''}
            </div>
            <div class="mb-3"><strong>摘要：</strong> ${escapeHtml(f.summary || '')}</div>
            ${f.baselineSnippet ? `<h6 class="mt-3">基准分支片段</h6><pre class="log-box" style="max-height:300px">${escapeHtml(f.baselineSnippet)}</pre>` : ''}
            ${f.targetSnippet ? `<h6 class="mt-3">目标分支片段</h6><pre class="log-box" style="max-height:300px">${escapeHtml(f.targetSnippet)}</pre>` : ''}
            ${f.llmComment ? `<h6 class="mt-3">AI 评语</h6><div class="alert alert-info mb-0">${escapeHtml(f.llmComment)}</div>` : ''}
          `;
          findingModal.show();
        };
      });
    } catch (e) {
      container.innerHTML = '<div class="card-body py-2"><span class="text-danger small">加载失败: ' + escapeHtml(e.message) + '</span></div>';
    }
  }

  cardsEl.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('.btn-toggle-findings');
    if (!btn) return;
    const id = btn.dataset.targetId;
    const container = document.getElementById('findings-' + id);
    const showed = container.classList.toggle('show');
    btn.innerHTML = showed
      ? '<i class="ti ti-chevron-up"></i> 收起'
      : '<i class="ti ti-chevron-down"></i> 展开';
    if (showed) await loadFindings(id);
  });

  function startOuterPoll() { if (!outerTimer) outerTimer = setInterval(load, 3000); }
  function stopOuterPoll() { if (outerTimer) { clearInterval(outerTimer); outerTimer = null; } }

  if (refreshBtn) refreshBtn.onclick = load;

  await load();
  startOuterPoll();
})();
