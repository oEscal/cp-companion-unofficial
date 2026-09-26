#!/usr/bin/env python3
"""Check files selected for publication; ignored local SDK files are allowed."""
from pathlib import Path, PurePosixPath
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def forbidden(name: str) -> bool:
    path = PurePosixPath(name)
    return (
        any(part in {"build", ".gradle", ".kotlin", ".idea", ".agents", ".codex"} for part in path.parts)
        or path.suffix.lower() in {".apk", ".aab", ".apks", ".jks", ".keystore", ".p12", ".pfx", ".har", ".log", ".db", ".sqlite", ".sqlite3"}
        or path.name in {"PLAN.md", "local.properties", "secrets.properties", "signing.properties", "keystore.properties", "release.properties"}
        or (path.name.startswith(".env") and path.name != ".env.example")
    )


def main() -> int:
    top = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=ROOT, capture_output=True, text=True)
    if top.returncode != 0 or Path(top.stdout.strip()).resolve() != ROOT.resolve():
        print("Source archive: no Git metadata. Publication files are checked before archive creation.")
        return 0
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT,
    ).decode().split("\0")
    bad = sorted({name for name in names if name and forbidden(name)})
    if bad:
        print("Files must be removed from publication (git rm --cached preserves local files):", file=sys.stderr)
        print("\n".join(bad), file=sys.stderr)
        return 1
    print("Repository file checks passed; local ignored SDK configuration is allowed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
