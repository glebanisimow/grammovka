package app.grammovka.data.ai

import app.grammovka.domain.activity.ActivityType
import app.grammovka.domain.activity.Intensity
import app.grammovka.domain.model.Confidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityMappingTest {
    private val items = ActivityMapping.proposals(
        Json.parseToJsonElement(javaClass.getResource("/fixtures/activity_ride_gym.json")!!.readText()).jsonObject,
    )

    @Test fun rideKeepsAllFields() {
        val r = items[0]
        assertEquals(ActivityType.BIKE, r.type)
        assertEquals(240.0, r.minutes!!, 0.0)
        assertEquals(100.0, r.distanceKm!!, 0.0)
        assertEquals(135, r.avgHr)
        assertEquals(Intensity.MODERATE, r.intensity)
        assertEquals(3100.0, r.grossKcal!!, 0.0)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test fun noDurationMeansNoKcalAndNoHighConfidence() {
        val g = items[1]
        assertNull(g.minutes)
        assertNull(g.grossKcal)
        assertEquals(Confidence.MEDIUM, g.confidence)
    }

    @Test fun unknownTypeAndImplausibleNumbersAreDropped() {
        val s = items[2]
        assertEquals(ActivityType.OTHER, s.type)
        assertEquals("Другое", s.title)
        assertNull(s.distanceKm)
        assertNull(s.avgHr)
        assertEquals(450.0, s.grossKcal!!, 0.0)
    }
}
