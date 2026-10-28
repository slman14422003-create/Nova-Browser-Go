(function () {
  'use strict';
  try {
    if (window.__novaModern) return;
    window.__novaModern = 1;
    function def(o, k, v) { try { if (!(k in o)) Object.defineProperty(o, k, { value: v, writable: true, configurable: true }); } catch (e) {} }
    function at(i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return i < 0 || i >= this.length ? undefined : this[i]; }
    def(Array.prototype, 'at', at);
    def(String.prototype, 'at', at);
    def(Object, 'hasOwn', function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); });
    def(String.prototype, 'replaceAll', function (a, b) {
      if (a instanceof RegExp) { if (!a.global) throw new TypeError('replaceAll must be called with a global RegExp'); return this.replace(a, b); }
      return this.split(String(a)).join(typeof b === 'function' ? b(a) : String(b));
    });
    def(Array.prototype, 'findLast', function (f, t) { for (var i = this.length - 1; i >= 0; i--) if (f.call(t, this[i], i, this)) return this[i]; });
    def(Array.prototype, 'findLastIndex', function (f, t) { for (var i = this.length - 1; i >= 0; i--) if (f.call(t, this[i], i, this)) return i; return -1; });
    def(Array.prototype, 'toSorted', function (f) { return this.slice().sort(f); });
    def(Array.prototype, 'toReversed', function () { return this.slice().reverse(); });
    def(Array.prototype, 'toSpliced', function () { var a = this.slice(); a.splice.apply(a, arguments); return a; });
    def(Array.prototype, 'with', function (i, v) { var a = this.slice(); i = Math.trunc(i) || 0; if (i < 0) i += a.length; if (i < 0 || i >= a.length) throw new RangeError('Invalid index'); a[i] = v; return a; });
    def(Object, 'groupBy', function (it, f) { var o = Object.create(null), i = 0; for (var x of it) { var k = f(x, i++); (o[k] || (o[k] = [])).push(x); } return o; });
    def(Promise, 'withResolvers', function () { var res, rej, p = new Promise(function (a, b) { res = a; rej = b; }); return { promise: p, resolve: res, reject: rej }; });
    def(window, 'structuredClone', function (v) { return v === undefined ? v : JSON.parse(JSON.stringify(v)); });
    def(window, 'queueMicrotask', function (f) { Promise.resolve().then(f); });
    def(window, 'requestIdleCallback', function (f, o) {
      var s = Date.now();
      return setTimeout(function () { f({ didTimeout: false, timeRemaining: function () { return Math.max(0, 50 - (Date.now() - s)); } }); }, 1);
    });
    def(window, 'cancelIdleCallback', function (id) { clearTimeout(id); });
    if (window.AbortSignal) {
      def(AbortSignal, 'timeout', function (ms) { var c = new AbortController(); setTimeout(function () { c.abort(); }, ms); return c.signal; });
      def(AbortSignal, 'abort', function () { var c = new AbortController(); c.abort(); return c.signal; });
    }
    if (window.URL) def(URL, 'canParse', function (u, b) { try { new URL(u, b); return true; } catch (e) { return false; } });
    if (window.crypto && !crypto.randomUUID && crypto.getRandomValues) {
      def(crypto, 'randomUUID', function () {
        var b = crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
        var h = Array.prototype.map.call(b, function (x) { return (x + 256).toString(16).slice(1); }).join('');
        return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
      });
    }
  } catch (e) {}
})();
