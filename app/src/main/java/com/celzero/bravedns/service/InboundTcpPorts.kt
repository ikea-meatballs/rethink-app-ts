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

    private fun parsePort(token: String): Int? {
        if (token.isEmpty() || token.any { !it.isDigit() }) return null
        val v = token.toLongOrNull() ?: return null
        return if (v in PORT_MIN..PORT_MAX) v.toInt() else null
    }
}
