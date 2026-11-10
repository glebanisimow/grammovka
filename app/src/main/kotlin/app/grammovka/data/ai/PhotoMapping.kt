package app.grammovka.data.ai

import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.text.Normalize
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Pure part of the photo estimate: which recognised items need a database lookup and how the PHOTO answer and
 * the RESOLVE proposals become drafts. Numbers come only from a printed table or from RESOLVE (invariant 1).
 */
object PhotoMapping {

    /**
     * Recognised items without repeats. The model tends to list one product once per photo (the bun, then the
     * same bun on the package shot), which doubled the lines on the confirm screen. Items are the same food when
     * their names or their search queries are equal, or when one name and one query are contained in the other
     * («Булочка ржаная» / «Булочка ржаная с семечками»). The first position is kept, with the richer data.
     */
    private fun items(o: JsonObject): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (item in o["items"]?.jsonArray?.map { it.jsonObject }.orEmpty()) {
            val i = out.indexOfFirst { same(it, item) }
            if (i < 0) out += item else out[i] = merge(out[i], item)
        }
        return out
    }

    private fun words(s: String?): Set<String> = Normalize.tokens(s.orEmpty()).toSet()

    private fun same(a: JsonObject, b: JsonObject): Boolean {
        val an = words(a.s("nameRu")); val bn = words(b.s("nameRu"))
        val aq = words(a.s("queryEn")); val bq = words(b.s("queryEn"))
        if (an.isEmpty() || bn.isEmpty()) return false
        fun nested(x: Set<String>, y: Set<String>) = x.isNotEmpty() && y.isNotEmpty() && (x.containsAll(y) || y.containsAll(x))
        return an == bn || (aq.isNotEmpty() && aq == bq) || (nested(an, bn) && nested(aq, bq))
    }

    /** One item out of two shots of the same food: the printed table, the label weight and the fuller name win. */
    private fun merge(a: JsonObject, b: JsonObject): JsonObject {
        val base = when {
            a.table() == null && b.table() != null -> b
            a.table() != null && b.table() == null -> a
            words(b.s("nameRu")).size > words(a.s("nameRu")).size -> b
            else -> a
        }
        val other = if (base === a) b else a
        val extra = buildMap<String, kotlinx.serialization.json.JsonElement> {
            val weightFromLabel = other.s("gramsBasis") == "label" && base.s("gramsBasis") != "label"
            if (other.d("grams") != null && (base.d("grams") == null || weightFromLabel)) {
                put("grams", other.getValue("grams")); other["gramsBasis"]?.let { put("gramsBasis", it) }
            }
            for (k in listOf("brand", "packageG")) if (base.s(k) == null && base.d(k) == null) other[k]?.takeIf { it !is JsonNull }?.let { put(k, it) }
            if (other.bool("packaged")) put("packaged", JsonPrimitive(true))
        }
        return JsonObject(base + extra)
    }

    private fun request(it: JsonObject) = "${it.s("nameRu")} — ${it.s("queryEn")}. ${it.s("details")}"

    /** RESOLVE requests for items without a full printed table, in item order. */
    fun requests(o: JsonObject): List<String> = items(o).filter { it.table() == null }.map(::request)

    fun combine(o: JsonObject, resolved: List<Proposal>, barcode: String?): PhotoResult {
        val items = items(o)
        val notes = o.s("notes")
        if (items.isEmpty()) return PhotoResult(emptyList(), notes ?: "На фото не видно еды.")
        val toResolve = items.filter { it.table() == null }
        val single = items.size == 1
        val foods = items.map { item ->
            val packaged = item.bool("packaged")
            val table = item.table()
            val (draft, sourceNote) = if (table != null) {
                FoodDraft(
                    name = item.s("nameRu").orEmpty(), brand = item.s("brand"), per100 = table, basis = FoodBasis.AS_SOLD,
                    source = FoodSource.LABEL, sourceRef = barcode.takeIf { single }, barcode = barcode.takeIf { single },
                ) to "цифры с этикетки"
            } else {
                val p = resolved.firstOrNull { it.request == request(item) } ?: resolved.getOrNull(toResolve.indexOf(item))
                var d = p?.draft ?: FoodDraft(name = item.s("nameRu").orEmpty(), per100 = Nutrients(0.0, 0.0, 0.0, 0.0), source = FoodSource.MANUAL)
                d = d.copy(name = item.s("nameRu") ?: d.name, brand = d.brand ?: item.s("brand"))
                // A barcode scanned before the photo belongs to the packaged product: the next scan finds it locally.
                if (barcode != null && single && packaged) d = d.copy(barcode = barcode)
                d to (p?.note ?: "в базах не нашлось, введите цифры вручную")
            }
            val grams = item.d("grams")?.takeIf { it > 0 }
            val packageG = item.d("packageG")?.takeIf { single && packaged && it > 0 }
            val gramsNote = when {
                grams != null && item.s("gramsBasis") == "label" -> "вес с упаковки"
                grams != null -> "вес оценён по фото, проверьте"
                packageG != null -> "подставлен вес упаковки"
                else -> "вес по фото не определить, укажите"
            }
            val seen = item.s("details").orEmpty().trim().let { if (it.isEmpty() || it.endsWith(".")) it else "$it." }
            PhotoFood(draft, "По фото: $seen Источник: ${sourceNote.trimEnd('.', ' ')}; $gramsNote.", grams ?: packageG)
        }
        return PhotoResult(foods, notes)
    }

    private fun JsonObject.table(): Nutrients? {
        val kcal = d("tableKcal"); val p = d("tableProtein"); val f = d("tableFat"); val c = d("tableCarbs")
        return if (kcal != null && p != null && f != null && c != null) Nutrients(kcal, p, f, c, d("tableFiber")) else null
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
}
