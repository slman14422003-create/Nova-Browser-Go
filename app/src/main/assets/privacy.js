(function () {
  'use strict';
  if (window.__nv) return;
  try { Object.defineProperty(window, '__nv', { value: 1 }); } catch (e) {}
  var seed = __SEED__;
  var SITE_LANG = '__LANG__';
  function safe(f) { try { f(); } catch (e) {} }
  function hash(s) { var x = 2166136261; for (var i = 0; i < s.length; i++) { x ^= s.charCodeAt(i); x = Math.imul(x, 16777619); } return x >>> 0; }
  var rv = 0;
  safe(function () { var a = new Uint32Array(2); crypto.getRandomValues(a); rv = a[0] ^ a[1]; });
  // حالة المولّد عشوائية في كل سياق (صفحة أو إطار): نفس الاستدعاء في سياقين مختلفين يعطي نتيجتين مختلفتين
  var st = (hash(String(location.hostname)) ^ seed ^ rv ^ ((Math.random() * 4294967296) >>> 0)) >>> 0;
  // المولّد يتقدّم مع كل استدعاء: كل قراءة (Canvas/WebGL/صوت) تعطي نتيجة مختلفة قليلاً
  function rnd() { st = (Math.imul(st, 1664525) + 1013904223) >>> 0; return st / 4294967296; }
  // مواقع جوجل/يوتيوب: لا نعدّل Canvas/WebGL/الصوت/قياس الخطوط فيها. هذه الخطافات تُبطئ صفحاتها الثقيلة (وضع الذكاء الاصطناعي، المشغّل)
  // ولا فائدة منها هناك أصلاً لأن جوجل تعرفك عبر حسابك. تبقى حماية الـ Navigator الخفيفة.
  var LIGHT = /(^|\.)(google\.[a-z.]+|youtube\.com|gstatic\.com|googleusercontent\.com|ytimg\.com|googlevideo\.com)$/.test(String(location.hostname));
  function def(o, p, v) { try { Object.defineProperty(o, p, { get: function () { return v; }, configurable: true }); } catch (e) {} }
  function noisePx(a) {
    var n = a.length, flips = 0, first = -1;
    for (var i = 0; i < n; i += 4) {
      if (a[i + 3] === 0) continue;
      if (first < 0) first = i;
      if (rnd() < 0.015) { a[i + ((rnd() * 3) | 0)] ^= 1; flips++; }
    }
    if (!flips && first >= 0) a[first] ^= 1;   // نضمن تغييراً واحداً على الأقل
  }
  function jitter(a) { var n = Math.min(a.length, 8192); for (var i = 0; i < n; i += 4) { if (rnd() < 0.12) a[i + ((rnd() * 3) | 0)] ^= 1; } }

  // ---------- Navigator ----------
  safe(function () {
    var N = Navigator.prototype;
    def(N, 'hardwareConcurrency', 4);
    def(N, 'deviceMemory', 4);
    def(N, 'doNotTrack', '1');
    def(N, 'globalPrivacyControl', true);
    if (!LIGHT) { def(N, 'getBattery', undefined); def(N, 'connection', undefined); }
    var lang = SITE_LANG || navigator.language || 'en-US';
    if (SITE_LANG) def(N, 'language', lang);
    def(N, 'languages', Object.freeze([lang]));
  });
  safe(function () {
    var u = navigator.userAgentData;
    if (u) {
      var maj = ((navigator.userAgent.match(/Chrome\/(\d+)/) || [])[1]) || '120';
      Object.getPrototypeOf(u).getHighEntropyValues = function () {
        var b = this.brands || [];
        return Promise.resolve({
          brands: b, mobile: this.mobile, platform: this.platform, architecture: '', bitness: '', model: '',
          platformVersion: '10.0.0', uaFullVersion: maj + '.0.0.0', wow64: false,
          fullVersionList: b.map(function (x) { return { brand: x.brand, version: maj + '.0.0.0' }; })
        });
      };
    }
  });

  if (LIGHT) return;

  // ---------- Canvas 2D ----------
  var kind = new WeakMap();
  var getCtx = HTMLCanvasElement.prototype.getContext;
  var gid = CanvasRenderingContext2D.prototype.getImageData;
  var pid = CanvasRenderingContext2D.prototype.putImageData;
  var tdu = HTMLCanvasElement.prototype.toDataURL;
  var tbl = HTMLCanvasElement.prototype.toBlob;
  safe(function () {
    HTMLCanvasElement.prototype.getContext = function (t) {
      var c = getCtx.apply(this, arguments);
      if (c) kind.set(this, String(t));
      return c;
    };
    CanvasRenderingContext2D.prototype.getImageData = function () { var d = gid.apply(this, arguments); jitter(d.data); return d; };
  });
  // نسخة منقّحة من أي Canvas (2D أو WebGL) تُستخدم عند التصدير
  function noisyCopy(cv) {
    try {
      var w = cv.width, h = cv.height;
      if (!w || !h) return null;
      var t = document.createElement('canvas'); t.width = w; t.height = h;
      var c = getCtx.call(t, '2d'); c.drawImage(cv, 0, 0);
      var rw = w, rh = h;
      if (w * h > 600000) { rw = Math.min(w, 512); rh = Math.min(h, 512); }   // لوحات كبيرة: جزء فقط للسرعة
      var d = gid.call(c, 0, 0, rw, rh); noisePx(d.data); pid.call(c, d, 0, 0);
      return t;
    } catch (e) { return null; }
  }
  function isKnown(cv) { return kind.has(cv); }
  safe(function () {
    HTMLCanvasElement.prototype.toDataURL = function () {
      var t = isKnown(this) ? noisyCopy(this) : null;
      return tdu.apply(t || this, arguments);
    };
    HTMLCanvasElement.prototype.toBlob = function () {
      var t = isKnown(this) ? noisyCopy(this) : null;
      return tbl.apply(t || this, arguments);
    };
  });
  // OffscreenCanvas على الخيط الرئيسي
  safe(function () {
    if (!window.OffscreenCanvas) return;
    var ogid = OffscreenCanvasRenderingContext2D.prototype.getImageData;
    OffscreenCanvasRenderingContext2D.prototype.getImageData = function () { var d = ogid.apply(this, arguments); jitter(d.data); return d; };
    var cb = OffscreenCanvas.prototype.convertToBlob;
    OffscreenCanvas.prototype.convertToBlob = function () {
      try { var c = this.getContext('2d'); if (c) { var d = ogid.call(c, 0, 0, Math.min(this.width, 512), Math.min(this.height, 512)); noisePx(d.data); c.putImageData(d, 0, 0); } } catch (e) {}
      return cb.apply(this, arguments);
    };
  });

  // الخطوط: تغيير بالغ الصغر في قياس النص
  safe(function () {
    var mt = CanvasRenderingContext2D.prototype.measureText;
    CanvasRenderingContext2D.prototype.measureText = function () {
      var r = mt.apply(this, arguments);
      try {
        var w = r.width * (1 + (rnd() - 0.5) * 0.0002);
        return new Proxy(r, { get: function (t, p) { if (p === 'width') return w; var v = t[p]; return typeof v === 'function' ? v.bind(t) : v; } });
      } catch (e) { return r; }
    };
  });

  // ---------- WebGL ----------
  [window.WebGLRenderingContext, window.WebGL2RenderingContext].forEach(function (C) {
    if (!C) return;
    safe(function () {
      var gp = C.prototype.getParameter;
      C.prototype.getParameter = function (p) {
        if (p === 37445) return 'Google Inc.';
        if (p === 37446) return 'ANGLE (Generic Renderer)';
        return gp.apply(this, arguments);
      };
      var rp = C.prototype.readPixels;
      C.prototype.readPixels = function () {
        var r = rp.apply(this, arguments);
        for (var i = 0; i < arguments.length; i++) {
          var a = arguments[i];
          if (a && a.BYTES_PER_ELEMENT === 1 && a.length) { jitter(a); break; }
        }
        return r;
      };
    });
  });

  // ---------- الصوت ----------
  safe(function () {
    if (!window.AudioBuffer) return;
    function audioNoise(d) { var n = Math.min(d.length, 20000); for (var i = 0; i < n; i += 97) d[i] += (rnd() - 0.5) * 1e-7; }
    var gcd = AudioBuffer.prototype.getChannelData;
    var seen = new WeakSet();
    AudioBuffer.prototype.getChannelData = function () {
      var d = gcd.apply(this, arguments);
      if (!seen.has(d)) { seen.add(d); audioNoise(d); }
      return d;
    };
    var cfc = AudioBuffer.prototype.copyFromChannel;
    AudioBuffer.prototype.copyFromChannel = function (dest) { var r = cfc.apply(this, arguments); audioNoise(dest); return r; };
  });
  safe(function () {
    if (!window.AnalyserNode) return;
    var gff = AnalyserNode.prototype.getFloatFrequencyData;
    AnalyserNode.prototype.getFloatFrequencyData = function (a) { var r = gff.apply(this, arguments); for (var i = 0; i < a.length; i += 7) a[i] += (rnd() - 0.5) * 1e-4; return r; };
    var gft = AnalyserNode.prototype.getFloatTimeDomainData;
    AnalyserNode.prototype.getFloatTimeDomainData = function (a) { var r = gft.apply(this, arguments); for (var i = 0; i < a.length; i += 7) a[i] += (rnd() - 0.5) * 1e-7; return r; };
  });
})();
