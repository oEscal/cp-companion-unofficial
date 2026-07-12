# Observed read-only endpoint matrix

The application deliberately implements only observed read operations. It obtains current public website configuration automatically from `https://www.cp.pt/fe-config.json`.

| Feature | Method | Path | Polling/caching |
|---|---|---|---|
| Station catalogue | GET | `/cp/services/travel-api/stations` | Cache for hours |
| Train catalogue | GET | `/cp/services/travel-api/trains` | Cache for hours; train numbers are not unique |
| Train trip | GET | `/cp/services/travel-api/trains/{trainNumber}/timetable/{yyyy-MM-dd}` | Every 10 seconds only during explicit active tracking |
| Station board | GET | `/cp/services/travel-api/stations/{stationCode}/timetable/{yyyy-MM-dd}?view=DEPARTURES|ARRIVALS&start=HH:mm` | On screen open/selection |
| Station details | GET | `/cp/services/stations-api/stations/infos/{stationCode}` | Cacheable |

## Identity rules

- A concrete run is at minimum `trainNumber + serviceDate`.
- A passenger tracking session additionally requires stable origin and destination station codes.
- Catalogue duplicates are retained using train number, service code, origin, and destination as a compound key.
- Station-stop coordinates are station coordinates, not a current train position.

## Automatic header discovery

The frontend configuration supplies `travelApiUrl`, `stationsApiUrl`, `travelApiKey`, `stationsApiKey`, `xcck`, and `xccs`. Travel calls use `travelApiKey`; station-information calls use `stationsApiKey`; both use the current `xcck` and `xccs` values. The app caches this configuration for 24 hours and forces a refresh after HTTP 401 or 403.
