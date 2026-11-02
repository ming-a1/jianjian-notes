/* 把笔记渲染成一张图片（用于「分享以图片形式」） */
window.NX = window.NX || {};
(function () {
  const SH = {};
  const W = 720;

  const PALETTE = {
    light: {
      bg: '#ffffff', text: '#15171c', text2: '#5b6270', text3: '#8b93a1', border: '#e7e9ee',
      border2: '#dcdfe6', accent: '#4f46e5', codeBg: '#f6f7f9', codeText: '#c7254e', quoteBg: '#f7f8fa',
      hlKey: '#a626a4', hlStr: '#50a14f', hlNum: '#986801', hlCom: '#a0a1a7', hlFn: '#4078f2'
    },
    dark: {
      bg: '#12141a', text: '#e8eaf0', text2: '#9aa2b1', text3: '#717a8a', border: '#252a34',
      border2: '#2e3440', accent: '#8b87ff', codeBg: '#1a1e26', codeText: '#ff9db1', quoteBg: '#181c24',
      hlKey: '#c678dd', hlStr: '#98c379', hlNum: '#d19a66', hlCom: '#5c6370', hlFn: '#61afef'
    }
  };

  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const pad = n => (n < 10 ? '0' : '') + n;

  function css(p) {
    return [
      '.sc-card{width:' + W + 'px;box-sizing:border-box;padding:48px 52px 40px;background:' + p.bg + ';color:' + p.text + ';',
      'font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Hiragino Sans GB,Noto Sans SC,Microsoft YaHei,sans-serif;',
      '-webkit-font-smoothing:antialiased;}',
      '.sc-title{font-size:28px;font-weight:700;line-height:1.32;letter-spacing:-.3px;margin:0 0 10px;}',
      '.sc-meta{font-size:13px;color:' + p.text3 + ';margin:0 0 26px;padding-bottom:22px;border-bottom:1px solid ' + p.border + ';}',
      '.sc-body{font-size:16px;line-height:1.8;word-wrap:break-word;}',
      '.sc-body p{margin:.7em 0;}',
      '.sc-body h1,.sc-body h2,.sc-body h3,.sc-body h4{font-weight:680;line-height:1.4;margin:1.3em 0 .5em;}',
      '.sc-body h1{font-size:1.6em;border-bottom:1px solid ' + p.border + ';padding-bottom:.25em;}',
      '.sc-body h2{font-size:1.32em;}',
      '.sc-body h3{font-size:1.14em;}',
      '.sc-body ul,.sc-body ol{padding-left:1.4em;margin:.6em 0;}',
      '.sc-body li{margin:.2em 0;}',
      '.sc-body a{color:' + p.accent + ';text-decoration:none;}',
      '.sc-body strong{font-weight:680;}',
      '.sc-body blockquote{margin:.9em 0;padding:.4em 1em;border-left:3px solid ' + p.accent + ';background:' + p.quoteBg + ';color:' + p.text2 + ';border-radius:0 8px 8px 0;}',
      '.sc-body code{font-family:SFMono-Regular,Menlo,Consolas,monospace;font-size:.88em;background:' + p.codeBg + ';color:' + p.codeText + ';padding:.12em .36em;border-radius:5px;}',
      '.sc-body pre{margin:.9em 0;padding:14px 16px;background:' + p.codeBg + ';border:1px solid ' + p.border + ';border-radius:10px;white-space:pre-wrap;}',
      '.sc-body pre code{background:none;color:' + p.text + ';padding:0;font-size:.85em;}',
      '.sc-body table{border-collapse:collapse;margin:.9em 0;font-size:.93em;}',
      '.sc-body th,.sc-body td{border:1px solid ' + p.border2 + ';padding:7px 12px;text-align:left;}',
      '.sc-body th{background:' + p.quoteBg + ';font-weight:650;}',
      '.sc-body hr{border:0;border-top:1px solid ' + p.border + ';margin:1.6em 0;}',
      '.sc-body del{color:' + p.text2 + ';}',
      '.sc-body .task-list-item{list-style:none;margin-left:-1.15em;}',
      '.sc-task{display:inline-block;width:16px;height:16px;border-radius:4px;border:1.6px solid ' + p.border2 + ';',
      'margin-right:8px;vertical-align:-3px;text-align:center;line-height:15px;font-size:12px;color:#fff;box-sizing:border-box;}',
      '.sc-task.on{background:' + p.accent + ';border-color:' + p.accent + ';}',
      '.sc-imgph{display:inline-block;padding:6px 10px;border:1px dashed ' + p.border2 + ';border-radius:8px;color:' + p.text3 + ';font-size:13px;}',
      '.sc-hl-key{color:' + p.hlKey + ';font-weight:600;}',
      '.sc-hl-str{color:' + p.hlStr + ';}',
      '.sc-hl-num{color:' + p.hlNum + ';}',
      '.sc-hl-com{color:' + p.hlCom + ';font-style:italic;}',
      '.sc-hl-fn{color:' + p.hlFn + ';}',
      '.sc-body .hljs-keyword,.sc-body .hljs-selector-tag{color:' + p.hlKey + ';font-weight:600;}',
      '.sc-body .hljs-string,.sc-body .hljs-regexp{color:' + p.hlStr + ';}',
      '.sc-body .hljs-number,.sc-body .hljs-literal{color:' + p.hlNum + ';}',
      '.sc-body .hljs-comment,.sc-body .hljs-quote{color:' + p.hlCom + ';font-style:italic;}',
      '.sc-body .hljs-title,.sc-body .hljs-function{color:' + p.hlFn + ';}',
      '.sc-body .hljs-attr,.sc-body .hljs-attribute,.sc-body .hljs-variable{color:' + p.hlStr + ';}',
      '.sc-foot{margin-top:30px;padding-top:16px;border-top:1px solid ' + p.border + ';color:' + p.text3 + ';font-size:12px;display:flex;justify-content:space-between;}'
    ].join('');
  }

  /* 任务清单 / 图片 / 链接处理，保证 SVG 内联渲染稳定 */
  function postProcess(root) {
    Array.prototype.slice.call(root.querySelectorAll('input[type="checkbox"]')).forEach(cb => {
      const wrap = cb.closest('label') || cb;
      const span = document.createElement('span');
      span.className = 'sc-task' + (cb.checked ? ' on' : '');
      span.textContent = cb.checked ? '✓' : '';
      wrap.parentNode.insertBefore(span, wrap);
      if (wrap.classList && wrap.classList.contains('task-list-item-label')) wrap.remove();
      else cb.remove();
    });
    Array.prototype.slice.call(root.querySelectorAll('img')).forEach(img => {
      const ph = document.createElement('span');
      ph.className = 'sc-imgph';
      ph.textContent = '🖼 ' + (img.getAttribute('alt') || '图片');
      img.parentNode.replaceChild(ph, img);
    });
    Array.prototype.slice.call(root.querySelectorAll('*')).forEach(el => {
      el.removeAttribute('id');
      el.removeAttribute('aria-hidden');
      el.removeAttribute('tabindex');
    });
  }

  function serialize(node) {
    return new XMLSerializer().serializeToString(node);
  }

  function loadImage(url) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error('图片渲染失败（当前 WebView 不支持 SVG foreignObject）'));
      img.src = url;
    });
  }

  SH.render = async function (note) {
    const theme = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    const p = PALETTE[theme];
    const title = (note.title || '').trim() || NX.util.deriveTitle(note.content || '') || '无标题';
    const words = NX.md.countWords(note.content || '');
    const d = new Date(note.updatedAt || Date.now());
    const dateStr = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());

    const wrap = document.createElement('div');
    wrap.setAttribute('aria-hidden', 'true');
    wrap.style.cssText = 'position:fixed;left:-10000px;top:0;width:' + W + 'px;pointer-events:none;z-index:-1;';
    const card = document.createElement('div');
    card.className = 'sc-card';
    card.innerHTML = '<div class="sc-title">' + esc(title) + '</div>' +
      '<div class="sc-meta">' + dateStr + ' · ' + words + ' 字 · 简笺笔记</div>' +
      '<div class="sc-body">' + NX.md.render(note.content || '') + '</div>' +
      '<div class="sc-foot"><span>简笺 · Markdown 笔记</span><span>' + dateStr + '</span></div>';
    const body = card.querySelector('.sc-body');
    postProcess(body);
    /* 正文首行是一级标题且与标题重复时去掉，避免图片里出现两遍 */
    const first = body.firstElementChild;
    if (first && /^H1$/i.test(first.tagName) && first.textContent.trim() === title.trim()) {
      first.remove();
    }
    if (!body.textContent.trim()) body.innerHTML = '<p style="color:' + p.text3 + '">（空白笔记）</p>';

    const style = document.createElement('style');
    style.textContent = css(p);
    card.insertBefore(style, card.firstChild);

    wrap.appendChild(card);
    document.body.appendChild(wrap);

    let height, width;
    try {
      const rect = card.getBoundingClientRect();
      height = Math.ceil(rect.height);
      width = Math.ceil(rect.width);
    } finally {
      // 稍后再移除，保证样式已计算
      setTimeout(() => wrap.remove(), 0);
    }

    const bodyHtml = serialize(card);
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '">' +
      '<foreignObject x="0" y="0" width="100%" height="100%">' + bodyHtml + '</foreignObject></svg>';
    const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
    const img = await loadImage(url);

    const maxH = 12000;
    const scale = height > 4000 ? 1.5 : 2;
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(width * scale);
    canvas.height = Math.round(Math.min(height, maxH) * scale);
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = p.bg;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, width * scale, height * scale);

    const blob = await new Promise(res => canvas.toBlob(res, 'image/png'));
    if (!blob) throw new Error('生成图片失败');
    return { blob: blob, title: title, filename: 'jianjian-' + dateStr + '-' + safeName(title) + '.png' };
  };

  function safeName(s) {
    return String(s).replace(/[\\/:*?"<>|\s]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'note';
  }

  function toBase64(blob) {
    return new Promise((resolve, reject) => {
      const fr = new FileReader();
      fr.onload = () => resolve(String(fr.result).split(',')[1] || '');
      fr.onerror = () => reject(new Error('读取图片失败'));
      fr.readAsDataURL(blob);
    });
  }
  SH.toBase64 = toBase64;

  /* 分享：Android 走原生分享面板，Web 走 Web Share / 下载 */
  SH.share = async function (note) {
    const res = await SH.render(note);
    if (NX.backend.kind === 'android' && NX.backend.shareImage) {
      const b64 = await toBase64(res.blob);
      const r = await NX.backend.shareImage(b64, res.filename);
      if (!r || r.ok === false) throw new Error((r && r.error) || '分享失败');
      return { via: 'android', filename: res.filename };
    }
    const file = new File([res.blob], res.filename, { type: 'image/png' });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ files: [file], title: res.title });
        return { via: 'share', filename: res.filename };
      } catch (e) {
        if (e && e.name === 'AbortError') return { via: 'cancel' };
      }
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(res.blob);
    a.download = res.filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1500);
    return { via: 'download', filename: res.filename };
  };

  NX.share = SH;
})();
