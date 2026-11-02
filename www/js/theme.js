/* 主题：浅色 / 深色 / 跟随系统 */
window.NX = window.NX || {};
(function () {
  const T = { mode: 'light', _mq: null, _listeners: [] };

  T.resolved = function () {
    if (T.mode === 'system') {
      const dark = T._mq ? T._mq.matches : window.matchMedia('(prefers-color-scheme: dark)').matches;
      return dark ? 'dark' : 'light';
    }
    return T.mode === 'dark' ? 'dark' : 'light';
  };

  T.apply = function () {
    const theme = T.resolved();
    document.documentElement.setAttribute('data-theme', theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#101216' : '#ffffff');
    if (NX.native && NX.native.setSystemTheme) {
      try { NX.native.setSystemTheme(theme === 'dark'); } catch (e) { /* ignore */ }
    }
    T._listeners.forEach(fn => { try { fn(theme); } catch (e) { console.error(e); } });
  };

  T.set = function (mode) {
    T.mode = (mode === 'dark' || mode === 'system') ? mode : 'light';
    T.apply();
  };

  T.init = function (mode) {
    T._mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    if (T._mq) {
      const onChange = () => { if (T.mode === 'system') T.apply(); };
      if (T._mq.addEventListener) T._mq.addEventListener('change', onChange);
      else if (T._mq.addListener) T._mq.addListener(onChange);
    }
    T.set(mode || 'light');
  };

  T.onChange = function (fn) { T._listeners.push(fn); };

  NX.theme = T;
})();
