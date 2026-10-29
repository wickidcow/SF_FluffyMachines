#!/usr/bin/env python3
"""Offline release identity checks; this module never uploads or mutates GitHub."""
import argparse
import hashlib
import re
import struct
import zipfile
from pathlib import Path

MAIN_CLASS = 'io/ncbpfluffybear/fluffymachines/FluffyMachines.class'


def validate_request(event, ref, enabled, requested, expected, source, current_main):
    if event != 'workflow_dispatch' or ref != 'refs/heads/master' or enabled != 'true':
        raise ValueError('Publishing requires explicit opt-in dispatch on master')
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', requested) or requested != expected:
        raise ValueError('Requested numeric version does not match this source')
    if not re.fullmatch(r'[0-9a-f]{40}', source) or source != current_main:
        raise ValueError('Publishing source is not current master')


def validate_jar(path, version, expected_sha256):
    path = Path(path)
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', version):
        raise ValueError('Invalid numeric version')
    if path.name != f'SF_FluffyMachines{version}.jar':
        raise ValueError('Unexpected canonical artifact filename')
    if not re.fullmatch(r'[0-9a-f]{64}', expected_sha256):
        raise ValueError('Missing or invalid expected SHA-256')
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise ValueError('Artifact SHA-256 differs from the same-run build')
    with zipfile.ZipFile(path) as jar:
        if jar.testzip() is not None or len(jar.namelist()) != len(set(jar.namelist())):
            raise ValueError('Corrupt or duplicate JAR entries')
        descriptor = jar.read('plugin.yml').decode('utf-8')
        for key, wanted in (('name', 'FluffyMachines'), ('version', version),
                            ('api-version', '1.21.11'),
                            ('main', 'io.ncbpfluffybear.fluffymachines.FluffyMachines')):
            values = re.findall(r'^' + re.escape(key) + r':\s*([^\r\n]+)$', descriptor, re.MULTILINE)
            if len(values) != 1 or values[0].strip().strip("\"'") != wanted:
                raise ValueError(f'Unexpected or ambiguous plugin.yml {key}')
        main = jar.read(MAIN_CLASS)
        if len(main) < 8 or main[:4] != b'\xca\xfe\xba\xbe' or struct.unpack('>H', main[6:8])[0] != 65:
            raise ValueError('Canonical plugin main class is not Java 21 bytecode')
    return expected_sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    validate_jar(args.jar, args.version, args.sha256)
    print('Canonical Java 21 JAR and same-run SHA-256 verified')


if __name__ == '__main__':
    main()
