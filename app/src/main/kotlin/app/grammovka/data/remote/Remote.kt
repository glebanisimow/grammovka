package app.grammovka.data.remote

import app.grammovka.data.db.RemoteCacheDao
import app.grammovka.data.db.RemoteCacheEntity
import app.grammovka.data.net.NetException
import app.grammovka.data.net.NetProblem
import app.grammovka.data.net.getText
import app.grammovka.data.settings.Secret
import app.grammovka.data.settings.SecretStore
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

/** 30-day cache for OFF and USDA (spec: OFF limits are strict; never search as you type). */
class RemoteCache(private val dao: RemoteCacheDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun getOrFetch(key: String, fetch: suspend () -> String): String {
        val fresh = clock() - TTL_MS
        dao.get(key, fresh)?.let { return it.body }
        val body = fetch()
        dao.put(RemoteCacheEntity(key, body, clock()))
        return body
    }

    suspend fun purge() = dao.purge(clock() - TTL_MS)

    companion object {
        const val TTL_MS = 30L * 24 * 3600 * 1000
    }
}

// ---------------------------------------------------------------- Open Food Facts

data class OffProduct(
    val code: String,
    val name: String?,
    val brand: String?,
    val quantity: String?,
    /** Null when OFF has no complete per-100 g kcal/protein/fat/carbs. */
    val per100: Nutrients?,
)

interface OffApi {
    /** Null when the barcode is unknown to OFF. */
    suspend fun product(barcode: String): OffProduct?
    suspend fun search(query: String, limit: Int = 5): List<OffProduct>
}

/**
 * Open Food Facts. Product: https://openfoodfacts.github.io/openfoodfacts-server/api/tutorial-off-api/
 * (`/api/v2/product/{code}.json?fields=…`, 15 req/min). Full-text search is Search-a-licious
 * (`https://search.openfoodfacts.org/search?q=…`, 10 req/min); v2 has no full-text search.
 * Custom User-Agent required: https://openfoodfacts.github.io/openfoodfacts-server/api/
 * Data is ODbL: fine for personal use, never publish a derived database.
 */
class OffClient(private val http: OkHttpClient, private val cache: RemoteCache, private val userAgent: String) : OffApi {

    override suspend fun product(barcode: String): OffProduct? {
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FIELDS).build()
        val body = cache.getOrFetch("off:product:$barcode") { http.getText(req(url.toString())) }
        return parseProduct(body)
    }

    override suspend fun search(query: String, limit: Int): List<OffProduct> {
        val q = query.trim()
        val url = "https://search.openfoodfacts.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$q countries_tags:\"en:poland\"")
            .addQueryParameter("page_size", limit.toString())
            .addQueryParameter("fields", "code,product_name,product_name_pl,brands,quantity,nutriments")
            .build()
        val body = cache.getOrFetch("off:search:${q.lowercase()}:$limit") { http.getText(req(url.toString())) }
        return parseSearch(body)
    }

    private fun req(url: String) = Request.Builder().url(url).header("User-Agent", userAgent).get().build()

    companion object {
        const val FIELDS = "code,product_name,product_name_pl,brands,quantity,serving_size,nutrition_data_per,nutriments"

        fun parseProduct(body: String): OffProduct? {
            val root = lenientJson.parseToJsonElement(body).jsonObject
            if (root["status"]?.jsonPrimitive?.intOrNull != 1) return null
            val p = root["product"].obj() ?: return null
            return productOf(p, root.str("code"))
        }

        fun parseSearch(body: String): List<OffProduct> {
            val hits = lenientJson.parseToJsonElement(body).jsonObject["hits"] as? JsonArray ?: return emptyList()
            return hits.mapNotNull { h -> h.obj()?.let { productOf(it, null) } }
        }

        private fun productOf(p: JsonObject, fallbackCode: String?): OffProduct? {
            val code = p.str("code") ?: fallbackCode ?: return null
            val brands = when (val b = p["brands"]) {
                is JsonArray -> b.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
                is JsonPrimitive -> b.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
            return OffProduct(code, p.str("product_name_pl") ?: p.str("product_name"), brands, p.str("quantity"), nutrients(p["nutriments"].obj()))
        }

        /** kcal from energy-kcal_100g, else energy-kj_100g / 4.184 (spec). */
        fun nutrients(n: JsonObject?): Nutrients? {
            n ?: return null
            val kcal = n.num("energy-kcal_100g") ?: n.num("energy-kj_100g")?.let(Nutrition::kcalFromKj) ?: return null
            val p = n.num("proteins_100g") ?: return null
            val f = n.num("fat_100g") ?: return null
            val c = n.num("carbohydrates_100g") ?: return null
            return Nutrients(kcal, p, f, c, n.num("fiber_100g"))
        }
    }
}

