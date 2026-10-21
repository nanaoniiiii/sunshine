"""Push a complete public GitHub release to the official site's authenticated API.

Used by GitHub Actions at release publication. Website visitors never query GitHub.
The narrowly scoped sync secret is read from the environment and never printed.
"""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

REPO = 'nanaoniiiii/sunshine'
ORIGIN = 'https://nanaoni.tech'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'universal')
MAX_APK = 25 * 1024 * 1024


def publish_request(path, method, body, secret, content_type):
    digest = hashlib.sha256(body).hexdigest()
    for attempt, delay in enumerate((0, 2, 5, 15, 30, 60)):
        if delay:
            time.sleep(delay)
        stamp = str(int(time.time()))
        signed = f'{method}\n{path}\n{stamp}\n{digest}'.encode()
        signature = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        request = urllib.request.Request(ORIGIN + path, data=body, method=method, headers={
            'User-Agent': 'Sunshine-Release-Push/1', 'Content-Type': content_type,
            'X-Sunshine-Timestamp': stamp, 'X-Sunshine-Content-SHA256': digest,
            'X-Sunshine-Signature': signature})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (409, 429, 500, 502, 503, 504) or attempt == 5:
                raise RuntimeError(f'Official site publication failed: HTTP {error.code}') from None
        except (urllib.error.URLError, TimeoutError):
            if attempt == 5:
                raise RuntimeError('Official site publication connection failed') from None
        print(f'Retrying official upload ({attempt + 1}/5)', flush=True)


def main():
    secret = os.environ.get('OFFICIAL_RELEASE_SYNC_SECRET', '')
    if len(secret) < 32:
        raise RuntimeError('OFFICIAL_RELEASE_SYNC_SECRET is not configured')
    event_path = os.environ.get('GITHUB_EVENT_PATH')
    event = json.loads(Path(event_path).read_text()) if event_path else {}
    tag = os.environ.get('RELEASE_TAG') or event.get('release', {}).get('tag_name')
    if not re.fullmatch(r'v\d{4}\.\d{2}\.\d{2}\.\d{1,6}', tag or ''):
        raise RuntimeError('A valid public release tag is required')
    if event.get('action') in ('unpublished', 'deleted'):
        print(json.dumps(publish_request('/updates/publish/retract', 'POST', json.dumps({'tag': tag}).encode(), secret, 'application/json')))
        return
    headers = {'User-Agent': 'Sunshine-Release-Push/1', 'Accept': 'application/vnd.github+json'}
    if os.environ.get('GITHUB_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GITHUB_TOKEN']
    request = urllib.request.Request(f'https://api.github.com/repos/{REPO}/releases/tags/{tag}', headers=headers)
    with urllib.request.urlopen(request, timeout=45) as response:
        release = json.load(response)
    if release['draft'] or not release.get('published_at') or release['tag_name'] != tag:
        raise RuntimeError('Only a public release can be copied to the official site')
    assets = []
    with tempfile.TemporaryDirectory(prefix='sunshine-release-push-') as temporary:
        packages = []
        for abi in ABIS:
            name = f'Sunshine-{tag}-{abi}.apk'
            matches = [asset for asset in release['assets'] if asset['name'] == name and asset['state'] == 'uploaded']
            if len(matches) != 1:
                raise RuntimeError(f'Release is incomplete: {name}')
            asset = matches[0]
            source_url = f'https://github.com/{REPO}/releases/download/{tag}/{name}'
            if asset['browser_download_url'] != source_url or not 0 < asset['size'] <= MAX_APK or not re.fullmatch(r'sha256:[a-fA-F0-9]{64}', asset.get('digest') or ''):
                raise RuntimeError(f'Untrusted release asset: {name}')
            package = Path(temporary) / name
            # Public binary requests carry no GitHub Authorization header across redirects.
            with urllib.request.urlopen(source_url, timeout=120) as response, package.open('wb') as output:
                total = 0
                while chunk := response.read(256 * 1024):
                    total += len(chunk)
                    if total > MAX_APK:
                        raise RuntimeError('Release asset exceeds the package limit')
                    output.write(chunk)
            if package.stat().st_size != asset['size'] or 'sha256:' + hashlib.sha256(package.read_bytes()).hexdigest() != asset['digest'].lower():
                raise RuntimeError(f'Release asset checksum mismatch: {name}')
            packages.append((abi, package))
            assets.append({'name': name, 'size': asset['size'], 'digest': asset['digest'].lower(),
                           'browser_download_url': f'{ORIGIN}/downloads/{tag}/{name}'})
        # Verify the entire release before sending any package or advertising its manifest.
        for abi, package in packages:
            result = publish_request(f'/updates/publish/{tag}/{abi}', 'PUT', package.read_bytes(), secret, 'application/vnd.android.package-archive')
            print(f"Uploaded {package.name}: {result['size']} bytes, {result['digest']}", flush=True)
        manifest = {'schema_version': 1, 'repository': REPO, 'release': {
            'tag_name': tag, 'draft': False, 'prerelease': bool(release['prerelease']),
            'published_at': release['published_at'], 'body': (release.get('body') or '')[:12000], 'assets': assets}}
        result = publish_request('/updates/publish', 'POST', json.dumps(manifest, ensure_ascii=False).encode(), secret, 'application/json')
        if result.get('published') != tag or not re.fullmatch(r'v\d{4}\.\d{2}\.\d{2}\.\d{1,6}', result.get('latest') or ''):
            raise RuntimeError('Official site did not acknowledge the complete release')
        print(json.dumps(result), flush=True)
    # The authenticated commit validates every stored receipt before acknowledging
    # publication. Anonymous clients can have separate CDN bot policies and must
    # not turn a completed upload into a failed release or trigger duplicate work.
    print('Verified authenticated official-site publication: ' + result['published'])


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
