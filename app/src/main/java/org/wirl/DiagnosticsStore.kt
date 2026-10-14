package org.wirl

import org.json.JSONArray
import org.json.JSONObject

/** Stores diagnostic entries as JSON. Unreadable data reads as an empty log. */
object DiagnosticsStore {
    fun encode(entries: List<DiagnosticEntry>): String {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("time", e.time)
                    .put("engines", e.engines)
                    .put("errorCode", e.errorCode ?: JSONObject.NULL)
                    .put("message", e.message)
                    .put("stackTrace", e.stackTrace ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<DiagnosticEntry> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            DiagnosticEntry(
                time = o.getLong("time"),
                engines = o.optString("engines"),
                errorCode = if (o.isNull("errorCode")) null else o.getInt("errorCode"),
                message = o.optString("message"),
                stackTrace = if (o.isNull("stackTrace")) null else o.getString("stackTrace"),
            )
        }
    }.getOrDefault(emptyList())
}
