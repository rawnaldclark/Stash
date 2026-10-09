package com.stash.core.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    @Test fun `strips spotify sp_dc cookie value`() {
        val out = DiagnosticsRedactor.redact("cookie: sp_dc=AQB1234secretValue; Path=/")
        assertFalse(out.contains("AQB1234secretValue"))
        assertTrue(out.contains("sp_dc=[REDACTED]"))
    }

    @Test fun `strips youtube google auth cookies`() {
        val out = DiagnosticsRedactor.redact("Cookie: SAPISID=abc123def; HSID=zzz999; SID=qqq111")
        listOf("abc123def", "zzz999", "qqq111").forEach { assertFalse(out.contains(it)) }
    }

    @Test fun `strips bearer and named tokens`() {
        val out = DiagnosticsRedactor.redact(
            "Authorization: Bearer ya29.A0AReallyLongToken\naccess_token=secretAccess refresh_token=\"secretRefresh\""
        )
        listOf("ya29.A0AReallyLongToken", "secretAccess", "secretRefresh").forEach {
            assertFalse(out.contains(it))
        }
    }

    @Test fun `strips email addresses`() {
        val out = DiagnosticsRedactor.redact("account: jane.doe+test@gmail.com synced")
        assertFalse(out.contains("jane.doe+test@gmail.com"))
        assertTrue(out.contains("[REDACTED_EMAIL]"))
    }

    @Test fun `strips unrecognized cookies via generic header fallback`() {
        val out = DiagnosticsRedactor.redact("Cookie: VISITOR_INFO1_LIVE=abcSECRET; YSC=defSECRET")
        assertFalse(out.contains("abcSECRET"))
        assertFalse(out.contains("defSECRET"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test fun `strips the cast media server's address and token`() {
        val token = "0123456789abcdef0123456789abcdef"
        val out = DiagnosticsRedactor.redact(
            "load http://192.168.1.23:41877/$token/m/3\nserving /$token/a/4 failed: reset",
        )
        listOf(token, "192.168.1.23", "41877").forEach { assertFalse(out.contains(it)) }
        assertTrue(out.contains("http://[REDACTED_LAN]/[REDACTED]/m/3"))
        assertTrue(out.contains("/[REDACTED]/a/4"))
    }

    @Test fun `leaves ordinary text and stack traces intact`() {
        val text = "java.lang.NoSuchMethodError at FFmpegBridge.kt:98\nrefreshing 13 Stash Mix(es)"
        assertTrue(DiagnosticsRedactor.redact(text) == text)
    }
}
