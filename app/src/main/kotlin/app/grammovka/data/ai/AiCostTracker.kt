package app.grammovka.data.ai

import app.grammovka.data.db.AiCallLogDao
import app.grammovka.data.db.AiCallLogEntity
import app.grammovka.domain.model.AiPurpose
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/** USD per million tokens. */
data class ModelPrice(val input: Double, val output: Double, val cacheWrite5m: Double, val cacheRead: Double)

/**
 * Prices from https://platform.claude.com/docs/en/about-claude/pricing as of 2026-11-05.
 * СВЕРИТЬ С PRICING перед изменением моделей: логика цен не хардкодит, только этот конфиг.
 * Haiku 5.5 has higher prices above 100k prompt tokens; our prompts stay far below that.
 */
object AiPricing {
    val models: Map<String, ModelPrice> = mapOf(
        "claude-haiku-5-5" to ModelPrice(input = 0.10, output = 0.50, cacheWrite5m = 0.125, cacheRead = 0.01),
        "claude-sonnet-5-5" to ModelPrice(input = 2.0, output = 10.0, cacheWrite5m = 2.50, cacheRead = 0.10),
        "claude-opus-5-5" to ModelPrice(input = 5.0, output = 25.0, cacheWrite5m = 6.25, cacheRead = 0.50),
        // Qwen, International (Singapore), non-thinking, input ≤ 256K — https://www.alibabacloud.com/help/en/model-studio/model-pricing
        // (2026-11-20). qwen3.6-plus is from the official table; qwen-plus from a third-party summary of it — СВЕРИТЬ С PRICING.
        // Cached input is priced as plain input (overcounts a little; the discount is not verified).
        "qwen3.6-plus" to ModelPrice(input = 0.50, output = 3.0, cacheWrite5m = 0.50, cacheRead = 0.50),
        "qwen-plus" to ModelPrice(input = 0.40, output = 1.2, cacheWrite5m = 0.40, cacheRead = 0.40),
    )

    /** Unverified Qwen-side ids (qwen-max, vl, flash, deepseek…) are priced above any of them so the limit still bites. */
    private val QWEN_FALLBACK = ModelPrice(input = 1.6, output = 6.4, cacheWrite5m = 1.6, cacheRead = 1.6)

    /** $10 per 1,000 searches: Anthropic web search and Qwen `enable_search` in Singapore cost the same. */
    const val WEB_SEARCH_USD = 0.01

    /** Unknown model ids are priced like Sonnet (Claude) or above the Qwen table (others) so the monthly limit still bites. */
    fun price(model: String): ModelPrice = models[model] ?: if (model.startsWith("claude")) models.getValue("claude-sonnet-5-5") else QWEN_FALLBACK
}

data class Usage(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val webSearches: Int = 0,
) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, webSearches + o.webSearches)

    fun costUsd(model: String): Double {
        val p = AiPricing.price(model)
        return (input * p.input + output * p.output + cacheWrite * p.cacheWrite5m + cacheRead * p.cacheRead) / 1_000_000 +
            webSearches * AiPricing.WEB_SEARCH_USD
    }
}

class AiBudgetExceeded(val spentUsd: Double, val limitUsd: Double) : Exception("monthly AI limit")

/** Writes every response's usage to ai_call_log and enforces the monthly limit. */
class AiCostTracker(private val dao: AiCallLogDao, private val clock: () -> Long = System::currentTimeMillis) {

    fun monthStart(): Long =
        LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun observeMonthCost(): Flow<Double> = dao.observeCostSince(monthStart())

    suspend fun check(limitUsd: Double) {
        val spent = dao.costSince(monthStart())
        if (spent >= limitUsd) throw AiBudgetExceeded(spent, limitUsd)
    }

    suspend fun record(purpose: AiPurpose, model: String, promptVersion: String?, usage: Usage, ok: Boolean, errorCode: String? = null) {
        dao.insert(
            AiCallLogEntity(
                ts = clock(), purpose = purpose, model = model, promptVersion = promptVersion,
                inputTokens = usage.input, outputTokens = usage.output, cacheReadTokens = usage.cacheRead,
                cacheWriteTokens = usage.cacheWrite, webSearches = usage.webSearches,
                costUsd = usage.costUsd(model), ok = ok, errorCode = errorCode,
            ),
        )
    }
}
