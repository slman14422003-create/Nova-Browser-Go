package com.nova.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.HttpURLConnection
import java.net.URL

/**
 * مساعد محرك العرض. التطبيق يعرض الصفحات بمحرك Android System WebView (نفس محرك كروم) ولا يمكنه ترقيته بنفسه،
 * لذلك يقوم بثلاثة أشياء:
 * 1) يقرأ إصدار المحرك المثبّت ويقارنه بآخر إصدار مستقر لكروم (فحص خفيف مرة كل 12 ساعة).
 * 2) يعرّف نفسه للمواقع بآخر إصدار مثبّت فعلاً (User-Agent + Client Hints) كي تُقدَّم له النسخة الحديثة الكاملة من الصفحات.
 * 3) ينبّه ويفتح صفحة التحديث في المتجر عندما يتأخر المحرك بإصدارين أو أكثر.
 */
object WebEngine {
    class Info(val pkg: String, val version: String, val major: Int)

    var info by mutableStateOf<Info?>(null); private set
    var latestMajor by mutableIntStateOf(0); private set
    var checking by mutableStateOf(false); private set

    // المستخدم فتح المتجر عند هذا الزوج (المثبّت، الأحدث) ولم يجد المتجر شيئاً أحدث: نعدّ المحرك محدّثاً حتى يتغيّر أحد الرقمين
    private var ackInstalled by mutableIntStateOf(0)
    private var ackLatest by mutableIntStateOf(0)
    val acked: Boolean get() { val i = info ?: return false; return ackInstalled == i.major && ackLatest == latestMajor && latestMajor > 0 }

    val behind: Int get() { val i = info ?: return 0; return if (!acked && latestMajor > i.major) latestMajor - i.major else 0 }
    // التحديث التدريجي (staged rollout) يجعل المحرك يتأخر إصداراً أو اثنين طبيعياً، فلا ننبّه إلا عند تأخر 3 إصدارات فأكثر
    val outdated: Boolean get() = behind >= 3

    fun init(c: Context) {
        try {
            val p = WebViewCompat.getCurrentWebViewPackage(c)
            val v = p?.versionName
            if (p != null && v != null) info = Info(p.packageName, v, v.substringBefore('.').toIntOrNull() ?: 0)
        } catch (_: Throwable) { }
        val st = ConfStore.open(c)
        latestMajor = st.getInt("wvlatest", 0)
        ackInstalled = st.getInt("wvackinst", 0); ackLatest = st.getInt("wvacklatest", 0)
    }

    /** User-Agent سطح المكتب بآخر إصدار مثبّت (بدل رقم ثابت قديم). */
    fun desktopUa(): String {
        val m = info?.major?.takeIf { it > 0 } ?: 130
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$m.0.0.0 Safari/537.36"
    }

    /** Client Hints (Sec-CH-UA…) متوافقة مع الـ User-Agent، فلا تعاملنا المواقع كمتصفح مضمَّن قديم. */
    fun applyMetadata(wv: WebView, desktop: Boolean) {
        val i = info ?: return
        if (i.major <= 0 || !WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val major = i.major.toString()
            val full = if (Prefs.antiFingerprint) "$major.0.0.0" else i.version
            fun b(brand: String, maj: String, fv: String) =
                UserAgentMetadata.BrandVersion.Builder().setBrand(brand).setMajorVersion(maj).setFullVersion(fv).build()
            val md = UserAgentMetadata.Builder()
                .setBrandVersionList(listOf(b("Not.A/Brand", "99", "99.0.0.0"), b("Chromium", major, full), b("Google Chrome", major, full)))
                .setFullVersion(full)
                .setPlatform(if (desktop) "Linux" else "Android")
                .setPlatformVersion(if (desktop) "6.5.0" else "10.0.0")
                .setMobile(!desktop)
                .setModel(if (desktop) "" else "K")
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, md)
        }
    }

    /** يفحص آخر إصدار مستقر لكروم (مرة كل 12 ساعة ما لم يُفرض). */
    fun checkLatest(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        val st = ConfStore.open(app)
        val nowH = (System.currentTimeMillis() / 3_600_000L).toInt()
        if (checking || (!force && nowH - st.getInt("wvchk", 0) < 12)) return
        checking = true
        Thread({
            val m = runCatching { fetchLatestMajor() }.getOrDefault(0)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                checking = false
                if (m > 0) {
                    latestMajor = m; st.putInt("wvlatest", m); st.putInt("wvchk", nowH)
                    // تنبيه واحد كل 24 ساعة كحد أقصى
                    if (outdated && nowH - st.getInt("wvwarn", 0) >= 24) {
                        st.putInt("wvwarn", nowH)
                        toast(app, L("محرك العرض قديم — حدّث Android System WebView لأفضل أداء وتوافق (الإعدادات ← محرك العرض)"))
                    }
                }
            }
        }, "nova-wvcheck").start()
    }

    private fun readMajors(url: String): List<Int> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        try {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return Regex("\"version\"\\s*:\\s*\"(\\d+)\\.").findAll(body).map { it.groupValues[1].toInt() }.toList()
        } finally { c.disconnect() }
    }

    /**
     * آخر إصدار مستقر «وصل فعلاً لمعظم الأجهزة». قائمة versions تشمل الإصدارات التي بدأ طرحها لنسبة صغيرة فقط
     * (فيظهر رقم أحدث من المتاح في المتجر)، لذلك نستخدم releases مع شرط نسبة الطرح ≥ 50%.
     */
    private fun fetchLatestMajor(): Int {
        val base = "https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions"
        val rolled = runCatching {
            readMajors("$base/all/releases?filter=endtime%3Dnone,fraction%3E%3D0.5&orderBy=version%20desc&pageSize=3").maxOrNull() ?: 0
        }.getOrDefault(0)
        if (rolled > 0) return rolled
        // احتياطي: القائمة العامة، ونطرح إصداراً لأن أحدثها غالباً قيد الطرح التدريجي
        val any = readMajors("$base?orderBy=version%20desc&pageSize=3").maxOrNull() ?: 0
        return if (any > 1) any - 1 else 0
    }

    /** يفتح صفحة تحديث الـ WebView (أو كروم إن كان هو المزوّد) في المتجر. */
    fun openStore(c: Context) {
        val pkg = info?.pkg ?: "com.google.android.webview"
        info?.let { i ->
            // نتذكّر أن المستخدم زار المتجر عند هذا الزوج من الإصدارات: إن لم يتغيّر المثبّت فالمتجر لا يملك أحدث لجهازه
            ackInstalled = i.major; ackLatest = latestMajor
            ConfStore.open(c.applicationContext).apply { putInt("wvackinst", i.major); putInt("wvacklatest", latestMajor) }
        }
        fun view(u: String) = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(view("market://details?id=$pkg")) }
        catch (_: Exception) { runCatching { c.startActivity(view("https://play.google.com/store/apps/details?id=$pkg")) } }
    }

    fun summary(): String {
        val i = info ?: return L("غير معروف")
        val base = "WebView ${i.major}"
        return when {
            checking -> "$base — " + L("جارٍ الفحص…")
            outdated -> "$base — " + L("يتوفر إصدار أحدث") + " (${latestMajor}) — " + L("اضغط للتحديث")
            acked -> "$base — " + L("محدّث (هذا أحدث ما يوفّره المتجر لجهازك)")
            latestMajor > 0 -> "$base — " + L("محدّث")
            else -> "$base — " + L("اضغط للفحص الآن")
        }
    }
}
