/* Nova YouTube bundle (yt.js) — سكربت واحد لصفحات يوتيوب فقط، يُحقن مرة واحدة مبكراً في الـ WebView المخصّص ليوتيوب (YtWeb.kt / YtHub.kt).
 * لا واجهة أصلية ولا جسر بيانات: يوتيوب يعرض نفسه كما هو، والسكربت يضيف فقط:
 *   1) FIX   : شكل/موضع الترجمة (من إعدادات التطبيق)، لافتات «افتح التطبيق»، قياس الفيديو بعد الدوران، العودة للأعلى
 *   2) PLAY  : استئناف الفيديو + تذكّر السرعة + ضغط مطوّل ×2
 *   3) MEDIA : حالة التشغيل لإشعار الوسائط + حماية التشغيل في الخلفية/النافذة المنبثقة + أوامر الإشعار
 * كل قسم في IIFE مستقل بحارس خاص فخطأ في قسم لا يوقف البقية. لا يلمس الإعلانات ولا يستخرج روابط البث.
 * العلامات التي تستبدلها YtHub.kt: __CC__ و __UI__ و __PLAY__ و __BG__.
 */

/* ═════════ 1) FIX ═════════ */
try {
(function () {
  'use strict';
  try {
    if (window.__novaYtFix || window.top !== window || location.hostname === 'music.youtube.com') return;   // لا نعدّل YouTube Music
    window.__novaYtFix = 1;
    var CC = __CC__, UI = __UI__;
    function root() { return document.documentElement; }

    var CSS =
      // مقاسات الترجمة (تُضرب في الحجم المتجاوب)
      ':root{--nova-cc-k:1;--nova-cc-bg:rgba(14,14,18,.72);--nova-cc-sh:none}' +
      'html[data-nova-ccsize="s"]{--nova-cc-k:.82}html[data-nova-ccsize="l"]{--nova-cc-k:1.22}html[data-nova-ccsize="xl"]{--nova-cc-k:1.5}' +
      'html[data-nova-ccbg="solid"]{--nova-cc-bg:rgba(0,0,0,.92)}' +
      'html[data-nova-ccbg="none"]{--nova-cc-bg:transparent;' +
      '--nova-cc-sh:0 0 3px #000,0 0 3px #000,0 1px 6px rgba(0,0,0,.95),0 0 10px rgba(0,0,0,.8)}' +
      // الحاوية تملأ المشغّل وتوزّع النوافذ بـ flex: توسيط أفقي مضمون بلا transform ولا left/margin (يوتيوب يضع مقاسات بكسل خاصة به)
      // وبلا transition ولا backdrop-filter (كانا سبب التقطيع وأحياناً سواد الفيديو مع الترجمة التلقائية)
      '.ytp-caption-window-container{pointer-events:none!important;position:absolute!important;left:0!important;right:0!important;width:auto!important;height:auto!important;' +
      'display:flex!important;flex-direction:column!important;align-items:center!important;margin:0!important;padding:0 4%!important;box-sizing:border-box!important}' +
      '.ytp-caption-window-container .caption-window,.caption-window{position:static!important;left:auto!important;right:auto!important;top:auto!important;bottom:auto!important;' +
      'transform:none!important;transition:none!important;margin:0 0 4px 0!important;width:auto!important;max-width:100%!important;' +
      'text-align:center!important;display:flex!important;flex-direction:column!important;align-items:center!important}' +
      // الموضع: b أسفل (فوق أزرار التحكم) • m وسط • t أعلى. --nova-cc-off إزاحة دقيقة نحو الداخل (٪)
      'html:not([data-nova-ccpos]) .ytp-caption-window-container,html[data-nova-ccpos=\"b\"] .ytp-caption-window-container{top:0!important;justify-content:flex-end!important;bottom:calc(var(--nova-cc-base,27%) + var(--nova-cc-off,0)*1%)!important}' +
      'html[data-nova-ccpos=\"m\"] .ytp-caption-window-container{top:0!important;bottom:0!important;justify-content:center!important}' +
      'html[data-nova-ccpos=\"t\"] .ytp-caption-window-container{bottom:0!important;justify-content:flex-start!important;top:calc(7% + var(--nova-cc-off,0)*1%)!important}' +
      '.ytp-caption-window-container .caption-visual-line{display:block!important;text-align:center!important;margin:2px 0!important}' +
      // النص: حجم متجاوب مع عرض الشاشة، يلتف السطر ولا يقتصّ
      '.ytp-caption-segment{background:var(--nova-cc-bg)!important;color:#fff!important;' +
      'font-family:Roboto,\"Segoe UI\",\"Noto Naskh Arabic\",\"Noto Sans Arabic\",system-ui,sans-serif!important;' +
      'font-size:calc(clamp(13px,3.9vw,26px)*var(--nova-cc-k))!important;line-height:1.55!important;' +
      'padding:.12em .6em!important;border-radius:10px!important;text-shadow:var(--nova-cc-sh)!important;text-align:center!important;' +
      'unicode-bidi:plaintext;white-space:pre-wrap!important;word-break:break-word;' +
      '-webkit-box-decoration-break:clone;box-decoration-break:clone}' +
      // ── طبقات إصلاحية عامة ──
      '*{-webkit-tap-highlight-color:transparent}' +
      // لافتات الدعوة لتثبيت تطبيق يوتيوب (لا تمسّ الإعلانات)
      'ytm-mealbar-promo-renderer,ytm-upsell-dialog-renderer,ytm-app-banner-renderer,.open-app-banner,ytm-open-app-banner{display:none!important}' +
      // يمنع ارتداد الصفحة (overscroll) الذي يقطع تمرير القوائم داخل WebView
      'html,body{overscroll-behavior-y:none}' +
      // طبقة «الفيديوهات المقترحة» عند الإيقاف تحجب المشغّل وتبطّئ اللمس
      '.ytp-pause-overlay,.ytp-pause-overlay-container{display:none!important}' +
      // «الإضاءة السينمائية/المحيطية»: طبقة كانفس ضبابية خلف المشغّل لا تُعاد رسمها بعد الدوران فتظهر ضبابية ثم سوداء
      '#cinematics canvas,.ytp-cinematic-container canvas,ytm-cinematic-container-renderer canvas{display:none!important}';
    // إخفاء شورتس (اختياري). القاعدة التي تستعمل :has منفصلة كي لا تُسقط القواعد الأخرى في المحركات القديمة
    var SHORTS = UI.shorts
      ? 'ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2{display:none!important}' : '';
    var SHORTS2 = UI.shorts ? 'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model),ytm-rich-section-renderer:has(ytm-reel-shelf-renderer){display:none!important}' : '';

    function addStyle(id, css) {
      if (!css) return;
      var st = document.createElement('style'); st.id = id; st.textContent = css;
      (document.head || root()).appendChild(st);
    }
    addStyle('nova-yt-fix-style', CSS + SHORTS);
    addStyle('nova-yt-fix-style2', SHORTS2);

    // ───────── لمسات جمالية وسلاسة لقوائم يوتيوب (لا تمسّ المشغّل ولا صفحة المشاهدة نفسها) ─────────
    var POLISH =
      'html{-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}' +
      '::-webkit-scrollbar{display:none}' +
      // صور مصغّرة بحواف مدوّرة (قوائم الرئيسية والبحث والمقترحات فقط)
      '.video-thumbnail-container-large,.video-thumbnail-container-compact,.media-item-thumbnail-container{border-radius:12px;overflow:hidden}' +
      // يقلّل كلفة إعادة الحساب أثناء التمرير الطويل
      'ytm-rich-item-renderer,ytm-media-item,ytm-compact-video-renderer,ytm-video-with-context-renderer{contain:layout style}' +
      // أزرار وشرائح: انتقال ناعم عند اللمس
      '.yt-spec-button-shape-next,ytm-chip-cloud-chip-renderer,ytm-button-renderer{transition:transform .16s cubic-bezier(.2,0,0,1)}';
    var ANIM =
      '@keyframes novaIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}' +
      // ظهور تدريجي لبطاقات الفيديو الجديدة (التمرير اللانهائي والبحث) بحركة قصيرة على الطبقة فقط (opacity/transform)
      'ytm-rich-item-renderer,ytm-media-item,ytm-compact-video-renderer,ytm-video-with-context-renderer{animation:novaIn .3s cubic-bezier(.2,0,0,1) both}' +
      // ضغط خفيف على البطاقات والأزرار
      'ytm-media-item:active,ytm-compact-video-renderer:active,ytm-rich-item-renderer:active{transform:scale(.99);transition:transform .12s ease-out}' +
      '.yt-spec-button-shape-next:active,ytm-chip-cloud-chip-renderer:active,ytm-button-renderer:active{transform:scale(.95)}' +
      '@media (prefers-reduced-motion:reduce){ytm-rich-item-renderer,ytm-media-item,ytm-compact-video-renderer,ytm-video-with-context-renderer{animation:none!important}}';
    addStyle('nova-yt-polish', POLISH);
    if (UI.anim) addStyle('nova-yt-anim', ANIM);

    // إعدادات الترجمة تأتي من إعدادات التطبيق مباشرة (لا تخزين محلي ولا واجهة وسيطة)
    (function applyCc() {
      var r = root(); if (!r) { setTimeout(applyCc, 20); return; }
      r.setAttribute('data-nova-ccsize', CC.size || 'm');
      r.setAttribute('data-nova-ccbg', CC.bg || 'glass');
      r.setAttribute('data-nova-ccpos', CC.pos || 'b');
      var o = parseInt(CC.off, 10); if (!(o >= 0 && o <= 40)) o = 0;
      r.style.setProperty('--nova-cc-off', String(o));
    })();

    // ───────── قياس الفيديو بعد الدوران ─────────
    // يوتيوب يترك مقاسات بكسل قديمة على عنصر الفيديو بعد تدوير الشاشة فيظهر مقصوصاً/بشريط؛ نُجبره على إعادة الحساب مرة واحدة
    var rz = 0, fromUs = false, land = window.innerWidth > window.innerHeight;
    function refit() {
      rz = 0;
      var v = document.querySelector('video.html5-main-video'); if (!v) return;
      v.style.removeProperty('width'); v.style.removeProperty('height'); v.style.removeProperty('left'); v.style.removeProperty('top');
      fromUs = true; try { window.dispatchEvent(new Event('resize')); } catch (e) {} fromUs = false;
    }
    // فقط عند انقلاب الاتجاه (طولي ↔ عرضي)، فلا يتأثر التمرير عند ظهور/اختفاء شريط المتصفح
    window.addEventListener('resize', function () {
      if (fromUs) return;
      var l = window.innerWidth > window.innerHeight;
      if (l === land) return;
      land = l; clearTimeout(rz); rz = setTimeout(refit, 400);
    });

    // ───────── موضع الترجمة السفلي يتبع أزرار التحكم: فوقها تماماً وهي ظاهرة، وقريب من الحافة حين تختفي ─────────
    // لا عمل إطلاقاً ما لم تكن هناك ترجمة معروضة؛ يُقاس ارتفاع الشريط فقط عند تغيّر حالته، والمتغيّر يُضبط على المشغّل لا على <html>
    // (ضبطه على <html> كان يُبطل أنماط الصفحة كلها في كل مرة)
    var lastShown = null, lastEl = null, tickT = 0;
    function player() { return document.getElementById('movie_player') || document.querySelector('.html5-video-player'); }
    function ctl() { return document.querySelector('.player-controls-bottom,.ytp-chrome-bottom'); }
    function shown(c) {
      if (!c) return false;
      var cs = getComputedStyle(c);
      return cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.05;
    }
    function tick() {
      if (document.hidden || location.pathname !== '/watch') return;
      if (!document.querySelector('.ytp-caption-window-container .caption-window')) return;
      var p = player(); if (!p) return;
      var c = ctl(), s = shown(c);
      if (s === lastShown && p === lastEl) return;
      lastShown = s; lastEl = p;
      var b = '9%';
      if (s) {
        var d = p.getBoundingClientRect().bottom - c.getBoundingClientRect().top;
        b = (d > 20 && d < p.clientHeight * 0.6) ? Math.round(d + 6) + 'px' : '27%';
      }
      p.style.setProperty('--nova-cc-base', b);
    }
    function arm() { if (!tickT) tickT = setInterval(tick, 700); }
    function disarm() { if (tickT) { clearInterval(tickT); tickT = 0; } }
    document.addEventListener('visibilitychange', function () { if (document.hidden) disarm(); else arm(); });
    ['touchend', 'click'].forEach(function (e) { document.addEventListener(e, function () { setTimeout(tick, 80); }, true); });
    window.addEventListener('yt-navigate-finish', function () { lastShown = null; });
    arm();

    // ───────── بعد الخروج من ملء الشاشة/الدوران: إعادة قياس المشغّل وإيقاظ طبقة الفيديو (يمنع الشاشة السوداء) ─────────
    function nudge() {
      var v = document.querySelector('video.html5-main-video'); if (!v) return;
      v.style.setProperty('transform', 'translateZ(0)', 'important');
      requestAnimationFrame(function () { requestAnimationFrame(function () { v.style.removeProperty('transform'); }); });
    }
    window.__novaRefit = function (deep) { lastShown = null; refit(); if (deep) nudge(); setTimeout(tick, 80); };
    ['fullscreenchange', 'webkitfullscreenchange'].forEach(function (n) {
      document.addEventListener(n, function () { setTimeout(function () { window.__novaRefit(false); }, 250); setTimeout(function () { window.__novaRefit(true); }, 900); }, true);
    });

    // ───────── العودة للأعلى (اسم الموقع في الشريط العلوي يستدعيها) ─────────
    window.__novaTop = function () {
      try {
        var best = document.scrollingElement || root(), bh = best.scrollTop;
        var l = document.querySelectorAll('ytm-app,main,[role=main]');
        for (var i = 0; i < l.length; i++) {
          var e = l[i];
          if (e.scrollTop > bh && e.scrollHeight > e.clientHeight + 50) { best = e; bh = e.scrollTop; }
        }
        best.scrollTo({ top: 0, behavior: 'smooth' });
      } catch (e) {}
    };
  } catch (e) {}
})();
} catch (e) {}

