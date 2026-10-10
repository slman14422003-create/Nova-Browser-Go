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

  function handle(a) {
    var ms, i, m;
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
