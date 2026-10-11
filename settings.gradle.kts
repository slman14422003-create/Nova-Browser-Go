pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // JitPack: مكتبة NewPipeExtractor (استخراج روابط يوتيوب للتنزيل) واعتمادياتها
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}
rootProject.name = "NovaGo"
include(":app")
