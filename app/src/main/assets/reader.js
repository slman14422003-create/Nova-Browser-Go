// وضع القراءة: يستخرج نص المقالة ويعرضه نظيفاً فوق الصفحة. تشغيله مرة ثانية يغلقه.
// يبني العناصر بـ DOM API وقائمة سماح (لا innerHTML) فلا يتأثر بسياسات CSP ولا ينقل سكربتات الصفحة.
(function () {
  try {
    var old = document.getElementById('__nova_reader');
    if (old) { if (old.__close) old.__close(); else old.remove(); return 'closed'; }

    var ALLOW = { H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, P: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, PRE: 1, CODE: 1, FIGURE: 1,
      FIGCAPTION: 1, STRONG: 1, EM: 1, B: 1, I: 1, BR: 1, TABLE: 1, THEAD: 1, TBODY: 1, TR: 1, TD: 1, TH: 1, A: 1, IMG: 1 };
    var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, IFRAME: 1, FORM: 1, BUTTON: 1, NAV: 1, ASIDE: 1, FOOTER: 1, SVG: 1, CANVAS: 1,
      VIDEO: 1, AUDIO: 1, OBJECT: 1, EMBED: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, TEMPLATE: 1, DIALOG: 1 };
    var JUNK = /(comment|share|social|related|promo|advert|banner|newsletter|subscribe|sidebar|cookie|popup|modal|breadcrumb|toolbar)/i;

    function linkDensity(el) {
      var tl = (el.textContent || '').length || 1, ll = 0, as = el.getElementsByTagName('a');
      for (var i = 0; i < as.length && i < 400; i++) ll += (as[i].textContent || '').length;
      return ll / tl;
    }

    // اختيار حاوية المقالة: الفقرات الطويلة تمنح نقاطاً لأبيها وجدّها
    function pick() {
      var art = document.querySelector('article');
      if (art && (art.textContent || '').length > 900) return art;
      var map = new Map(), ps = document.querySelectorAll('p');
      for (var i = 0; i < ps.length && i < 2500; i++) {
        var p = ps[i], t = (p.textContent || '').trim();
        if (t.length < 50 || (p.closest && p.closest('nav,aside,footer,header,form'))) continue;
        var s = 1 + Math.min(t.split(/[,،]/).length, 6) + Math.min(Math.floor(t.length / 100), 4);
        var par = p.parentElement;
        if (par) { map.set(par, (map.get(par) || 0) + s); var g = par.parentElement; if (g) map.set(g, (map.get(g) || 0) + s / 2); }
      }
      var best = null, bs = 0;
      map.forEach(function (v, el) {
        if (el === document.body || el === document.documentElement) return;
        v = v * (1 - Math.min(linkDensity(el), 0.9));
        if (v > bs) { bs = v; best = el; }
      });
      return bs >= 12 ? best : null;
    }

    var root = pick();
    if (!root) return 'none';

    function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }

    function walk(src, dst) {
      for (var n = src.firstChild; n; n = n.nextSibling) {
        if (n.nodeType === 3) { dst.appendChild(document.createTextNode(n.nodeValue)); continue; }
        if (n.nodeType !== 1) continue;
        var tag = n.tagName;
        if (SKIP[tag] || n.hidden || n.getAttribute('aria-hidden') === 'true') continue;
        if (n !== root) {
          var id = (n.id || '') + ' ' + (typeof n.className === 'string' ? n.className : '');
          if (JUNK.test(id) && (n.textContent || '').length < 600) continue;
        }
        if (tag === 'IMG') {
          var u = abs(n.currentSrc || n.src || n.getAttribute('data-src') || n.getAttribute('data-lazy-src') || '');
          if (!/^https?:/.test(u) || (n.naturalWidth && n.naturalWidth < 80)) continue;
          var im = document.createElement('img');
          im.src = u; im.alt = n.alt || ''; im.loading = 'lazy';
          dst.appendChild(im);
          continue;
        }
        if (ALLOW[tag]) {
          var e = document.createElement(tag.toLowerCase());
          if (tag === 'A') { var h = n.href; if (/^https?:/.test(h)) { e.href = h; e.rel = 'noopener'; } }
          walk(n, e);
          if (tag !== 'BR' && !e.firstChild) continue;
          dst.appendChild(e);
        } else walk(n, dst);
      }
    }

    var article = document.createElement('article');
    article.setAttribute('dir', 'auto');
    var h1 = root.querySelector('h1') || document.querySelector('h1');
    var title = document.createElement('h1');
    title.textContent = (h1 && h1.textContent.trim()) || document.title || '';
    article.appendChild(title);
    var meta = document.createElement('div');
    meta.className = 'meta'; meta.textContent = location.hostname.replace(/^www\./, '');
    article.appendChild(meta);
    walk(root, article);
    // الاسم المكرر: نحذف h1 الثاني إن كان نفس العنوان
    var hs = article.querySelectorAll('h1');
    for (var k = 1; k < hs.length; k++) if (hs[k].textContent.trim() === title.textContent.trim()) hs[k].remove();
    if ((article.textContent || '').length < 300) return 'none';

    var ar = /^ar/i.test(document.documentElement.lang || navigator.language || '');
    var host = document.createElement('div');
    host.id = '__nova_reader';
    host.style.cssText = 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:2147483647;overflow:auto;-webkit-overflow-scrolling:touch;';
    var sh = host.attachShadow({ mode: 'open' });
    var css =
      ':host{--bg:#fff;--fg:#1d1d1f;--mut:#6b6b70;--ln:#3d5afe;--bar:rgba(255,255,255,.92);--fs:19px}' +
      ':host(.dark){--bg:#121214;--fg:#e8e8ea;--mut:#9a9aa0;--ln:#8fa3ff;--bar:rgba(18,18,20,.92)}' +
      ':host(.sepia){--bg:#f4ecd8;--fg:#3b3226;--mut:#7b6f5c;--ln:#8a5a1f;--bar:rgba(244,236,216,.92)}' +
      '.wrap{min-height:100%;background:var(--bg);color:var(--fg)}' +
      '.bar{position:sticky;top:0;display:flex;gap:6px;align-items:center;padding:8px 10px;background:var(--bar);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);border-bottom:1px solid rgba(128,128,128,.25)}' +
      '.bar button{all:unset;box-sizing:border-box;min-width:44px;height:40px;padding:0 10px;display:flex;align-items:center;justify-content:center;border-radius:20px;font:600 16px sans-serif;color:var(--fg);background:rgba(128,128,128,.16)}' +
      '.bar .sp{flex:1}' +
      'article{max-width:720px;margin:0 auto;padding:18px 20px 90px;font:var(--fs)/1.85 Georgia,"Noto Naskh Arabic","Noto Serif",serif;overflow-wrap:anywhere}' +
      'h1{font:700 1.5em/1.4 sans-serif;margin:.3em 0 .2em}h2,h3,h4{font-family:sans-serif;line-height:1.5;margin:1.4em 0 .4em}' +
      '.meta{color:var(--mut);font:14px sans-serif;margin-bottom:1.2em}' +
      'p{margin:0 0 1.1em}a{color:var(--ln)}img{max-width:100%;height:auto;border-radius:10px;display:block;margin:1em auto}' +
      'figure{margin:1em 0}figcaption{color:var(--mut);font:14px sans-serif;text-align:center}' +
      'blockquote{margin:1em 0;padding:.2em 1em;border-inline-start:4px solid var(--ln);color:var(--mut)}' +
      'pre{background:rgba(128,128,128,.14);padding:12px;border-radius:10px;overflow:auto;font:14px/1.5 monospace;direction:ltr;text-align:left}' +
      'code{font-family:monospace;font-size:.9em}table{border-collapse:collapse;display:block;overflow:auto}td,th{border:1px solid rgba(128,128,128,.4);padding:6px 10px}';
    try { var sheet = new CSSStyleSheet(); sheet.replaceSync(css); sh.adoptedStyleSheets = [sheet]; } catch (e) { }

    var wrap = document.createElement('div'); wrap.className = 'wrap';
    var bar = document.createElement('div'); bar.className = 'bar';
    function btn(txt, fn, label) { var b = document.createElement('button'); b.textContent = txt; b.setAttribute('aria-label', label); b.addEventListener('click', fn); return b; }
    var sp = document.createElement('div'); sp.className = 'sp';

    var size = 19, modes = ['', 'sepia', 'dark'], mi = 0;
    var dark = window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches;
    if (dark) { mi = 2; host.className = 'dark'; }
    function setSize(d) { size = Math.max(14, Math.min(34, size + d)); host.style.setProperty('--fs', size + 'px'); }

    var prevOv = document.documentElement.style.overflow, closed = false, pushed = false;
    function cleanup() { closed = true; window.removeEventListener('popstate', onPop); host.remove(); document.documentElement.style.overflow = prevOv; }
    function onPop() { if (!closed) cleanup(); }
    function close() {
      if (closed) return;
      cleanup();
      if (pushed) { try { history.back(); } catch (e) { } }
    }
    host.__close = close;

    bar.appendChild(btn('✕', close, ar ? 'إغلاق' : 'Close'));
    bar.appendChild(sp);
    bar.appendChild(btn('A−', function () { setSize(-2); }, ar ? 'تصغير الخط' : 'Smaller'));
    bar.appendChild(btn('A+', function () { setSize(2); }, ar ? 'تكبير الخط' : 'Larger'));
    bar.appendChild(btn('◐', function () { mi = (mi + 1) % 3; host.className = modes[mi]; }, ar ? 'السمة' : 'Theme'));
    wrap.appendChild(bar); wrap.appendChild(article); sh.appendChild(wrap);

    document.documentElement.style.overflow = 'hidden';
    (document.body || document.documentElement).appendChild(host);
    // زر الرجوع في أندرويد يغلق القارئ بدل مغادرة الصفحة
    try { history.pushState({ novaReader: 1 }, ''); pushed = true; window.addEventListener('popstate', onPop); } catch (e) { }
    return 'ok';
  } catch (e) { return 'none'; }
})();
