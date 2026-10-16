"""Sample primary/virtual capture; optional main-panel sleep is restored in finally."""
import argparse
import json
import re
import statistics
import subprocess
import time
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--host', required=True)
parser.add_argument('--tag', required=True)
parser.add_argument('--seconds', type=int, default=20)
parser.add_argument('--adb')
parser.add_argument('--serial')
parser.add_argument('--source-package', help='Optional source app: sample its rendering and scheduling group')
parser.add_argument('--sleep-main', action='store_true')
parser.add_argument('--panel-off', action='store_true', help='Blank physical panel through the virtual-screen API, without system sleep')
args = parser.parse_args()
if bool(args.adb) != bool(args.serial):
    parser.error('--adb and --serial must be supplied together')
if args.sleep_main and not args.adb:
    parser.error('--sleep-main requires --adb and --serial')
if args.panel_off and (args.sleep_main or not args.adb):
    parser.error('--panel-off requires ADB and cannot be combined with --sleep-main')
if args.source_package and not args.adb:
    parser.error('--source-package requires ADB and --serial')
folder = Path('work/virtual-stream-network') / args.tag
folder.mkdir(parents=True, exist_ok=True)


def get(path):
    with urllib.request.urlopen(f'http://{args.host}:47990/api/{path}', timeout=8) as response:
        return json.load(response)


def panel(off):
    request = urllib.request.Request(f'http://{args.host}:47990/api/virtual-screen/panel',
                                     data=json.dumps({'off': off}).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
    if result.get('status') != 'true':
        raise RuntimeError(result)


def physical_panel(label):
    raw = device('dumpsys', 'SurfaceFlinger')
    (folder / f'{label}-surfaceflinger.txt').write_text(raw, encoding='utf-8')
    state = re.search(r'^Display \d+ \(internal, primary,[^\n]*\)\s*\n(?:(?!^Display ).*\n)*?\s*powerMode=(\w+)', raw, re.MULTILINE)
    return state.group(1) if state else 'unknown'


def kernel_udp(label):
    if not args.adb:
        return {}
    raw = subprocess.check_output([args.adb, '-s', args.serial, 'shell', 'cat', '/proc/net/snmp'],
                                  encoding='utf-8', errors='replace', timeout=10)
    (folder / f'udp-{label}.txt').write_text(raw, encoding='utf-8')
    lines = [line.split()[1:] for line in raw.splitlines() if line.startswith('Udp:')]
    return dict(zip(lines[0], map(int, lines[1])))


samples = []
udp_before = kernel_udp('before')
start = time.monotonic()
sleep_requested = False


def device(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, 'shell', *command], encoding='utf-8', errors='replace', timeout=10)


try:
    for index in range(args.seconds + 1):
        item = {'elapsed': time.monotonic() - start, 'diagnostics': get('diagnostics'),
                'screen': get('virtual-screen'), 'sessions': get('runtime/sessions')}
        if args.adb and index % 15 == 0:
            thermal = device('dumpsys', 'thermalservice')
            (folder / f'{index:03}-thermal.txt').write_text(thermal, encoding='utf-8')
            status = re.search(r'Thermal Status:\s*(\d+)', thermal)
            item['thermal_status'] = int(status.group(1)) if status else None
            try:
                engine_pid = device('pidof', 'com.alkaidlab.sunshine:engine').strip()
                if engine_pid.isdigit():
                    groups = device('cat', f'/proc/{engine_pid}/cgroup')
                    (folder / f'{index:03}-engine-cgroup.txt').write_text(groups, encoding='utf-8')
                    item['engine_pid'] = int(engine_pid)
                    item['engine_cpuset'] = next((line.split(':', 2)[2] for line in groups.splitlines()
                                                  if ':cpuset:' in line), 'unknown')
            except subprocess.SubprocessError:
                item['engine_cpuset'] = 'unavailable'
            if args.source_package:
                source_pid = device('pidof', args.source_package).strip()
                source = {'pid': source_pid}
                if source_pid.isdigit():
                    groups = device('cat', f'/proc/{source_pid}/cgroup')
                    (folder / f'{index:03}-source-cgroup.txt').write_text(groups, encoding='utf-8')
                    source['cpuset'] = next((line.split(':', 2)[2] for line in groups.splitlines()
                                             if ':cpuset:' in line), 'unknown')
                    gfx = device('dumpsys', 'gfxinfo', args.source_package)
                    (folder / f'{index:03}-source-gfx.txt').write_text(gfx, encoding='utf-8')
                    count = re.search(r'Total frames rendered:\s*(\d+)', gfx)
                    source['rendered_frames'] = int(count.group(1)) if count else None
                windows = device('dumpsys', 'window', 'displays')
                (folder / f'{index:03}-window-displays.txt').write_text(windows, encoding='utf-8')
                primary = re.search(r'Display: mDisplayId=0\b(.*?)(?=Display: mDisplayId=|\Z)', windows, re.DOTALL)
                focus = re.search(r'mFocusedApp=([^\n]+)', primary.group(1)) if primary else None
                source['primary_focused_app'] = focus.group(1).strip() if focus else 'unknown'
                item['source_app'] = source
        if args.sleep_main or args.panel_off:
            raw = device('cmd', 'display', 'get-displays')
            (folder / f'{index:03}-displays.txt').write_text(raw, encoding='utf-8')
            primary = next((line for line in raw.splitlines() if 'Display id 0:' in line), '')
            state = re.search(r'\bstate (ON|OFF|DOZE|DOZE_SUSPEND)\b', primary)
            item['primary_state'] = state.group(1) if state else 'unknown'
        if args.panel_off and index % 15 == 0:
            item['physical_panel_power'] = physical_panel(f'{index:03}')
        samples.append(item)
        (folder / f'{index:03}.json').write_text(json.dumps(item, ensure_ascii=False, indent=2), encoding='utf-8')
        if index == 0 and (args.sleep_main or args.panel_off):
            sleep_requested = True
            if args.panel_off:
                panel(True)
            else:
                device('input', 'keyevent', '223')
        if index < args.seconds:
            time.sleep(max(0, start + index + 1 - time.monotonic()))
