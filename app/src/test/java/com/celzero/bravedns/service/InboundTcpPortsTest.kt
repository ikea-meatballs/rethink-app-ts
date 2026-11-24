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

    @Test
    fun `presets are the five starter groups with flux first`() {
        assertEquals(5, InboundTcpPorts.PRESETS.size)
        assertEquals(
            listOf("flux", "adb", "kde_connect", "termux_ssh", "syncthing"),
            InboundTcpPorts.PRESETS.map { it.id }
        )
        assertEquals("12070-12108", InboundTcpPorts.PRESETS[0].spec)
        assertEquals("5555", InboundTcpPorts.PRESETS[1].spec)
        assertEquals("1714-1764", InboundTcpPorts.PRESETS[2].spec)
        assertEquals("8022", InboundTcpPorts.PRESETS[3].spec)
        assertEquals("22000", InboundTcpPorts.PRESETS[4].spec)
    }

    @Test
    fun `parsePresetIds drops blanks duplicates and unknown ids`() {
        assertEquals(emptyList<String>(), InboundTcpPorts.parsePresetIds(""))
        assertEquals(emptyList<String>(), InboundTcpPorts.parsePresetIds("   "))
        assertEquals(listOf("flux", "adb"), InboundTcpPorts.parsePresetIds("flux,adb"))
        assertEquals(
            listOf("flux", "adb"),
            InboundTcpPorts.parsePresetIds(" adb, flux, adb, unknown ")
        )
        assertEquals(emptyList<String>(), InboundTcpPorts.parsePresetIds("unknown,other"))
    }

    @Test
    fun `serializePresetIds is canonical and deduped`() {
        assertEquals("", InboundTcpPorts.serializePresetIds(emptyList()))
        assertEquals("flux,adb", InboundTcpPorts.serializePresetIds(listOf("adb", "flux")))
        assertEquals("flux,adb", InboundTcpPorts.serializePresetIds(listOf("adb", "flux", "adb")))
        assertEquals("", InboundTcpPorts.serializePresetIds(listOf("unknown")))
    }

    @Test
    fun `combineSpec covers presets custom and both`() {
        assertEquals("12070-12108", InboundTcpPorts.combineSpec(listOf("flux"), "").normalized)
        assertEquals(
            "12070-12108, 5555",
            InboundTcpPorts.combineSpec(listOf("adb", "flux"), "").normalized
        )
        assertEquals("8080", InboundTcpPorts.combineSpec(emptyList(), "8080").normalized)
        assertEquals("12070-12108, 8080", InboundTcpPorts.combineSpec(listOf("flux"), "8080").normalized)
    }

    @Test
    fun `combineSpec dedupes preset specs retyped in custom`() {
        assertEquals(
            "12070-12108, 8080",
            InboundTcpPorts.combineSpec(listOf("flux"), "12070-12108, 8080").normalized
        )
        assertEquals(
            "12070-12108, 5555",
            InboundTcpPorts.combineSpec(listOf("flux", "adb"), "5555").normalized
        )
    }

    @Test
    fun `combineSpec keeps reserved ports rejected even with presets`() {
        listOf("1053", "1055", "1050-1060").forEach { custom ->
            val r = InboundTcpPorts.combineSpec(listOf("flux"), custom)
            assertFalse("custom '$custom' should be invalid", r.valid)
            assertEquals("", r.normalized)
            assertTrue("custom '$custom' should carry an error", r.error.isNotEmpty())
        }
    }

    @Test
    fun `combineSpec of nothing is an empty ok`() {
        val r = InboundTcpPorts.combineSpec(emptyList(), "")
        assertTrue(r.valid)
        assertEquals("", r.normalized)
    }

    @Test
    fun `activeGroups labels each preset and custom in order`() {
        val groups =
            InboundTcpPorts.activeGroups(listOf("adb", "flux"), "8080")
        assertEquals(
            listOf(
                InboundTcpPorts.Group("Flux", "12070-12108"),
                InboundTcpPorts.Group("ADB over TCP", "5555"),
                InboundTcpPorts.Group("Custom", "8080")
            ),
            groups
        )
        assertTrue(InboundTcpPorts.activeGroups(emptyList(), "").isEmpty())
    }

    @Test
    fun `activeGroups omits custom when it is invalid`() {
        val groups = InboundTcpPorts.activeGroups(listOf("flux"), "1053")
        assertEquals(listOf(InboundTcpPorts.Group("Flux", "12070-12108")), groups)
    }

    @Test
    fun `formatCardSubtitle summarizes groups and shields up`() {
        assertEquals(
            "None (blocked)",
            InboundTcpPorts.formatCardSubtitle(emptyList(), "", false)
        )
        assertEquals(
            "Flux (12070-12108)",
            InboundTcpPorts.formatCardSubtitle(listOf("flux"), "", false)
        )
        assertEquals(
            "Flux (12070-12108) · ADB over TCP (5555)",
            InboundTcpPorts.formatCardSubtitle(listOf("flux", "adb"), "", false)
        )
        assertEquals(
            "Flux (12070-12108) · Custom (8080)",
            InboundTcpPorts.formatCardSubtitle(listOf("flux"), "8080", false)
        )
        assertEquals(
            "Flux (12070-12108) (blocked while Shields Up is on)",
            InboundTcpPorts.formatCardSubtitle(listOf("flux"), "", true)
        )
    }

    @Test
    fun `formatDialogGroups previews one group per line`() {
        assertEquals(
            "None (all inbound TCP blocked)",
            InboundTcpPorts.formatDialogGroups(emptyList(), "")
        )
        assertEquals(
            "Flux: 12070-12108\nADB over TCP: 5555",
            InboundTcpPorts.formatDialogGroups(listOf("flux", "adb"), "")
        )
        assertEquals(
            "Flux: 12070-12108\nCustom: 8080",
            InboundTcpPorts.formatDialogGroups(listOf("flux"), "8080")
        )
    }
}
