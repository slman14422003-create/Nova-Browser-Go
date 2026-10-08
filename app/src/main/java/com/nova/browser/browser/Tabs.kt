package com.nova.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** لون ثابت لكل موقع (يُشتق من اسم النطاق) لتمييز البطاقات. */
private fun hostColor(host: String): Color = Color.hsv((host.hashCode().toLong().let { if (it < 0) -it else it } % 360).toFloat(), 0.55f, 0.72f)

/** ألوان علامات التبويبات (الفهرس 0 = بلا علامة). */
val TabTagColors = listOf(Color.Transparent, Color(0xFFE57373), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFFF06292))

/** شاشة التبويبات: شبكة بطاقات بمعاينة مصغّرة، تثبيت، علامات لونية، تصفية، وقائمة (نسخ/إغلاق الآخرين/إغلاق علامة) بضغطة مطوّلة. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabSwitcher(
    tabs: List<BrowserTab>, current: Int,
    onSelect: (Int) -> Unit, onClose: (Int) -> Unit, onNew: () -> Unit, onCloseAll: () -> Unit, onBack: () -> Unit,
    onDuplicate: (Int) -> Unit = {}, onCloseOthers: (Int) -> Unit = {}, onCloseTag: (Int) -> Unit = {},
    canReopen: Boolean = false, onReopen: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    var picked by remember { mutableIntStateOf(-1) }   // -1 الكل، -2 المثبّتة، 1..6 علامة
    val usedTags = tabs.map { it.tag }.filter { it > 0 }.distinct().sorted()
    // إن اختفى آخر تبويب بتلك العلامة نعود إلى «الكل» دون كتابة حالة أثناء التركيب
    val filter = if ((picked > 0 && picked !in usedTags) || (picked == -2 && tabs.none { it.pinned })) -1 else picked
    val shown = tabs.withIndex().filter { (_, t) -> when (filter) { -1 -> true; -2 -> t.pinned; else -> t.tag == filter } }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (current / 2) * 2)
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("التبويبات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("" + tabs.size + L(" مفتوحة"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (canReopen) IconButton(onClick = onReopen) { Icon(Icons.Default.Refresh, L("إعادة فتح تبويب مغلق")) }
                TextButton(onClick = onCloseAll) { Text(L("إغلاق الكل")) }
                FilledTonalButton(onClick = onNew) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(L("جديد")) }
                Spacer(Modifier.width(8.dp))
            }
            // شريط التصفية: الكل / المثبّتة / الألوان المستخدمة
            if (usedTags.isNotEmpty() || tabs.any { it.pinned }) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = filter == -1, onClick = { picked = -1 }, label = { Text(L("الكل")) })
                if (tabs.any { it.pinned }) FilterChip(selected = filter == -2, onClick = { picked = -2 }, label = { Text("📌 " + L("المثبّتة")) })
                usedTags.forEach { g ->
                    FilterChip(
                        selected = filter == g, onClick = { picked = g }, label = { Text(tabs.count { it.tag == g }.toString()) },
                        leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(TabTagColors[g])) }
                    )
                }
            }
            LazyVerticalGrid(
                state = gridState, columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
            ) {
                items(shown, key = { it.value.id }) { (i, t) ->
                    val sel = i == current
                    val blank = t.url.isBlank()
                    val host = if (blank) "nova" else hostOf(t.url)
                    val accent = remember(host) { hostColor(host) }
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().aspectRatio(0.74f).animateItem()) {
                        Surface(
                            shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerHigh,
                            border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant),
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)).combinedClickable(onClick = { onSelect(i) }, onLongClick = { menu = true })
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                if (t.tag > 0) Box(Modifier.fillMaxWidth().height(4.dp).background(TabTagColors[t.tag]))
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = if (t.tag > 0) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(22.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                                        val fvTick = Favicons.version   // يعيد الرسم عند وصول أيقونة
                                        val ic = if (blank) null else remember(host, fvTick) { Favicons.get(host) }
                                        if (ic != null) {
                                            val ib = remember(ic) { ic.asImageBitmap() }
                                            Image(ib, null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit)
                                        } else Text(host.take(1).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (t.pinned) { Text("📌", fontSize = 11.sp); Spacer(Modifier.width(4.dp)) }
                                    Text(
                                        if (blank) L("صفحة البداية") else t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { onClose(i) }, modifier = Modifier.size(38.dp)) {
                                        Icon(Icons.Default.Close, L("إغلاق"), Modifier.size(16.dp))
                                    }
                                }
                                Box(
                                    Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                                        .clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHighest)
                                ) {
                                    val th = t.thumb
                                    if (th != null && !blank) {
                                        val img = remember(th) { th.asImageBitmap() }
                                        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                                    } else {
                                        Box(
                                            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent))),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(if (blank) "✦" else host.take(1).uppercase(), fontSize = 34.sp, color = accent, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (!blank) Text(
                                        host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(10.dp))
                                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (t.pinned) L("إلغاء التثبيت") else L("تثبيت")) }, onClick = { t.pinned = !t.pinned; menu = false })
                            DropdownMenuItem(text = { Text(L("نسخ التبويب")) }, onClick = { menu = false; onDuplicate(i) })
                            DropdownMenuItem(text = { Text(L("إغلاق التبويبات الأخرى")) }, onClick = { menu = false; onCloseOthers(i) })
                            if (t.tag > 0) DropdownMenuItem(text = { Text(L("إغلاق كل تبويبات هذا اللون")) }, onClick = { menu = false; onCloseTag(t.tag) })
                            // اختيار لون العلامة
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                for (g in 0..6) Box(
                                    Modifier.size(26.dp).clip(CircleShape)
                                        .background(if (g == 0) cs.surfaceContainerHighest else TabTagColors[g])
                                        .border(if (t.tag == g) 2.dp else 0.dp, cs.primary, CircleShape)
                                        .clickable { t.tag = g; menu = false },
                                    contentAlignment = Alignment.Center
                                ) { if (g == 0) Icon(Icons.Default.Close, null, Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
