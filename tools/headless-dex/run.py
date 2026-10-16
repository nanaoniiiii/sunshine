"""Build/test the loopback DeX prototype; explicit --attempt requests a device session."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
BUILD = ROOT.parents[1] / 'work' / 'headless-dex'


def run(arguments, timeout=60, allowed=(0,)):
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True,
                            text=True, errors='replace', timeout=timeout)
    if result.returncode not in allowed:
        raise RuntimeError(f'{Path(arguments[0]).name} exited {result.returncode}:\n{result.stdout}\n{result.stderr}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='ADB device serial or IP:port')
    parser.add_argument('--attempt', action='store_true', help='Explicitly request loopback wireless DeX')
    parser.add_argument('--test-only', action='store_true', help='Only run JVM loopback protocol tests')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME') or
                        os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'AppData/Local/Android/Sdk'))
    args = parser.parse_args()
    if args.attempt and args.test_only:
        parser.error('--attempt cannot be combined with --test-only')
    if not args.test_only and not args.serial:
        parser.error('--serial is required for device operations')
    for command in ('javac', 'java', 'jar'):
        if not shutil.which(command):
            raise RuntimeError(f'{command} is not on PATH; use JDK 17+')
    jvm = BUILD / 'jvm-tests'
    jvm.mkdir(parents=True, exist_ok=True)
    run(['javac', '--release', '8', '-d', jvm, ROOT / 'LocalWfdSink.java', ROOT / 'LocalWfdSinkTest.java'])
    tests = run(['java', '-cp', jvm, 'LocalWfdSinkTest'], timeout=15)
    print(tests.stdout.strip())
    (BUILD / 'protocol-test.log').write_text(tests.stdout, encoding='utf-8')
    if args.test_only:
        return 0
    sdk_jar = args.sdk / 'platforms/android-34/android.jar'
    d8 = args.sdk / 'build-tools/34.0.0/lib/d8.jar'
    if not sdk_jar.is_file() or not d8.is_file():
        raise RuntimeError('Android SDK platform 34 and build-tools 34.0.0 are required; specify --sdk')
    if not shutil.which('adb'):
        raise RuntimeError('adb is not on PATH')
    classes, dex = BUILD / 'android-classes', BUILD / 'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    run(['javac', '--release', '8', '-cp', sdk_jar, '-d', classes,
         ROOT / 'HeadlessDex.java', ROOT / 'LocalWfdSink.java'])
    archive = BUILD / 'headless-dex.jar'
    run(['jar', 'cf', archive, '-C', classes, '.'])
    run(['java', '-cp', d8, 'com.android.tools.r8.D8', '--min-api', '29',
         '--lib', sdk_jar, '--output', dex, archive])
    mode = '--attempt' if args.attempt else '--diagnose'
    run(['adb', '-s', args.serial, 'push', dex / 'classes.dex', '/data/local/tmp/sunshine-headless-dex.dex'])
    result = run(['adb', '-s', args.serial, 'shell',
                  f'CLASSPATH=/data/local/tmp/sunshine-headless-dex.dex app_process / HeadlessDex {mode}'],
                 timeout=45, allowed=(0, 3, 4))
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip().startswith('{')]
    if len(lines) != 1:
        raise RuntimeError(f'Expected one JSON report:\n{result.stdout}\n{result.stderr}')
    report = json.loads(lines[0])
    path = BUILD / ('attempt.json' if args.attempt else 'diagnose.json')
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f'Report: {path}')
    return result.returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
