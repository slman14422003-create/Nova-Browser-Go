package com.nova.browser

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * مكان حفظ التنزيلات.
 *  - أندرويد 10+ : MediaStore (Download/Nova) بلا أي إذن.
 *  - أندرويد 6–9 : ملف عادي في Download/Nova (يحتاج إذن التخزين)، وإن رُفض الإذن يُحفظ في مجلد التطبيق الخاص بلا إذن.
 * كل الدوال تتعامل مع Uri واحد (content:// أو file://) كي تبقى بقية الشيفرة كما هي.
 */
object Storage {
    private const val FOLDER = "Nova"
    private val scoped get() = Build.VERSION.SDK_INT >= 29

    fun needsPermission(c: Context): Boolean =
        !scoped && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun dir(c: Context): File {
        if (!needsPermission(c)) {
            val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (pub.isDirectory || pub.mkdirs()) return pub
        }
        val priv = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(c.filesDir, "downloads")
        priv.mkdirs()
        return priv
    }

    private fun isFile(u: Uri) = u.scheme == "file"
    fun pathOf(u: Uri): String? = if (isFile(u)) u.path else null

    /** ينشئ ملفاً فارغاً جديداً (باسم فريد) ويعيد Uri له. */
    fun create(c: Context, name: String, mime: String): Uri {
        if (scoped) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                if (mime.isNotBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("insert failed")
        }
        val safe = name.replace('/', '_').replace('\\', '_').ifBlank { "file" }
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        val d = dir(c)
        var f = File(d, safe)
        var i = 1
        while (f.exists()) { f = File(d, "$stem ($i)$ext"); i++ }
        if (!f.createNewFile()) throw IOException("create failed")
        return Uri.fromFile(f)
    }

    /** الاسم الفعلي بعد إنشاء الملف (قد يختلف لو وُجد ملف بنفس الاسم). */
    fun displayName(c: Context, u: Uri): String? {
        if (isFile(u)) return u.path?.let { File(it).name }
        return c.contentResolver.query(u, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /** يُستدعى بعد اكتمال الكتابة: يجعل الملف ظاهراً لبقية التطبيقات. */
    fun finish(c: Context, u: Uri, mime: String = "") {
        if (isFile(u)) {
            val p = u.path ?: return
            runCatching { MediaScannerConnection.scanFile(c, arrayOf(p), if (mime.isBlank()) null else arrayOf(mime), null) }
        } else {
            c.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    fun delete(c: Context, u: Uri) {
        if (isFile(u)) { u.path?.let { File(it).delete() } }
        else c.contentResolver.delete(u, null, null)
    }

    /** Uri صالح للمشاركة/الفتح في تطبيق آخر (file:// لا يُسمح به من أندرويد 7، فنحوّله عبر FileProvider). */
    fun shareUri(c: Context, u: Uri): Uri {
        val p = pathOf(u) ?: return u
        return runCatching { FileProvider.getUriForFile(c, c.packageName + ".files", File(p)) }.getOrDefault(u)
    }
}
