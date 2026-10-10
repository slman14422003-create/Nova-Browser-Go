package com.nova.browser

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** عميل شبكة مشترك (OkHttp): HTTP/2 + إعادة استخدام الاتصالات + كاش صغير. يُستخدم لاقتراحات البحث. */
object NetKit {
    private var app: Context? = null
    private var lastWarm = 0L
    fun init(c: Context) { app = c.applicationContext }

    val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        app?.let { b.cache(Cache(File(it.cacheDir, "nova-http"), 4L * 1024 * 1024)) }
        b.build()
    }

    /** نص الاستجابة أو null عند أي فشل. يُلغى الطلب فوراً عند إلغاء الـ coroutine (كتابة حرف جديد). */
    suspend fun getText(url: String): String? = suspendCancellableCoroutine { k ->
        val call = runCatching { client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()) }.getOrNull()
        if (call == null) { k.resume(null); return@suspendCancellableCoroutine }
        k.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (k.isActive) k.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = runCatching { response.use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
                if (k.isActive) k.resume(body)
            }
        })
    }

    /** يفتح اتصال TLS مبكراً بخادم الاقتراحات عند بدء الكتابة، فتصل أول الاقتراحات أسرع. */
    fun warm(url: String) {
        val n = System.currentTimeMillis()
        if (n - lastWarm < 90_000) return
        lastWarm = n
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
    }
}

class Sug(val text: String, val url: String?, val kind: Int)   // kind: 0 بحث، 1 سجل، 2 مفضلة

object Suggest {
    // بنفس ترتيب Prefs.engines: Google, DuckDuckGo, Bing, Brave, Ecosia
    private val endpoints = listOf(
        "https://suggestqueries.google.com/complete/search?client=firefox&q=",
        "https://duckduckgo.com/ac/?type=list&q=",
        "https://api.bing.com/osjson.aspx?query=",
        "https://search.brave.com/api/suggest?q=",
        "https://ac.ecosia.org/autocomplete?type=list&q="
    )
    private fun endpoint() = endpoints.getOrElse(Prefs.engine) { endpoints[0] }

    fun warm() { if (Prefs.suggest) NetKit.warm(endpoint().substringBefore("?")) }

    /** اقتراحات من المفضلة والسجل (محلية بالكامل، لا تغادر الجهاز). */
    fun local(q: String): List<Sug> {
        val toks = q.lowercase().split(' ').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        fun hit(u: String, t: String): Boolean { val s = (u + " " + t).lowercase(); return toks.all { s.contains(it) } }
        val out = ArrayList<Sug>(6)
        val seen = HashSet<String>()
        for (b in Library.bookmarks) {
            if (out.size >= 2) break
            if (hit(b.url, b.title) && seen.add(b.url)) out.add(Sug(b.title.ifBlank { b.url }, b.url, 2))
        }
        for (h in Library.history) {
            if (out.size >= 5) break
            if (hit(h.url, h.title) && seen.add(h.url)) out.add(Sug(h.title.ifBlank { h.url }, h.url, 1))
        }
        return out
    }

    /** اقتراحات محرك البحث المختار (تُرسل النص المكتوب إلى المحرك نفسه فقط؛ تُعطَّل من الإعدادات). */
    suspend fun remote(q: String): List<String> {
        val text = NetKit.getText(endpoint() + URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text).getJSONArray(1)
            (0 until minOf(arr.length(), 8)).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}

/** قائمة الاقتراحات فوق شريط العنوان أثناء الكتابة. أقرب عنصر للحقل هو الأهم (القائمة معكوسة). */
@Composable
fun SuggestionsPanel(query: String, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val q = query.trim()
    if (q.isEmpty()) return
    val local = remember(q, Library.history.size, Library.bookmarks.size) { Suggest.local(q) }
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    val looksUrl = q.contains('.') && !q.contains(' ')

    LaunchedEffect(Unit) { Suggest.warm() }
    LaunchedEffect(q, Prefs.engine, Prefs.suggest) {
        if (!Prefs.suggest || looksUrl) { remote = emptyList(); return@LaunchedEffect }
        delay(140)   // تأخير بسيط: لا طلب لكل حرف
        remote = Suggest.remote(q)
    }
    // تسخين DNS لأول نتيجة محلية أو للنطاق المكتوب
    LaunchedEffect(local.firstOrNull()?.url, q) {
        WebSupport.prefetchDns(local.firstOrNull()?.url?.let { hostOf(it) } ?: if (looksUrl) hostOf(normalize(q)) else null)
    }

    val rows = remember(local, remote, q) {
        buildList {
            addAll(local)
            remote.filter { it.isNotBlank() && !it.equals(q, true) }.take(5).forEach { add(Sug(it, null, 0)) }
        }
    }
    if (rows.isEmpty()) return
    Favicons.version   // اشتراك: يُعاد الرسم عند وصول أيقونات جديدة

    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
    ) {
        LazyColumn(Modifier.heightIn(max = 300.dp), reverseLayout = true) {
            items(rows, key = { it.kind.toString() + (it.url ?: it.text) }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(s.url ?: s.text) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val ic = if (s.url != null) Favicons.get(hostOf(s.url)) else null
                    if (ic != null) {
                        val img = remember(ic) { ic.asImageBitmap() }
                        Image(img, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Fit)
                    } else Icon(
                        when (s.kind) { 2 -> Icons.Default.Star; 1 -> Icons.Default.DateRange; else -> Icons.Default.Search },
                        null, Modifier.size(20.dp), tint = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        if (s.url != null) Text(hostOf(s.url), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { onFill(s.url ?: s.text) }) {
                        Icon(Icons.Default.KeyboardArrowUp, L("إدراج في الشريط"), Modifier.rotate(-45f), tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
