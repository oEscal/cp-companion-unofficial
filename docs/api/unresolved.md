# Unresolved upstream contract items

- Official permission and support terms for the observed gateway.
- Authenticated account/ticket endpoints and token lifecycle.
- Ticket QR/PDF payload format.
- Complete train status enum semantics.
- Reliability of `lastStationCode` across service types.
- Whether top-level train latitude/longitude is populated for any services.
- Exact time-zone semantics for cross-border ETA/ETD values.
- Appropriate production polling quota for 10-second active tracking.

Until these are verified, ticket import is manual and authenticated operations are intentionally absent.
