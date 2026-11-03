/* Markdown 渲染：markdown-it + GFM 表格/删除线/任务清单/脚注 + 代码高亮 */
window.NX = window.NX || {};
(function () {
  const M = {};
  let md = null;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function init() {
    if (md) return md;
    const options = {
      html: false,
      xhtmlOut: false,
      breaks: true,
      langPrefix: 'language-',
      linkify: true,
      typographer: true,
      highlight: function (str, lang) {
        const lc = lang ? ' language-' + esc(lang) : '';
        try {
          if (lang && window.hljs && window.hljs.getLanguage(lang)) {
            return '<pre class="hljs' + lc + '"><code class="hljs' + lc + '">' + window.hljs.highlight(str, { language: lang, ignoreIllegals: true }).value + '</code></pre>';
          }
        } catch (e) { /* fallthrough */ }
        return '<pre class="hljs' + lc + '"><code class="hljs' + lc + '">' + esc(str) + '</code></pre>';
      }
    };
    md = window.markdownit(options);
    if (window.markdownitTaskLists) md.use(window.markdownitTaskLists, { enabled: true, label: true, labelAfter: true });
    if (window.markdownitFootnote) md.use(window.markdownitFootnote);

    // 外链新窗口打开
    const defaultLinkOpen = md.renderer.rules.link_open || function (tokens, idx, opts, env, self) {
      return self.renderToken(tokens, idx, opts);
    };
    md.renderer.rules.link_open = function (tokens, idx, opts, env, self) {
      const href = tokens[idx].attrGet('href') || '';
      if (/^https?:\/\//i.test(href)) {
        tokens[idx].attrSet('target', '_blank');
        tokens[idx].attrSet('rel', 'noopener noreferrer');
      }
      return defaultLinkOpen(tokens, idx, opts, env, self);
    };
    return md;
  }

  M.render = function (src) {
    init();
    try {
      return md.render(String(src || ''));
    } catch (e) {
      console.error('markdown render error', e);
      return '<p>渲染失败：' + esc(e.message) + '</p>';
    }
  };

  /* 预览区任务清单可点击：回调把变更写回 Markdown 源码 */
  M.bindTaskToggles = function (container, onToggle) {
    container.addEventListener('change', function (e) {
      const t = e.target;
      if (!t || !t.classList || !t.classList.contains('task-list-item-checkbox')) return;
      const all = Array.prototype.slice.call(container.querySelectorAll('.task-list-item-checkbox'));
      const idx = all.indexOf(t);
      if (idx < 0) return;
      if (onToggle) onToggle(idx, t.checked);
    });
  };

  M.countWords = function (src) {
    const text = String(src || '').replace(/```[\s\S]*?```/g, ' ').replace(/[#>*_`~\-\[\]()!|]/g, ' ');
    const cn = (text.match(/[\u4e00-\u9fa5]/g) || []).length;
    const en = (text.replace(/[\u4e00-\u9fa5]/g, ' ').match(/[A-Za-z0-9]+/g) || []).length;
    return cn + en;
  };

  NX.md = M;
})();
