package com.nova.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private val netImageCache = object : android.util.LruCache<String, Bitmap>(24) {}

/** تحميل صورة من الشبكة بدون مكتبات خارجية (مع كاش بسيط في الذاكرة). */
@Composable
fun NetImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bmp by remember(url) { mutableStateOf(netImageCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                    c.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.also { netImageCache.put(url, it) }
            }
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = contentScale)
    else Box(modifier.background(Color(0x22888888)))
}
