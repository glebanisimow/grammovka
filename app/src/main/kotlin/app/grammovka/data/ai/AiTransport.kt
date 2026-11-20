package app.grammovka.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** A piece of a user turn. */
sealed interface Part {
    data class Text(val text: String) : Part

    /** Already downscaled to ~1568 px on the long side. */
    class Jpeg(val bytes: ByteArray) : Part
}

/** A client tool the model may call; [schema] is a JSON Schema object (all properties required, no extras). */
data class ToolDef(val name: String, val description: String, val schema: JsonObject)

data class ToolOutput(val content: String, val isError: Boolean = false)

/** What a call returned: the JSON answer (or the final tool's input) and the tokens it took. */
data class AiAnswer(val json: JsonObject, val usage: Usage)

/**
 * One provider's wire format. [AiService] owns prompts, schemas, keys, the monthly limit, cost records and all
 * mapping to drafts (invariants 1–3), so both providers behave the same; a transport only speaks its API.
 * Errors are [ClaudeException] with [ClaudeException.provider] set.
 */
interface AiTransport {
    val provider: AiProvider

    /** One request whose answer is a JSON object matching [schema] (structured output). */
    suspend fun json(key: String, model: String, system: String, parts: List<Part>, schema: JsonObject, maxTokens: Int, timeoutMs: Long): AiAnswer

    /**
     * Tool loop: the model calls [tools] (run by [exec]) and web search (the provider's own), and finishes by calling
     * [final], whose input is returned. [AiAnswer.usage] is the sum over all rounds; it is also attached to a thrown
     * [ClaudeException] as [ClaudeException.usage] so a failed loop is still charged.
     */
    suspend fun toolLoop(
        key: String,
        model: String,
        system: String,
        user: String,
        tools: List<ToolDef>,
        final: ToolDef,
        exec: suspend (name: String, input: JsonObject) -> ToolOutput,
        timeoutMs: Long,
    ): AiAnswer

    /** Plain text, streamed. The last event is [StreamEvent.Done]; a refusal or content filter is stopReason "refusal". */
    fun stream(key: String, model: String, system: String, user: String, maxTokens: Int): Flow<StreamEvent>
}
