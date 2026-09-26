#!/usr/bin/env python3
"""Validate a release tag and package the exact tagged source alongside a signed APK."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def validate_ref(ref: str, version: str) -> str:
    tag = ref.removeprefix('refs/tags/')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?', version):
        raise ValueError('versionName must be X.Y.Z or X.Y.Z-alpha/beta/rc.N')
    suffix = r'(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?' if '-' not in version else ''
    if not ref.startswith('refs/tags/') or not re.fullmatch(r'v' + re.escape(version) + suffix, tag):
        raise ValueError(f'Tag must match versionName, for example v{version}')
    return tag


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-ref', required=True)
    parser.add_argument('--package', action='store_true')
    parser.add_argument('--github-output', type=Path, help='Write validated tag, immutable commit, and prerelease status for Actions')
    args = parser.parse_args()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT/'app/build.gradle.kts').read_text()).group(1)
    tag = validate_ref(args.check_ref, version)
    head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).strip()
    tagged = subprocess.check_output(['git','rev-parse',f'refs/tags/{tag}^{{commit}}'],cwd=ROOT).strip()
    if head != tagged: raise SystemExit('Checkout does not match the release tag.')
    if subprocess.check_output(['git','status','--porcelain','--untracked-files=normal'],cwd=ROOT).strip():
        raise SystemExit('Release source must be committed and clean.')
    names = subprocess.check_output(['git','ls-tree','-r','--name-only','HEAD'],cwd=ROOT).decode().splitlines()
    if not any(Path(name).parent == Path('.') and Path(name).name.upper().startswith(('LICENSE','COPYING')) for name in names):
        raise SystemExit('The maintainer-selected license must be committed before a release.')
    from validate_repository import forbidden
    bad = [name for name in names if forbidden(name)]
    if bad: raise SystemExit(f'Private/generated files remain in release source: {bad}')
    if not (ROOT/'docs/RELEASE_NOTES.md').is_file(): raise SystemExit('Missing release notes.')
    if args.package:
        output = ROOT/'dist'
        for name in ('cp-companion.apk','SIGNING_CERTIFICATE.txt'):
            if not (output/name).is_file(): raise SystemExit('Run tools/build_release.sh first.')
        subprocess.run(['git','archive','--format=tar.gz',f'--prefix=cp-companion-{tag}/',f'--output={output}/cp-companion-{tag}-source.tar.gz','HEAD'],cwd=ROOT,check=True)
        paths = sorted(path for path in output.iterdir() if path.is_file() and path.name != 'SHA256SUMS')
        (output/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in paths))
    if args.github_output:
        with args.github_output.open('a') as output:
            output.write(f'tag={tag}\nsha={head.decode()}\nprerelease={str("-" in tag).lower()}\n')
    print(f'Release source checked: {tag}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
