package app.grammovka.data.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusMappingTest {
    @Test
    fun anthropic() {
        assertEquals(KeyCheck.Ok, mapAnthropicStatus(200))
        assertEquals(KeyCheck.Invalid, mapAnthropicStatus(401))
        assertEquals(KeyCheck.NoPermission, mapAnthropicStatus(403))
        assertEquals(KeyCheck.RateLimited, mapAnthropicStatus(429))
        assertEquals(KeyCheck.ServiceDown(529), mapAnthropicStatus(529))
        assertEquals(KeyCheck.Unexpected(404), mapAnthropicStatus(404))
    }

    @Test
    fun usda() {
        assertEquals(KeyCheck.Ok, mapUsdaStatus(200))
        assertEquals(KeyCheck.Invalid, mapUsdaStatus(403))
        assertEquals(KeyCheck.RateLimited, mapUsdaStatus(429))
        assertEquals(KeyCheck.ServiceDown(503), mapUsdaStatus(503))
    }
}
