package pt.cpcompanion.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.cpcompanion.model.CpFrontendConfig

class CpFrontendConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesCurrentPublicFrontendConfigurationShape() {
        val value = json.decodeFromString<CpFrontendConfig>(
            """
            {
              "recaptchaSiteKey": "ignored",
              "xcck": "connect-id",
              "xccs": "connect-secret",
              "travelApiUrl": "https://api-gateway.cp.pt/cp/services/travel-api",
              "stationsApiUrl": "https://api-gateway.cp.pt/cp/services/stations-api",
              "travelApiKey": "travel-key",
              "stationsApiKey": "DUMMY"
            }
            """.trimIndent(),
        )

        assertEquals("connect-id", value.xcck)
        assertEquals("connect-secret", value.xccs)
        assertEquals("travel-key", value.travelApiKey)
        assertEquals("DUMMY", value.stationsApiKey)
        assertTrue(value.travelApiUrl.endsWith("/travel-api"))
        assertTrue(value.stationsApiUrl.endsWith("/stations-api"))
    }
}
