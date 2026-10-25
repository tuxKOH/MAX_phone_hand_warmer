#!/usr/bin/env python3
import os
import shutil
from pathlib import Path
root=Path(os.environ['QNN_SDK_ROOT'])
target=Path(__file__).resolve().parents[1]/'app/src/main/jniLibs/arm64-v8a'
libs=[root/'lib/aarch64-android'/name for name in ['libQnnHtp.so','libQnnHtpPrepare.so','libQnnSystem.so']]
for arch in [75,79,81]:
    libs += [root/'lib/aarch64-android'/f'libQnnHtpV{arch}{suffix}.so' for suffix in ['Stub','CalculatorStub']]
    libs += [root/f'lib/hexagon-v{arch}/unsigned/libQnnHtpV{arch}Skel.so']
for lib in libs:
    if not lib.is_file(): raise SystemExit(f'Missing {lib}; use one complete QAIRT SDK version')
target.mkdir(parents=True,exist_ok=True)
for lib in libs: shutil.copy2(lib,target/lib.name)
print(f'Staged {len(libs)} QNN runtime libraries in {target}')
