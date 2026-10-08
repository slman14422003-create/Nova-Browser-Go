plugins {
    id("com.android.application") version "9.2.1" apply false
    // AGP 9 يحمل دعم Kotlin مدمجاً (لا حاجة لـ org.jetbrains.kotlin.android). مكوّن Compose يجب أن يطابق نسخة Kotlin التي يعتمد عليها AGP 9.2
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
