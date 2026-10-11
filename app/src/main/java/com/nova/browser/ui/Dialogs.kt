package com.nova.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * مربع الحوار الموحّد لكل التطبيق: نفس الشكل (حواف 28dp، سطح surfaceContainerHigh، شارة أيقونة، أزرار بنفس الأسلوب)
 * بحيث لا يظهر أي حوار بشكل مختلف عن بقية الواجهة. الحذف/التحذير يأخذ لون الخطر.
 */
@Composable
fun NovaDialog(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = L("إغلاق"),
    onDismissClick: (() -> Unit)? = null,
    danger: Boolean = false,
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = cs.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (icon != null) Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(if (danger) cs.errorContainer else cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, Modifier.size(24.dp), tint = if (danger) cs.onErrorContainer else cs.onPrimaryContainer) }
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = {
            if (confirmText != null && onConfirm != null) Button(
                onClick = onConfirm, shape = CircleShape,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError) else ButtonDefaults.buttonColors()
            ) { Text(confirmText) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                extraAction?.invoke()
                if (dismissText != null) TextButton(onClick = onDismissClick ?: onDismiss) { Text(dismissText) }
            }
        }
    )
}

/** حوار اختيار واحد (لغة، مظهر، …): صفوف بحواف دائرية، والمختار مظلَّل بعلامة ✓ — يتبع هوية التطبيق بدل أزرار الراديو الافتراضية. */
@Composable
fun NovaChoiceDialog(
    title: String, options: List<String>, selected: Int, icon: ImageVector? = null,
    onSelect: (Int) -> Unit, onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    NovaDialog(title = title, onDismiss = onDismiss, icon = icon, dismissText = L("إغلاق")) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) cs.primaryContainer else cs.surfaceContainerHighest.copy(alpha = 0.55f))
                        .clickable { onSelect(i); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        o, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) cs.onPrimaryContainer else cs.onSurface
                    )
                    if (on) Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = cs.onPrimaryContainer)
                }
            }
        }
    }
}

/** نص شرح داخل الحوار بلون ثانوي. */
@Composable
fun DialogText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
