const JSON_H = { "content-type": "application/json", "access-control-allow-origin": "*" };
const GH_HOSTS = ["github.com", "githubusercontent.com"];
const okHost = (h) => GH_HOSTS.some((d) => h === d || h.endsWith("." + d));

export default {
  async fetch(req) {
    const url = new URL(req.url);
    let m;

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
