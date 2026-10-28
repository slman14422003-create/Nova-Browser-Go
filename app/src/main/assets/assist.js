(function () {
  'use strict';
  try {
    if (window.__novaAssist || window.top !== window) return;
    window.__novaAssist = 1;
    var LOW = !!window.__novaLow;
    var root = document.documentElement;

    // Passive scroll/touch listeners by default: smoother scrolling and less main-thread blocking.
    try {
      var ael = EventTarget.prototype.addEventListener, hot = { touchstart: 1, touchmove: 1, wheel: 1, mousewheel: 1 };
      EventTarget.prototype.addEventListener = function (t, f, o) {
        if (hot[t] && (this === window || this === document || this === document.body || this === root)) {
          if (o === undefined || o === false || o === true) o = { capture: o === true, passive: true };
          else if (typeof o === 'object' && o && o.passive === undefined) o = { capture: !!o.capture, once: !!o.once, passive: true, signal: o.signal };
        }
        return ael.call(this, t, f, o);
      };
    } catch (e) {}

    // Pause CSS animations while the page is hidden.
    function hiddenCss() {
      var id = '__nova_pause', st = document.getElementById(id);
      if (document.hidden) {
        if (!st && document.head) { st = document.createElement('style'); st.id = id; st.textContent = '*,*::before,*::after{animation-play-state:paused!important}'; document.head.appendChild(st); }
      } else if (st) st.remove();
    }
    document.addEventListener('visibilitychange', hiddenCss, { passive: true });

    if (!LOW) return;

    // Weak devices: skip rendering work for off-screen blocks and load frames/media lazily.
    var css = 'article,section,aside,footer,[role=article],[role=listitem],li{content-visibility:auto;contain-intrinsic-size:auto 400px}' +
      'img{content-visibility:auto}video{will-change:auto}';
    function addCss() {
      if (document.getElementById('__nova_low') || !document.head) return;
      var s = document.createElement('style'); s.id = '__nova_low'; s.textContent = css; document.head.appendChild(s);
    }
    var pend = [], sched = 0;
    function flush() {
      sched = 0;
      var n = pend.splice(0, 40);
      for (var i = 0; i < n.length; i++) {
        var e = n[i];
        try {
          if (e.tagName === 'IFRAME' && !e.hasAttribute('loading')) e.setAttribute('loading', 'lazy');
          else if (e.tagName === 'IMG') { if (!e.hasAttribute('decoding')) e.decoding = 'async'; }
          else if (e.tagName === 'VIDEO' && !e.hasAttribute('preload')) e.preload = 'metadata';
        } catch (x) {}
      }
      if (pend.length) later();
    }
    function later() {
      if (sched) return; sched = 1;
      if (window.requestIdleCallback) requestIdleCallback(flush, { timeout: 800 }); else setTimeout(flush, 120);
    }
    function scan(n) {
      if (!n || n.nodeType !== 1) return;
      var t = n.tagName;
      if (t === 'IFRAME' || t === 'IMG' || t === 'VIDEO') pend.push(n);
      else if (n.querySelectorAll && pend.length < 400) { var l = n.querySelectorAll('iframe,img,video'); for (var i = 0; i < l.length && pend.length < 400; i++) pend.push(l[i]); }
    }
    var mo = new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) for (var j = 0; j < ms[i].addedNodes.length; j++) scan(ms[i].addedNodes[j]);
      if (pend.length) later();
    });
    function start() {
      addCss();
      scan(document.body);
      later();
      try { mo.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true }); else start();
    // Stop observing once the page settles: no permanent per-mutation cost.
    window.addEventListener('load', function () { setTimeout(function () { try { mo.disconnect(); } catch (e) {} }, 6000); }, { once: true });
  } catch (e) {}
})();
