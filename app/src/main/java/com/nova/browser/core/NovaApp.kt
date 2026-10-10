package com.nova.browser

import android.app.Application
import java.security.Security

/**
 * نقطة إقلاع التطبيق.
 *
 * تثبّت Conscrypt كمزوّد TLS افتراضي قبل أي اتصال شبكة. هذا يستبدل طبقة
 * TLS المدمجة في نظام أندرويد نفسه (والتي تتجمّد عند نسخة أندرويد التصنيع
 * ولا تتحدّث على الأجهزة القديمة) بمكتبة TLS حديثة مرفقة داخل التطبيق.
 *
 * هذا هو سبب فشل تحميل فيديوهات يوتيوب على الأجهزة القديمة: روابط التنزيل
 * الفعلية (googlevideo.com) تتطلب الآن بروتوكول/مجموعات تشفير TLS حديثة
 * لم تكن مدعومة افتراضياً إلا من أندرويد 13 تقريباً؛ الأجهزة الأقدم تفشل
 * في المصافحة (handshake) وتُرفض الوصلة فوراً، بصرف النظر عن صحة الرابط.
 *
 * بتثبيت Conscrypt هنا، يستخدم HttpURLConnection (في NpDownloader وDownloader)
 * هذه الطبقة الحديثة تلقائياً على كل الأجهزة من أندرويد 7 فما فوق — دون أي
 * تعديل آخر على كود التنزيل أو NewPipeExtractor.
 */
class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        }
    }
}
