package app.grammovka.data.repo

import app.grammovka.data.db.FoodDao
import app.grammovka.data.db.FoodEntity
import app.grammovka.data.db.PortionEntity
import app.grammovka.data.db.UsedFoodRow
import app.grammovka.domain.log.CatalogPort
import app.grammovka.domain.model.CatalogFood
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.parse.PortionDef
import app.grammovka.domain.text.Normalize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FoodRepository(private val dao: FoodDao, private val clock: () -> Long = System::currentTimeMillis) : CatalogPort {

    override suspend fun search(query: String, limit: Int): List<CatalogFood> {
        val match = Normalize.ftsMatch(query) ?: return emptyList()
        return dao.search(match, limit).map { it.toDomain() }
    }

    override suspend fun portions(foodId: Long): List<PortionDef> = dao.portions(foodId).map { PortionDef(it.name, it.grams) }

    suspend fun portionEntities(foodId: Long): List<PortionEntity> = dao.portions(foodId)

    fun observePortions(foodId: Long): Flow<List<PortionEntity>> = dao.observePortions(foodId)

    fun observeCatalog(archived: Boolean): Flow<List<FoodEntity>> = dao.observeAll(archived)

    suspend fun searchEntities(query: String, includeArchived: Boolean): List<FoodEntity> {
        val match = Normalize.ftsMatch(query) ?: return emptyList()
        return dao.search(match, 100, includeArchived)
    }

    fun observe(id: Long): Flow<FoodEntity?> = dao.observe(id)
    suspend fun byId(id: Long): FoodEntity? = dao.byId(id)
    suspend fun byBarcode(code: String): FoodEntity? = dao.byBarcode(code)

    fun observeUsed(): Flow<List<UsedFoodRow>> = dao.observeUsed()

    /** Creates a catalog food from a confirmed draft. Throws if the draft breaks invariant 1. */
    suspend fun create(d: FoodDraft): Long {
        require(d.blockingProblems().isEmpty()) { d.blockingProblems().joinToString(" ") }
        val now = clock()
        val id = dao.insert(d.toEntity(id = 0, createdAt = now, updatedAt = now))
        if (d.portions.isNotEmpty()) dao.replacePortions(id, d.portions.map { (n, g) -> PortionEntity(0, id, n, g) })
        return id
    }

    /** Updates a food's catalog data. Log entries keep their own snapshot (invariant 4). */
    suspend fun update(id: Long, d: FoodDraft) {
        require(d.blockingProblems().isEmpty()) { d.blockingProblems().joinToString(" ") }
        val old = dao.byId(id) ?: return
        dao.update(
            d.toEntity(id, createdAt = old.createdAt, updatedAt = clock())
                .copy(usageCount = old.usageCount, lastUsedAt = old.lastUsedAt, archived = old.archived),
        )
        dao.replacePortions(id, d.portions.map { (n, g) -> PortionEntity(0, id, n, g) })
    }

    suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived, clock())

    /** Adds [words] as a synonym unless the name or an alias already normalizes to the same text. */
    suspend fun learnAlias(id: Long, words: String) {
        val f = dao.byId(id) ?: return
        val w = words.trim()
        val n = Normalize.text(w)
        if (n.isEmpty() || n == Normalize.text(f.name) || aliasesOf(f.aliases).any { Normalize.text(it) == n }) return
        val aliases = aliasesOf(f.aliases) + w
        dao.update(
            f.copy(
                aliases = aliases.joinToString("\n"),
                searchText = Normalize.searchText(listOfNotNull(f.name, f.brand).joinToString(" "), aliases),
                updatedAt = clock(),
            ),
        )
    }

    suspend fun draftOf(id: Long): FoodDraft? {
        val f = dao.byId(id) ?: return null
        return FoodDraft(
            name = f.name, brand = f.brand, aliases = aliasesOf(f.aliases),
            per100 = Nutrients(f.kcal, f.protein, f.fat, f.carbs, f.fiber),
            basis = f.basis, source = f.source, sourceRef = f.sourceRef, confidence = f.confidence, barcode = f.barcode,
            portions = dao.portions(id).map { it.name to it.grams },
        )
    }

    companion object {
        fun aliasesOf(s: String): List<String> = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun FoodEntity.toDomain() = CatalogFood(
            id, name, brand, aliasesOf(aliases), Nutrients(kcal, protein, fat, carbs, fiber), source, confidence, usageCount, lastUsedAt,
        )

        private fun FoodDraft.toEntity(id: Long, createdAt: Long, updatedAt: Long) = FoodEntity(
            id = id,
            name = name.trim(),
            brand = brand?.trim()?.takeIf { it.isNotEmpty() },
            aliases = aliases.joinToString("\n") { it.trim() },
            searchText = Normalize.searchText(listOfNotNull(name, brand).joinToString(" "), aliases),
            kcal = per100.kcal, protein = per100.protein, fat = per100.fat, carbs = per100.carbs, fiber = per100.fiber,
            basis = basis, source = source, sourceRef = sourceRef?.trim()?.takeIf { it.isNotEmpty() }, confidence = confidence,
            barcode = barcode?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = createdAt, updatedAt = updatedAt,
        )
    }
}
