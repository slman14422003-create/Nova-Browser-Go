/* media.js — تتبّع وتحكّم بفيديو/صوت أي صفحة (خارج يوتيوب): حالة التشغيل للنافذة المنبثقة، وتشغيل واحد في كل مرة.
   يُحقن قبل سكربتات الصفحة في كل الإطارات؛ يتصل بالتطبيق عبر الكائن NovaMedia. */
(function () {
  'use strict';
  if (window.__novaMediaInit) return;
  try { Object.defineProperty(window, '__novaMediaInit', { value: true }); } catch (e) { window.__novaMediaInit = true; }

  var bridge = window.NovaMedia;
  var isTop = window.top === window;
  var cur = null;

  function post(o) { try { if (bridge) bridge.postMessage(JSON.stringify(o)); } catch (e) {} }
  function all() { return document.querySelectorAll('video,audio'); }
  function area(m) { try { var r = m.getBoundingClientRect(); return r.width * r.height; } catch (e) { return 0; } }

  function best() {
    var ms = all(), b = null, i, m;
    for (i = 0; i < ms.length; i++) { m = ms[i]; if (!m.paused && !m.ended && (!b || area(m) > area(b))) b = m; }
    return b || (ms.length ? ms[0] : null);
  }

  function state(m, playing) {
    var dur = 0;
    try { dur = isFinite(m.duration) ? Math.round(m.duration * 1000) : 0; } catch (e) {}
    return {
      t: 'state', playing: !!playing, video: m.tagName === 'VIDEO',
      audible: !m.muted && m.volume > 0, title: (document.title || '').slice(0, 200),
      pos: Math.round((m.currentTime || 0) * 1000), dur: dur
    };
  }

  function onEv(e) {
    var m = e.target;
    if (!m || (m.tagName !== 'VIDEO' && m.tagName !== 'AUDIO')) return;
    var aud = !m.muted && m.volume > 0, playing;
    if (e.type === 'play' || e.type === 'playing') playing = true;
    else if (e.type === 'volumechange') playing = !m.paused && !m.ended;
    else playing = false;
    if (playing) {
      // فيديو صامت (خلفيات/معاينات متحركة) لا يُعدّ تشغيلاً: لا نافذة منبثقة ولا إيقاف لغيره
      if (!aud) { if (cur === m) { cur = null; post(state(m, false)); } return; }
      cur = m;
    } else if (cur !== m) return;
    post(state(m, playing));
  }
  ['play', 'playing', 'pause', 'ended', 'volumechange'].forEach(function (n) { document.addEventListener(n, onEv, true); });

  // مغادرة الصفحة: نُعلم التطبيق أن لا وسائط بعد الآن
  window.addEventListener('pagehide', function () {
    if (cur) post({ t: 'state', playing: false, video: false, gone: true });
  });

  var PIP_CSS = '__nova_pip_css', pipSaved = [];

  function pipClear() {
    var o = document.getElementById(PIP_CSS); if (o) o.remove();
    document.querySelectorAll('.__nova_v,.__nova_f').forEach(function (x) { x.classList.remove('__nova_v'); x.classList.remove('__nova_f'); });
    pipSaved.forEach(function (p) { p[0].style.cssText = p[1]; });
    pipSaved = [];
  }

  function pipLift(el) {
    for (var p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) {
      pipSaved.push([p, p.style.cssText]);
      ['transform', 'filter', 'contain', 'perspective'].forEach(function (k) { p.style.setProperty(k, 'none', 'important'); });
      p.style.setProperty('will-change', 'auto', 'important');
      p.style.setProperty('overflow', 'visible', 'important');
    }
  }

  function pipStyle(sel) {
    var st = document.createElement('style'); st.id = PIP_CSS;
    st.textContent = 'html,body{background:#000!important;overflow:hidden!important}body *{visibility:hidden!important}' +
      sel + '{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;' +
      'max-width:none!important;max-height:none!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important;margin:0!important;border:0!important}';
    (document.head || document.documentElement).appendChild(st);
  }

  function pipOn() {
    pipClear();
    var ms = all(), m = null, i;
    for (i = 0; i < ms.length; i++) { if (ms[i].tagName === 'VIDEO' && !ms[i].paused && !ms[i].ended && (!m || area(ms[i]) > area(m))) m = ms[i]; }
    if (m) {
      pipStyle('video.__nova_v');
      m.classList.add('__nova_v');
      pipLift(m);
      if (!isTop) { try { window.parent.postMessage({ __novaPipFrame: 1 }, '*'); } catch (e) {} }
      return true;
    }
    return false;
  }

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || !d.__novaPipFrame) return;
    var f = document.getElementsByTagName('iframe'), i, hit = null;
    for (i = 0; i < f.length; i++) { try { if (f[i].contentWindow === e.source) { hit = f[i]; break; } } catch (x) {} }
    if (!hit) return;
    pipClear();
    pipStyle('iframe.__nova_f');
    hit.classList.add('__nova_f');
    pipLift(hit);
    if (!isTop) { try { window.parent.postMessage({ __novaPipFrame: 1 }, '*'); } catch (x) {} }
  });

  function handle(a) {
    var ms, i, m;
    if (a === 'pip:0') { pipClear(); return; }
    if (a === 'pip:1') { pipOn(); return; }
    if (a === 'pauseall' || a === 'pause') {
      ms = all();
      for (i = 0; i < ms.length; i++) { try { if (a === 'pauseall' || !ms[i].paused) ms[i].pause(); } catch (e) {} }
      return;
    }
    m = (cur && cur.isConnected) ? cur : (isTop ? best() : null);
    if (!m) return;
    try {
      if (a === 'play') { var p = m.play(); if (p && p.catch) p.catch(function () {}); }
      else if (a === 'back') m.currentTime = Math.max(0, m.currentTime - 10);
      else if (a === 'fwd') m.currentTime = isFinite(m.duration) ? Math.min(m.duration, m.currentTime + 10) : m.currentTime + 10;
      else if (a.indexOf('seek:') === 0) m.currentTime = (parseInt(a.slice(5), 10) || 0) / 1000;
    } catch (e) {}
  }

  // الإطارات الفرعية (مشغّلات مضمّنة): نمرّر الأمر لها ثم تمرّره لما تحتها
  function broadcast(a) {
    try {
      var f = document.getElementsByTagName('iframe'), i;
      for (i = 0; i < f.length; i++) { try { f[i].contentWindow.postMessage({ __novaMedia: a }, '*'); } catch (e) {} }
    } catch (e) {}
  }
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (d && typeof d === 'object' && typeof d.__novaMedia === 'string' && e.source === window.parent) {
      handle(d.__novaMedia); broadcast(d.__novaMedia);
    }
  });

  if (isTop) {
    try {
      Object.defineProperty(window, '__novaMediaCtl', {
        value: function (a) { a = String(a); handle(a); broadcast(a); }, configurable: true
      });
    } catch (e) { window.__novaMediaCtl = function (a) { a = String(a); handle(a); broadcast(a); }; }
  }
})();
