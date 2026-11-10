package app.grammovka.data.ai

import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMappingTest {
    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$name")!!.readText()).jsonObject

    private fun usda(request: String, name: String, n: Nutrients) =
        Proposal(request, FoodDraft(name = name, per100 = n, source = FoodSource.USDA, sourceRef = "1"), "USDA FoodData Central, fdcId 1", null)

    @Test fun bunGoesThroughResolveWithVisualGrams() {
        val o = fixture("photo_bun.json")
        val req = PhotoMapping.requests(o)
        assertEquals(1, req.size)
        assertTrue(req[0].contains("rye bread with sunflower seeds"))
        val r = PhotoMapping.combine(o, listOf(usda(req[0], "Rye bread", Nutrients(259.0, 8.5, 3.3, 48.3, 5.8))), null)
        val f = r.foods.single()
        assertEquals("Булочка ржаная с семечками", f.draft.name)
        assertEquals(FoodSource.USDA, f.draft.source)
        assertEquals(259.0, f.draft.per100.kcal, 0.0)
        assertEquals(85.0, f.grams!!, 0.0)
        assertTrue(f.note.contains("оценён по фото"))
    }

    @Test fun packagedMinceGetsBarcodeAndPackageWeight() {
        val o = fixture("photo_mince.json")
        val req = PhotoMapping.requests(o).single()
        val estimate = Proposal(
            req, FoodDraft(name = "x", per100 = Nutrients(218.0, 18.0, 16.0, 0.0), source = FoodSource.CLAUDE_ESTIMATE, confidence = Confidence.MEDIUM),
            "Оценка Claude", null,
        )
        val r = PhotoMapping.combine(o, listOf(estimate), "5900000000001")
        val f = r.foods.single()
        assertEquals("5900000000001", f.draft.barcode)
        assertEquals("Sokołów", f.draft.brand)
        assertEquals(FoodSource.CLAUDE_ESTIMATE, f.draft.source)
        assertEquals(500.0, f.grams!!, 0.0)
        assertEquals("Жирность на упаковке не указана, взята типичная.", r.notes)
    }

    @Test fun printedTableSkipsLookupAndPlateKeepsOrder() {
        val o = fixture("photo_plate.json")
        val req = PhotoMapping.requests(o)
        assertEquals(1, req.size) // only buckwheat needs a lookup
        val r = PhotoMapping.combine(o, listOf(usda(req[0], "Buckwheat", Nutrients(92.0, 3.4, 0.6, 19.9, 2.7))), "123")
        assertEquals(listOf("Гречка отварная", "Йогурт"), r.foods.map { it.draft.name })
        val yogurt = r.foods[1]
        assertEquals(FoodSource.LABEL, yogurt.draft.source)
        assertEquals(64.0, yogurt.draft.per100.kcal, 0.0)
        // Several items: a scanned barcode is not attached to the dish component.
        assertNull(r.foods[0].draft.barcode)
        assertNull(yogurt.draft.barcode)
    }

    @Test fun missingProposalLeavesManualDraft() {
        val r = PhotoMapping.combine(fixture("photo_bun.json"), emptyList(), null)
        assertEquals(FoodSource.MANUAL, r.foods.single().draft.source)
    }

    @Test fun sameFoodFromTwoPhotosIsOneLine() {
        val o = fixture("photo_bun_twice.json")
        val req = PhotoMapping.requests(o)
        assertEquals(2, req.size) // the bun once, the butter once
        assertTrue(req[0].contains("with sunflower seeds"))
        val r = PhotoMapping.combine(
            o,
            listOf(usda(req[0], "Rye roll", Nutrients(259.0, 8.5, 3.3, 48.3, 5.8)), usda(req[1], "Butter", Nutrients(717.0, 0.9, 81.0, 0.1))),
            null,
        )
        assertEquals(listOf("Булочка ржаная с семечками", "Масло сливочное"), r.foods.map { it.draft.name })
        // The fuller description wins and brings its label weight; the butter keeps the grams of the shot that had them.
        assertEquals(80.0, r.foods[0].grams!!, 0.0)
        assertEquals("Putka", r.foods[0].draft.brand)
        assertTrue(r.foods[0].note.contains("вес с упаковки"))
        assertEquals(10.0, r.foods[1].grams!!, 0.0)
        assertEquals(717.0, r.foods[1].draft.per100.kcal, 0.0)
    }

    @Test fun differentFoodsWithSharedWordsStayApart() {
        val o = Json.parseToJsonElement(
            """{"items":[
                {"nameRu":"Рис отварной","queryEn":"rice, white, cooked","details":"","packaged":false,"brand":null,"packageG":null,"grams":150,"gramsBasis":"visual","tableKcal":null,"tableProtein":null,"tableFat":null,"tableCarbs":null,"tableFiber":null},
                {"nameRu":"Курица отварная","queryEn":"chicken breast, cooked","details":"","packaged":false,"brand":null,"packageG":null,"grams":120,"gramsBasis":"visual","tableKcal":null,"tableProtein":null,"tableFat":null,"tableCarbs":null,"tableFiber":null},
                {"nameRu":"Сыр","queryEn":"cheese, gouda","details":"","packaged":false,"brand":null,"packageG":null,"grams":30,"gramsBasis":"visual","tableKcal":null,"tableProtein":null,"tableFat":null,"tableCarbs":null,"tableFiber":null},
                {"nameRu":"Сыр плавленый","queryEn":"processed cheese spread","details":"","packaged":false,"brand":null,"packageG":null,"grams":20,"gramsBasis":"visual","tableKcal":null,"tableProtein":null,"tableFat":null,"tableCarbs":null,"tableFiber":null}
            ],"notes":null}""",
        ).jsonObject
        assertEquals(4, PhotoMapping.requests(o).size)
    }

    @Test fun noFood() {
        val r = PhotoMapping.combine(Json.parseToJsonElement("""{"items":[],"notes":null}""").jsonObject, emptyList(), null)
        assertTrue(r.foods.isEmpty())
    }
}
