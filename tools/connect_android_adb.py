"""Discover an existing Wi-Fi ADB endpoint through USB and verify device identity.

Does not enable debugging, restart adbd/the host server, or change device settings.
Run from any folder; supply the USB serial explicitly to avoid selecting another device.
"""
import argparse
import ipaddress
import json
import re
import subprocess
import time
from pathlib import Path


def adb_command(adb, *arguments, timeout=8):
    result = subprocess.run([adb, *arguments], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return result.stdout.strip()


def shell(adb, serial, *arguments):
    return adb_command(adb, '-s', serial, 'shell', *arguments)


def discover(adb, serial):
    identity = shell(adb, serial, 'getprop', 'ro.serialno')
    if not identity:
        raise RuntimeError('USB device did not report its identity')
    addresses = shell(adb, serial, 'ip', '-o', '-4', 'addr', 'show', 'wlan0')
    match = re.search(r'\binet (\d+\.\d+\.\d+\.\d+)/', addresses)
    if not match:
        raise RuntimeError('USB device has no wlan0 IPv4 address')
    address = str(ipaddress.IPv4Address(match.group(1)))
    tls_enabled = shell(adb, serial, 'settings', 'get', 'global', 'adb_wifi_enabled') == '1'
    tls_port = shell(adb, serial, 'getprop', 'service.adb.tls.port')
    tcp_port = shell(adb, serial, 'getprop', 'service.adb.tcp.port')
    candidates = [('tls', tls_port)] if tls_enabled else []
    candidates.append(('tcp', tcp_port))
    for mode, port in candidates:
        if port.isdigit() and 1 <= int(port) <= 65535:
            return {'serial': identity, 'model': shell(adb, serial, 'getprop', 'ro.product.model'),
                    'endpoint': f'{address}:{port}', 'mode': mode,
                    'native_wireless_debugging': tls_enabled}
    raise RuntimeError('No active wireless ADB port. Open wireless debugging on the device first.')


def connect(adb, device):
    endpoint = device['endpoint']
    adb_command(adb, 'connect', endpoint)
    identity = shell(adb, endpoint, 'getprop', 'ro.serialno')
    if identity != device['serial']:
        # Disconnect only the endpoint just selected, never other devices.
        adb_command(adb, 'disconnect', endpoint)
        raise RuntimeError('Wireless endpoint identity differs from the USB device')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--usb-serial', required=True)
    parser.add_argument('--check-seconds', type=int, default=0,
                        help='Finite once-per-second check; exit on any failed round trip')
    parser.add_argument('--output', type=Path, help='Optional local JSON report (contains device identity)')
    args = parser.parse_args()
    if not 0 <= args.check_seconds <= 3600:
        parser.error('--check-seconds must be between 0 and 3600')
    device = discover(args.adb, args.usb_serial)
    connect(args.adb, device)
    print(f"Verified {device['model']}: {device['endpoint']} ({device['mode']})", flush=True)
    report = {'device': device, 'samples': []}
    started = time.monotonic()
    try:
        for index in range(args.check_seconds):
            before = time.monotonic()
            row = {'elapsed': round(before - started, 3)}
            try:
                value = shell(args.adb, device['endpoint'], 'echo', 'sunshine-adb-check')
                row.update(ok=value == 'sunshine-adb-check',
                           round_trip_ms=round((time.monotonic() - before) * 1000, 2))
                report['samples'].append(row)
                if not row['ok']:
                    raise RuntimeError('Unexpected wireless response')
            except Exception as error:
                if not report['samples'] or report['samples'][-1] is not row:
                    report['samples'].append({**row, 'ok': False, 'error': str(error)})
                raise
            if index + 1 < args.check_seconds:
                time.sleep(max(0, started + index + 1 - time.monotonic()))
    finally:
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    if args.check_seconds:
        print(f"Passed {len(report['samples'])} wireless checks", flush=True)


if __name__ == '__main__':
    main()
