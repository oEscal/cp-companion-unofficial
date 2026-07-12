package pt.cpcompanion.domain

import java.text.Normalizer
import java.time.ZoneId
import pt.cpcompanion.model.Station

/** Centralized station-time-zone resolution for boards, trips, notifications, and ticket times. */
object StationTimeZoneResolver {
    private val portugal = ZoneId.of("Europe/Lisbon")
    private val spain = ZoneId.of("Europe/Madrid")

    // Explicit station overrides take precedence over broader provider-code/region heuristics.
    private val explicitOverrides: Map<String, ZoneId> = emptyMap()

    fun resolve(stationCode: String, region: String? = null): ZoneId {
        explicitOverrides[stationCode]?.let { return it }
        val normalizedRegion = region.orEmpty().normalize()
        if (normalizedRegion.contains("espanha") || normalizedRegion.contains("spain") ||
            normalizedRegion.contains("galicia") || normalizedRegion.contains("galiza")
        ) return spain

        // CP/UIC foreign station identifiers currently use country-prefix 71 for Spain.
        // Keep this fallback in one audited location rather than duplicating it across the app.
        return if (stationCode.startsWith("71-")) spain else portugal
    }

    fun resolve(station: Station): ZoneId = station.timeZoneId
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: resolve(station.code, station.region)

    private fun String.normalize(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
}
