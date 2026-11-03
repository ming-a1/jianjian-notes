/* 代码块工具栏：折叠 / 复制 / 下载 / 分享 / 放大 */
window.NX = window.NX || {};
(function () {
  const C = {};
  const COLLAPSE_LINES = 24;

  function langOf(pre) {
    const code = pre.querySelector('code');
    if (!code) return '';
    const m = (code.className || '').match(/language-([\w+#.-]+)/);
    return m ? m[1].toLowerCase() : '';
  }

  function fileName(lang) {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    const ts = d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' +
      p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
    return 'code-' + ts + (lang === 'md' || lang === 'markdown' ? '.md' : '.txt');
  }

  function toast(msg) {
    if (window.NX && NX.util && NX.util.toast) NX.util.toast(msg);
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
      }
      toast('已复制代码');
    } catch (e) {
      toast('复制失败');
    }
  }

  function download(text, lang) {
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = fileName(lang);
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    toast('已下载代码');
  }

  function share(text, lang) {
    const body = lang ? '```' + lang + '\n' + text + '\n```' : text;
    if (navigator.share) {
      navigator.share({ title: lang ? '代码片段 · ' + lang : '代码片段', text: body }).catch(() => { });
    } else {
      copyText(body);
      toast('已复制，可粘贴分享');
    }
  }

  function fullscreen(pre, lang) {
    const mask = document.createElement('div');
    mask.className = 'code-full';
    const card = document.createElement('div');
    card.className = 'code-full-card';

    const head = document.createElement('div');
    head.className = 'code-full-head';
    const name = document.createElement('span');
    name.className = 'code-lang';
    name.textContent = lang || '代码';
    head.appendChild(name);
    const sp = document.createElement('span');
    sp.className = 'sp';
    head.appendChild(sp);

    const copy = document.createElement('button');
    copy.type = 'button';
    copy.className = 'code-btn';
    copy.textContent = '复制';
    copy.addEventListener('click', () => copyText(pre.textContent));
    head.appendChild(copy);

    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'code-btn';
    close.textContent = '关闭';
    close.addEventListener('click', () => mask.remove());
    head.appendChild(close);

    const body = document.createElement('div');
    body.className = 'code-full-body';
    body.appendChild(pre.cloneNode(true));

    card.appendChild(head);
    card.appendChild(body);
    mask.appendChild(card);
    mask.addEventListener('click', e => { if (e.target === mask) mask.remove(); });
    document.addEventListener('keydown', function esc(e) {
      if (e.key === 'Escape') { mask.remove(); document.removeEventListener('keydown', esc); }
    });
    document.body.appendChild(mask);
  }

  function build(pre) {
    if (pre.closest('.code-full')) return;
    if (pre.parentElement && pre.parentElement.classList.contains('code-card')) return;

    const raw = pre.textContent || '';
    const lang = langOf(pre);
    const lines = raw.split('\n').length;
    const collapsed = lines > COLLAPSE_LINES;

    const card = document.createElement('div');
    card.className = 'code-card' + (collapsed ? ' collapsed' : '');

    const head = document.createElement('div');
    head.className = 'code-head';

    const label = document.createElement('span');
    label.className = 'code-lang';
    label.textContent = lang || '代码';
    head.appendChild(label);

    const meta = document.createElement('span');
    meta.className = 'code-lines';
    meta.textContent = '· ' + lines + ' 行';
    head.appendChild(meta);

    const sp = document.createElement('span');
    sp.className = 'sp';
    head.appendChild(sp);

    const mk = (text, fn) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'code-btn';
      b.textContent = text;
      b.addEventListener('click', fn);
      return b;
    };

    const toggle = mk(collapsed ? '展开' : '折叠', () => {
      const on = card.classList.toggle('collapsed');
      toggle.textContent = on ? '展开' : '折叠';
    });
    head.appendChild(toggle);
    head.appendChild(mk('复制', () => copyText(raw)));
    head.appendChild(mk('下载', () => download(raw, lang)));
    head.appendChild(mk('分享', () => share(raw, lang)));
    head.appendChild(mk('放大', () => fullscreen(pre, lang)));

    pre.parentNode.insertBefore(card, pre);
    card.appendChild(head);
    card.appendChild(pre);
  }

  C.enhance = function (root) {
    if (!root) return;
    const list = [];
    root.querySelectorAll('pre').forEach(p => list.push(p));
    list.forEach(build);
  };

  NX.code = C;
})();
