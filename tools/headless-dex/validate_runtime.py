"""Sample real DeX rendering/capture; optionally sleep the phone and always wake it afterwards."""
import argparse
import csv
import io
import json
import re
import statistics
import subprocess
import time
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--host', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=90)
    parser.add_argument('--interval', type=float, default=15)
    parser.add_argument('--sleep', action='store_true')
    args = parser.parse_args()
    if args.seconds < 1 or args.interval < 0.5:
        parser.error('seconds must be positive and interval must be at least 0.5 seconds')
    args.output.mkdir(parents=True, exist_ok=True)

    def shell(*command):
        result = subprocess.run([args.adb, '-s', args.serial, 'shell', *command],
                                capture_output=True, encoding='utf-8', errors='replace', timeout=15)
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout

    def api(path):
        with urllib.request.urlopen(f'http://{args.host}:47990/api/{path}', timeout=8) as response:
            return json.load(response)

    def sample(label):
        result = {'elapsed': round(time.monotonic() - start, 2), 'diagnostics': api('diagnostics'),
                  'virtual_screen': api('virtual-screen'), 'sessions': api('runtime/sessions')}
        for name, command in [('power', ['dumpsys', 'power']),
                              ('displays', ['cmd', 'display', 'get-displays']),
                              ('gfx', ['dumpsys', 'gfxinfo', 'com.alkaidlab.sunshine.headlessprobe', 'framestats'])]:
            raw = shell(*command)
            (args.output / f'{label}-{name}.txt').write_text(raw, encoding='utf-8')
            if name == 'displays':
                primary = next((line for line in raw.splitlines() if 'Display id 0:' in line), '')
                state = re.search(r'\bstate (ON|OFF|DOZE|DOZE_SUSPEND)\b', primary)
                result['primary_display_state'] = state.group(1) if state else 'unknown'
            if name == 'power':
                result['wakefulness'] = next((line.strip() for line in raw.splitlines() if 'mWakefulness=' in line), '')
            if name == 'gfx':
                rows = []
                for block in raw.split('---PROFILEDATA---')[1::2]:
                    for row in csv.DictReader(io.StringIO(block.strip())):
                        try:
                            if int(row['Flags']) == 0:
                                rows.append(int(row['IntendedVsync']))
                        except (KeyError, ValueError):
                            pass
                times = sorted(set(rows))
                deltas = [b-a for a, b in zip(times, times[1:]) if 0 < b-a < 1_000_000_000]
                result['app_frames'] = {'count': len(times), 'last_intended_vsync_ns': times[-1] if times else None,
                                        'median_vsync_interval_ms': statistics.median(deltas)/1e6 if deltas else None}
        (args.output / f'{label}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        frames = result['diagnostics']['frames']
        print(json.dumps({'label': label, 'elapsed': result['elapsed'], 'wakefulness': result['wakefulness'],
                          'capture_fps': frames['capture_fps'], 'capture_age_ms': frames['capture_age_ms'],
                          'surface_failures': frames['surface_failures'], 'app_frames': result['app_frames']}, ensure_ascii=False), flush=True)
        return result

    start = time.monotonic()
    results = []
    try:
        results.append(sample('before'))
        if args.sleep:
            shell('input', 'keyevent', '223')
        deadline = time.monotonic() + args.seconds
        index = 0
        while time.monotonic() < deadline:
            time.sleep(min(args.interval, max(0, deadline-time.monotonic())))
            index += 1
            results.append(sample(f'during-{index:02d}'))
    finally:
        if args.sleep:
            shell('input', 'keyevent', '224')
            time.sleep(2)
            results.append(sample('restored'))
        (args.output / 'samples.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
