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

/**
 * Parser/validator for the user-configurable inbound Tailscale TCP allowlist.
 *
 * The spec is a comma-separated list of single ports ("12100") and inclusive
 * ranges ("12070-12108"); surrounding whitespace is tolerated. An empty (or
 * whitespace-only) spec means "no ports": inbound TCP is then default-deny,
 * which is also what the Go engine does with an empty allowlist.
 *
 * Port 1053 (the engine's DNS relay) and 1055 (its SOCKS5 listener) are
 * reserved and can never be allowlisted, neither directly nor inside a range.
 *
 * Rules here mirror `parseInboundTCPPorts` in /tailscale/engine.go exactly;
 * keep both in sync.
 */
object InboundTcpPorts {

    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    // reserved loopback listeners owned by the engine itself
    private const val DNS_PORT = 1053
    private const val SOCKS5_PORT = 1055

    private val RESERVED = mapOf(DNS_PORT to "DNS relay", SOCKS5_PORT to "SOCKS5 proxy")

    /** Label used for the user-supplied ports (everything not from a preset). */
    private const val CUSTOM_LABEL = "Custom"

    /** Summary shown when no preset is selected and Custom is empty. */
    private const val NONE_SUMMARY = "None (blocked)"

    /** Preview shown in the editor dialog when nothing is allowlisted. */
    private const val NONE_PREVIEW = "None (all inbound TCP blocked)"

    /** Suffix appended to the card summary while Shields Up is on. */
    private const val SHIELDS_UP_SUFFIX = " (blocked while Shields Up is on)"

    /** A named, starter group of ports offered as a checkbox in the UI. */
    data class Preset(
        val id: String,
        val label: String,
        val spec: String,
        val note: String = ""
    )

    /**
     * Starter presets, in display order. `flux` is offered first but is never
     * pre-selected: nothing is allowlisted until the user opts in.
     */
    val PRESETS: List<Preset> =
        listOf(
            Preset("flux", "Flux", "12070-12108"),
            Preset("adb", "ADB over TCP", "5555", "adb tcpip 5555"),
            Preset("kde_connect", "KDE Connect", "1714-1764"),
            Preset("termux_ssh", "Termux SSH", "8022"),
            Preset("syncthing", "Syncthing", "22000")
        )

    private val PRESETS_BY_ID: Map<String, Preset> = PRESETS.associateBy { it.id }

    /** A group of ports with the label it came from (a preset name or Custom). */
    data class Group(val label: String, val spec: String)

    /** Outcome of a validation: either [normalized] or a user-facing [error]. */
    data class Result(
        val valid: Boolean,
        /** Trimmed, whitespace-normalized spec ("12070-12108, 8080"); "" when none. */
        val normalized: String,
        /** User-facing message when [valid] is false; "" otherwise. */
        val error: String
    ) {
        companion object {
            fun ok(normalized: String) = Result(true, normalized, "")

            fun err(error: String) = Result(false, "", error)
        }
    }

    /**
     * Validates and normalizes [spec], never throwing for expected invalid
     * input. Use [parse] only for well-formed specs (it throws otherwise).
     */
    fun validate(spec: String): Result {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return Result.ok("")

        val parts = mutableListOf<String>()
        for (raw in trimmed.split(",")) {
            val token = raw.trim()
            if (token.isEmpty()) {
                return Result.err("Remove the empty entry: separate ports with commas, e.g. 12070-12108, 8080")
            }
            val start: Int
            val end: Int
            when (token.count { it == '-' }) {
                0 -> {
                    val p = parsePort(token)
                        ?: return Result.err("\"$token\" is not a port number between 1 and 65535")
                    start = p
                    end = p
                }
                1 -> {
                    val idx = token.indexOf('-')
                    val lo = token.substring(0, idx).trim()
                    val hi = token.substring(idx + 1).trim()
                    if (lo.isEmpty() || hi.isEmpty()) {
                        return Result.err("\"$token\" is an incomplete range; use start-end, e.g. 12070-12108")
                    }
                    val a = parsePort(lo)
                        ?: return Result.err("\"$lo\" is not a port number between 1 and 65535")
                    val b = parsePort(hi)
                        ?: return Result.err("\"$hi\" is not a port number between 1 and 65535")
                    if (a > b) {
                        return Result.err("\"$token\" starts after it ends; use start-end, e.g. 12070-12108")
                    }
                    start = a
                    end = b
                }
                else ->
                    return Result.err(
                        "\"$token\" is malformed; use single ports or ranges, e.g. 12070-12108, 8080"
                    )
            }
            for (reserved in RESERVED.keys.sorted()) {
                if (reserved < start || reserved > end) continue
                return Result.err("Port $reserved is reserved for this device's ${RESERVED[reserved]} and cannot be forwarded")
            }
            parts.add(if (start == end) start.toString() else "$start-$end")
        }
        return Result.ok(parts.joinToString(", "))
    }

