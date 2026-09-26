#!/usr/bin/env python3
"""Validate an apksigner --verbose --print-certs report against a known certificate."""
import os
from pathlib import Path
import re
import sys


def verify(report: str, expected: str) -> None:
    expected = expected.replace(':', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Expected certificate fingerprint must contain 64 hex digits')
    # Android build-tools versions use either "Signer #1" or "V2 Signer" labels.
    digests = {value.lower() for value in re.findall(r'^.*certificate SHA-256 digest: ([0-9a-fA-F]+)$', report, re.M)}
    if not re.search(r'^Number of signers: 1$', report, re.M) or digests != {expected}:
        raise ValueError('APK signer does not match the expected single release certificate')


if __name__ == '__main__':
    verify(Path(sys.argv[1]).read_text(), os.environ['CP_RELEASE_CERT_SHA256'])