/* ═════════ 2b) PLAY — تشغيل أذكى: استئناف + تذكّر السرعة + ضغط مطوّل ×2 ═════════ */
try {
(function () {
  'use strict';
  try {
    if (window.__novaYtPlay || window.top !== window || location.hostname === 'music.youtube.com') return;
    window.__novaYtPlay = 1;
    var P = __PLAY__;
    var KP = 'nova.yt.pos', KR = 'nova.yt.rate';
    function sget(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
    function sset(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
    function vid() { try { return new URLSearchParams(location.search).get('v') || ''; } catch (e) { return ''; } }
    function video() { return document.querySelector('video.html5-main-video') || document.querySelector('video'); }
    function player() { return document.getElementById('movie_player') || document.querySelector('.html5-video-player'); }
    function ad() { var p = player(); return !!(p && p.classList && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'))); }
    function watch() { return location.pathname === '/watch'; }

    // ───────── استئناف من حيث توقفت ─────────
    function posMap() { try { return JSON.parse(sget(KP) || '{}') || {}; } catch (e) { return {}; } }
    function savePos(id, sec, drop) {
      var m = posMap();
      if (drop) delete m[id]; else m[id] = [Math.floor(sec), Date.now()];
      var ks = Object.keys(m);
      if (ks.length > 80) { ks.sort(function (a, b) { return m[a][1] - m[b][1]; }); for (var i = 0; i < ks.length - 80; i++) delete m[ks[i]]; }
      sset(KP, JSON.stringify(m));
    }
    var resumedFor = '', lastSave = 0, ours = false, holding = false;
    function tryResume(v) {
      if (!P.resume || !watch() || ad()) return;
      var id = vid(); if (!id || resumedFor === id || !(v.duration > 60)) return;
      resumedFor = id;
      if (/[?&]t=/.test(location.search)) return;               // رابط بوقت محدد يغلب الاستئناف
      var e = posMap()[id]; if (!e) return;
      var s = e[0]; if (s > 15 && v.duration - s > 20) { try { v.currentTime = s; } catch (x) {} }
    }
    function track(v) {
      if (!P.resume || !watch() || ad() || !(v.duration > 60) || holding) return;
      var now = Date.now(); if (now - lastSave < 5000) return; lastSave = now;
      var id = vid(); if (!id) return;
      if (v.duration - v.currentTime <= 20) savePos(id, 0, true);
      else if (v.currentTime > 15) savePos(id, v.currentTime, false);
    }

    // ───────── تذكّر السرعة ─────────
    function applyRate(v) {
      if (!P.keep || ad()) return;
      var r = parseFloat(sget(KR) || '1');
      if (r > 0 && r <= 2 && Math.abs(v.playbackRate - r) > 0.01) { ours = true; try { v.playbackRate = r; } catch (e) {} ours = false; }
    }

    document.addEventListener('loadedmetadata', function (e) {
      var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
      setTimeout(function () { tryResume(v); applyRate(v); }, 500);
    }, true);
    document.addEventListener('timeupdate', function (e) { var v = e.target; if (v && v.tagName === 'VIDEO') track(v); }, true);
    document.addEventListener('ratechange', function (e) {
      var v = e.target; if (!v || v.tagName !== 'VIDEO' || !P.keep || ours || holding || ad() || !watch()) return;
      if (v.playbackRate > 0 && v.playbackRate <= 2) sset(KR, String(v.playbackRate));
    }, true);
    window.addEventListener('yt-navigate-finish', function () { resumedFor = ''; });
    document.addEventListener('visibilitychange', function () {
      var v = video(), id = vid(); if (!P.resume || !v || !id || !watch() || ad() || !(v.duration > 60)) return;
      if (v.duration - v.currentTime > 20 && v.currentTime > 15) savePos(id, v.currentTime, false);
    }, true);

    // ───────── ضغط مطوّل على المشغّل = ×2 مؤقتاً (يعود عند الرفع) ─────────
    if (P.hold) (function () {
      var timer = 0, sx = 0, sy = 0, prev = 1, badge = null;
      var css = document.createElement('style');
      css.textContent = '#nova-hold-badge{position:absolute;top:9%;left:50%;transform:translateX(-50%);z-index:99;padding:6px 14px;border-radius:999px;' +
        'background:rgba(0,0,0,.55);-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);color:#fff;font:600 13px/1.2 system-ui,sans-serif;pointer-events:none;direction:ltr}';
      (document.head || document.documentElement).appendChild(css);
      function inPlayer(t) {
        if (!t || !t.closest) return false;
        if (t.closest('button,[role=slider],[role=button],.ytp-chrome-bottom,.player-controls-bottom,.ytp-settings-menu,.ytp-ce-element')) return false;
        return !!t.closest('#movie_player,.html5-video-player,#player-container-id,.player-container');
      }
      function stop() {
        clearTimeout(timer); timer = 0;
        if (!holding) return;
        var v = video(); if (v) { ours = true; try { v.playbackRate = prev; } catch (e) {} ours = false; }
        holding = false; if (badge && badge.parentNode) badge.parentNode.removeChild(badge); badge = null;
      }
      document.addEventListener('touchstart', function (e) {
        if (!watch() || ad() || !e.touches || e.touches.length !== 1 || !inPlayer(e.target)) return;
        sx = e.touches[0].clientX; sy = e.touches[0].clientY;
        clearTimeout(timer);
        timer = setTimeout(function () {
          var v = video(); if (!v || v.paused) return;
          prev = v.playbackRate || 1; holding = true;
          ours = true; try { v.playbackRate = 2; } catch (x) {} ours = false;
          var host = player() || document.body;
          badge = document.createElement('div'); badge.id = 'nova-hold-badge'; badge.textContent = '2x  ▶▶'; host.appendChild(badge);
          try { if (navigator.vibrate) navigator.vibrate(12); } catch (x) {}
        }, 450);
      }, { passive: true, capture: true });
      document.addEventListener('touchmove', function (e) {
        if (!timer || holding || !e.touches || !e.touches[0]) return;
        if (Math.abs(e.touches[0].clientX - sx) > 12 || Math.abs(e.touches[0].clientY - sy) > 12) { clearTimeout(timer); timer = 0; }
      }, { passive: true, capture: true });
      ['touchend', 'touchcancel'].forEach(function (n) { document.addEventListener(n, stop, { passive: true, capture: true }); });
      document.addEventListener('contextmenu', function (e) { if (holding || timer) e.preventDefault(); }, true);
    })();
  } catch (e) {}
})();
} catch (e) {}

/* ═════════ 3) MEDIA ═════════ */
try {
(function () {
  if (window.__novaYt || window.top !== window || !window.NovaYt) return;
  window.__novaYt = 1;
  var BG = __BG__;
  var bg = false, userPaused = false, lastResume = 0, nlog = 0, lastGesture = 0;
  // الحماية من إيقاف الصفحة للفيديو أثناء الخلفية/النافذة المنبثقة (إن فُعّلت من الإعدادات)
  var graceUntil = 0;   // بعد العودة للواجهة يصل أحياناً إيقاف متأخر من النظام (تدوير الشاشة): نبقي الحماية لحظة
  function guard() { return BG && (bg || Date.now() < graceUntil); }

  function send(o) { try { window.NovaYt.postMessage(JSON.stringify(o)); } catch (e) {} }
  function log(m) { if (nlog++ < 150) send({ t: 'log', m: String(m).slice(0, 400) }); }
  function v() { return document.querySelector('video.html5-main-video') || document.querySelector('video'); }

  // لمسة حقيقية من المستخدم: إيقاف الفيديو بعدها يُعدّ طلباً منه فلا تمنعه الحماية (مهم داخل النافذة المنبثقة)
  ['touchstart', 'pointerdown', 'click', 'keydown'].forEach(function (n) {
    document.addEventListener(n, function (e) { if (e.isTrusted !== false) lastGesture = Date.now(); }, { capture: true, passive: true });
  });

  // القيمة الحقيقية لرؤية الصفحة (قبل التمويه) للتشخيص
  var realVis = function () { try { return Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState').get.call(document); } catch (e) { return '?'; } };
  log('script loaded ' + location.pathname + ' BG=' + BG);

  function meta() {
    var m = navigator.mediaSession && navigator.mediaSession.metadata;
    var t = (m && m.title) || document.title.replace(/ - YouTube$/, '');
    var a = (m && m.artist) || '';
    var art = '';
    if (m && m.artwork && m.artwork.length) art = m.artwork[m.artwork.length - 1].src;
    if (!art) { var og = document.querySelector('meta[property="og:image"]'); if (og) art = og.content; }
    return { title: t, artist: a, art: art };
  }
  var stT = 0;
  function state() {
    var e = v(); if (!e) return;
    var m = meta();
    send({
      t: 'state', playing: !e.paused && !e.ended,
      pos: Math.floor(e.currentTime * 1000),
      dur: isFinite(e.duration) ? Math.floor(e.duration * 1000) : 0,
      title: m.title, artist: m.artist, art: m.art
    });
  }
  // أحداث متقاربة (play ثم seeked ثم loadedmetadata) تُدمج في رسالة واحدة
  function stateSoon() { clearTimeout(stT); stT = setTimeout(state, 80); }
  ['play', 'pause', 'ended', 'seeked', 'loadedmetadata'].forEach(function (n) { document.addEventListener(n, stateSoon, true); });
  ['play', 'pause', 'waiting', 'stalled', 'ended', 'error', 'emptied'].forEach(function (n) {
    document.addEventListener(n, function (ev) {
      if (ev.target && ev.target.tagName === 'VIDEO')
        log('event ' + n + ' t=' + Math.round(ev.target.currentTime) + ' vis=' + realVis() + ' bg=' + bg);
    }, true);
  });
  setInterval(function () { var e = v(); if (e && !e.paused && !document.hidden) state(); else if (e && !e.paused && guard()) state(); }, 5000);

  // إخفاء تغيّر الرؤية عن الصفحة حتى لا يوقف يوتيوب التشغيل (يعمل فقط عند وجود الحماية)
  try {
    var dsc = function (n) { try { return Object.getOwnPropertyDescriptor(Document.prototype, n); } catch (e) { return null; } };
    var dh = dsc('hidden'), dv = dsc('visibilityState');
    Object.defineProperty(document, 'hidden', { get: function () { return guard() ? false : (dh && dh.get ? dh.get.call(document) : false); }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return guard() ? 'visible' : (dv && dv.get ? dv.get.call(document) : 'visible'); }, configurable: true });
    Object.defineProperty(document, 'webkitHidden', { get: function () { return guard() ? false : (dh && dh.get ? dh.get.call(document) : false); }, configurable: true });
    Object.defineProperty(document, 'webkitVisibilityState', { get: function () { return guard() ? 'visible' : (dv && dv.get ? dv.get.call(document) : 'visible'); }, configurable: true });
    ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze', 'blur'].forEach(function (n) {
      window.addEventListener(n, function (e) { if (guard()) e.stopImmediatePropagation(); }, true);
      document.addEventListener(n, function (e) { if (guard()) e.stopImmediatePropagation(); }, true);
    });
  } catch (e) {}

  // يمنع كود يوتيوب نفسه من إيقاف الفيديو أثناء الحماية، إلا إن طلب المستخدم الإيقاف (من إشعار الوسائط أو بلمسة حديثة)
  try {
    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function () {
      if (guard() && !userPaused && !this.ended) {
        if (Date.now() - lastGesture < 900) userPaused = true;       // لمسة المستخدم على زر الإيقاف
        else { log('blocked page pause'); return; }
      }
      return origPause.apply(this, arguments);
    };
    window.__novaOrigPause = origPause;
  } catch (e) {}

  window.__novaBg = function (b) { b = !!b; if (bg && !b) graceUntil = Date.now() + 1500; bg = b; log('bg=' + bg); };

  // ───────── استعادة الموضع إذا أعاد يوتيوب تحميل الفيديو من الصفر أثناء الخروج للخلفية ─────────
  // السجل: عند الضغط على زر الرئيسية يوقف النظام الفيديو (vis=hidden) ثم يُفرّغ يوتيوب المصدر (emptied t=0) فيبدأ من البداية.
  // نحفظ آخر موضع ومعرّف الفيديو، وإن حدث التفريغ أثناء الخلفية/بعدها بلحظات نقفز لنفس الموضع عند جاهزية المصدر الجديد (لنفس الفيديو فقط).
  var lastT = 0, lastId = '', lastBgAt = 0, restore = null;
  function curId() { try { var p = document.getElementById('movie_player'); var d = p && p.getVideoData && p.getVideoData(); return (d && d.video_id) || ''; } catch (x) { return ''; } }
  var _bgFn = window.__novaBg;
  window.__novaBg = function (b) { lastBgAt = Date.now(); return _bgFn(b); };
  document.addEventListener('timeupdate', function (ev) {
    var e = ev.target; if (!e || e.tagName !== 'VIDEO' || e.ended || !(e.currentTime > 1)) return;
    lastT = e.currentTime; lastId = curId();
  }, true);
  document.addEventListener('emptied', function (ev) {
    if (!ev.target || ev.target.tagName !== 'VIDEO') return;
    if ((guard() || Date.now() - lastBgAt < 6000) && lastT > 3) { restore = { t: lastT, id: lastId, at: Date.now() }; log('will restore ' + Math.round(lastT)); }
  }, true);
  function tryRestore(ev) {
    var e = ev.target; if (!restore || !e || e.tagName !== 'VIDEO') return;
    if (Date.now() - restore.at > 20000) { restore = null; return; }
    var id = curId(); if (restore.id && id && id !== restore.id) { restore = null; return; }
    if (e.currentTime < restore.t - 2) {
      var r = restore; restore = null;
      try { e.currentTime = r.t; } catch (x) {}
      log('restored ' + Math.round(r.t));
      if (!userPaused) { var pl = e.play(); if (pl && pl.catch) pl.catch(function () {}); }
    } else restore = null;
  }
  ['loadedmetadata', 'canplay'].forEach(function (n) { document.addEventListener(n, tryRestore, true); });

  // ───────── إصلاح الشاشة السوداء بعد الدوران/ملء الشاشة ─────────
  // الصفحة تُعدّ مخفية لحظة الدوران فيُفرّغ يوتيوب مصدر الفيديو (emptied) ويبقى المشغّل أسود حتى إعادة التحميل.
  // الفحص: هل للعنصر مصدر وإطار مرسوم؟ إن لا → نطلب من المشغّل إعادة التحميل من نفس الثانية، وفي المرحلة الأخيرة نعيد فتح الصفحة بنفس الموضع.
  function blackFrame(e) {
    try {
      if (e.paused && e.currentTime < 1) return false;
      var c = document.createElement('canvas'); c.width = c.height = 8;
      var x = c.getContext('2d'); x.drawImage(e, 0, 0, 8, 8);
      var d = x.getImageData(0, 0, 8, 8).data;
      for (var i = 0; i < d.length; i += 4) if (d[i] + d[i + 1] + d[i + 2] > 12) return false;
      return true;
    } catch (err) { return false; }   // محمي/ملوّث: لا نحكم
  }
  // يفحص هل عنصر الفيديو مرئي فعلاً داخل المشغّل (مقاس/موضع/شفافية). refit القديم يمسح مقاسات يوتيوب المضمّنة فقد يبقى العنصر بلا مقاس
  // والصوت والترجمة يعملان بلا صورة. إن وجدنا خللاً نثبّت مقاساً صريحاً يملأ المشغّل (object-fit: contain يحفظ النسبة).
  function fixLayout(e, p) {
    var info = {};
    try {
      var r = e.getBoundingClientRect(), pr = (p || e.parentElement).getBoundingClientRect(), cs = getComputedStyle(e);
      info = { v: [Math.round(r.width), Math.round(r.height), Math.round(r.left), Math.round(r.top)], p: [Math.round(pr.width), Math.round(pr.height), Math.round(pr.left), Math.round(pr.top)],
        d: cs.display, vis: cs.visibility, op: cs.opacity, of: cs.objectFit };
      var bad = r.width < pr.width * 0.4 || r.height < pr.height * 0.3 || Math.abs(r.left - pr.left) > pr.width * 0.5 || Math.abs(r.top - pr.top) > pr.height * 0.5 ||
        cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.1;
      info.bad = bad;
      if (bad && pr.width > 50 && pr.height > 50) {
        var st = e.style;
        st.setProperty('display', 'block', 'important'); st.setProperty('visibility', 'visible', 'important'); st.setProperty('opacity', '1', 'important');
        st.setProperty('position', 'absolute', 'important'); st.setProperty('left', '0px', 'important'); st.setProperty('top', '0px', 'important');
        st.setProperty('width', Math.round(pr.width) + 'px', 'important'); st.setProperty('height', Math.round(pr.height) + 'px', 'important');
        st.setProperty('object-fit', 'contain', 'important');
        info.fixed = 1;
      }
    } catch (x) { info.err = String(x); }
    return info;
  }
  window.__novaHeal = function (stage) {
    try {
      if (location.pathname !== '/watch') return 'skip';
      var e = v(), p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (!e) return 'novideo';
      var ok = e.readyState >= 2 && e.videoWidth > 0 && !!e.currentSrc;
      var loading = e.networkState === 2 && e.readyState < 2;     // ما زال يحمّل: ننتظر
      if (ok) {
        var lay = fixLayout(e, p); log('heal diag stage=' + stage + ' ' + JSON.stringify(lay) + ' black=' + blackFrame(e) + ' rs=' + e.readyState + ' vw=' + e.videoWidth + ' t=' + Math.round(e.currentTime));
        // يعمل (صوت وترجمة) لكن قد لا تُرسم الصورة: نقرأ إطاراً صغيراً من الفيديو؛ إن كان أسود كلياً نعيد إنشاء المشغّل
        if (stage >= 1 && blackFrame(e)) {
          var id0 = '', t1 = Math.floor(e.currentTime || 0);
          try { var d0 = p && p.getVideoData && p.getVideoData(); id0 = d0 && d0.video_id || ''; } catch (x) {}
          if (!id0) { var m0 = /[?&]v=([^&]+)/.exec(location.search); id0 = m0 ? m0[1] : ''; }
          log('heal black frame stage=' + stage + ' id=' + id0);
          if (id0) {
            if (stage < 2 && p && p.loadVideoById) { try { p.loadVideoById(id0, t1); return 'black-reloaded-player'; } catch (x) {} }
            if (stage >= 2) { location.replace('/watch?v=' + encodeURIComponent(id0) + (t1 > 3 ? '&t=' + t1 + 's' : '')); return 'black-reloaded-page'; }
          }
        }
        e.style.setProperty('transform', 'translateZ(0)', 'important'); void e.offsetHeight; e.style.removeProperty('transform');
        if (e.paused) { try { var t0 = e.currentTime; e.currentTime = t0 > 0.05 ? t0 - 0.01 : t0; } catch (x) {} }
        return 'ok';
      }
      if (document.querySelector('.ad-showing,.ad-interrupting')) return 'ad';
      if (loading && (stage < 2 || e.currentSrc)) return 'loading';
      var id = '', t = Math.floor(e.currentTime || 0);
      try { var d = p && p.getVideoData && p.getVideoData(); id = d && d.video_id || ''; if (p && p.getCurrentTime) t = Math.floor(p.getCurrentTime() || t); } catch (x) {}
      if (!id) { var m = /[?&]v=([^&]+)/.exec(location.search); id = m ? m[1] : ''; }
      log('heal stage=' + stage + ' rs=' + e.readyState + ' id=' + id + ' t=' + t);
      if (!id) return 'noid';
      if (stage < 2) {
        if (p && p.loadVideoById) { try { p.loadVideoById(id, t); if (userPaused && p.pauseVideo) setTimeout(function () { try { p.pauseVideo(); } catch (x) {} }, 800); return 'reloaded-player'; } catch (x) {} }
        var pl = e.play(); if (pl && pl.catch) pl.catch(function () {});
        return 'play';
      }
      location.replace('/watch?v=' + encodeURIComponent(id) + (t > 3 ? '&t=' + t + 's' : ''));
      return 'reloaded-page';
    } catch (x) { return 'err'; }
  };

  // إيقاف جاء من خارج الصفحة (نظام/WebView): نستأنف ما لم يطلب المستخدم الإيقاف
  var burstAt = 0, burstN = 0, retryT = 0;
  document.addEventListener('play', function () { userPaused = false; }, true);
  document.addEventListener('pause', function (ev) {
    var e = v();
    if (!guard() || userPaused || !e || ev.target !== e || e.ended) return;
    if (Date.now() - lastGesture < 900) { userPaused = true; return; }
    var now = Date.now();
    if (now - burstAt > 3000) { burstAt = now; burstN = 0; }
    if (++burstN > 8) return;   // يوتيوب يصرّ على الإيقاف فعلاً: لا نتصارع معه
    if (now - lastResume < 400) {
      // إيقاف ثانٍ قريب جداً من الاستئناف (يحدث عند تدوير الشاشة) كان يُهمل فيبقى الفيديو متوقفاً: نعيد المحاولة بعد قليل
      clearTimeout(retryT);
      retryT = setTimeout(function () { if (guard() && !userPaused && e.paused) { var p2 = e.play(); if (p2 && p2.catch) p2.catch(function () {}); } }, 450);
      return;
    }
    lastResume = now;
    log('resuming after external pause vis=' + realVis());
    setTimeout(function () { if (guard() && !userPaused && e.paused) { var p = e.play(); if (p && p.catch) p.catch(function (x) { log('play rejected ' + x); }); } }, 120);
  }, true);
  function clickPlay() {
    var b = document.querySelector('.ytp-play-button,button.player-control-play-pause-icon,[aria-label="Play"]');
    if (b) b.click();
  }

  // أوامر من إشعار الوسائط
  window.__novaYtCtl = function (a) {
    var e = v(); if (!e) { log('ctl ' + a + ' but no video'); return; }
    log('ctl ' + a);
    if (a === 'play') {
      userPaused = false;
      // واجهة المشغّل نفسها أولاً (تتجاوز منطق الإيقاف الداخلي)، ثم play() ثم نقر الزر كاحتياط
      try { var mp = document.getElementById('movie_player'); if (mp && mp.playVideo) mp.playVideo(); } catch (x) {}
      var p = e.play();
      if (p && p.catch) p.catch(function (x) { log('ctl play rejected ' + x); clickPlay(); });
      setTimeout(function () { if (e.paused && !userPaused) { clickPlay(); setTimeout(function () { if (e.paused && !userPaused) { try { e.click(); } catch (x) {} } }, 400); } }, 450);
    }
    else if (a === 'pause') { userPaused = true; (window.__novaOrigPause || e.pause).call(e); }
    else if (a === 'fwd') e.currentTime = Math.min(e.duration || 1e9, e.currentTime + 10);
    else if (a === 'back') e.currentTime = Math.max(0, e.currentTime - 10);
    else if (a.indexOf('seek:') === 0) e.currentTime = parseFloat(a.slice(5)) / 1000;
    stateSoon();
  };

  // النافذة المنبثقة: يملأ الفيديو الشاشة ويُخفى كل ما عداه. التراجع (false) آمن دائماً ويُستدعى أيضاً عند كل عودة للواجهة
  window.__novaPip = function (on) {
    var id = '__nova_pip_css', old = document.getElementById(id), e = v();
    log('pip css ' + on);
    if (!on) {
      if (old) old.remove();
      document.querySelectorAll('.__nova_v').forEach(function (x) { x.classList.remove('__nova_v'); });
      (window.__novaPipEls || []).forEach(function (p) { p[0].style.cssText = p[1]; });
      window.__novaPipEls = [];
      return;
    }
    if (!e || old) return;
    var st = document.createElement('style'); st.id = id;
    st.textContent = 'html,body{background:#000!important;overflow:hidden!important}' +
      'body *{visibility:hidden!important}' +
      'video.__nova_v{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;' +
      'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
      'z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important}';
    document.head.appendChild(st);
    e.classList.add('__nova_v');
    window.__novaPipEls = [];
    for (var p = e.parentElement; p && p !== document.documentElement; p = p.parentElement) {
      window.__novaPipEls.push([p, p.style.cssText]);
      ['transform', 'filter', 'contain', 'perspective', 'will-change'].forEach(function (k) {
        p.style.setProperty(k, k === 'will-change' ? 'auto' : 'none', 'important');
      });
      p.style.setProperty('overflow', 'visible', 'important');
    }
  };
})();
} catch (e) {}
