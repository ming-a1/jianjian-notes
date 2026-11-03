/**
 * 简笺 Web 端功能测试（jsdom + fake-indexeddb + Node WebCrypto）
 * 直接运行真实的 www 前端代码，覆盖：笔记 CRUD、Markdown 渲染、主题、
 * 单篇渲染开关、备份（完整/增量/加密）、留存、导入合并、定时警告。
 */
const { JSDOM, VirtualConsole } = require('jsdom');
const { indexedDB, IDBKeyRange } = require('fake-indexeddb');
const { webcrypto } = require('crypto');
const { TextEncoder, TextDecoder } = require('util');

const URL_APP = process.env.APP_URL || 'http://127.0.0.1:8899/index.html';
let pass = 0, fail = 0;
const failures = [];

function ok(cond, name, extra) {
  if (cond) { pass++; console.log('  PASS', name); }
  else { fail++; failures.push(name + (extra ? ' :: ' + extra : '')); console.log('  FAIL', name, extra || ''); }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function waitFor(fn, desc, timeout = 6000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    try { if (await fn()) return true; } catch (e) { }
    await sleep(50);
  }
  throw new Error('等待超时: ' + desc);
}

function makeDom() {
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => { if (!/Not implemented: navigation/.test(e.message)) console.error('[jsdom]', e.message); });
  vc.on('error', (...a) => console.error('[page error]', ...a));
  return JSDOM.fromURL(URL_APP, {
    virtualConsole: vc,
    runScripts: 'dangerously',
    resources: 'usable',
    pretendToBeVisual: true,
    beforeParse(window) {
      window.TextEncoder = TextEncoder;
      window.TextDecoder = TextDecoder;
      window.indexedDB = indexedDB;
      window.IDBKeyRange = IDBKeyRange;
      Object.defineProperty(window, 'crypto', { value: webcrypto, configurable: true });
      window.matchMedia = q => ({
        matches: false, media: q, onchange: null,
        addEventListener() { }, removeEventListener() { }, addListener() { }, removeListener() { }, dispatchEvent() { return false; }
      });
      window.URL.createObjectURL = () => 'blob:fake';
      window.URL.revokeObjectURL = () => { };
      window.scrollTo = () => { };
    }
  });
}

