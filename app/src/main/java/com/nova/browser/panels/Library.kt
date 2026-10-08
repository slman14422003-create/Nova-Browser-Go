package com.nova.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Bookmark(val url: String, val title: String, val folder: String, val added: Long)
class HistoryItem(val url: String, val title: String, val time: Long)

/** مخزن الإشارات المرجعية والسجل (ملف JSON خاص بالتطبيق، يُحمَّل في خيط خلفي ويُحفَظ بتأخير لتجنّب التقطيع). */
object Library {
    var show by mutableStateOf(false)
    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryItem>()
    private const val MAX_HISTORY = 5000
    private var file: File? = null
    private val main = Handler(Looper.getMainLooper())
    private var saveQueued = false
    @Volatile private var loaded = false

    fun init(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "library.json"); file = f
        Thread({
            val b = ArrayList<Bookmark>(); val h = ArrayList<HistoryItem>()
            runCatching {
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    o.optJSONArray("b")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { x -> b.add(Bookmark(x.getString("u"), x.optString("t"), x.optString("f"), x.optLong("a"))) } }
                    o.optJSONArray("h")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { x -> h.add(HistoryItem(x.getString("u"), x.optString("t"), x.optLong("m"))) } }
                }
            }
            main.post { bookmarks.addAll(b); history.addAll(h); loaded = true }
        }, "nova-library").start()
    }

    private fun scheduleSave() {
        if (saveQueued || !loaded) return
        saveQueued = true
        main.postDelayed({
            saveQueued = false
            val b = bookmarks.toList(); val h = history.toList(); val f = file ?: return@postDelayed
            Thread({
                runCatching {
                    val o = JSONObject()
                    o.put("b", JSONArray().also { a -> b.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("f", it.folder).put("a", it.added)) } })
                    o.put("h", JSONArray().also { a -> h.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("m", it.time)) } })
                    val tmp = File(f.parentFile, "library.tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
                }
            }, "nova-library-save").start()
        }, 4000)
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    /** يضيف أو يزيل؛ يعيد true إن أُضيفت. */
    fun toggleBookmark(url: String, title: String): Boolean {
        val i = bookmarks.indexOfFirst { it.url == url }
        val added = i < 0
        if (added) bookmarks.add(0, Bookmark(url, title.ifBlank { url }, "", System.currentTimeMillis())) else bookmarks.removeAt(i)
        scheduleSave(); return added
    }

    fun removeBookmark(b: Bookmark) { bookmarks.remove(b); scheduleSave() }
    fun removeHistory(h: HistoryItem) { history.remove(h); scheduleSave() }
    fun clearHistory() { history.clear(); scheduleSave() }

    /** تسجيل زيارة صفحة (يُستدعى بعد اكتمال التحميل). */
    fun visit(url: String, title: String?) {
        if (!url.startsWith("http") || !loaded) return
        val top = history.firstOrNull()
        if (top != null && top.url == url) return
        history.add(0, HistoryItem(url, title?.takeIf { it.isNotBlank() } ?: url, System.currentTimeMillis()))
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        scheduleSave()
    }

    /** دمج مستورد دون تكرار. يُستدعى على أي خيط؛ التعديل الفعلي يتم على الخيط الرئيسي ويُنتظر انتهاؤه. */
    fun addBookmarks(list: List<Bookmark>): Int = onMain {
        val have = bookmarks.mapTo(HashSet()) { it.url }; var n = 0
        for (b in list) if (have.add(b.url)) { bookmarks.add(b); n++ }
        if (n > 0) scheduleSave(); n
    }

    fun addHistory(list: List<HistoryItem>): Int = onMain {
        val have = history.mapTo(HashSet()) { it.url + "|" + it.time / 60000 }; var n = 0
        val fresh = list.filter { have.add(it.url + "|" + it.time / 60000) }.also { n = it.size }
        if (n > 0) {
            val merged = (history.toList() + fresh).sortedByDescending { it.time }.take(MAX_HISTORY)
            history.clear(); history.addAll(merged); scheduleSave()
        }
        n
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: Any? = null; val latch = java.util.concurrent.CountDownLatch(1)
        main.post { out = block(); latch.countDown() }
        latch.await()
        @Suppress("UNCHECKED_CAST") return out as T
    }
}

