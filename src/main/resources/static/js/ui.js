// 全局 UI 工具：toast 通知 + 确认对话框，统一替代浏览器原生 alert/confirm
window.UI = (function () {
  const ICONS = {
    success: 'ti-check',
    danger:  'ti-alert-circle',
    warning: 'ti-alert-triangle',
    info:    'ti-info-circle'
  };

  // ---- Toast：右上角浮层，Tabler alert 样式 ----
  let toastHost = null;
  function ensureToastHost() {
    if (toastHost) return toastHost;
    toastHost = document.createElement('div');
    toastHost.className = 'toast-host';
    document.body.appendChild(toastHost);
    return toastHost;
  }

  function toast(type, message, opts) {
    opts = opts || {};
    const duration = opts.duration == null ? 4000 : opts.duration;
    const host = ensureToastHost();
    const el = document.createElement('div');
    el.className = `alert alert-${type} alert-dismissible toast-item`;
    el.innerHTML = `
      <div class="d-flex align-items-start">
        <i class="ti ${ICONS[type] || ICONS.info} alert-icon me-2 mt-1"></i>
        <div class="flex-fill">${escapeHtml(message)}</div>
        <button type="button" class="btn-close ms-2" data-bs-dismiss="alert" aria-label="关闭"></button>
      </div>
    `;
    host.appendChild(el);
    if (duration > 0) {
      setTimeout(() => {
        if (el.parentNode) tabler.Alert.getOrCreateInstance(el).close();
      }, duration);
    }
  }

  // ---- Confirm：Tabler small modal，返回 Promise<boolean> ----
  let confirmEl = null;
  let confirmInstance = null;
  function ensureConfirm() {
    if (confirmEl) return { el: confirmEl, modal: confirmInstance };
    confirmEl = document.createElement('div');
    // 不用 modal-blur：任务列表/详情页有定时轮询，背景重渲染会让 backdrop-filter 闪烁
    confirmEl.className = 'modal fade';
    confirmEl.tabIndex = -1;
    confirmEl.innerHTML = `
      <div class="modal-dialog modal-sm modal-dialog-centered">
        <div class="modal-content">
          <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>
          <div class="modal-status" id="confirm-status"></div>
          <div class="modal-body text-center py-4">
            <i class="ti icon-lg mb-2 d-block" id="confirm-icon"></i>
            <h3 id="confirm-title">确认操作？</h3>
            <div class="text-secondary" id="confirm-text"></div>
          </div>
          <div class="modal-footer">
            <div class="w-100">
              <div class="row">
                <div class="col"><a href="#" class="btn w-100" data-bs-dismiss="modal" id="confirm-cancel">取消</a></div>
                <div class="col"><a href="#" class="btn w-100" id="confirm-ok">确认</a></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;
    document.body.appendChild(confirmEl);
    confirmInstance = new tabler.Modal(confirmEl);
    return { el: confirmEl, modal: confirmInstance };
  }

  // opts: { title, text, okText, cancelText, okStyle='danger'|'primary'|'success'|... }
  function confirmDialog(opts) {
    opts = opts || {};
    return new Promise((resolve) => {
      const { el, modal } = ensureConfirm();
      const style = opts.okStyle || 'danger';
      el.querySelector('#confirm-title').textContent = opts.title || '确认操作？';
      el.querySelector('#confirm-text').textContent = opts.text || '';
      el.querySelector('#confirm-status').className = `modal-status bg-${style}`;
      const iconMap = { danger: 'ti-alert-triangle text-danger', primary: 'ti-help text-primary', success: 'ti-check text-success', warning: 'ti-alert-triangle text-warning' };
      el.querySelector('#confirm-icon').className = `ti ${iconMap[style] || iconMap.danger} icon-lg mb-2 d-block`;
      const okBtn = el.querySelector('#confirm-ok');
      okBtn.textContent = opts.okText || '确认';
      okBtn.className = `btn w-100 btn-${style}`;
      const cancelBtn = el.querySelector('#confirm-cancel');
      cancelBtn.textContent = opts.cancelText || '取消';

      let resolved = false;
      const onOk = (ev) => {
        ev.preventDefault();
        resolved = true;
        modal.hide();
        resolve(true);
      };
      const onHidden = () => {
        okBtn.removeEventListener('click', onOk);
        el.removeEventListener('hidden.bs.modal', onHidden);
        if (!resolved) resolve(false);
      };
      okBtn.addEventListener('click', onOk);
      el.addEventListener('hidden.bs.modal', onHidden);
      modal.show();
    });
  }

  return {
    toast,
    success: (m, o) => toast('success', m, o),
    danger:  (m, o) => toast('danger', m, o),
    info:    (m, o) => toast('info', m, o),
    warning: (m, o) => toast('warning', m, o),
    confirm: confirmDialog
  };
})();
