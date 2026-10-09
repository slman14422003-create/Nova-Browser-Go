-keepattributes *Annotation*
-dontwarn java.lang.invoke.StringConcatFactory

# مكتبة الـ core library desugaring (تشغّل APIs جافا حديثة زي
# URLDecoder.decode(String, Charset) على أندرويد قديم). بدون هذه القاعدة
# R8 بيحذفها في الريليس فيحصل NoSuchMethodError وقت التشغيل.
-keep class j$.** { *; }
-dontwarn j$.**

# NewPipeExtractor + Rhino + jsoup
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.protobuf.**
-dontwarn sun.misc.Unsafe
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.lang.model.**

# OkHttp / Okio (مزوّدات TLS الاختيارية غير موجودة على أندرويد)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
