#!/usr/bin/env python3
"""Build offline APK notices from reviewed POM metadata and actual runtime artifacts."""
import argparse
from collections import defaultdict
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'app/src/main/assets/third-party-notices.txt'


def embedded_notices(data: bytes, prefix: str = ''):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith('/'): continue
            if any(word in name.lower() for word in ('license', 'notice', 'copyright')):
                yield prefix + name, archive.read(name).decode('utf-8').strip()
            elif name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                yield from embedded_notices(archive.read(name), prefix + name + '!')


def generate() -> str:
    metadata = json.loads((ROOT / 'docs/third-party/dependencies.json').read_text())
    reviewed = {entry['coordinate']: entry for entry in metadata}
    artifacts = defaultdict(list)
    for line in (ROOT / 'app/build/reports/release-dependencies.tsv').read_text().splitlines():
        coordinate, filename = line.split('\t', 1)
        artifacts[coordinate].append(Path(filename))
    if set(reviewed) != set(artifacts):
        raise ValueError(f'Review dependency licenses and update docs/third-party/dependencies.json. Added: {sorted(set(artifacts)-set(reviewed))}; removed: {sorted(set(reviewed)-set(artifacts))}')
    sections = ['CP Companion — third-party software notices\n\nCopyright and license rights remain with the upstream authors.\nThis inventory covers the resolved release runtime, including transitive libraries.\nDependency metadata and embedded notices follow.']
    embedded = defaultdict(set)
    for coordinate in sorted(artifacts):
        entry = reviewed[coordinate]
        if not entry['licenses']: raise ValueError(f'No reviewed license: {coordinate}')
        sections.append(coordinate + '\n' + '\n'.join(f"{item['name']} — {item['url']}" for item in entry['licenses']) + '\nMetadata: ' + entry['pom'] + ('\nInherited license: ' + entry['licenseSource'] if 'licenseSource' in entry else ''))
        for path in artifacts[coordinate]:
            for name, text in embedded_notices(path.read_bytes()):
                embedded[text].add(coordinate + ' / ' + name)
    for text, sources in sorted(embedded.items(), key=lambda pair: sorted(pair[1])):
        sections.append('Embedded notice for:\n' + '\n'.join(sorted(sources)) + '\n\n' + text)
    for name in ('Apache-2.0.txt', 'MPL-2.0.txt'):
        sections.append(name + '\n\n' + (ROOT / 'docs/third-party' / name).read_text().strip())
    sections.append('OkHttp includes Mozilla Public Suffix List data under MPL-2.0.\nSource: https://publicsuffix.org/list/public_suffix_list.dat\nThe original public suffix notice is included above. CP Companion does not modify that data.')
    return '\n\n'.join(sections) + '\n'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = generate()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != result:
            raise SystemExit('Third-party notices are stale. Run python3 tools/generate_notices.py, then rebuild.')
        print('Third-party notices match every resolved release dependency.')
    else:
        OUTPUT.write_text(result)
        print(f'Wrote {OUTPUT.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