    /**
     * Splits an already-validated spec into inclusive (start, end) pairs; an
     * empty spec yields no pairs. Throws [IllegalArgumentException] if [spec]
     * does not validate — run [validate] first.
     */
    fun parse(spec: String): List<Pair<Int, Int>> {
        val result = validate(spec)
        require(result.valid) { result.error }
        if (result.normalized.isEmpty()) return emptyList()
        return result.normalized.split(",").map { token ->
            val t = token.trim()
            val idx = t.indexOf('-')
            if (idx < 0) {
                val p = t.toInt()
                p to p
            } else {
                t.substring(0, idx).trim().toInt() to t.substring(idx + 1).trim().toInt()
            }
        }
    }

    /**
     * Parses a stored comma-separated preset-id string into known preset ids
     * in canonical [PRESETS] order. Unknown ids, blanks and duplicates are
     * dropped.
     */
    fun parsePresetIds(raw: String): List<String> {
        return canonicalPresetIds(raw.split(","))
    }

    /** Serializes preset ids to their canonical, deduped, comma-joined form. */
    fun serializePresetIds(ids: Collection<String>): String {
        return canonicalPresetIds(ids).joinToString(",")
    }

    private fun canonicalPresetIds(ids: Collection<String>): List<String> {
        val wanted = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return PRESETS.map { it.id }.filter { wanted.contains(it) }
    }

    /**
     * Combines the selected presets and the Custom spec into the single
     * comma-separated spec handed to the engine. An invalid Custom spec
     * invalidates the whole result (nothing is applied); otherwise identical
     * entries typed in Custom and already covered by a preset are emitted
     * once. Empty + empty is a valid "allowlist nothing".
     */
    fun combineSpec(presetIds: Collection<String>, customSpec: String): Result {
        val custom = validate(customSpec)
        if (!custom.valid) return custom

        val combined = mutableListOf<String>()
        parsePresetIds(presetIds.joinToString(",")).forEach { id ->
            PRESETS_BY_ID[id]?.spec?.let { combined.add(it) }
        }
        if (custom.normalized.isNotEmpty()) {
            custom.normalized.split(",").forEach { token ->
                val t = token.trim()
                if (t.isNotEmpty() && !combined.contains(t)) combined.add(t)
            }
        }
        return Result.ok(combined.joinToString(", "))
    }

    /**
     * One group per selected preset (canonical order) plus a trailing Custom
     * group when the Custom spec is non-empty. An invalid Custom spec omits
     * only the Custom group; callers surface `validate`'s error separately.
     */
    fun activeGroups(presetIds: Collection<String>, customSpec: String): List<Group> {
        val groups = mutableListOf<Group>()
        parsePresetIds(presetIds.joinToString(",")).forEach { id ->
            PRESETS_BY_ID[id]?.let { groups.add(Group(it.label, it.spec)) }
        }
        val custom = validate(customSpec)
        if (custom.valid && custom.normalized.isNotEmpty()) {
            groups.add(Group(CUSTOM_LABEL, custom.normalized))
        }
        return groups
    }

    /** Card subtitle for the settings row, e.g. "Flux (12070-12108) · ADB over TCP (5555)". */
    fun formatCardSubtitle(
        presetIds: Collection<String>,
        customSpec: String,
        shieldsUp: Boolean
    ): String {
        val groups = activeGroups(presetIds, customSpec)
        if (groups.isEmpty()) return NONE_SUMMARY
        val summary = groups.joinToString(" · ") { "${it.label} (${it.spec})" }
        return if (shieldsUp) summary + SHIELDS_UP_SUFFIX else summary
    }

    /** Multi-line editor preview, e.g. "Flux: 12070-12108\nADB over TCP: 5555". */
    fun formatDialogGroups(presetIds: Collection<String>, customSpec: String): String {
        val groups = activeGroups(presetIds, customSpec)
        if (groups.isEmpty()) return NONE_PREVIEW
        return groups.joinToString("\n") { "${it.label}: ${it.spec}" }
    }

    private fun parsePort(token: String): Int? {
        if (token.isEmpty() || token.any { !it.isDigit() }) return null
        val v = token.toLongOrNull() ?: return null
        return if (v in PORT_MIN..PORT_MAX) v.toInt() else null
    }
}
