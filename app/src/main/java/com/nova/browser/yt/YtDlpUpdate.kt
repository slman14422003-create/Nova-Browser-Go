package com.nova.browser

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object YtDlpUpdate {
    private val repos = listOf("yt-dlp/yt-dlp-nightly-builds", "yt-dlp/yt-dlp")
    private const val MIN_SIZE = 1_000_000L
    private const val MAX_SIZE = 80_000_000L
    @Volatile private var cached: File? = null

    private val proxy: String get() = BuildConfig.YT_PROXY.trim().trimEnd('/').takeIf { it.startsWith("https://") } ?: ""

    private fun log(m: String) = YtLog.add("yt-dlp-update: $m")

    fun locate(app: Context): File? {
        cached?.takeIf { it.isFile && it.length() > MIN_SIZE / 2 }?.let { return it }
        var found: File? = null
        for (root in listOf(app.noBackupFilesDir, app.filesDir)) {
            root.walkTopDown().maxDepth(6).forEach { f ->
                if (found == null && f.isFile && f.name == "yt-dlp" && f.length() > MIN_SIZE / 2) found = f
            }
            if (found != null) break
        }
        if (found == null) {
            val names = runCatching { app.noBackupFilesDir.walkTopDown().maxDepth(3).take(40).joinToString(",") { it.name } }.getOrDefault("")
            log("binary not found; noBackupFilesDir: $names")
        }
        cached = found
        return found
    }

    private fun sigOf(f: File) = "${f.length()}:${f.lastModified()}"

    fun isCurrent(app: Context): Boolean {
        val f = locate(app) ?: return true
        return app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).getString("sig", null) == sigOf(f)
    }

    private fun connect(urls: List<String>, accept: String?): HttpURLConnection {
        var last: Exception = IOException("no url")
        for (u in urls) {
            try {
                val cn = URL(u).openConnection() as HttpURLConnection
                cn.connectTimeout = 10_000
                cn.readTimeout = 30_000
                cn.instanceFollowRedirects = true
                cn.setRequestProperty("User-Agent", "nova-go-ytdlp-update")
                if (accept != null) cn.setRequestProperty("Accept", accept)
                if (cn.responseCode == 200) return cn
                last = IOException("HTTP ${cn.responseCode} ${u.substringBefore('?').take(80)}")
                cn.disconnect()
            } catch (e: Exception) { last = e }
        }
        throw IOException(last.message ?: last.javaClass.simpleName, last)
    }

    private fun text(urls: List<String>, accept: String? = null): String {
        val cn = connect(urls, accept)
        try { return cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
    }

    private fun viaProxy(url: String): List<String> {
        val p = proxy
        val enc = URLEncoder.encode(url, "UTF-8")
        return if (p.isNotEmpty()) listOf("$p/dl?u=$enc", url) else listOf(url)
    }

    private fun release(repo: String): JSONObject {
        val p = proxy
        val direct = "https://api.github.com/repos/$repo/releases/latest"
        val urls = if (p.isNotEmpty()) listOf("$p/gh/repos/$repo/releases/latest", direct) else listOf(direct)
        return JSONObject(text(urls, "application/vnd.github+json"))
    }

    private fun assetUrl(rel: JSONObject, name: String): String? {
        val a = rel.optJSONArray("assets") ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("name") == name) return o.optString("browser_download_url").ifBlank { null }
        }
        return null
    }

    private fun assetDigest(rel: JSONObject, name: String): String? {
        val a = rel.optJSONArray("assets") ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("name") == name) return o.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 }
        }
        return null
    }

    private fun expectedSha(rel: JSONObject): String {
        assetUrl(rel, "SHA2-256SUMS")?.let { u ->
            val t = runCatching { text(viaProxy(u)) }.getOrNull()
            t?.lineSequence()?.map { it.trim().split(Regex("\\s+")) }?.firstOrNull { it.size >= 2 && it[1].removePrefix("*") == "yt-dlp" }?.let { return it[0].lowercase() }
        }
        return assetDigest(rel, "yt-dlp") ?: throw IOException("no checksum for yt-dlp asset")
    }

    private fun download(url: String, dst: File): String {
        val cn = connect(viaProxy(url), null)
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        try {
            cn.inputStream.use { i -> dst.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = i.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r); md.update(buf, 0, r); n += r
                    if (n > MAX_SIZE) throw IOException("file too large")
                }
            } }
        } finally { cn.disconnect() }
        if (n < MIN_SIZE) throw IOException("file too small: $n")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun looksLikeZipApp(f: File): Boolean {
        val head = f.inputStream().use { s -> ByteArray(256).also { s.read(it) } }
        val str = String(head, Charsets.ISO_8859_1)
        return str.startsWith("#!") && str.contains("PK\u0003\u0004")
    }

    @Synchronized
    fun update(app: Context): String {
        val bin = locate(app) ?: throw IOException("yt-dlp binary not found")
        val sp = app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val errs = ArrayList<String>()
        for (repo in repos) {
            val tmp = File(bin.parentFile, "yt-dlp.new")
            try {
                val rel = release(repo)
                val tag = rel.optString("tag_name")
                if (tag.isBlank()) throw IOException("no tag")
                if (sp.getString("tag", "") == tag && sp.getString("sig", null) == sigOf(bin)) return "$repo $tag (current)"
                val url = assetUrl(rel, "yt-dlp") ?: throw IOException("no yt-dlp asset")
                val want = expectedSha(rel)
                val got = download(url, tmp)
                if (got != want) throw IOException("checksum mismatch")
                if (!looksLikeZipApp(tmp)) throw IOException("not a yt-dlp zipapp")
                runCatching { bin.copyTo(File(bin.parentFile, "yt-dlp.bak"), overwrite = true) }
                if (!tmp.renameTo(bin)) { tmp.copyTo(bin, overwrite = true); tmp.delete() }
                bin.setReadable(true, false)
                sp.edit().putString("tag", tag).putString("sig", sigOf(bin)).apply()
                log("updated from $repo to $tag")
                return "$repo $tag"
            } catch (e: Throwable) {
                runCatching { tmp.delete() }
                val m = "$repo: " + (e.message ?: e.javaClass.simpleName)
                log("failed $m")
                errs.add(m)
            }
        }
        throw IOException(errs.joinToString("; ").take(400))
    }
}
