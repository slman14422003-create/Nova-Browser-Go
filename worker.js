// Nova Go proxy worker
//   GET /streams/<VIDEO_ID>   extraction from YouTube (InnerTube) done from Cloudflare, Piped as fallback
//   GET /gv?u=<googlevideo url>&c=<client>   media proxy (same IP that extracted, so signed links work)
//   GET /gh/repos/<owner>/<repo>/releases/latest
//   GET /dl?u=<encoded GitHub url>
const PIPED = [
  "https://pipedapi.kavin.rocks",
  "https://pipedapi.adminforge.de",
  "https://api.piped.private.coffee",
  "https://pipedapi.leptons.xyz",
  "https://pipedapi.nosebs.ru",
  "https://piped-api.privacy.com.de",
  "https://pipedapi.owo.si",
  "https://pipedapi.ducks.party",
];

const CLIENTS = [
  {
    name: "ANDROID_VR", ver: "1.60.19", id: "28",
    ua: "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    ctx: { deviceMake: "Oculus", deviceModel: "Quest 3", osName: "Android", osVersion: "12L", androidSdkVersion: 32 },
  },
  {
    name: "IOS", ver: "20.10.4", id: "5",
    ua: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
    ctx: { deviceMake: "Apple", deviceModel: "iPhone16,2", osName: "iPhone", osVersion: "18.3.2.22D82" },
  },
  {
    name: "TVHTML5_SIMPLY_EMBEDDED_PLAYER", ver: "2.0", id: "85",
    ua: "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
    ctx: {}, embed: true,
  },
];
const UA_BY_NAME = Object.fromEntries(CLIENTS.map((c) => [c.name, c.ua]));

const JSON_H = { "content-type": "application/json", "access-control-allow-origin": "*" };
const GH_HOSTS = ["github.com", "githubusercontent.com"];
const okHost = (h) => GH_HOSTS.some((d) => h === d || h.endsWith("." + d));
const isGv = (h) => h === "googlevideo.com" || h.endsWith(".googlevideo.com");

async function innertube(id, c) {
  const client = { clientName: c.name, clientVersion: c.ver, hl: "en", gl: "US", ...c.ctx };
  const context = { client };
  if (c.embed) context.thirdParty = { embedUrl: "https://www.youtube.com/" };
  const r = await fetch("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "user-agent": c.ua,
      "x-youtube-client-name": c.id,
      "x-youtube-client-version": c.ver,
      origin: "https://www.youtube.com",
    },
    body: JSON.stringify({ videoId: id, contentCheckOk: true, racyCheckOk: true, context }),
    signal: AbortSignal.timeout(9000),
  });
  if (!r.ok) throw new Error(c.name + " HTTP " + r.status);
  const j = await r.json();
  const st = j.playabilityStatus && j.playabilityStatus.status;
  if (st !== "OK") throw new Error(c.name + " " + st + " " + ((j.playabilityStatus && j.playabilityStatus.reason) || "").slice(0, 60));
  return j;
}

function viaWorker(u, origin, client) {
  return origin + "/gv?c=" + encodeURIComponent(client) + "&u=" + encodeURIComponent(u);
}

function shape(j, origin, client) {
  const sd = j.streamingData || {};
  const vs = [];
  const au = [];
  for (const o of sd.formats || []) {
    if (!o.url || !String(o.mimeType).startsWith("video/mp4")) continue;
    vs.push({ url: viaWorker(o.url, origin, client), mimeType: o.mimeType, quality: o.qualityLabel || "video", videoOnly: false });
  }
  for (const o of sd.adaptiveFormats || []) {
    if (!o.url) continue;
    const m = String(o.mimeType);
    if (m.startsWith("video/mp4") && m.includes("avc1")) {
      vs.push({ url: viaWorker(o.url, origin, client), mimeType: m, quality: o.qualityLabel || "video", videoOnly: true });
    } else if (m.startsWith("audio/")) {
      au.push({ url: viaWorker(o.url, origin, client), mimeType: m, bitrate: o.bitrate || 0 });
    }
  }
  const vd = j.videoDetails || {};
  return {
    title: vd.title || "", uploader: vd.author || "",
    duration: parseInt(vd.lengthSeconds || "0", 10) || 0,
    videoStreams: vs, audioStreams: au,
  };
}