@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var help by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            Thread({
                val r = runCatching { ChromeImport.run(ctx.applicationContext, uris) }.getOrElse { ImportResult().also { x -> x.notes += (it.message ?: "error") } }
                Handler(Looper.getMainLooper()).post { importing = false; result = r }
            }, "nova-import").start()
        }
    }

    val q = query.trim()
    val bms = Library.bookmarks.filter { q.isBlank() || it.title.contains(q, true) || it.url.contains(q, true) || it.folder.contains(q, true) }
    val hs = Library.history.filter { q.isBlank() || it.title.contains(q, true) || it.url.contains(q, true) }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
                Spacer(Modifier.width(8.dp))
                Text(L("المكتبة"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RoundBtn(onClick = { help = true }) { Icon(Icons.Default.KeyboardArrowDown, L("استيراد من كروم")) }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(L("المفضلة") + " (${Library.bookmarks.size})") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(L("السجل") + " (${Library.history.size})") })
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(L("بحث")) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (importing) NovaLoadingRow(L("جارٍ الاستيراد…"))
            val empty = if (tab == 0) bms.isEmpty() else hs.isEmpty()
            if (empty) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (tab == 0) L("لا توجد مفضلة بعد") else L("السجل فارغ"), color = cs.onSurfaceVariant)
                }
            } else if (tab == 0) {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(bms, key = { i, b -> b.url + i }) { i, b ->
                        ListRow(groupShape(i, bms.size), b.title, if (b.folder.isBlank()) hostOf(b.url) else b.folder + " • " + hostOf(b.url), { onOpen(b.url) },
                            trailing = { IconButton(onClick = { Library.removeBookmark(b) }) { Icon(Icons.Default.Close, L("حذف")) } }) { IconCircle { Icon(Icons.Default.Star, null) } }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(hs.take(1000), key = { i, h -> h.url + h.time + i }) { i, h ->
                        ListRow(groupShape(i, minOf(hs.size, 1000)), h.title, hostOf(h.url) + " • " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(h.time)), { onOpen(h.url) },
                            trailing = { IconButton(onClick = { Library.removeHistory(h) }) { Icon(Icons.Default.Close, L("حذف")) } }) { IconCircle { Icon(Icons.Default.Refresh, null) } }
                    }
                    item { TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text(L("مسح السجل")) } }
                }
            }
        }
    }

    if (help) AlertDialog(
        onDismissRequest = { help = false }, title = { Text(L("استيراد بيانات كروم")) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(L("أندرويد يمنع أي تطبيق من قراءة بيانات كروم مباشرة، لذلك يُستورد من ملفات يصدّرها كروم نفسه. اختر ملفاً أو عدة ملفات دفعة واحدة:"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Text(L("• كلمات المرور: كروم ← الإعدادات ← مدير كلمات المرور ← تصدير (ملف CSV). احذف الملف بعد الاستيراد."), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(L("• المفضلة والسجل والباقي: takeout.google.com ← اختر Chrome فقط ← صدّر ← اختر ملف ZIP كما هو."), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(L("• من كروم سطح المكتب: ملف Bookmarks.html (مدير الإشارات ← تصدير) أو ملفا Bookmarks وHistory من مجلد ملف التعريف."), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(L("لا يمكن نقل الكوكيز وتبويبات كروم المفتوحة؛ كروم لا يصدّرها."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { help = false; picker.launch(arrayOf("*/*")) }) { Text(L("اختيار الملفات")) } },
        dismissButton = { TextButton(onClick = { help = false }) { Text(L("إغلاق")) } }
    )

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null }, title = { Text(L("اكتمل الاستيراد")) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(L("المفضلة") + ": ${r.bookmarks}\n" + L("السجل") + ": ${r.history}\n" + L("كلمات المرور") + ": ${r.passwords}\n" + L("متجاهَل") + ": ${r.skipped}")
                if (r.notes.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(r.notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = cs.error) }
            } },
            confirmButton = { TextButton(onClick = { result = null }) { Text(L("إغلاق")) } }
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(L("مسح السجل؟")) },
        confirmButton = { TextButton(onClick = { Library.clearHistory(); confirmClear = false }) { Text(L("مسح")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L("إلغاء")) } }
    )
}
