package com.nova.browser

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * تنظيف الملفات المؤقتة وكاش المتصفح (java.io فقط — يعمل على أندرويد 6؛ حزمة java.nio.file تحتاج أندرويد 8).
 * يجب استدعاؤه قبل إنشاء أي WebView حتى لا تكون ملفات الكاش مفتوحة.
 * لا يمسّ: الكوكيز، التنزيلات، التبويبات المحفوظة، الإعدادات.
 */
object CacheCleaner {
    /** مجلدات الكاش داخل بيانات الـ WebView (آمنة الحذف عند بدء التطبيق). */
    private val WEBVIEW_CACHE_DIRS = arrayOf(
        "HTTP Cache", "Code Cache", "GPUCache", "GrShaderCache", "ShaderCache",
        "Service Worker/CacheStorage", "Service Worker/ScriptCache"
    )

    /** الحدّ الذي يُنظَّف عنده الكاش تلقائياً (أقل على الأجهزة الضعيفة ذات التخزين الصغير). */
    private const val LIMIT = 150L * 1024 * 1024

    private fun webBase(ctx: Context) = File(ctx.applicationInfo.dataDir, "app_webview/Default")

    /** يحذف الكاش ويعيد عدد البايتات المحررة. */
    @JvmStatic
    fun clean(ctx: Context): Long {
        var freed = deleteContents(ctx.cacheDir)
        freed += deleteContents(ctx.externalCacheDir)
        val base = webBase(ctx)
        for (d in WEBVIEW_CACHE_DIRS) freed += deleteContents(File(base, d))
        return freed
    }

    /** مسح كلي للكاش عند كل تشغيل (قبل إنشاء أي WebView). لا يحذف الكوكيز ولا كلمات المرور ولا nova.conf. */
    @JvmStatic
    fun cleanAll(ctx: Context): Long = clean(ctx)

    /** هل يحتاج الكاش تنظيفاً عند هذا التشغيل؟ (قراءة علم محفوظ، فورية). */
    @JvmStatic
    fun pending(ctx: Context): Boolean =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("cleanpending", false)

    /** يُستدعى عند الخروج من التطبيق: يقيس الكاش ويضع علماً إن تجاوز الحد. */
    @JvmStatic
    fun markIfLarge(ctx: Context) {
        val base = webBase(ctx)
        val total = sizeOf(ctx.cacheDir) + sizeOf(ctx.externalCacheDir) +
            sizeOf(File(base, "HTTP Cache")) + sizeOf(File(base, "Service Worker/CacheStorage"))
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("cleanpending", total > LIMIT).apply()
    }

    /** تنظيف خفيف: ملفات التحميل المؤقتة فقط، ويُبقي كاش الشيفرة والرسوميات لسرعة التشغيل. */
    @JvmStatic
    fun cleanLarge(ctx: Context): Long {
        val base = webBase(ctx)
        var freed = deleteContents(ctx.cacheDir)
        freed += deleteContents(ctx.externalCacheDir)
        freed += deleteContents(File(base, "HTTP Cache"))
        freed += deleteContents(File(base, "Service Worker/CacheStorage"))
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("cleanpending", false).apply()
        return freed
    }

    /** حجم الكاش الحالي بالبايت (للعرض في الإعدادات). */
    @JvmStatic
    fun size(ctx: Context): Long {
        var total = sizeOf(ctx.cacheDir) + sizeOf(ctx.externalCacheDir)
        val base = webBase(ctx)
        for (d in WEBVIEW_CACHE_DIRS) total += sizeOf(File(base, d))
        return total
    }

    private fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var sum = 0L
        runCatching { dir.walkTopDown().forEach { if (it.isFile) sum += it.length() } }
        return sum
    }

    /** يحذف محتويات المجلد ويُبقي المجلد نفسه. */
    private fun deleteContents(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) { freed += deleteContents(f); f.delete() }
            else { val s = f.length(); if (f.delete()) freed += s }
        }
        return freed
    }

    /** تنسيق الحجم للعرض. */
    @JvmStatic
    fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
