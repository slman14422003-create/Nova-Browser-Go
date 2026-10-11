package com.nova.browser

import android.content.Context

/**
 * صيغ الصوت المحوَّلة (MP3/OGG/FLAC…) كانت تعتمد على FFmpeg، وهو مكتبة native ضخمة (عشرات الميغابايت) لا تعمل على أندرويد 6
 * ولا تناسب أجهزة Android Go. في نسخة Go تُقدَّم الملفات الصوتية الأصلية بلا تحويل (M4A / WebM Opus) وهي الأسرع والأعلى جودة.
 * تبقى الأنواع هنا فارغة حتى لا تتغير بقية الشيفرة (واجهة تنزيل يوتيوب تقرأ AudioFormats.all).
 */
class AudioFmt(
    val label: String, val sub: String, val ext: String, val mime: String,
    val container: String, val codecArgs: List<String>, val id3: Boolean = false
)

object AudioFormats {
    val all: List<AudioFmt> = emptyList()
}

object AudioConvert {
    /** لا تحويل في نسخة Go: يبقى الملف الأصلي كما نُزِّل. */
    @Suppress("UNUSED_PARAMETER")
    fun run(app: Context, base: String, title: String, artist: String, src: DlTask, fmt: AudioFmt) = Unit
}
