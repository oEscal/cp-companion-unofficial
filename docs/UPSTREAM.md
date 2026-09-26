# CP integration and branding

CP Companion is independent of CP — Comboios de Portugal. It uses observed read-only website configuration and gateway operations documented in [the endpoint matrix](api/endpoint-matrix.md). This repository does not claim a supported public API contract or grant rights to CP data, website content, or marks.

The app makes direct requests from each user's device and does not implement account login, ticket purchase, payment, or QR/PDF retrieval. It uses request coalescing, bounded caches, adaptive polling, and HTTP 429 backoff to limit load, but those implementation choices do not establish an upstream quota or endorsement.

Keep CP names and marks clearly nominative and do not bundle harvested timetable datasets or CP website artwork. For current terms, support, attribution, and polling limits, consult CP's [legal notice](https://www.cp.pt/info/legal) and contact CP through its published institutional channels. Keep any correspondence and credentials outside Git.

Authenticated endpoints remain out of scope until their authorization and data contract are established.