(async () => {
  const dom = await makeDom();
  const win = dom.window;
  const doc = win.document;
  const $ = s => doc.querySelector(s);
  const $$ = s => Array.from(doc.querySelectorAll(s));
  const click = el => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true, cancelable: true }));
  const type = (el, v) => { el.value = v; el.dispatchEvent(new win.Event('input', { bubbles: true })); };
  const change = (el, v) => { el.value = v; el.dispatchEvent(new win.Event('change', { bubbles: true })); };

  await new Promise(res => win.addEventListener('load', res));
  await waitFor(() => win.NX && win.NX.app && win.NX.app.S.settings, '应用启动');

  console.log('\n[1] 启动与空状态');
  ok(!!$('#list-count'), '列表计数存在');
  ok($('#empty').hidden === false, '无笔记时显示空状态');

  console.log('\n[2] 新建笔记 + Markdown 渲染');
  click($('#btn-new'));
  await waitFor(() => $('#note-view').hidden === false && win.NX.app.S.current, '新建笔记');
  const md = [
    '# 标题一',
    '',
    '**加粗** 和 `行内代码`，还有 [链接](https://example.com)。',
    '',
    '| 列A | 列B |',
    '| --- | --- |',
    '| 1 | 2 |',
    '',
    '- [ ] 待办一',
    '- [x] 待办二',
    '',
    '```js',
    "console.log('hi')",
    '```',
    '',
    '> 引用文本',
    '',
    '脚注引用[^1]',
    '',
    '[^1]: 脚注内容'
  ].join('\n');
  type($('#editor'), md);
  await sleep(800);
  click($$('#mode-seg button').find(b => b.dataset.mode === 'read'));
  await waitFor(() => $$('#preview h1').length > 0, '预览渲染');
  ok($('#preview h1').textContent.includes('标题一'), 'H1 渲染');
  ok($$('#preview table').length === 1, '表格渲染');
  ok($$('#preview pre code').length >= 1, '代码块渲染');
  ok($$('#preview input.task-list-item-checkbox').length === 2, '任务清单渲染');
  ok($$('#preview .footnotes').length === 1, '脚注渲染');
  ok($('#preview').innerHTML.includes('hljs'), '代码高亮');
  ok(!!$('#preview .code-card'), '代码块工具栏');
  const codeBtns = $$('#preview .code-card .code-btn').map(b => b.textContent);
  ok(['折叠', '展开'].some(t => codeBtns.includes(t)), '折叠按钮');
  ok(codeBtns.includes('复制') && codeBtns.includes('下载') && codeBtns.includes('放大'), '复制/下载/放大按钮');
  const card = $('#preview .code-card');
  const toggleBtn = $$('#preview .code-card .code-btn').find(b => b.textContent === '折叠' || b.textContent === '展开');
  toggleBtn.click();
  ok(card.classList.contains('collapsed'), '折叠生效');
  toggleBtn.click();
  ok(!card.classList.contains('collapsed'), '展开生效');
  $$('#preview .code-card .code-btn').find(b => b.textContent === '放大').click();
  ok(!!$('.code-full'), '放大浮层打开');
  const overlay = $('.code-full');
  if (overlay) overlay.remove();
  click($$('#mode-seg button').find(b => b.dataset.mode === 'edit'));
  await sleep(100);

  console.log('\n[3] 单篇「渲染后查看」开关');
  click($('#btn-more'));
  ok($('#note-menu').hidden === false, '菜单打开');
  click($('#btn-render-toggle'));
  await sleep(300);
  ok($('#switch-render').getAttribute('aria-checked') === 'true', '开关状态为开');
  click($('#menu-scrim'));
  await sleep(100);
  ok($$('.note-item .badge.render').length === 1, '列表显示渲染标记');
  click($('#btn-back'));
  await sleep(100);
  click($$('.note-item')[0]);
  await sleep(200);
  ok($('#editor').hidden === true && $('#preview').hidden === false, '重新打开直接进入渲染视图');

  console.log('\n[4] 主题切换');
  ok(doc.documentElement.getAttribute('data-theme') === 'light', '默认白色（浅色）主题');
  click($('#btn-theme'));
  await waitFor(() => doc.documentElement.getAttribute('data-theme') === 'dark', '切换深色');
  ok(doc.documentElement.getAttribute('data-theme') === 'dark', '深色主题生效');
  click($('#btn-theme'));
  await waitFor(() => doc.documentElement.getAttribute('data-theme') === 'light', '切回浅色');
  ok((await win.NX.backend.settings()).theme === 'light', '主题已持久化');

  console.log('\n[5] 设置面板');
  click($('#btn-settings'));
  ok($('#settings-modal').hidden === false, '设置面板打开');
  ok($('#switch-realtime').getAttribute('aria-checked') === 'false', '实时备份默认关闭');
  ok($('#switch-scheduled').getAttribute('aria-checked') === 'false', '定时备份默认关闭');
  ok($('#switch-enc').getAttribute('aria-checked') === 'false', '加密备份默认关闭');

  console.log('\n[6] 备份：完整 / 增量 / 留存');
  click($('#btn-backup-now'));
  await waitFor(() => $('#backup-status').textContent.includes('notes-'), '首次备份完成');
  let backups = await win.NX.backend.listBackups();
  ok(backups.length === 1, '生成第一份备份', 'len=' + backups.length);
  ok(backups[0].type === 'full', '首份为完整备份', backups[0].type);
  const first = await win.NX.backend.getBackup(backups[0].id);
  const firstDoc = JSON.parse(win.NX.util.bytesToText(win.NX.util.base64ToBytes(first.base64)));
  ok(firstDoc.format === 'heyunotes.backup' && firstDoc.notes.length === 1, '备份内容为合法 JSON 且含 1 条笔记');

  // 修改笔记后再备份 → 增量
  click($('#btn-close-settings'));
  await sleep(100);
  type($('#editor'), md + '\n\n新增一行内容');
  await sleep(900);
  click($('#btn-settings'));
  click($('#btn-backup-now'));
  await waitFor(async () => (await win.NX.backend.listBackups()).length === 2, '第二份备份', 8000);
  backups = await win.NX.backend.listBackups();
  ok(backups[0].type === 'incremental', '第二份为增量备份', backups[0].type);
  const b2 = JSON.parse(win.NX.util.bytesToText(win.NX.util.base64ToBytes((await win.NX.backend.getBackup(backups[0].id)).base64)));
  ok(b2.notes.length === 1 && b2.type === 'incremental', '增量备份只包含变更的笔记');

  // 留存
  change($('#keep'), '2');
  await sleep(300);
  for (let i = 0; i < 3; i++) {
    click($('#btn-close-settings'));
    await sleep(80);
    type($('#editor'), md + '\n\n第 ' + i + ' 次修改');
    await sleep(700);
    click($('#btn-settings'));
    click($('#btn-backup-now'));
    await sleep(1200);
  }
  backups = await win.NX.backend.listBackups();
  ok(backups.length <= 2, '留存份数生效（≤2）', 'len=' + backups.length);

  console.log('\n[7] 加密备份');
  const pwd = 'test-pass-123';
  click($('#switch-enc'));
  await waitFor(() => $('#dialog').hidden === false, '加密密码对话框');
  type($('#dialog-body input[data-name="pwd"]'), pwd);
  type($('#dialog-body input[data-name="pwd2"]'), pwd);
  click($('#dialog-actions button[data-action="ok"]'));
  await waitFor(() => $('#switch-enc').getAttribute('aria-checked') === 'true', '加密开关打开');
  ok(await win.NX.backend.hasBackupPassword(), '备份密码已保存');
  click($('#btn-close-settings'));
  await sleep(80);
  type($('#editor'), md + '\n\n加密前的修改');
  await sleep(700);
  click($('#btn-settings'));
  click($('#btn-backup-now'));
  await sleep(1500);
  backups = await win.NX.backend.listBackups();
  const eb = backups.find(b => b.encrypted);
  ok(!!eb, '生成了加密备份', JSON.stringify(backups.map(b => b.name)));
  const encBytes = win.NX.util.base64ToBytes((await win.NX.backend.getBackup(eb.id)).base64);
  ok(win.NX.backup.isEncrypted(encBytes), '加密文件带 HNB1 头');
  const dec = await win.NX.util.bytesToText(await win.NX.backup.decrypt(encBytes, pwd));
  ok(JSON.parse(dec).format === 'heyunotes.backup', '可用正确密码解密');
  let wrong = null;
  try { await win.NX.backup.decrypt(encBytes, 'bad-password'); } catch (e) { wrong = e; }
  ok(!!wrong, '错误密码无法解密');

  console.log('\n[8] 导入（合并）');
  const before = win.NX.app.S.notes.length;
  const importDoc = {
    format: 'heyunotes.backup', version: 1, type: 'incremental', createdAt: Date.now(),
    notes: [{ id: 'import-test-1', title: '导入的笔记', content: '# 来自备份', createdAt: 1, updatedAt: Date.now(), pinned: false, renderMode: false }],
    removed: []
  };
  const res = await win.NX.backup.apply(importDoc, {});
  await win.NX.app.refresh();
  ok(res.added === 1, '导入新增 1 条', JSON.stringify(res));
  ok(win.NX.app.S.notes.length === before + 1, '笔记数量 +1');
  const imported = win.NX.app.S.notes.find(n => n.id === 'import-test-1');
  ok(imported && imported.title === '导入的笔记', '导入笔记内容正确');

  console.log('\n[9] 定时备份 <5 分钟提示');
  click($('#btn-close-settings'));
  await sleep(80);
  click($('#btn-settings'));
  await sleep(150);
  ok(await (async () => !(await win.NX.backend.settings()).backup.realtime)(), '实时备份仍为关闭');

  console.log('\n[9.1] 设置 Tab 与自定义间隔');
  click($$('#settings-tabs .tab').find(t => t.dataset.tab === 'backup'));
  await sleep(120);
  ok($$('#settings-modal .pane').filter(p => !p.hidden).length === 1, 'Tab 切换：同一时间只显示一个面板');
  ok($('#settings-modal .pane[data-pane="backup"]').hidden === false, '备份面板可见');
  click($$('#settings-tabs .tab').find(t => t.dataset.tab === 'about'));
  await sleep(100);
  ok($('#settings-modal .pane[data-pane="about"]').hidden === false, '关于面板可见');
  click($$('#settings-tabs .tab').find(t => t.dataset.tab === 'backup'));
  await sleep(100);

  change($('#interval'), 'custom');
  await sleep(150);
  ok($('#interval-custom-field').hidden === false, '选择「自定义」后出现分钟输入框');
  change($('#interval-custom'), '8');
  await sleep(500);
  ok((await win.NX.backend.settings()).backup.interval === 8, '自定义间隔保存为 8 分钟');
  ok($('#interval').value === 'custom', '自定义间隔回填到下拉框');

  change($('#interval'), '1');
  await sleep(400);
  ok($('#dialog').hidden === false && ($('#dialog-title').textContent || '').includes('实时备份'), '小于 5 分钟弹出建议');
  click($('#dialog-actions button[data-action="cancel"]'));
  await sleep(200);
  ok($('#interval').value !== '1', '取消后间隔回退');

  console.log('\n[10] 实时备份触发');
  click($('#switch-realtime')); // 开启实时备份
  await sleep(300);
  const beforeCount = (await win.NX.backend.listBackups()).length;
  type($('#editor'), md + '\n\n实时备份触发' + Date.now());
  await sleep(4200);
  const afterCount = (await win.NX.backend.listBackups()).length;
  ok(afterCount > beforeCount || (await win.NX.backend.settings()).lastBackupAt > 0, '修改后自动备份（实时）');

  console.log('\n[11] 持久化：重载后数据仍在');
  const noteCount = win.NX.app.S.notes.length;
  const dom2 = await makeDom();
  await new Promise(res => dom2.window.addEventListener('load', res));
  await waitFor(() => dom2.window.NX && dom2.window.NX.app && dom2.window.NX.app.S.settings, '第二次启动');
  await sleep(600);
  ok(dom2.window.NX.app.S.notes.length === noteCount, '刷新后笔记数量一致', dom2.window.NX.app.S.notes.length + ' vs ' + noteCount);
  ok(dom2.window.document.getElementById('note-view').hidden === true, '启动停在列表，不自动打开上一篇笔记');
  ok(dom2.window.document.documentElement.getAttribute('data-theme') === 'light', '主题设置持久化');
  dom2.window.close();

  console.log('\n============================');
  console.log('通过 ' + pass + ' 项，失败 ' + fail + ' 项');
  if (fail) { console.log('失败项：\n - ' + failures.join('\n - ')); }
  win.close();
  process.exit(fail ? 1 : 0);
})().catch(e => {
  console.error('测试异常:', e);
  process.exit(2);
});
