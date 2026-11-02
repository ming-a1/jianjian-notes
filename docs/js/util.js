/* 通用工具：DOM、时间、对话框、Toast */
window.NX = window.NX || {};
(function () {
  const U = {};

  U.$ = (sel, root) => (root || document).querySelector(sel);
  U.$$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

  U.esc = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  };

  U.uid = function () {
    return 'n' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  };

  U.debounce = function (fn, ms) {
    let t;
    return function () {
      const args = arguments, self = this;
      clearTimeout(t);
      t = setTimeout(() => fn.apply(self, args), ms);
    };
  };

  U.pad = n => (n < 10 ? '0' : '') + n;

  U.fmtTime = function (ts) {
    if (!ts) return '';
    const d = new Date(ts), now = new Date();
    const diff = Date.now() - ts;
    if (diff >= 0 && diff < 60000) return '刚刚';
    if (diff >= 0 && diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    const sameDay = d.toDateString() === now.toDateString();
    if (sameDay) return U.pad(d.getHours()) + ':' + U.pad(d.getMinutes());
    const y = new Date(now.getTime() - 86400000);
    if (d.toDateString() === y.toDateString()) return '昨天 ' + U.pad(d.getHours()) + ':' + U.pad(d.getMinutes());
    if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + '月' + d.getDate() + '日';
    return d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日';
  };

  U.fmtDateTime = function (ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    return d.getFullYear() + '-' + U.pad(d.getMonth() + 1) + '-' + U.pad(d.getDate()) +
      ' ' + U.pad(d.getHours()) + ':' + U.pad(d.getMinutes()) + ':' + U.pad(d.getSeconds());
  };

  U.fmtSize = function (n) {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(2) + ' MB';
  };

  /* 从正文推导标题 */
  U.deriveTitle = function (content) {
    const lines = String(content || '').split('\n');
    for (let i = 0; i < lines.length; i++) {
      let s = lines[i].trim();
      if (!s) continue;
      s = s.replace(/^#{1,6}\s+/, '')
        .replace(/^>\s?/, '')
        .replace(/^[-*+]\s+\[[ xX]\]\s*/, '')
        .replace(/^[-*+]\s+/, '')
        .replace(/^\d+\.\s+/, '')
        .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/[*_`~]/g, '')
        .trim();
      if (s) return s.length > 80 ? s.slice(0, 80) + '…' : s;
    }
    return '';
  };

  U.excerpt = function (content, max) {
    max = max || 140;
    let s = String(content || '')
      .replace(/```[\s\S]*?```/g, ' [代码] ')
      .replace(/^#{1,6}\s+/gm, '')
      .replace(/!\[[^\]]*\]\([^)]*\)/g, ' [图片] ')
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/[>*_`~|-]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    return s.length > max ? s.slice(0, max) + '…' : s;
  };

  U.bytesToBase64 = function (u8) {
    let s = '';
    const CH = 0x8000;
    for (let i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  };
  U.base64ToBytes = function (b64) {
    const bin = atob(String(b64 || '').replace(/\s+/g, ''));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  };
  U.textToBytes = s => new TextEncoder().encode(s);
  U.bytesToText = b => new TextDecoder().decode(b);

  /* ---------- Toast ---------- */
  U.toast = function (msg, kind, ms) {
    const wrap = document.getElementById('toast-wrap');
    if (!wrap) return;
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = msg;
    wrap.appendChild(el);
    setTimeout(() => {
      el.style.transition = 'opacity .25s, transform .25s';
      el.style.opacity = '0';
      el.style.transform = 'translateY(8px)';
      setTimeout(() => el.remove(), 260);
    }, ms || 2400);
  };

  /* ---------- 对话框 ---------- */
  let dialogOpen = null;

  U.dialog = function (opts) {
    opts = opts || {};
    if (dialogOpen) dialogOpen.close(null);
    const $ = U.$;
    const scrim = $('#dialog-scrim'), box = $('#dialog');
    const cancelable = opts.cancelable !== false;
    const titleEl = $('#dialog-title'), bodyEl = $('#dialog-body'), actionsEl = $('#dialog-actions');

    titleEl.textContent = opts.title || '';
    titleEl.hidden = !opts.title;
    bodyEl.innerHTML = '';
    actionsEl.innerHTML = '';

    if (opts.html) {
      const d = document.createElement('div');
      d.innerHTML = opts.html;
      bodyEl.appendChild(d);
    }
    const fields = opts.fields || [];
    fields.forEach(f => {
      const label = document.createElement('label');
      label.className = 'field';
      const span = document.createElement('span');
      span.textContent = f.label || '';
      label.appendChild(span);
      let input;
      if (f.type === 'select') {
        input = document.createElement('select');
        (f.options || []).forEach(o => {
          const op = document.createElement('option');
          op.value = o.value; op.textContent = o.text; input.appendChild(op);
        });
        input.value = f.value != null ? f.value : '';
      } else {
        input = document.createElement('input');
        input.type = f.type || 'text';
        input.autocomplete = 'off';
        input.placeholder = f.placeholder || '';
        input.value = f.value != null ? f.value : '';
      }
      input.dataset.name = f.name;
      if (f.maxlength) input.maxLength = f.maxlength;
      label.appendChild(input);
      bodyEl.appendChild(label);
    });

    const err = document.createElement('div');
    err.className = 'err';
    bodyEl.appendChild(err);

    const actions = opts.actions || [
      { id: 'cancel', label: '取消', kind: 'ghost' },
      { id: 'ok', label: '确定', kind: 'primary' }
    ];
    let primaryBtn = null;
    actions.forEach(a => {
      const b = document.createElement('button');
      b.className = 'btn ' + (a.kind || 'ghost');
      b.textContent = a.label;
      b.dataset.action = a.id;
      b.addEventListener('click', () => submit(a.id));
      actionsEl.appendChild(b);
      if (a.kind === 'primary') primaryBtn = b;
    });

    scrim.hidden = false;
    box.hidden = false;

    function collect() {
      const values = {};
      U.$$('input,select,[data-name]', bodyEl).forEach(el => {
        if (el.dataset && el.dataset.name) values[el.dataset.name] = el.value;
      });
      return values;
    }

    function submit(action) {
      const values = collect();
      if (action === 'ok' && opts.validate) {
        const msg = opts.validate(values);
        if (msg) { err.textContent = msg; return; }
      }
      close({ action: action, values: values });
    }

    let closed = false;
    function close(result) {
      if (closed) return;
      closed = true;
      scrim.hidden = true; box.hidden = true;
      document.removeEventListener('keydown', onKey, true);
      box.removeEventListener('click', stop);
      scrim.removeEventListener('click', cancel);
      if (dialogOpen && dialogOpen.close === close) dialogOpen = null;
      if (resolveFn) resolveFn(result);
    }
    function cancel() { if (cancelable) close(null); }
    function stop(e) { e.stopPropagation(); }
    function onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); if (cancelable) cancel(); return; }
      if (e.key === 'Enter' && primaryBtn && document.activeElement && document.activeElement.tagName === 'INPUT') {
        e.preventDefault(); submit('ok');
      }
    }
    let resolveFn = null;
    const p = new Promise(res => { resolveFn = res; });
    dialogOpen = { close: close };
    if (cancelable) scrim.addEventListener('click', cancel);
    box.addEventListener('click', stop);
    document.addEventListener('keydown', onKey, true);
    setTimeout(() => {
      const first = bodyEl.querySelector('input,select,textarea');
      if (first) first.focus();
      else if (primaryBtn) primaryBtn.focus();
    }, 60);
    return p;
  };

  U.closeDialog = function () {
    if (dialogOpen) { dialogOpen.close(null); return true; }
    return false;
  };

  U.confirm = function (title, html, opts) {
    opts = opts || {};
    return U.dialog({
      title: title, html: html,
      actions: [
        { id: 'cancel', label: opts.cancelText || '取消', kind: 'ghost' },
        { id: 'ok', label: opts.okText || '确定', kind: opts.danger ? 'primary danger' : 'primary' }
      ]
    }).then(r => !!r && r.action === 'ok');
  };

  U.prompt = function (title, fields, opts) {
    opts = opts || {};
    return U.dialog({
      title: title,
      html: opts.html || '',
      fields: fields,
      validate: opts.validate,
      actions: [
        { id: 'cancel', label: '取消', kind: 'ghost' },
        { id: 'ok', label: opts.okText || '确定', kind: 'primary' }
      ]
    }).then(r => (r && r.action === 'ok' ? r.values : null));
  };

  NX.util = U;
})();
