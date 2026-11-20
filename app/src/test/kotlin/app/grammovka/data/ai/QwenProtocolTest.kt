package app.grammovka.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses below are trimmed from real Model Studio (Singapore) answers recorded 2026-11-20. */
class QwenProtocolTest {
    private fun parse(s: String) = QwenProtocol.lenient.parseToJsonElement(s).jsonObject

    @Test
    fun structuredReplyWithUsage() {
        val r = QwenProtocol.parseReply(
            parse(
                """{"choices":[{"message":{"role":"assistant","content":"{\"items\": [{\"fragment\": \"творожок 150\", \"foodId\": 5, \"grams\": 150.0, \"nameRu\": \"Творог 5%\"}]}"},"finish_reason":"stop","index":0}],
                "object":"chat.completion","usage":{"prompt_tokens":76,"completion_tokens":121,"total_tokens":197,"prompt_tokens_details":{"cached_tokens":6}},"model":"qwen-plus"}""",
            ),
        )
        assertEquals("stop", r.finishReason)
        assertEquals(Usage(input = 70, output = 121, cacheRead = 6), r.usage)
        val o = QwenProtocol.extractJson(r.content)!!
        assertEquals(5, o["items"]!!.jsonArray[0].jsonObject["foodId"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun jsonInsideProseOrFenceIsFound() {
        val o = QwenProtocol.extractJson("Вот ответ:\n```json\n{\"left\": \"красный\", \"right\": \"зелёный\"}\n```")!!
        assertEquals("зелёный", o["right"]!!.jsonPrimitive.content)
        assertNull(QwenProtocol.extractJson("It looks like your message contains only"))
        assertNull(QwenProtocol.extractJson("{ broken"))
    }

    @Test
    fun missingRequiredKeys() {
        val schema = AiService.obj("found" to AiService.str(), "notes" to AiService.nullable("string"))
        assertEquals(listOf("notes"), QwenProtocol.missingKeys(parse("""{"found":"x"}"""), schema))
        assertTrue(QwenProtocol.missingKeys(parse("""{"found":"x","notes":null}"""), schema).isEmpty())
    }

    @Test
    fun toolCallsAndHistory() {
        val r = QwenProtocol.parseReply(
            parse(
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_960b","type":"function",
                "function":{"name":"usda_search","arguments":"{\"query_en\": \"Asian pear\"}"},"index":0}]},"finish_reason":"tool_calls"}]}""",
            ),
        )
        assertEquals(listOf(QwenToolCall("call_960b", "usda_search", "{\"query_en\": \"Asian pear\"}")), r.toolCalls)
        val h = QwenProtocol.historyOf(r)
        assertEquals("assistant", h["role"]!!.jsonPrimitive.content)
        assertEquals(1, (h["tool_calls"] as JsonArray).size)
        val t = QwenProtocol.toolResult("call_960b", "[]")
        assertEquals("tool", t["role"]!!.jsonPrimitive.content)
        assertEquals("call_960b", t["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun imagesGoAsDataUrlsAndSwitchToJsonObject() {
        val c = QwenProtocol.userContent(listOf(Part.Jpeg(byteArrayOf(1, 2, 3)), Part.Text("Extract the nutrition table."))) as JsonArray
        val img = c[0].jsonObject
        assertEquals("image_url", img["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", img["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val schema = AiService.obj("a" to AiService.str())
        assertEquals("json_object", QwenProtocol.responseFormat(schema, hasImages = true)["type"]!!.jsonPrimitive.content)
        val f = QwenProtocol.responseFormat(schema, hasImages = false)
        assertEquals("json_schema", f["type"]!!.jsonPrimitive.content)
        assertEquals(schema, f["json_schema"]!!.jsonObject["schema"])
        // json_object mode needs the word JSON in the prompt; the schema text carries it.
        assertTrue(QwenProtocol.systemWithSchema("Be brief.", schema).contains("JSON"))
        assertEquals(JsonPrimitive("hi"), QwenProtocol.userContent(listOf(Part.Text("hi"))))
    }

    @Test
    fun streamLines() {
        assertEquals(
            listOf(QwenChunk.Text("С")),
            QwenProtocol.streamLine("""data: {"id":"c","choices":[{"index":0,"delta":{"content":"С"},"finish_reason":null}]}"""),
        )
        assertEquals(
            listOf(QwenChunk.Finish("stop")),
            QwenProtocol.streamLine("""data: {"id":"c","choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}]}"""),
        )
        assertEquals(
            listOf(QwenChunk.Totals(Usage(input = 14, output = 13))),
            QwenProtocol.streamLine("""data: {"id":"c","choices":[],"usage":{"prompt_tokens":14,"total_tokens":27,"completion_tokens":13}}"""),
        )
        assertEquals(listOf(QwenChunk.End), QwenProtocol.streamLine("data: [DONE]"))
        assertTrue(QwenProtocol.streamLine(": keep-alive").isEmpty())
        // Thinking deltas are not part of the answer.
        assertTrue(QwenProtocol.streamLine("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}""").isEmpty())
    }

    @Test
    fun errors() {
        val denied = """{"error":{"message":"Access to model denied. Please make sure you are eligible for using the model.","type":"AccessDenied.Unpurchased","code":"AccessDenied.Unpurchased"}}"""
        val (code, msg) = QwenProtocol.errorOf(denied)
        assertEquals("AccessDenied.Unpurchased", code)
        assertTrue(msg!!.startsWith("Access to model denied"))
        assertEquals(ClaudeErrorKind.NO_PERMISSION, QwenProtocol.kindOf(403, code))
        assertEquals(ClaudeErrorKind.INVALID_KEY, QwenProtocol.kindOf(401, "InvalidApiKey"))
        assertEquals(ClaudeErrorKind.BILLING, QwenProtocol.kindOf(400, "Arrearage"))
        assertEquals(ClaudeErrorKind.BILLING, QwenProtocol.kindOf(403, "Arrearage"))
        assertEquals(ClaudeErrorKind.RATE_LIMIT, QwenProtocol.kindOf(429, "Throttling.RateQuota"))
        assertTrue(QwenProtocol.retryable(429, "Throttling.RateQuota"))
        assertTrue(!QwenProtocol.retryable(429, "Throttling.AllocationQuota"))
        assertTrue(!QwenProtocol.retryable(403, "Arrearage"))
        assertTrue(QwenProtocol.retryable(503, null))
        assertEquals(ClaudeErrorKind.BAD_REQUEST, QwenProtocol.kindOf(400, "DataInspectionFailed"))
        assertTrue(QwenProtocol.messageOf("DataInspectionFailed", "Input data may contain inappropriate content.")!!.contains("фильтр"))
        // DashScope's flat shape.
        assertEquals("InvalidApiKey" to "Invalid API-key provided.", QwenProtocol.errorOf("""{"code":"InvalidApiKey","message":"Invalid API-key provided.","request_id":"x"}"""))
        assertEquals(null to null, QwenProtocol.errorOf("<html>502</html>"))
    }

    @Test
    fun endpoints() {
        val ws = "https://ws-f31yubcgflpk9r5y.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
        assertEquals(ws, QwenEndpoint.normalize("ws-f31yubcgflpk9r5y"))
        assertEquals(ws, QwenEndpoint.normalize("  WS-F31YUBCGFLPK9R5Y "))
        assertEquals(ws, QwenEndpoint.normalize("$ws/chat/completions"))
        assertEquals(ws, QwenEndpoint.normalize("ws-f31yubcgflpk9r5y.ap-southeast-1.maas.aliyuncs.com"))
        assertEquals(QwenEndpoint.SHARED, QwenEndpoint.normalize(""))
        assertEquals(QwenEndpoint.SHARED, QwenEndpoint.normalize("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/"))
        assertNull(QwenEndpoint.normalize("не адрес"))
        assertEquals("ws-f31yubcgflpk9r5y", QwenEndpoint.workspaceOf(ws))
        assertNull(QwenEndpoint.workspaceOf(QwenEndpoint.SHARED))
    }

    @Test
    fun pricing() {
        // 1M in + 1M out on qwen3.6-plus = $3.50; unknown Qwen-side ids are priced above the table.
        assertEquals(3.5, Usage(input = 1_000_000, output = 1_000_000).costUsd("qwen3.6-plus"), 1e-9)
        assertTrue(Usage(input = 1_000_000).costUsd("deepseek-v4.1-flash") > Usage(input = 1_000_000).costUsd("qwen3.6-plus"))
        assertEquals(0.01, Usage(webSearches = 1).costUsd("qwen3.6-plus"), 1e-9)
        assertEquals(Usage(input = 1_000_000).costUsd("claude-sonnet-5-5"), Usage(input = 1_000_000).costUsd("claude-unknown"), 1e-9)
    }

    @Suppress("unused")
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
}
