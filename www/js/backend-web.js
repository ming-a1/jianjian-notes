/* Web 后端：IndexedDB 本地存储 + 文件导出导入 + WebDAV（CORS 允许时） */
window.NX = window.NX || {};
(function () {
  const B = NX.backup;
  const DB_NAME = 'jianjian-notes';
  const DB_VER = 2;
  let dbp = null;

  function db() {
    if (dbp) return dbp;
    dbp = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VER);
      req.onupgradeneeded = e => {
        const d = e.target.result;
        if (!d.objectStoreNames.contains('notes')) d.createObjectStore('notes', { keyPath: 'id' });
        if (!d.objectStoreNames.contains('settings')) d.createObjectStore('settings');
        if (!d.objectStoreNames.contains('backups')) d.createObjectStore('backups', { keyPath: 'id' });
        if (!d.objectStoreNames.contains('meta')) d.createObjectStore('meta');
        if (!d.objectStoreNames.contains('secrets')) d.createObjectStore('secrets');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbp;
  }

  function run(store, mode, fn) {
    return db().then(d => new Promise((resolve, reject) => {
      const t = d.transaction(store, mode);
      const s = t.objectStore(store);
      let req;
      try { req = fn(s); } catch (e) { reject(e); return; }
      t.oncomplete = () => resolve(req && typeof req === 'object' && 'result' in req ? req.result : undefined);
      t.onerror = () => reject(t.error);
      t.onabort = () => reject(t.error);
    }));
  }

  const idb = {
    get: (store, key) => run(store, 'readonly', s => s.get(key)),
    getAll: store => run(store, 'readonly', s => s.getAll()),
    put: (store, val, key) => run(store, 'readwrite', s => (key === undefined ? s.put(val) : s.put(val, key))),
    del: (store, key) => run(store, 'readwrite', s => s.delete(key)),
    clear: store => run(store, 'readwrite', s => s.clear())
  };

  const DEFAULTS = {
    theme: 'light',
    fontSize: 16,
    webdav: { url: '', user: '', pass: '', path: 'heyunotes' },
    backup: { realtime: false, scheduled: false, interval: 60, encrypt: false, keep: 7 },
    backupIndex: { lastIncludedAt: 0, lastFullAt: 0, sinceFull: 0 },
    settingsVersion: 2,
    lastBackupAt: 0,
    lastBackupType: ''
  };

  const A = { kind: 'web', _schedTimer: null };

  A.init = async function () {
    const s = await A.settings();
    A._schedule(s);
    return s;
  };

  A.defaults = () => JSON.parse(JSON.stringify(DEFAULTS));

  A.notes = async function () {
    const list = await idb.getAll('notes');
    return (list || []).filter(n => n && n.id);
  };

  A.saveNote = async function (note, opts) {
    const n = Object.assign({}, note);
    if (!n.id) n.id = NX.util.uid();
    n.updatedAt = n.updatedAt || Date.now();
    n.createdAt = n.createdAt || n.updatedAt;
    await idb.put('notes', n);
    return n;
  };

  A.deleteNote = async function (id, opts) {
    await idb.del('notes', id);
    if (!opts || !opts.silent) {
      const tombs = (await idb.get('meta', 'tombstones')) || [];
      tombs.push({ id: id, at: Date.now() });
      const cutoff = Date.now() - 30 * 86400000;
      await idb.put('meta', tombs.filter(t => t.at > cutoff), 'tombstones');
    }
  };

  A.settings = async function () {
    const s = await idb.get('settings', 'app');
    const base = JSON.parse(JSON.stringify(DEFAULTS));
    if (!s) return base;
    const merged = {
      theme: s.theme || base.theme,
      fontSize: s.fontSize || base.fontSize,
      webdav: Object.assign(base.webdav, s.webdav || {}),
      backup: Object.assign(base.backup, s.backup || {}),
      backupIndex: Object.assign(base.backupIndex, s.backupIndex || {}),
      settingsVersion: s.settingsVersion || 1,
      lastBackupAt: s.lastBackupAt || 0,
      lastBackupType: s.lastBackupType || ''
    };
    /* v1 -> v2：备份策略默认改为关闭（老数据一次性纠正，其余设置保留） */
    if (merged.settingsVersion < 2) {
      merged.backup.realtime = false;
      merged.backup.scheduled = false;
      merged.settingsVersion = 2;
      await idb.put('settings', merged, 'app');
    }
    return merged;
  };

  A.saveSettings = async function (s) {
    const cur = await A.settings();
    const merged = JSON.parse(JSON.stringify(cur));
    if (s) {
      ['theme', 'fontSize', 'lastBackupAt', 'lastBackupType', 'settingsVersion'].forEach(k => {
        if (s[k] !== undefined && s[k] !== null) merged[k] = s[k];
      });
      if (s.webdav) merged.webdav = Object.assign({}, cur.webdav, s.webdav);
      if (s.backup) merged.backup = Object.assign({}, cur.backup, s.backup);
      if (s.backupIndex) merged.backupIndex = Object.assign({}, cur.backupIndex, s.backupIndex);
    }
    await idb.put('settings', merged, 'app');
    A._schedule(merged);
    return merged;
  };

  /* 使用 WebCrypto，密码只保存在本机 IndexedDB */
  A.hasBackupPassword = async function () {
    const v = await idb.get('secrets', 'encpwd');
    return !!(v && v.pwd);
  };
  A.setBackupPassword = async function (pwd) {
    await idb.put('secrets', { pwd: pwd }, 'encpwd');
  };
  A.clearBackupPassword = async function () {
    await idb.del('secrets', 'encpwd');
  };
  A.getBackupPassword = async function () {
    const v = await idb.get('secrets', 'encpwd');
    return v && v.pwd ? v.pwd : '';
  };

  A.encryptText = async function (text, pwd) {
    try {
      const bytes = await B.encryptText(text, pwd);
      return { ok: true, base64: NX.util.bytesToBase64(bytes) };
    } catch (e) {
      return { ok: false, error: e.message || String(e) };
    }
  };

  A.decryptText = async function (base64, pwd) {
    try {
      const bytes = NX.util.base64ToBytes(base64);
      if (!B.isEncrypted(bytes)) return { ok: true, text: NX.util.bytesToText(bytes) };
      if (!pwd) return { ok: false, error: '需要密码', needPassword: true };
      const text = await B.decryptText(bytes, pwd);
      return { ok: true, text: text };
    } catch (e) {
      return { ok: false, error: '密码错误或文件已损坏', needPassword: true };
    }
  };

  A.backupNow = async function (reason, opts) {
    opts = opts || {};
    const s = await A.settings();
    if (s.backup.encrypt) {
      const pwd = await A.getBackupPassword();
      if (!pwd) return { ok: false, error: 'NO_PASSWORD' };
      var password = pwd;
    }
    const notes = await A.notes();
    const tombs = (await idb.get('meta', 'tombstones')) || [];
    const doc = B.makeDoc({
      notes: notes, tombstones: tombs, index: s.backupIndex, keep: s.backup.keep,
      device: 'web', appVersion: (document.getElementById('app-version') || {}).textContent || '1.0.0'
    });
    if (!doc) return { ok: true, skipped: true, message: '没有变化，跳过本次备份' };

    const encrypted = !!s.backup.encrypt;
    if (encrypted && !password) return { ok: false, error: '未设置备份密码' };
    let bytes;
    try {
      bytes = await B.docToBytes(doc, encrypted, password);
    } catch (e) {
      return { ok: false, error: '加密失败：' + (e.message || e) };
    }
    const name = B.filename(doc.createdAt, doc.type, encrypted);
    const b64 = NX.util.bytesToBase64(bytes);

    const rec = {
      id: 'local:' + name, source: 'local', name: name, ts: doc.createdAt,
      type: doc.type, size: bytes.length, encrypted: encrypted, data: b64
    };
    await idb.put('backups', rec);

    const all = await idb.getAll('backups');
    const drop = B.retention(all.map(x => ({ id: x.id, ts: x.ts })), s.backup.keep);
    for (const id of drop) await idb.del('backups', id);

    s.backupIndex.lastIncludedAt = doc.createdAt;
    if (doc.type === 'full') { s.backupIndex.lastFullAt = doc.createdAt; s.backupIndex.sinceFull = 0; }
    else s.backupIndex.sinceFull = (s.backupIndex.sinceFull || 0) + 1;
    s.lastBackupAt = doc.createdAt;
    s.lastBackupType = doc.type;
    await A.saveSettings(s);

    let davMsg = '';
    if (s.webdav.url) {
      const up = await A._davPut(name, bytes);
      if (!up.ok) davMsg = '（WebDAV 上传失败：' + up.error + '）';
      else davMsg = '（已上传 WebDAV）';
    }

    if (reason === 'manual' || opts.download) {
      downloadFile(name, bytes);
    }
    return {
      ok: true, type: doc.type, file: name, count: (doc.notes || []).length,
      removed: (doc.removed || []).length, size: bytes.length, encrypted: encrypted,
      message: (doc.type === 'full' ? '完整备份' : '增量备份') + '完成' + davMsg
    };
  };

  A.listBackups = async function () {
    const local = await idb.getAll('backups');
    const items = (local || []).map(x => ({
      id: x.id, source: 'local', name: x.name, ts: x.ts, type: x.type,
      size: x.size, encrypted: x.encrypted
    }));
    const s = await A.settings();
    if (s.webdav.url) {
      const remote = await A._davList();
      remote.forEach(r => items.push(r));
    }
    return items.sort((a, b) => b.ts - a.ts);
  };

  A.getBackup = async function (id) {
    if (String(id).indexOf('local:') === 0) {
      const rec = await idb.get('backups', id);
      if (!rec) return null;
      return { name: rec.name, base64: rec.data, ts: rec.ts, type: rec.type, encrypted: rec.encrypted, source: 'local' };
    }
    if (String(id).indexOf('dav:') === 0) {
      const s = await A.settings();
      const name = String(id).slice(4);
      const bytes = await A._davGet(name);
      if (!bytes) return null;
      return {
        name: name, base64: NX.util.bytesToBase64(bytes), ts: 0, type: /full/.test(name) ? 'full' : 'inc',
        encrypted: B.isEncrypted(bytes), source: 'webdav'
      };
    }
    return null;
  };

  A.deleteBackup = async function (id) {
    if (String(id).indexOf('local:') === 0) {
      const rec = await idb.get('backups', id);
      await idb.del('backups', id);
      if (rec) {
        const s = await A.settings();
        if (s.webdav.url) await A._davDelete(rec.name);
      }
      return true;
    }
    if (String(id).indexOf('dav:') === 0) {
      await A._davDelete(String(id).slice(4));
      return true;
    }
    return false;
  };

  A.pickFile = function () {
    return new Promise(resolve => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.hnb,.hnbe,.json,application/json,text/plain,*/*';
      input.style.display = 'none';
      document.body.appendChild(input);
      let done = false;
      const finish = v => { if (done) return; done = true; input.remove(); resolve(v); };
      input.addEventListener('change', () => {
        const f = input.files && input.files[0];
        if (!f) return finish(null);
        const fr = new FileReader();
        fr.onload = () => finish({ name: f.name, base64: String(fr.result).split(',')[1] || '', size: f.size });
        fr.onerror = () => finish(null);
        fr.readAsDataURL(f);
      });
      input.addEventListener('cancel', () => finish(null));
      input.click();
    });
  };

  function downloadFile(name, bytes) {
    const blob = new Blob([bytes], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 1500);
  }
  A.downloadBackupFile = async function (id) {
    const b = await A.getBackup(id);
    if (!b) return false;
    downloadFile(b.name, NX.util.base64ToBytes(b.base64));
    return true;
  };

  A.setSchedule = async function (s) { A._schedule(s); };

  A._schedule = function (s) {
    if (A._schedTimer) { clearInterval(A._schedTimer); A._schedTimer = null; }
    if (!s || !s.backup || !s.backup.scheduled) return;
    const ms = Math.max(60, Number(s.backup.interval) || 60) * 60000;
    A._schedTimer = setInterval(() => {
      A.backupNow('scheduled').catch(err => console.warn('scheduled backup failed', err));
    }, ms);
  };

  A.stats = async function () {
    const notes = await A.notes();
    const backups = await idb.getAll('backups');
    return {
      notes: notes.length,
      bytes: notes.reduce((a, n) => a + (n.content || '').length, 0),
      backups: (backups || []).length,
      location: '浏览器本地存储（IndexedDB）'
    };
  };

  /* ---------- WebDAV（直连失败时自动走本地 /api/dav 代理，绕过浏览器 CORS） ---------- */
  function davUrl(s, name) {
    let base = (s.webdav.url || '').trim();
    if (!base) return '';
    if (base.slice(-1) !== '/') base += '/';
    const p = (s.webdav.path || '').replace(/^\/+|\/+$/g, '');
    const parts = [base];
    if (p) parts.push(p.split('/').map(encodeURIComponent).join('/') + '/');
    if (name) parts.push(encodeURIComponent(name));
    return parts.join('');
  }

  function basicAuth(s) {
    if (!s.webdav.user) return '';
    let bin = '';
    const str = s.webdav.user + ':' + (s.webdav.pass || '');
    try { bin = btoa(unescape(encodeURIComponent(str))); } catch (e) { bin = btoa(str); }
    return 'Basic ' + bin;
  }

  function toUint8(base64) {
    const bin = atob(base64 || '');
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  /* 直连 → 失败则用同源代理；返回 {status, body(Uint8Array), headers} */
  async function davRequest(s, method, url, extraHeaders, body) {
    const headers = Object.assign({}, extraHeaders || {});
    if (s.webdav.user) headers['Authorization'] = basicAuth(s);
    try {
      const r = await fetch(url, { method: method, headers: headers, body: body });
      const buf = r.status === 204 ? new Uint8Array(0) : new Uint8Array(await r.arrayBuffer());
      return { status: r.status, body: buf, headers: {}, viaProxy: false };
    } catch (e) {
      const res = await fetch('./api/dav', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          method: method,
          url: url,
          headers: headers,
          body: body ? NX.util.bytesToBase64(body) : null
        })
      });
      if (!res.ok) throw new Error('CORS_NOT_SUPPORTED');
      const j = await res.json();
      if (j.error) throw new Error(j.error);
      return { status: j.status, body: toUint8(j.body || ''), headers: j.headers || {}, viaProxy: true };
    }
  }

  A._davPut = async function (name, bytes) {
    const s = await A.settings();
    if (!s.webdav.url) return { ok: false, error: '未配置 WebDAV' };
    try {
      const url = davUrl(s, name);
      let r = await davRequest(s, 'PUT', url, { 'Content-Type': 'application/octet-stream' }, bytes);
      if (r.status === 404 || r.status === 409) {
        await davRequest(s, 'MKCOL', davUrl(s, ''), {}, null).catch(() => { });
        r = await davRequest(s, 'PUT', url, { 'Content-Type': 'application/octet-stream' }, bytes);
      }
      if (r.status >= 200 && r.status < 300) return { ok: true };
      return { ok: false, error: 'HTTP ' + r.status };
    } catch (e) {
      return { ok: false, error: e.message === 'CORS_NOT_SUPPORTED'
        ? '浏览器跨域限制：请用 node serve.js 启动站点（自带 /api/dav 代理），或改用 APK 版备份'
        : (e.message || '网络错误') };
    }
  };

  A._davGet = async function (name) {
    const s = await A.settings();
    try {
      const r = await davRequest(s, 'GET', davUrl(s, name), {}, null);
      if (r.status >= 200 && r.status < 300) return r.body;
      return null;
    } catch (e) { return null; }
  };

  A._davDelete = async function (name) {
    const s = await A.settings();
    try {
      const r = await davRequest(s, 'DELETE', davUrl(s, name), {}, null);
      return r.status >= 200 && r.status < 300;
    } catch (e) { return false; }
  };

  A._davList = async function () {
    const s = await A.settings();
    if (!s.webdav.url) return [];
    try {
      const r = await davRequest(s, 'PROPFIND', davUrl(s, ''), {
        Depth: '1', 'Content-Type': 'application/xml'
      }, new TextEncoder().encode('<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>'));
      if (r.status >= 400) return [];
      const xml = new TextDecoder().decode(r.body);
      const out = [];
      const blocks = xml.split(/<(?:d|D|\w+):response[ >]/);
      const hrefPat = /<(?:d|D|\w+):href>([^<]+)</i;
      const lenPat = /<(?:d|D|\w+):getcontentlength>(\d+)</i;
      for (const b of blocks) {
        const hm = b.match(hrefPat);
        if (!hm) continue;
        const href = decodeURIComponent(hm[1]);
        const name = href.endsWith('/') ? '' : href.split('/').filter(Boolean).pop();
        if (!name || !/\.hnb(e)?$/.test(name)) continue;
        const lm = b.match(lenPat);
        out.push({
          id: 'dav:' + name, source: 'webdav', name: name, ts: 0,
          type: /full/.test(name) ? 'full' : 'inc', size: lm ? Number(lm[1]) : 0,
          encrypted: /\.hnbe$/.test(name)
        });
      }
      return out;
    } catch (e) { return []; }
  };

  A.testWebdav = async function () {
    const s = await A.settings();
    if (!s.webdav.url) return { ok: false, message: '请先填写服务器地址' };
    try {
      const r = await davRequest(s, 'PROPFIND', davUrl(s, ''), {
        Depth: '0', 'Content-Type': 'application/xml'
      }, new TextEncoder().encode('<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>'));
      if (r.status === 401 || r.status === 403) return { ok: false, message: '认证失败（HTTP ' + r.status + '），请检查账号或应用密码' };
      if (r.status === 404) return { ok: false, message: '目录不存在（HTTP 404），请检查地址与备份目录' };
      if (r.status >= 200 && r.status < 300 || r.status === 207) {
        return { ok: true, message: '连接成功（HTTP ' + r.status + (r.viaProxy ? '，经本地代理' : '') + '）' };
      }
      return { ok: false, message: '服务器返回 HTTP ' + r.status };
    } catch (e) {
      if (e.message === 'CORS_NOT_SUPPORTED') {
        return { ok: false, message: '浏览器跨域限制：请用 node serve.js 启动本站（自带 /api/dav 代理），或改用 APK 版' };
      }
      return { ok: false, message: '无法连接：' + (e.message || '网络错误') };
    }
  };

  NX.backendWeb = A;
  if (!NX.backend) NX.backend = A;
})();
