package com.nova.browser

import android.app.Application
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * نقطة إقلاع التطبيق — تصلح مشكلتَي TLS على الأجهزة القديمة قبل أي اتصال شبكة:
 *
 * ١) Conscrypt: طبقة TLS حديثة مرفقة داخل التطبيق، تستبدل طبقة TLS المدمجة في نظام
 *    أندرويد نفسه (المتجمّدة عند نسخة تصنيع الجهاز ولا تتحدّث على الأجهزة القديمة).
 *
 * ٢) ISRG Root X1: شهادة الجذر طويلة الأمد لـ Let's Encrypt (صالحة حتى 2035)، تُضاف
 *    يدوياً إلى مخزن الثقة. السبب: أجهزة قديمة لم تتلقَّ تحديث نظام منذ سنوات ينقصها
 *    هذا الجذر من مخزن الثقة المدمج، فيفشل أي اتصال HTTPS بخادم شهادته من Let's Encrypt
 *    (ومنها خوادم Piped المستخدَمة لتنزيل يوتيوب) بخطأ:
 *    "Trust anchor for certification path not found".
 *    تُضاف هذه الشهادة فوق شهادات النظام العادية كلها (بلا استبدال أي منها)، فلا
 *    يتأثر أي اتصال آخر بالتطبيق.
 */
class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1) }
        runCatching { installExtraTrustAnchor() }
    }

    private fun installExtraTrustAnchor() {
        val extraCa = resources.openRawResource(R.raw.isrg_root_x1).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }

        // ثقة النظام الافتراضية (كل شهادات أندرويد المعتادة) — تبقى كما هي بلا أي تغيير.
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        // ثقة إضافية تحتوي فقط على ISRG Root X1 (النمط الرسمي الموصى به من أندرويد
        // لإضافة جذر غير معروف لدى النظام: developer.android.com/training/articles/security-ssl).
        val extraKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg-root-x1", extraCa)
        }
        val extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        extraTmf.init(extraKs)
        val extraTm = extraTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        // دمج: جرّب ثقة النظام أولاً، وإن رفضت (السلسلة غير معروفة له) جرّب شهادتنا الإضافية.
        val merged = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                systemTm.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try { systemTm.checkServerTrusted(chain, authType) }
                catch (e: Exception) { extraTm.checkServerTrusted(chain, authType) }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTm.acceptedIssuers + extraTm.acceptedIssuers
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(merged), null)
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        SSLContext.setDefault(ctx)
    }
}
