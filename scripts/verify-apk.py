#!/usr/bin/env python3
"""Check the install-compatible native-library layout without running the app."""
import argparse
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--qnn-sdk', type=Path)
args = parser.parse_args()

with zipfile.ZipFile(args.apk) as apk:
    entries = apk.infolist()
    names = [entry.filename for entry in entries]
    assert len(names) == len(set(names)), 'Duplicate ZIP entries'
    assert apk.testzip() is None, 'ZIP CRC failure'
    native = [entry for entry in entries if entry.filename.startswith('lib/')]
    assert native, 'Missing native libraries'
    for entry in native:
        assert entry.compress_type == zipfile.ZIP_STORED, f'Compressed native library: {entry.filename}'
    for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
        for name in ['libmaxheater.so', 'libc++_shared.so']:
            data = apk.read(f'lib/{abi}/{name}')
            assert data[:5] == b'\x7fELF\x02', f'Expected ELF64: {abi}/{name}'
            assert int.from_bytes(data[18:20], 'little') == machine, f'Incorrect CPU ABI: {abi}/{name}'
    for name in ['libQnnHtp.so', 'libQnnHtpPrepare.so', 'libQnnSystem.so']:
        assert f'lib/arm64-v8a/{name}' in names, f'Missing {name}'
    for arch in [73, 75, 79, 81]:
        for suffix in ['Stub', 'CalculatorStub', 'Skel']:
            name = f'libQnnHtpV{arch}{suffix}.so'
            data = apk.read(f'lib/arm64-v8a/{name}')
            if args.qnn_sdk and suffix == 'Skel':
                source = args.qnn_sdk / f'lib/hexagon-v{arch}/unsigned' / name
                assert data == source.read_bytes(), f'DSP Skel changed: {name}'
    for name in ['QAIRT_LICENSE.pdf', 'QNN_NOTICE.txt', 'ANDROID_NDK_NOTICE.txt', 'APACHE-2.0.txt']:
        assert f'assets/third_party/{name}' in names, f'Missing {name}'
    print(f'PASS: CRC, unique entries, {len(native)} stored native libraries, CPU ABIs, QNN v73/v75/v79/v81, third-party notices')
