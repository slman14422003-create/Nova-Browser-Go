(function () {
  if (window.__novaPw || window.top !== window || !window.NovaPw) return;
  window.__novaPw = 1;
  var last = '', lastT = 0, announced = false, tm = null;

  function vis(e) {
    try {
      var r = e.getBoundingClientRect(), cs = getComputedStyle(e);
      return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
    } catch (x) { return false; }
  }
  function pwFields() {
    return Array.prototype.filter.call(document.querySelectorAll('input[type=password]'), vis);
  }
  // حقول اسم المستخدم المعلنة صراحةً (دخول بخطوتين: البريد أولاً ثم كلمة المرور)
  function nameFields() {
    return Array.prototype.filter.call(document.querySelectorAll('input[autocomplete~=username]'), vis);
  }
  function userField(pw) {
    var scope = pw.form || document;
    var c = Array.prototype.slice.call(
      scope.querySelectorAll('input:not([type]),input[type=text],input[type=email],input[type=tel],input[type=url]')
    ).filter(vis);
    var best = null;
    for (var i = 0; i < c.length; i++) {
      if (c[i].compareDocumentPosition(pw) & 4) best = c[i];
    }
    return best;
  }
  function send(t, o) {
    try { o.t = t; window.NovaPw.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  function remember(e) {
    var el = e.target;
    if (el && el.tagName === 'INPUT' && el.type !== 'password' && el.value) {
      try { sessionStorage.setItem('__novaU', el.value); } catch (x) {}
    }
  }
  function capture() {
    var f = pwFields();
    for (var i = 0; i < f.length; i++) {
      var p = f[i];
      if (!p.value) continue;
      var u = userField(p), uv = u ? u.value : '';
      if (!uv) { try { uv = sessionStorage.getItem('__novaU') || ''; } catch (x) {} }
      var key = uv + '\u0000' + p.value, now = Date.now();
      if (key === last && now - lastT < 4000) return;
      last = key; lastT = now;
      send('save', { u: uv, p: p.value });
      return;
    }
  }
  var skip = /(show|hide|reveal|eye|visib|toggle)/i;
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest && e.target.closest('button,input[type=submit],input[type=button],[role=button],a');
    if (!b) return;
    var meta = (b.getAttribute('aria-label') || '') + ' ' + (b.className || '') + ' ' + (b.id || '') + ' ' + (b.title || '');
    if (skip.test(meta)) return;
    setTimeout(capture, 0);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target && e.target.type === 'password') capture();
  }, true);
  document.addEventListener('input', remember, true);
  document.addEventListener('change', remember, true);

  function announce() {
    var f = pwFields();
    if (!f.length) f = nameFields();
    if (f.length && !announced) { announced = true; send('form', {}); }
    else if (!f.length) announced = false;
  }
  try {
    new MutationObserver(function () { clearTimeout(tm); tm = setTimeout(announce, 300); })
      .observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
  document.addEventListener('DOMContentLoaded', announce);
  setTimeout(announce, 800);

  // تعبئة (تُستدعى من التطبيق بعد التحقق من الهوية فقط)
  window.__novaFill = function (u, p) {
    function set(el, v) {
      var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      d.set.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }
    var f = pwFields(), pw = f[0] || null, us = null;
    if (pw) us = userField(pw); else us = nameFields()[0] || null;
    if (!pw && !us) return false;
    if (us && u) set(us, u);
    if (pw && p) set(pw, p);   // كلمة مرور فارغة = تعبئة البريد فقط
    return true;
  };
})();
