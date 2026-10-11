// Cloudflare Worker: وسيط لاستخراج يوتيوب (Piped). ينشر على Cloudflare (شبكة غير محجوبة) ويستدعي خوادم Piped نيابةً عن التطبيق.
// الاستخدام: GET https://<اسمك>.workers.dev/streams/<VIDEO_ID>
const UPSTREAMS = [
  "https://pipedapi.kavin.rocks",
  "https://pipedapi.adminforge.de",
  "https://api.piped.private.coffee",
  "https://pipedapi.leptons.xyz",
  "https://pipedapi.nosebs.ru",
  "https://piped-api.privacy.com.de",
  "https://pipedapi.owo.si",
  "https://pipedapi.ducks.party",
];
const JSON_H = { "content-type": "application/json", "access-control-allow-origin": "*" };

export default {
  async fetch(req) {
    const m = new URL(req.url).pathname.match(/^\/streams\/([A-Za-z0-9_-]{11})$/);
    if (!m) return new Response("nova yt proxy ok", { status: 200 });
    const errors = [];
    for (const base of UPSTREAMS) {
      try {
        const r = await fetch(`${base}/streams/${m[1]}`, { headers: { "User-Agent": "Mozilla/5.0", Accept: "application/json" }, signal: AbortSignal.timeout(8000) });
        const t = await r.text();
        if (r.ok && t.includes('"videoStreams"')) return new Response(t, { headers: JSON_H });
        errors.push(base + " -> " + r.status);
      } catch (e) { errors.push(base + " -> " + e.message); }
    }
    return new Response(JSON.stringify({ error: "all upstreams failed", message: errors.slice(-2).join(" | ") }), { status: 502, headers: JSON_H });
  },
};
