# Source delivery

The public source package is generated from an exact clean release tag using `tools/prepare_release.py --package`. It excludes generated APKs, local SDK paths, signing material, caches, and local planning notes. APKs belong in release assets, not Git. Existing historical APKs/logs are not removed by a current-tree cleanup; no history was rewritten during preparation.

The source archive accompanies the signed APK, certificate report, and SHA256SUMS in the draft prerelease. Development checkout checks allow ignored local SDK configuration. Optional source-delivery checksum verification is separate from normal builds.

See [VALIDATION.md](VALIDATION.md) for observed results and [RELEASING.md](RELEASING.md) for the publication process and remaining external requirements.
