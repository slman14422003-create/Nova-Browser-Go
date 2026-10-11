package com.nova.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** أدوات واجهة مشتركة بين شاشات التنزيلات والمكتبة: شريط علوي، بحث، تبويبات، قائمة ⋮، وحالة فارغة. */

@Composable
fun PanelTopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** زر ⋮ دائري يفتح قائمة؛ محتواها يستلم دالة إغلاق. */
@Composable
fun PanelMenu(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundBtn(onClick = { open = true }) { Icon(Icons.Default.MoreVert, L("المزيد")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            content({ open = false })
        }
    }
}

@Composable
fun PanelSearch(value: String, onChange: (String) -> Unit, placeholder: String) {
    val cs = MaterialTheme.colorScheme
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, L("مسح")) } }) else null,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/** تبويبات مقسّمة بعرض كامل (اختيار واحد). */
@Composable
fun PanelSegments(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, t ->
                val on = i == selected
                Surface(
                    onClick = { onSelect(i) }, shape = RoundedCornerShape(24.dp),
                    color = if (on) cs.primaryContainer else Color.Transparent, modifier = Modifier.weight(1f)
                ) {
                    Text(
                        t, Modifier.fillMaxWidth().padding(vertical = 11.dp), textAlign = TextAlign.Center, maxLines = 1,
                        style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** شريحات مرشّحات قابلة للتمرير أفقياً. */
@Composable
fun PanelChips(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, t -> FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(t) }) }
    }
}

@Composable
fun PanelEmpty(icon: ImageVector, title: String, sub: String? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(36.dp), tint = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (sub != null) {
                Spacer(Modifier.height(6.dp))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
