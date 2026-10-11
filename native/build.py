"""Build the pinned Android Sunshine source; never handles APK signing secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
LOCK = json.loads((ROOT / 'sources.lock.json').read_text())
CPP_REL = Path('app-mirror/src/main/cpp')

def run(args, cwd=None):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)

def output(args, cwd):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()

def apply_patch(source, patch):
    forward = ['git', 'apply', '--check', str(patch)]
    reverse = ['git', 'apply', '--reverse', '--check', str(patch)]
    if subprocess.run(forward, cwd=source, capture_output=True).returncode == 0:
        run(['git', 'apply', str(patch)], source)
    elif subprocess.run(reverse, cwd=source, capture_output=True).returncode != 0:
        raise RuntimeError(f'{source} differs from the pinned source and patch; use a fresh work directory')

def prepare(work):
    source = work / 'source'
    if not source.exists():
        run(['git', 'clone', '--no-checkout', LOCK['upstream'], source])
        run(['git', 'checkout', '--detach', LOCK['commit']], source)
    if output(['git', 'rev-parse', 'HEAD'], source) != LOCK['commit']:
        raise RuntimeError('Native checkout is at an unexpected commit; use a fresh work directory')
    dependencies = CPP_REL / 'dependencies'
    modules = [str(dependencies / name) for name in LOCK['submodules'] if '/' not in name]
    run(['git', 'submodule', 'update', '--init', '--recursive', '--', *modules], source)
    for name, commit in LOCK['submodules'].items():
        if output(['git', 'rev-parse', 'HEAD'], source / dependencies / name) != commit:
            raise RuntimeError(f'Submodule revision mismatch: {name}')
    apply_patch(source, ROOT / 'patches/android-host.patch')
    apply_patch(source / dependencies / 'openssl-cmake', ROOT / 'patches/openssl-download.patch')
    return source

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--work', type=Path, default=ROOT / '.build')
    parser.add_argument('--abis', nargs='+', choices=['arm64-v8a', 'armeabi-v7a'], default=['arm64-v8a', 'armeabi-v7a'])
    parser.add_argument('--jobs', type=int, default=6)
    parser.add_argument('--tests', action='store_true')
    parser.add_argument('--install', action='store_true', help='Copy stripped libraries to app/src/main/jniLibs')
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    revision = (ndk / 'source.properties').read_text()
    if f'Pkg.Revision = {LOCK["ndk_version"]}' not in revision:
        raise RuntimeError('Use the NDK revision in sources.lock.json')
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source = prepare(work)
    for abi in args.abis:
        build = work / abi
        ssl = LOCK['openssl']
        run([args.cmake, '-S', source / CPP_REL, '-B', build, '-G', 'Ninja',
             f'-DCMAKE_MAKE_PROGRAM={args.ninja}', f'-DCMAKE_TOOLCHAIN_FILE={ndk.as_posix()}/build/cmake/android.toolchain.cmake',
             f'-DANDROID_ABI={abi}', f'-DANDROID_PLATFORM=android-{LOCK["android_api"]}',
             '-DCMAKE_BUILD_TYPE=Release', '-DBOOST_USE_STATIC=ON', '-DCMAKE_DISABLE_FIND_PACKAGE_Boost=ON',
             f'-DSUNSHINE_ANDROID_OVERLAY={ROOT.as_posix()}',
             f'-DSUNSHINE_ANDROID_TESTS={"ON" if args.tests else "OFF"}',
             f'-DSUNSHINE_OPENSSL_URL=https://builds.viaduck.org/prebuilts/openssl/{ssl["version"]}/{abi}-android.tar.gz',
             f'-DSUNSHINE_OPENSSL_SHA256={ssl[abi]}'])
        run([args.cmake, '--build', build, '--parallel', args.jobs])
        if args.install:
            dest = ROOT.parent / 'app/src/main/jniLibs' / abi
            dest.mkdir(parents=True, exist_ok=True)
            shutil.copy2(build / 'libsunshine.so', dest / 'libsunshine.so')
            host_tag = 'windows-x86_64' if os.name == 'nt' else ('darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64')
            strip = ndk / 'toolchains/llvm/prebuilt' / host_tag / 'bin' / ('llvm-strip.exe' if os.name == 'nt' else 'llvm-strip')
            ssl_dir = build / 'dependencies/openssl-cmake/openssl-prefix/src/openssl/usr/local/lib'
            for lib in ['libssl.so', 'libcrypto.so']:
                shutil.copy2(ssl_dir / lib, dest / lib)
            for lib in ['libsunshine.so', 'libssl.so', 'libcrypto.so']:
                run([strip, '--strip-unneeded', dest / lib])
            hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(dest.glob('*.so'))}
            (work / f'{abi}-hashes.json').write_text(json.dumps(hashes, indent=2))
            print(f'{abi}: {hashes}', flush=True)

if __name__ == '__main__':
    main()
