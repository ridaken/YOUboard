// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads retained records without changing their schema or retaining arbitrary payloads. */
internal class DiagnosticExportSummary {
    private val bursts = java.util.ArrayDeque<String>()
    private val transitions = java.util.ArrayDeque<String>()
    private var incomplete = false
    private var older = false
    private val seen = java.util.LinkedHashSet<String>()
    fun missingHistory() { incomplete = true }
    fun observe(line: String) {
        try {
            val record = Json.parseToJsonElement(line).jsonObject
            val reason = record["reason"]?.jsonPrimitive?.content ?: return
            val sequence = record["sequence"]?.jsonPrimitive?.content
            if (sequence != null) {
                val key = "${record["session"]}:$sequence"
                if (!seen.add(key)) return
                if (seen.size > 256) seen.remove(seen.first())
            }
            val data = record["data"]?.jsonObject ?: return
            val utc = record["utc"]?.jsonPrimitive?.content ?: "unknown time"
            val correlation = "session=${record["session"]?.jsonPrimitive?.content} " +
                "input=${data["inputSession"]} view=${data["viewActivation"]} update=${data["geometryUpdate"]}"
            if (reason == "DISRUPTION") {
                if (bursts.size >= 16) bursts.removeFirst()
                bursts.addLast("$utc ${data["kind"]} ${data["phase"]} count=${data["count"]} $correlation")
            }
            if (reason == "GEOMETRY_APPLIED" || reason == "POSTURE_COMMITTED" || reason == "EDITOR_RECOVERY") {
                if (transitions.size >= 20) transitions.removeFirst()
                transitions.addLast("$utc $reason posture=${data["committed"]} split=${data["after"]?.jsonObject?.get("split")} " +
                    "recovery=${data["outcome"]} causes=${data["refreshCauses"]} $correlation")
            }
            if (reason in setOf("QUEUE_OVERFLOW", "STORAGE_FAILURE", "STORAGE_RECOVERED")) incomplete = true
            if ("inputSession" !in data) older = true
        } catch (_: Exception) { incomplete = true }
    }
    fun text() = buildString {
        appendLine("Keyboard disruption summary")
        if (bursts.isEmpty()) appendLine("No disruption bursts found in retained history.")
        else bursts.forEach(::appendLine)
        appendLine("Recent correlated transitions (up to 20):")
        transitions.forEach(::appendLine)
        if (older) appendLine("Some records predate lifecycle correlation; they cannot establish whether typing was interrupted.")
        if (incomplete) appendLine("History may be incomplete: dropped, unavailable, or unreadable records were detected.")
        appendLine("History is limited by retention; absence of a warning does not rule out a problem.")
    }
}
