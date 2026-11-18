package com.ai.assistance.operit.core.companion

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** Journal identity survives JSONObject copies and the streaming snapshot writer. */
internal object CategoryJournalFingerprint {
    const val PREFIX = "json-number-v2:"

    fun digest(value: Any?): String = PREFIX + InnerStructure.hash(canonical(value))

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