function viaPipedProxy(u, proxyUrl) {
  try {
    const src = new URL(u);
    if (!isGv(src.hostname)) return u;
    const px = new URL(proxyUrl);
    src.searchParams.set("host", src.hostname);
    src.protocol = px.protocol;
    src.host = px.host;
    return src.toString();
  } catch (e) {
    return u;
  }
}

async function fromPiped(id, errors) {
  for (const base of PIPED) {
    try {
      const r = await fetch(`${base}/streams/${id}`, {
        headers: { "User-Agent": "Mozilla/5.0", Accept: "application/json" },
        signal: AbortSignal.timeout(6000),
      });
      const t = await r.text();
      if (r.ok && t.includes('"videoStreams"')) {
        const data = JSON.parse(t);
        const px = data.proxyUrl || base.replace("pipedapi", "pipedproxy");
        for (const k of ["videoStreams", "audioStreams"]) {
          for (const s of data[k] || []) if (s && s.url) s.url = viaPipedProxy(s.url, px);
        }
        return data;
      }
      errors.push(base + " -> " + r.status);
    } catch (e) {
      errors.push(base + " -> " + e.message);
    }
  }
  return null;
}

async function handleStreams(id, origin) {
  const errors = [];
  for (const c of CLIENTS) {
    try {
      const data = shape(await innertube(id, c), origin, c.name);
      if (data.videoStreams.length || data.audioStreams.length) {
        return new Response(JSON.stringify(data), { headers: JSON_H });
      }
      errors.push(c.name + " no formats");
    } catch (e) {
      errors.push(e.message);
    }
  }
  const p = await fromPiped(id, errors);
  if (p) return new Response(JSON.stringify(p), { headers: JSON_H });
  return new Response(JSON.stringify({ error: "all upstreams failed", message: errors.slice(0, 4).join(" | ") }), { status: 502, headers: JSON_H });
}

async function handleGv(url, req) {
  let target;
  try {
    target = new URL(url.searchParams.get("u") || "");
  } catch (e) {
    return new Response("bad url", { status: 400 });
  }
  if (target.protocol !== "https:" || !isGv(target.hostname)) return new Response("forbidden", { status: 403 });
  const h = new Headers({ "User-Agent": UA_BY_NAME[url.searchParams.get("c") || ""] || CLIENTS[0].ua, Origin: "https://www.youtube.com", Referer: "https://www.youtube.com/" });
  const range = req.headers.get("range");
  if (range) h.set("Range", range);
  const r = await fetch(target.toString(), { headers: h, redirect: "follow" });
  const out = new Headers({ "access-control-allow-origin": "*" });
  for (const k of ["content-type", "content-length", "content-range", "accept-ranges", "content-disposition"]) {
    if (r.headers.get(k)) out.set(k, r.headers.get(k));
  }
  return new Response(r.body, { status: r.status, headers: out });
}

export default {
  async fetch(req) {
    const url = new URL(req.url);
    let m;

    if ((m = url.pathname.match(/^\/streams\/([A-Za-z0-9_-]{11})$/))) return handleStreams(m[1], url.origin);
    if (url.pathname === "/gv") return handleGv(url, req);

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
      if (target.protocol !== "https:" || !okHost(target.hostname)) return new Response("forbidden", { status: 403 });
      const r = await fetch(target.toString(), { headers: { "User-Agent": "nova-go-proxy" }, redirect: "follow" });
      const h = new Headers();
      for (const k of ["content-type", "content-length", "content-disposition"]) {
        if (r.headers.get(k)) h.set(k, r.headers.get(k));
      }
      return new Response(r.body, { status: r.status, headers: h });
    }

    return new Response("nova proxy ok", { status: 200 });
  },
};
