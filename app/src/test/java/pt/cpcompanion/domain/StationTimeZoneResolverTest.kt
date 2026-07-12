package pt.cpcompanion.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import pt.cpcompanion.model.Station

class StationTimeZoneResolverTest {
    @Test
    fun persistedZoneTakesPrecedence() {
        val station = Station(
            code = "custom",
            name = "Custom",
            timeZoneId = "Europe/Madrid",
        )

        assertEquals(ZoneId.of("Europe/Madrid"), StationTimeZoneResolver.resolve(station))
    }

    @Test
    fun spanishUicPrefixUsesMadrid() {
        assertEquals(ZoneId.of("Europe/Madrid"), StationTimeZoneResolver.resolve("71-00000"))
    }

    @Test
    fun portugueseStationUsesLisbon() {
        assertEquals(ZoneId.of("Europe/Lisbon"), StationTimeZoneResolver.resolve("94-36004"))
    }
}
