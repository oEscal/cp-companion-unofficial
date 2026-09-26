#!/usr/bin/env bash
# Linux x86_64 bootstrap, with a fixed binary checksum. Other hosts may install this version themselves.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if command -v gitleaks >/dev/null; then
  GITLEAKS="$(command -v gitleaks)"
else
  [[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || { echo 'Install Gitleaks 8.30.1 and rerun.' >&2; exit 1; }
  SCAN_TEMP="$(mktemp -d)"
  trap 'rm -rf "$SCAN_TEMP"' EXIT
  curl --fail --location --retry 3 --output "$SCAN_TEMP/gitleaks.tar.gz" \
    https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz
  python3 - "$SCAN_TEMP/gitleaks.tar.gz" <<'PY'
import hashlib, pathlib, sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != '551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb':
    raise SystemExit('Gitleaks checksum mismatch')
PY
  tar -xzf "$SCAN_TEMP/gitleaks.tar.gz" -C "$SCAN_TEMP" gitleaks
  GITLEAKS="$SCAN_TEMP/gitleaks"
fi
"$GITLEAKS" git "$ROOT" --log-opts=--all --redact --no-banner
# Scan publication candidates too, including edits which have not yet been committed.
python3 - "$ROOT" "$GITLEAKS" <<'PY'
from pathlib import Path
import shutil, subprocess, sys, tempfile
root=Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='cp-secret-scan-') as directory:
    paths=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root).decode().split('\0')
    for name in set(paths):
        source=root/name
        if not name or not source.is_file() or source.is_symlink(): continue
        dest=Path(directory)/name
        dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,dest)
    subprocess.run([sys.argv[2],'dir',directory,'--redact','--no-banner'],check=True)
PY
