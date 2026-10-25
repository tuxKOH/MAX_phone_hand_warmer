#!/usr/bin/env python3
"""Bounded Android instrumentation with persistent results and optional KGSL samples."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--case', type=int, choices=range(6), required=True,
               help='0/1/2=NPU 1024/2048/3072; 3/4=GPU queue 1/4; 5=CPU+GPU+NPU')
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
args.output.parent.mkdir(parents=True, exist_ok=True)
base = ['adb', '-s', args.serial]
run_id = uuid.uuid4().hex

def adb(*command, timeout=4):
    return subprocess.run(base + list(command), capture_output=True, text=True, timeout=timeout)

# Launch errors fail immediately, while optional telemetry errors never abort the test.
adb('get-state').check_returncode()
with args.output.with_suffix('.instrumentation.txt').open('w') as stream, \
        args.output.with_suffix('.telemetry.jsonl').open('w') as telemetry:
    child = subprocess.Popen(base + ['shell', 'am', 'instrument', '-w',
        '-e', 'mode', 'bench', '-e', 'case', str(args.case), '-e', 'run_id', run_id,
        'dev.maxheater.test/dev.maxheater.SmokeInstrumentation'], stdout=stream, stderr=subprocess.STDOUT)
    terminal = False
    report = ''
    deadline = time.monotonic() + 50
    try:
        while time.monotonic() < deadline:
            sample = {'host_monotonic': time.monotonic()}
            try:
                raw = adb('shell', 'cat /proc/uptime; cat /sys/class/kgsl/kgsl-3d0/gpubusy; '
                          'cat /sys/class/kgsl/kgsl-3d0/gpuclk; cat /sys/class/kgsl/kgsl-3d0/max_gpuclk', timeout=2)
                sample['raw'] = raw.stdout.strip()
                sample['returncode'] = raw.returncode
                lines = raw.stdout.splitlines()
                if len(lines) >= 4:
                    sample['device_uptime_seconds'] = float(lines[0].split()[0])
                    busy, total = map(int, lines[1].split())
                    sample['busy'] = busy
                    sample['total'] = total
                    # KGSL reports a driver interval pair. Do not difference it
                    # again or interpret a zero denominator as an idle sample.
                    sample['busy_percent'] = busy * 100 / total if total > 0 and 0 <= busy <= total else None
                    sample['gpu_hz'] = int(lines[2])
                    sample['max_gpu_hz'] = int(lines[3])
            except (subprocess.TimeoutExpired, ValueError, IndexError) as error:
                sample['telemetry_error'] = str(error)
            telemetry.write(json.dumps(sample) + '\n')
            telemetry.flush()
            try:
                saved = adb('shell', 'run-as', 'dev.maxheater', 'cat', 'files/workload-bench.txt', timeout=2)
                if saved.returncode == 0 and saved.stdout.startswith('RUNNING id=' + run_id + '\n'):
                    report = saved.stdout
                    args.output.write_text(report)
                    terminal = 'PASS workload sweep' in report or 'FAIL / STOPPED:' in report
            except subprocess.TimeoutExpired:
                pass
            if terminal:
                break
            if child.poll() is not None and not report:
                # Give a recovering wireless connection a chance to retrieve
                # the device-side file even if the host command already ended.
                pass
            time.sleep(0.35)
    finally:
        if not terminal:
            # Stop device instrumentation too: compilation must not be followed
            # by an unattended workload after the host timeout.
            try:
                adb('shell', 'am', 'force-stop', 'dev.maxheater', timeout=3)
            except subprocess.TimeoutExpired:
                pass
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
    if not terminal:
        raise SystemExit('No terminal result retrieved; native run is bounded to 9 s. Recover files/workload-bench.txt with run-as. Do not treat this run as PASS.')
    print(report, end='')
    if 'FAIL / STOPPED:' in report:
        raise SystemExit(1)
