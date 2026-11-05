package app.grammovka.domain.health

import app.grammovka.domain.model.WeightSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeightImportTest {
    private val d1 = LocalDate.of(2026, 11, 3)
    private val d2 = LocalDate.of(2026, 11, 4)
    private val d3 = LocalDate.of(2026, 11, 5)

    @Test
    fun manualWinsLatestOfDayRefreshesImports() {
        val existing = mapOf(d1 to WeightSource.MANUAL, d2 to WeightSource.HEALTH_CONNECT)
        val imported = listOf(
            ImportedWeight(d1, 80.0, 1), // manual exists → skipped
            ImportedWeight(d2, 79.8, 1), ImportedWeight(d2, 79.6, 2), // latest wins, refreshes old import
            ImportedWeight(d3, 79.5, 1),
            ImportedWeight(d3, 7.0, 3), // implausible → ignored
        )
        assertEquals(mapOf(d2 to 79.6, d3 to 79.5), WeightImport.toWrite(existing, imported))
    }
}
