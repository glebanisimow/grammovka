package app.grammovka.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Test

class NiceTicksTest {
    @Test fun weightRange() = assertEquals(listOf(79.0, 80.0, 81.0), niceTicks(79.2, 80.9, 3))
    @Test fun kcalFromZero() = assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0), niceTicks(0.0, 2400.0, 3))
}
