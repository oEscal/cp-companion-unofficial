package pt.cpcompanion.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import pt.cpcompanion.model.Station

@RunWith(AndroidJUnit4::class)
class NavigationStateTest {
    @Test
    fun stationRouteRoundTripsThroughSavedStateEncoding() {
        val screen = AppScreen.StationScreen(
            Station(code = "94-10000", name = "Lisboa Oriente", timeZoneId = "Europe/Lisbon"),
        )
        assertEquals(screen, MainViewModel.decodeScreen(MainViewModel.encodeScreen(screen)))
    }

    @Test
    fun tripRouteRoundTripsThroughSavedStateEncoding() {
        val screen = AppScreen.TripScreen("123", "2026-07-12", "ticket|with delimiter")
        assertEquals(screen, MainViewModel.decodeScreen(MainViewModel.encodeScreen(screen)))
    }
}
