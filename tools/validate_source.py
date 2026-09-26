#!/usr/bin/env python3
"""Fast source-only checks that do not require the Android SDK."""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
RES = APP / "src" / "main" / "res"
ERRORS: list[str] = []
SOURCE_CHECKSUMS = ROOT / "docs" / "SOURCE_SHA256SUMS.txt"


def error(message: str) -> None:
    ERRORS.append(message)


def parse_xml_files() -> None:
    for path in sorted(RES.rglob("*.xml")) + [APP / "src" / "main" / "AndroidManifest.xml"]:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            error(f"Invalid XML {path.relative_to(ROOT)}: {exc}")


def values_resources(path: Path) -> dict[tuple[str, str], Path]:
    result: dict[tuple[str, str], Path] = {}
    if not path.exists():
        return result
    for xml in sorted(path.glob("*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for child in root:
            name = child.attrib.get("name")
            if name:
                result[(child.tag, name)] = xml
    return result


def check_translation_parity() -> None:
    default = values_resources(RES / "values")
    portuguese = values_resources(RES / "values-pt")
    translatable_tags = {"string", "plurals", "string-array"}
    missing_pt = sorted(k for k in default if k[0] in translatable_tags and k not in portuguese)
    extra_pt = sorted(k for k in portuguese if k[0] in translatable_tags and k not in default)
    for tag, name in missing_pt:
        error(f"Portuguese resource missing: {tag}/{name}")
    for tag, name in extra_pt:
        error(f"Portuguese resource has no default counterpart: {tag}/{name}")


def collect_declared_resources() -> set[tuple[str, str]]:
    declared: set[tuple[str, str]] = set()
    for child in RES.iterdir():
        if not child.is_dir():
            continue
        resource_type = child.name.split("-", 1)[0]
        if resource_type == "values":
            for (tag, name), _ in values_resources(child).items():
                mapped = "array" if tag in {"array", "string-array", "integer-array"} else tag
                declared.add((mapped, name))
        else:
            for path in child.iterdir():
                if path.is_file():
                    declared.add((resource_type, path.stem))
    return declared


def check_resource_references() -> None:
    declared = collect_declared_resources()
    kotlin_ref = re.compile(r"\bR\.(\w+)\.(\w+)\b")
    xml_ref = re.compile(r"@(?:\+)?([a-zA-Z_]+)/(\w[\w.]*)")
    references: set[tuple[str, str, Path]] = set()

    for path in sorted((APP / "src").rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for resource_type, name in kotlin_ref.findall(text):
            references.add((resource_type, name, path))

    for path in sorted((APP / "src").rglob("*.xml")):
        text = path.read_text(encoding="utf-8")
        for resource_type, name in xml_ref.findall(text):
            if resource_type == "id":
                continue
            references.add((resource_type, name, path))

    for resource_type, name, path in sorted(references, key=lambda item: (item[0], item[1], str(item[2]))):
        if (resource_type, name) not in declared:
            error(f"Missing resource {resource_type}/{name}, referenced by {path.relative_to(ROOT)}")


def check_source_checksums() -> None:
    if not SOURCE_CHECKSUMS.is_file():
        error("Missing docs/SOURCE_SHA256SUMS.txt")
        return

    seen: set[Path] = set()
    for line_number, raw_line in enumerate(SOURCE_CHECKSUMS.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        parts = line.split(maxsplit=1)
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            error(f"Invalid source checksum entry at line {line_number}")
            continue
        relative_text = parts[1].removeprefix("./")
        relative = Path(relative_text)
        if relative.is_absolute() or ".." in relative.parts:
            error(f"Unsafe source checksum path at line {line_number}: {parts[1]}")
            continue
        path = ROOT / relative
        if path in seen:
            error(f"Duplicate source checksum path: {relative_text}")
            continue
        seen.add(path)
        if not path.is_file():
            error(f"Source checksum file is missing: {relative_text}")
            continue
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != parts[0]:
            error(f"Source checksum mismatch: {relative_text}")


def check_project_invariants() -> None:
    app_build = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    manifest = (APP / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
    network = (RES / "xml" / "network_security_config.xml").read_text(encoding="utf-8")
    versions = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
    wrapper = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
    build_script = (ROOT / "build.sh").read_text(encoding="utf-8")
    workflow = (ROOT / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")
    api_client = (
        APP / "src" / "main" / "java" / "pt" / "cpcompanion" / "network" / "CpApiClient.kt"
    ).read_text(encoding="utf-8")
    notification_factory = (
        APP / "src" / "main" / "java" / "pt" / "cpcompanion" / "notifications" / "TrackingNotificationFactory.kt"
    ).read_text(encoding="utf-8")

    required_fragments = {
        "applicationId": 'applicationId = "pt.cpcompanion"',
        "compileSdk": "compileSdk = 37",
        "targetSdk": "targetSdk = 37",
        "backup disabled": 'android:allowBackup="false"',
        "cleartext disabled in manifest": 'android:usesCleartextTraffic="false"',
        "cleartext disabled in network policy": 'cleartextTrafficPermitted="false"',
        "Material 3 Expressive dependency": 'material3 = "1.5.0-alpha24"',
        "version code": "versionCode = 9",
        "version name": 'versionName = "0.7.0"',
        "versioned user agent": 'USER_AGENT = "CP-Companion-Android/0.7.0"',
        "Gradle distribution": "gradle-9.4.1-bin.zip",
        "Gradle checksum": "distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb",
        "Gradle wrapper invocation": '"$ROOT/gradlew"',
        "private fallback tracking notification": ".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)",
        "private progress tracking notification": ".setVisibility(Notification.VISIBILITY_PRIVATE)",
    }
    haystacks = {
        "applicationId": app_build,
        "compileSdk": app_build,
        "targetSdk": app_build,
        "backup disabled": manifest,
        "cleartext disabled in manifest": manifest,
        "cleartext disabled in network policy": network,
        "Material 3 Expressive dependency": versions,
        "version code": app_build,
        "version name": app_build,
        "versioned user agent": api_client,
        "Gradle distribution": wrapper,
        "Gradle checksum": wrapper,
        "Gradle wrapper invocation": build_script,
        "private fallback tracking notification": notification_factory,
        "private progress tracking notification": notification_factory,
    }
    for label, fragment in required_fragments.items():
        if fragment not in haystacks[label]:
            error(f"Project invariant missing: {label}")

    for path in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        for action in re.findall(r"uses:\s*([^\s#]+)", path.read_text()):
            if not action.startswith("./") and not re.fullmatch(r"[A-Za-z0-9_./-]+@[0-9a-f]{40}", action):
                error(f"Action must be pinned to an immutable revision: {action}")

    if "VISIBILITY_PUBLIC" in notification_factory:
        error("Tracking notifications must not expose travel details with VISIBILITY_PUBLIC")

    for path in sorted((APP / "src" / "main" / "java").rglob("*.kt")):
        if "androidx.compose.material." in path.read_text(encoding="utf-8"):
            error(f"Legacy Compose Material import found: {path.relative_to(ROOT)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checksums", action="store_true", help="Also verify the optional source-delivery manifest")
    args = parser.parse_args()
    parse_xml_files()
    check_translation_parity()
    privacy = ROOT / "PRIVACY.md"
    bundled_privacy = APP / "src/main/assets/privacy.txt"
    if not privacy.is_file() or not bundled_privacy.is_file() or privacy.read_bytes() != bundled_privacy.read_bytes():
        error("PRIVACY.md and the bundled English privacy policy must match")
    check_resource_references()
    check_project_invariants()
    if args.checksums:
        check_source_checksums()
    if ERRORS:
        print("Source validation failed:", file=sys.stderr)
        for message in ERRORS:
            print(f"  - {message}", file=sys.stderr)
        return 1
    print("Source validation passed: XML, resources, translations, and project invariants.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
