(function () {
  'use strict';
  try {
    if (window.top !== window || window.__novaGpuSet) return;
    var h = location.hostname || '';
    var heavy = /(^|\.)(youtube\.com|youtu\.be|google\.[a-z.]+|openstreetmap\.org|waze\.com|windy\.com)$/.test(h);   // maps/players: keep their own rendering
    var lvl = window.__novaGpuInit | 0, cap = false, lastRun = 0, frameT = -1;
    var AIHOST = /(^|\.)(gemini\.google\.com|chatgpt\.com|chat\.openai\.com|claude\.ai|perplexity\.ai|copilot\.microsoft\.com|chat\.deepseek\.com|grok\.com|poe\.com|chat\.mistral\.ai)$/;
    function isAi() {
      if (AIHOST.test(h)) return true;
      return /(^|\.)google\.[a-z.]+$/.test(h) && (/[?&]udm=50(&|$)/.test(location.search) || /^\/ai(\/|$)/.test(location.pathname));
    }
    var raf = window.requestAnimationFrame, caf = window.cancelAnimationFrame, live = {};

    // Frame cap (about 30 fps) for page animation loops: roughly halves GPU/CPU work of canvas and JS animations.
    if (raf && caf && !heavy) {
      window.requestAnimationFrame = function (cb) {
        var f = function (t) {
          if (f.dead) return;
          if (cap && t !== frameT) {
            if (t - lastRun < 28) { f.id = raf.call(window, f); live[f.first] = f; return; }
            lastRun = t; frameT = t;
          }
          delete live[f.first];
          cb(t);
        };
        f.id = f.first = raf.call(window, f);
        live[f.first] = f;
        return f.first;
      };
      window.cancelAnimationFrame = function (id) {
        var f = live[id];
        if (f) { f.dead = true; delete live[id]; caf.call(window, f.id); } else caf.call(window, id);
      };
    }

    var CSS1 = '*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important;will-change:auto!important}html{scroll-behavior:auto!important}';
    var CSS2 = '*,*::before,*::after{animation:none!important;transition:none!important;filter:none!important;text-shadow:none!important;box-shadow:none!important}';
    var CSS_AI = '*,*::before,*::after{animation-duration:1ms!important;animation-delay:0s!important;transition-duration:1ms!important;transition-delay:0s!important;scroll-behavior:auto!important}html,body{text-rendering:optimizeSpeed!important}';
    var CSS_AI2 = '*,*::before,*::after{text-shadow:none!important;box-shadow:none!important}';
    var st = null;
    function css() {
      var t = lvl >= 2 ? CSS1 + CSS2 : lvl >= 1 ? CSS1 : '';
      if (lvl >= 1 && isAi()) t = CSS1 + CSS_AI + (lvl >= 2 ? CSS_AI2 : '');
      if (!t) { if (st) { st.remove(); st = null; } return; }
      if (!st) { st = document.createElement('style'); st.id = '__nova_gpu'; }
      if (st.textContent !== t) st.textContent = t;
      var p = document.head || document.documentElement;
      if (p && st.parentNode !== p) p.appendChild(st);
    }

    // Autoplaying muted loops (hero/background videos) are paused while off-screen.
    var io = null, paused = new WeakSet();
    function watchVideos() {
      if (heavy || lvl < 1 || !window.IntersectionObserver) return;
      if (!io) io = new IntersectionObserver(function (es) {
        for (var i = 0; i < es.length; i++) {
          var v = es[i].target;
          if (!es[i].isIntersecting) { if (!v.paused && v.muted) { paused.add(v); try { v.pause(); } catch (x) {} } }
          else if (paused.has(v)) { paused.delete(v); try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (x) {} }
        }
      }, { threshold: 0.05 });
      var l = document.querySelectorAll('video[autoplay],video[loop]');
      for (var i = 0; i < l.length; i++) io.observe(l[i]);
    }

    window.__novaGpuSet = function (n) {
      lvl = n | 0; cap = lvl >= 2 && !heavy;
      if (document.documentElement) css();
      watchVideos();
    };
    window.__novaGpuSet(lvl);
    document.addEventListener('DOMContentLoaded', function () { css(); watchVideos(); setTimeout(watchVideos, 2500); }, { once: true });
  } catch (e) {}
})();
