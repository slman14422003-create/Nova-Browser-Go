package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LangUtilTest {
    @Test fun rtlLanguages() {
        assertTrue(LangUtil.isRtl("ar"))
        assertTrue(LangUtil.isRtl("fa-IR"))
        assertFalse(LangUtil.isRtl("en"))
        assertFalse(LangUtil.isRtl(""))
        assertFalse(LangUtil.isRtl(null))
    }

    @Test fun acceptLanguage() {
        assertEquals("", LangUtil.acceptLanguage(""))
        assertEquals("en", LangUtil.acceptLanguage("en-US"))
        assertEquals("ar,en;q=0.8", LangUtil.acceptLanguage("ar"))
    }
}
