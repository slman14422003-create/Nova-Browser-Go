// Nova Go proxy worker
//   GET /streams/<VIDEO_ID>                         YouTube extraction through Piped
//   GET /gh/repos/<owner>/<repo>/releases/latest    update check (api.github.com)
//   GET /dl?u=<encoded GitHub url>                  APK download (github.com / githubusercontent.com only)
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
const GH_HOSTS = ["github.com", "githubusercontent.com"];
const okHost = (h) => GH_HOSTS.some((d) => h === d || h.endsWith("." + d));

// googlevideo URLs returned by Piped are signed for the Piped server IP.
// Opening them from the phone gives HTTP 403, so route them through the
// instance's own media proxy (same behaviour as the Piped web frontend).
function viaProxy(u, proxyUrl) {
  try {
    const src = new URL(u);
    if (!src.hostname.endsWith(".googlevideo.com")) return u;
    const px = new URL(proxyUrl);
    src.searchParams.set("host", src.hostname);
    src.protocol = px.protocol;
    src.host = px.host;
    return src.toString();
  } catch (e) {
    return u;
  }
}

function rewriteStreams(data) {
  const proxyUrl = data.proxyUrl;
  if (!proxyUrl) return data;
  for (const k of ["videoStreams", "audioStreams"]) {
    if (Array.isArray(data[k])) {
      for (const s of data[k]) if (s && s.url) s.url = viaProxy(s.url, proxyUrl);
    }
  }
  return data;
}

export default {
  async fetch(req) {
    const url = new URL(req.url);
    let m;

    if ((m = url.pathname.match(/^\/streams\/([A-Za-z0-9_-]{11})$/))) {
      const errors = [];
      for (const base of UPSTREAMS) {
        try {
          const r = await fetch(`${base}/streams/${m[1]}`, {
            headers: { "User-Agent": "Mozilla/5.0", Accept: "application/json" },
            signal: AbortSignal.timeout(8000),
          });
          const t = await r.text();
          if (r.ok && t.includes('"videoStreams"')) {
            let out = t;
            try {
              const data = JSON.parse(t);
              if (!data.proxyUrl) data.proxyUrl = base.replace("pipedapi", "pipedproxy");
              out = JSON.stringify(rewriteStreams(data));
            } catch (e) {}
            return new Response(out, { headers: JSON_H });
          }
          errors.push(base + " -> " + r.status);
        } catch (e) {
          errors.push(base + " -> " + e.message);
        }
      }
      return new Response(
        JSON.stringify({ error: "all upstreams failed", message: errors.slice(-2).join(" | ") }),
        { status: 502, headers: JSON_H }
      );
    }

    if ((m = url.pathname.match(/^\/gh\/(repos\/[\w.-]+\/[\w.-]+\/releases\/latest)$/))) {
      const r = await fetch("https://api.github.com/" + m[1], {
        headers: { "User-Agent": "nova-go-proxy", Accept: "application/vnd.github+json" },
      });
      return new Response(r.body, { status: r.status, headers: JSON_H });
    }

    if (url.pathname === "/dl") {
      let target;
      try {
        target = new URL(url.searchParams.get("u") || "");
      } catch (e) {
        return new Response("bad url", { status: 400 });
      }
      if (target.protocol !== "https:" || !okHost(target.hostname)) {
        return new Response("forbidden", { status: 403 });
      }
      const r = await fetch(target.toString(), {
        headers: { "User-Agent": "nova-go-proxy" },
        redirect: "follow",
      });
      const h = new Headers();
      for (const k of ["content-type", "content-length", "content-disposition"]) {
        if (r.headers.get(k)) h.set(k, r.headers.get(k));
      }
      return new Response(r.body, { status: r.status, headers: h });
    }

    return new Response("nova proxy ok", { status: 200 });
  },
};