finally:
    if sleep_requested:
        if args.panel_off:
            panel(False)
            (folder / 'restored-panel.json').write_text(json.dumps({'physical_panel_power': physical_panel('restored')}, indent=2), encoding='utf-8')
        else:
            device('input', 'keyevent', '224')
        (folder / 'restored-displays.txt').write_text(device('cmd', 'display', 'get-displays'), encoding='utf-8')

report = {}
report['_thermal_status_samples'] = [{'elapsed': sample['elapsed'], 'status': sample['thermal_status']}
                                     for sample in samples if 'thermal_status' in sample]
report['_engine_cpuset_samples'] = [{'elapsed': sample['elapsed'], 'pid': sample.get('engine_pid'), 'cpuset': sample['engine_cpuset']}
                                    for sample in samples if 'engine_cpuset' in sample]
if args.source_package:
    report['_source_app_samples'] = [{'elapsed': sample['elapsed'], **sample['source_app']}
                                     for sample in samples if 'source_app' in sample]
if args.sleep_main or args.panel_off:
    report['_primary_states'] = [sample.get('primary_state') for sample in samples]
if args.panel_off:
    report['_physical_panel_states'] = [{'elapsed': sample['elapsed'], 'power': sample['physical_panel_power']}
                                        for sample in samples if 'physical_panel_power' in sample]
for sid in dict.fromkeys(v['session_id'] for sample in samples for v in sample['diagnostics']['video']):
    rows = [(s['elapsed'], v) for s in samples for v in s['diagnostics']['video'] if v['session_id'] == sid]
    last = rows[-1][1]
    first = rows[0][1]
    row = {'seconds': rows[-1][0] - rows[0][0], 'first_elapsed': rows[0][0], 'last_elapsed': rows[-1][0],
           'present_at_end': any(v['session_id'] == sid for v in samples[-1]['diagnostics']['video']),
           'sample_count': len(rows), 'codec': last['codec'], 'fps': last['configured_fps']}
    targets = [target for sample in samples if rows[0][0] <= sample['elapsed'] <= rows[-1][0]
               for target in sample['diagnostics']['frames']['targets']
               if target['thread_id'] == last['thread_id']]
    row['effective_fps'] = sorted(set(target['effective_fps'] for target in targets))
    row['in_flight_frames_max'] = max((target.get('in_flight_frames', 0) for target in targets), default=0)
    row['submission_timeouts_max'] = max((target.get('submission_timeouts', 0) for target in targets), default=0)
    row['submission_timeouts_delta'] = targets[-1].get('submission_timeouts', 0) - targets[0].get('submission_timeouts', 0) if targets else 0
    row['encoder_skipped_inputs_delta'] = targets[-1].get('encoder_skipped_inputs', 0) - targets[0].get('encoder_skipped_inputs', 0) if targets else 0
    row['bitrate_mode'] = last.get('bitrate_mode')
    row['resolution'] = [last['encoded_width'], last['encoded_height']]
    row['applied_bitrate_kbps'] = sorted(set(v['applied_bitrate_kbps'] for _, v in rows))
    row['adaptive_enabled'] = last.get('adaptive_enabled', False)
    row['adaptive_states'] = sorted(set(v.get('adaptive_state', 'unknown') for _, v in rows))
    row['fec_percentages'] = sorted(set(v.get('fec_percentage', 0) for _, v in rows))
    peers = [session for sample in samples for session in sample['sessions']['sessions'] if session['session_id'] == sid]
    row['client_ips'] = sorted(set(peer['client_ip'] for peer in peers))
    for key in ('encoded_frames', 'queue_dropped_frames', 'sent_data_packets', 'sent_parity_packets',
                'sent_udp_payload_bytes', 'send_errors', 'send_buffer_retries', 'send_syscalls', 'idr_requests', 'keyframes', 'fec_skipped_blocks', 'pacing_timer_slack_resets', 'network_control_updates'):
        row[key + '_delta'] = last.get(key, 0) - first.get(key, 0)
    for key in ('peak_frame_bytes', 'peak_keyframe_bytes', 'queue_peak_bytes', 'pacing_timer_slack_ns', 'pacing_timer_slack_peak_ns', 'last_send_errno', 'encoder_reported_latency_frames', 'socket_send_buffer_bytes'):
        row[key] = last.get(key)
    for key in ('submit_to_output_p95_ms', 'queue_delay_p95_ms', 'socket_send_p95_ms', 'control_rtt_ms', 'encoded_fps',
                'encoded_bitrate_kbps', 'pacing_wait_p95_ms', 'frame_send_p95_ms', 'frame_prepare_p95_ms',
                'packet_header_p95_ms', 'frame_cpu_p95_ms', 'fec_compute_p95_ms'):
        values = [v[key] for _, v in rows if v.get(key) is not None]
        row[key + '_min_median_max'] = [min(values), statistics.median(values), max(values)] if values else None
    report[str(sid)] = row
udp_after = kernel_udp('after')
if udp_before:
    report['_kernel_udp_delta'] = {key: udp_after[key] - value for key, value in udp_before.items()}
(folder / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
