import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------- التوقيع: متغيرات بيئة (CI) أو ملف keystore.properties محلي (غير مرفوع) ----------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, key: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)
val ksPath = signingValue("KEYSTORE_PATH", "storeFile")
val ksPass = signingValue("KEYSTORE_PASSWORD", "storePassword")
val ksAlias = signingValue("KEY_ALIAS", "keyAlias")
val ksKeyPass = signingValue("KEY_PASSWORD", "keyPassword") ?: ksPass
val hasReleaseSigning = ksPath != null && ksPass != null && ksAlias != null && rootProject.file(ksPath).exists()

android {
    namespace = "com.nova.browser"   // اسم حزمة الشيفرة (R) يبقى كما هو؛ معرّف التطبيق أدناه مختلف كي يتعايش مع Nova Browser
    compileSdk = 37
    defaultConfig {
        applicationId = "com.nova.browser.go"
        minSdk = 24   // أندرويد 7.0 فما فوق (webkit 1.16.0 بيتطلب 24 كحد أدنى)
        targetSdk = 37
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 10
        versionName = System.getenv("VERSION_NAME") ?: "1.8.1-go"
        // مستودع التحديثات: يُملأ تلقائياً في GitHub Actions (owner/repo)، فارغ محلياً = التحديث معطّل
        buildConfigField("String", "UPDATE_REPO", "\"" + (System.getenv("GITHUB_REPOSITORY") ?: "") + "\"")
        vectorDrawables.useSupportLibrary = true
        // لا مكتبات native (حُذف FFmpeg) ← APK واحد يعمل على armeabi-v7a وarm64 وx86 وبحجم صغير
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = rootProject.file(ksPath!!)
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksKeyPass
        }
    }
    androidResources { localeFilters += listOf("ar", "en") }   // يقلّل حجم الـ APK (نصوص المكتبات بلغتين فقط)
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") } }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"      // يتعايش مع نسخة الإصدار على نفس الجهاز
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // بلا مفاتيح (مثل طلبات الدمج من forks) يُوقَّع بمفتاح debug كي لا يفشل البناء
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true      // java.time وغيرها على أندرويد 6/7 (مطلوب أيضاً لـ NewPipeExtractor)
    }
    buildFeatures { compose = true; buildConfig = true }   // BuildConfig مطلوب لـ Updater (كان سبب الخطأ)
}

// Kotlin مدمج في AGP 9؛ هدف JVM = 1.8 (أقل جافا ممكنة) بغض النظر عن JDK المشغِّل للبناء.
// إزالة فحوص null الداخلية تقلّل حجم الشيفرة وتسرّع الأجهزة الضعيفة قليلاً.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")                  // ActivityManagerCompat/ServiceCompat/NotificationManagerCompat… (توافق أندرويد 6 وGo)
    implementation("androidx.webkit:webkit:1.16.0")             // حقن سكربت الحماية قبل الصفحة (1.16: أحدث مستقر، يدعم ميزات المحرك الجديدة)
    implementation("androidx.browser:browser:1.8.0")              // Chrome Custom Tabs لصفحات تسجيل الدخول الحساسة
    implementation("com.squareup.okhttp3:okhttp:5.5.0")            // شبكة HTTP/2 — موحّدة مع نسخة NewPipeExtractor الداخلية (okhttp-android) لتفادي تعارض النسخ
    implementation("androidx.profileinstaller:profileinstaller:1.4.1") // ملفات Baseline لتسريع بدء التشغيل وتقليل التقطيع
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") // استخراج روابط الفيديو/الصوت من يوتيوب (مجاني ومفتوح المصدر)
    implementation("org.conscrypt:conscrypt-android:2.5.2")        // طبقة TLS حديثة مستقلة عن نظام الجهاز — تحل فشل تحميل روابط يوتيوب (googlevideo) على الأجهزة القديمة (أندرويد دون 13) التي لا تدعم تبادل TLS الذي تفرضه خوادم غوغل حالياً
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
