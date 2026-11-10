package com.example.ai.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortTest {

    @Test
    fun missingOrBlankPortFallsBackToTheDefault() {
        assertEquals(8080, parsePort(null))
        assertEquals(8080, parsePort(""))
        assertEquals(8080, parsePort("  "))
    }

    @Test
    fun aValidPortIsUsed() {
        assertEquals(9090, parsePort("9090"))
        assertEquals(1, parsePort("1"))
        assertEquals(65535, parsePort("65535"))
    }

    @Test
    fun aBadPortFailsFastInsteadOfSilentlyUsingTheDefault() {
        // A typo in PORT must not quietly start the server somewhere else than the operator meant.
        for (raw in listOf("abc", "0", "-1", "65536", "80.5", "8080 8081")) {
            assertFailsWith<IllegalArgumentException>("PORT='$raw'") { parsePort(raw) }
        }
    }
}
