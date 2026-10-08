/* Nova Shield (shield.js) — حماية الصفحة الفورية. تُحقن قبل أي سكربت للصفحة وفي كل الإطارات.
 *  1) WebSocket: منع صفحة عامة من الاتصال بالجهاز نفسه أو الشبكة المحلية (الجانب الأصلي لا يرى WebSocket).
 *  2) إزالة سمة ping من الروابط (تتبع النقر) قبل أن يقع النقر.
 *  3) تنظيف معرّفات التتبع من الروابط عند اللمس ومن عنوان الصفحة الحالية.
 * كل جزء معزول بـ try/catch: أي فشل يعني أن الحماية لا تعمل في ذلك الجزء فقط، ولا تتأثر الصفحة أبداً.
 */
(function () {
  'use strict';
  if (window.__novaShield) return;
  try { Object.defineProperty(window, '__novaShield', { value: 1 }); } catch (e) {}
  function safe(f) { try { f(); } catch (e) {} }

  // ───────── عناوين خاصة (نفس منطق Shield.kt) ─────────
  function v4(h) {
    var p = h.split('.');
    if (!p.length || p.length > 4) return null;
    var n = [];
    for (var i = 0; i < p.length; i++) {
      var s = p[i], x;
      if (!s) return null;
      if (/^0x[0-9a-f]+$/i.test(s)) x = parseInt(s.slice(2), 16);
      else if (/^0[0-7]+$/.test(s)) x = parseInt(s, 8);
      else if (/^[0-9]+$/.test(s)) x = parseInt(s, 10);
      else return null;
      if (!isFinite(x) || x < 0) return null;
      n.push(x);
    }
    var last = n[n.length - 1];
    for (var j = 0; j < n.length - 1; j++) if (n[j] > 255) return null;
    var lim = [0, 4294967295, 16777215, 65535, 255][n.length];
    if (last > lim) return null;
    var v = last;
    for (var k = 0; k < n.length - 1; k++) v += n[k] * Math.pow(2, 24 - 8 * k);
    return v;
  }
  function priv4(v) {
    var a = Math.floor(v / 16777216) & 255, b = Math.floor(v / 65536) & 255;
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  function priv(host) {
    var h = String(host || '').replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase();
    if (!h) return false;
    if (h === 'localhost' || /\.(localhost|local|internal|lan)$/.test(h) || /\.home\.arpa$/.test(h)) return true;
    if (h.indexOf(':') >= 0) {
      if (h === '::1' || h === '::' || /^fe80:/.test(h) || /^f[cd]/.test(h)) return true;
      var m = /^::ffff:(.+)$/.exec(h);
      if (m) { var q = v4(m[1]); return q !== null && priv4(q); }
      return false;
    }
    var r = v4(h);
    return r !== null && priv4(r);
  }
  var pagePrivate = !location.hostname || priv(location.hostname);

  // ───────── 1) WebSocket إلى الشبكة المحلية ─────────
  safe(function () {
    var W = window.WebSocket;
    if (!W || pagePrivate) return;
    window.WebSocket = new Proxy(W, {
      construct: function (target, args, newTarget) {
        var bad = false;
        try { bad = priv(new URL(args[0], location.href).hostname); } catch (e) {}
        if (bad) throw new DOMException('Blocked by Nova Shield', 'SecurityError');
        return Reflect.construct(target, args, newTarget);
      }
    });
  });

  // ───────── 2) سمة ping ─────────
  function stripPing(e) {
    safe(function () {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[ping],area[ping]') : null;
      if (a) a.removeAttribute('ping');
    });
  }
  safe(function () {
    document.addEventListener('click', stripPing, true);
    document.addEventListener('auxclick', stripPing, true);
    document.addEventListener('touchstart', stripPing, { capture: true, passive: true });
  });

  // ───────── 3) معرّفات التتبع ─────────
  var TP = /^(utm_[a-z0-9_]+|fbclid|gclid|dclid|gbraid|wbraid|msclkid|yclid|mc_eid|mc_cid|igshid|_ga|_gl|ref_src|ref_url|vero_id|oly_enc_id|oly_anon_id)$/i;
  function cleaned(href) {
    try {
      var u = new URL(href, location.href);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
      var keys = [], changed = false;
      u.searchParams.forEach(function (_v, k) { keys.push(k); });
      keys.forEach(function (k) { if (TP.test(k)) { u.searchParams.delete(k); changed = true; } });
      return changed ? u.href : null;
    } catch (e) { return null; }
  }
  function cleanLink(e) {
    safe(function () {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (!a) return;
      var c = cleaned(a.href);
      if (c) a.href = c;
    });
  }
  safe(function () {
    document.addEventListener('click', cleanLink, true);
    document.addEventListener('touchstart', cleanLink, { capture: true, passive: true });
  });
  // عنوان الصفحة الحالية (قبل أن تقرأه سكربتات التحليلات)
  safe(function () {
    if (window.top !== window) return;
    var c = cleaned(location.href);
    if (c && c.split('#')[0] !== location.href.split('#')[0] && history && history.replaceState) history.replaceState(history.state, '', c);
  });
})();
