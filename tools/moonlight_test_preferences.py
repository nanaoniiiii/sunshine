"""Configure only test display/bitrate preferences of a debuggable Moonlight app; keep a restorable local backup."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', required=True)
parser.add_argument('--backup', type=Path, required=True)
parser.add_argument('--resolution')
parser.add_argument('--fps', type=int)
parser.add_argument('--bitrate', type=int)
parser.add_argument('--portrait', action='store_true')
parser.add_argument('--restore', action='store_true')
args = parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_.]+', args.package):
    parser.error('Invalid package')
if args.resolution and not re.fullmatch(r'[0-9]{3,4}x[0-9]{3,4}', args.resolution):
    parser.error('Resolution must be WIDTHxHEIGHT')
if args.fps is not None and not 15 <= args.fps <= 240:
    parser.error('FPS must be within 15..240')


def adb(*command, data=None):
    return subprocess.run([args.adb, '-s', args.serial, *command], input=data, capture_output=True, check=True).stdout


adb('shell', 'am', 'force-stop', args.package)
remote = f'shared_prefs/{args.package}_preferences.xml'
current = adb('shell', 'run-as', args.package, 'cat', remote)
if not args.backup.exists():
    args.backup.parent.mkdir(parents=True, exist_ok=True)
    args.backup.write_bytes(current)
if args.restore:
    content = args.backup.read_bytes()
    ET.fromstring(content)
else:
    root = ET.fromstring(current)

    def value(key, text, kind='string'):
        node = root.find(f"*[@name='{key}']")
        if node is None:
            node = ET.SubElement(root, kind, name=key)
        node.tag = kind
        if kind == 'boolean':
            node.text = None
            node.set('value', text)
        else:
            node.attrib.pop('value', None)
            node.text = text

    if args.resolution:
        value('list_resolution', args.resolution)
    if args.fps:
        value('list_fps', str(args.fps))
        value('checkbox_unlock_fps', str(args.fps > 60).lower(), 'boolean')
    if args.bitrate:
        value('edit_diy_bitrate', str(args.bitrate))
    if args.portrait:
        value('checkbox_enable_portrait', 'true', 'boolean')
    content = ET.tostring(root, encoding='utf-8', xml_declaration=True)
command = f'run-as {shlex.quote(args.package)} sh -c {shlex.quote("cat > " + shlex.quote(remote))}'
adb('shell', command, data=content)
check = ET.fromstring(adb('shell', 'run-as', args.package, 'cat', remote))
for key in ('list_resolution', 'list_fps', 'edit_diy_bitrate'):
    node = check.find(f"*[@name='{key}']")
    print(f'{key}={node.text if node is not None else "default"}')
