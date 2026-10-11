package com.nova.browser

import java.util.Locale

/** أدوات اللغة: اتجاه الكتابة وترويسة Accept-Language. */
object LangUtil {
    private val RTL = setOf("ar", "fa", "ur", "he", "ps", "ku")
    private val SEP = Regex("[-_]")

    /** هل اللغة تُكتب من اليمين لليسار؟ */
    @JvmStatic
    fun isRtl(code: String?): Boolean {
        if (code.isNullOrEmpty()) return false
        return RTL.contains(code.lowercase(Locale.ROOT).split(SEP)[0])
    }

    /** ترويسة Accept-Language للغة مفضّلة (مع الإنجليزية كاحتياط). */
    @JvmStatic
    fun acceptLanguage(code: String?): String {
        if (code.isNullOrEmpty()) return ""
        val c = code.lowercase(Locale.ROOT).split(SEP)[0]
        return if (c == "en") "en" else "$c,en;q=0.8"
    }
}
