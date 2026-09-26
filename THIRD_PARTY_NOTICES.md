# Third-party notices

The project license does not replace upstream licenses. Release runtime components and their declared licenses are recorded in [the reviewed dependency inventory](docs/third-party/dependencies.json). It includes transitive dependencies and is checked against Gradle's actual resolved artifacts.

The APK bundles [complete generated notices](app/src/main/assets/third-party-notices.txt), available offline in the Automation tab > Third-party software notices. These contain Maven license declarations, embedded copyright/license/NOTICE files (including notices inside AAR class JARs), the Apache-2.0 text, the protobuf BSD notice, and the MPL-2.0 text for OkHttp's bundled Public Suffix List data. Original resource license metadata is also retained during Android packaging.

The Gradle wrapper and generated launchers are from Gradle 9.4.1, licensed under Apache-2.0; see [Gradle's license](https://github.com/gradle/gradle/blob/v9.4.1/LICENSE) and the [included Apache license text](docs/third-party/Apache-2.0.txt).

The Obtainium badge in the README is copied from [ImranR98/Obtainium](https://github.com/ImranR98/Obtainium), which is licensed under GPL-3.0. The original [badge asset](https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png) is stored at `docs/assets/badge_obtainium.png`.

App vector drawables are stored in `app/src/main/res/drawable`. No CP website imagery is bundled. Asset authorship/rights should be confirmed by the maintainer before release; absence of an external logo file is not proof of ownership. CP names, logos, trademarks, and upstream timetable content are not relicensed here.

To refresh notices after a dependency change, follow [CONTRIBUTING.md](CONTRIBUTING.md). The generator fails on unreviewed additions/removals rather than assigning a license automatically.
