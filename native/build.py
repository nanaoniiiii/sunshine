"""Build official Sunshine with this project's independent Android adapters."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
LOCK = json.loads((ROOT / 'sources.lock.json').read_text())

def run(args, cwd=None):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)

def output(args, cwd):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()

def checkout(url, commit, dest):
    if not dest.exists():
        run(['git', 'clone', '--no-checkout', url, dest])
        run(['git', 'checkout', '--detach', commit], dest)
    if output(['git', 'rev-parse', 'HEAD'], dest) != commit:
        raise RuntimeError(f'Unexpected revision: {dest}; use a fresh work directory')
    if output(['git', 'status', '--porcelain', '--untracked-files=no'], dest):
        raise RuntimeError(f'Source changes detected: {dest}')

def archive(url, digest, dest, downloads):
    marker = dest / '.archive-sha256'
    if marker.exists() and marker.read_text() == digest:
        return
    downloads.mkdir(parents=True, exist_ok=True)
    cached = downloads / (digest + '.tar.gz')
    if not cached.exists():
        print(f'Download {url}', flush=True)
        partial = cached.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as out:
            shutil.copyfileobj(response, out)
        partial.replace(cached)
    if hashlib.sha256(cached.read_bytes()).hexdigest() != digest:
        raise RuntimeError(f'Archive digest mismatch: {url}')
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(cached) as source:
        source.extractall(dest, filter='data')
    marker.write_text(digest)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--work', type=Path, default=ROOT / '.official-build')
    parser.add_argument('--abis', nargs='+', choices=['arm64-v8a', 'armeabi-v7a'], default=['arm64-v8a', 'armeabi-v7a'])
    parser.add_argument('--jobs', type=int, default=6)
    parser.add_argument('--tests', action='store_true')
    parser.add_argument('--install', action='store_true')
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    if f'Pkg.Revision = {LOCK["ndk_version"]}' not in (ndk / 'source.properties').read_text():
        raise RuntimeError('Use the NDK revision in sources.lock.json')
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    upstream = work / 'upstream'
    checkout(LOCK['upstream'], LOCK['commit'], upstream)
    for name, revision in LOCK['submodules'].items():
        parent = upstream if name.count('/') == 1 else upstream / Path(name).parent
        module = name if name.count('/') == 1 else Path(name).name
        run(['git', 'submodule', 'update', '--init', '--', module], parent)
        if output(['git', 'rev-parse', 'HEAD'], upstream / name) != revision:
            raise RuntimeError(f'Submodule revision mismatch: {name}')
    opus = work / 'opus'
    checkout(LOCK['opus']['url'], LOCK['opus']['commit'], opus)
    from android.prepare_source import prepare
    source = work / 'source'
    prepare(upstream, source, ROOT / 'android')
    vendor = work / 'vendor'
    boost = LOCK['boost']
    boost_dest = vendor / 'boost'
    archive(boost['url'], boost['sha256'], boost_dest, work / 'downloads')
    boost_source = next(p.parent for p in boost_dest.glob('*/CMakeLists.txt'))
    host_tag = 'windows-x86_64' if os.name == 'nt' else ('darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64')
    strip = ndk / 'toolchains/llvm/prebuilt' / host_tag / 'bin' / ('llvm-strip.exe' if os.name == 'nt' else 'llvm-strip')
    for abi in args.abis:
        ssl = LOCK['openssl']
        ssl_dest = vendor / 'openssl' / abi
        archive(f'https://builds.viaduck.org/prebuilts/openssl/{ssl["version"]}/{abi}-android.tar.gz', ssl[abi], ssl_dest, work / 'downloads')
        ssl_prefix = ssl_dest / f'{abi}-android/usr/local'
        build = work / abi
        run([args.cmake, '-S', ROOT / 'android', '-B', build, '-G', 'Ninja',
             f'-DCMAKE_MAKE_PROGRAM={args.ninja}', f'-DCMAKE_TOOLCHAIN_FILE={ndk.as_posix()}/build/cmake/android.toolchain.cmake',
             f'-DANDROID_ABI={abi}', f'-DANDROID_PLATFORM=android-{LOCK["android_api"]}', '-DANDROID_STL=c++_static',
             '-DCMAKE_BUILD_TYPE=Release', f'-DSUNSHINE_SOURCE_DIR={source.as_posix()}',
             f'-DOPUS_SOURCE_DIR={opus.as_posix()}', f'-DBOOST_SOURCE_DIR={boost_source.as_posix()}',
             f'-DSSL_PREFIX={ssl_prefix.as_posix()}',
             f'-DSUNSHINE_ANDROID_TESTS={"ON" if args.tests else "OFF"}'])
        run([args.cmake, '--build', build, '--parallel', args.jobs])
        if args.install:
            dest = ROOT.parent / 'app/src/main/jniLibs' / abi
            dest.mkdir(parents=True, exist_ok=True)
            shutil.copy2(build / 'libsunshine.so', dest / 'libsunshine.so')
            shutil.copy2(build / 'libsunshine_controller.so', dest / 'libsunshine_controller.so')
            for lib in ['libssl.so', 'libcrypto.so']:
                shutil.copy2(ssl_prefix / 'lib' / lib, dest / lib)
            for lib in ['libsunshine.so', 'libsunshine_controller.so', 'libssl.so', 'libcrypto.so']:
                run([strip, '--strip-unneeded', dest / lib])
            hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(dest.glob('*.so'))}
            (work / f'{abi}-hashes.json').write_text(json.dumps(hashes, indent=2))
            print(f'{abi}: {hashes}', flush=True)

if __name__ == '__main__':
    main()
