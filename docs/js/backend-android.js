/* Android 后端：通过 window.Native 桥接原生 Java（文件存储 / 加密 / WebDAV / 定时备份） */
window.NX = window.NX || {};
(function () {
  const hasNative = !!(window.Native && window.Native.call);
  const pending = {};
  let seq = 0;

  window.__nxCb = function (id, json) {
    const fn = pending[id];
    delete pending[id];
    if (!fn) return;
    let data;
    try { data = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { data = { ok: false, error: '返回数据解析失败' }; }
    fn(data);
  };

  if (!hasNative) return;

  function sync(action, payload) {
    const raw = window.Native.call(action, JSON.stringify(payload || {}));
    let r;
    try { r = JSON.parse(raw); } catch (e) { throw new Error('原生返回解析失败'); }
    if (!r || !r.ok) throw new Error((r && r.error) || '原生操作失败');
    return r.data;
  }

  function asyncCall(action, payload) {
    return new Promise(resolve => {
      const id = 'cb' + (++seq) + '_' + Date.now().toString(36);
      pending[id] = resolve;
      try {
        window.Native.asyncCall(action, JSON.stringify(payload || {}), id);
      } catch (e) {
        delete pending[id];
        resolve({ ok: false, error: String(e) });
      }
      setTimeout(() => {
        if (pending[id]) { delete pending[id]; resolve({ ok: false, error: '操作超时（60s）' }); }
      }, 60000);
    });
  }

  const A = { kind: 'android' };

  A.init = async function () { return A.settings(); };

  A.notes = async function () { return sync('notes') || []; };
  A.saveNote = async function (note) { return sync('saveNote', { note: note }); };
  A.deleteNote = async function (id, opts) { sync('deleteNote', { id: id, silent: !!(opts && opts.silent) }); };
  A.settings = async function () { return sync('settings'); };
  A.saveSettings = async function (s) {
    const merged = sync('saveSettings', { settings: s });
    sync('setSchedule', { backup: merged.backup });
    return merged;
  };

  A.hasBackupPassword = async function () { return !!sync('hasPwd'); };
  A.setBackupPassword = async function (p) { sync('setPwd', { pwd: p }); };
  A.clearBackupPassword = async function () { sync('clearPwd'); };
  A.getBackupPassword = async function () { return ''; };

  A.encryptText = async function (text, pwd) { return asyncCall('encrypt', { text: text, pwd: pwd }); };
  A.decryptText = async function (base64, pwd) { return asyncCall('decrypt', { base64: base64, pwd: pwd }); };

  A.backupNow = async function (reason, opts) { return asyncCall('backupNow', { reason: reason, download: !!(opts && opts.download) }); };
  A.listBackups = async function () { const r = await asyncCall('listBackups'); return r.ok ? (r.data || []) : []; };
  A.getBackup = async function (id) { const r = await asyncCall('getBackup', { id: id }); return r.ok ? r.data : null; };
  A.deleteBackup = async function (id) { const r = await asyncCall('deleteBackup', { id: id }); return !!r.ok; };
  A.downloadBackupFile = async function (id) { return asyncCall('exportBackup', { id: id }); };
  A.pickFile = async function () { const r = await asyncCall('pickFile'); return r.ok ? r.data : null; };
  A.testWebdav = async function () { const r = await asyncCall('testWebdav'); return r.ok ? r.data : { ok: false, message: r.error || '失败' }; };
  A.stats = async function () { return sync('stats'); };
  A.setSchedule = async function (s) { sync('setSchedule', { backup: (s && s.backup) || {} }); };
  A.shareImage = async function (base64, name) { return asyncCall('shareImage', { base64: base64, name: name }); };

  A.wipe = async function () { const r = await asyncCall('wipe'); return !!r.ok; };

  A.back = function () { try { window.Native.back(); } catch (e) { } };

  NX.backendAndroid = A;
  NX.backend = A;

  NX.native = NX.native || {};
  NX.native.available = true;
  NX.native.setSystemTheme = function (dark) {
    try { window.Native.setSystemTheme(!!dark); } catch (e) { }
  };
})();
