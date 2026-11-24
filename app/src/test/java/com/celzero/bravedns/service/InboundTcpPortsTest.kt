/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [InboundTcpPorts]; these mirror the rules enforced by
 * `parseInboundTCPPorts` in /tailscale/engine.go.
 */
class InboundTcpPortsTest {

    @Test
    fun `empty and whitespace are valid none`() {
        listOf("", "   ", "\t\n").forEach { spec ->
            val r = InboundTcpPorts.validate(spec)
            assertTrue("spec '$spec' should be valid", r.valid)
            assertEquals("", r.normalized)
            assertEquals("", r.error)
            assertTrue(InboundTcpPorts.parse(spec).isEmpty())
        }
    }

    @Test
    fun `single ports normalize`() {
        val r = InboundTcpPorts.validate("12100")
        assertTrue(r.valid)
        assertEquals("12100", r.normalized)
        assertEquals(listOf(12100 to 12100), InboundTcpPorts.parse("12100"))
    }

    @Test
    fun `ranges normalize`() {
        val r = InboundTcpPorts.validate("12070-12108")
        assertTrue(r.valid)
        assertEquals("12070-12108", r.normalized)
        assertEquals(listOf(12070 to 12108), InboundTcpPorts.parse("12070-12108"))
    }

    @Test
    fun `lists join with comma space`() {
        val r = InboundTcpPorts.validate("12070-12108, 8080")
        assertTrue(r.valid)
        assertEquals("12070-12108, 8080", r.normalized)
    }

    @Test
    fun `messy whitespace is trimmed`() {
        val r = InboundTcpPorts.validate(" 12070-12108,8080 ")
        assertTrue(r.valid)
        assertEquals("12070-12108, 8080", r.normalized)
        assertEquals(
            listOf(12070 to 12108, 8080 to 8080),
            InboundTcpPorts.parse(" 12070-12108,8080 ")
        )
    }

    @Test
    fun `boundaries are allowed`() {
        assertTrue(InboundTcpPorts.validate("1").valid)
        assertTrue(InboundTcpPorts.validate("65535").valid)
        assertTrue(InboundTcpPorts.validate("1-1052").valid)
        assertTrue(InboundTcpPorts.validate("1056-65535").valid)
    }

    @Test
    fun `malformed specs are invalid`() {
        listOf(
            "abc",
            "0",
            "65536",
            "12108-12070",
            "12070-",
            "-12108",
            "12070,",
            ",12100",
            "1-2-3",
            "12a",
            "1,,2",
            "-"
        ).forEach { spec ->
            val r = InboundTcpPorts.validate(spec)
            assertFalse("spec '$spec' should be invalid", r.valid)
            assertTrue("spec '$spec' should carry an error", r.error.isNotEmpty())
            assertEquals("", r.normalized)
        }
    }

    @Test
    fun `reserved ports are rejected and named`() {
        mapOf(
            "1053" to "1053",
            "1055" to "1055",
            "1050-1060" to "1053",
            "1-65535" to "1053"
        ).forEach { (spec, port) ->
            val r = InboundTcpPorts.validate(spec)
            assertFalse("spec '$spec' should be invalid", r.valid)
            assertTrue(
                "error for '$spec' should mention reserved port $port: ${r.error}",
                r.error.contains(port) && r.error.contains("reserved", ignoreCase = true)
            )
        }
    }
}