// ---------------------------------------------------------------- USDA FoodData Central

data class UsdaFood(val fdcId: Long, val description: String, val dataType: String?, val per100: Nutrients?)

interface UsdaApi {
    suspend fun search(queryEn: String, dataTypes: List<String>, limit: Int = 5): List<UsdaFood>
    suspend fun food(fdcId: Long): UsdaFood?
}

/**
 * USDA FDC: https://fdc.nal.usda.gov/api-guide. Nutrient ids (checked on live data 2026-11-05):
 * 1008 energy kcal (Foundation may lack it → 2047 / 2048 Atwater), 1003 protein, 1004 fat, 1005 carbs, 1079 fiber.
 * Search returns `foodNutrients[].nutrientId/value`; `/food/{id}` returns `foodNutrients[].nutrient.id/amount`.
 */
class UsdaClient(
    private val http: OkHttpClient,
    private val cache: RemoteCache,
    private val secrets: SecretStore,
) : UsdaApi {

    private val slow = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun key(): String = secrets.get(Secret.USDA_KEY) ?: throw NetException(NetProblem.NO_KEY)

    override suspend fun search(queryEn: String, dataTypes: List<String>, limit: Int): List<UsdaFood> {
        val types = dataTypes.filter { it in ALLOWED_TYPES }.ifEmpty { listOf("Foundation", "SR Legacy") }
        val cacheKey = "usda:search:${queryEn.lowercase().trim()}:${types.joinToString(",")}:$limit"
        val body = cache.getOrFetch(cacheKey) {
            val url = "https://api.nal.usda.gov/fdc/v1/foods/search".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key())
                .addQueryParameter("query", queryEn)
                .addQueryParameter("dataType", types.joinToString(","))
                .addQueryParameter("pageSize", limit.toString())
                .build()
            slow.getText(Request.Builder().url(url).get().build())
        }
        return parseSearch(body)
    }

    override suspend fun food(fdcId: Long): UsdaFood? {
        val body = cache.getOrFetch("usda:food:$fdcId") {
            val url = "https://api.nal.usda.gov/fdc/v1/food/$fdcId".toHttpUrl().newBuilder().addQueryParameter("api_key", key()).build()
            slow.getText(Request.Builder().url(url).get().build())
        }
        return parseFood(body)
    }

    companion object {
        val ALLOWED_TYPES = setOf("Foundation", "SR Legacy", "Survey (FNDDS)")

        fun parseSearch(body: String): List<UsdaFood> {
            val foods = lenientJson.parseToJsonElement(body).jsonObject["foods"] as? JsonArray ?: return emptyList()
            return foods.mapNotNull { e ->
                val f = e.obj() ?: return@mapNotNull null
                val values = (f["foodNutrients"] as? JsonArray).orEmpty().mapNotNull { n ->
                    val o = n.obj() ?: return@mapNotNull null
                    val id = o["nutrientId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val unit = o.str("unitName")?.lowercase()
                    if (id in ENERGY_IDS && unit != "kcal") return@mapNotNull null
                    o.num("value")?.let { id to it }
                }.toMap()
                UsdaFood(f["fdcId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null, f.str("description").orEmpty(), f.str("dataType"), nutrients(values))
            }
        }

        fun parseFood(body: String): UsdaFood? {
            val f = lenientJson.parseToJsonElement(body).jsonObject
            val values = (f["foodNutrients"] as? JsonArray).orEmpty().mapNotNull { n ->
                val o = n.obj() ?: return@mapNotNull null
                val nut = o["nutrient"].obj() ?: return@mapNotNull null
                val id = nut["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (id in ENERGY_IDS && nut.str("unitName")?.lowercase() != "kcal") return@mapNotNull null
                o.num("amount")?.let { id to it }
            }.toMap()
            return UsdaFood(f["fdcId"]?.jsonPrimitive?.longOrNull ?: return null, f.str("description").orEmpty(), f.str("dataType"), nutrients(values))
        }

        private val ENERGY_IDS = setOf(1008, 2047, 2048)

        /**
         * USDA "Carbohydrate, by difference" (1005) includes fiber; the catalog follows EU/Polish labels,
         * where carbohydrates exclude fiber, so the energy check 4P + 4C + 9F + 2·fiber holds for both.
         */
        private fun nutrients(v: Map<Int, Double>): Nutrients? {
            val kcal = v[1008] ?: v[2047] ?: v[2048] ?: return null
            val fiber = v[1079]
            val carbs = (v[1005] ?: return null) - (fiber ?: 0.0)
            return Nutrients(kcal, v[1003] ?: return null, v[1004] ?: return null, carbs.coerceAtLeast(0.0), fiber)
        }
    }
}
