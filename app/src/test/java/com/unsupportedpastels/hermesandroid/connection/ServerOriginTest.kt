package com.unsupportedpastels.hermesandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerOriginTest {
    @Test
    fun canonicalizesHttpsOrigin() {
        assertEquals(
            "https://example.com",
            ServerOrigin.parse("  HTTPS://Example.COM:443/  ").value,
        )
    }

    @Test
    fun preservesExplicitNonDefaultPort() {
        assertEquals(
            "https://example.com:8443",
            ServerOrigin.parse("https://example.com:8443").value,
        )
    }

    @Test
    fun canonicalizesInternationalizedDnsName() {
        assertEquals(
            "https://xn--r8jz45g.xn--zckzah",
            ServerOrigin.parse("https://例え.テスト/").value,
        )
    }

    @Test
    fun rejectsNonHttpsAndNonOriginUrls() {
        listOf(
            "",
            "http://example.com",
            "https://user@example.com",
            "https://example.com/api",
            "https://example.com?ticket=secret",
            "https://example.com#fragment",
            "example.com",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerOrigin.parse(input)
            }
        }
    }
}
