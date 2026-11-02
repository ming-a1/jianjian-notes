/* 备份格式、加密、增量差异与留存规则（Web 与 Android 共用同一格式）
 *
 * 未加密文件：UTF-8 JSON
 * 加密文件：magic "HNB1" | ver u8 | kdf u8(1=PBKDF2-SHA256) | iter u32 BE |
 *           saltLen u8(16) | salt | ivLen u8(12) | iv | AES-256-GCM(ciphertext+tag)
 * AAD = 前 40 字节头部
 */
window.NX = window.NX || {};
(function () {
  const B = {};

  B.FORMAT = 'heyunotes.backup';
  B.VERSION = 1;
  B.MAGIC = 'HNB1';
  B.ITERATIONS = 200000;
  B.SALT_LEN = 16;
  B.IV_LEN = 12;
  B.HEADER_LEN = 40;
  B.DEFAULT_KEEP = 7;

  B.utf8 = s => new TextEncoder().encode(s);
  B.fromUtf8 = b => new TextDecoder().decode(b);

  B.filename = function (ts, type, encrypted) {
    const d = new Date(ts);
    const p = n => (n < 10 ? '0' : '') + n;
    const stamp = d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
    const tag = type === 'full' ? 'full' : 'inc';
    return 'notes-' + stamp + '-' + tag + (encrypted ? '.hnbe' : '.hnb');
  };

  B.isEncrypted = function (bytes) {
    if (!bytes || bytes.length < 5) return false;
    return bytes[0] === 0x48 && bytes[1] === 0x4e && bytes[2] === 0x42 && bytes[3] === 0x31;
  };

  function buildHeader(iter, salt, iv) {
    const h = new Uint8Array(B.HEADER_LEN);
    h[0] = 0x48; h[1] = 0x4e; h[2] = 0x42; h[3] = 0x31;
    h[4] = 1; h[5] = 1;
    new DataView(h.buffer).setUint32(6, iter, false);
    h[10] = B.SALT_LEN;
    h.set(salt, 11);
    h[27] = B.IV_LEN;
    h.set(iv, 28);
    return h;
  }

  async function deriveKey(password, salt, iter) {
    const base = await crypto.subtle.importKey('raw', B.utf8(password), 'PBKDF2', false, ['deriveKey']);
    return crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt: salt, iterations: iter, hash: 'SHA-256' },
      base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']
    );
  }

  /* 加密：返回 Uint8Array */
  B.encrypt = async function (plainBytes, password) {
    if (!window.crypto || !crypto.subtle) throw new Error('当前环境不支持 WebCrypto 加密');
    const iter = B.ITERATIONS;
    const salt = crypto.getRandomValues(new Uint8Array(B.SALT_LEN));
    const iv = crypto.getRandomValues(new Uint8Array(B.IV_LEN));
    const header = buildHeader(iter, salt, iv);
    const key = await deriveKey(password, salt, iter);
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv, additionalData: header, tagLength: 128 }, key, plainBytes));
    const out = new Uint8Array(header.length + ct.length);
    out.set(header, 0);
    out.set(ct, header.length);
    return out;
  };

  /* 解密：失败（密码错误/文件损坏）抛错 */
  B.decrypt = async function (bytes, password) {
    if (!B.isEncrypted(bytes)) throw new Error('不是加密的备份文件');
    if (!window.crypto || !crypto.subtle) throw new Error('当前环境不支持 WebCrypto 解密');
    const h = bytes.subarray(0, B.HEADER_LEN);
    const iter = new DataView(bytes.buffer, bytes.byteOffset + 6, 4).getUint32(0, false);
    const saltLen = bytes[10], salt = bytes.subarray(11, 11 + saltLen);
    const ivLen = bytes[27], iv = bytes.subarray(28, 28 + ivLen);
    const ct = bytes.subarray(B.HEADER_LEN);
    const key = await deriveKey(password, new Uint8Array(salt), iter);
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: new Uint8Array(iv), additionalData: new Uint8Array(h), tagLength: 128 }, key, ct);
    return new Uint8Array(pt);
  };

  B.encryptText = async (text, password) => B.encrypt(B.utf8(text), password);
  B.decryptText = async (bytes, password) => B.fromUtf8(await B.decrypt(bytes, password));

  /* ---------- 差异 / 增量 ---------- */
  B.makeDoc = function (opts) {
    const now = opts.now || Date.now();
    const notes = opts.notes || [];
    const index = opts.index || null;
    const tombstones = opts.tombstones || [];
    const lastAt = index && index.lastIncludedAt ? index.lastIncludedAt : 0;
    const hasBase = !!(index && index.lastFullAt);
    const keep = Math.max(1, Number(opts.keep) || 7);
    const sinceFull = index && index.sinceFull ? index.sinceFull : 0;
    /* 首个备份、每 keep-1 次增量后、或距上次全量超过 7 天时做完整备份，保证留存窗口内可完整重建 */
    const needFull = !hasBase || sinceFull >= Math.max(1, keep - 1) || (now - index.lastFullAt) > 7 * 86400000;
    const changed = needFull ? notes.slice() : notes.filter(n => (n.updatedAt || 0) > lastAt);
    const removed = tombstones
      .filter(t => t && t.at && now - t.at < 30 * 86400000)
      .map(t => ({ id: t.id, at: t.at }));
    if (!changed.length && !removed.length && index) return null;
    const doc = {
      format: B.FORMAT,
      version: B.VERSION,
      type: needFull ? 'full' : 'incremental',
      createdAt: now,
      device: opts.device || 'web',
      appVersion: opts.appVersion || '1.0.0',
      notes: changed,
      removed: removed,
      counts: { notes: changed.length, removed: removed.length, total: notes.length }
    };
    return doc;
  };

  B.docToBytes = function (doc, encrypted, password) {
    const text = JSON.stringify(doc);
    if (!encrypted) return Promise.resolve(B.utf8(text));
    return B.encryptText(text, password);
  };

  /* ---------- 恢复：把备份内容合并进当前笔记 ---------- */
  B.validate = function (doc) {
    if (!doc || typeof doc !== 'object') return '不是有效的备份文件';
    if (doc.format !== B.FORMAT) return '不是「简笺」的备份文件';
    if (!Array.isArray(doc.notes)) return '备份内容已损坏（缺少 notes）';
    if (doc.version > B.VERSION) return '备份版本较新，请升级应用后再导入';
    return null;
  };

  /* 合并策略：同 id 取 updatedAt 较新者；removed 中的 id 若本地有更新的版本则保留 */
  B.mergeInto = function (localNotes, doc) {
    const byId = new Map();
    localNotes.forEach(n => byId.set(n.id, n));
    let added = 0, updated = 0, unchanged = 0;
    (doc.notes || []).forEach(n => {
      if (!n || !n.id) return;
      const cur = byId.get(n.id);
      if (!cur) { byId.set(n.id, n); added++; }
      else if ((n.updatedAt || 0) > (cur.updatedAt || 0)) { byId.set(n.id, n); updated++; }
      else unchanged++;
    });
    let removedCount = 0;
    (doc.removed || []).forEach(r => {
      if (!r || !r.id) return;
      const cur = byId.get(r.id);
      if (cur && (cur.updatedAt || 0) <= (r.at || 0)) { byId.delete(r.id); removedCount++; }
    });
    return { notes: Array.from(byId.values()), added: added, updated: updated, unchanged: unchanged, removed: removedCount };
  };

  B.apply = async function (doc, opts) {
    opts = opts || {};
    const local = await NX.backend.notes();
    if (opts.replaceAll) {
      const incoming = new Map((doc.notes || []).map(n => [n.id, n]));
      for (const n of local) if (!incoming.has(n.id) && (doc.type === 'full')) await NX.backend.deleteNote(n.id, { silent: true });
      for (const n of (doc.notes || [])) await NX.backend.saveNote(n, { silent: true });
      return { added: (doc.notes || []).length, updated: 0, removed: local.length - (doc.notes || []).length, total: (doc.notes || []).length };
    }
    const res = B.mergeInto(local, doc);
    for (const n of res.notes) await NX.backend.saveNote(n, { silent: true });
    const present = new Set(local.map(n => n.id));
    for (const r of (doc.removed || [])) if (present.has(r.id)) await NX.backend.deleteNote(r.id, { silent: true });
    res.total = res.notes.length;
    return res;
  };

  /* 留存：返回需要删除的 id（按时间从旧到新保留最新的 keep 份） */
  B.retention = function (items, keep) {
    if (!keep || keep < 1) keep = B.DEFAULT_KEEP;
    const sorted = items.slice().sort((a, b) => (b.ts || 0) - (a.ts || 0));
    return sorted.slice(keep).map(it => it.id);
  };

  NX.backup = B;
})();
