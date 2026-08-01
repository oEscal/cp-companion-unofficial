#!/usr/bin/env python3
"""Regenerate the source-delivery checksum manifest from repository files."""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "SOURCE_SHA256SUMS.txt"
EXCLUDED_PREFIXES = ("app/build/",)


def git_paths(*args: str) -> set[Path]:
    result = subprocess.run(
        ["git", *args, "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return {
        Path(value.decode("utf-8"))
        for value in result.stdout.split(b"\0")
        if value
    }


def main() -> int:
    paths = git_paths("ls-files") | git_paths("ls-files", "--others", "--exclude-standard")
    output_relative = OUTPUT.relative_to(ROOT)
    lines: list[str] = []
    for relative in sorted(paths, key=lambda path: path.as_posix()):
        relative_name = relative.as_posix()
        if relative == output_relative or relative_name.startswith(EXCLUDED_PREFIXES):
            continue
        source = ROOT / relative
        if not source.is_file():
            continue
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        lines.append(f"{digest}  ./{relative_name}")
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Updated {OUTPUT.relative_to(ROOT)} with {len(lines)} entries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
