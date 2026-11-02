/* 应用主逻辑 */
window.NX = window.NX || {};
(function () {
  window.NX_VERSION = window.NX_VERSION || '1.0.1';
  const U = NX.util;
  const { esc, fmtTime, fmtDateTime, fmtSize, deriveTitle, excerpt, toast, dialog, prompt, confirm, debounce } = U;

  const S = {
    notes: [],
    settings: null,
    current: null,
    mode: 'edit',
    search: '',
    dirty: false,
    backupTimer: null,
    listDirty: false,
    backups: []
  };
  const el = {};

  const DEFAULTS = {
    theme: 'light',
    fontSize: 16,
    webdav: { url: '', user: '', pass: '', path: 'heyunotes' },
    backup: { realtime: false, scheduled: false, interval: 60, encrypt: false, keep: 7 }
  };

  function cache() {
    const ids = ['app', 'sidebar', 'main', 'search', 'note-list', 'list-count', 'note-view', 'empty', 'note-title',
      'note-meta', 'editor', 'preview', 'mode-seg', 'save-hint', 'note-menu', 'menu-scrim', 'settings-modal',
      'settings-scrim', 'backup-log', 'backup-status', 'dav-status', 'interval-warn', 'enc-pwd-row',
      'enc-pwd-status', 'storage-info', 'dialog-scrim', 'dialog'];
    ids.forEach(id => { el[id.replace(/-(\w)/g, (m, c) => c.toUpperCase())] = document.getElementById(id); });
    el.title = document.getElementById('note-title');
  }

  /* ---------------- 启动 ---------------- */
  document.addEventListener('DOMContentLoaded', boot);

  async function boot() {
    cache();
    try {
      S.settings = await NX.backend.settings();
    } catch (e) {
      S.settings = JSON.parse(JSON.stringify(DEFAULTS));
      toast('读取设置失败：' + e.message, 'err');
    }
    S.settings = mergeDefaults(S.settings);
    NX.theme.init(S.settings.theme);
    applyFontSize(S.settings.fontSize);
    bind();
    try { await reloadNotes(); } catch (e) { toast('读取笔记失败：' + e.message, 'err'); }
    renderList();
    renderBackupHint();

    if (NX.backend.kind === 'web') {
      const dl = document.getElementById('dl-link');
      if (dl) dl.hidden = false;
    }

    /* 启动停在列表，不自动打开上次的笔记 */
    showEmpty();

    if (S.settings.backup.encrypt && !(await safeHasPwd())) {
      setTimeout(() => promptEncPassword().then(v => { if (!v) patch({ backup: { encrypt: false } }); }), 400);
    }
    setTimeout(() => { refreshBackups().catch(() => { }); }, 600);
  }

  function mergeDefaults(s) {
    const base = JSON.parse(JSON.stringify(DEFAULTS));
    if (!s) return base;
    return {
      theme: s.theme || base.theme,
      fontSize: s.fontSize || base.fontSize,
      webdav: Object.assign(base.webdav, s.webdav || {}),
      backup: Object.assign(base.backup, s.backup || {}),
      backupIndex: s.backupIndex || { lastIncludedAt: 0, lastFullAt: 0, sinceFull: 0 },
      settingsVersion: s.settingsVersion || 2,
      lastBackupAt: s.lastBackupAt || 0,
      lastBackupType: s.lastBackupType || ''
    };
  }

  function applyFontSize(size) {
    const fs = Math.min(22, Math.max(13, Number(size) || 16));
    document.documentElement.style.setProperty('--md-fs', fs + 'px');
    document.documentElement.style.setProperty('--editor-fs', (fs - 1) + 'px');
    const r = document.getElementById('fontsize');
    if (r) r.value = fs;
    const v = document.getElementById('fontsize-val');
    if (v) v.textContent = fs;
  }

  async function reloadNotes() {
    S.notes = (await NX.backend.notes()) || [];
  }

  /* ---------------- 事件绑定 ---------------- */
  function bind() {
    document.getElementById('btn-new').addEventListener('click', newNote);
    document.getElementById('btn-empty-new').addEventListener('click', newNote);
    document.getElementById('btn-theme').addEventListener('click', quickThemeToggle);
    document.getElementById('btn-settings').addEventListener('click', openSettings);
    document.getElementById('btn-close-settings').addEventListener('click', closeSettings);
    el.settingsScrim.addEventListener('click', closeSettings);
    U.$$('#settings-tabs .tab').forEach(b => b.addEventListener('click', () => switchSettingsTab(b.dataset.tab)));
    document.getElementById('btn-back').addEventListener('click', showList);
    document.getElementById('btn-more').addEventListener('click', openMenu);
    el.menuScrim.addEventListener('click', closeMenu);
    document.getElementById('btn-render-toggle').addEventListener('click', toggleRenderMode);
    document.getElementById('btn-pin-toggle').addEventListener('click', togglePin);
    document.getElementById('btn-share-img').addEventListener('click', shareAsImage);
    document.getElementById('btn-rename').addEventListener('click', renameNote);
    document.getElementById('btn-delete').addEventListener('click', deleteCurrent);

    el.search.addEventListener('input', () => {
      S.search = el.search.value;
      document.getElementById('btn-clear-search').hidden = !S.search;
      renderList();
    });
    document.getElementById('btn-clear-search').addEventListener('click', () => {
      el.search.value = ''; S.search = '';
      document.getElementById('btn-clear-search').hidden = true;
      renderList(); el.search.focus();
    });

    el.noteList.addEventListener('click', e => {
      const row = e.target.closest('.note-item');
      if (row) openNote(row.dataset.id);
    });

    el.title.addEventListener('input', () => { S.dirty = true; scheduleSave(); updateRowSoon(); updateMetaSoon(); });
    el.editor.addEventListener('input', () => { S.dirty = true; scheduleSave(); updateMetaSoon(); });
    el.editor.addEventListener('blur', () => flushSave());
    el.editor.addEventListener('keydown', onEditorKey);

    U.$$('#mode-seg button').forEach(b => b.addEventListener('click', () => setMode(b.dataset.mode)));

    NX.md.bindTaskToggles(el.preview, (idx, checked) => {
      const n = S.current; if (!n) return;
      const next = toggleTaskInSource(n.content || '', idx, checked);
      el.editor.value = next;
      S.dirty = true;
      flushSave().then(() => renderPreview());
    });

    document.addEventListener('keydown', onGlobalKey);
    window.addEventListener('beforeunload', () => { if (S.dirty) flushSave(); });
    document.addEventListener('visibilitychange', () => { if (document.hidden && S.dirty) flushSave(); });

    window.__androidBack = androidBack;
    window.__showList = showList;

    bindSettings();
  }

  function onEditorKey(e) {
    if (e.key === 'Tab') {
      e.preventDefault();
      const t = e.target, s = t.selectionStart, en = t.selectionEnd;
      t.value = t.value.slice(0, s) + '  ' + t.value.slice(en);
      t.selectionStart = t.selectionEnd = s + 2;
      S.dirty = true; scheduleSave();
    }
  }

  function onGlobalKey(e) {
    const mod = e.metaKey || e.ctrlKey;
    if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); flushSave(); toast('已保存'); }
    else if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); newNote(); }
    else if (mod && e.key.toLowerCase() === 'e') { e.preventDefault(); setMode(S.mode === 'edit' ? 'read' : 'edit'); }
    else if (e.key === 'Escape') {
      if (!el.settingsModal.hidden) closeSettings();
      else if (!el.noteMenu.hidden) closeMenu();
      else if (U.closeDialog()) { }
    }
  }

  function androidBack() {
    if (U.closeDialog()) return true;
    if (!el.settingsModal.hidden) { closeSettings(); return true; }
    if (!el.noteMenu.hidden) { closeMenu(); return true; }
    if (el.app.classList.contains('viewing')) { showList(); return true; }
    return false;
  }

  /* ---------------- 列表 ---------------- */
  function visibleNotes() {
    const q = (S.search || '').trim().toLowerCase();
    let list = S.notes.slice();
    if (q) list = list.filter(n => ((n.title || '') + '\n' + (n.content || '')).toLowerCase().indexOf(q) >= 0);
    list.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (b.updatedAt || 0) - (a.updatedAt || 0));
    return list;
  }

  function renderList() {
    const list = visibleNotes();
    const curId = S.current && S.current.id;
    el.noteList.innerHTML = list.length ? list.map(n => noteRow(n, curId)).join('')
      : '<div class="empty-list">' + (S.search ? '没有匹配的笔记' : '点击「新建」开始记录') + '</div>';
    el.listCount.textContent = (S.search ? list.length + ' / ' : '') + S.notes.length + ' 条笔记';
  }
  const renderListSoon = debounce(renderList, 280);
  const updateRowSoon = debounce(() => { if (S.current) updateRow(S.current); }, 260);
  const updateMetaSoon = debounce(() => updateMeta(), 260);

  /* 只更新受影响的那一行，避免每次输入都重建整个列表 */
  function updateRow(note) {
    if (!note || !el.noteList) return;
    const row = el.noteList.querySelector('.note-item[data-id="' + note.id + '"]');
    if (!row) { renderListSoon(); return; }
    const tmp = document.createElement('div');
    tmp.innerHTML = noteRow(note, S.current && S.current.id);
    const fresh = tmp.firstElementChild;
    if (!fresh) return;
    row.replaceWith(fresh);

    const items = Array.prototype.slice.call(el.noteList.querySelectorAll('.note-item'));
    const byId = new Map();
    S.notes.forEach(x => byId.set(x.id, x));
    const pin = note.pinned ? 1 : 0;
    const ts = note.updatedAt || 0;
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (it === fresh) continue;
      const other = byId.get(it.dataset.id);
      if (!other) continue;
      const opin = other.pinned ? 1 : 0;
      if (pin > opin || (pin === opin && ts > (other.updatedAt || 0))) {
        el.noteList.insertBefore(fresh, it);
        return;
      }
    }
  }

  function noteRow(n, curId) {
    const title = (n.title && n.title.trim()) || deriveTitle(n.content) || '无标题';
    const ex = excerpt(n.content) || '空白笔记';
    const badges = [];
    if (n.pinned) badges.push('<span class="badge pin">置顶</span>');
    if (n.renderMode) badges.push('<span class="badge render">渲染查看</span>');
    return '<div class="note-item' + (n.id === curId ? ' active' : '') + '" data-id="' + esc(n.id) + '">' +
      '<div class="note-item-top"><div class="note-item-title">' + esc(title) + '</div>' +
      '<div class="note-item-time">' + fmtTime(n.updatedAt) + '</div></div>' +
      '<div class="note-item-excerpt">' + esc(ex) + '</div>' +
      (badges.length ? '<div class="badges">' + badges.join('') + '</div>' : '') +
      '</div>';
  }

  function showEmpty() {
    el.empty.hidden = false;
    el.noteView.hidden = true;
    el.app.classList.remove('viewing');
    const has = S.notes.length > 0;
    const h2 = el.empty.querySelector('h2');
    const p = el.empty.querySelector('p');
    const btn = document.getElementById('btn-empty-new');
    if (h2) h2.textContent = has ? '选择一篇笔记' : '还没有笔记';
    if (p) p.textContent = has
      ? '从左侧列表选一篇开始阅读，或新建一篇继续记录。'
      : '支持完整 Markdown：标题、列表、表格、代码块、任务清单、脚注…';
    if (btn) btn.textContent = has ? '新建笔记' : '写第一篇笔记';
  }

  function showList() {
    flushSave();
    el.app.classList.remove('viewing');
  }

  /* ---------------- 笔记 ---------------- */
  async function newNote() {
    await flushSave();
    const now = Date.now();
    const note = {
      id: U.uid(), title: '', content: '', createdAt: now, updatedAt: now,
      pinned: false, renderMode: false
    };
    try {
      const saved = await NX.backend.saveNote(note);
      Object.assign(note, saved);
    } catch (e) { toast('新建失败：' + e.message, 'err'); return; }
    S.notes.unshift(note);
    openNote(note.id, { mode: 'edit' });
    renderList();
    setTimeout(() => { el.title.focus(); }, 60);
  }

  async function openNote(id, opts) {
    opts = opts || {};
    if (S.current && S.current.id !== id) await flushSave();
    const note = S.notes.find(n => n.id === id);
    if (!note) return;
    S.current = note;
    S.mode = opts.mode || (note.renderMode ? 'read' : 'edit');
    try { localStorage.setItem('jj.last', id); } catch (e) { }
    el.app.classList.add('viewing');
    el.empty.hidden = true;
    el.noteView.hidden = false;
    renderNote();
    renderList();
    /* 不自动聚焦编辑器：避免浏览器把滚动位置带到文末（移动端也不弹键盘） */
    if (S.mode === 'edit') {
      setTimeout(() => { el.editor.scrollTop = 0; }, 20);
    }
  }

  function renderNote() {
    const n = S.current;
    if (!n) return;
    el.title.value = n.title || '';
    el.editor.value = n.content || '';
    el.editor.scrollTop = 0;
    el.editor.hidden = S.mode === 'read';
    el.preview.hidden = S.mode !== 'read';
    if (S.mode === 'read') renderPreview();
    U.$$('#mode-seg button').forEach(b => b.classList.toggle('on', b.dataset.mode === S.mode));
    updateMeta();
    syncMenuSwitches();
  }

  function renderPreview() {
    const n = S.current; if (!n) return;
    el.preview.innerHTML = n.content && n.content.trim() ? NX.md.render(n.content) : '<p style="color:var(--text3)">（空白笔记）</p>';
  }

  function setMode(mode) {
    if (mode === S.mode) return;
    if (mode === 'read') { flushSave(); }
    S.mode = mode;
    el.editor.hidden = mode === 'read';
    el.preview.hidden = mode !== 'read';
    if (mode === 'read') renderPreview();
    else { el.editor.scrollTop = 0; }
    U.$$('#mode-seg button').forEach(b => b.classList.toggle('on', b.dataset.mode === mode));
  }

  function scheduleSave() { clearTimeout(S.saveTimer); S.saveTimer = setTimeout(() => flushSave(), 600); }

  async function flushSave() {
    clearTimeout(S.saveTimer);
    if (!S.current || !S.dirty) return;
    const n = S.current;
    const content = el.editor.value;
    const title = el.title.value.trim();
    if (content === (n.content || '') && title === (n.title || '')) { S.dirty = false; return; }
    n.content = content;
    n.title = title;
    n.updatedAt = Date.now();
    S.dirty = false;
    try {
      const saved = await NX.backend.saveNote(n);
      Object.assign(n, saved);
      updateRow(n);
      updateMeta();
      flashSaved();
      queueAutoBackup();
    } catch (e) {
      S.dirty = true;
      toast('保存失败：' + e.message, 'err');
    }
  }

  function flashSaved() {
    const h = el.saveHint;
    h.textContent = '已保存';
    h.classList.add('show');
    clearTimeout(flashSaved._t);
    flashSaved._t = setTimeout(() => h.classList.remove('show'), 1200);
  }

  function updateMeta() {
    const n = S.current; if (!n) return;
    const words = NX.md.countWords(n.content);
    el.noteMeta.innerHTML = '<span>' + words + ' 字</span><span>保存于 ' + fmtTime(n.updatedAt) + '</span>' +
      (n.renderMode ? '<span>· 默认渲染查看</span>' : '');
  }

  async function deleteCurrent() {
    closeMenu();
    const n = S.current; if (!n) return;
    const ok = await confirm('删除这篇笔记？', '<p>「' + esc((n.title || deriveTitle(n.content) || '无标题').slice(0, 40)) + '」将被永久删除，此操作不可撤销。</p>', { okText: '删除', danger: true });
    if (!ok) return;
    try {
      await NX.backend.deleteNote(n.id);
    } catch (e) { toast('删除失败：' + e.message, 'err'); return; }
    S.notes = S.notes.filter(x => x.id !== n.id);
    S.current = null;
    const next = visibleNotes()[0];
    if (next) openNote(next.id); else { showEmpty(); renderList(); }
    renderList();
    toast('已删除');
  }

  async function shareAsImage() {
    closeMenu();
    const n = S.current;
    if (!n) return;
    await flushSave();
    toast('正在生成图片…', '', 1600);
    try {
      const r = await NX.share.share(n);
      if (r.via === 'cancel') return;
      if (r.via === 'download') toast('图片已保存到下载目录', 'ok');
      else if (r.via === 'android') toast('已打开分享面板', 'ok');
      else toast('已分享', 'ok');
    } catch (e) {
      console.error(e);
      toast('生成图片失败：' + (e.message || e), 'err', 3600);
    }
  }

  async function renameNote() {
    closeMenu();
    const n = S.current; if (!n) return;
    const v = await prompt('重命名', [{ name: 'title', label: '标题', value: n.title || deriveTitle(n.content) || '', placeholder: '输入标题' }], { okText: '保存' });
    if (!v) return;
    n.title = (v.title || '').trim();
    n.updatedAt = Date.now();
    el.title.value = n.title;
    await flushSave();
    renderList();
    toast('已重命名');
  }

  /* ---------------- 笔记菜单 ---------------- */
  function openMenu() { el.menuScrim.hidden = false; el.noteMenu.hidden = false; syncMenuSwitches(); }
  function closeMenu() { el.menuScrim.hidden = true; el.noteMenu.hidden = true; }
  function syncMenuSwitches() {
    const n = S.current; if (!n) return;
    document.getElementById('switch-render').setAttribute('aria-checked', n.renderMode ? 'true' : 'false');
    document.getElementById('switch-pin').setAttribute('aria-checked', n.pinned ? 'true' : 'false');
  }

  async function toggleRenderMode() {
    const n = S.current; if (!n) return;
    n.renderMode = !n.renderMode;
    syncMenuSwitches();
    n.updatedAt = Date.now();
    try { await NX.backend.saveNote(n); } catch (e) { toast('设置失败', 'err'); }
    renderList();
    toast(n.renderMode ? '已设置为「打开时渲染查看」' : '已设置为「打开时编辑」');
  }

  async function togglePin() {
    const n = S.current; if (!n) return;
    n.pinned = !n.pinned;
    syncMenuSwitches();
    try { await NX.backend.saveNote(n); } catch (e) { }
    renderList();
    toast(n.pinned ? '已置顶' : '已取消置顶');
    closeMenu();
  }

  function toggleTaskInSource(src, idx, checked) {
    let count = -1;
    return src.replace(/(^\n?|\n)([ \t]*[-*+][ \t]+\[)([ xX])(\])/g, (m, pre, a, c, b) => {
      count++;
      return count === idx ? pre + a + (checked ? 'x' : ' ') + b : m;
    });
  }

  /* ---------------- 主题 ---------------- */
  async function quickThemeToggle() {
    const cur = NX.theme.resolved();
    const next = cur === 'dark' ? 'light' : 'dark';
    NX.theme.set(next);
    await patch({ theme: next });
    syncThemeSeg();
    toast(next === 'dark' ? '已切换到深色' : '已切换到浅色', '', 1200);
  }

  function syncThemeSeg() {
    U.$$('#theme-seg button').forEach(b => b.classList.toggle('on', b.dataset.theme === S.settings.theme));
  }

  /* ---------------- 设置 ---------------- */
  async function patch(p) {
    try {
      S.settings = await NX.backend.saveSettings(p);
      S.settings = mergeDefaults(S.settings);
      renderBackupHint();
    } catch (e) { toast('保存设置失败：' + e.message, 'err'); }
    return S.settings;
  }

  function renderBackupHint() {
    /* 底部状态栏已移除，备份状态在「设置 → 备份」里查看 */
    if (!el.backupHint) return;
    const b = S.settings.backup || {};
    const last = S.settings.lastBackupAt ? ' · 上次 ' + fmtTime(S.settings.lastBackupAt) : '';
    let txt = '备份未开启';
    if (b.realtime) txt = '实时备份已开启' + last;
    else if (b.scheduled) txt = '每 ' + intervalText(b.interval) + ' 备份' + last;
    if (b.encrypt) txt = '🔒 ' + txt;
    el.backupHint.textContent = txt;
  }

  function intervalText(min) {
    min = Number(min) || 60;
    if (min < 60) return min + ' 分钟';
    if (min < 1440) return (min / 60) + ' 小时';
    return '天';
  }

  function openSettings() {
    el.settingsScrim.hidden = false;
    el.settingsModal.hidden = false;
    fillSettings();
    switchSettingsTab('appearance');
    refreshBackups().catch(() => { });
    updateStorageInfo();
  }

  function switchSettingsTab(name) {
    U.$$('#settings-tabs .tab').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
    U.$$('#settings-modal .pane').forEach(p => { p.hidden = p.dataset.pane !== name; });
    const body = el.settingsModal.querySelector('.modal-body');
    if (body) body.scrollTop = 0;
    if (name === 'backup') refreshBackups().catch(() => { });
  }

  function closeSettings() {
    el.settingsScrim.hidden = true;
    el.settingsModal.hidden = true;
    collectDavFields().catch(() => { });
  }

  function fillSettings() {
    const s = S.settings;
    syncThemeSeg();
    document.getElementById('fontsize').value = s.fontSize;
    document.getElementById('fontsize-val').textContent = s.fontSize;
    document.getElementById('dav-url').value = s.webdav.url || '';
    document.getElementById('dav-user').value = s.webdav.user || '';
    document.getElementById('dav-pass').value = s.webdav.pass || '';
    document.getElementById('dav-path').value = s.webdav.path || 'heyunotes';
    setSwitch('switch-realtime', s.backup.realtime);
    setSwitch('switch-scheduled', s.backup.scheduled);
    document.getElementById('interval').disabled = !s.backup.scheduled;
    document.getElementById('interval-custom').disabled = !s.backup.scheduled;
    document.getElementById('interval-field').style.opacity = s.backup.scheduled ? '1' : '.55';
    document.getElementById('interval-custom-field').style.opacity = s.backup.scheduled ? '1' : '.55';
    setIntervalUI(s.backup.interval || 60);
    setSwitch('switch-enc', s.backup.encrypt);
    document.getElementById('keep').value = s.backup.keep || 7;
    el.encPwdRow.hidden = !s.backup.encrypt;
    safeHasPwd().then(has => {
      el.encPwdStatus.textContent = has ? '已保存备份密码（本机加密保存，不会再次询问）' : '未设置备份密码';
    });
    document.getElementById('app-version').textContent = 'v' + (window.NX_VERSION || '1.0.0');
    document.getElementById('dav-status').textContent = '';
  }

  function setSwitch(id, on) {
    const sw = document.getElementById(id);
    if (sw) sw.setAttribute('aria-checked', on ? 'true' : 'false');
  }
  function switchOn(id) {
    const sw = document.getElementById(id);
    return sw && sw.getAttribute('aria-checked') === 'true';
  }

  function bindSettings() {
    U.$$('#theme-seg button').forEach(b => b.addEventListener('click', async () => {
      NX.theme.set(b.dataset.theme);
      await patch({ theme: b.dataset.theme });
      syncThemeSeg();
    }));

    const fs = document.getElementById('fontsize');
    fs.addEventListener('input', () => { applyFontSize(fs.value); });
    fs.addEventListener('change', () => { patch({ fontSize: Number(fs.value) }); applyFontSize(fs.value); });

    ['dav-url', 'dav-user', 'dav-pass', 'dav-path'].forEach(id => {
      const input = document.getElementById(id);
      input.addEventListener('change', () => { collectDavFields(); });
      input.addEventListener('blur', () => { collectDavFields(); });
    });

    document.getElementById('btn-dav-test').addEventListener('click', async () => {
      const st = el.davStatus;
      st.textContent = '测试中…';
      await collectDavFields();
      const r = await NX.backend.testWebdav();
      st.textContent = (r.ok ? '✓ ' : '✗ ') + (r.message || '');
      st.style.color = r.ok ? 'var(--accent)' : 'var(--danger)';
    });

    document.getElementById('switch-realtime').addEventListener('click', async () => {
      const on = !switchOn('switch-realtime');
      setSwitch('switch-realtime', on);
      await patch({ backup: { realtime: on } });
      if (on) toast('已开启实时备份：修改后自动备份');
    });

    document.getElementById('switch-scheduled').addEventListener('click', async () => {
      const on = !switchOn('switch-scheduled');
      setSwitch('switch-scheduled', on);
      document.getElementById('interval').disabled = !on;
      document.getElementById('interval-field').style.opacity = on ? '1' : '.55';
      await patch({ backup: { scheduled: on } });
      updateIntervalWarn();
    });

    document.getElementById('interval').addEventListener('change', onIntervalChange);
    document.getElementById('interval-custom').addEventListener('change', onCustomIntervalInput);

    document.getElementById('switch-enc').addEventListener('click', onEncryptToggle);
    document.getElementById('btn-change-pwd').addEventListener('click', changePassword);
    document.getElementById('keep').addEventListener('change', async () => {
      let v = Math.round(Number(document.getElementById('keep').value));
      if (!v || v < 1) v = 1;
      if (v > 30) v = 30;
      document.getElementById('keep').value = v;
      await patch({ backup: { keep: v } });
      toast('保留份数：' + v + ' 份');
    });

    document.getElementById('btn-backup-now').addEventListener('click', doBackupNow);
    document.getElementById('btn-import').addEventListener('click', () => importFromFile());
    document.getElementById('btn-refresh-list').addEventListener('click', () => refreshBackups());
    el.backupLog.addEventListener('click', onBackupLogClick);
    document.getElementById('btn-wipe').addEventListener('click', wipeAll);
  }

  async function collectDavFields() {
    const url = document.getElementById('dav-url').value.trim();
    const user = document.getElementById('dav-user').value.trim();
    const pass = document.getElementById('dav-pass').value;
    const path = document.getElementById('dav-path').value.trim() || 'heyunotes';
    const cur = S.settings.webdav;
    if (url === cur.url && user === cur.user && pass === cur.pass && path === cur.path) return;
    await patch({ webdav: { url: url, user: user, pass: pass, path: path } });
  }

  const INTERVAL_PRESETS = [1, 2, 3, 5, 10, 15, 30, 60, 180, 720, 1440];

  function setIntervalUI(min) {
    const sel = document.getElementById('interval');
    if (!sel) return;
    min = Math.max(1, Math.min(10080, Math.round(Number(min) || 60)));
    const isPreset = INTERVAL_PRESETS.indexOf(min) >= 0;
    sel.value = isPreset ? String(min) : 'custom';
    document.getElementById('interval-custom-field').hidden = isPreset;
    if (!isPreset) document.getElementById('interval-custom').value = min;
    updateIntervalWarn();
  }

  function currentIntervalValue() {
    const sel = document.getElementById('interval');
    if (!sel) return Number(S.settings.backup.interval) || 60;
    if (sel.value === 'custom') {
      const v = Math.round(Number(document.getElementById('interval-custom').value));
      return v > 0 ? v : (Number(S.settings.backup.interval) || 60);
    }
    return Number(sel.value) || 60;
  }

  function updateIntervalWarn() {
    const warn = document.getElementById('interval-warn');
    if (!warn) return;
    const scheduled = switchOn('switch-scheduled');
    warn.hidden = !(scheduled && currentIntervalValue() < 5 && !switchOn('switch-realtime'));
  }

  async function onIntervalChange() {
    const sel = document.getElementById('interval');
    const customField = document.getElementById('interval-custom-field');
    if (sel.value === 'custom') {
      customField.hidden = false;
      const input = document.getElementById('interval-custom');
      input.value = Number(S.settings.backup.interval) || 60;
      input.focus();
      if (input.select) input.select();
      updateIntervalWarn();
      return;
    }
    customField.hidden = true;
    await commitInterval(Number(sel.value));
  }

  async function onCustomIntervalInput() {
    const input = document.getElementById('interval-custom');
    let v = Math.round(Number(input.value));
    if (!v || v < 1) { input.value = Number(S.settings.backup.interval) || 60; return; }
    if (v > 10080) { v = 10080; input.value = 10080; }
    await commitInterval(v);
  }

  async function commitInterval(val) {
    val = Math.max(1, Math.min(10080, Math.round(Number(val) || 60)));
    if (val < 5 && !switchOn('switch-realtime')) {
      const r = await dialog({
        title: '建议使用实时备份',
        html: '<p>定时备份间隔小于 5 分钟会比较耗电，系统也可能延迟执行。<br>建议改用「实时备份」：笔记一有修改就立即备份。</p>',
        actions: [
          { id: 'cancel', label: '取消', kind: 'ghost' },
          { id: 'keep', label: '仍然保存', kind: 'ghost' },
          { id: 'realtime', label: '改用实时备份', kind: 'primary' }
        ]
      });
      if (!r || r.action === 'cancel') { setIntervalUI(S.settings.backup.interval || 60); return; }
      if (r.action === 'realtime') {
        setSwitch('switch-realtime', true);
        await patch({ backup: { realtime: true, interval: 5, scheduled: true } });
        setIntervalUI(5);
        toast('已开启实时备份，定时间隔设为 5 分钟');
        return;
      }
    }
    await patch({ backup: { interval: val } });
    setIntervalUI(val);
  }

  async function safeHasPwd() {
    try { return await NX.backend.hasBackupPassword(); } catch (e) { return false; }
  }

  async function promptEncPassword() {
    const v = await dialog({
      title: '设置备份加密密码',
      html: '<p>开启加密后，备份文件使用 AES-256-GCM 加密，导入时需要输入该密码。<br><b>请务必记住密码，忘记后无法恢复备份。</b></p>',
      cancelable: false,
      fields: [
        { name: 'pwd', label: '备份密码（至少 6 位）', type: 'password', placeholder: '输入密码' },
        { name: 'pwd2', label: '再次输入', type: 'password', placeholder: '确认密码' }
      ],
      validate: v => {
        if (!v.pwd || v.pwd.length < 6) return '密码至少 6 位';
        if (v.pwd !== v.pwd2) return '两次输入的密码不一致';
        return null;
      },
      actions: [{ id: 'cancel', label: '取消', kind: 'ghost' }, { id: 'ok', label: '保存密码', kind: 'primary' }]
    });
    if (!v || v.action !== 'ok') return null;
    await NX.backend.setBackupPassword(v.values.pwd);
    toast('备份密码已保存', 'ok');
    return v.values.pwd;
  }

  async function onEncryptToggle() {
    const on = !switchOn('switch-enc');
    if (!on) {
      setSwitch('switch-enc', false);
      el.encPwdRow.hidden = true;
      await patch({ backup: { encrypt: false } });
      toast('已关闭备份加密');
      return;
    }
    let has = await safeHasPwd();
    if (!has) {
      const pwd = await promptEncPassword();
      if (!pwd) { setSwitch('switch-enc', false); return; }
      has = true;
    }
    setSwitch('switch-enc', true);
    el.encPwdRow.hidden = false;
    el.encPwdStatus.textContent = '已保存备份密码（本机加密保存，不会再次询问）';
    await patch({ backup: { encrypt: true } });
    toast('已开启加密备份', 'ok');
  }

  async function changePassword() {
    const v = await dialog({
      title: '修改备份密码',
      html: '<p>修改后请用新密码导入之后的加密备份，历史备份仍需旧密码。</p>',
      cancelable: false,
      fields: [
        { name: 'pwd', label: '新密码（至少 6 位）', type: 'password' },
        { name: 'pwd2', label: '再次输入', type: 'password' }
      ],
      validate: v => (v.pwd && v.pwd.length >= 6) ? (v.pwd === v.pwd2 ? null : '两次输入的密码不一致') : '密码至少 6 位',
      actions: [{ id: 'cancel', label: '取消', kind: 'ghost' }, { id: 'ok', label: '保存', kind: 'primary' }]
    });
    if (!v || v.action !== 'ok') return;
    await NX.backend.setBackupPassword(v.values.pwd);
    el.encPwdStatus.textContent = '已保存备份密码（本机加密保存，不会再次询问）';
    toast('备份密码已更新', 'ok');
  }

  async function doBackupNow() {
    el.backupStatus.textContent = '备份中…';
    try {
      const r = await NX.backend.backupNow('manual');
      if (!r || r.ok === false) {
        if (r && r.error === 'NO_PASSWORD') { toast('请先设置备份密码', 'err'); await changePassword(); }
        else toast('备份失败：' + ((r && r.error) || '未知错误'), 'err');
        el.backupStatus.textContent = '';
        return;
      }
      if (r.skipped) { toast(r.message || '没有变化，已跳过'); el.backupStatus.textContent = r.message || ''; return; }
      S.settings.lastBackupAt = Date.now();
      S.settings.lastBackupType = r.type;
      renderBackupHint();
      el.backupStatus.textContent = '✓ ' + (r.file || '备份完成') + (r.size ? '（' + fmtSize(r.size) + '）' : '');
      toast(r.message || '备份完成', 'ok');
      refreshBackups();
    } catch (e) {
      el.backupStatus.textContent = '备份失败：' + e.message;
      toast('备份失败：' + e.message, 'err');
    }
  }

  /* ---------------- 备份列表 ---------------- */
  async function refreshBackups() {
    el.backupLog.innerHTML = '<p class="row-status">读取中…</p>';
    let items = [];
    try { items = await NX.backend.listBackups(); } catch (e) { items = []; }
    S.backups = items || [];
    if (!S.backups.length) {
      el.backupLog.innerHTML = '<p class="row-status">还没有备份记录。点击「立即备份」创建第一份。</p>';
      return;
    }
    el.backupLog.innerHTML = S.backups.map(it => {
      const size = it.size ? fmtSize(it.size) : '';
      const when = it.ts ? fmtDateTime(it.ts) : (it.name || '').replace(/^notes-/, '').replace(/\.(hnb|hnbe)$/, '');
      return '<div class="backup-row" data-id="' + esc(it.id) + '">' +
        '<div class="bk-name"><b>' + esc(it.name) + '</b>' +
        '<small>' + esc(when) + (size ? ' · ' + size : '') + ' · ' + (it.source === 'webdav' ? 'WebDAV' : '本机') + '</small></div>' +
        '<span class="bk-tag' + (it.encrypted ? ' enc' : '') + '">' + (it.encrypted ? '加密' : '明文') + '</span>' +
        '<span class="bk-tag">' + (it.type === 'full' ? '完整' : '增量') + '</span>' +
        '<div class="bk-actions">' +
        '<button class="btn ghost small" data-act="restore">恢复</button>' +
        '<button class="btn ghost small" data-act="download">导出</button>' +
        '<button class="btn ghost small danger" data-act="delete">删除</button>' +
        '</div></div>';
    }).join('');
    const last = S.backups[0];
    if (last && !el.backupStatus.textContent) el.backupStatus.textContent = '最近备份：' + fmtDateTime(last.ts || 0).replace('1970-01-01 08:00:00', '—');
  }

  async function onBackupLogClick(e) {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const row = e.target.closest('.backup-row');
    const id = row && row.dataset.id;
    const item = S.backups.find(x => x.id === id);
    if (!item) return;
    const act = btn.dataset.act;
    if (act === 'restore') return restoreFrom(item);
    if (act === 'delete') {
      const ok = await confirm('删除备份文件？', '<p>' + esc(item.name) + ' 将从' + (item.source === 'webdav' ? ' WebDAV ' : '本机') + '删除。</p>', { okText: '删除', danger: true });
      if (!ok) return;
      await NX.backend.deleteBackup(item.id);
      toast('已删除备份');
      refreshBackups();
      return;
    }
    if (act === 'download') {
      if (item.source === 'webdav') { toast('WebDAV 备份请使用「恢复」或下载原文件'); return; }
      if (NX.backend.kind === 'android') {
        const r = await NX.backend.downloadBackupFile(item.id);
        toast(r && r.ok ? (r.data && r.data.message) || '已导出' : '导出失败：' + ((r && r.error) || ''), r && r.ok ? 'ok' : 'err');
      } else {
        await NX.backend.downloadBackupFile(item.id);
        toast('已开始下载');
      }
    }
  }

  async function restoreFrom(item) {
    const b = await NX.backend.getBackup(item.id);
    if (!b) { toast('无法读取该备份', 'err'); return; }
    const text = await readBackupText(b);
    if (!text) return;
    await applyBackupText(text, item.name);
  }

  async function importFromFile() {
    let picked;
    try { picked = await NX.backend.pickFile(); } catch (e) { picked = null; }
    if (!picked) return;
    const text = await readBackupText(picked);
    if (!text) return;
    await applyBackupText(text, picked.name);
  }

  /* 读取备份：自动识别加密并请求密码 */
  async function readBackupText(file) {
    const bytes = U.base64ToBytes(file.base64);
    if (!NX.backup.isEncrypted(bytes)) {
      try { return U.bytesToText(bytes); } catch (e) { toast('文件编码错误', 'err'); return null; }
    }
    let pwd = '';
    try { pwd = (await NX.backend.getBackupPassword()) || ''; } catch (e) { }
    let r = await NX.backend.decryptText(file.base64, pwd);
    let tries = 0;
    while (!r.ok && tries < 5) {
      const v = await prompt('备份已加密', [
        { name: 'pwd', label: '请输入备份密码', type: 'password', placeholder: '备份加密密码' }
      ], {
        okText: '解密',
        cancelable: false,
        html: '<p>这个备份文件使用 AES-256-GCM 加密。<br>请输入设置备份时使用的密码。</p>'
      });
      if (!v) return null;
      r = await NX.backend.decryptText(file.base64, v.pwd);
      if (!r.ok) toast(r.error || '密码错误，请重试', 'err');
      tries++;
    }
    if (!r.ok) { toast('解密失败', 'err'); return null; }
    return r.text;
  }

  async function applyBackupText(text, name) {
    let doc;
    try { doc = JSON.parse(text); } catch (e) { toast('文件不是有效的 JSON 备份', 'err'); return; }
    const bad = NX.backup.validate(doc);
    if (bad) { toast(bad, 'err'); return; }
    const when = doc.createdAt ? fmtDateTime(doc.createdAt) : '未知时间';
    const r = await dialog({
      title: '导入备份',
      html: '<p><b>' + esc(name || '') + '</b></p>' +
        '<p>备份时间：' + esc(when) + ' · ' + (doc.type === 'full' ? '完整备份' : '增量备份') +
        '<br>包含 ' + (doc.notes || []).length + ' 条笔记变更，删除记录 ' + (doc.removed || []).length + ' 条。</p>' +
        '<p>「合并」会按更新时间保留较新的内容；「覆盖」会删除本机多余笔记。</p>',
      actions: [
        { id: 'cancel', label: '取消', kind: 'ghost' },
        { id: 'merge', label: '合并导入', kind: 'primary' },
        { id: 'replace', label: '覆盖导入', kind: 'primary' }
      ]
    });
    if (!r || r.action === 'cancel') return;
    let res;
    try { res = await NX.backup.apply(doc, { replaceAll: r.action === 'replace' }); }
    catch (e) { toast('导入失败：' + e.message, 'err'); return; }
    await reloadNotes();
    renderList();
    if (S.current) {
      const cur = S.notes.find(n => n.id === S.current.id);
      S.current = cur || null;
      if (!cur) { const first = visibleNotes()[0]; if (first) openNote(first.id); else showEmpty(); }
      else renderNote();
    }
    toast('导入完成：新增 ' + res.added + ' · 更新 ' + res.updated + ' · 删除 ' + res.removed, 'ok', 3200);
    refreshBackups();
  }

  async function updateStorageInfo() {
    try {
      const st = await NX.backend.stats();
      el.storageInfo.innerHTML = st.notes + ' 条笔记 · ' + fmtSize(st.bytes) + ' · 备份 ' + st.backups + ' 份<br><span style="color:var(--text3)">' + esc(st.location || '') + '</span>';
    } catch (e) { el.storageInfo.textContent = ''; }
  }

  async function wipeAll() {
    const ok = await confirm('清空所有笔记？', '<p>共 ' + S.notes.length + ' 条笔记将被永久删除，建议先做一次备份。此操作不可撤销。</p>', { okText: '全部删除', danger: true });
    if (!ok) return;
    try {
      if (NX.backend.wipe) await NX.backend.wipe();
      else for (const n of S.notes) await NX.backend.deleteNote(n.id, { silent: true });
    } catch (e) { toast('清空失败：' + e.message, 'err'); return; }
    S.notes = [];
    S.current = null;
    showEmpty();
    renderList();
    updateStorageInfo();
    toast('已清空');
  }

  /* ---------------- 自动备份 ---------------- */
  function queueAutoBackup() {
    if (!S.settings || !S.settings.backup.realtime) return;
    clearTimeout(S.backupTimer);
    S.backupTimer = setTimeout(async () => {
      try {
        const r = await NX.backend.backupNow('realtime');
        if (r && r.ok && !r.skipped) {
          S.settings.lastBackupAt = Date.now();
          S.settings.lastBackupType = r.type;
          renderBackupHint();
        } else if (r && r.ok === false && r.error !== 'NO_PASSWORD') {
          console.warn('auto backup failed', r.error);
        }
      } catch (e) { console.warn('auto backup error', e); }
    }, 2500);
  }

  NX.app = { S: S, refresh: async () => { await reloadNotes(); renderList(); } };
})();
