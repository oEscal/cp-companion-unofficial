package pt.cpcompanion.sms

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTicketParserTest {
    private val parser = SmsTicketParser()

    @Test
    fun parsesRoundTripWithSeatsOnBothLegs() {
        val result = parser.parse(
            "Ida 2026-07-10: IC 723 - Coimbra-B - 21h33 >> Aveiro - 22h02 / " +
                "Volta 2026-07-11: IC 520 - Aveiro - 07h27 >> Coimbra-B - 07h55 ; " +
                "Bilhete 0001-12345678: Ida IC 723 Car:21 Lug:35 / " +
                "Volta IC 520 Car:24 Lug:98 Preço 0,00Eur",
        )!!

        assertEquals("0001-12345678", result.ticketReference)
        assertEquals(2, result.legs.size)
        with(result.legs[0]) {
            assertEquals(SmsJourneyDirection.OUTBOUND, direction)
            assertEquals(LocalDate.of(2026, 7, 10), serviceDate)
            assertEquals("723", trainNumber)
            assertEquals("Coimbra-B", originName)
            assertEquals(LocalTime.of(21, 33), departureTime)
            assertEquals("Aveiro", destinationName)
            assertEquals("21", carriage)
            assertEquals("35", seat)
        }
        with(result.legs[1]) {
            assertEquals(SmsJourneyDirection.RETURN, direction)
            assertEquals("520", trainNumber)
            assertEquals("24", carriage)
            assertEquals("98", seat)
        }
    }

    @Test
    fun parsesPortugueseDateColonTimeAndFullSeatLabels() {
        val result = parser.parse(
            "Bilhete 0002-23456789 Ida 12/07/2026 Comboio IC 721 " +
                "Coimbra-B 11:35 > Aveiro 12:02 Carruagem 21 Lugar 95",
        )!!

        assertEquals(LocalDate.of(2026, 7, 12), result.legs.single().serviceDate)
        assertEquals(LocalTime.of(11, 35), result.legs.single().departureTime)
        assertEquals("21", result.legs.single().carriage)
        assertEquals("95", result.legs.single().seat)
    }

    @Test
    fun stripsCarriageAndSeatPrefixLetters() {
        val result = parser.parse(
            "Bilhete 0002-23456789 Ida 12/07/2026 Comboio IC 721 " +
                "Coimbra-B 11:35 > Aveiro 12:02 Car:C24 Lug:L107",
        )!!

        assertEquals("24", result.legs.single().carriage)
        assertEquals("107", result.legs.single().seat)
    }

    @Test
    fun parsesDashDateArrowVariant() {
        val result = parser.parse(
            "Ida: 12-07-2026 - Comboio IC 721 - Coimbra-B - 11h35 → Aveiro - 12h02; " +
                "Ref. 0002-23456789; Carr. 21 Assento 95",
        )
        assertNotNull(result)
        assertEquals("Coimbra-B", result!!.legs.single().originName)
        assertEquals("Aveiro", result.legs.single().destinationName)
    }

    @Test
    fun parsesPastTicketBecauseDateIsNotFiltered() {
        val result = parser.parse(
            "Ida 2021-01-05: R 4605 - Coimbra-B - 07h50 >> Aveiro - 08h44; " +
                "Bilhete 0003-34567890: Ida R 4605 Preço 9,00Eur",
        )
        assertNotNull(result)
        assertEquals(LocalDate.of(2021, 1, 5), result!!.legs.single().serviceDate)
    }

    @Test
    fun parsesRoundTripWhenOutboundHasNoSeat() {
        val result = parser.parse(
            "Ida 2025-12-03: R 4605 - Coimbra-B - 07h50 >> Aveiro - 08h44 / " +
                "Volta 2025-12-03: IC 620 - Aveiro - 18h27 >> Coimbra-B - 18h54 ; " +
                "Bilhete 0003-34567890: Ida R 4605 / Volta IC 620 Car:24 Lug:107 Preço 0,00Eur",
        )!!

        assertEquals(2, result.legs.size)
        assertNull(result.legs[0].carriage)
        assertNull(result.legs[0].seat)
        assertEquals("24", result.legs[1].carriage)
        assertEquals("107", result.legs[1].seat)
    }

    @Test
    fun ignoresUnrelatedMessage() {
        assertNull(parser.parse("O seu comboio encontra-se atrasado."))
    }

    @Test
    fun normalizesWhitespaceAndLineBreaks() {
        val result = parser.parse(
            "Ida 2026-07-12: IC 721 - Coimbra-B - 11h35 >> Aveiro - 12h02 ;\n" +
                "Bilhete 0002-23456789: Ida IC 721 Car:21 Lug:95 Preço 0,00Eur",
        )
        assertTrue(result != null)
    }
}
