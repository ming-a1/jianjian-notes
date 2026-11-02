/* 简笺 PWA：离线缓存应用外壳（数据在 IndexedDB，不会进缓存） */
const CACHE = 'jianjian-v1';
const ASSETS = [
  './', './index.html', './css/app.css',
  './js/util.js', './js/theme.js', './js/markdown.js', './js/backup.js',
  './js/backend-web.js', './js/backend-android.js', './js/app.js',
  './vendor/markdown-it.min.js', './vendor/markdown-it-task-lists.min.js',
  './vendor/markdown-it-footnote.min.js', './vendor/highlight.min.js',
  './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  e.respondWith(
    caches.match(req, { ignoreSearch: true }).then(hit => hit || fetch(req).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match('./index.html')))
  );
});
