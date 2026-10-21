import {createHash, createHmac} from 'node:crypto';
import {ORIGIN, REPO, selectRelease, fileKey, manifestKey} from './update-service.mjs';
export const apk = new TextEncoder().encode('verified test package');
export const digest = 'sha256:' + createHash('sha256').update(apk).digest('hex');
export const secret = 'unit-test-only-secret-'.repeat(3);
export function release(tag = 'v2026.10.17.3') {
  return {tag_name: tag, draft: false, prerelease: true, published_at: '2026-10-17T00:00:00Z', body: 'notes',
    assets: ['arm64-v8a', 'armeabi-v7a', 'universal'].map(abi => {
      const name = `Sunshine-${tag}-${abi}.apk`;
      return {name, size: apk.length, state: 'uploaded', digest, browser_download_url: `https://github.com/${REPO}/releases/download/${tag}/${name}`};
    })};
}
export function storage() {
  const entries = new Map();
  return {entries,
    async get(key, type) {
      const value = entries.get(key); if (value == null) return null;
      if (type === 'json') return JSON.parse(value);
      if (type === 'stream') return new Response(typeof value === 'string' ? value : value.slice()).body;
      return value;
    },
    async put(key, value) {entries.set(key, typeof value === 'string' ? value : new Uint8Array(value).slice());},
    async delete(key) {entries.delete(key);},
    async list({prefix}) {return {keys: [...entries.keys()].filter(k => k.startsWith(prefix)).map(name => ({name})), list_complete: true};}
  };
}
export function environment(kv = storage()) {
  return {RELEASES: kv, RELEASE_SYNC_SECRET: secret, ASSETS: {async fetch(request) {
    if (new URL(request.url).pathname === '/updates/baseline.json') return Response.json(selectRelease([release()]));
    if (request.headers.has('If-Match')) return new Response(null, {status: 412});
    return new Response(apk, {headers: {'Content-Type': 'application/vnd.android.package-archive'}});
  }}};
}
export function saved(env, tag) {
  const manifest = selectRelease([release(tag)]);
  env.RELEASES.entries.set('latest', JSON.stringify(manifest));
  env.RELEASES.entries.set(manifestKey(tag), JSON.stringify(manifest));
  for (const asset of manifest.release.assets) env.RELEASES.entries.set(fileKey(tag, asset.name), apk.slice());
  return manifest;
}
export function signed(path, method, value, {stamp = Math.floor(Date.now() / 1000), digestOverride} = {}) {
  const body = value instanceof Uint8Array ? value : new TextEncoder().encode(JSON.stringify(value));
  const hash = digestOverride || createHash('sha256').update(body).digest('hex');
  const signature = createHmac('sha256', secret).update(`${method}\n${path}\n${stamp}\n${hash}`).digest('hex');
  return new Request(ORIGIN + path, {method, body, headers: {'X-Sunshine-Timestamp': String(stamp),
    'X-Sunshine-Content-SHA256': hash, 'X-Sunshine-Signature': signature}});
}
