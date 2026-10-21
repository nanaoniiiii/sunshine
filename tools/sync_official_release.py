"""Stage the newest public GitHub release on the existing official Worker.

Run after website prepare-assets.py. No DNS, routes, triggers or domains writes.
Without --deploy this only prepares local artifacts. Credentials stay in gh/Wrangler.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

REPO = 'nanaoniiiii/sunshine'
ORIGIN = 'https://nanaoni.tech'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'universal')


def run(args, cwd=None):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError(result.stderr[-3000:] + result.stdout[-3000:])
    return result.stdout


def version(tag):
    from datetime import date
    m = re.fullmatch(r'v(\d{4})\.(\d{2})\.(\d{2})\.(\d{1,6})', tag)
    if not m:
        return None
    y, mo, d, revision = map(int, m.groups())
    return date(y, mo, d), revision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment-dir', type=Path, required=True)
    parser.add_argument('--apk-dir', type=Path, help='Reuse verified release APKs here; otherwise download using gh')
    parser.add_argument('--deploy', action='store_true')
    args = parser.parse_args()
    target = args.deployment_dir.resolve()
    config_path = target / 'wrangler.jsonc'
    config = json.loads(config_path.read_text(encoding='utf-8'))
    assert config['name'] == 'nanaoni-tech' and config['account_id'] == '978c8d2a53d9513955e60fd06f7e4b4d'
    assert not any(config.get(key) for key in ('route', 'routes', 'triggers'))
    releases = json.loads(run(['gh', 'api', f'repos/{REPO}/releases?per_page=30']))
    candidates = []
    for release in releases:
        try:
            v = version(release['tag_name'])
            if v is not None and not release['draft'] and release['published_at']:
                names = {a['name'] for a in release['assets'] if a['state'] == 'uploaded'}
                if all(f"Sunshine-{release['tag_name']}-{abi}.apk" in names for abi in ABIS):
                    candidates.append((v, release))
        except (KeyError, ValueError):
            pass
    if not candidates:
        raise RuntimeError('No complete public release; drafts are never mirrored')
    release = max(candidates, key=lambda x: x[0])[1]
    tag = release['tag_name']
    package_dir = (args.apk_dir or target / 'release-downloads' / tag).resolve()
    package_dir.mkdir(parents=True, exist_ok=True)
    assets = []
    for abi in ABIS:
        name = f'Sunshine-{tag}-{abi}.apk'
        asset = next(a for a in release['assets'] if a['name'] == name)
        expected_url = f'https://github.com/{REPO}/releases/download/{tag}/{name}'
        assert asset['browser_download_url'] == expected_url
        assert 0 < asset['size'] <= 25 * 1024 * 1024
        assert re.fullmatch(r'sha256:[0-9a-fA-F]{64}', asset.get('digest') or '')
        package = package_dir / name
        if not package.exists():
            run(['gh', 'release', 'download', tag, '--repo', REPO, '--pattern', name, '--dir', str(package_dir)])
        assert package.stat().st_size == asset['size'], name
        assert 'sha256:' + hashlib.sha256(package.read_bytes()).hexdigest() == asset['digest'].lower(), name
        assets.append({'name': name, 'size': asset['size'], 'digest': asset['digest'].lower(),
                       'browser_download_url': f'{ORIGIN}/downloads/{tag}/{name}'})
    public = target / 'public'
    updates = public / 'updates'
    updates.mkdir(parents=True, exist_ok=True)
    baseline = updates / 'baseline.json'
    old = json.loads(baseline.read_text(encoding='utf-8')) if baseline.exists() else None
    # All packages are verified before changing the manifest that advertises them.
    for asset in assets:
        dest = public / 'downloads' / tag / asset['name']
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(package_dir / asset['name'], dest)
    manifest = {'schema_version': 1, 'repository': REPO, 'release': {
        'tag_name': tag, 'draft': False, 'prerelease': release['prerelease'], 'published_at': release['published_at'],
        'body': (release.get('body') or '')[:12000], 'assets': assets}}
    baseline.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    if old and old['release']['tag_name'] != tag:
        # Remove only the old APK paths from this tool's previous manifest.
        # Historical originals remain on GitHub and outside public in package_dir.
        root = (public / 'downloads').resolve()
        for asset in old['release']['assets']:
            old_tag = old['release']['tag_name']
            assert version(old_tag) is not None
            assert any(asset['name'] == f'Sunshine-{old_tag}-{abi}.apk' for abi in ABIS)
            path = (root / old_tag / asset['name']).resolve()
            assert path.is_relative_to(root) and path != root
            if path.is_file():
                path.unlink()
    for name in ('worker.mjs', 'update-service.mjs', 'release-publish.mjs'):
        shutil.copyfile(Path(__file__).resolve().parents[1] / 'website' / name, target / name)
    config.update(main='./worker.mjs')
    config['assets'].update(binding='ASSETS', run_worker_first=['/updates/*', '/downloads/*'])
    config_path.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
    headers_path = public / '_headers'
    headers = headers_path.read_text(encoding='utf-8').split('# BEGIN sunshine updates')[0].rstrip()
    headers += '\n\n# BEGIN sunshine updates\n/updates/*\n  Cache-Control: no-store\n  Access-Control-Allow-Origin: *\n'
    headers += '/downloads/*\n  Content-Type: application/vnd.android.package-archive\n  Cache-Control: public, max-age=604800\n  X-Content-Type-Options: nosniff\n'
    headers_path.write_text(headers, encoding='utf-8')
    receipt = {'release': tag, 'prerelease': release['prerelease'], 'assets': assets, 'published': False,
               'dns_mutations': False, 'domain_mutations': False}
    if args.deploy:
        npx = shutil.which('npx.cmd') or shutil.which('npx')
        previous = json.loads(run([npx, '--yes', 'wrangler', 'deployments', 'list', '--json'], cwd=target))
        receipt['previous_deployment'] = max(previous, key=lambda d: d['created_on'])
        uploaded = run([npx, '--yes', 'wrangler', 'versions', 'upload', '--tag', 'official-updates',
                        '--message', f'Official latest APK and updater: {tag}'], cwd=target)
        match = re.search(r'(?:Worker )?Version ID:\s*([0-9a-f-]{36})', uploaded)
        if not match:
            raise RuntimeError('Version uploaded but ID could not be parsed: ' + uploaded[-3000:])
        version_id = match.group(1)
        receipt['worker_version_id'] = version_id
        receipt_path = target / 'apk-publication-receipt.json'
        receipt_path.write_text(json.dumps(receipt, indent=2), encoding='utf-8')
        run([npx, '--yes', 'wrangler', 'versions', 'deploy', f'{version_id}@100%', '--yes',
             '--message', f'Activate official update downloads {tag}; preserve domains'], cwd=target)
        deployed = max(json.loads(run([npx, '--yes', 'wrangler', 'deployments', 'list', '--json'], cwd=target)), key=lambda d: d['created_on'])
        assert deployed['versions'] == [{'version_id': version_id, 'percentage': 100}]
        receipt.update(published=True, deployment=deployed)
    (target / 'apk-publication-receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
