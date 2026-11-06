package app.grammovka.data.ai

import app.grammovka.domain.activity.ActivityType
import app.grammovka.domain.activity.Intensity
import app.grammovka.domain.model.Confidence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** One workout as Claude read it. [grossKcal] is total energy for that time; the app takes rest out. */
data class ActivityProposal(
    val type: ActivityType,
    val title: String,
    val minutes: Double?,
    val distanceKm: Double?,
    val avgHr: Int?,
    val intensity: Intensity?,
    val grossKcal: Double?,
    val confidence: Confidence,
    val reasoning: String,
)

/** Pure mapping of the ACTIVITY structured output; tested with fixtures. Out-of-range numbers become null. */
object ActivityMapping {
    fun proposals(o: JsonObject): List<ActivityProposal> =
        (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e.jsonObject
            val type = i.s("type")?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() } ?: ActivityType.OTHER
            val minutes = i.d("minutes")?.takeIf { it > 0 && it <= 24 * 60 }
            ActivityProposal(
                type = type,
                title = i.s("title")?.takeIf(String::isNotBlank) ?: type.ru,
                minutes = minutes,
                distanceKm = i.d("distanceKm")?.takeIf { it > 0 && it < 1000 },
                avgHr = i.d("avgHr")?.toInt()?.takeIf { it in 40..230 },
                intensity = i.s("intensity")?.let { runCatching { Intensity.valueOf(it) }.getOrNull() },
                grossKcal = i.d("grossKcal")?.takeIf { it > 0 && it < 20_000 && minutes != null },
                // A vague description never comes back as HIGH without a duration.
                confidence = (i.s("confidence")?.let { runCatching { Confidence.valueOf(it) }.getOrNull() } ?: Confidence.LOW)
                    .let { if (minutes == null && it == Confidence.HIGH) Confidence.MEDIUM else it },
                reasoning = i.s("reasoning").orEmpty(),
            )
        }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
}
