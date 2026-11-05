package app.grammovka.domain.health

import app.grammovka.domain.model.WeightSource
import java.time.LocalDate

/** One weight reading from Health Connect, already converted to the local date. */
data class ImportedWeight(val date: LocalDate, val kg: Double, val epochMillis: Long)

object WeightImport {
    /**
     * Spec, phase 2: a Health Connect weight is written for a date only when there is no manual value.
     * Several readings on one day: the latest wins. Earlier imports for a date are refreshed.
     * Returns date → kg to upsert with source HEALTH_CONNECT.
     */
    fun toWrite(existing: Map<LocalDate, WeightSource>, imported: List<ImportedWeight>): Map<LocalDate, Double> =
        imported.filter { it.kg in 20.0..400.0 }
            .groupBy { it.date }
            .mapValues { (_, l) -> l.maxBy { it.epochMillis }.kg }
            .filterKeys { existing[it] != WeightSource.MANUAL }
}
