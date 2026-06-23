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
  // 跨轮询保存"已展开 target"的状态，避免 3s 一次的 outer load() 把用户展开的内容打回去
  const expanded = new Set();
  // 已加载的 findings 缓存（按 targetId），避免每轮重新拉
  const findingsCache = new Map();
  // 上一轮 cards 的指纹，相同则不重渲染（避免闪烁、保护选中文本、保护展开状态）
  let lastCardsSig = '';

  function statusBadge(s) {
    const cls = STATUS_BADGE[s] || 'bg-secondary-lt';
    return `<span class="badge ${cls}">${escapeHtml(s)}</span>`;
  }

  function sevBadge(s) {
    const map = { ERROR: 'bg-red-lt', WARN: 'bg-yellow-lt', INFO: 'bg-blue-lt' };
    return `<span class="badge ${map[s] || 'bg-secondary-lt'}">${escapeHtml(s)}</span>`;
  }

  function detectorBadge(d) {
    if (d === 'LLM') return '<span class="badge bg-purple-lt">AI</span>';
    if (d === 'RULE_PATCH') return '<span class="badge bg-teal-lt">MR 验证</span>';
    return '<span class="badge bg-cyan-lt">规则</span>';
  }

  function detectorLabel(d) {
    if (d === 'LLM') return 'AI';
    if (d === 'RULE_PATCH') return 'MR 验证';
    return '规则';
  }

  function cardsSignature(t, targets) {
    return [
      t.status, t.progressDone, t.progressTotal, t.progressPhase, t.errorMessage || '',
      ...targets.map(tg => [
        tg.id, tg.status, tg.errorCount, tg.warnCount, tg.infoCount,
        tg.filesScanned, tg.startedAt || '', tg.finishedAt || '', tg.errorMessage || ''
      ].join('|'))
    ].join('//');
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
    const sig = cardsSignature(t, targets);
    if (sig !== lastCardsSig) {
      cardsEl.innerHTML = targets.map(tg => `
        <div class="card mb-2" data-target-id="${tg.id}">
          <div class="card-header d-flex align-items-center flex-wrap gap-2">
            <div class="me-3"><code>${escapeHtml(tg.targetBranch)}</code></div>
            <div class="me-3">${statusBadge(tg.status)}</div>
            <div class="me-3"><span class="badge bg-red-lt">${tg.errorCount || 0} 错</span></div>
            <div class="me-3"><span class="badge bg-yellow-lt">${tg.warnCount || 0} 警</span></div>
            <div class="me-3"><span class="badge bg-blue-lt">${tg.infoCount || 0} 提示</span></div>
            <div class="me-3"><small class="text-secondary">${tg.filesScanned || 0} 文件</small></div>
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
      lastCardsSig = sig;

      // 重渲染后恢复之前展开的 target：重新刷一次 findings（数据可能也新增了）
      for (const id of expanded) {
        const container = document.getElementById('findings-' + id);
        if (!container) { expanded.delete(id); continue; }
        container.classList.add('show');
        const btn = cardsEl.querySelector(`.btn-toggle-findings[data-target-id="${id}"]`);
        if (btn) btn.innerHTML = '<i class="ti ti-chevron-up"></i> 收起';
        findingsCache.delete(String(id));
        loadFindings(id);
      }
    }

    lastTaskStatus = t.status;
    if (TERMINAL_TASK.has(t.status)) stopOuterPoll();
  }

  // ---- finding 表格：按文件聚合 ----

  /**
   * 中间省略：保留前若干段 + 末两段，中间用 … 替代。
   * 例：src/main/java/com/foo/bar/baz/service/impl/UserServiceImpl.java
   *   → src/main/java/…/service/impl/UserServiceImpl.java
   * 短路径 / 段数少时原样返回。
   */
  function midEllipsisPath(path, maxLen) {
    if (!path) return '';
    const max = maxLen || 70;
    if (path.length <= max) return path;
    const parts = path.split('/');
    if (parts.length <= 3) {
      // 单层文件名也太长：从字符层中间切
      const keep = Math.max(8, Math.floor((max - 1) / 2));
      return path.slice(0, keep) + '…' + path.slice(path.length - keep);
    }
    // 优先按"段"省略：从末尾保留 2 段，从开头保留若干段，直到长度合规
    const tail = parts.slice(-2).join('/');
    for (let head = parts.length - 3; head >= 1; head--) {
      const candidate = parts.slice(0, head).join('/') + '/…/' + tail;
      if (candidate.length <= max) return candidate;
    }
    // 段省略仍不够：兜底字符省略
    const keep = Math.max(8, Math.floor((max - 1) / 2));
    return path.slice(0, keep) + '…' + path.slice(path.length - keep);
  }

  function groupByFile(list) {
    const groups = new Map();
    for (const f of list) {
      const k = f.filePath || '(unknown)';
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(f);
    }
    // 排序：先 ERROR > WARN > INFO 数量多的；同分按路径
    const sevWeight = { ERROR: 100, WARN: 10, INFO: 1 };
    return Array.from(groups.entries()).map(([path, items]) => {
      const c = { ERROR: 0, WARN: 0, INFO: 0 };
      for (const f of items) if (c[f.severity] != null) c[f.severity]++;
      return { path, items, counts: c, score: c.ERROR * 100 + c.WARN * 10 + c.INFO };
    }).sort((a, b) => b.score - a.score || a.path.localeCompare(b.path));
  }

  function renderFileGroups(groups, targetId) {
    return `
      <div class="table-responsive">
        <table class="table table-vcenter table-sm mb-0 findings-file-table">
          <thead>
            <tr>
              <th class="col-path">文件</th>
              <th class="col-badges">问题数</th>
              <th class="col-actions"></th>
            </tr>
          </thead>
          <tbody>
            ${groups.map((g, gi) => {
              const rowId = `file-${targetId}-${gi}`;
              return `
                <tr class="file-row" data-row-id="${rowId}" style="cursor:pointer">
                  <td class="col-path"><i class="ti ti-chevron-right me-1 file-chevron"></i><code title="${escapeHtml(g.path)}">${escapeHtml(midEllipsisPath(g.path, 80))}</code></td>
                  <td class="col-badges">
                    ${g.counts.ERROR ? `<span class="badge bg-red-lt me-1">${g.counts.ERROR} 错</span>` : ''}
                    ${g.counts.WARN ? `<span class="badge bg-yellow-lt me-1">${g.counts.WARN} 警</span>` : ''}
                    ${g.counts.INFO ? `<span class="badge bg-blue-lt me-1">${g.counts.INFO} 提示</span>` : ''}
                  </td>
                  <td class="col-actions"><small class="text-secondary">${g.items.length} 条</small></td>
                </tr>
                <tr class="file-detail collapse" id="${rowId}">
                  <td colspan="3" class="p-0">
                    <table class="table table-sm table-vcenter mb-0 findings-inner-table">
                      <thead class="table-light">
                        <tr>
                          <th class="col-sev">严重度</th>
                          <th class="col-src">来源</th>
                          <th class="col-type">类型</th>
                          <th class="col-summary">摘要</th>
                          <th class="col-mr">MR</th>
                          <th class="col-act"></th>
                        </tr>
                      </thead>
                      <tbody>
                        ${g.items.map(f => `
                          <tr>
                            <td class="col-sev">${sevBadge(f.severity)}</td>
                            <td class="col-src">${detectorBadge(f.detector)}</td>
                            <td class="col-type"><small title="${escapeHtml(f.type || '')}">${escapeHtml(f.type || '')}</small></td>
                            <td class="col-summary"><small title="${escapeHtml(f.summary || '')}">${escapeHtml(f.summary || '')}</small></td>
                            <td class="col-mr">${f.mrIid ? `<small>!${f.mrIid}</small>` : ''}</td>
                            <td class="col-act"><button class="btn btn-sm btn-finding-detail" data-id="${f.id}">详情</button></td>
                          </tr>
                        `).join('')}
                      </tbody>
                    </table>
                  </td>
                </tr>
              `;
            }).join('')}
          </tbody>
        </table>
      </div>
    `;
  }

  async function loadFindings(targetId) {
    const container = document.getElementById('findings-' + targetId);
    if (!container) return;
    container.innerHTML = '<div class="card-body py-2"><span class="text-secondary small">加载中...</span></div>';
    try {
      let list = findingsCache.get(String(targetId));
      if (!list) {
        list = await api.get(`/api/compare/tasks/${taskId}/targets/${targetId}/findings`);
        findingsCache.set(String(targetId), list);
      }
      if (!list.length) {
        container.innerHTML = '<div class="card-body py-3"><span class="text-success"><i class="ti ti-check me-1"></i>无差异</span></div>';
        return;
      }
      const groups = groupByFile(list);
      container.innerHTML = renderFileGroups(groups, targetId);

      // 文件行点击展开/收起该文件的详细 finding 列表
      container.querySelectorAll('.file-row').forEach(row => {
        row.onclick = (ev) => {
          if (ev.target.closest('.btn-finding-detail')) return;
          const detail = document.getElementById(row.dataset.rowId);
          if (!detail) return;
          const showed = detail.classList.toggle('show');
          const chev = row.querySelector('.file-chevron');
          if (chev) chev.className = 'ti me-1 file-chevron ' + (showed ? 'ti-chevron-down' : 'ti-chevron-right');
        };
      });

      container.querySelectorAll('.btn-finding-detail').forEach(btn => {
        btn.onclick = (ev) => {
          ev.stopPropagation();
          const f = list.find(x => x.id == btn.dataset.id);
          if (!f) return;
          openFindingDetail(f);
        };
      });
    } catch (e) {
      container.innerHTML = '<div class="card-body py-2"><span class="text-danger small">加载失败: ' + escapeHtml(e.message) + '</span></div>';
    }
  }

  // ---- finding 详情弹窗：行级 diff 视图 ----

  function openFindingDetail(f) {
    findingTitle.textContent = `${f.severity} · ${f.filePath}`;
    const hasBaseline = !!(f.baselineSnippet && f.baselineSnippet.trim());
    const hasTarget = !!(f.targetSnippet && f.targetSnippet.trim());
    const isPatch = f.detector === 'RULE_PATCH' && hasBaseline;

    let snippetHtml = '';
    if (isPatch) {
      const titleLabel = f.type === 'MR_LINE_LINGERING'
        ? 'MR 已删除但目标分支仍存在的片段'
        : (f.type === 'MR_FILE_MISSING'
            ? 'MR 新增的文件内容（目标分支不存在）'
            : 'MR 引入但目标分支未生效的片段');
      snippetHtml = `
        <div class="finding-modal-section-title">
          <i class="ti ti-git-pull-request"></i>${escapeHtml(titleLabel)}
          <span class="badge bg-red-lt">- MR 删除</span>
          <span class="badge bg-green-lt">+ MR 新增</span>
          <span class="text-secondary small ms-2">@@ 行末尾通常是包围方法/标签，可用于定位</span>
        </div>
        <div class="diff-box">${renderPatchSnippet(f.baselineSnippet)}</div>
      `;
    } else if (hasBaseline && hasTarget) {
      snippetHtml = `
        <div class="finding-modal-section-title">
          <i class="ti ti-arrows-diff"></i>
          差异（基准 → 目标）
          <span class="badge bg-red-lt">- 仅基准</span>
          <span class="badge bg-green-lt">+ 仅目标</span>
        </div>
        <div class="diff-box">${renderUnifiedDiff(f.baselineSnippet, f.targetSnippet)}</div>
      `;
    } else if (hasBaseline) {
      snippetHtml = `
        <div class="finding-modal-section-title"><i class="ti ti-file-x"></i>基准分支片段（目标分支缺失）</div>
        <pre class="snippet-box">${escapeHtml(f.baselineSnippet)}</pre>
      `;
    } else if (hasTarget) {
      snippetHtml = `
        <div class="finding-modal-section-title"><i class="ti ti-file-plus"></i>目标分支片段（基准分支无）</div>
        <pre class="snippet-box">${escapeHtml(f.targetSnippet)}</pre>
      `;
    }

    findingBody.innerHTML = `
      <div class="mb-2">
        <strong>类型：</strong> ${escapeHtml(f.type || '')}
        　<strong>来源：</strong> ${detectorLabel(f.detector)}
        ${f.mrIid ? `　<strong>MR：</strong> !${f.mrIid}` : ''}
      </div>
      <div class="mb-3"><strong>摘要：</strong> ${escapeHtml(f.summary || '')}</div>
      ${snippetHtml}
      ${f.llmComment ? `<div class="finding-modal-section-title"><i class="ti ti-robot"></i>AI 评语</div><div class="alert alert-info mb-0">${escapeHtml(f.llmComment)}</div>` : ''}
    `;
    findingModal.show();
  }

  /**
   * 把后端已经组好的 unified-diff 文本按 patch 风格渲染：
   *  - @@ 行作为分组头（蓝紫色）
   *  - + 行绿底、- 行红底、上下文行普通
   * patch verifier 的 snippet 走这个，不再走 LCS 算法。
   */
  function renderPatchSnippet(text) {
    const lines = text.split(/\r?\n/);
    // 处理末尾空行：split 把 "a\nb\n" 拆成 ["a","b",""]，丢掉空尾巴
    if (lines.length && lines[lines.length - 1] === '') lines.pop();
    return lines.map(line => {
      if (line.startsWith('@@')) {
        return `<div class="diff-hunk-header">${escapeHtml(line)}</div>`;
      }
      if (line.startsWith('+')) {
        return `<div class="diff-line add"><span class="diff-marker">+</span><span class="diff-text">${escapeHtml(line.substring(1))}</span></div>`;
      }
      if (line.startsWith('-')) {
        return `<div class="diff-line del"><span class="diff-marker">-</span><span class="diff-text">${escapeHtml(line.substring(1))}</span></div>`;
      }
      // 上下文行（' ' 开头或后端塞的空 " "）
      const body = line.startsWith(' ') ? line.substring(1) : line;
      return `<div class="diff-line eq"><span class="diff-marker"> </span><span class="diff-text">${escapeHtml(body)}</span></div>`;
    }).join('');
  }

  /**
   * 行级 unified diff：用 LCS 找出公共行，未匹配的左边算 "-"、右边算 "+"。
   * snippet 一般在几百行以内，O(n*m) 完全可以接受。
   */
  function renderUnifiedDiff(a, b) {
    const A = a.split(/\r?\n/);
    const B = b.split(/\r?\n/);
    const n = A.length, m = B.length;
    // dp[i][j] = LCS length of A[i..] and B[j..]
    const dp = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
    const out = [];
    let i = 0, j = 0, lnA = 1, lnB = 1;
    while (i < n && j < m) {
      if (A[i] === B[j]) {
        out.push(diffLine('eq', ' ', lnA, lnB, A[i]));
        i++; j++; lnA++; lnB++;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        out.push(diffLine('del', '-', lnA, '', A[i]));
        i++; lnA++;
      } else {
        out.push(diffLine('add', '+', '', lnB, B[j]));
        j++; lnB++;
      }
    }
    while (i < n) { out.push(diffLine('del', '-', lnA, '', A[i])); i++; lnA++; }
    while (j < m) { out.push(diffLine('add', '+', '', lnB, B[j])); j++; lnB++; }
    return out.join('');
  }

  function diffLine(cls, marker, lnA, lnB, text) {
    return `<div class="diff-line ${cls}">` +
      `<span class="diff-ln">${escapeHtml(String(lnA))}</span>` +
      `<span class="diff-ln">${escapeHtml(String(lnB))}</span>` +
      `<span class="diff-marker">${marker}</span>` +
      `<span class="diff-text">${escapeHtml(text)}</span>` +
      `</div>`;
  }

  // ---- 事件 / 轮询 ----

  cardsEl.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('.btn-toggle-findings');
    if (!btn) return;
    const id = btn.dataset.targetId;
    const container = document.getElementById('findings-' + id);
    const showed = container.classList.toggle('show');
    btn.innerHTML = showed
      ? '<i class="ti ti-chevron-up"></i> 收起'
      : '<i class="ti ti-chevron-down"></i> 展开';
    if (showed) {
      expanded.add(id);
      // 用户主动点开时清缓存强制重拉，保证数据最新
      findingsCache.delete(String(id));
      await loadFindings(id);
    } else {
      expanded.delete(id);
    }
  });

  function startOuterPoll() { if (!outerTimer) outerTimer = setInterval(load, 3000); }
  function stopOuterPoll() { if (outerTimer) { clearInterval(outerTimer); outerTimer = null; } }

  if (refreshBtn) refreshBtn.onclick = () => {
    findingsCache.clear();
    lastCardsSig = '';
    load();
  };

  await load();
  // 已是终态就不启动轮询，避免到时一次无谓的 load() 抢用户操作
  if (!TERMINAL_TASK.has(lastTaskStatus)) startOuterPoll();
})();
