# Known limitations

- This is an unofficial beta. CP gateway support, permitted reuse, and production polling quotas have not been confirmed. Upstream changes or rate limiting can interrupt service.
- Timetables and status can be stale, incomplete, or ambiguous. Check official information before travel decisions. Cross-border ETA/ETD semantics and some status values remain unresolved.
- Android and manufacturer battery restrictions can delay T-60 activation. Exact-alarm access, visible notifications, and appropriate battery settings improve reliability but do not guarantee it.
- Long routes, promoted notifications, and overlapping delayed journeys still need the real-device checks in [DEVICE_TESTING.md](DEVICE_TESTING.md).
- Compose/Material dependencies include prerelease versions. UI compatibility is tested incrementally; a green unit test suite does not prove every device experience.
- Imported journeys are local records, not validated travel documents. No account login, purchase, refund, payment, or QR/PDF retrieval is provided.
- Tickets remain locally until deleted. Deleting a ticket does not remove its source SMS; reimport may recreate it. Android backups and device transfer are disabled, so clearing data/uninstalling loses saved journeys.
- The current app requests optional READ_SMS. Google Play distribution has not been approved; use the signed GitHub beta channel. A future Play build may need to remove inbox access or obtain an eligible policy exception.
