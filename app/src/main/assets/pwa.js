// Nova PWA Runtime v2 — يحوّل يوتيوب ومواقع الذكاء الاصطناعي إلى تجربة تطبيق.
// يُحقن قبل سكربتات الصفحة (document-start) على النطاقات المسموحة فقط. __CFG__ يستبدلها Pwa.kt بإعدادات التشغيل.
(function () {
  'use strict';
  try {
    if (window.__novaPwa || window.top !== window) return;
    var H = location.hostname;
    var YT = /(^|\.)youtube\.com$/.test(H);
    var GOOGLE = /(^|\.)google\.[a-z.]+$/.test(H);
    // google.com: فقط وضع الذكاء الاصطناعي (udm=50)، أما البحث العادي فلا يُمسّ
    if (GOOGLE && !/[?&]udm=50(&|$)/.test(location.search) && !/^\/ai(\/|$)/.test(location.pathname)) return;
    window.__novaPwa = 1;
    // صفحات جوجل (وضع الذكاء الاصطناعي) ثقيلة أصلاً وتتغيّر باستمرار أثناء كتابة الرد:
    // نُبقي لها الحد الأدنى فقط (هوية التطبيق + المشاركة) بلا مراقبات DOM ولا حركات انتقال، وإلا سبّبت لاغاً كبيراً.
    var LIGHT = GOOGLE;

    var CFG = { v: 2, anim: true, hap: true };
    try { CFG = Object.assign(CFG, __CFG__); } catch (e) {}

    var root = document.documentElement;
    function def(o, k, v) { try { Object.defineProperty(o, k, { get: function () { return v; }, configurable: true }); } catch (e) {} }
    function safe(f) { try { f(); } catch (e) {} }
    function send(o) { try { if (window.NovaPwa) window.NovaPwa.postMessage(JSON.stringify(o)); } catch (e) {} }

    // ───────────── 1) هوية التطبيق المثبّت ─────────────
    // المواقع ترى أنها تعمل كتطبيق مثبّت فتخفي لافتات «ثبّت/افتح في التطبيق»
    safe(function () {
      var mm = window.matchMedia.bind(window);
      window.matchMedia = function (q) {
        var s = String(q);
        var hit = /display-mode\s*:\s*(standalone|minimal-ui)/i.test(s);
        var miss = /display-mode\s*:\s*(browser|fullscreen)/i.test(s);
        if (hit || miss) {
          return { matches: hit, media: s, onchange: null,
            addListener: function () {}, removeListener: function () {},
            addEventListener: function () {}, removeEventListener: function () {},
            dispatchEvent: function () { return false; } };
        }
        return mm(q);
      };
    });
    def(navigator, 'standalone', true);
    safe(function () {
      navigator.getInstalledRelatedApps = function () { return Promise.resolve([{ platform: 'webapp', url: location.origin }]); };
      navigator.setAppBadge = navigator.clearAppBadge = function () { return Promise.resolve(); };
      window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); e.stopImmediatePropagation(); }, true);
    });

    // ───────────── 2) ميزات أصلية مفقودة في WebView ─────────────
    // Web Share API → نافذة المشاركة الأصلية للنظام
    safe(function () {
      navigator.share = function (d) {
        d = d || {};
        var t = [d.title, d.text, d.url].filter(Boolean).join('\n');
        if (!t) return Promise.reject(new TypeError('Nothing to share'));
        send({ t: 'share', text: t });
        return Promise.resolve();
      };
      navigator.canShare = function () { return true; };
    });
    // زر «نسخ» في الدردشات وأكواد الذكاء الاصطناعي: احتياطي عند رفض Clipboard API
    safe(function () {
      function legacyCopy(t) {
        return new Promise(function (ok, no) {
          try {
            var a = document.createElement('textarea');
            a.value = t; a.setAttribute('readonly', '');
            a.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none';
            (document.body || root).appendChild(a);
            a.select(); a.setSelectionRange(0, t.length);
            var r = document.execCommand('copy');
            a.remove();
            r ? ok() : no(new Error('copy failed'));
          } catch (e) { no(e); }
        });
      }
      var cb = navigator.clipboard;
      if (cb && cb.writeText) {
        var w = cb.writeText.bind(cb);
        cb.writeText = function (t) { return w(t).catch(function () { return legacyCopy(String(t)); }); };
      }
    });

    // ───────────── 3) مظهر وإحساس التطبيق (CSS) ─────────────
    var css =
      '*{-webkit-tap-highlight-color:transparent}' +
      'html{--nova-sat:env(safe-area-inset-top,0px);--nova-sab:env(safe-area-inset-bottom,0px);touch-action:manipulation;-webkit-text-size-adjust:100%;text-size-adjust:100%;overscroll-behavior-y:none}' +   // بلا تأخير نقرتين ولا ارتداد التمرير
      'body{-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;overscroll-behavior-y:none}' +
      '::-webkit-scrollbar{display:none}' +
      'input,textarea{-webkit-user-select:text;user-select:text}' +
      'button,[role=button],[role=tab],summary,select{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}' +
      'img,video,canvas{-webkit-user-drag:none}' +
      (CFG.anim && !LIGHT ? '@keyframes novaIn{from{opacity:.01}to{opacity:1}}html{animation:novaIn .18s ease-out backwards}' : '') +
      (YT ? 'ytm-open-app-promo-renderer,ytm-mealbar-promo-renderer,ytm-app-install-promo-renderer,ytm-promoted-sparkles-web-renderer{display:none!important}' : '');
    safe(function () {
      var sh = new CSSStyleSheet();
      sh.replaceSync(css);
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sh]);
    });

    // ───────────── 4) أداء: اتصالات مسبقة بخوادم الموقع ─────────────
    safe(function () {
      var hosts = YT ? ['https://i.ytimg.com', 'https://yt3.ggpht.com', 'https://www.gstatic.com', 'https://fonts.gstatic.com']
                     : GOOGLE ? ['https://www.gstatic.com', 'https://lh3.googleusercontent.com', 'https://fonts.gstatic.com']
                     : ['https://fonts.gstatic.com', 'https://cdn.jsdelivr.net'];
      hosts.forEach(function (h) {
        var l = document.createElement('link');
        l.rel = 'preconnect'; l.href = h; l.crossOrigin = '';
        root.appendChild(l);
      });
    });

    // ───────────── 5) لوحة المفاتيح: يبقى حقل الكتابة ظاهراً ─────────────
    // التطبيق يصغّر مساحة الصفحة عند ظهور الكيبورد؛ هنا نمرّر الحقل لمنتصف الرؤية بعد استقرار الحجم
    function editable(e) {
      if (!e || e.nodeType !== 1) return false;
      var t = e.tagName;
      return t === 'TEXTAREA' || e.isContentEditable || (t === 'INPUT' && !/^(checkbox|radio|button|submit|reset|file|range|color|image)$/i.test(e.type));
    }
    var kbT = 0;
    function reveal() {
      clearTimeout(kbT);
      kbT = setTimeout(function () {
        var a = document.activeElement;
        if (!editable(a)) return;
        var r = a.getBoundingClientRect(), vh = (window.visualViewport && window.visualViewport.height) || window.innerHeight;
        if (r.bottom > vh - 12 || r.top < 8) safe(function () { a.scrollIntoView({ block: 'center', behavior: 'auto' }); });
      }, 90);
    }
    document.addEventListener('focusin', function (e) { if (editable(e.target)) reveal(); }, true);
    safe(function () { window.visualViewport.addEventListener('resize', reveal); });

    // ───────────── 6) لمسة اهتزاز خفيفة على الأزرار ─────────────
    if (CFG.hap) {
      var lastH = 0;
      document.addEventListener('click', function (e) {
        if (!e.isTrusted) return;
        var n = e.target && e.target.closest && e.target.closest('button,[role=button],[role=tab],[role=switch],[role=menuitem],input[type=checkbox],input[type=radio]');
        var now = Date.now();
        if (n && now - lastH > 90) { lastH = now; send({ t: 'hap' }); }
      }, true);
    }

    // ───────────── 7) إخفاء لافتات «حمّل التطبيق» ─────────────
    // يوتيوب فقط (وبقية المواقع غير جوجل). كان يمرّ على كل الروابط والأزرار ويقرأ innerText (يفرض إعادة تخطيط) مع كل تغيير في الصفحة.
    // الآن: عناصر الأزرار فقط، قراءة aria-label/textContent بلا تخطيط، بتباطؤ أطول وبحدّ أقصى لعدد الفحوص.
    var bannerRe = /^(open app|open in app|get the app|get app|download the app|install app|use the app|try the app|فتح التطبيق|افتح التطبيق|حمّل التطبيق|حمل التطبيق|تنزيل التطبيق|ouvrir l.application|abrir app|app öffnen)$/i;
    var sweepT = 0, sweepN = 0;
    function sweep() {
      sweepT = 0; sweepN++;
      safe(function () {
        var l = document.querySelectorAll('ytm-button-renderer,yt-button-shape,button,[role=button]');
        for (var i = 0; i < l.length && i < 600; i++) {
          var e = l[i];
          if (e.__novaHid || e.__novaSeen) continue;
          e.__novaSeen = 1;   // كل عنصر يُفحص مرة واحدة فقط
          var s = ((e.getAttribute && e.getAttribute('aria-label')) || (e.childElementCount < 4 ? e.textContent : '') || '').trim();
          if (!s || s.length > 28 || !bannerRe.test(s)) continue;
          e.__novaHid = 1;
          var box = e.closest('ytm-button-renderer') || e;
          // إن كان داخل شريط ثابت صغير (لافتة مخصصة) نخفي الشريط كله — إلا إذا كان شريط الصفحة نفسه
          // (شعار/بحث/ترويسة): إخفاؤه كان يترك فراغاً بارتفاعه فوق الفيديو لأن يوتيوب يحجز مكانه.
          // حينها نخفي الزر وحده.
          var KEEP = 'input,ytm-searchbox,ytm-home-logo,ytm-topbar-logo-renderer,ytm-mobile-topbar-renderer,header,[role=search],a[href="/"]';
          if (!(e.closest && e.closest('ytm-mobile-topbar-renderer,header'))) {
            for (var p = box, d = 0; p && p !== document.body && d < 5; p = p.parentElement, d++) {
              var cs = getComputedStyle(p);
              if ((cs.position === 'fixed' || cs.position === 'sticky') && p.getBoundingClientRect().height < 140) {
                if (!p.matches(KEEP) && !p.querySelector(KEEP)) box = p;
                break;
              }
            }
          }
          box.style.setProperty('display', 'none', 'important');
        }
      });
    }
    function startSweep() {
      if (LIGHT) return;
      sweep();
      new MutationObserver(function () { if (!sweepT && sweepN < 40 && !document.hidden) sweepT = setTimeout(sweep, 1800); })
        .observe(root, { childList: true, subtree: true });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startSweep); else startSweep();

    // ───────────── 8) انتقال ناعم عند التنقل داخل الصفحة (SPA) + العودة للأعلى ─────────────
    // يوتيوب وصفحات الذكاء الاصطناعي تتنقل بلا إعادة تحميل (pushState) فتبدو التبديلات فجائية.
    // نُخفت المحتوى قليلاً ثم نُظهره (opacity فقط: لا يكسر العناصر الثابتة ولا مشغّل الفيديو).
    var lastRoute = location.pathname + location.search, navT = 0;
    function pageEl() { return document.querySelector('ytm-app') || document.querySelector('main') || document.body; }
    function routeChanged() {
      clearTimeout(navT);
      navT = setTimeout(function () {
        var r = location.pathname + location.search;
        if (r === lastRoute) return;
        lastRoute = r;
        if (!CFG.anim || LIGHT) return;
        safe(function () {
          var el = pageEl();
          if (el && el.animate) el.animate([{ opacity: 0.55 }, { opacity: 1 }], { duration: 190, easing: 'cubic-bezier(.2,0,0,1)' });
        });
      }, 30);
    }
    safe(function () {
      ['pushState', 'replaceState'].forEach(function (k) {
        var o = history[k];
        history[k] = function () { var r = o.apply(this, arguments); routeChanged(); return r; };
      });
      window.addEventListener('popstate', routeChanged);
    });
    // اسم الموقع في الشريط الأصلي يستدعيها: أكبر حاوية قابلة للتمرير → للأعلى بحركة ناعمة
    window.__novaTop = function () {
      safe(function () {
        var best = document.scrollingElement || root, bh = best.scrollTop;
        var l = document.querySelectorAll('main,[role=main],div,section');
        for (var i = 0; i < l.length && i < 400; i++) {
          var e = l[i];
          if (e.scrollTop > bh && e.scrollHeight > e.clientHeight + 50) { var o = getComputedStyle(e).overflowY; if (o === 'auto' || o === 'scroll') { best = e; bh = e.scrollTop; } }
        }
        best.scrollTo({ top: 0, behavior: CFG.anim ? 'smooth' : 'auto' });
      });
    };

    // واجهة عامة صغيرة يمكن للصفحات (أو سكربتاتك لاحقاً) استخدامها
    window.NovaApp = { version: CFG.v, share: navigator.share, haptic: function () { send({ t: 'hap' }); } };
  } catch (e) { }
})();
