package app.chocola.api

import app.chocola.data.Redactor
import app.chocola.data.objects
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import org.json.JSONObject

/** Validation errors may echo the whole request. Only human error fields may leave this decoder. */
internal object HttpErrorText {
    fun decode(body: String): String {
        val value = runCatching { JSONObject(body) }.getOrNull()
        val message = (value?.optJSONObject("error")?.opt("message") as? String).orEmpty().ifBlank {
            (value?.opt("message") as? String).orEmpty()
        }.ifBlank {
            (value?.opt("error") as? String).orEmpty()
        }.ifBlank {
            (value?.opt("detail") as? String).orEmpty()
        }.ifBlank {
            value?.optJSONArray("detail")?.objects().orEmpty().mapNotNull { error ->
                val text = (error.opt("msg") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val loc = error.optJSONArray("loc")?.let { path -> (0 until path.length()).joinToString(".") { path.opt(it).toString() } }.orEmpty()
                if (loc.isBlank()) text else "$loc：$text"
            }.take(3).joinToString("；")
        }.ifBlank {
            // peekBody is bounded. Read a message that precedes a large, truncated input echo.
            runCatching { JsonFactory().createParser(body).use { parser ->
                while (parser.nextToken() != null) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) continue
                    val name = parser.currentName
                    val token = parser.nextToken()
                    if (name in setOf("input", "request", "body")) parser.skipChildren()
                    else if (name in setOf("message", "msg", "detail") && token == JsonToken.VALUE_STRING) return@use parser.text
                }
                ""
            } }.getOrDefault("")
        }.ifBlank {
            if (body.trimStart().startsWith('{') || body.trimStart().startsWith('[') || body.trimStart().startsWith('<')) "" else body.take(500)
        }
        return Redactor.clean(message).take(1000)
    }
}
